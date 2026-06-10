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

const http = require('http');
const fs   = require('fs');
const path = require('path');
const crypto = require('crypto');
const WebSocket = require('ws');

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

// ----- HTTP server (for healthchecks + media + WebSocket upgrade) -----
const httpServer = http.createServer((req, res) => {
  if (req.url === '/health') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({
      ok: true,
      online: users.size,
      fcm: !!fcm,
    }));
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
      case 'register': {
        const name    = (msg.username || '').trim().toLowerCase();
        const pubEd   = msg.pubkey_ed;     // base64 raw 32-byte ed25519 public key
        const authTs  = msg.auth_ts;       // ms since epoch
        const authSig = msg.auth_sig;      // base64 ed25519 signature
        if (!name) { send(ws, { type: 'register_error', reason: 'empty' }); return; }
        if (!pubEd || typeof authTs !== 'number' || !authSig) {
          send(ws, { type: 'register_error', reason: 'auth_missing' }); return;
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

        // Username binding: the FIRST device to register a username wins
        // and from then on only signatures from that same Ed25519 key can
        // re-register as that username. NOTE: this binding lives only in
        // memory; a process restart wipes it. Persistent storage is a
        // follow-up (see THREAT_MODEL §6 + roadmap).
        const boundPubEd = usernamePubEd.get(name);
        if (boundPubEd && boundPubEd !== pubEd) {
          console.log(`auth: rejected ${name} — different ed25519 key`);
          send(ws, { type: 'register_error', reason: 'username_taken_by_another_device' });
          return;
        }
        if (!boundPubEd) {
          usernamePubEd.set(name, pubEd);
          console.log(`auth: bound ${name} to ed25519 key`);
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
        const recipientWs = users.get(to);
        if (recipientWs && recipientWs.readyState === WebSocket.OPEN) {
          send(recipientWs, envelope);
          send(ws, { type: 'chat_ack', id, status: 'sent' });
          console.log(`chat ${ws.username} -> ${to} (delivered)`);
        } else {
          let q = pendingMessages.get(to);
          if (!q) { q = []; pendingMessages.set(to, q); }
          q.push(envelope);
          if (q.length > MAX_QUEUE_PER_USER) q.shift();
          send(ws, { type: 'chat_ack', id, status: 'queued' });
          pushChatMessage(to, ws.username, q.length);
          console.log(`chat ${ws.username} -> ${to} (queued, ${q.length} pending)`);
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
        const recipientWs = users.get(to);
        if (recipientWs && recipientWs.readyState === WebSocket.OPEN) {
          send(recipientWs, envelope);
          console.log(`chat_edit ${ws.username} -> ${to}`);
        } else {
          // Use the same offline queue as chat_send so the edit follows
          // the original message into the recipient's catch-up batch.
          let q = pendingMessages.get(to);
          if (!q) { q = []; pendingMessages.set(to, q); }
          q.push(envelope);
          if (q.length > MAX_QUEUE_PER_USER) q.shift();
          console.log(`chat_edit ${ws.username} -> ${to} (queued)`);
        }
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
        const recipientWs = users.get(to);
        if (recipientWs && recipientWs.readyState === WebSocket.OPEN) {
          send(recipientWs, envelope);
          console.log(`chat_delete ${ws.username} -> ${to}`);
        } else {
          let q = pendingMessages.get(to);
          if (!q) { q = []; pendingMessages.set(to, q); }
          q.push(envelope);
          if (q.length > MAX_QUEUE_PER_USER) q.shift();
          console.log(`chat_delete ${ws.username} -> ${to} (queued)`);
        }
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
        const recipientWs = users.get(to);
        if (recipientWs && recipientWs.readyState === WebSocket.OPEN) {
          send(recipientWs, envelope);
          console.log(`contact_request ${ws.username} -> ${to}`);
        } else {
          let q = pendingMessages.get(to);
          if (!q) { q = []; pendingMessages.set(to, q); }
          q.push(envelope);
          if (q.length > MAX_QUEUE_PER_USER) q.shift();
          console.log(`contact_request ${ws.username} -> ${to} (queued)`);
        }
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
        const recipientWs = users.get(to);
        if (recipientWs && recipientWs.readyState === WebSocket.OPEN) {
          send(recipientWs, envelope);
          console.log(`contact_accept ${ws.username} -> ${to}`);
        } else {
          let q = pendingMessages.get(to);
          if (!q) { q = []; pendingMessages.set(to, q); }
          q.push(envelope);
          if (q.length > MAX_QUEUE_PER_USER) q.shift();
          console.log(`contact_accept ${ws.username} -> ${to} (queued)`);
        }
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

        const recipientWs = users.get(to);
        if (recipientWs && recipientWs.readyState === WebSocket.OPEN) {
          send(recipientWs, envelope);
          send(ws, { type: 'media_ack', id, status: 'sent' });
          console.log(`media ${ws.username} -> ${to} (delivered)`);
        } else {
          let q = pendingMessages.get(to);
          if (!q) { q = []; pendingMessages.set(to, q); }
          q.push(envelope);
          if (q.length > MAX_QUEUE_PER_USER) q.shift();
          send(ws, { type: 'media_ack', id, status: 'queued' });
          pushChatMessage(to, ws.username, q.length);  // same wake-up mechanism
          console.log(`media ${ws.username} -> ${to} (queued, ${q.length} pending)`);
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
        const recipientWs = users.get(to);
        if (recipientWs && recipientWs.readyState === WebSocket.OPEN) {
          send(recipientWs, envelope);
          console.log(`group_event ${ws.username} -> ${to} (${event} ${groupId})`);
        } else {
          let q = pendingMessages.get(to);
          if (!q) { q = []; pendingMessages.set(to, q); }
          // Coalesce: drop any older invite/update from the same sender for
          // the same group — the newest carries the latest roster + key.
          if (event === 'invite' || event === 'update') {
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
          q.push(envelope);
          if (q.length > MAX_QUEUE_PER_USER) q.shift();
          console.log(`group_event ${ws.username} -> ${to} (queued ${event})`);
        }
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
        const recipientWs = users.get(to);
        if (recipientWs && recipientWs.readyState === WebSocket.OPEN) {
          send(recipientWs, envelope);
          console.log(`profile ${ws.username} -> ${to} (delivered)`);
        } else {
          // Profile updates are useful when the peer comes back online; queue them.
          // To avoid stale spam, only keep the latest profile_msg from each sender.
          let q = pendingMessages.get(to);
          if (!q) { q = []; pendingMessages.set(to, q); }
          // Drop any older profile_msg from the same sender; keep only the newest.
          for (let i = q.length - 1; i >= 0; i--) {
            if (q[i].type === 'profile_msg' && q[i].from === ws.username) q.splice(i, 1);
          }
          q.push(envelope);
          if (q.length > MAX_QUEUE_PER_USER) q.shift();
          console.log(`profile ${ws.username} -> ${to} (queued)`);
        }
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
        const recipientWs = users.get(to);
        if (recipientWs && recipientWs.readyState === WebSocket.OPEN) {
          send(recipientWs, envelope);
          console.log(`reaction ${ws.username} -> ${to} (${envelope.emoji || 'clear'})`);
        } else {
          let q = pendingMessages.get(to);
          if (!q) { q = []; pendingMessages.set(to, q); }
          q.push(envelope);
          if (q.length > MAX_QUEUE_PER_USER) q.shift();
          console.log(`reaction ${ws.username} -> ${to} (queued)`);
        }
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
