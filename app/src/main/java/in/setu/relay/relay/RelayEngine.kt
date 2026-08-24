package `in`.setu.relay.relay

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import androidx.core.content.ContextCompat
import `in`.setu.relay.crypto.Identity
import `in`.setu.relay.crypto.KeyBook
import `in`.setu.relay.radio.beacon.BeaconAdvertiser
import `in`.setu.relay.radio.beacon.BeaconScanner
import `in`.setu.relay.radio.beacon.PresenceAdvertiser
import `in`.setu.relay.store.MessageStore
import `in`.setu.relay.store.StoredMessage
import `in`.setu.relay.wire.AdvertState
import `in`.setu.relay.wire.Bodies
import `in`.setu.relay.wire.Codec
import `in`.setu.relay.wire.Envelope
import `in`.setu.relay.wire.MsgType
import `in`.setu.relay.wire.Proto
import `in`.setu.relay.wire.Status
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * The relay loop from docs/01-architecture.md. Everything funnels through
 * [MessageStore]; this class is what moves bytes between the store and the
 * radio, and it owns no UI.
 *
 * Single-threaded by construction: every mutation runs on [worker], so the
 * store, the scheduler and the radio slot never race.
 */
class RelayEngine(context: Context) {

    private val app = context.applicationContext
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "setu-relay").apply { isDaemon = true }
    }
    private val scope = CoroutineScope(SupervisorJob() + worker.asCoroutineDispatcher())

    val store = MessageStore(app)
    val identity: Identity = Identity.get(app)
    val keyBook = KeyBook(app).also { it.learn(identity.publicKey) }
    private val scheduler = BackoffScheduler()

    private val adapter: BluetoothAdapter? =
        (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val advertiser = BeaconAdvertiser(adapter)
    private val presence = PresenceAdvertiser(adapter)
    private val scanner = BeaconScanner(
        adapter = adapter,
        onEnvelope = { envelope, rssi -> onEnvelope(envelope, rssi) },
        onPresence = { keyId, _ -> onPresence(keyId) },
    )

    /**
     * Restarts the radio when Bluetooth is toggled off and on. Without this the
     * scan died with the adapter and never came back, which looked exactly like
     * "sometimes it stops seeing other phones".
     */
    private val adapterWatcher = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_ON -> if (running) {
                    Log.i(TAG, "bluetooth back on, restarting radio")
                    scanner.restart()
                    presence.start(identity.keyId)
                    publish()
                }

                BluetoothAdapter.STATE_TURNING_OFF -> {
                    scanner.stop()
                    presence.stop()
                    publish()
                }
            }
        }
    }

    private val _state = MutableStateFlow(RelayState(identityKeyId = identity.keyIdHex))
    val state: StateFlow<RelayState> = _state

    private var loop: Job? = null
    private var housekeeping: Job? = null

    @Volatile
    private var running = false

    /** Last shared-slot presence burst, single-advertising-set devices only. */
    private var lastSharedPresenceMs = 0L

    // ------------------------------------------------------------- lifecycle

    fun start() {
        if (running) return
        running = true
        runCatching {
            ContextCompat.registerReceiver(
                app,
                adapterWatcher,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
        scope.launch {
            restoreSchedules()
            val ok = scanner.start()
            // Presence runs for as long as the relay does, independent of
            // whether there is anything in the store to advertise. This is what
            // makes two idle phones able to see each other at all.
            //
            // On a chipset with only one advertising slot, running it
            // continuously would starve the beacon plane, so there it is
            // time-shared from the idle branch of advertisingLoop instead.
            if (presence.multipleSetsSupported) {
                presence.start(identity.keyId)
            } else {
                Log.i(TAG, "single advertising slot; presence will time-share with beacons")
            }
            publish(scanningOverride = ok)
            loop = scope.launch { advertisingLoop() }
            housekeeping = scope.launch { housekeepingLoop() }
        }
    }

    fun stop() {
        running = false
        loop?.cancel()
        housekeeping?.cancel()
        runCatching { app.unregisterReceiver(adapterWatcher) }
        scanner.shutdown()
        presence.stop()
        advertiser.stop()
        publish()
    }

    fun shutdown() {
        stop()
        scope.launch { store.close() }
        worker.shutdown()
    }

    private fun restoreSchedules() {
        val now = TimeSource.monotonicMs()
        val wall = TimeSource.wallMs()
        scheduler.clear()
        for (m in store.liveMessages()) {
            val remaining = m.expiresAt() - wall
            if (remaining <= 0) continue
            scheduler.restore(
                msgId = m.idHex,
                tier = m.tier,
                // The device may have been off for hours. Treat a restored
                // message as freshly stored so it gets a real advertising
                // window rather than starting at the 30 s whisper.
                storedAtMs = now,
                expiresAtMs = now + remaining,
                nowMs = now,
            )
        }
        Log.i(TAG, "restored ${scheduler.size()} schedules")
    }

    // ------------------------------------------------------------- ingestion

    /** A locally created message. Stored, tracked and advertised immediately. */
    fun submitLocal(envelope: Envelope) {
        scope.launch {
            val encoded = envelope.encode()
            val nowMono = TimeSource.monotonicMs()
            store.markSeen(envelope.msgId, TimeSource.wallMs())
            store.insert(envelope, encoded, TimeSource.wallMs(), isMine = true)
            scheduler.track(
                envelope.msgId.let { Codec.hex(it) },
                envelope.tier,
                nowMono,
                nowMono + envelope.ttlMillis,
            )
            Log.i(TAG, "originated ${MsgType.name(envelope.type)} ${Codec.hex(envelope.msgId)}")
            publish()
        }
    }

    /**
     * A presence beacon off the air: a Setu phone is nearby but has said nothing
     * else. This is the only path by which an idle peer becomes visible, and its
     * absence was the detection bug — see PresenceAdvertiser.
     */
    private fun onPresence(originKeyId: ByteArray) {
        if (originKeyId.contentEquals(identity.keyId)) return  // our own echo
        scope.launch {
            store.touchPeer(originKeyId, TimeSource.wallMs())
            publish()
        }
    }

    /** A beacon off the air. Runs on the scanner's thread; hands off immediately. */
    private fun onEnvelope(encoded: ByteArray, rssi: Int) {
        scope.launch { ingest(encoded, rssi) }
    }

    private fun ingest(encoded: ByteArray, rssi: Int) {
        val wall = TimeSource.wallMs()

        // Structural validation first — it is cheap and it is what protects the
        // store from malformed input and from a signature check we cannot do.
        val envelope = Envelope.decodeOrNull(encoded) ?: run {
            _state.value = _state.value.copy(malformedDropped = _state.value.malformedDropped + 1)
            return
        }

        // Dedupe before verification. A replayed beacon must stay cheap.
        val first = store.markSeen(envelope.msgId, wall)
        if (!first) {
            _state.value = _state.value.copy(duplicatesHeard = _state.value.duplicatesHeard + 1)
            return
        }
        if (envelope.originKeyId.contentEquals(identity.keyId)) return  // our own echo

        store.touchPeer(envelope.originKeyId, wall)

        val verdict = Messages.verify(envelope, encoded, keyBook.lookup(envelope.originKeyId))
        if (verdict == Messages.VerifyResult.INVALID || verdict == Messages.VerifyResult.BAD_KEY_ID) {
            Log.w(TAG, "dropping ${Codec.hex(envelope.msgId)}: $verdict")
            _state.value = _state.value.copy(signatureDropped = _state.value.signatureDropped + 1)
            return
        }

        if (envelope.type == MsgType.RECEIPT) {
            applyReceipt(envelope, wall)
        }

        val hop = envelope.hopCount + 1
        val forwarded = envelope.withHopCount(hop)
        store.insert(forwarded, forwarded.encode(), wall, isMine = false)
        store.evictIfFull()

        // At the hop limit a message is stored but not repeated.
        if (hop >= RelayParams.HOP_LIMIT) {
            store.setAdvertState(envelope.msgId, AdvertState.DONE)
            Log.i(TAG, "hop limit reached for ${Codec.hex(envelope.msgId)}")
        } else {
            val nowMono = TimeSource.monotonicMs()
            val expiresIn = TimeSource.expiryFromClaim(envelope.createdAt, envelope.ttlHours, wall) - wall
            scheduler.track(Codec.hex(envelope.msgId), envelope.tier, nowMono, nowMono + expiresIn)
        }

        // A relay that gives no feedback destroys trust. Acknowledge the urgent
        // tiers so the origin can see CARRIED rather than guessing.
        if (envelope.tier <= 1) emitCarriedReceipt(envelope.msgId)

        Log.i(
            TAG,
            "stored ${MsgType.name(envelope.type)} ${Codec.hex(envelope.msgId)} " +
                "hop=$hop rssi=$rssi verify=$verdict",
        )
        publish()
    }

    private fun applyReceipt(envelope: Envelope, wall: Long) {
        val refId = Bodies.receiptRefMsgId(envelope.sealedBody)
        val kind = Bodies.receiptKind(envelope.sealedBody)
        val target = store.get(refId) ?: return
        if (!target.isMine) return
        store.addReceipt(refId, envelope.originKeyId, wall, kind)
        val status = if (kind == Bodies.RECEIPT_DELIVERED) Status.DELIVERED else Status.CARRIED
        if (target.status < status) store.setStatus(refId, status)
        Log.i(TAG, "receipt for ${Codec.hex(refId)} kind=$kind from ${Codec.hex(envelope.originKeyId)}")
    }

    private fun emitCarriedReceipt(refMsgId: ByteArray) {
        val receipt = Messages.receipt(
            identity, refMsgId, Bodies.RECEIPT_CARRIED, TimeSource.wallSeconds(),
        )
        val encoded = receipt.encode()
        store.markSeen(receipt.msgId, TimeSource.wallMs())
        store.insert(receipt, encoded, TimeSource.wallMs(), isMine = true)
        val nowMono = TimeSource.monotonicMs()
        scheduler.track(Codec.hex(receipt.msgId), receipt.tier, nowMono, nowMono + receipt.ttlMillis)
    }

    // ----------------------------------------------------------------- loops

    private suspend fun advertisingLoop() {
        while (running) {
            val now = TimeSource.monotonicMs()
            val battery = batteryPercent()
            val neighbours = store.neighbourCount(TimeSource.wallMs(), RelayParams.NEIGHBOUR_WINDOW_MS)
            val due = scheduler.due(
                nowMs = now,
                batteryPct = battery,
                duplicatesOf = { hex -> store.duplicateCount(Codec.unhex(hex)) },
                neighbourCount = neighbours,
            )

            if (due.isEmpty()) {
                // Nothing to relay. On a single-slot chipset this idle time is
                // the only chance presence gets, so use it.
                if (!presence.multipleSetsSupported) sharedSlotPresence(now)
                publish()
                delay(scheduler.nextWakeMs(now).coerceIn(200L, 2_000L))
                continue
            }

            // One radio slot, highest priority first.
            for (entry in due) {
                if (!running) break
                val stored = store.get(Codec.unhex(entry.msgId))
                if (stored == null) {
                    scheduler.remove(entry.msgId)
                    continue
                }
                store.setAdvertState(stored.msgId, AdvertState.ADVERTISING)
                _state.value = _state.value.copy(advertisingId = entry.msgId)
                // Burst length tracks the gap between bursts: a message that
                // only speaks once every 30 s has to speak for long enough to
                // land inside a peer's scan window. See RelayParams.burstMsFor.
                val interval = scheduler.intervalFor(now - entry.storedAtMs)
                awaitBurst(stored.envelope, RelayParams.burstMsFor(interval))
                scheduler.onAdvertised(entry, TimeSource.monotonicMs())
                store.setAdvertState(stored.msgId, AdvertState.BACKOFF)
                _state.value = _state.value.copy(advertisingId = null)
            }
            publish()
            delay(SLOT_GAP_MS)
        }
    }

    /**
     * One presence burst on the shared advertising slot, for chipsets that
     * cannot hold two sets. Long enough to overlap a peer's scan window, rare
     * enough to leave the slot free for real traffic.
     */
    private suspend fun sharedSlotPresence(nowMs: Long) {
        if (nowMs - lastSharedPresenceMs < SHARED_PRESENCE_EVERY_MS) return
        lastSharedPresenceMs = nowMs
        if (!presence.start(identity.keyId)) return
        delay(RelayParams.BURST_MAX_MS)
        presence.stop()
    }

    private suspend fun awaitBurst(
        envelope: ByteArray,
        burstMs: Long,
    ) = suspendCancellableCoroutine { cont ->
        var resumed = false
        advertiser.burst(envelope, burstMs) {
            if (!resumed) {
                resumed = true
                if (cont.isActive) cont.resume(Unit)
            }
        }
    }

    private suspend fun housekeepingLoop() {
        while (running) {
            val wall = TimeSource.wallMs()
            store.reapExpired(wall)
            store.purgeSeen(wall)
            store.purgePeers(wall)
            store.evictIfFull()
            publish()
            delay(HOUSEKEEPING_MS)
        }
    }

    // ----------------------------------------------------------------- state

    /**
     * Android gates BLE scan *results* behind the system Location toggle, not
     * just the permission. With it off, startScan succeeds and no result ever
     * arrives — the single most confusing failure mode in field testing, so the
     * home screen says it out loud.
     */
    private fun locationServicesEnabled(): Boolean = try {
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
        when {
            lm == null -> true
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P -> lm.isLocationEnabled
            else -> lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
        }
    } catch (_: Throwable) {
        true
    }

    private fun batteryPercent(): Int {
        val intent: Intent? = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level < 0 || scale <= 0) 100 else (level * 100 / scale)
    }

    fun publish(scanningOverride: Boolean? = null) {
        val wall = TimeSource.wallMs()
        val mine = store.myMessages()
            .filter { it.type == MsgType.SOS || it.type == MsgType.CHECK_IN }
            .map { it.toUi(store.carrierCount(it.msgId)) }
        _state.value = _state.value.copy(
            neighbours = store.neighbourCount(wall, RelayParams.NEIGHBOUR_WINDOW_MS),
            carrying = store.countCarriedForOthers(),
            totalStored = store.countAll(),
            myMessages = mine,
            scanning = scanningOverride ?: scanner.scanning,
            extendedAdvertising = advertiser.extendedSupported,
            bluetoothOn = adapter?.isEnabled == true,
            locationServicesOn = locationServicesEnabled(),
            packetsSeen = scanner.packetsSeen,
            fragmentsSeen = scanner.fragmentsSeen,
            reassembled = scanner.reassembled,
            presenceSeen = scanner.presenceSeen,
            burstsSent = advertiser.burstsSent,
            knownKeys = keyBook.size(),
            batteryPct = batteryPercent(),
            wallClockJumped = TimeSource.wallClockJumped,
            hardwareBackedKey = identity.hardwareBacked,
            radioError = scanner.lastError ?: advertiser.lastError,
        )
    }

    fun carriedForOthers(): List<CarriedItem> = store.carriedForOthers().map {
        CarriedItem(
            idHex = it.idHex,
            type = it.type,
            tier = it.tier,
            hopCount = it.hopCount,
            sizeBytes = it.envelope.size,
            receivedAt = it.receivedAt,
            expiresAt = it.expiresAt(),
        )
    }

    private fun StoredMessage.toUi(carriers: Int) = MyMessage(
        idHex = idHex,
        type = type,
        status = status,
        carriers = carriers,
        hopCount = hopCount,
        createdAt = createdAt,
        expiresAt = expiresAt(),
        sealedBody = envelope.copyOfRange(
            Proto.OFF_SEALED_BODY,
            Proto.OFF_SEALED_BODY + Proto.LEN_SEALED_BODY,
        ),
    )

    companion object {
        private const val TAG = "SetuRelay"
        private const val SLOT_GAP_MS = 120L
        private const val HOUSEKEEPING_MS = 60_000L

        /**
         * How often presence gets the shared advertising slot on chipsets that
         * cannot hold two sets. Five seconds keeps an idle phone discoverable
         * within a few scan windows while leaving the slot free almost always.
         */
        private const val SHARED_PRESENCE_EVERY_MS = 5_000L

        // Holds the application context, not an Activity, and lives as long as
        // the process by design — the relay must outlive every screen.
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: RelayEngine? = null

        fun get(context: Context): RelayEngine {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                val e = RelayEngine(context.applicationContext)
                instance = e
                return e
            }
        }
    }
}
