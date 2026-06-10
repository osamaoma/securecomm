package com.example.p2pvoice;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.p2pvoice.databinding.ActivityGroupCreateBinding;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Multi-select contact picker plus a group name field. Tapping "Create"
 * generates a fresh group key, persists the group locally, and asks the
 * hub to send invite events to every selected member.
 *
 * Once the group is created, the activity navigates straight into the
 * group's ChatActivity so the user can start messaging.
 */
public class GroupCreateActivity extends AppCompatActivity {

    private ActivityGroupCreateBinding b;
    private Store store;
    private SignalingHub hub;
    private MemberAdapter adapter;
    private final List<Contact> available = new ArrayList<>();
    private final Set<String> selected = new HashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityGroupCreateBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());

        store = new Store(this);
        hub = SignalingHub.get(this);

        b.btnBack.setOnClickListener(v -> finish());
        b.btnCreate.setOnClickListener(v -> create());

        // Load contacts and present them as a checklist.
        available.addAll(store.getContacts());
        if (available.isEmpty()) {
            b.tvNoContacts.setVisibility(View.VISIBLE);
            b.rvMembers.setVisibility(View.GONE);
            b.btnCreate.setVisibility(View.GONE);
        } else {
            adapter = new MemberAdapter();
            b.rvMembers.setLayoutManager(new LinearLayoutManager(this));
            b.rvMembers.setAdapter(adapter);
        }
    }

    private void create() {
        String name = b.etGroupName.getText().toString().trim();
        if (name.isEmpty()) {
            Toast.makeText(this, "Pick a group name", Toast.LENGTH_SHORT).show();
            return;
        }
        if (selected.size() < 1) {
            Toast.makeText(this, "Pick at least one member", Toast.LENGTH_SHORT).show();
            return;
        }
        b.btnCreate.setEnabled(false);
        Group g = hub.createGroup(name, new ArrayList<>(selected));
        if (g == null) {
            Toast.makeText(this, "Failed to create group", Toast.LENGTH_LONG).show();
            b.btnCreate.setEnabled(true);
            return;
        }
        // Go straight into the new group's chat.
        Intent i = new Intent(this, ChatActivity.class);
        i.putExtra(ChatActivity.EXTRA_PEER, g.peerId());
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(i);
        finish();
    }

    // -------------------------------------------------------------------
    // Inline adapter
    // -------------------------------------------------------------------

    private class MemberAdapter extends RecyclerView.Adapter<MemberAdapter.MVH> {
        @NonNull
        @Override
        public MVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_member_pick, parent, false);
            return new MVH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull MVH h, int position) {
            Contact c = available.get(position);
            // Use synced display name if we have one, fall back to local label.
            Store.PeerProfile prof = store.getPeerProfile(c.username);
            String name = prof != null && prof.displayName != null && !prof.displayName.isEmpty()
                    ? prof.displayName : c.displayName;
            h.name.setText(name);
            h.username.setText("@" + c.username);
            String initial = name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase();
            h.avatar.setText(initial);

            h.check.setChecked(selected.contains(c.username));
            h.itemView.setOnClickListener(v -> {
                if (selected.contains(c.username)) {
                    selected.remove(c.username);
                } else {
                    selected.add(c.username);
                }
                notifyItemChanged(position);
            });
        }

        @Override
        public int getItemCount() { return available.size(); }

        class MVH extends RecyclerView.ViewHolder {
            TextView avatar, name, username;
            CheckBox check;
            MVH(@NonNull View v) {
                super(v);
                avatar = v.findViewById(R.id.tvAvatar);
                name = v.findViewById(R.id.tvName);
                username = v.findViewById(R.id.tvUsername);
                check = v.findViewById(R.id.cbMember);
            }
        }
    }
}
