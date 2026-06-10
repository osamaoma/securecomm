// server/server.js — username-routed signaling server for P2P Voice, with
// optional Firebase Cloud Messaging push so calls ring even when the app
// is closed on the callee's phone.
//
// Run locally:    node server.js
// Requires:       npm install      (installs ws, plus firebase-admin if you
//                                   want push). FCM is optional.
//
// Environment variables:
//   PORT                       — port to listen on (default 8080)
//   FCM_SERVICE_ACCOUNT_JSON   — Firebase Admin service account JSON, AS A
//                                 STRING. If set, the server will send push
//                                 messages on `call`. If unset, FCM is
//                                 skipped silently and the server behaves
//                                 exactly like before.
//   FCM_SERVICE_ACCOUNT_FILE   — alternative: path to a JSON file on disk.
//
//   --- Password-recovery email (optional) ---
//   SMTP_HOST                  — SMTP relay host (e.g. smtp.gmail.com,
//                                 smtp-relay.brevo.com). When unset, password
//                                 reset codes are LOGGED to console only.
//                                 That mode is fine for dev/staging — the
//                                 admin can read the code from Render's
//                                 Logs view and pass it to the user — but
//                                 NOT suitable for real users.
//   SMTP_PORT                  — usually 587 (STARTTLS) or 465 (TLS). Default 587.
//   SMTP_USER                  — auth username (often the from address).
//   SMTP_PASS                  — auth password / app password / API key.
//   SMTP_FROM                  — From: header on outgoing mail. Required if SMTP_HOST set.
//   SMTP_FROM_NAME             — display name for the From: header. Default "SecureComm".

const http = require('http');
const fs   = require('fs');
const path = require('path');
const crypto = require('crypto');
const WebSocket = require('ws');
let nodemailer = null;
try { nodemailer = require('nodemailer'); } catch (e) {
  console.warn('nodemailer not installed; password-reset email will be log-only');
}

const PORT = process.env.PORT || 8080;

// ----- Local media store -----
// Phones upload encrypted ciphertext here (we NEVER see the key) and
// download it back when delivering a media_msg. The server only ever
// touches opaque bytes.
const MEDIA_DIR     = path.join(__dirname, 'media-store');
const MEDIA_MAX_AGE = 7 * 24 * 3600 * 1000;   // 7 days
const MEDIA_MAX_SZ  = 16 * 1024 * 1024;       // 16 MB per file
try { fs.mkdirSync(MEDIA_DIR, { recursive: true }); } catch (_) {}

// Periodic GC of old files.
setInterval(() => {
  try {
    const now = Date.now();
    for (const f of fs.readdirSync(MEDIA_DIR)) {
      const full = path.join(MEDIA_DIR, f);
      try {
        const st = fs.statSync(full);
        if (now - st.mtimeMs > MEDIA_MAX_AGE) fs.unlinkSync(full);
      } catch (_) {}
    }
  } catch (_) {}
}, 3600 * 1000);

function safeMediaId(id) {
  // Accept 1-80 chars of url-safe base64-ish. No path traversal.
  return typeof id === 'string' && /^[A-Za-z0-9_-]{1,80}$/.test(id);
}

// ----- Optional Firebase Admin setup -----
let fcm = null;
try {
  const accountStr  = process.env.FCM_SERVICE_ACCOUNT_JSON;
  const accountFile = process.env.FCM_SERVICE_ACCOUNT_FILE;
  let serviceAccount = null;
  if (accountStr) {
    serviceAccount = JSON.parse(accountStr);
  } else if (accountFile) {
    serviceAccount = JSON.parse(require('fs').readFileSync(accountFile, 'utf8'));
  }
  if (serviceAccount) {
    const admin = require('firebase-admin');
    admin.initializeApp({ credential: admin.credential.cert(serviceAccount) });
    fcm = admin.messaging();
    console.log('FCM: enabled');
  } else {
    console.log('FCM: disabled (no service account configured)');
  }
} catch (err) {
  console.log('FCM: disabled (' + err.message + ')');
}

// ----- SMTP transport (optional) -----
// Used to email password-reset codes. If unconfigured, codes are written
// to the console and the operator can deliver them manually — fine for
// staging, never for production. Any nodemailer-compatible relay works;
// Gmail (app password), Brevo's free tier, Postmark, etc.
let smtp = null;
if (nodemailer && process.env.SMTP_HOST) {
  try {
    const smtpPort = parseInt(process.env.SMTP_PORT || '587', 10);
    smtp = nodemailer.createTransport({
      host: process.env.SMTP_HOST,
      port: smtpPort,
      // Port 465 is implicit-TLS; everything else (typically 587) uses STARTTLS.
      secure: smtpPort === 465,
      auth: (process.env.SMTP_USER && process.env.SMTP_PASS) ? {
        user: process.env.SMTP_USER,
        pass: process.env.SMTP_PASS,
      } : undefined,
    });
    console.log(`SMTP: enabled via ${process.env.SMTP_HOST}:${smtpPort}`);
  } catch (e) {
    smtp = null;
    console.log('SMTP: disabled (' + e.message + ')');
  }
} else {
  console.log('SMTP: disabled (no SMTP_HOST configured; reset codes will be logged only)');
}

function sendPasswordResetEmail(toEmail, username, code) {
  // Either send for real, or log the code so the operator can read it
  // off the Render dashboard and pass it along. The user-facing
  // response is the same in both modes — we never tell the requester
  // which transport happened, so log-only mode doesn't leak info.
  if (!smtp) {
    console.log(`[password reset] code for ${username} (${toEmail}): ${code}`);
    return Promise.resolve();
  }
  const fromAddr = process.env.SMTP_FROM || process.env.SMTP_USER;
  const fromName = process.env.SMTP_FROM_NAME || 'SecureComm';
  return smtp.sendMail({
    from: `"${fromName}" <${fromAddr}>`,
    to: toEmail,
    subject: 'Your SecureComm password reset code',
    text:
      `Someone (hopefully you) requested a password reset for the SecureComm ` +
      `account "${username}".\n\n` +
      `Your reset code is: ${code}\n\n` +
      `This code expires in 30 minutes. If you didn't request this, you can ignore this email.\n`,
  }).then(() => {
    console.log(`[password reset] emailed ${username} -> ${toEmail}`);
  }).catch((err) => {
    console.log(`[password reset] SMTP send failed for ${username}: ${err.message}`);
    // We log the code as a fallback so the user isn't completely stuck
    // when SMTP is misconfigured. This is the only path where we both
    // attempted SMTP AND log — visible to operators only.
    console.log(`[password reset] fallback code for ${username} (${toEmail}): ${code}`);
  });
}

function generateResetCode() {
  // 12 chars from an unambiguous alphabet (no 0/O/I/L/1) — ~60 bits of
  // entropy, easy to type from an email body or paper.
  const ALPHA = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';
  let out = '';
  const buf = crypto.randomBytes(12);
  for (let i = 0; i < 12; i++) out += ALPHA[buf[i] % ALPHA.length];
  return out;
}

// Reads a JSON body up to 16 KB and parses it. Calls cb(error, parsed).
// Used by the password-reset HTTP endpoints.
function readJsonBody(req, cb) {
  let len = 0;
  const chunks = [];
  req.on('data', (c) => {
    len += c.length;
    if (len > 16 * 1024) {
      req.destroy();
      cb('too large');
      return;
    }
    chunks.push(c);
  });
  req.on('end', () => {
    try {
      const body = JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}');
      cb(null, body);
    } catch (e) {
      cb('bad json');
    }
  });
  req.on('error', () => cb('read error'));
}

// ----- HTTP server (for healthchecks + media + WebSocket upgrade) -----
const httpServer = http.createServer((req, res) => {
  if (req.url === '/health') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({
      ok: true,
      online: users.size,
      fcm: !!fcm,
      smtp: !!smtp,
    }));
    return;
  }

  // Password reset endpoints. POST JSON; respond with JSON. Kept on HTTP
  // (rather than the WebSocket) because the user is by definition not
  // logged in — the WebSocket would auto-register them, which is the
  // wrong abstraction here. CORS is wide-open since the only callers
  // are the Android clients.
  if (req.method === 'POST' && req.url === '/password-reset/request') {
    readJsonBody(req, (err, body) => {
      if (err) { res.writeHead(400); res.end(err); return; }
      const name  = ((body.username || '') + '').trim().toLowerCase();
      const email = ((body.email    || '') + '').trim().toLowerCase();
      if (!name || !email) {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ sent: false, reason: 'invalid' }));
        return;
      }
      const storedEmail = usernameEmail.get(name);
      if (!storedEmail || storedEmail !== email) {
        // Generic response (sent:true) regardless of whether the
        // account/email actually matched — avoids leaking which
        // usernames are registered. Logs distinguish the cases for
        // the operator.
        console.log(`reset request rejected for ${name} (email mismatch or no account)`);
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ sent: true }));
        return;
      }
      for (const [code, info] of passwordResetCodes) {
        if (info.username === name) passwordResetCodes.delete(code);
      }
      const code = generateResetCode();
      passwordResetCodes.set(code, {
        username: name,
        expiresAt: Date.now() + RESET_CODE_TTL_MS,
      });
      sendPasswordResetEmail(email, name, code);
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ sent: true }));
    });
    return;
  }

  if (req.method === 'POST' && req.url === '/password-reset/complete') {
    readJsonBody(req, (err, body) => {
      if (err) { res.writeHead(400); res.end(err); return; }
      const code = ((body.code || '') + '').trim().toUpperCase();
      const newAuthKey = body.new_auth_key;
      if (!code || !newAuthKey) {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ ok: false, reason: 'invalid' }));
        return;
      }
      const entry = passwordResetCodes.get(code);
      if (!entry || entry.expiresAt < Date.now()) {
        passwordResetCodes.delete(code);
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ ok: false, reason: 'bad_or_expired_code' }));
        return;
      }
      usernameAuthKey.set(entry.username, newAuthKey);
      passwordResetCodes.delete(code);
      // Force-logout any current session so they re-sign in with the new password.
      const live = users.get(entry.username);
      if (live) {
        send(live, { type: 'force_logout', reason: 'password_reset' });
        try { live.username = null; live.terminate(); } catch (e) {}
        users.delete(entry.username);
      }
      console.log(`password reset completed for ${entry.username}`);
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ ok: true }));
    });
    return;
  }

  // Encrypted-blob upload. POST /media/<id> with raw ciphertext as body.
  // Phones POST here after encrypting locally; the server stores opaque bytes.
  if (req.method === 'POST' && req.url.startsWith('/media/')) {
    const id = req.url.substring('/media/'.length);
    if (!safeMediaId(id)) {
      res.writeHead(400); res.end('bad id'); return;
    }
    const dest = path.join(MEDIA_DIR, id);
    let received = 0;
    let aborted  = false;
    const out = fs.createWriteStream(dest);
    req.on('data', chunk => {
      received += chunk.length;
      if (received > MEDIA_MAX_SZ) {
        aborted = true;
        out.destroy();
        try { fs.unlinkSync(dest); } catch (_) {}
        if (!res.headersSent) { res.writeHead(413); res.end('too large'); }
        req.destroy();
      }
    });
    req.on('error', () => {
      aborted = true; try { out.destroy(); fs.unlinkSync(dest); } catch (_) {}
      if (!res.headersSent) { res.writeHead(400); res.end('upload error'); }
    });
    req.pipe(out);
    out.on('finish', () => {
      if (aborted) return;
      console.log('media stored ' + id + ' (' + received + ' bytes)');
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ ok: true, id, size: received }));
    });
    out.on('error', () => {
      if (!res.headersSent) { res.writeHead(500); res.end('write error'); }
    });
    return;
  }

  // Encrypted-blob download. GET /media/<id> returns raw ciphertext.
  // Phones GET here after receiving a media_msg pointing to this URL.
  if (req.method === 'GET' && req.url.startsWith('/media/')) {
    const id = req.url.substring('/media/'.length);
    if (!safeMediaId(id)) { res.writeHead(400); res.end('bad id'); return; }
    const src = path.join(MEDIA_DIR, id);
    fs.stat(src, (err, st) => {
      if (err) {
        console.log('media GET ' + id + ' -> 404 (not found)');
        res.writeHead(404); res.end('not found'); return;
      }
      console.log('media GET ' + id + ' -> 200 (' + st.size + ' bytes)');
      res.writeHead(200, {
        'Content-Type': 'application/octet-stream',
        'Content-Length': st.size,
        'Cache-Control': 'private, max-age=86400',
      });
      fs.createReadStream(src).pipe(res);
    });
    return;
  }

  res.writeHead(200, { 'Content-Type': 'text/plain' });
  res.end('P2P Voice signaling server\n');
});

const wss = new WebSocket.Server({ server: httpServer });

// ----- State -----
// username -> ws
const users = new Map();
// username -> { token, updated }  (FCM device token for push wakeup)
const fcmTokens = new Map();
// username -> { publicKey, version } where version is the client-supplied
// timestamp of when that keypair was generated. Lets clients detect
// rotation (a peer reinstalled, switched devices, or was impersonated).
const pubKeys = new Map();
// username -> base64 ed25519 public key. First device to register a
// username locks in its key; subsequent registers with a different key
// are rejected with "username_taken_by_another_device". Lives in memory
// only — server restart releases all bindings.
const usernamePubEd = new Map();
// username -> base64 PBKDF2 verifier of the user's password. Set on
// first sign-up; required to match on every subsequent register.
// Combined with usernamePubEd, this gives "something you know"
// (password) + "something you have" (device's Ed25519 private key).
// Also in-memory only; a server restart wipes everything.
const usernameAuthKey = new Map();
// username -> email address (lowercased). Used only for password-reset
// delivery. Set on first sign-up; updated on subsequent registers if
// the user supplies a new value AND the existing password/sig check
// succeeds (so an attacker can't change another user's recovery email
// without knowing their password).
const usernameEmail = new Map();
// resetCode -> { username, expiresAt }. Tokens are generated when a
// user asks for a password reset and consumed when they submit the
// new password. Single-use; expired entries are cleaned on access.
const passwordResetCodes = new Map();
const RESET_CODE_TTL_MS = 30 * 60 * 1000;  // 30 minutes
// callId -> { caller, callee, peers:Set }
const calls = new Map();
// username -> array of pending chat messages {id, from, ciphertext, ts}
// Held while the recipient is offline; flushed on their next register.
const pendingMessages = new Map();
const MAX_QUEUE_PER_USER = 200;

// Presence tracking. While a user is connected they're "online" — no entry
// needed in lastSeen. On disconnect we stamp the wall-clock time so peers
// can show "last seen at X". On reconnect we drop the entry again.
const lastSeen = new Map();   // username -> ms timestamp of disconnect
// Who wants presence updates about whom. Bidirectional index for fast
// lookup in both directions when a user comes online or goes offline.
//   presenceSubs : subscriber -> Set<watched>
//   presenceWatchers : watched -> Set<subscriber>
const presenceSubs = new Map();
const presenceWatchers = new Map();

function notePresence(username, online) {
  const ts = Date.now();
  if (online) {
    lastSeen.delete(username);
  } else {
    lastSeen.set(username, ts);
  }
  // Push to every subscriber currently watching this username.
  const watchers = presenceWatchers.get(username);
  if (!watchers) return;
  for (const sub of watchers) {
    const subWs = users.get(sub);
    if (subWs) {
      send(subWs, {
        type: 'presence_in',
        user: username,
        online,
        lastSeen: online ? 0 : ts,
      });
    }
  }
}

function unsubscribeAllFor(subscriber) {
  // Called when a user disconnects — remove them from every watcher set.
  const watched = presenceSubs.get(subscriber);
  if (!watched) return;
  for (const w of watched) {
    const set = presenceWatchers.get(w);
    if (set) {
      set.delete(subscriber);
      if (set.size === 0) presenceWatchers.delete(w);
    }
  }
  presenceSubs.delete(subscriber);
}

function send(ws, obj) {
  if (ws && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(obj));
}

function uid() {
  return Math.random().toString(36).slice(2) + Date.now().toString(36);
}

// Clean up any calls a socket is part of, notifying the other peer.
function endCallsFor(ws) {
  for (const [callId, call] of calls) {
    if (call.peers.has(ws)) {
      for (const peer of call.peers) {
        if (peer !== ws) send(peer, { type: 'call_ended', callId });
      }
      calls.delete(callId);
    }
  }
}

// Send an FCM data message to wake the callee's app for an incoming call.
async function pushIncomingCall(toUsername, fromUsername, callId) {
  if (!fcm) return;
  const entry = fcmTokens.get(toUsername);
  if (!entry || !entry.token) return;
  const msg = {
    token: entry.token,
    // Data-only message (no `notification` field) so the Android service
    // handles it directly. Use high priority for immediate delivery.
    data: {
      type: 'incoming_call',
      from: fromUsername,
      callId: callId,
    },
    android: {
      priority: 'high',
      ttl: 30 * 1000,            // 30 seconds — calls don't matter once stale
    },
  };
  try {
    const id = await fcm.send(msg);
    console.log(`fcm sent to ${toUsername} (${id})`);
  } catch (err) {
    console.log(`fcm failed to ${toUsername}: ${err.message}`);
    // If the token is now invalid, drop it so we don't keep retrying.
    if (err.code === 'messaging/registration-token-not-registered'
        || err.code === 'messaging/invalid-registration-token') {
      fcmTokens.delete(toUsername);
    }
  }
}

// Send an FCM data message to wake the recipient app for a new chat message.
// Unlike calls, we use normal priority and a 24h TTL so chats survive longer
// periods of the recipient being offline.
async function pushChatMessage(toUsername, fromUsername, count) {
  if (!fcm) return;
  const entry = fcmTokens.get(toUsername);
  if (!entry || !entry.token) return;
  const msg = {
    token: entry.token,
    data: {
      type: 'chat',
      from: fromUsername,
      count: String(count || 1),
    },
    android: {
      priority: 'high',
      ttl: 24 * 60 * 60 * 1000,
    },
  };
  try {
    await fcm.send(msg);
  } catch (err) {
    if (err.code === 'messaging/registration-token-not-registered'
        || err.code === 'messaging/invalid-registration-token') {
      fcmTokens.delete(toUsername);
    }
  }
}

// Send any messages queued for a user that just came online.
function flushPending(username, ws) {
  const queue = pendingMessages.get(username);
  if (!queue || queue.length === 0) return;
  for (const m of queue) send(ws, m);
  pendingMessages.delete(username);
  console.log(`flushed ${queue.length} queued message(s) to ${username}`);
}

// Robust message delivery. Always queues to pendingMessages and ALSO
// attempts a live send if the recipient is online. This protects against
// the message-loss race where the server writes to a half-open WebSocket
// (the client's socket has died but the server hasn't noticed yet) just
// before the client reconnects. Without queuing, those writes are lost
// to the dying TCP buffer and the new WS never sees them.
//
// On the next register from the recipient, flushPending re-sends the
// queue. Client-side idempotency handles duplicates:
//   - chat_msg: Store.putMessage replaces by id
//   - contact_request_in: Store.putIncomingContactRequest replaces by from
//   - profile_msg: ProfileManager keeps only the newer version
//   - group_event_msg: roster ops idempotent
// The 200-message cap and flushPending's queue-delete keep memory bounded.
// Returns 'live' if the recipient was online, 'queued' otherwise.
function deliverOrQueue(to, envelope) {
  let q = pendingMessages.get(to);
  if (!q) { q = []; pendingMessages.set(to, q); }
  q.push(envelope);
  if (q.length > MAX_QUEUE_PER_USER) q.shift();
  const recipientWs = users.get(to);
  if (recipientWs && recipientWs.readyState === WebSocket.OPEN) {
    send(recipientWs, envelope);
    return 'live';
  }
  return 'queued';
}

wss.on('connection', (ws) => {
  ws.username = null;
  ws.isAlive = true;
  ws.on('pong', () => { ws.isAlive = true; });

  ws.on('message', (raw) => {
    let msg;
    try { msg = JSON.parse(raw); } catch { return; }

    switch (msg.type) {

      // --- Registration: client announces its username and proves ownership
      // by signing a challenge with an Ed25519 key the server has on file
      // for this username (or which it stores as authoritative on first use).
      // Also verifies a password-derived auth_key, which gives the user
      // a recoverable secret (password) on top of the device-bound key.
      case 'register': {
        const name    = (msg.username || '').trim().toLowerCase();
        const pubEd   = msg.pubkey_ed;     // base64 raw 32-byte ed25519 public key
        const authTs  = msg.auth_ts;       // ms since epoch
        const authSig = msg.auth_sig;      // base64 ed25519 signature
        const authKey = msg.auth_key;      // base64 32-byte PBKDF2(password, "p2pvoice-"+username)
        const email   = (msg.email || '').trim().toLowerCase();   // recovery email, optional but recommended
        if (!name) { send(ws, { type: 'register_error', reason: 'empty' }); return; }
        if (!pubEd || typeof authTs !== 'number' || !authSig) {
          send(ws, { type: 'register_error', reason: 'auth_missing' }); return;
        }
        if (!authKey) {
          send(ws, { type: 'register_error', reason: 'password_missing' }); return;
        }

        // Reject stale/replayed challenges. ±5 minutes of server clock.
        const now = Date.now();
        if (Math.abs(now - authTs) > 5 * 60 * 1000) {
          send(ws, { type: 'register_error', reason: 'auth_stale' }); return;
        }

        // Verify the Ed25519 signature over `register:<username>:<ts>`.
        const challenge = `register:${name}:${authTs}`;
        let sigValid = false;
        try {
          const pubKeyBuf = Buffer.from(pubEd, 'base64');
          if (pubKeyBuf.length !== 32) throw new Error('bad pubkey length');
          // Node's crypto wants the raw 32-byte ed25519 key wrapped as JWK.
          // base64url has no padding and uses -/_ instead of +/=.
          const xB64Url = pubKeyBuf.toString('base64')
              .replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
          const publicKey = crypto.createPublicKey({
            key: { kty: 'OKP', crv: 'Ed25519', x: xB64Url },
            format: 'jwk',
          });
          sigValid = crypto.verify(
              null,
              Buffer.from(challenge),
              publicKey,
              Buffer.from(authSig, 'base64'));
        } catch (err) {
          console.log(`auth verify error for ${name}: ${err.message}`);
        }
        if (!sigValid) {
          send(ws, { type: 'register_error', reason: 'auth_failed' }); return;
        }

        // Password verification: the client sends a 32-byte key derived
        // from PBKDF2(password, "p2pvoice-"+username, 200k iter, SHA-256).
        // The server stores this verifier verbatim on first sign-up; on
        // every subsequent register the values must match. The server
        // never sees the plaintext password.
        const boundAuthKey = usernameAuthKey.get(name);
        if (boundAuthKey && boundAuthKey !== authKey) {
          console.log(`auth: rejected ${name} — wrong password`);
          send(ws, { type: 'register_error', reason: 'wrong_password' });
          return;
        }
        if (!boundAuthKey) {
          usernameAuthKey.set(name, authKey);
          console.log(`auth: bound ${name} password verifier`);
        }

        // Username/device binding. Password matched (or just bound), so:
        //   - same Ed25519 key      → routine re-login on the same device
        //   - different Ed25519 key → device migration. User reinstalled
        //     on a new phone and proved ownership via password; rebind
        //     usernamePubEd to the new key. Peers will see a key rotation
        //     and the SAS will change — that's expected and intentional.
        const boundPubEd = usernamePubEd.get(name);
        if (!boundPubEd) {
          usernamePubEd.set(name, pubEd);
          console.log(`auth: bound ${name} to ed25519 key`);
        } else if (boundPubEd !== pubEd) {
          usernamePubEd.set(name, pubEd);
          console.log(`auth: device migration for ${name} (password ok, new ed25519 key)`);
        }

        // Update recovery email if provided. Only happens here, after
        // password + signature have been verified, so an attacker can't
        // hijack a recovery email without knowing the password.
        if (email && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
          const prev = usernameEmail.get(name);
          if (prev !== email) {
            usernameEmail.set(name, email);
            console.log(`recovery email ${prev ? 'updated' : 'set'} for ${name}`);
          }
        }

        const existing = users.get(name);
        if (existing && existing !== ws) {
          endCallsFor(existing);
          send(existing, { type: 'force_logout', reason: 'logged_in_elsewhere' });
          try { existing.username = null; existing.terminate(); } catch (e) {}
          users.delete(name);
        }

        ws.username = name;
        users.set(name, ws);
        send(ws, { type: 'registered', username: name });
        console.log(`registered: ${name} (online=${users.size})`);
        // Deliver anything we queued while they were away.
        flushPending(name, ws);
        // Notify anyone subscribed to this user's presence that they're online.
        notePresence(name, true);
        break;
      }

      // --- FCM device token from a client; stored against its username ---
      case 'fcm_token': {
        if (!ws.username) return;
        const token = (msg.token || '').trim();
        if (!token) return;
        // If this exact device-token is currently mapped to a different
        // user, drop the old binding. Happens when one account signs out
        // and another signs in on the same device — without this, push
        // notifications for the old account would still wake the device
        // (now showing the new account's UI).
        for (const [u, rec] of fcmTokens.entries()) {
          if (u !== ws.username && rec.token === token) {
            fcmTokens.delete(u);
            console.log(`fcm token transferred ${u} -> ${ws.username}`);
          }
        }
        fcmTokens.set(ws.username, { token, updated: Date.now() });
        console.log(`fcm token registered for ${ws.username}`);
        break;
      }

      // --- E2EE: client uploads its long-term X25519 public key ---
      //     in:  { type:'pubkey_upload', publicKey:'<b64>', version:<long> }
      case 'pubkey_upload': {
        if (!ws.username) return;
        const pk = (msg.publicKey || '').trim();
        if (!pk) return;
        // version is the client-side timestamp of keypair generation.
        // Fall back to current time so old clients without version still work.
        const version = Number(msg.version) > 0 ? Number(msg.version) : Date.now();
        const existing = pubKeys.get(ws.username);
        if (existing && existing.publicKey === pk) {
          // Same key, just keep existing version (don't drift on re-upload).
          break;
        }
        pubKeys.set(ws.username, { publicKey: pk, version });
        console.log(`pubkey stored for ${ws.username} (v=${version})`);
        break;
      }

      // --- E2EE: client asks for a peer's public key ---
      //     in:  { type:'pubkey_request', peer:'<username>' }
      //     out: { type:'pubkey_response', peer:'<username>',
      //             publicKey:'<b64>'|null, version:<long>|null }
      case 'pubkey_request': {
        if (!ws.username) return;
        const peer = (msg.peer || '').trim().toLowerCase();
        if (!peer) return;
        const entry = pubKeys.get(peer);
        send(ws, {
          type: 'pubkey_response',
          peer,
          publicKey: entry ? entry.publicKey : null,
          version:   entry ? entry.version   : null,
        });
        break;
      }

      // --- Chat: encrypted text message from sender to recipient ---
      //   in:  { type:'chat_send', to, id, ciphertext, replyTo?, forwarded? }
      //   out: { type:'chat_msg', from, id, ciphertext, replyTo?, forwarded?, ts }
      //        { type:'chat_ack', id, status:'sent'|'queued' } to sender
      case 'chat_send': {
        if (!ws.username) return;
        const to = (msg.to || '').trim().toLowerCase();
        const id = (msg.id || '').trim();
        const ciphertext = msg.ciphertext;
        if (!to || !id || !ciphertext) return;

        const envelope = {
          type: 'chat_msg',
          from: ws.username,
          id,
          ciphertext,
          ts: Date.now(),
        };
        if (msg.replyTo)   envelope.replyTo = msg.replyTo;
        if (msg.forwarded) envelope.forwarded = true;
        if (msg.groupId)   envelope.groupId = msg.groupId;
        const status = deliverOrQueue(to, envelope);
        if (status === 'live') {
          send(ws, { type: 'chat_ack', id, status: 'sent' });
          console.log(`chat ${ws.username} -> ${to} (delivered)`);
        } else {
          const queueLen = (pendingMessages.get(to) || []).length;
          send(ws, { type: 'chat_ack', id, status: 'queued' });
          pushChatMessage(to, ws.username, queueLen);
          console.log(`chat ${ws.username} -> ${to} (queued, ${queueLen} pending)`);
        }
        break;
      }

      // --- Chat edit ("edited within 15min" client policy is enforced
      // on the sender side; the server just forwards the new ciphertext
      // for the existing message id). ---
      //   in:  { type:'chat_edit', to, id, ciphertext, groupId? }
      //   out: { type:'chat_edit_in', from, id, ciphertext, groupId?, ts }
      case 'chat_edit': {
        if (!ws.username) return;
        const to = (msg.to || '').trim().toLowerCase();
        const id = (msg.id || '').trim();
        const ciphertext = msg.ciphertext;
        if (!to || !id || !ciphertext) return;
        const envelope = {
          type: 'chat_edit_in',
          from: ws.username,
          id,
          ciphertext,
          ts: Date.now(),
        };
        if (msg.groupId) envelope.groupId = msg.groupId;
        const status = deliverOrQueue(to, envelope);
        console.log(`chat_edit ${ws.username} -> ${to}${status === 'queued' ? ' (queued)' : ''}`);
        break;
      }

      // --- Chat delete ("unsend") ---
      //   in:  { type:'chat_delete', to, id, groupId? }
      //   out: { type:'chat_delete_in', from, id, groupId?, ts }
      case 'chat_delete': {
        if (!ws.username) return;
        const to = (msg.to || '').trim().toLowerCase();
        const id = (msg.id || '').trim();
        if (!to || !id) return;
        const envelope = {
          type: 'chat_delete_in',
          from: ws.username,
          id,
          ts: Date.now(),
        };
        if (msg.groupId) envelope.groupId = msg.groupId;
        const status = deliverOrQueue(to, envelope);
        console.log(`chat_delete ${ws.username} -> ${to}${status === 'queued' ? ' (queued)' : ''}`);
        break;
      }

      // --- Contact request (sender asks recipient to add them) ---
      // The recipient gets a prompt with Accept/Decline. Acceptance flows
      // back as contact_accept (handled below). Decline is local — we
      // don't tell the requester anything, to avoid leaking the recipient's
      // decision.
      //   in:  { type:'contact_request', to, displayName? }
      //   out: { type:'contact_request_in', from, displayName?, ts }
      case 'contact_request': {
        if (!ws.username) return;
        const to = (msg.to || '').trim().toLowerCase();
        if (!to || to === ws.username) return;
        const displayName = typeof msg.displayName === 'string' ? msg.displayName : '';
        const envelope = {
          type: 'contact_request_in',
          from: ws.username,
          displayName,
          ts: Date.now(),
        };
        const status = deliverOrQueue(to, envelope);
        console.log(`contact_request ${ws.username} -> ${to}${status === 'queued' ? ' (queued)' : ''}`);
        break;
      }

      // --- Contact request accepted by the recipient ---
      //   in:  { type:'contact_accept', to, displayName? }
      //   out: { type:'contact_accept_in', from, displayName?, ts }
      case 'contact_accept': {
        if (!ws.username) return;
        const to = (msg.to || '').trim().toLowerCase();
        if (!to) return;
        const displayName = typeof msg.displayName === 'string' ? msg.displayName : '';
        const envelope = {
          type: 'contact_accept_in',
          from: ws.username,
          displayName,
          ts: Date.now(),
        };
        const status = deliverOrQueue(to, envelope);
        console.log(`contact_accept ${ws.username} -> ${to}${status === 'queued' ? ' (queued)' : ''}`);
        break;
      }

      // --- Read receipts ---
      // The recipient tells the sender they've now seen one or more
      // previously-delivered messages. We batch the ids to keep churn down.
      //   in:  { type:'chat_read', to, ids:[...] }
      //   out: { type:'chat_read_in', from, ids, ts }
      // No offline queue: read receipts are nice-to-have, not durable, and
      // sending stale ones after the sender reconnects has no value.
      case 'chat_read': {
        if (!ws.username) return;
        const to = (msg.to || '').trim().toLowerCase();
        const ids = Array.isArray(msg.ids) ? msg.ids.filter(x => typeof x === 'string') : [];
        if (!to || ids.length === 0) return;
        const recipientWs = users.get(to);
        if (recipientWs && recipientWs.readyState === WebSocket.OPEN) {
          send(recipientWs, {
            type: 'chat_read_in',
            from: ws.username,
            ids,
            ts: Date.now(),
          });
        }
        break;
      }

      // --- Typing indicator ---
      // Transient signal — never queued. Recipient times it out themselves
      // after a few seconds so a missed "stop" notification doesn't strand
      // the indicator on screen.
      //   in:  { type:'typing', to, typing:bool, groupId? }
      //   out: { type:'typing_in', from, typing, groupId?, ts }
      case 'typing': {
        if (!ws.username) return;
        const to = (msg.to || '').trim().toLowerCase();
        const typing = !!msg.typing;
        if (!to) return;
        const envelope = {
          type: 'typing_in',
          from: ws.username,
          typing,
          ts: Date.now(),
        };
        if (msg.groupId) envelope.groupId = msg.groupId;
        const recipientWs = users.get(to);
        if (recipientWs && recipientWs.readyState === WebSocket.OPEN) {
          send(recipientWs, envelope);
        }
        break;
      }

      // --- Presence subscription ---
      // The client wants ongoing online/offline updates about a single peer
      // (typically the one whose chat they just opened). We immediately
      // reply with the current state, then push updates as it changes.
      //   in:  { type:'presence_subscribe', user }
      //   out: { type:'presence_in', user, online, lastSeen, ts }  (immediate + future)
      case 'presence_subscribe': {
        if (!ws.username) return;
        const user = (msg.user || '').trim().toLowerCase();
        if (!user) return;
        let subSet = presenceSubs.get(ws.username);
        if (!subSet) { subSet = new Set(); presenceSubs.set(ws.username, subSet); }
        subSet.add(user);
        let watchSet = presenceWatchers.get(user);
        if (!watchSet) { watchSet = new Set(); presenceWatchers.set(user, watchSet); }
        watchSet.add(ws.username);
        const online = users.has(user);
        send(ws, {
          type: 'presence_in',
          user,
          online,
          lastSeen: online ? 0 : (lastSeen.get(user) || 0),
        });
        break;
      }

      case 'presence_unsubscribe': {
        if (!ws.username) return;
        const user = (msg.user || '').trim().toLowerCase();
        if (!user) return;
        const subSet = presenceSubs.get(ws.username);
        if (subSet) subSet.delete(user);
        const watchSet = presenceWatchers.get(user);
        if (watchSet) {
          watchSet.delete(ws.username);
          if (watchSet.size === 0) presenceWatchers.delete(user);
        }
        break;
      }

      // --- Media: reference to an encrypted blob in external storage ---
      //   in:  { type:'media_send', to, id, url, mediaType, width, height,
      //          thumbnail?, caption?, durationMs? }
      //   out: { type:'media_msg', from, id, url, mediaType, width, height,
      //          thumbnail?, caption?, durationMs?, ts } to recipient
      //        { type:'media_ack', id, status:'sent'|'queued' } to sender
      case 'media_send': {
        if (!ws.username) return;
        const to = (msg.to || '').trim().toLowerCase();
        const id = (msg.id || '').trim();
        const url = (msg.url || '').trim();
        if (!to || !id || !url) return;

        const envelope = {
          type: 'media_msg',
          from: ws.username,
          id,
          url,
          mediaType: msg.mediaType || 'image/jpeg',
          width:     msg.width  || 0,
          height:    msg.height || 0,
          ts: Date.now(),
        };
        if (msg.thumbnail)  envelope.thumbnail = msg.thumbnail;
        if (msg.caption)    envelope.caption = msg.caption;
        if (msg.durationMs) envelope.durationMs = msg.durationMs;
        if (msg.replyTo)    envelope.replyTo = msg.replyTo;
        if (msg.forwarded)  envelope.forwarded = true;
        if (msg.groupId)    envelope.groupId = msg.groupId;

        const status = deliverOrQueue(to, envelope);
        if (status === 'live') {
          send(ws, { type: 'media_ack', id, status: 'sent' });
          console.log(`media ${ws.username} -> ${to} (delivered)`);
        } else {
          const queueLen = (pendingMessages.get(to) || []).length;
          send(ws, { type: 'media_ack', id, status: 'queued' });
          pushChatMessage(to, ws.username, queueLen);
          console.log(`media ${ws.username} -> ${to} (queued, ${queueLen} pending)`);
        }
        break;
      }

      // --- Group roster/membership/key events ---
      //   in:  { type:'group_event_send', to, groupId, event, payloadCt? }
      //   out: { type:'group_event_msg', from, groupId, event, payloadCt?, ts } to recipient
      // event is one of 'invite' | 'update' | 'leave'. payloadCt is opaque to
      // the server (encrypted with the pairwise key). Always queued for offline
      // recipients so they catch up on reconnect; we keep only the latest
      // 'invite'/'update' per (sender, groupId) pair to avoid stale spam.
      case 'group_event_send': {
        if (!ws.username) return;
        const to = (msg.to || '').trim().toLowerCase();
        const groupId = (msg.groupId || '').trim();
        const event = (msg.event || '').trim();
        if (!to || !groupId || !event) return;
        const envelope = {
          type: 'group_event_msg',
          from: ws.username,
          groupId,
          event,
          ts: Date.now(),
        };
        if (msg.payloadCt) envelope.payloadCt = msg.payloadCt;
        // Coalesce invite/update events: drop any older invite/update from
        // the same sender for the same group from the pending queue — the
        // newest carries the latest roster + key. Done before we add the
        // new envelope so the queue ends up with only the newest.
        if (event === 'invite' || event === 'update') {
          const q = pendingMessages.get(to);
          if (q) {
            for (let i = q.length - 1; i >= 0; i--) {
              const e = q[i];
              if (e.type === 'group_event_msg'
                  && e.from === ws.username
                  && e.groupId === groupId
                  && (e.event === 'invite' || e.event === 'update')) {
                q.splice(i, 1);
              }
            }
          }
        }
        const status = deliverOrQueue(to, envelope);
        console.log(`group_event ${ws.username} -> ${to} (${event} ${groupId}${status === 'queued' ? ', queued' : ''})`);
        break;
      }

      // --- Profile: encrypted display name + avatar metadata, push to one peer ---
      //   in:  { type:'profile_send', to, payloadCt, version }
      //   out: { type:'profile_msg', from, payloadCt, version, ts }
      // payloadCt is an opaque ciphertext from the client's POV — the server
      // never decodes it. version is the sender's profile timestamp; receivers
      // ignore stale updates by comparing to their cached value.
      case 'profile_send': {
        if (!ws.username) return;
        const to = (msg.to || '').trim().toLowerCase();
        const payloadCt = msg.payloadCt;
        if (!to || !payloadCt) return;
        const envelope = {
          type: 'profile_msg',
          from: ws.username,
          payloadCt,
          version: msg.version || Date.now(),
          ts: Date.now(),
        };
        // Coalesce: drop any older profile_msg from the same sender before
        // queuing the new one. Keeps the queue from holding stale profiles.
        const q = pendingMessages.get(to);
        if (q) {
          for (let i = q.length - 1; i >= 0; i--) {
            if (q[i].type === 'profile_msg' && q[i].from === ws.username) q.splice(i, 1);
          }
        }
        const status = deliverOrQueue(to, envelope);
        console.log(`profile ${ws.username} -> ${to}${status === 'queued' ? ' (queued)' : ' (delivered)'}`);
        break;
      }

      // --- Reactions: small emoji attached to a previously-sent message ---
      //   in:  { type:'reaction_send', to, targetId, emoji }   (emoji="" = remove)
      //   out: { type:'reaction_msg', from, targetId, emoji, ts } to recipient
      // Reactions are best-effort: if the recipient is offline we queue them
      // alongside other pending messages so they catch up on reconnect.
      case 'reaction_send': {
        if (!ws.username) return;
        const to = (msg.to || '').trim().toLowerCase();
        const targetId = (msg.targetId || '').trim();
        if (!to || !targetId) return;
        const envelope = {
          type: 'reaction_msg',
          from: ws.username,
          targetId,
          emoji: typeof msg.emoji === 'string' ? msg.emoji : '',
          ts: Date.now(),
        };
        if (msg.groupId) envelope.groupId = msg.groupId;
        const status = deliverOrQueue(to, envelope);
        console.log(`reaction ${ws.username} -> ${to} (${envelope.emoji || 'clear'}${status === 'queued' ? ', queued' : ''})`);
        break;
      }

      // --- Presence: is a username online? ---
      case 'presence': {
        const target = (msg.username || '').trim().toLowerCase();
        send(ws, { type: 'presence', username: target, online: users.has(target) });
        break;
      }

      // --- Caller initiates a call to a username ---
      case 'call': {
        const callee = (msg.to || '').trim().toLowerCase();
        const calleeWs = users.get(callee);
        const isVideo = !!msg.isVideo;
        // Even if not WebSocket-connected, we can still try to wake the
        // callee via FCM. Only mark "offline" if we have NO way to reach.
        if (!calleeWs && !fcmTokens.has(callee)) {
          send(ws, { type: 'call_failed', to: callee, reason: 'offline' });
          return;
        }
        if (calleeWs === ws) {
          send(ws, { type: 'call_failed', to: callee, reason: 'self' });
          return;
        }
        const callId = uid();
        const peers = new Set([ws]);
        if (calleeWs) peers.add(calleeWs);
        calls.set(callId, { caller: ws.username, callee, peers, isVideo });
        ws.callId = callId;
        if (calleeWs) {
          send(calleeWs, {
            type: 'incoming_call',
            from: ws.username,
            callId,
            isVideo,
          });
        }
        // Push wakeup is independent of WebSocket presence: if the callee's
        // app is in background, the WS message above won't render UI fast
        // enough; the FCM push wakes it up and the WS message arrives once
        // the app reconnects.
        pushIncomingCall(callee, ws.username, callId);
        send(ws, { type: 'calling', to: callee, callId, isVideo });
        console.log(`call ${ws.username} -> ${callee} (${callId}) video=${isVideo}`);
        break;
      }

      // --- Callee accepts ---
      case 'accept': {
        const call = calls.get(msg.callId);
        if (!call) return;
        ws.callId = msg.callId;
        // If callee was woken by FCM, its WebSocket may have just reconnected
        // and not yet in the peers set. Add it now.
        if (ws.username === call.callee) call.peers.add(ws);
        const callerWs = users.get(call.caller);
        const isVideo = !!call.isVideo;
        send(callerWs, { type: 'call_accepted', callId: msg.callId, initiator: true, isVideo });
        send(ws,       { type: 'call_accepted', callId: msg.callId, initiator: false, isVideo });
        console.log(`accepted ${msg.callId}`);
        break;
      }

      // --- Callee rejects ---
      case 'reject': {
        const call = calls.get(msg.callId);
        if (!call) return;
        const callerWs = users.get(call.caller);
        send(callerWs, { type: 'call_rejected', callId: msg.callId });
        calls.delete(msg.callId);
        console.log(`rejected ${msg.callId}`);
        break;
      }

      // --- Hang up / cancel ---
      case 'hangup': {
        const call = calls.get(msg.callId);
        if (!call) return;
        for (const peer of call.peers) {
          if (peer !== ws) send(peer, { type: 'call_ended', callId: msg.callId });
        }
        calls.delete(msg.callId);
        console.log(`hangup ${msg.callId}`);
        break;
      }

      // --- WebRTC signaling relay (offer/answer/candidate) ---
      case 'offer':
      case 'answer':
      case 'candidate': {
        const call = calls.get(msg.callId);
        if (!call) return;
        // Make sure the sender is in the peer set (FCM-woken callees may
        // reconnect during the offer/answer dance).
        call.peers.add(ws);
        for (const peer of call.peers) {
          if (peer !== ws && peer.readyState === WebSocket.OPEN) {
            peer.send(raw.toString());
          }
        }
        break;
      }

      default:
        break;
    }
  });

  ws.on('close', () => {
    if (ws.username && users.get(ws.username) === ws) {
      users.delete(ws.username);
      console.log(`disconnected: ${ws.username} (online=${users.size})`);
      notePresence(ws.username, false);
      unsubscribeAllFor(ws.username);
    }
    endCallsFor(ws);
  });

  ws.on('error', () => {});
});

// --- Heartbeat: drop dead sockets so usernames get freed promptly ---
const heartbeat = setInterval(() => {
  for (const ws of wss.clients) {
    if (ws.isAlive === false) {
      try { ws.terminate(); } catch (e) {}
      continue;
    }
    ws.isAlive = false;
    try { ws.ping(); } catch (e) {}
  }
}, 30000);

wss.on('close', () => clearInterval(heartbeat));

httpServer.listen(PORT, () => {
  console.log(`Signaling server listening on :${PORT} (ws + http)`);
});
