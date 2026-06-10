package com.example.p2pvoice;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.List;

public class ContactsAdapter extends RecyclerView.Adapter<ContactsAdapter.VH> {

    public interface OnContactAction {
        void onCall(Contact c);
        void onChat(Contact c);
        void onLongPress(Contact c);
    }

    private final List<Contact> contacts;
    private final OnContactAction actions;
    private final Store store;

    public ContactsAdapter(List<Contact> contacts, OnContactAction actions, Store store) {
        this.contacts = contacts;
        this.actions = actions;
        this.store = store;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_contact, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Contact c = contacts.get(position);
        boolean isGroup = Group.isGroupPeerId(c.username);

        if (isGroup) {
            // Group row: show name + "N members"; no @handle line; no Call button.
            Group g = store != null
                    ? store.getGroup(Group.groupIdFromPeerId(c.username))
                    : null;
            String name = g != null ? g.name : c.displayName;
            h.name.setText(name);
            h.username.setText(g != null
                    ? g.members.size() + (g.members.size() == 1 ? " member" : " members")
                    : "");
            h.avatar.setText(name.isEmpty() ? "G" : name.substring(0, 1).toUpperCase());
            h.avatar.setVisibility(View.VISIBLE);
            h.avatarImg.setVisibility(View.GONE);
            h.call.setVisibility(View.GONE);
        } else {
            // 1-on-1 contact row.
            // Prefer the synced display name from the peer's own profile push,
            // but fall back to the locally-set Contact.displayName, then username.
            Store.PeerProfile prof = store == null ? null : store.getPeerProfile(c.username);
            String name = prof != null && prof.displayName != null && !prof.displayName.isEmpty()
                    ? prof.displayName
                    : c.displayName;
            h.name.setText(name);
            h.username.setText("@" + c.username);

            // Avatar: image if cached, else letter tile.
            String avatarPath = prof != null ? prof.avatarPath : null;
            Bitmap avatarBmp = null;
            if (avatarPath != null && new File(avatarPath).exists()) {
                avatarBmp = BitmapFactory.decodeFile(avatarPath);
            }
            if (avatarBmp != null) {
                h.avatarImg.setImageBitmap(ProfileManager.toCircle(avatarBmp));
                h.avatarImg.setVisibility(View.VISIBLE);
                h.avatar.setVisibility(View.GONE);
            } else {
                String initial = name.isEmpty() ? "?"
                        : name.substring(0, 1).toUpperCase();
                h.avatar.setText(initial);
                h.avatar.setVisibility(View.VISIBLE);
                h.avatarImg.setVisibility(View.GONE);
            }
            h.call.setVisibility(View.VISIBLE);
        }

        // Unread badge — works the same for groups and contacts because we
        // store unread under the same key as the chat conversation.
        int unread = store == null ? 0 : store.getUnread(c.username);
        if (unread > 0) {
            h.unread.setVisibility(View.VISIBLE);
            h.unread.setText(unread > 99 ? "99+" : String.valueOf(unread));
        } else {
            h.unread.setVisibility(View.GONE);
        }

        // Mute / block indicators. We keep these subtle: prepend a 🔕 to the
        // username line for mute (works for both 1-on-1 and groups), and
        // fade the whole row + append " · Blocked" to the username line for
        // blocked 1-on-1 contacts. Blocked rows still respond to tap so the
        // user can long-press and unblock easily.
        if (store != null) {
            boolean isMuted = store.isMuted(c.username);
            boolean isBlocked = !isGroup && store.isBlocked(c.username);
            CharSequence baseUser = h.username.getText();
            StringBuilder sb = new StringBuilder();
            if (isMuted) sb.append("\uD83D\uDD15  ");
            sb.append(baseUser);
            if (isBlocked) sb.append("  ·  Blocked");
            h.username.setText(sb.toString());
            h.itemView.setAlpha(isBlocked ? 0.5f : 1.0f);
        } else {
            h.itemView.setAlpha(1.0f);
        }

        h.call.setOnClickListener(v -> actions.onCall(c));
        h.chat.setOnClickListener(v -> actions.onChat(c));
        h.itemView.setOnLongClickListener(v -> { actions.onLongPress(c); return true; });
    }

    @Override
    public int getItemCount() { return contacts.size(); }

    static class VH extends RecyclerView.ViewHolder {
        TextView avatar, name, username, call, chat, unread;
        ImageView avatarImg;
        VH(@NonNull View v) {
            super(v);
            avatar = v.findViewById(R.id.tvAvatar);
            avatarImg = v.findViewById(R.id.imgAvatar);
            name = v.findViewById(R.id.tvName);
            username = v.findViewById(R.id.tvUsername);
            call = v.findViewById(R.id.btnCall);
            chat = v.findViewById(R.id.btnChat);
            unread = v.findViewById(R.id.tvUnread);
        }
    }
}
