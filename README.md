# Setu — Android relay

Offline-first disaster communication. Small records move phone-to-phone over
Bluetooth LE until one phone in the chain finds signal.

The specification lives in `D:\Setu-docs`. **The docs are authoritative**; this
app implements them, and every place it deviates is recorded in
`Setu-docs/MEMORY.md` with a D-number and explained in the source at the point
of deviation.

---

## What is built

| Phase | Deliverable | Status |
|---|---|---|
| 1 | Wire + identity — envelope codec, Ed25519, X25519, sealed box | **Done** |
| 2 | Beacon plane — advertiser, scanner, dedupe, backoff schedule | **Done** |
| 2b | Legacy fragmentation — 8+1 fragment codec | **Done** |
| 3 | Receipts + status UI — status ladder, carrying screen, mesh count | **Done** |
| 4 | Backend ingest | Not started |
| 5 | Bulk plane — GATT, bloom digest | Not started (UUID allocated) |
| 6 | iOS | Not started |
| 7 | Disaster profiles | Not started (table exists) |
| 8 | Suppression | Implemented and tested, **off** by default per D3 |

Phases 1–3 are the demo. `docs/08-build-plan.md` is explicit that a rock-solid
1–3 beats a shaky 1–7.

## Open in Android Studio

```bash
studio D:\Setu-App
```

Or **File → Open** and pick `D:\Setu-App`. It is a standard Gradle project:
AGP 9.3.2, Gradle 9.5, Kotlin 2.2.10, `compileSdk 37`, `targetSdk 36`,
`minSdk 26`. `local.properties` points at the SDK on this machine; Studio will
rewrite it if yours differs.

## Command line

```bash
cd D:\Setu-App && ./gradlew :app:assembleDebug
```

```bash
cd D:\Setu-App && ./gradlew :app:testDebugUnitTest
```

```bash
cd D:\Setu-App && ./gradlew :app:connectedDebugAndroidTest
```

```bash
cd D:\Setu-App && ./gradlew :app:assembleRelease
```

The release APK lands in `app/build/outputs/apk/release/app-release.apk` and is
signed with the debug key so it installs without extra setup. **It is not a
distributable build** — sign it properly before giving it to anyone.

## APK budget

`docs/08-build-plan.md` targets 1.5 MB and says to stop and flag at 2 MB.

| Build | Size |
|---|---|
| debug, unminified | ~11 MB |
| **release, R8 full mode + resource shrinking** | **~1.14 MB** |

Check it after any dependency change:

```bash
cd D:\Setu-App && ./gradlew :app:assembleRelease && ls -l app/build/outputs/apk/release/
```

The only native code in the APK is `libandroidx.graphics.path.so`, ~37 KB across
four ABIs, pulled in transitively by `compose-ui-graphics`. Everything else is
Kotlin and platform APIs.

---

## Testing it for real

The emulator cannot advertise or scan BLE. The app runs there — every screen,
the store, the crypto, the advertising loop — but **no message will ever move
between two emulators.** You need two physical Android phones.

### Before you start, on both phones

1. Bluetooth **on**.
2. System **Location on**. Android gates BLE scan *results* behind the Location
   toggle, not just the permission — with it off, scanning silently returns
   nothing forever. The home screen warns you when this is the problem.
3. Grant every permission the first-run screen asks for.
4. Walk the battery-optimisation step. On Xiaomi, Oppo, Vivo and Samsung, also
   go into the OEM's own battery manager and set Setu to "no restrictions" /
   "allow background activity". This is the single largest risk in the build.

### The core claim — three phones, screens off

This is the test in `docs/09-test-plan.md`, and it is the whole product:

1. Install on phones **A**, **B**, **C**. Start the relay on all three.
2. Put **B** and **C** in range of each other, and **A** in range of **B** only.
3. On **A**, hold the SOS button for 2 seconds.
4. Turn **A** completely off.
5. Move **C** away from **B** and check the Carrying screen on **C**.

**C** should be carrying A's SOS with `hop_count == 2`, having received it from
**B** while A was powered off. Every screen stays off during the run.

Airplane mode with Bluetooth on is a valid and worthwhile variant: it is the
real deployment condition.

### The two-phone version

1. Both phones running the relay, sitting next to each other.
2. Phone A: hold SOS. Phone B's home screen shows **1 phone nearby** within a
   few seconds and the Carrying screen shows one message.
3. Phone A's SOS status moves **HELD → CARRIED BY 1 PHONE** when B's receipt
   arrives back. Expect this within roughly 5–15 seconds.

**CARRIED is amber, not green, and carries a warning line.** That is deliberate
and it is the most important honesty rule in the app: carried is not delivered,
and the UI must never imply help is coming when it is not. `DELIVERED` only
appears on a backend delivery receipt, which is phase 4 — you will not see it
yet, and you should not claim it in a demo.

### Reading the Diagnostics screen

Home → *Diagnostics*. This is field-test data, not decoration:

- **Advertising path** — extended (one 144-byte packet) or legacy (9 fragments
  per message). `docs/09-test-plan.md` asks you to log which path each handset
  took. Note it per device.
- **Beacons heard / bursts sent / duplicates heard** — if bursts climb and
  beacons stay at zero on both phones, it is Bluetooth or Location, not the app.
- **Service starts this install** — if this climbs on its own, an OEM battery
  manager is killing the foreground service. That is the number the docs care
  most about.
- **Rescuer view (demo)** — opens the sealed SOS body with the demo rescuer key
  that ships in the APK, to show the location really is encrypted and really is
  in there. Say out loud in any demo that key *distribution* is not implemented.

---

## Layout

```
in.setu.relay
├── wire/          Envelope codec, fragmentation, geo quantisation, PROTO_VERSION
├── crypto/        Ed25519, X25519, ChaCha20-Poly1305, sealed box, identity, keybook
├── store/         SQLiteOpenHelper schema v1, MessageStore
├── radio/beacon/  BLE advertiser + scanner, advertising data format
├── relay/         RelayEngine, RelayService, backoff scheduler, time source
└── ui/            Compose screens
```

Dependency direction is strictly downward. `wire` and `crypto` depend on nothing
but the platform. `ui` depends on everything and nothing depends on it. `relay`
resolves the launcher intent by package rather than importing `MainActivity`,
precisely so it does not point upward.

## Dependencies

Only what `CLAUDE.md` allows: Kotlin stdlib, AndroidX core/lifecycle/activity,
Compose without `material-icons-extended`, platform `android.bluetooth.le`,
platform `SQLiteOpenHelper`, platform `java.security` + AndroidKeyStore,
`kotlinx-coroutines`.

No Room, no Retrofit, no OkHttp, no Gson, no protobuf, no Tink/BouncyCastle/
libsodium, no `play-services-*`, no Firebase, no Hilt, no Navigation component,
no image loader. Four screens and a `when` beats a navigation graph.

## Known gaps

- **Beacon-plane signatures are only checked when the origin key is known.** The
  142-byte envelope carries an 8-byte key *id*, not the key, so a relay that has
  never met the origin has nothing to verify against. Structural validation runs
  before every store, and the Diagnostics screen states this plainly rather than
  overclaiming. See **D16**; bulk-plane key exchange in phase 5 is the fix.
- **Receipt volume is O(N).** Every device that stores an SOS emits a receipt.
  Fine at demo scale, unmeasured at 100 phones.
- **Plural forms.** Strings like "4 phones nearby" are not `<plurals>`, so they
  read wrong at n=1 and do not follow Hindi/Bengali/Assamese plural rules.
- **The Bodo translation needs a native speaker.** Assamese and Bengali want a
  check too. Untranslated keys fall back to English, which is the safe failure.
- **The legacy 27-byte fragment fits a 31-byte advertisement only if Android
  omits the flags AD for non-connectable advertising.** That is AOSP behaviour
  but not guaranteed by every OEM stack. `BeaconAdvertiser` logs
  `ADVERTISE_FAILED_DATA_TOO_LARGE` loudly if not — that log line is data.
- **The rescuer private key ships in the APK.** It exists so the demo can show
  the seal opening. Remove it from anything real.
