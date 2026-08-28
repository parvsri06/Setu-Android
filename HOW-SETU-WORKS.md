# How Setu Works

A complete explanation of the app — what it does, how the data actually moves
between phones, every protocol it uses, and how to demonstrate it to someone
else.

Written to be read start to finish. No prior Bluetooth knowledge assumed.

---

## Table of contents

1. [The one-sentence idea](#1-the-one-sentence-idea)
2. [The problem, and why existing apps fail at it](#2-the-problem-and-why-existing-apps-fail-at-it)
3. [The mental model: post with no roads](#3-the-mental-model-post-with-no-roads)
4. [The two planes — the central design decision](#4-the-two-planes--the-central-design-decision)
5. [**How data actually moves, step by step**](#5-how-data-actually-moves-step-by-step)
6. [Plane 0: the beacon — 142 bytes on the air](#6-plane-0-the-beacon--142-bytes-on-the-air)
7. [The relay algorithm — how a message spreads](#7-the-relay-algorithm--how-a-message-spreads)
8. [Plane 1: the bulk plane — our custom BLE GATT service](#8-plane-1-the-bulk-plane--our-custom-ble-gatt-service)
9. [Cryptography — what is protected and what is not](#9-cryptography--what-is-protected-and-what-is-not)
10. [The survey system](#10-the-survey-system)
11. [Storage — what lives on the phone](#11-storage--what-lives-on-the-phone)
12. [The interface, and its honesty rules](#12-the-interface-and-its-honesty-rules)
13. [**How to demonstrate this to other people**](#13-how-to-demonstrate-this-to-other-people)
14. [**Questions you will be asked, with honest answers**](#14-questions-you-will-be-asked-with-honest-answers)
15. [What is not built yet](#15-what-is-not-built-yet)
16. [Where everything lives in the code](#16-where-everything-lives-in-the-code)

---

## 1. The one-sentence idea

> A message can leave a village with no signal, ride through strangers' pockets,
> and arrive — and you can watch it happen.

Setu turns every phone running it into a **courier**. Your phone physically
carries other people's messages until it meets another phone, hands over a copy,
and eventually some copy in the chain reaches a phone that has signal.

"Setu" is Hindi/Sanskrit for **bridge**.

---

## 2. The problem, and why existing apps fail at it

Assam floods every year. Mobile towers go down and stay down for weeks. Every
flood-response tool assumes the phone has internet — so they all stop working at
exactly the moment they matter most.

The insight is that **the phones are still there and still working.** They just
cannot reach a tower. Two phones three metres apart can talk to each other over
Bluetooth perfectly well with no network at all. What has been missing is
software that uses that.

---

## 3. The mental model: post with no roads

Forget the word "network" for a moment.

Imagine a postal system with no roads and no timetable. You hand a letter to
whoever walks past. They put it in their pocket. When they meet someone else,
they **copy** it and hand the copy on. Eventually one of those copies reaches
someone standing where the postbox still works, and they post it.

That is not an analogy — it is literally the protocol. It has a name in computer
science: **Delay-Tolerant Networking**, specifically **store-carry-forward**.
The specific flavour is **epidemic routing** (Vahdat & Becker, 2000): give a copy
to everyone you meet.

Three words worth knowing, because they will make you sound like you know the
field (you will):

| Term | What it means |
|---|---|
| **Contact** | Two phones in radio range at the same moment. Your only chance to transfer anything. |
| **Store-carry-forward** | Hold the message in storage while you physically walk around with it. |
| **Epidemic routing** | Give a copy to everyone you meet. Simple, and very hard to stop. |

**The one idea to internalise:** in a normal network a link either exists or it
does not. Here, *a link is an event in time*. Two phones 3 m apart with the app
off have no link. Two phones that will be near each other in four hours **do**
have a link — a future one. No single phone has to get lucky; the chain does.

---

## 4. The two planes — the central design decision

This is the thing to lead with if someone technical asks. It is the decision
everything else follows from.

Bluetooth Low Energy can move data in two completely different ways:

**Advertising** — shouting into the air. Every phone in range hears it. No
setup, no pairing, no connection. But each shout is tiny (a few hundred bytes at
most), and you cannot shout a large file.

**A connection (GATT)** — two phones establish a private link and pass data back
and forth reliably, in any size. But it takes a couple of seconds to set up, and
**a phone's chipset can only hold 4–7 connections at once.**

So Setu uses both, each for what it is good at:

| | **Plane 0 — Beacon** | **Plane 1 — Bulk** |
|---|---|---|
| How | BLE advertisements (shouting) | GATT connection (private link) |
| Size | ≤142 bytes, fixed | 300 bytes – 8 KB |
| Carries | SOS, check-in, receipts | Survey records |
| How many listeners | **Unlimited** | 4–7 at a time |
| Speed | Seconds | Seconds to minutes |
| Works with screen off | Yes | Yes |

**Why not just use connections for everything?** Because a connection is a
terrible way to send 142 bytes to 60 neighbours in a relief camp. You would pay
a 3-second setup sixty times, and you can only hold seven at once. An SOS would
take minutes to reach the crowd.

**Why not just use advertisements for everything?** Because an advertisement is
a terrible way to send an 8 KB survey. It does not fit.

Use each for what it is good at. That is the whole architecture.

---

## 5. How data actually moves, step by step

This is the part that is hardest to picture, so here it is concretely. Two
scenarios.

### Scenario A: an SOS travelling across three phones

**Phone A** (a person in trouble), **Phone B** (a stranger walking past),
**Phone C** (someone near a working tower). A can reach B. B can reach C. A can
*not* reach C.

**Step 1 — A creates the message.**
You hold the SOS button for 2 seconds. The phone:
- gets a GPS fix (or notes that it could not)
- **encrypts** the coordinates so only a rescuer can read them (6 bytes of
  latitude/longitude become a 54-byte sealed blob)
- builds a **142-byte packet** (the exact layout is in §6)
- **signs** it with this phone's private identity key, so nobody can alter it
- saves it to its own database
- starts advertising

**Step 2 — A shouts.**
Phone A's Bluetooth radio broadcasts those 142 bytes on three radio channels,
repeatedly. This is not aimed at anyone. It is a shout into the room. It costs
about a millisecond of airtime per shout.

The screen can be off. The app can be closed. A **foreground service** keeps the
radio running, which is why there is a permanent notification.

**Step 3 — B hears it.**
Phone B is scanning — listening — for about 1 second in every 4. When a shout
lands inside a listening window, B receives the 142 bytes and:
- checks the **signature**. If even one byte was altered in transit, the check
  fails and B silently drops it.
- checks whether it has **seen this message ID before**. If yes, it just counts
  the duplicate and stops.
- **saves it to B's own database.** B is now carrying a stranger's SOS.
- B's home screen count of carried messages goes up by one.

**Step 4 — B waits a random moment, then shouts it onward.**
B does *not* rebroadcast immediately. It waits a **random 0–3 seconds** first.
This matters enormously — see §7. Then B starts shouting the same 142 bytes,
with the hop counter increased from 0 to 1.

Crucially: **A can now be switched off, run out of battery, or be washed away.**
The message is no longer dependent on A. It lives in B's pocket.

**Step 5 — C hears it from B.**
Same as step 3. C stores it. Its hop count reads **2** — the number of phones it
has passed through. That number is shown on screen, and it is your proof that
the message really did travel rather than being received directly.

**Step 6 — a receipt travels back.**
When B stored A's message, B created a small **receipt** — itself a 142-byte
message — saying "I saw message X". That receipt spreads through the same mesh.
When it reaches A, A's screen changes from `HELD` to `CARRIED BY 1 PHONE`.

That is the feedback loop. Without it, the app would be asking people to trust
an invisible process, which is unreasonable.

### Scenario B: a survey record moving between two phones

A survey is far too big for a shout. So:

**Step 1 —** A surveyor fills the six-step form on phone A. On save, the app
packs the relayable fields into a compact binary record (~300–700 bytes), seals
it to the relief office's key, and stores it.

**Step 2 —** Every running phone continuously sends a tiny 11-byte
"**a Setu phone is here, and it can accept records**" advertisement, about once
per second. It is *connectable*, meaning others are allowed to dial it.

**Step 3 —** Phone A hears that advertisement from phone B and learns B's
Bluetooth address. A decides whether it is worth connecting (§8 — it usually is
not, and it skips).

**Step 4 —** A dials B and opens a GATT connection. This takes a second or two.

**Step 5 —** A **reads B's digest** — a 259-byte summary of every record B
already holds (§8 explains the Bloom filter).

**Step 6 —** A checks each of its own records against that summary. Anything B
does not have, A **writes across the connection in 400-byte chunks**, waiting
for an acknowledgement after every chunk.

**Step 7 —** B reassembles the chunks, checks the size and shape, and stores the
record. **B can read it** and shows it under Surveys → *From other phones*. The
Aadhaar number inside stays sealed to the relief office and B sees only the last
four digits. B's "Carrying N survey records" count goes up.

**Step 8 —** A disconnects. The whole session takes under a second for a handful
of records.

Both phones do this to each other, so records flow both ways.

---

## 6. Plane 0: the beacon — 142 bytes on the air

### Why the size is obsessive

BLE sends **1 bit per microsecond**. A packet's time on air is
`(10 + payload) × 8 microseconds`. Two phones transmitting on the same channel
at the same moment destroy each other's packets — there is no collision
detection, they just both fail.

The probability a packet survives is `e^(−2 × T × R)` where `T` is its airtime
and `R` is how much traffic is offered. Read that as: **loss grows exponentially
with packet size.**

This was measured in simulation with 400 phones under identical load:

| Packet size | Delivered |
|---|---|
| 60 bytes | 33% |
| **142 bytes** | (the design point) |
| 250 bytes | **7%** |

That is why the envelope is a fixed 142 bytes and why growing it is a hard rule.
Every added byte costs delivery probability for everyone.

### The exact layout

| Offset | Bytes | Field | Meaning |
|---|---|---|---|
| 0 | 1 | `version` | protocol version + flags |
| 1 | 1 | `type_priority` | what kind of message, and its priority tier |
| 2 | 8 | `msg_id` | random unique ID — the duplicate-detection key |
| 10 | 8 | `origin_key_id` | who sent it (first 8 bytes of a hash of their public key) |
| 18 | 4 | `created_at` | claimed time, **not trusted** |
| 22 | 1 | `hop_count` | how many phones it has passed through, max 32 |
| 23 | 1 | `ttl_hours` | how long before it expires |
| 24 | 54 | `sealed_body` | the encrypted payload |
| 78 | 64 | `signature` | Ed25519 signature over bytes 0–77 |
| | **142** | | |

**The clever bit:** `hop_count` is set to zero before the signature is computed.
Every relay must increment it, so if it were signed, every relay would break the
signature. By zeroing it, relays can update the hop counter while **every other
byte stays cryptographically locked from sender to receiver.** Alter anything
else and the next phone drops it.

### Inside the 54-byte sealed body

```
ephemeral public key (32) + ciphertext (6) + authentication tag (16)
```

The 6 bytes of plaintext are the position:

- 3 bytes latitude: `(lat + 90) × 93206.75` → about 1.2 m of precision
- 3 bytes longitude: `(lon + 180) × 46603.4` → about 2.4 m

So the location is squeezed into **six bytes** and then encrypted so only a
rescuer can open it. Relays carry an opaque blob. This is deliberate — see §9.

### Message types

| Type | Tier | Lives for |
|---|---|---|
| SOS | 0 (highest) | 24 hours |
| Check-in ("I'm safe") | 1 | 72 hours |
| Receipt | 2 | 24 hours |
| Survey reference | 3 | 14 days |
| Profile update | 4 | 30 days |

Priority is enforced everywhere: which message advertises first, and which gets
deleted first when storage fills. **A tier-0 SOS with no receipt yet is never
deleted.**

### How it is wrapped for the air

BLE advertisements have a "manufacturer data" field. Setu has no assigned
company ID, so it uses `0xFFFF` — the identifier reserved for testing — with a
2-byte magic prefix so Setu traffic can be told apart from anyone else using it:

- **Extended (modern phones, BLE 5):** `'S' 'T'` + the 142-byte envelope = 144
  bytes, one packet.
- **Legacy (older phones, 27 bytes per advertisement):** the envelope is split
  into fragments and reassembled by the receiver.
- **Presence (all phones):** `'S' 'P'` + 8-byte key ID + 1 flag byte = 11 bytes.

The Diagnostics screen tells you which path your handset took. That is real
field-test data worth recording per device.

---

## 7. The relay algorithm — how a message spreads

This is about forty lines of real code, and three details each cost real
coverage if you get them wrong.

```
when a message arrives:
    already seen it?          -> count the duplicate, stop
    signature invalid?        -> drop it, stop
    hop count at the limit?   -> store it but do not repeat
    otherwise                 -> store it, and schedule a decision
                                 at a random point in the next 0-3 seconds

at that moment:
    start advertising it

while advertising:
    pick the interval from the schedule based on the message's age
    battery under 15% and this is not an SOS? -> stop
    past its TTL?                             -> stop and delete
```

### Detail 1 — the random wait is not optional

Without it, every phone that hears a message rebroadcasts **at the same
instant**, and they all collide and destroy each other. This is the single most
common mesh-networking bug. Spreading the retransmissions randomly over three
seconds is what makes the whole thing work.

### Detail 2 — never go completely silent

The advertising interval widens as the message ages:

| Message age | Shout every |
|---|---|
| under 10 s | 0.5 s |
| under 30 s | 2 s |
| under 2 min | 10 s |
| after that | 30 s, forever, until it expires |

**It never reaches zero and never becomes infinite.** An earlier design stopped
advertising after a fixed 8-second window. It failed in sparse areas — a message
died because its only carrier went quiet before its one neighbour happened to be
listening. Coverage in the sparse case was **75%**. With the unbounded schedule
it is **98%**. A carried message keeps whispering; it just gets quieter.

### Detail 3 — the burst has to be long enough to be heard

A listening phone is only actually listening about **1 second in every 4** (this
is the battery/detection trade-off). So a short shout has only about a 25% chance
of landing while anyone is listening.

That is fine for a young message shouting twice a second — it gets many chances.
But an old message shouting once every 30 seconds, with a 25% chance each time,
takes *minutes* to be noticed. That was a real bug, reported as "sometimes it
doesn't detect other devices".

The fix: **the burst length scales with the gap.** Rare whispers are made long
enough to be heard (up to 1.2 s), frequent ones stay short (0.3 s). Airtime stays
bounded at about 4% either way.

### Why it gets *faster* with more phones

Measured in simulation, 80 m × 80 m camp, 20 live messages:

| Phones | Naive flooding | Setu's approach |
|---|---|---|
| 100 | 53% delivered | **100% in 12.5 s** |
| 400 | 13% delivered | **100% in 7.2 s** |

More phones means more independent paths to hear a message on, while the random
backoff keeps the channel from saturating. Naive flooding collapses under its
own traffic; this does not.

### Battery

Counterintuitive but measured: a phone spends roughly **18 seconds listening for
every 1 second transmitting.** Listening dominates. So the lever that matters for
battery is the *scan duty cycle*, not the advertising interval. Setu uses
Android's `SCAN_MODE_BALANCED`, about 10–15%.

---

## 8. Plane 1: the bulk plane — our custom BLE GATT service

This is the part you built most recently, and the part most likely to be asked
about in technical detail.

### What GATT is, briefly

GATT is the standard way BLE devices expose data over a connection. A device
publishes a **service** (identified by a UUID), containing **characteristics** —
named values that can be read, written, or subscribed to. A fitness band exposing
"heart rate" is a GATT service. Setu defines its own.

### Setu's service

```
Service   5e701000-9b2a-4f6d-8c31-2f7a1d0e4b55
  DIGEST  5e701001-9b2a-4f6d-8c31-2f7a1d0e4b55   read
  PUSH    5e701002-9b2a-4f6d-8c31-2f7a1d0e4b55   write
```

Every running phone hosts this server **and** acts as a client to others. There
is no central device and no pairing.

### The digest — a Bloom filter

Before sending anything, you want to know what the other phone already has.
Sending a list of every record ID would grow without bound. Instead, the digest
is a **Bloom filter**: a fixed 256 bytes that can answer *"do you have this
ID?"* for any number of records.

How it works, plainly: 256 bytes is 2048 bits, all starting at zero. To add a
record, hash its ID, use the hash to pick 3 of those 2048 bits, set them to 1.
To test whether a record is present, hash it the same way and check whether all
3 bits are set. If any is 0, the record is **definitely not there**. If all 3 are
set, it is **probably** there.

- **False negatives are impossible.** It never says "missing" about something it
  has.
- **False positives happen** — about 3% at 200 records. A record is wrongly
  skipped this time, and the next contact carries it. In a store-carry-forward
  network that is a delay, not a loss.

The digest on the wire is `version(1) + record count(2) + bloom(256)` = **259
bytes**.

### Push, not pull — an honest design deviation

The original spec sketched three characteristics: read the digest, *request*
what you lack, receive the *payload*.

**That cannot work with a Bloom filter,** and noticing this is worth explaining
if asked. A Bloom filter answers only "do you have X?". You cannot walk it to
list its contents. So a phone can read a peer's digest and learn nothing about
what to ask for.

Push works, and needs one characteristic fewer:

```
1. Client reads the server's DIGEST     -> a Bloom of everything the server has
2. Client tests its OWN record IDs      -> the ones that fail are the gap
3. Client WRITES those records          -> chunked, acknowledged
```

Because every phone runs both roles, A pushes to B and B pushes to A, and the
result is the same reconciliation with less protocol.

### Chunking

A GATT write is limited by the **MTU** — the maximum packet size for that
connection, negotiated at connect time. The client asks for 517 bytes; if the
peer refuses, it can be as low as 23.

Each chunk carries a repeating header so the receiver needs no complex state:

```
record_id (16) + total_length (4) + offset (4) + data (up to 400)
```

Chunks are written **with acknowledgement**, so a dropped chunk is noticed. The
cap is 400 rather than the ~490 that a 517-byte MTU allows, because some
manufacturers' Bluetooth stacks quietly truncate long writes near the limit —
and a record arriving silently short would fail to decrypt with no clue why.
Costing one extra round trip is cheaper than debugging that in a field test.

### When a connection is opened

Three rules, each for a specific failure:

1. **One session at a time.** A chipset supports 4–7 connections, and scanning
   plus several connections is where cheap radios start dropping advertisements.
   Serialising keeps the SOS-carrying beacon plane unaffected.
2. **One minute cooldown per peer.** Presence advertisements arrive about once a
   second. Without a cooldown, two idle phones would reconnect forever, burning
   both batteries re-exchanging a digest that has not changed.
3. **Nothing to send, no connection.** With no records, a session could only
   discover the peer also has nothing.

### Safety

Everything arriving is hostile input from a stranger's phone. Sizes are checked
before memory is allocated, a record over 16 KB is refused, at most 8
partly-received records are buffered at once, half-finished transfers are
discarded after 60 seconds, and a record that fails to decode never touches the
database. Records are never overwritten — the ID *is* the survey's UUID, so a
second copy carries nothing new, and accepting one would let any peer replace a
record it did not create.

---

## 9. Cryptography — what is protected and what is not

Being precise here is what separates a credible project from an overclaiming
one. Technical judges reward the precision.

### The keys

| Key | Where it lives | Job |
|---|---|---|
| **Device identity** (Ed25519) | Generated on first run, private half sealed by the Android hardware keystore | Signs every message |
| **Rescuer public key** (X25519) | Ships inside the app | Encrypts SOS locations |
| **Backend public key** (X25519) | Ships inside the app | Encrypts survey records |

All of this is hand-written Kotlin — Ed25519, X25519, ChaCha20-Poly1305 —
because pulling in a crypto library would have cost about 1 MB, and the whole
app is 1.2 MB. It is verified against the official test vectors from the RFCs.

### Sealing, explained simply

"Sealing" means: encrypt something so that **only the holder of one specific
private key can open it — including you, after you have sealed it.**

The app generates a throwaway key pair for each message, combines it with the
recipient's public key to derive a shared secret (X25519), and encrypts with
that. The throwaway public key rides along in the packet so the recipient can
redo the calculation. Nobody else can.

**This is the thing to demonstrate.** Your phone seals its own GPS position and
then genuinely cannot read it back.

### What tamper-evidence proves — and what it does not

An Ed25519 signature at capture proves **the record was not altered in transit
by any relay.** That is exactly the threat the relay design creates, and closing
it is a real result.

It does **not** prove the record was true when it was written. A person can sign
a fabricated claim perfectly. **Tamper-evidence is not fraud prevention.** Say
this out loud before anyone asks — overclaiming here is the fastest way to lose a
technical judge.

### Untrusted time

An offline phone has no trustworthy clock. The user can change it; a dead
battery resets it. So `created_at` is a **claim, not a fact**. The app detects
when the wall clock jumps backwards (by comparing against the monotonic
since-boot clock) and flags it on the Diagnostics screen.

### Aadhaar — three forms, three jobs

| Form | Answers | Reversible? |
|---|---|---|
| Sealed to the backend key | "what does the relief office need?" | Only with the office's private key |
| Salted hash | "have we already surveyed this person?" | **Yes, trivially** |
| Last 4 digits | "what may a surveyor see?" | No — 8 digits are gone |

**The plaintext number is never stored on the phone.** It is sealed the moment
it is entered.

**Be careful with the hash.** An Aadhaar number is 12 digits — a trillion
possibilities, which is minutes of brute force on ordinary hardware. The salted
hash stops a precomputed lookup table and **nothing else**. It exists so the
phone can spot a duplicate without keeping the number. If anyone describes it as
protecting privacy, correct them: **the sealing is the protection.**

### The threats this design takes seriously

| Threat | Why it matters | What is done |
|---|---|---|
| **Location privacy inversion** | A naive mesh SOS would broadcast a vulnerable person's exact GPS to every stranger in range — an invitation to looting and trafficking | GPS sealed to the rescuer key; relays carry an opaque blob |
| **Tampering in transit** | A malicious relay could alter a message | Signature covers every byte except the hop counter |
| **Replay of an old SOS** | Wastes rescue capacity | Message IDs are remembered across restarts; TTL enforced |
| **Storage flooding** | Filling every phone with junk | Signature required before storing; tiered eviction; caps |
| **Malicious relay dropping messages** | Silent censorship | Multiple independent paths; receipts make drops visible |
| **Jamming, traffic analysis** | — | **Out of scope, and stated as such.** Do not claim the conflict-zone use case. |

Note the deliberate asymmetry: survey data must be unreadable by relays, but an
SOS must be readable by nearby rescuers. Two different key models, on purpose.

### Privacy law

You are moving identifiable personal data across strangers' phones. Under
India's **DPDP Act 2023** a private app does not get the State's disaster
exemption. So: explicit consent in the user's own language before anything is
captured, purpose stated on screen, survey bodies sealed so relays never see
personal data, automatic deletion on expiry, and a screen that tells you exactly
what your phone is carrying for other people.

Turning the relay off withdraws consent. That is why the switch is prominent
rather than buried.

---

## 10. The survey system

### The six steps

Personal details → Location → Damage → Affected people → Relief camp → Review.
Then Saved. Every step has **Save draft**, and it autosaves after each pause in
typing, because a phone dying mid-survey in a flood is a realistic event.

### Entering for someone else

A toggle on the first step: *"I am filling this for someone else."* This is the
requirement that a surveyor can record a person who has no phone or cannot use
one. When it is on, the app records **separately** that consent was given by the
person the data is about — because under DPDP, consent has to come from them,
not from the surveyor.

### No GPS needed

Location is entered by name: village, district, post office, police station, PIN.
This is deliberate. GPS is slow, drains battery, and often fails under tree cover
or indoors, and rural addresses in Assam are known by name locally anyway.

### The transport split

This is the part you specifically asked for:

- **Identity fields and casualty status relay over Bluetooth.** Small.
- **Damage detail, relief camp details and photos do not.** They are meant for
  internet upload.

The reason is arithmetic, not preference. Photo bytes on a radio that also
carries SOS traffic would starve the thing that saves lives in order to move
something that can wait for a tower.

### The record format

Hand-written binary — no JSON — because field names would cost more than the
values do. Roughly 300–700 bytes:

```
version(1) + survey UUID(16) + created(4) + flags(1)
+ the already-sealed Aadhaar blob
+ name, father's name, mobile, family ID, last-4
+ village, district, post office, police station, PIN
+ number of people, then for each: status, gender, age, name, location
```

Strings are length-prefixed UTF-8, capped at 200 bytes each — enough for a real
name in Assamese, where a character is 3 bytes.

The whole record is then sealed to the relief office's key. Note that the
Aadhaar inside is **already sealed**, so it is double-sealed: a compromised
record key alone still learns nobody's Aadhaar number.

---

## 11. Storage — what lives on the phone

Plain SQLite, no Room (which would have cost ~1 MB). Eight tables:

| Table | Holds |
|---|---|
| `message` | The 142-byte envelopes, own and carried |
| `seen` | Message IDs already seen, so duplicates survive a restart |
| `receipt` | Who has confirmed seeing what |
| `peer` | Nearby phones and when they were last seen |
| `record` | Sealed survey records — yours and other people's |
| `profile` | Disaster form definitions (future) |
| `survey` | Your own surveys, in full |
| `person` | Affected people, one row each |

**Limits:** 2000 messages or 50 MB, 200 records. When full, the highest tier
number goes first, oldest first — and **a tier-0 SOS without a receipt is never
deleted.** Expired messages are removed automatically.

**Upgrades add tables, never drop them.** An earlier version rebuilt the database
on upgrade, which would have destroyed a half-finished survey or a live SOS on
any phone updating from 1.0.1. There is a test that builds an old-format database
and proves the data survives.

---

## 12. The interface, and its honesty rules

Designed for a wet phone, one hand, bright sun or darkness, low literacy, and a
person under stress.

- **Minimum touch target 64 dp; the SOS button is 88 dp.** Wet fingers, shaking
  hands.
- **SOS is press-and-hold for 2 seconds**, not a tap — a pocket press must not
  fire it — and not a multi-step dialog, which would cost seconds.
- **Never colour alone.** Every state has an icon and a word too.
- **Five languages** from the first commit: Assamese, Bodo, Bengali, Hindi,
  English. The language picker comes *before* any other text, because someone who
  cannot read the consent notice has not consented to anything.
- **No animations that keep the screen awake.** The relay works with the screen
  off, and the interface must never imply it needs to be on.

### The status ladder, and the rule that matters most

```
HELD  ->  CARRIED BY 3 PHONES  ->  DELIVERED  ->  EXPIRED
```

**`CARRIED` is amber, never green, and carries an explicit warning line.**

Carried means some phones have a copy. It does **not** mean help is coming.
Rendering it as success would be the most damaging thing this app could do.
`DELIVERED` appears only on a real delivery receipt.

The palette enforces it: red means SOS and nothing else, amber means CARRIED,
green means DELIVERED and appears nowhere else a user can reach. The "I'm safe"
button is blue rather than green for exactly this reason — it *starts* a
check-in, it does not confirm one.

### The carrying screen

*"Your phone is carrying 12 messages for other people."* For SOS and check-in
messages this is counts and sizes only, because the phone genuinely cannot read
them. Survey records are different since format v2 — those are readable and
listed under Surveys, with the Aadhaar number still sealed.

This screen exists because people deserve to know their device is a courier. It
turns a creepy surprise into a reason to trust the app.

---

## 13. How to demonstrate this to other people

**You need two Android phones minimum. Three is much better. The emulator cannot
do Bluetooth — no message will ever move between two emulators.**

### Before you start, on every phone

1. Bluetooth **on**.
2. System **Location on**. Android hides Bluetooth scan *results* behind the
   Location toggle, not just the permission. With it off, scanning silently
   returns nothing forever. The home screen warns you when this is the problem.
3. Grant every permission the first-run screen asks for.
4. Walk the battery-optimisation step. On Xiaomi, Oppo, Vivo and Samsung, also
   open the manufacturer's own battery manager and set Setu to "no
   restrictions". **This is the single biggest risk to a live demo.**
5. Start the relay on each phone (the home screen button).

### Demo 1 — "the phones can see each other" (30 seconds)

Put two phones side by side with the relay running.

Within a few seconds both home screens change from *"No phones nearby yet"* to
**"1 phone nearby"**.

> **Say:** "No internet, no pairing, no Wi-Fi. They found each other over
> Bluetooth alone. Everything from here works with the towers down."

Turn one phone's Bluetooth off — the other drops back to zero within a minute.
Turn it back on and it recovers. That recovery is worth showing; it is a fix, not
an accident.

### Demo 2 — the SOS and the status ladder (2 minutes)

1. On phone A, hold SOS for 2 seconds.
2. A shows **HELD**, and says the location was sealed.
3. Within roughly 5–15 seconds, A changes to **CARRIED BY 1 PHONE**.
4. On phone B, open the carrying screen — it shows it is carrying one message.

> **Say, pointing at the amber:** "This is the most important design decision in
> the app. Amber, not green. It means a copy is on someone's phone. It does
> **not** mean help is coming, and we refuse to let the interface imply that."

### Demo 3 — the one that proves the whole thesis (5 minutes)

**This is the demo. Everything else is warm-up.** You need three phones.

1. Install on **A**, **B**, **C**. Relay running on all three.
2. Place **B** and **C** near each other. Take **A** to another room, in range of
   **B** only — not C.
3. On **A**, hold SOS.
4. Wait about 20 seconds.
5. **Now switch phone A completely off.** Power off, not just the screen.
6. Bring **C** back and open its carrying screen.

**C is carrying A's SOS, with hop count 2, and A is switched off.**

> **Say:** "Phone A is off. It has no battery, no signal, and it is not in this
> conversation any more. Its message is still travelling, in a stranger's pocket.
> That is the entire idea — the message outlives the sender, and no single phone
> has to get lucky."

Show the **hop count of 2** on screen. That number is the proof it travelled
through B rather than arriving directly.

Do the whole thing with **every screen off**, and say so.

### Demo 4 — the survey moving between phones (3 minutes)

1. On phone A, fill in a survey and save it.
2. Open Diagnostics on A: **Records held: 1**.
3. Bring phone B close. Within about a minute, on B's Diagnostics:
   **Records received from peers: 1**. On A: **Records sent to peers: 1**.
4. Open B's carrying screen: *"Carrying 1 survey record for other people."*

5. On B, open **Surveys**. The survey is listed under *From other phones*. Tap it
   to see the full table.

> **Say:** "That survey moved phone to phone over a Bluetooth connection with no
> internet. Phone B can read the operational fields, which is the point — a
> district officer collects what field workers gathered without a tower. The
> Aadhaar number is the exception: it stays sealed to the relief office, so B
> sees only the last four digits."

If it does not transfer within a minute, tap into Diagnostics and read the
**transfer sessions** counter aloud — being able to diagnose your own demo live
reads as far more competent than a demo that simply worked.

### Demo 5 — rescue mode (2 minutes, and this is the one that closes the loop)

Until now the SOS went out and nobody could act on it. Rescue mode is the
receiving end.

**Setup:** the same APK on both phones. On the phone you are calling the rescuer,
open **Rescue mode** from the home screen and paste the demo key:

```
8872a00d82067595c977fd7a3a3024f78336d0102ee27c8f510b73c60bb8057b
```

1. The rescuer phone now says **Rescue mode is on**, and the home screen shows a
   green banner with the number of SOS calls heard.
2. On the civilian phone, hold SOS.
3. The rescuer phone raises a **loud notification** — sound and vibration,
   separate from the quiet relay notification.
4. Open Rescue mode on it. The SOS is listed with the **actual coordinates**,
   the hop count, and an **Open in maps** button.

> **Say:** "Both phones are running the identical APK I gave you. What makes this
> one a rescuer is that someone entered a key into it. The civilian phone sealed
> its position so that only a holder of that key can read it — every phone in
> between carried it blind. And notice the app shows two times separately: the
> time the sender's phone claimed, and the time this phone actually heard it. An
> offline phone has no trustworthy clock, so we never present its timestamp as
> fact."

Then turn rescue mode off on that phone and open the same screen: the calls are
still there, still sealed, unreadable. **That is the whole security claim, shown
in one toggle.**

### Demo 6 — sealing is real, not decoration (1 minute, debug build only)

On a **debug** build, Diagnostics → *Rescuer view*. It opens the sealed SOS and
shows the actual coordinates.

On the **release** build you hand out, the same screen says the body is sealed
and this build holds no key to open it.

> **Say:** "The same app, the same message. The build I give you cannot read it.
> Only a rescuer's key can. And I want to be precise: sealing is implemented, key
> *distribution* is not — a real deployment would push rescuer keys through
> signed profile updates."

That precision is a strength. Claiming end-to-end key management you have not
built is how you lose a technical judge.

### The airplane-mode variant

Put every phone in **airplane mode, then turn Bluetooth back on**. Everything
above still works. This is the real deployment condition and it is the single
most convincing thing you can do.

### Rehearse the failure, not just the success

Bluetooth misbehaves on stage. Have a phone already carrying messages as a
fallback, and be ready to say out loud what went wrong and why. A presenter who
can diagnose their own demo live is more impressive than one whose demo happened
to work.

---

## 14. Questions you will be asked, with honest answers

**"Is this a mesh network like Bridgefy or FireChat?"**
Same family, different threat model. Bridgefy was broken in 2020 — impersonation,
man-in-the-middle, deanonymisation — while protesters were trusting it with their
safety. Setu signs every message with a hardware-backed key and seals locations
so relays carry an opaque blob. Those are direct responses to how that failed.

**"How far apart can the phones be?"**
About **20 metres** in practice, not the 100 m on the datasheet. Datasheets
measure free space. You have wet air, wet clothing and human bodies, and 2.4 GHz
is absorbed by water. Being conservative here is the honest answer, and the
number is still unverified on real handsets — it is a stated open question.

**"How many phones can this handle?"**
It gets *faster* with more. 100 phones: 100% delivery in 12.5 s. 400 phones:
100% in 7.2 s. Naive flooding collapses to 13% at that density; the random
backoff is what prevents that.

**"What about battery?"**
Listening dominates — roughly 18 seconds of receiving per 1 second of
transmitting. The app uses a 10–15% scan duty cycle, and below 15% battery it
stops relaying everything except SOS. Being honest: phones dying after days
without grid power is the real failure mode, and it is out of scope for now.

**"Can't someone send fake SOS messages?"**
Yes, and it is the most serious threat, because it wastes rescue capacity and
people can die from that. Mitigations: every message needs an unforgeable device
key, relays enforce a per-key quota, and the backend rate-limits per identity.
This does not eliminate the risk, it raises the cost.

**"Does the signature prove the report is true?"**
**No.** It proves nobody altered it in transit. A person can sign a false claim.
Tamper-evidence is not fraud prevention.

**"Why not use Google Nearby Connections?"**
It requires Google Play Services. That would exclude grey-market and de-Googled
phones, and Google is changing Nearby's radio behaviour in late 2026. Doing it
directly also gives control of the advertising interval — the single parameter
that decides whether this scales.

**"Why is the app so small?"**
1.2 MB, deliberately. Classic Bluetooth file transfer runs 100–200 KB/s, so a
1.2 MB app moves person-to-person in about 10 seconds. A 30 MB Flutter app takes
two people standing still holding phones for 3–5 minutes, in a disaster.
**Size is transferability.** No Room, no Retrofit, no Firebase, no image library,
no crypto library — each cut is deliberate.

**"What about iPhones?"**
Researched and quantified, not yet built. A backgrounded iPhone cannot advertise
arbitrary data — a hard Apple limit — but it *can* scan, connect and serve GATT.
So iPhones would receive on the beacon plane and participate fully on the bulk
plane. Simulation says at a 50/50 population, building the iOS app moves 6-hour
SOS delivery from 20% to 64%. The practical consequence for iPhone-heavy areas is
to deploy cheap always-on Android handsets as **anchors** at camps and police
posts.

**"Does it work with the screen off?"**
Yes. A foreground service holds the radio, which is why there is a permanent
notification. The honest caveat: Xiaomi, Oppo, Vivo and Samsung aggressively kill
background services regardless, which is why there is a battery-exemption
walkthrough and why the Diagnostics screen counts service restarts.

**"What's the weakest part?"**
Two things, and say them plainly. First, OEM battery managers killing the
service — that is the biggest unknown in the whole build. Second, the 20 m range
assumption is from published measurements, not from your own handsets. A field
test with 20–30 phones is worth more than any further simulation, and the right
thing to do is publish the mismatch rather than hide it.

---

## 15. What is not built yet

Say this list out loud in any demo. It is short, and being straight about it
buys credibility for everything you *have* built.

| Not built | Consequence |
|---|---|
| **Internet upload** | Nothing ever reaches a server. `DELIVERED` will never appear — only `HELD` and `CARRIED`. Skipped deliberately for now. |
| **Backend** | No Merkle log, no ingest, no officer dashboard. |
| **iOS app** | Android only. |
| **Key distribution** | The rescuer public key is compiled in and the private key is typed in by hand. Nothing issues or rotates keys. |
| **Disaster profiles** | Forms are compiled in rather than being data pushed over the mesh. |
| **Suppression** | Deliberately off. The backoff schedule alone already achieves 100% delivery; suppression only halves channel load, so it waits for field data. |
| **Photos** | Captured nowhere yet; they belong with internet upload. |

The honest summary: **messages and survey records move phone-to-phone, offline,
today. Nothing reaches the internet yet.**

---

## 16. Where everything lives in the code

```
in.setu.relay
├── wire/          The 142-byte envelope, fragmentation, GPS squeezing,
│                  the survey record format
├── crypto/        Ed25519 signing, X25519 sealing, ChaCha20-Poly1305,
│                  device identity, Aadhaar handling
├── store/         SQLite — messages, receipts, peers, records, surveys
├── radio/
│   ├── beacon/    The shouting plane: advertiser, scanner, presence
│   └── bulk/      The connection plane: GATT server, GATT client,
│                  Bloom filter, chunking, sync scheduling
├── relay/         The engine, the foreground service, the backoff schedule
└── ui/            Compose screens, theme, honesty rules
```

Dependencies point strictly downward: `wire` and `crypto` depend on nothing but
the platform, and `ui` depends on everything while nothing depends on it.

**Reading order if you want to understand it properly:**
`wire/Proto.kt` → `wire/Envelope.kt` → `relay/RelayParams.kt` →
`relay/RelayEngine.kt` → `radio/bulk/BulkProto.kt`.

Every file explains *why* it is the way it is, especially where a decision looks
odd. The full decision log with reasoning is in `Setu-docs/MEMORY.md`, and the
specification is in `Setu-docs/docs/`.

---

## The sentence to end on

> A message can leave a village with no signal, ride through strangers' pockets,
> and arrive — and you can watch it happen.

Everything in this app either serves that sentence or is waiting its turn.
