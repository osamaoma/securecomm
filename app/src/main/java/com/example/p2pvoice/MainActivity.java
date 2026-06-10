package com.example.p2pvoice;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.example.p2pvoice.databinding.ActivityMainBinding;

import java.util.ArrayList;
import java.util.List;

/**
 * Contacts home screen. Shows saved contacts, lets the user add/remove them,
 * and start outgoing calls. Hosts the persistent SignalingHub listener so it
 * can launch the CallActivity when an incoming call arrives.
 */
public class MainActivity extends AppCompatActivity
        implements SignalingHub.UiListener, ContactsAdapter.OnContactAction {

    private ActivityMainBinding b;
    private Store store;
    private SignalingHub hub;
    private final List<Contact> contacts = new ArrayList<>();
    private ContactsAdapter adapter;
    private Contact pendingCall; // call after permission granted

    /** Refresh the contact list when chat events occur so unread badges
     *  update without the user having to navigate away and back. */
    private final SignalingHub.ChatListener chatRefreshListener = new SignalingHub.ChatListener() {
        @Override public void onChatMessage(ChatMessage msg) { runOnUiThread(MainActivity.this::loadContacts); }
        @Override public void onChatStatus(String peer, String id, ChatMessage.Status status) {}
        @Override public void onPeerProfileUpdated(String peer) {
            runOnUiThread(MainActivity.this::loadContacts);
        }
        @Override public void onGroupChanged(String groupId) {
            runOnUiThread(MainActivity.this::loadContacts);
        }
        @Override public void onContactRequestsChanged() {
            // Re-render both the banner (contains incoming requests) and the
            // contact list (a newly-accepted request appears here).
            runOnUiThread(() -> {
                renderInvites();
                loadContacts();
            });
        }
    };

    private final ActivityResultLauncher<String[]> permLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> {
                        Boolean mic = result.get(Manifest.permission.RECORD_AUDIO);
                        if (mic != null && mic && pendingCall != null) {
                            dial(pendingCall);
                        } else {
                            Toast.makeText(this, "Microphone permission required",
                                    Toast.LENGTH_LONG).show();
                        }
                        pendingCall = null;
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new Store(this);
        store.backfillLastActivityIfNeeded();
        hub = SignalingHub.get(this);

        if (!store.hasProfile()) {
            startActivity(new Intent(this, SetupActivity.class));
            finish();
            return;
        }

        b = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());

        b.tvMe.setText("@" + store.getUsername());

        adapter = new ContactsAdapter(contacts, this, store);
        b.rvContacts.setLayoutManager(new LinearLayoutManager(this));
        b.rvContacts.setAdapter(adapter);

        b.btnAdd.setOnClickListener(v -> showAddDialog());
        b.btnNewGroup.setOnClickListener(v ->
                startActivity(new Intent(this, GroupCreateActivity.class)));
        b.btnSearch.setOnClickListener(v ->
                startActivity(new Intent(this, SearchActivity.class)));
        b.btnSignOut.setOnClickListener(v -> confirmSignOut());
        b.btnProfile.setOnClickListener(v ->
                startActivity(new Intent(this, ProfileActivity.class)));
        b.btnHistory.setOnClickListener(v ->
                startActivity(new Intent(this, CallLogActivity.class)));

        loadContacts();
    }

    private void confirmSignOut() {
        new AlertDialog.Builder(this)
                .setTitle("Sign out?")
                .setMessage("You'll need to pick a username again. Your saved contacts are kept.")
                .setPositiveButton("Sign out", (d, w) -> doSignOut())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void doSignOut() {
        hub.shutdown();
        store.clearProfile();
        startActivity(new Intent(this, SetupActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK));
        finish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hub.setUiListener(this);
        hub.addChatListener(chatRefreshListener);
        // Reflect current state right away (status callbacks only fire on changes).
        b.tvConn.setTextColor(hub.isRegistered() ? ContextCompat.getColor(this, R.color.conn_online) : ContextCompat.getColor(this, R.color.conn_offline));
        // Ensure connection/registration is live for receiving calls.
        hub.connectAndRegister();
        // Re-render contacts so unread badges reflect any reads / new arrivals
        // while we were away.
        loadContacts();

        // Ask for permission to show notifications on Android 13+, then fetch
        // an FCM token and push it to the server. Skip silently if Firebase
        // hasn't been configured (google-services.json missing).
        ensureNotificationPermission();
        refreshFcmToken();
    }

    @Override
    protected void onPause() {
        super.onPause();
        hub.removeChatListener(chatRefreshListener);
    }

    private void ensureNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            String perm = "android.permission.POST_NOTIFICATIONS";
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, perm)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{perm}, 4242);
            }
        }
    }

    private void refreshFcmToken() {
        // Lazy-load the Firebase classes via reflection so the app still builds
        // and runs when google-services.json is missing.
        try {
            Class<?> fmClass = Class.forName("com.google.firebase.messaging.FirebaseMessaging");
            Object fm = fmClass.getMethod("getInstance").invoke(null);
            Object task = fmClass.getMethod("getToken").invoke(fm);
            // Task#addOnSuccessListener(OnSuccessListener)
            Class<?> osl = Class.forName("com.google.android.gms.tasks.OnSuccessListener");
            Object listener = java.lang.reflect.Proxy.newProxyInstance(
                    osl.getClassLoader(),
                    new Class[]{osl},
                    (proxy, method, args) -> {
                        if ("onSuccess".equals(method.getName()) && args != null && args[0] instanceof String) {
                            String token = (String) args[0];
                            store.setFcmToken(token);
                            hub.publishFcmToken(token);
                        }
                        return null;
                    });
            task.getClass().getMethod("addOnSuccessListener", osl).invoke(task, listener);
        } catch (Throwable ignored) {
            // Firebase not on classpath / not configured — that's fine. The app
            // still works in foreground; just no push wakeup for closed app.
        }
    }

    private void loadContacts() {
        contacts.clear();
        contacts.addAll(store.getContacts());
        for (Group g : store.getGroups()) {
            Contact pseudo = new Contact(g.peerId(), g.name);
            contacts.add(pseudo);
        }
        // Sort by most recent activity, newest first. Conversations with no
        // history yet (last activity = 0) sink to the bottom but keep their
        // relative order from store.getContacts() — which is insertion order
        // — so freshly-added contacts don't jump around unexpectedly.
        java.util.Collections.sort(contacts, (a, bC) -> {
            long ta = store.getLastActivity(a.username);
            long tb = store.getLastActivity(bC.username);
            return Long.compare(tb, ta);
        });
        adapter.notifyDataSetChanged();
        boolean empty = contacts.isEmpty();
        b.tvEmpty.setVisibility(empty ? android.view.View.VISIBLE : android.view.View.GONE);
        b.rvContacts.setVisibility(empty ? android.view.View.GONE : android.view.View.VISIBLE);
        renderInvites();
    }

    /** Pin pending group invites to the top of the screen as banners with
     *  Accept/Decline buttons. Hidden entirely when there are none. */
    private void renderInvites() {
        java.util.List<Store.PendingInvite> pending = store.getPendingInvites();
        java.util.List<Store.PendingContactRequest> contactReqs =
                store.getIncomingContactRequests();
        b.invitesContainer.removeAllViews();
        if (pending.isEmpty() && contactReqs.isEmpty()) {
            b.invitesContainer.setVisibility(android.view.View.GONE);
            return;
        }
        b.invitesContainer.setVisibility(android.view.View.VISIBLE);
        android.view.LayoutInflater inf = android.view.LayoutInflater.from(this);
        // Contact requests first (more time-sensitive than group invites).
        for (Store.PendingContactRequest cr : contactReqs) {
            android.view.View row = inf.inflate(R.layout.item_pending_invite,
                    b.invitesContainer, false);
            String displayName = cr.displayName != null && !cr.displayName.isEmpty()
                    ? cr.displayName : cr.from;
            ((android.widget.TextView) row.findViewById(R.id.tvInviteTitle))
                    .setText(displayName + " wants to add you");
            ((android.widget.TextView) row.findViewById(R.id.tvInviteMeta))
                    .setText("@" + cr.from);
            final String fromUser = cr.from;
            row.findViewById(R.id.btnInviteAccept).setOnClickListener(v -> {
                hub.acceptContactRequest(fromUser);
                loadContacts();
            });
            row.findViewById(R.id.btnInviteReject).setOnClickListener(v -> {
                hub.declineContactRequest(fromUser);
                renderInvites();
            });
            b.invitesContainer.addView(row);
        }
        for (Store.PendingInvite pi : pending) {
            android.view.View row = inf.inflate(R.layout.item_pending_invite,
                    b.invitesContainer, false);
            // Inviter display name if we have it.
            Store.PeerProfile prof = store.getPeerProfile(pi.inviter);
            String inviterName = prof != null && prof.displayName != null
                    && !prof.displayName.isEmpty() ? prof.displayName : pi.inviter;
            ((android.widget.TextView) row.findViewById(R.id.tvInviteTitle))
                    .setText(inviterName + " invited you to \"" + pi.groupName + "\"");
            ((android.widget.TextView) row.findViewById(R.id.tvInviteMeta))
                    .setText(pi.members.size() + " member"
                            + (pi.members.size() == 1 ? "" : "s"));
            row.findViewById(R.id.btnInviteAccept).setOnClickListener(v -> {
                Group g = hub.acceptInvite(pi.groupId);
                if (g != null) {
                    Intent i = new Intent(MainActivity.this, ChatActivity.class);
                    i.putExtra(ChatActivity.EXTRA_PEER, g.peerId());
                    startActivity(i);
                }
                loadContacts();
            });
            row.findViewById(R.id.btnInviteReject).setOnClickListener(v -> {
                hub.rejectInvite(pi.groupId);
                loadContacts();
            });
            b.invitesContainer.addView(row);
        }
    }

    private void showAddDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);

        EditText etUser = new EditText(this);
        etUser.setHint("username");
        etUser.setInputType(InputType.TYPE_CLASS_TEXT);
        box.addView(etUser);

        TextView explain = new TextView(this);
        explain.setText("They'll receive a request and choose whether to accept. Once they accept, the contact appears in your list.");
        explain.setTextSize(12f);
        explain.setTextColor(androidx.core.content.ContextCompat.getColor(
                this, R.color.text_dim));
        int t = (int) (12 * getResources().getDisplayMetrics().density);
        explain.setPadding(0, t, 0, 0);
        box.addView(explain);

        new AlertDialog.Builder(this)
                .setTitle("Add contact")
                .setView(box)
                .setPositiveButton("Send Request", (d, w) -> {
                    String u = etUser.getText().toString().trim().toLowerCase();
                    if (u.isEmpty()) { Toast.makeText(this, "Enter a username", Toast.LENGTH_SHORT).show(); return; }
                    if (u.equals(store.getUsername())) { Toast.makeText(this, "That's you!", Toast.LENGTH_SHORT).show(); return; }
                    // Check why the request might fail to give useful feedback.
                    for (Contact existing : store.getContacts()) {
                        if (existing.username.equalsIgnoreCase(u)) {
                            Toast.makeText(this, "Already in your contacts",
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                    }
                    if (store.hasOutgoingContactRequest(u)) {
                        Toast.makeText(this,
                                "Already waiting for them to accept",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    boolean ok = hub.requestContact(u);
                    if (ok) {
                        Toast.makeText(this,
                                "Request sent — they'll appear in your contacts once they accept",
                                Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this, "Couldn't send request",
                                Toast.LENGTH_SHORT).show();
                    }
                    renderInvites();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---- ContactsAdapter.OnContactAction ----
    @Override
    public void onCall(Contact c) {
        if (Group.isGroupPeerId(c.username)) {
            // No group calls in v1; the adapter hides the call button but
            // we defend the entry point too.
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            dial(c);
        } else {
            pendingCall = c;
            permLauncher.launch(new String[]{
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.BLUETOOTH_CONNECT
            });
        }
    }

    @Override
    public void onChat(Contact c) {
        // Works for both 1-on-1 and group rows: c.username is either the
        // peer's username (1-on-1) or "group:<uuid>" (group).
        Intent i = new Intent(this, ChatActivity.class);
        i.putExtra(ChatActivity.EXTRA_PEER, c.username);
        startActivity(i);
    }

    @Override
    public void onLongPress(Contact c) {
        if (Group.isGroupPeerId(c.username)) {
            // Group long-press: jump straight into the group info screen.
            Intent i = new Intent(this, GroupInfoActivity.class);
            i.putExtra(GroupInfoActivity.EXTRA_GROUP_ID,
                    Group.groupIdFromPeerId(c.username));
            startActivity(i);
            return;
        }
        boolean muted = store.isMuted(c.username);
        boolean blocked = store.isBlocked(c.username);
        // Menu adapts to current state — toggle wording flips between
        // "Mute" / "Unmute" and "Block" / "Unblock" so the action label
        // always describes what tapping it will do.
        String[] items = new String[] {
                muted   ? "Unmute notifications" : "Mute notifications",
                blocked ? "Unblock contact"      : "Block contact",
                "Delete contact",
        };
        new AlertDialog.Builder(this)
                .setTitle(c.displayName)
                .setItems(items, (d, w) -> {
                    switch (w) {
                        case 0: // mute toggle
                            store.setMuted(c.username, !muted);
                            android.widget.Toast.makeText(this,
                                    (!muted ? "Muted " : "Unmuted ") + c.displayName,
                                    android.widget.Toast.LENGTH_SHORT).show();
                            loadContacts();
                            break;
                        case 1: // block toggle
                            if (!blocked) {
                                new AlertDialog.Builder(this)
                                        .setTitle("Block " + c.displayName + "?")
                                        .setMessage("They won't be able to message or call you. " +
                                                "Their messages will be silently dropped.")
                                        .setPositiveButton("Block", (dd, ww) -> {
                                            store.setBlocked(c.username, true);
                                            loadContacts();
                                        })
                                        .setNegativeButton("Cancel", null)
                                        .show();
                            } else {
                                store.setBlocked(c.username, false);
                                android.widget.Toast.makeText(this,
                                        "Unblocked " + c.displayName,
                                        android.widget.Toast.LENGTH_SHORT).show();
                                loadContacts();
                            }
                            break;
                        case 2: // delete
                            store.removeContact(c.username);
                            loadContacts();
                            break;
                    }
                })
                .show();
    }

    private void dial(Contact c) {
        hub.placeCall(c.username);
        Intent i = new Intent(this, CallActivity.class);
        i.putExtra(CallActivity.EXTRA_PEER, c.displayName);
        i.putExtra(CallActivity.EXTRA_MODE, CallActivity.MODE_OUTGOING);
        startActivity(i);
    }

    // ---- SignalingHub.UiListener ----
    @Override public void onConnectionState(String state) {
        boolean online = "connected".equals(state);
        b.tvConn.setTextColor(online ? ContextCompat.getColor(this, R.color.conn_online) : ContextCompat.getColor(this, R.color.conn_offline));
    }
    @Override public void onRegistered(String username) {
        b.tvConn.setTextColor(ContextCompat.getColor(this, R.color.conn_online));
    }
    @Override public void onRegisterError(String reason) {
        Toast.makeText(this, "Register error: " + reason, Toast.LENGTH_LONG).show();
    }
    @Override public void onIncomingCall(String from, String callId, boolean isVideo) {
        // The SignalingHub launches CallActivity directly (so the ring fires
        // regardless of which screen is foreground), so nothing to do here.
    }
    @Override public void onOutgoingRinging(String to) {}
    @Override public void onCallConnected(String peer) {}
    @Override public void onCallEnded(String reason) {}
}
