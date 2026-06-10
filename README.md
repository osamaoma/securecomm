# P2P Encrypted Voice (Android) — with Contacts & Incoming Calls

A peer-to-peer voice call app over the internet with:

- **AEC** (Acoustic Echo Cancellation) — hardware AEC + WebRTC AEC3 fallback
- **Noise Suppression** — hardware NS + WebRTC software NS
- **AGC** (Auto Gain Control)
- **E2EE** — per-frame **AES-GCM** via libwebrtc `FrameCryptor`, on top of mandatory DTLS-SRTP
- **Username-based contacts** — pick a username, save friends, tap to call
- **Incoming call screen** — full ring/accept/decline UI, shows over the lock screen
- **P2P media** — direct UDP between peers (STUN; add TURN for fallback)

---

## How calling works now

1. On first launch you set a **username**, the **server URL**, and a shared **E2EE secret**.
2. The app keeps a persistent connection to the signaling server and **registers your username**.
3. To call someone, add them as a contact (by their username) and tap **Call**.
4. The server **routes the call to that username**; their phone rings with an Accept/Decline screen.
5. On accept, the two phones connect **directly (P2P)** and audio flows, double-encrypted.

> Both users must share the **same E2EE secret** — it's the symmetric AES-GCM key.
> A mismatched secret = undecryptable audio (silence).

---

## Project layout

```
P2PVoice/
├── app/src/main/java/com/example/p2pvoice/
│   ├── SetupActivity.java     first-run: username + server + secret, registers
│   ├── MainActivity.java      contacts list, add/remove, start calls
│   ├── CallActivity.java      incoming/outgoing/active call screen (+ringtone)
│   ├── ContactsAdapter.java   RecyclerView adapter
│   ├── Contact.java           contact model
│   ├── Store.java             local storage (profile + contacts)
│   ├── SignalingHub.java      app-wide persistent connection + call orchestration
│   ├── SignalingClient.java   WebSocket protocol (register/call/accept/relay)
│   ├── RtcEngine.java         WebRTC: AEC/NS/AGC + E2EE FrameCryptor
│   └── CallService.java       foreground service (keeps mic alive)
└── server/
    ├── server.js              username-routed signaling server
    └── package.json
```

---

## 1. Run the signaling server

```bash
cd server
npm install
node server.js
# -> Username-routed signaling server on ws://0.0.0.0:8080
```

Host on a public VPS for real-device calls; use `wss://` behind TLS in production.

## 2. Open in Android Studio

File → Open → select the `P2PVoice` folder, let Gradle sync, run.

### Server URL cheatsheet
| Where the app runs | Server URL |
|---|---|
| Android **emulator** (server on same PC) | `ws://10.0.2.2:8080` |
| Real device, same Wi-Fi as PC | `ws://<your-PC-LAN-IP>:8080` |
| Anywhere (public) | `wss://your-domain` |

## 3. Try a call (two instances)

1. Launch on **two** devices/emulators.
2. Device A: set username `alice`, same server, same secret → Continue.
3. Device B: set username `bob`, same server, same secret → Continue.
4. On `alice`, tap **+ Add Contact**, enter `bob`, Add. Tap **Call**.
5. `bob`'s screen rings → Accept. You're connected.

Long-press a contact to delete it.

---

## Signaling protocol (username-routed)

Client → server: `register`, `presence`, `call`, `accept`, `reject`, `hangup`,
and the WebRTC relay messages `offer` / `answer` / `candidate` (each tagged with `callId`).
Server → client: `registered`, `register_error`, `incoming_call`, `calling`,
`call_accepted`, `call_rejected`, `call_failed`, `call_ended`, plus relayed WebRTC messages.

The caller is the WebRTC **initiator** (creates the offer) once the callee accepts.

---

## Notes on the earlier build error

`config.enableDtlsSrtp = true;` was removed — that field no longer exists in modern
libwebrtc because **DTLS-SRTP is always on and cannot be disabled**. Removing the line
changes nothing about security. (You found this correctly.)

The E2EE `FrameCryptor` key-provider call uses the 7-argument native signature
`(boolean sharedKey, byte[] ratchetSalt, int ratchetWindowSize, byte[] uncryptedMagicBytes,
int failureTolerance, int keyRingSize, boolean discardFrameWhenCryptorNotReady)`.
If you bump the `io.github.webrtc-sdk:android` version, keep the app code and the
native lib in sync or you'll hit an `UnsatisfiedLinkError` at runtime.

## Production hardening

- **Key exchange**: secret→SHA-256 is a demo. Use authenticated X25519 ECDH + a
  Short Authentication String users compare, to defeat a MITM at the signaling server.
  Hooks: `RtcEngine.deriveKey()` / `createKeyProvider()`.
- **TURN**: STUN fails on symmetric NATs. Deploy coturn, add it in `SignalingHub.iceServers()`.
- **TLS**: put signaling behind `wss://`, drop `usesCleartextTraffic`.
- **Push for offline ring**: to ring when the app is killed, add FCM + a high-priority
  data message that wakes the app and launches `CallActivity`.
- **Accounts/persistence**: the server keeps users in memory only. Add a datastore for
  durable accounts and offline call notifications.

## Requirement → code map

| Requirement | Where |
|---|---|
| AEC | `RtcEngine.initFactory()` `setUseHardwareAcousticEchoCanceler(true)` + `googEchoCancellation` |
| Noise Suppression | `setUseHardwareNoiseSuppressor(true)` + `googNoiseSuppression` |
| E2EE | `RtcEngine.createKeyProvider()`, `enableSenderEncryption()`, `enableReceiverDecryption()` |
| Contacts | `Store.java`, `MainActivity.java`, `ContactsAdapter.java` |
| Username routing | `server/server.js`, `SignalingClient.java`, `SignalingHub.java` |
| Incoming call UI | `CallActivity.java` (+ `activity_call.xml`) |
