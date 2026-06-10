package com.example.p2pvoice;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.example.p2pvoice.databinding.ActivityGroupInfoBinding;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Shows group name, member list, and provides "Add members" / "Leave group"
 * actions. Reached by tapping the chat header in a group conversation.
 */
public class GroupInfoActivity extends AppCompatActivity {

    public static final String EXTRA_GROUP_ID = "group_id";

    private ActivityGroupInfoBinding b;
    private Store store;
    private SignalingHub hub;
    private String groupId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityGroupInfoBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());

        store = new Store(this);
        hub = SignalingHub.get(this);

        groupId = getIntent().getStringExtra(EXTRA_GROUP_ID);
        if (groupId == null) { finish(); return; }

        b.btnBack.setOnClickListener(v -> finish());
        b.btnLeaveGroup.setOnClickListener(v -> confirmLeave());
        b.btnAddMembers.setOnClickListener(v -> showAddMembers());
        b.btnMuteGroup.setOnClickListener(v -> toggleMute());
        renderGroup();
    }

    private void toggleMute() {
        String peerId = "group:" + groupId;
        boolean muted = store.isMuted(peerId);
        store.setMuted(peerId, !muted);
        android.widget.Toast.makeText(this,
                !muted ? "Muted" : "Unmuted",
                android.widget.Toast.LENGTH_SHORT).show();
        refreshMuteLabel();
    }

    private void refreshMuteLabel() {
        String peerId = "group:" + groupId;
        b.btnMuteGroup.setText(store.isMuted(peerId)
                ? "Unmute notifications" : "Mute notifications");
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Reload in case the roster updated while we were elsewhere.
        renderGroup();
    }

    private void renderGroup() {
        Group g = store.getGroup(groupId);
        if (g == null) { finish(); return; }

        b.tvGroupName.setText(g.name);
        b.tvGroupAvatar.setText(initialOf(g.name));
        b.tvGroupMeta.setText(g.members.size() + " members · encrypted");
        refreshMuteLabel();

        b.membersList.removeAllViews();
        String me = store.getUsername();
        for (String username : g.members) {
            View row = LayoutInflater.from(this).inflate(R.layout.item_member_pick, b.membersList, false);
            // Reuse the member-pick row layout but hide the checkbox.
            row.findViewById(R.id.cbMember).setVisibility(View.GONE);

            String displayName = username;
            if (me != null && username.equalsIgnoreCase(me)) {
                displayName = store.getMyDisplayName() + " (you)";
            } else {
                Store.PeerProfile p = store.getPeerProfile(username);
                if (p != null && p.displayName != null && !p.displayName.isEmpty()) {
                    displayName = p.displayName;
                }
            }
            ((TextView) row.findViewById(R.id.tvName)).setText(displayName);
            ((TextView) row.findViewById(R.id.tvUsername)).setText("@" + username);
            ((TextView) row.findViewById(R.id.tvAvatar)).setText(initialOf(displayName));
            b.membersList.addView(row);
        }
    }

    private static String initialOf(String s) {
        return (s == null || s.isEmpty()) ? "?" : s.substring(0, 1).toUpperCase();
    }

    private void confirmLeave() {
        new AlertDialog.Builder(this)
                .setTitle("Leave group?")
                .setMessage("You'll be removed from the group and won't receive new messages from it.")
                .setPositiveButton("Leave", (d, w) -> {
                    hub.leaveGroup(groupId);
                    Toast.makeText(GroupInfoActivity.this, "Left group", Toast.LENGTH_SHORT).show();
                    // Bounce back to the main contacts screen.
                    Intent main = new Intent(this, MainActivity.class);
                    main.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    startActivity(main);
                    finish();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showAddMembers() {
        Group g = store.getGroup(groupId);
        if (g == null) return;
        // Show only contacts NOT currently in the group.
        Set<String> currentLower = new HashSet<>();
        for (String m : g.members) currentLower.add(m.toLowerCase());

        List<Contact> addable = new ArrayList<>();
        for (Contact c : store.getContacts()) {
            if (!currentLower.contains(c.username.toLowerCase())) addable.add(c);
        }
        if (addable.isEmpty()) {
            Toast.makeText(this, "Everyone you know is already in this group",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        CharSequence[] labels = new CharSequence[addable.size()];
        boolean[] checked = new boolean[addable.size()];
        for (int i = 0; i < addable.size(); i++) {
            Contact c = addable.get(i);
            Store.PeerProfile p = store.getPeerProfile(c.username);
            String name = p != null && p.displayName != null && !p.displayName.isEmpty()
                    ? p.displayName : c.displayName;
            labels[i] = name + "  (@" + c.username + ")";
        }
        new AlertDialog.Builder(this)
                .setTitle("Add members")
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("Add", (d, w) -> {
                    List<String> toAdd = new ArrayList<>();
                    for (int i = 0; i < addable.size(); i++) {
                        if (checked[i]) toAdd.add(addable.get(i).username);
                    }
                    if (toAdd.isEmpty()) return;
                    hub.addGroupMembers(groupId, toAdd);
                    Toast.makeText(this, "Added " + toAdd.size() + " member(s)",
                            Toast.LENGTH_SHORT).show();
                    renderGroup();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
