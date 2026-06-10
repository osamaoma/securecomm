# SecureComm — Threat Model

**Document version:** 1.0
**Last updated:** June 2026
**Audience:** Security auditors, procurement, compliance officers, prospective deploying organizations

This document describes what SecureComm protects against, what it does *not* protect against, and the assumptions a deploying organization must validate. It is deliberately specific and deliberately honest — overstated security claims are the fastest way to fail an audit and the most reliable way to harm the people relying on the product.

---

## 1. Product summary

SecureComm is a self-hosted, end-to-end encrypted messenger for Android with voice, video, group chat, file transfer, and friend-request-based contact addition. It consists of:

- An Android client (minSdk 24 / Android 7.0+) with WebRTC for media.
- A Node.js signaling server the deploying organization runs themselves.
- Optional Firebase Cloud Messaging (FCM) for push wake-up when the app is closed.

The product is sold under a self-hosted / white-label model — each customer organization runs its own signaling server, generates its own brand, and serves a closed user community (employees, members, clients).

## 2. Assets being protected

In rough order of sensitivity:

1. **Message content** — text, images, voice notes, documents, location, contact cards.
2. **Real-time call media** — audio and video frames during a voice or video call.
3. **Cryptographic keys** — X25519 long-term identity keys stored per-account on each device.
4. **Contact graph** — the list of who has whom in their contacts.
5. **Communication metadata** — who messaged whom, when, how often, and message sizes.
6. **Group membership** — who belongs to which group.
7. **User authentication state** — the binding between a username and a specific device.

## 3. Threat actors considered

| Actor | Capability assumed |
|---|---|
| Network observer (ISP, hotel WiFi, hostile nation-state on the wire) | Can read and modify all unencrypted traffic. Cannot break TLS 1.2/1.3 with current algorithms. |
| Compromised TURN relay (third-party) | Sees IP addresses of both call parties; cannot decrypt media. |
| Signaling-server operator | Sees all metadata that transits the server. Sees no message content. **In self-hosted deployments this is the deploying organization itself.** |
| FCM provider (Google) | If FCM is enabled, sees push-notification payloads. |
| Malicious peer (another user of the same deployment) | Can attempt key substitution, message replay, traffic analysis. |
| Compromised endpoint (rooted phone, malware on device) | Reads everything in the app's data directory. |
| Lost / stolen unlocked device | Reads everything in the app's data directory. |
| Lost / stolen locked device | Limited to side-channel attacks; depends on device-level encryption. |

We do **not** model nation-state attackers with arbitrary endpoint exploits; no application-layer messenger can. We document residual risks below.

## 4. Cryptographic foundations

All claims in this document depend on the following primitives being correctly implemented in the upstream libraries we use. The application code that wraps them is in scope for any third-party audit.

| Primitive | Use | Library |
|---|---|---|
| X25519 ECDH | Long-term identity keypair, peer-to-peer shared secret derivation | Google Tink |
| SHA-256 | Conversation key derivation from shared secret + context string | JDK |
| AES-GCM, 256-bit key, 96-bit IV, 128-bit auth tag | Authenticated encryption of text messages and media bodies | JDK (`AES/GCM/NoPadding`) |
| AES-GCM (via WebRTC FrameCryptor) | Per-frame encryption of voice and video RTP | WebRTC native (libwebrtc) |
| TLS 1.2+ | All transport between client and signaling server | OkHttp / OS truststore |

**Conversation key derivation** (the heart of the design):
```
shared = X25519(my_private, peer_public)
conversation_key = SHA-256(shared || "p2pvoice-v1" || min(usernameA, usernameB) || max(usernameA, usernameB))
```

This produces a 32-byte symmetric key that both parties derive independently and never transmit. The username context binds the key to a specific pair so that swapping endpoints invalidates prior keys.

**Wire format** for an encrypted message body:
```
[12-byte random IV] [ciphertext || 16-byte GCM tag]
```

**Short Authentication String (SAS):** a 6-digit decimal derived from the conversation key, shown to both users on first contact and after key rotation. Users compare the SAS out-of-band (phone call, in person) to detect man-in-the-middle attacks by the server.

## 5. Threats SecureComm protects against

### 5.1 Content confidentiality on the wire and on the server
**Threat:** Anyone other than the two endpoints reads the message content.
**Mitigation:** AES-GCM with a key only the two endpoints can derive. The signaling server stores and forwards opaque ciphertext for offline-queued messages and uploaded media; it has no decryption capability.
**Residual risk:** None at the cryptographic layer if endpoints are uncompromised.

### 5.2 Real-time call eavesdropping
**Threat:** Server or TURN relay records voice/video.
**Mitigation:** WebRTC FrameCryptor applies AES-GCM to every RTP frame, keyed from the same conversation key as text. The SFU/TURN sees only opaque encrypted frames.
**Residual risk:** Same conversation key used for both text and media — compromise of one compromises the other.

### 5.3 Active man-in-the-middle by the server
**Threat:** Server lies about a user's public key during the initial key fetch, inserting itself between A and B.
**Mitigation:** Short Authentication String comparison. Both users see the same 6-digit code derived from the conversation key; if the server is MITMing them, the codes do not match. The UI prompts re-verification after any key rotation.
**Residual risk:** Users must actually compare the SAS. Social engineering may bypass this.

### 5.4 Key replacement attacks
**Threat:** Attacker (server, malicious user) replaces a long-term key.
**Mitigation:** Key rotation is explicitly flagged with a `ROTATED` indicator in the UI; the conversation requires re-verification via SAS before continuing.
**Residual risk:** Users who dismiss the prompt without verifying lose the protection.

### 5.5 Block / mute / contact-request abuse
**Threat:** Strangers spam, harass, or fingerprint users via unsolicited contact attempts.
**Mitigation:** Contact additions require explicit acceptance (the friend-request mechanism). Block and mute are supported. Blocked-sender calls auto-reject with the same wire signal as a busy line (no leak that the user is blocked).

### 5.6 Local data isolation across accounts on shared devices
**Threat:** A user signs out and a different user signs in; the new user can read the previous user's data.
**Mitigation:** Each account's storage is namespaced (`p2pvoice_user_<username>`), so signing in as a different account opens a separate, empty data store. Existing data remains encrypted at rest by the OS device encryption.
**Residual risk:** A rooted device defeats this.

### 5.7 Group message confidentiality
**Threat:** Group server learns group message content.
**Mitigation:** Group messages are sent pairwise-encrypted (one envelope per recipient using each recipient's conversation key) rather than via a shared group key on the server. Server sees N opaque envelopes, no plaintext.
**Residual risk:** Server learns group membership and timing.

## 6. Threats SecureComm does NOT protect against

This list is the most important section of this document. Read it carefully.

### 6.1 No forward secrecy
**What this means:** SecureComm uses *static* X25519 long-term identity keys, not ephemeral ratcheting keys (the Signal Protocol's Double Ratchet). The conversation key between two users is deterministic from their identity keys.

**Practical consequence:** If an attacker captures encrypted message traffic today and later compromises a device, they can decrypt the captured traffic. Signal and Wire would not be decryptable in this scenario; SecureComm would be.

**Mitigation available:** Manual key rotation (the `ROTATED` flow) generates a new identity keypair and forces SAS re-verification. Frequent rotation reduces the window of capture-and-decrypt exposure but does not eliminate it for past traffic.

**Future work:** Adopting a Double-Ratchet implementation (libsignal, MLS) is a significant engineering project — explicitly on the roadmap but not implemented in this version.

### 6.2 No protection against compromised endpoints
A rooted Android device, a device infected with spyware, or a device given to a forensic examiner unlocked allows full extraction of the SharedPreferences-stored keys and message history. This is true of all messenger apps; SecureComm provides no additional defense beyond standard Android sandboxing. Users storing high-value content should use locked, regularly-updated devices and avoid sideloading other apps from untrusted sources.

### 6.3 Server sees metadata
The deploying organization's signaling server sees:

- Online/offline state of every user.
- Source and destination username on every message and call.
- Timestamps and approximate message sizes.
- Group identifiers and membership rosters (since the server delivers pairwise envelopes, it learns the recipient list).
- FCM tokens registered per user.

In a self-hosted deployment this is acceptable because the deploying organization is the legitimate operator. In any other deployment model it is a meaningful disclosure.

### 6.4 FCM payload metadata leaks to Google
When FCM is enabled, every push notification carries a data payload containing the sender's username and event type (`incoming_call` or `chat`). Google therefore learns:

- That user A sent a message or call to user B at time T.
- It does not learn the message content.

**Mitigation options for the deployer:**
- Disable FCM entirely. Cost: no push wake-up; the app must be open or kept alive by a foreground service to receive calls and messages. Workable for power users; inappropriate for general users.
- Replace FCM with a self-hosted push channel (e.g., a long-lived WebSocket via a foreground service). Cost: significant battery drain and OEM-specific reliability issues.

This is the single most significant unmitigated metadata leak in the current design.

### 6.5 TURN server sees connection IPs
WebRTC NAT traversal currently uses third-party public TURN servers. These see the IP addresses of both call parties, the duration and direction of calls, and the encrypted RTP volume. They cannot decrypt media.

**Mitigation:** Deploy your own `coturn` instance on infrastructure you control. This is recommended for production deployments. See the deployment guide.

### 6.6 No protection against the deploying organization
The signaling server is run by the customer organization. That organization can:

- Observe all metadata listed in §6.3.
- Inject malicious app updates if it also controls the app distribution channel.
- Force users to re-verify keys (which they could refuse) but cannot decrypt past or future messages by doing so.

This is by design — the model is that the deploying organization is the trusted party. Members of the user community who do not trust their deploying organization should not use any messenger that organization administers.

### 6.7 No traffic analysis resistance
Message timing, sizes, and frequencies are visible to anyone with sufficient network observation. SecureComm does not pad messages, batch them, or use cover traffic. A determined observer can infer conversation patterns even without decrypting content.

### 6.8 No account recovery
Identity keys exist only on the device. Losing the device loses the account's key history. There is no server-side key escrow, no encrypted backup, no recovery phrase. This is a feature for security but a usability liability.

**Mitigation:** Users should be told to verify SAS with critical contacts after a device change. The application provides a new identity key on each fresh install.

### 6.9 Side channels
Timing attacks, power analysis, electromagnetic emanations, acoustic side channels — not in scope. We rely on the upstream libraries (Tink, JDK, WebRTC) for constant-time primitives where they matter, and we accept the platform's defaults otherwise.

### 6.10 iOS
There is no iOS client. Organizations whose population includes iOS users cannot deploy SecureComm exclusively.

## 7. Deployment hardening checklist for organizations

Before going live with SecureComm in your organization, validate the following:

- [ ] Signaling server runs over TLS (`wss://`). HTTP downgrade is not allowed.
- [ ] Server's TLS certificate is from a recognized CA (Let's Encrypt is acceptable).
- [ ] The FCM service account JSON is stored as an environment variable or secret file, not committed to source control.
- [ ] The FCM project is owned by the deploying organization, not a personal account.
- [ ] Server filesystem is on an encrypted volume.
- [ ] Server SSH access is key-only, root login disabled, fail2ban or equivalent enabled.
- [ ] Server `ufw` or equivalent firewall allows only ports 22 (SSH, from admin IPs), 80 (HTTP-to-HTTPS redirect), and 443 (HTTPS/WSS).
- [ ] A coturn instance is deployed in the same organization's infrastructure and configured in the app's ICE server list. Public free TURN servers are removed before production rollout.
- [ ] FCM is either deliberately enabled with awareness of §6.4, or disabled.
- [ ] The Android APK is distributed through a channel the organization controls (MDM, internal app store, signed APK direct distribution) — not a public app store using a personal developer account.
- [ ] APK signing key is held by the deploying organization in a hardware security module or equivalent.
- [ ] An incident-response plan exists for the case of a stolen device claiming to be a known user.
- [ ] Users have been trained to compare SAS codes out of band before discussing sensitive information.

## 8. Known limitations explicit roadmap

| Limitation | Severity | Roadmap status |
|---|---|---|
| No forward secrecy | High for high-threat users | Planned: investigate libsignal or MLS migration |
| FCM payload metadata leak | High | Planned: silent-push redesign with opaque tokens |
| In-memory server state lost on restart | Medium | Planned: Redis-backed offline queue |
| No iOS client | High for mixed-platform orgs | Not currently scoped |
| No multi-device support | Medium | Not currently scoped |
| No disappearing messages | Low | Possible future feature |
| No screen-recording / screenshot protection | Low | Possible future feature (FLAG_SECURE) |
| No traffic-pattern obfuscation | Acceptable for typical threat models | No plan |

## 9. Third-party security review

This product has not yet undergone a third-party cryptographic audit. Deploying organizations operating under regulatory frameworks (HIPAA, attorney-client privilege jurisdictions, classified-information handling) should commission a focused review by a recognized firm (Cure53, Trail of Bits, NCC Group) before production rollout. We will assist auditors with code access, design documentation, and reproducible builds on request.

## 10. Contact

Security disclosures: `security@<your-domain>` (PGP key fingerprint published at `/security` on the marketing site).

Disclosures involving active exploitation: please contact us first; we follow a 90-day responsible-disclosure window with credit for the reporter.

---

*This document is a living artifact. It is updated whenever the threat surface changes (new feature, new dependency, new known vulnerability). Each version is signed and dated. Archived versions are available on request.*
