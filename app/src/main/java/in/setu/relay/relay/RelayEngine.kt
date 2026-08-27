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
import `in`.setu.relay.crypto.Keys
import `in`.setu.relay.crypto.RescuerKey
import `in`.setu.relay.crypto.SealedBox
import `in`.setu.relay.crypto.Signer
import `in`.setu.relay.crypto.KeyBook
import `in`.setu.relay.radio.beacon.BeaconAdvertiser
import `in`.setu.relay.radio.beacon.BeaconScanner
import `in`.setu.relay.radio.beacon.PresenceAdvertiser
import `in`.setu.relay.radio.bulk.BulkSync
import `in`.setu.relay.radio.bulk.GattServer
import `in`.setu.relay.store.HopStore
import `in`.setu.relay.store.RecordStore
import `in`.setu.relay.store.MessageStore
import `in`.setu.relay.store.StoredMessage
import `in`.setu.relay.wire.AdvertState
import `in`.setu.relay.wire.Announcement
import `in`.setu.relay.wire.Bodies
import `in`.setu.relay.wire.Codec
import `in`.setu.relay.wire.Envelope
import `in`.setu.relay.wire.HopTrail
import `in`.setu.relay.wire.MsgType
import `in`.setu.relay.wire.Proto
import `in`.setu.relay.wire.SosDetail
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
        onPresence = { keyId, device, bulk, rssi ->
            lastPresenceRssi = rssi
            onPresence(keyId, device, bulk)
        },
    )

    // ------------------------------------------------------------ bulk plane
    //
    // Records are sealed to the backend key, so this device carries other
    // people's surveys without being able to read one. The store is shared with
    // MessageStore: one database file, one write lock.
    val records = RecordStore(store.database)

    /** Every observation of every message: hop trails and silence detection. */
    val hops = HopStore(store.database)

    /** Makes this phone findable when a rescuer pings it. */
    val findMe = FindMe(app)

    /** Proximity to nearby phones, for a responder sweeping ground. */
    val scanner2 = Scanner()

    private val locator = Locator(app)

    /**
     * Last position this phone knew about, refreshed opportunistically.
     *
     * Cached rather than fetched per message: a hop observation must not block
     * ingest on a GPS fix, and a position from a few minutes ago is exactly as
     * useful for a search box as one from this second.
     */
    @Volatile
    private var lastLat = Double.NaN

    @Volatile
    private var lastLon = Double.NaN

    @Volatile
    private var lastFixAt = 0L

    /** True while the battery is low enough that only a position beacon runs. */
    @Volatile
    var lastBreath = false
        private set

    /** RSSI of the presence packet currently being handled. */
    @Volatile
    private var lastPresenceRssi = 0

    /** When this phone last answered a find ping. Rate limits an unauthenticated alarm. */
    @Volatile
    private var lastScreamAt = 0L

    private val gattServer = GattServer(
        context = app,
        heldRecordIds = { records.allIds() },
        onRecord = { id, sealed -> acceptRecord(id, sealed) },
    )

    private val bulkSync = BulkSync(
        context = app,
        heldRecordIds = { records.allIds() },
        sealedFor = { records.sealedFor(it) },
        onSessionEnd = { publish() },
    )

    /**
     * Bulk sessions block on GATT callbacks for up to a few seconds, so they
     * cannot share the single relay thread — parking it would stall the
     * advertising loop, and the beacon plane carries SOS. One extra thread, and
     * BulkSync itself allows only one session at a time.
     */
    private val bulkWorker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "setu-bulk").apply { isDaemon = true }
    }

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
            // The server must be listening before presence advertises that it
            // is, or a peer connects to a service that is not there yet.
            val serverUp = gattServer.start()
            if (presence.multipleSetsSupported) {
                presence.start(identity.keyId, bulkAvailable = serverUp)
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
        gattServer.stop()
        bulkSync.forget()
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
    private fun onPresence(
        originKeyId: ByteArray,
        device: android.bluetooth.BluetoothDevice?,
        bulkAvailable: Boolean,
    ) {
        if (originKeyId.contentEquals(identity.keyId)) return  // our own echo
        val now = TimeSource.wallMs()
        // Feeds the responder scanner. Cheap and lock-free, so it happens on the
        // scan callback rather than being queued — a rescuer sweeping ground
        // needs the needle to move now, not after a thread hop.
        scanner2.observe(originKeyId, lastPresenceRssi, now)
        scope.launch {
            store.touchPeer(originKeyId, now)
            // A presence beacon is also a sighting. Recording it under a
            // synthetic message id is what lets a phone that never sent
            // anything still show up in silence detection.
            hops.observe(
                msgId = PRESENCE_MSG_ID,
                keyId = originKeyId,
                heardAt = now,
                lat = lastLat,
                lon = lastLon,
                hopCount = 0,
                rssi = lastPresenceRssi,
            )
            publish()
        }
        // A peer that says it is serving GATT is a chance to hand over records.
        // BulkSync decides whether it is worth a connection; almost always the
        // answer is no, and it returns before touching the radio.
        if (bulkAvailable && device != null) {
            bulkWorker.execute { bulkSync.onPeerSeen(device, TimeSource.wallMs()) }
        }
    }

    /**
     * A record arriving from a peer. Runs on a GATT callback thread.
     *
     * The body is opaque: it is sealed to the backend key, so this device stores
     * and forwards something it cannot read. That is the design, not a
     * limitation — a courier should not be able to read the post.
     */
    /**
     * Works out what an arriving record is.
     *
     * The push frame carries only a record id and bytes — deliberately, since a
     * relay does not need to understand a payload to carry it. But this phone
     * does need to file it correctly, so the type is inferred by trying each
     * decoder. A record that matches nothing is still stored and still relayed:
     * being unable to read something is not a reason to stop carrying it.
     */
    private fun profileOf(body: ByteArray): String = when {
        HopTrail.decodeOrNull(body) != null -> HopTrail.PROFILE_ID
        Announcement.decodeOrNull(body) != null -> Announcement.PROFILE_ID
        `in`.setu.relay.wire.SurveyRecord.decodeOrNull(body) != null ->
            `in`.setu.relay.wire.SurveyRecord.PROFILE_ID
        // Sealed bodies cannot be identified without the key, and an SOS detail
        // is the only sealed record type, so anything opaque is filed as one.
        else -> SosDetail.PROFILE_ID
    }

    private fun acceptRecord(recordId: ByteArray, sealed: ByteArray): Boolean {
        val profile = profileOf(sealed)

        // A trail is the one record type that merges rather than replaces: two
        // phones each hold a partial view of the same route, and keeping only
        // the first to arrive would throw away half the path.
        if (profile == HopTrail.PROFILE_ID) {
            HopTrail.decodeOrNull(sealed)?.let { hops.mergeTrail(it.msgId, it.hops) }
        }

        val stored = records.acceptFromPeer(
            recordId = recordId,
            profileId = profile,
            sealed = sealed,
            nowMs = TimeSource.wallMs(),
        )
        if (stored) publish()
        return stored
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

        // Every message this phone hears becomes one honest observation: I
        // heard X, at this time, at this position, at this signal strength. It
        // is what rebuilds a route afterwards, and what turns "that phone went
        // quiet" into a search box rather than a shrug. See store/HopStore.kt.
        hops.observe(
            msgId = envelope.msgId,
            keyId = identity.keyId,
            heardAt = wall,
            lat = lastLat,
            lon = lastLon,
            hopCount = hop,
            rssi = rssi,
        )
        publishTrail(envelope.msgId)

        // A relay that gives no feedback destroys trust. Acknowledge the urgent
        // tiers so the origin can see CARRIED rather than guessing.
        if (envelope.tier <= 1) emitCarriedReceipt(envelope.msgId)

        // A rescuer must be told, not left to go looking. Fires only on a phone
        // holding the rescuer key, and only for a first-seen SOS — the dedupe
        // above has already returned for anything heard before, so a message
        // arriving by four different paths still alerts once.
        if (envelope.type == MsgType.SOS) deliverToRescuer(envelope, hop)
        if (envelope.type == MsgType.FIND_PING) answerFindPing(envelope)

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

        // THE BUG THIS FIXES: nothing used to stop a message once it had
        // arrived. D4 says never go silent *before* TTL, and that is about a
        // message still looking for a route — it was never meant to keep an SOS
        // shouting for 24 hours after a rescuer confirmed receiving it. Two
        // phones on a desk therefore relayed the same call forever, which is
        // what "the hops repeat in the same pattern again and again" was.
        if (status == Status.DELIVERED) stopAdvertising(refId)
        Log.i(TAG, "receipt for ${Codec.hex(refId)} kind=$kind from ${Codec.hex(envelope.originKeyId)}")
    }

    /**
     * Handles an SOS arriving at a phone that may be a rescuer.
     *
     * A rescuer is the **destination**, not another hop. Once it holds a call it
     * can read, repeating that call achieves nothing except spending airtime an
     * SOS still looking for a route could use. So this phone stops repeating it
     * and tells the origin it arrived — which is also the only way the status
     * ladder ever reaches DELIVERED instead of sitting on CARRIED forever.
     */
    private fun deliverToRescuer(envelope: Envelope, hop: Int) {
        val keyHex = Prefs(app).rescuerKeyHex
        if (keyHex.isEmpty()) return
        val key = RescuerKey.parseOrNull(keyHex) ?: return

        // Only a call this phone can actually open counts as delivered. An SOS
        // sealed to a different rescuer's key is still just passing through.
        if (SealedBox.open(key, envelope.sealedBody) == null) return

        RescueAlert.raise(app, Codec.hex(envelope.msgId), hop)
        stopAdvertising(envelope.msgId)
        emitReceipt(envelope.msgId, Bodies.RECEIPT_DELIVERED)
        Log.i(TAG, "rescuer accepted ${Codec.hex(envelope.msgId)}; not repeating it")
    }

    /**
     * Takes a message off the air without deleting it. The in-flight burst is
     * left alone: a burst is at most 1.2 s and self-terminating, so cancelling
     * one would add radio churn to save a fraction of a second of airtime.
     */
    private fun stopAdvertising(msgId: ByteArray) {
        scheduler.remove(Codec.hex(msgId))
        store.setAdvertState(msgId, AdvertState.DONE)
    }

    // ------------------------------------------------------------- find me

    /**
     * A rescuer is asking phones to announce themselves. Scream if it is for us.
     *
     * Deliberately obeyed by **every** phone, not only ones in rescue mode: the
     * whole point is that the handset in a buried pocket belongs to an ordinary
     * person who installed this months ago and has no idea it is happening.
     */
    private fun answerFindPing(envelope: Envelope) {
        val body = envelope.sealedBody
        val forUs = Bodies.findPingIsBroadcast(body) ||
            Bodies.findPingTarget(body).contentEquals(identity.keyId)
        if (!forUs) return

        // --------------------------------------------------------------
        // SECURITY. A find ping makes a stranger's phone scream at maximum
        // volume, flash its torch and vibrate. That is a physical effect
        // triggered by an unauthenticated radio packet, and it cannot be
        // authorised today: key exchange is phase 5, so a beacon from an
        // unknown key verifies as NO_KEY and is deliberately still relayed.
        // Anything that can craft a BLE advertisement can therefore send one.
        //
        // Unmitigated that is three separate harms:
        //   - battery exhaustion, the most power-hungry combination a handset
        //     has, against an app whose real failure mode is already power (D11)
        //   - forced position disclosure: a phone hidden in a pocket announces
        //     itself on a stranger's command
        //   - trivially repeatable denial of service on every phone in range
        //
        // Until pings can be authorised, they are *rate limited* instead. The
        // checks below are ordered cheapest-first and every one of them fails
        // closed.
        // --------------------------------------------------------------

        // 1. The user can opt out entirely.
        if (!Prefs(app).rescueAlertsOn) {
            Log.i(TAG, "find ping ignored: alerts disabled by the user")
            return
        }

        // 2. A dying phone never screams. Last-breath mode exists to keep it
        //    findable for days on the battery it has left; spending that on an
        //    unauthenticated alarm would destroy the thing it is protecting.
        if (lastBreath) {
            Log.i(TAG, "find ping ignored: last-breath mode")
            return
        }

        // 3. One scream per cooldown, however many pings arrive. This is what
        //    turns a flood into a single burst.
        val now = TimeSource.wallMs()
        if (now - lastScreamAt < RelayParams.FIND_COOLDOWN_MS) {
            Log.i(TAG, "find ping ignored: within cooldown")
            return
        }
        lastScreamAt = now

        val seconds = Bodies.findPingSeconds(body)
        Log.i(TAG, "find ping accepted, screaming for ${seconds}s")
        android.os.Handler(android.os.Looper.getMainLooper()).post { findMe.start(seconds) }
        publish()
    }

    /**
     * Sends a find ping. A null [targetKeyId] means every phone in range.
     *
     * Tier 0, so it travels on the same footing as an SOS — a rescuer standing
     * over rubble is not waiting behind a survey reference.
     */
    fun sendFindPing(targetKeyId: ByteArray?, seconds: Int = 30) {
        scope.launch {
            val envelope = Messages.findPing(
                identity, targetKeyId, seconds, TimeSource.wallSeconds(),
            )
            val encoded = envelope.encode()
            store.markSeen(envelope.msgId, TimeSource.wallMs())
            store.insert(envelope, encoded, TimeSource.wallMs(), isMine = true)
            val nowMono = TimeSource.monotonicMs()
            scheduler.track(
                Codec.hex(envelope.msgId),
                envelope.tier,
                nowMono,
                nowMono + seconds * 1000L,
            )
            Log.i(TAG, "find ping sent for ${seconds}s")
            publish()
        }
    }

    /** Stops this phone screaming, for the person who has just been dug out. */
    fun silenceFindMe() {
        android.os.Handler(android.os.Looper.getMainLooper()).post { findMe.stop() }
        publish()
    }

    // --------------------------------------------------------------- trails

    /**
     * Publishes this phone's view of a message's route as a bulk record, so it
     * spreads on the next contact and the whole path can be reconstructed later.
     */
    private fun publishTrail(msgId: ByteArray) {
        val trail = hops.trailFor(msgId)
        if (trail.isEmpty()) return
        val body = HopTrail.encode(msgId, trail)
        records.putMine(trailRecordId(msgId), HopTrail.PROFILE_ID, body, TimeSource.wallMs())
    }

    /** A 16-byte record id for a trail, tagged so it cannot collide with a survey. */
    private fun trailRecordId(msgId: ByteArray): ByteArray {
        val out = ByteArray(16)
        msgId.copyInto(out, 0)
        out[8] = 0x54
        out[9] = 0x52
        return out
    }

    /** The merged route of a message, for the UI. */
    fun trailFor(msgId: ByteArray): List<HopTrail.Hop> = hops.trailFor(msgId)

    // --------------------------------------------------------- announcements

    /**
     * Publishes an announcement signed by the authority seed.
     *
     * The seed is passed in, used and dropped rather than held in a field, so a
     * memory dump of a long-running service does not hand over the ability to
     * speak for the authority.
     */
    fun publishAnnouncement(
        authoritySeed: ByteArray,
        body: String,
        severity: Int,
        category: Int,
        lat: Double,
        lon: Double,
        radiusMetres: Int,
    ) {
        scope.launch {
            val id = ByteArray(Announcement.ID_BYTES)
                .also { java.security.SecureRandom().nextBytes(it) }
            val signing = Announcement.signingBytes(
                id, TimeSource.wallSeconds(), severity, category, lat, lon, radiusMetres, body,
            )
            val signature = Signer.sign(authoritySeed, signing)
            val record = Announcement.encode(signing, signature)
            records.putMine(id, Announcement.PROFILE_ID, record, TimeSource.wallMs())

            // A beacon so phones learn one exists without waiting for a GATT
            // contact. The announcement body itself rides the bulk plane.
            val envelope = Messages.announceRef(identity, id, severity, TimeSource.wallSeconds())
            val encoded = envelope.encode()
            store.markSeen(envelope.msgId, TimeSource.wallMs())
            store.insert(envelope, encoded, TimeSource.wallMs(), isMine = true)
            val nowMono = TimeSource.monotonicMs()
            scheduler.track(
                Codec.hex(envelope.msgId),
                envelope.tier,
                nowMono,
                nowMono + MsgType.defaultTtlHours(MsgType.ANNOUNCE_REF) * 3_600_000L,
            )
            Log.i(TAG, "announcement published, ${record.size} B")
            publish()
        }
    }

    /**
     * Every announcement this phone holds, newest first, each marked with whether
     * its authority signature actually checks out.
     *
     * Unverified ones are returned rather than filtered away. In a blackout an
     * unsigned message may still be true, and hiding it is its own kind of lie —
     * but the UI must never let it look official.
     */
    fun announcements(): List<VerifiedAnnouncement> =
        records.bodiesOfProfile(Announcement.PROFILE_ID)
            .mapNotNull { Announcement.decodeOrNull(it) }
            .map {
                VerifiedAnnouncement(
                    it,
                    Signer.verify(Keys.AUTHORITY_PUBLIC, it.signedBytes, it.signature),
                )
            }
            .sortedByDescending { it.announcement.issuedAt }

    class VerifiedAnnouncement(val announcement: Announcement.Decoded, val verified: Boolean)

    // ------------------------------------------------------------ SOS detail

    /**
     * Attaches detail to an SOS already sent. Sealed to the rescuer key: "two
     * children upstairs, one unconscious" is exactly what makes a house a
     * target, and every relay carrying it is a stranger.
     */
    fun attachSosDetail(
        msgId: ByteArray,
        category: Int,
        peopleCount: Int,
        needs: Int,
        text: String,
    ) {
        scope.launch {
            val plain = SosDetail.encode(msgId, category, peopleCount, needs, text)
            val sealed = SealedBox.seal(Keys.RESCUER_PUBLIC, plain)
            records.putMine(
                SosDetail.recordIdFor(msgId),
                SosDetail.PROFILE_ID,
                sealed,
                TimeSource.wallMs(),
            )
            Log.i(TAG, "SOS detail attached, ${sealed.size} B sealed")
            publish()
        }
    }

    /** Opens every SOS detail this phone can, keyed by the msg_id it belongs to. */
    fun sosDetails(rescuerKey: ByteArray): Map<String, SosDetail.Decoded> =
        records.bodiesOfProfile(SosDetail.PROFILE_ID)
            .mapNotNull { SealedBox.open(rescuerKey, it) }
            .mapNotNull { SosDetail.decodeOrNull(it) }
            .associateBy { Codec.hex(it.msgId) }

    // -------------------------------------------------------- silence search

    /** Phones nobody has heard from recently, with where they were last heard. */
    fun goneQuiet(quietForMs: Long = 10 * 60_000L): List<HopStore.Silence> =
        hops.goneQuiet(TimeSource.wallMs(), quietForMs, identity.keyId)

    private fun emitCarriedReceipt(refMsgId: ByteArray) =
        emitReceipt(refMsgId, Bodies.RECEIPT_CARRIED)

    private fun emitReceipt(refMsgId: ByteArray, kind: Int) {
        val receipt = Messages.receipt(
            identity, refMsgId, kind, TimeSource.wallSeconds(),
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
            updateLastBreath(battery)

            // Dying: no scanning, no relaying, no bulk plane. One position
            // beacon a minute and nothing else, until the battery is gone.
            if (lastBreath) {
                lastBreathBeacon()
                delay(RelayParams.LAST_BREATH_INTERVAL_MS)
                continue
            }

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
        if (!presence.start(identity.keyId, bulkAvailable = gattServer.running)) return
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
            // Observations outlive the messages that produced them: a message
            // expires in a day, but "who was last heard where" is what a search
            // team needs on day three.
            hops.purge(wall)
            refreshFix()
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

    /**
     * At 5% battery, stop being a relay and become a marker.
     *
     * Scanning is the expensive thing — roughly eighteen seconds of listening
     * for every second of transmitting (docs/03) — so a phone that keeps
     * relaying at 5% spends its last minutes carrying other people's traffic
     * and then dies silently in a pocket. Dropping everything except a position
     * beacon every 60 seconds turns those minutes into days of being findable.
     *
     * It is the one place where refusing to help others is the right call: a
     * phone that has gone quiet is a search problem, and this is what stops it
     * becoming one.
     */
    private fun updateLastBreath(batteryPct: Int) {
        val shouldEnter = batteryPct <= RelayParams.LAST_BREATH_PCT
        if (shouldEnter == lastBreath) return
        lastBreath = shouldEnter

        if (shouldEnter) {
            Log.w(TAG, "battery ${batteryPct}%: entering last-breath mode")
            // Listening is what drains the battery, so it is the first thing to
            // go. The phone can no longer hear anyone; it can still be heard.
            scanner.stop()
            gattServer.stop()
            bulkSync.forget()
        } else {
            Log.i(TAG, "battery ${batteryPct}%: leaving last-breath mode")
            if (running) {
                scanner.restart()
                val serverUp = gattServer.start()
                presence.start(identity.keyId, bulkAvailable = serverUp)
            }
        }
        publish()
    }

    /**
     * The only thing a phone does once it is dying: say where it is, once a
     * minute, until the battery is gone.
     */
    private suspend fun lastBreathBeacon() {
        val fix = currentFixOrNull()
        val envelope = if (fix == null) {
            Messages.sosWithoutFix(identity, TimeSource.wallSeconds())
        } else {
            Messages.sos(identity, fix.first, fix.second, TimeSource.wallSeconds())
        }
        advertiser.burst(envelope.encode(), RelayParams.LAST_BREATH_BURST_MS) {}
        presence.start(identity.keyId, bulkAvailable = false)
    }

    /**
     * Refreshes the cached position that hop observations are stamped with.
     *
     * Opportunistic and never blocking: ingest must not wait on GPS, and a fix
     * from a few minutes ago bounds a search area just as well as one from this
     * second. Skipped entirely in last-breath mode, where the beacon takes its
     * own fix and nothing else may spend battery.
     */
    private fun refreshFix() {
        if (lastBreath) return
        val now = TimeSource.wallMs()
        if (now - lastFixAt < FIX_REFRESH_MS) return
        lastFixAt = now
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            locator.fixOnce(FIX_TIMEOUT_MS) { location ->
                if (location != null) {
                    lastLat = location.latitude
                    lastLon = location.longitude
                }
            }
        }
    }

    private fun currentFixOrNull(): Pair<Double, Double>? =
        if (lastLat.isNaN() || lastLon.isNaN()) null else lastLat to lastLon

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

        // Only built in rescue mode. On an ordinary phone this is wasted work and
        // would put other people's sealed bodies into UI state for no reason.
        val rescuing = Prefs(app).rescuerKeyHex.isNotEmpty()
        val calls = if (rescuing) {
            store.liveMessages()
                .filter { it.type == MsgType.SOS }
                .map { it.toSosCall() }
        } else {
            emptyList()
        }

        _state.value = _state.value.copy(
            sosCalls = calls,
            rescueMode = rescuing,
            findMeActive = findMe.screaming,
            findMeEndsAt = findMe.endsAtMs,
            lastBreath = lastBreath,
            // Counted rather than listed: the full search list is a query the
            // rescue screen makes when it opens, not something every publish
            // should pay for.
            goneQuiet = if (rescuing) hops.goneQuiet(wall, excludeKeyId = identity.keyId).size else 0,
            announcements = records.bodiesOfProfile(
                `in`.setu.relay.wire.Announcement.PROFILE_ID,
            ).size,
            observations = hops.count(),
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
            recordsHeld = records.count(),
            recordsForOthers = records.countForOthers(),
            recordsReceived = gattServer.recordsReceived,
            recordsPushed = bulkSync.recordsPushed,
            bulkSessions = bulkSync.sessions,
            bulkServerUp = gattServer.running,
            bulkLastResult = bulkSync.lastResult ?: gattServer.lastError,
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

    private fun StoredMessage.toSosCall() = SosCall(
        idHex = idHex,
        originKeyIdHex = Codec.hex(originKeyId),
        hopCount = hopCount,
        createdAt = createdAt,
        receivedAt = receivedAt,
        isMine = isMine,
        sealedBody = envelope.copyOfRange(
            Proto.OFF_SEALED_BODY,
            Proto.OFF_SEALED_BODY + Proto.LEN_SEALED_BODY,
        ),
    )

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
        /**
         * A synthetic message id under which presence sightings are recorded.
         *
         * A phone that never sends anything still advertises presence, and
         * without this it would leave no trace at all — exactly the handset a
         * search team most needs a last-known position for.
         */
        private val PRESENCE_MSG_ID = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 1)

        /** How often the cached position is refreshed for hop stamping. */
        private const val FIX_REFRESH_MS = 5 * 60_000L
        private const val FIX_TIMEOUT_MS = 8_000L

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
