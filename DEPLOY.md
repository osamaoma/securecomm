# Deploying P2P Voice — FCM + Hosted Signaling Server

This guide walks through two things you do **once**:

1. Setting up Firebase Cloud Messaging so calls ring even when the app is killed.
2. Deploying the Node signaling server to a real internet host so it's reachable from anywhere.

After both, the app works like a real phone app: friends can call you from anywhere and your phone rings even if the app isn't open.

---

## Part 1 — Firebase project + FCM setup

### 1.1 Create a Firebase project

1. Go to <https://console.firebase.google.com/>
2. Click **Add project** → name it something like `p2pvoice` → Continue.
3. Skip Google Analytics (you don't need it) → Create project.

### 1.2 Register the Android app

1. In the project home, click the **Android icon** to add an Android app.
2. **Android package name:** `com.example.p2pvoice` (must match exactly).
3. App nickname (anything) and SHA-1 (skip — not needed for FCM).
4. Click **Register app**.
5. **Download `google-services.json`.**
6. Drop the file into your project at `app/google-services.json`.
   - The build is already configured to detect this file automatically.
   - If the file isn't there, the app still builds; FCM features just stay inactive.
7. You can skip the remaining "Add Firebase SDK" steps in the Firebase console — the Gradle changes are already in your project.

### 1.3 Generate a service account for the server

The server needs admin credentials to send pushes to user devices.

1. In Firebase console, click the **gear icon → Project settings**.
2. Tab **Service accounts** → **Generate new private key** → Generate key.
3. A JSON file downloads. **Keep it secret** — anyone with this can send pushes as your project.
4. You'll upload its contents to your hosting provider in Part 2.

### 1.4 Test it locally (optional)

If you want to test FCM before deploying:

```bash
cd server
npm install                              # installs firebase-admin too now
export FCM_SERVICE_ACCOUNT_FILE=/path/to/the-downloaded-key.json
node server.js
```

The console should print `FCM: enabled` on startup. Open the app on both phones — when one calls the other, you'll see `fcm sent to ...` in the server log.

---

## Part 2 — Deploying the server

You have two solid free options. I recommend **Render** for simplicity; **Fly.io** is also great.

### Option A: Render (simplest)

1. Push your code to a GitHub repo (just the `server/` folder is enough; or the whole project).
2. Go to <https://render.com> → Sign up / sign in with GitHub.
3. New → **Web Service** → connect your repo.
4. Configure:
   - **Root directory:** `server`
   - **Build command:** `npm install`
   - **Start command:** `node server.js`
   - **Instance type:** Free
5. Under **Environment** add a variable:
   - Key: `FCM_SERVICE_ACCOUNT_JSON`
   - Value: paste the **entire contents** of the service account JSON file from step 1.3 (yes, the whole JSON blob as one string)
6. Click **Create Web Service**. Wait ~2 minutes.
7. Render gives you a URL like `https://p2pvoice-signaling.onrender.com`.
   - WebSocket URL is the same but with `wss://`, so: `wss://p2pvoice-signaling.onrender.com`
8. Visit `https://<your-url>/health` in a browser — you should see JSON like `{"ok":true,"online":0,"fcm":true}`. If `fcm` is `true`, FCM is wired up correctly.

**Render free tier caveat:** the service sleeps after 15 minutes of inactivity. First call after a sleep takes ~30 seconds to wake up. For a real product you'd pay $7/month for "Always-on", but for personal/dev use the free tier is fine.

### Option B: Fly.io

1. Install `flyctl`: `curl -L https://fly.io/install.sh | sh`
2. `cd server && fly launch` — answer no to "Postgres / Redis", yes to "deploy now".
3. Set the service account: `fly secrets set FCM_SERVICE_ACCOUNT_JSON='<paste JSON>'`
4. `fly deploy`
5. Your URL is `wss://<app-name>.fly.dev`.

Fly's free tier is generous and the service doesn't sleep.

### Option C: $5 DigitalOcean / Hetzner / Linode droplet

If you want full control:

1. Spin up the smallest Ubuntu droplet.
2. `ssh` in, install Node 20+, clone the repo, `npm install`.
3. Put the service account JSON file somewhere safe and export `FCM_SERVICE_ACCOUNT_FILE=/root/firebase-key.json`.
4. Use `pm2` to keep it running: `npm i -g pm2 && pm2 start server.js && pm2 startup && pm2 save`.
5. Put nginx in front for TLS, or use Caddy which gets TLS automatically:
   ```
   yourdomain.com {
       reverse_proxy localhost:8080
   }
   ```

---

## Part 3 — Point the app at the deployed server

1. Open the app, sign out (button on the contacts screen).
2. On the Setup screen, change **Server URL** to your deployed URL.
   - **Must start with `wss://`** (not `ws://`) for any cloud host with TLS.
   - Example: `wss://p2pvoice-signaling.onrender.com`
3. Pick your username, hit Continue.

Repeat on the other phone(s).

### Cleartext traffic on production

Once you're on `wss://` everywhere, you can remove `android:usesCleartextTraffic="true"` from `AndroidManifest.xml` for tighter security. It's only there because the localhost dev server uses unencrypted `ws://`.

---

## Part 4 — How FCM push works end-to-end

For your understanding:

1. On app start, the app fetches an **FCM device token** from Google. This token uniquely identifies that app install on that phone.
2. The app sends the token to your server (`{type:"fcm_token", token:"..."}`).
3. The server stores `username → token`.
4. When user A calls user B:
   - The server sends the usual `incoming_call` WebSocket message (works if B's app is in foreground).
   - The server ALSO sends an FCM high-priority data message to B's device token.
5. FCM delivers the push directly to B's phone, which wakes the app even if killed.
6. The app's `PushService.onMessageReceived` runs, launches the ringing screen, and reconnects the WebSocket. The actual SDP offer/answer follows over WebSocket as before.

Two WebSocket messages plus one FCM push, and the call rings every time regardless of app state.

---

## Troubleshooting

**Server shows `FCM: disabled`** → service account env var not set or JSON is malformed. Render: check the environment tab. Fly: `fly secrets list`.

**App builds but Firebase warnings appear** → `google-services.json` not in `app/`. Drop it there and rebuild.

**`/health` shows `fcm:false` but I set the JSON** → typo in env var name. It must be exactly `FCM_SERVICE_ACCOUNT_JSON`.

**Push not arriving on Android** → check:
1. The app has been opened at least once after install (so the token was generated).
2. Server logs show `fcm token registered for <name>` followed later by `fcm sent to <name> (<msg-id>)`.
3. On the receiving phone, battery optimizations may be killing FCM. Settings → Apps → P2P Voice → Battery → Unrestricted.

**Token registered but push silently fails** → for Android 13+ you must have **POST_NOTIFICATIONS permission** granted (the app requests it on launch). If you denied it once, manually re-grant in app settings.
