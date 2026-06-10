package com.example.p2pvoice;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.text.style.StyleSpan;
import android.text.style.BackgroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.p2pvoice.databinding.ActivitySearchBinding;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Global search across all 1-on-1 and group conversations. Everything is
 * decrypted on the device, so the search is a simple in-memory substring
 * scan — no server round-trips, no plaintext leaving the device.
 *
 * Matched text is highlighted in the snippet.
 */
public class SearchActivity extends AppCompatActivity {

    private ActivitySearchBinding b;
    private Store store;
    private ResultAdapter adapter;
    private final List<Result> results = new ArrayList<>();

    /** One row in the results list. Three kinds: a matching contact, a
     *  matching group, or a matching message. We render them with the same
     *  layout but populate fields differently. */
    private enum Kind { CONTACT, GROUP, MESSAGE }
    private static class Result {
        Kind   kind;
        String peerId;            // for navigation
        String whereLabel;        // contact/group name (or display name)
        String avatarInitial;
        String snippet;           // possibly truncated plaintext around match
        int    matchStart;        // index of match in snippet, for highlight
        int    matchEnd;
        long   timestamp;         // for ordering MESSAGE results
        String typeLabel;         // "Contact" / "Group" / null
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivitySearchBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());

        store = new Store(this);
        adapter = new ResultAdapter();
        b.rvResults.setLayoutManager(new LinearLayoutManager(this));
        b.rvResults.setAdapter(adapter);

        b.btnBack.setOnClickListener(v -> finish());
        b.etQuery.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b1, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b1, int c) {}
            @Override public void afterTextChanged(Editable s) {
                doSearch(s.toString());
            }
        });
        b.etQuery.requestFocus();
    }

    /** Runs the substring scan and refreshes the list. Cheap enough at our
     *  scale (caps: 500 messages × N conversations) that we just block the
     *  UI thread. If conversations get huge later, push to a worker.
     *
     *  Result ordering: contacts/groups first (matching what people expect
     *  from a global search), then messages newest-first. */
    private void doSearch(String rawQuery) {
        results.clear();
        String query = rawQuery == null ? "" : rawQuery.trim();
        if (query.isEmpty()) {
            adapter.notifyDataSetChanged();
            updateEmptyText("Type to search contacts, groups, and messages.");
            return;
        }
        String needle = query.toLowerCase(Locale.ROOT);

        // --- Contacts: match against display name AND username. ---
        List<Result> contactMatches = new ArrayList<>();
        for (Contact c : store.getContacts()) {
            Store.PeerProfile prof = store.getPeerProfile(c.username);
            String displayName = prof != null && prof.displayName != null
                    && !prof.displayName.isEmpty() ? prof.displayName : c.displayName;
            String displayLow  = displayName.toLowerCase(Locale.ROOT);
            String usernameLow = c.username.toLowerCase(Locale.ROOT);
            // Match either the friendly name or the routing username (handles).
            int hitInName = displayLow.indexOf(needle);
            int hitInUser = usernameLow.indexOf(needle);
            if (hitInName < 0 && hitInUser < 0) continue;
            Result r = new Result();
            r.kind = Kind.CONTACT;
            r.peerId = c.username;
            r.whereLabel = displayName;
            r.avatarInitial = displayName.isEmpty() ? "?"
                    : displayName.substring(0, 1).toUpperCase();
            r.snippet = "@" + c.username;
            // Highlight whichever field matched. For names we highlight the
            // name itself (whereLabel is shown separately though, so we just
            // highlight the snippet for visual consistency).
            if (hitInUser >= 0) {
                // +1 because snippet starts with "@".
                r.matchStart = hitInUser + 1;
                r.matchEnd = r.matchStart + needle.length();
            }
            r.typeLabel = "Contact";
            contactMatches.add(r);
        }

        // --- Groups: match the group name. ---
        List<Result> groupMatches = new ArrayList<>();
        for (Group g : store.getGroups()) {
            String nameLow = g.name == null ? "" : g.name.toLowerCase(Locale.ROOT);
            if (nameLow.indexOf(needle) < 0) continue;
            Result r = new Result();
            r.kind = Kind.GROUP;
            r.peerId = g.peerId();
            r.whereLabel = g.name;
            r.avatarInitial = g.name == null || g.name.isEmpty() ? "G"
                    : g.name.substring(0, 1).toUpperCase();
            r.snippet = g.members.size() + (g.members.size() == 1 ? " member" : " members");
            r.matchStart = -1; r.matchEnd = -1;
            r.typeLabel = "Group";
            groupMatches.add(r);
        }

        // --- Messages: existing flow. ---
        List<Result> messageMatches = new ArrayList<>();
        for (Contact c : store.getContacts()) {
            Store.PeerProfile prof = store.getPeerProfile(c.username);
            String whereLabel = prof != null && prof.displayName != null
                    && !prof.displayName.isEmpty() ? prof.displayName : c.displayName;
            scanConversation(messageMatches, c.username, whereLabel,
                    whereLabel.isEmpty() ? "?" : whereLabel.substring(0, 1).toUpperCase(),
                    needle);
        }
        for (Group g : store.getGroups()) {
            scanConversation(messageMatches, g.peerId(), g.name,
                    g.name.isEmpty() ? "G" : g.name.substring(0, 1).toUpperCase(),
                    needle);
        }
        Collections.sort(messageMatches, (a, b) -> Long.compare(b.timestamp, a.timestamp));

        // Final order: contacts, groups, messages.
        results.addAll(contactMatches);
        results.addAll(groupMatches);
        results.addAll(messageMatches);
        adapter.notifyDataSetChanged();
        updateEmptyText(results.isEmpty() ? "No matches." : null);
    }

    private void scanConversation(List<Result> out, String peerId, String whereLabel,
                                  String avatar, String needle) {
        for (ChatMessage m : store.getMessages(peerId)) {
            if ("system".equals(m.senderUsername)) continue;
            String text = m.text;
            if (text == null || text.isEmpty()) continue;
            int idx = text.toLowerCase(Locale.ROOT).indexOf(needle);
            if (idx < 0) continue;
            Result r = new Result();
            r.kind = Kind.MESSAGE;
            r.peerId = peerId;
            r.whereLabel = whereLabel;
            r.avatarInitial = avatar;
            int radius = 40;
            int start = Math.max(0, idx - radius);
            int end   = Math.min(text.length(), idx + needle.length() + radius);
            String snippet = text.substring(start, end);
            if (start > 0) snippet = "…" + snippet;
            int prefixGrow = (start > 0 ? 1 : 0);
            r.snippet    = snippet;
            r.matchStart = idx - start + prefixGrow;
            r.matchEnd   = r.matchStart + needle.length();
            r.timestamp  = m.timestamp;
            out.add(r);
        }
    }

    private void updateEmptyText(String text) {
        if (text == null) {
            b.tvEmpty.setVisibility(View.GONE);
            b.rvResults.setVisibility(View.VISIBLE);
        } else {
            b.tvEmpty.setVisibility(View.VISIBLE);
            b.tvEmpty.setText(text);
            b.rvResults.setVisibility(View.GONE);
        }
    }

    // -------------------------------------------------------------------
    // Adapter
    // -------------------------------------------------------------------

    private class ResultAdapter extends RecyclerView.Adapter<RVH> {
        @NonNull
        @Override
        public RVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_search_result, parent, false);
            return new RVH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull RVH h, int position) {
            Result r = results.get(position);
            h.avatar.setText(r.avatarInitial);
            h.where.setText(r.whereLabel);
            // For message rows we show a relative timestamp; for contact /
            // group rows we show their type as a small label instead.
            if (r.kind == Kind.MESSAGE) {
                h.when.setText(DateUtils.getRelativeTimeSpanString(r.timestamp,
                        System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS));
            } else {
                h.when.setText(r.typeLabel);
            }
            // Highlight the matched substring inside the snippet, when present.
            SpannableString span = new SpannableString(r.snippet);
            if (r.matchStart >= 0 && r.matchEnd <= r.snippet.length()
                    && r.matchEnd > r.matchStart) {
                span.setSpan(new BackgroundColorSpan(0x664F8DFF),
                        r.matchStart, r.matchEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                span.setSpan(new StyleSpan(android.graphics.Typeface.BOLD),
                        r.matchStart, r.matchEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            h.snippet.setText(span);
            h.itemView.setOnClickListener(v -> {
                Intent i = new Intent(SearchActivity.this, ChatActivity.class);
                i.putExtra(ChatActivity.EXTRA_PEER, r.peerId);
                startActivity(i);
            });
        }

        @Override public int getItemCount() { return results.size(); }
    }

    static class RVH extends RecyclerView.ViewHolder {
        TextView avatar, where, when, snippet;
        RVH(@NonNull View v) {
            super(v);
            avatar  = v.findViewById(R.id.tvAvatar);
            where   = v.findViewById(R.id.tvWhere);
            when    = v.findViewById(R.id.tvWhen);
            snippet = v.findViewById(R.id.tvSnippet);
        }
    }
}
