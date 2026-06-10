package com.example.p2pvoice;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.Date;
import java.util.List;

public class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.VH> {

    public interface OnImageTap {
        void onTapImage(ChatMessage m);
    }
    public interface OnVoiceTap {
        void onTapVoice(ChatMessage m);
    }
    public interface OnLongPress {
        void onLongPressMessage(ChatMessage m, View anchor);
    }
    /** Bubble taps for non-image, non-voice rich kinds (document, contact card,
     *  location share). Implementations route based on m.kind. */
    public interface OnRichTap {
        void onTapRich(ChatMessage m);
    }

    private final List<ChatMessage> messages;
    private final OnImageTap imageTap;
    private final OnVoiceTap voiceTap;
    private final OnLongPress longPress;
    private final OnRichTap richTap;
    private final SignalingHub hub;
    /** Local user's username, so reactions chip can show "you" vs other people. */
    private final String myUsername;
    /** True when this adapter is showing a group conversation — incoming bubbles
     *  then carry a small sender label so you can tell who said what. */
    private final boolean isGroup;

    public ChatAdapter(List<ChatMessage> messages, OnImageTap imageTap,
                       OnVoiceTap voiceTap, OnLongPress longPress, OnRichTap richTap,
                       SignalingHub hub, String myUsername, boolean isGroup) {
        this.messages = messages;
        this.imageTap = imageTap;
        this.voiceTap = voiceTap;
        this.longPress = longPress;
        this.richTap = richTap;
        this.hub = hub;
        this.myUsername = myUsername;
        this.isGroup = isGroup;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_chat_message, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        ChatMessage m = messages.get(position);
        Context ctx = h.itemView.getContext();

        // Bubble alignment + color depending on direction.
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) h.bubble.getLayoutParams();
        if (m.direction == ChatMessage.Direction.OUTGOING) {
            lp.gravity = Gravity.END;
            h.bubble.setBackgroundResource(R.drawable.bg_bubble_out);
            h.status.setText(statusGlyph(m.status));
            // READ ticks render in the success colour to distinguish from
            // DELIVERED ticks (which use the default muted colour). All other
            // statuses keep the default colour assigned via XML.
            int statusColor = m.status == ChatMessage.Status.READ
                    ? ContextCompat.getColor(ctx, R.color.accent_success)
                    : ContextCompat.getColor(ctx, R.color.text_secondary);
            h.status.setTextColor(statusColor);
            h.status.setVisibility(View.VISIBLE);
        } else {
            lp.gravity = Gravity.START;
            h.bubble.setBackgroundResource(R.drawable.bg_bubble_in);
            h.status.setVisibility(View.GONE);
        }
        h.bubble.setLayoutParams(lp);

        // Time label, with a small "edited" suffix when the message has been
        // edited after sending. Deleted messages drop the edited tag since
        // the visible bubble content is now just the placeholder.
        String timeLabel = DateFormat.format("HH:mm", new Date(m.timestamp)).toString();
        if (m.editedAt > 0 && !m.deleted) timeLabel += " · edited";
        h.time.setText(timeLabel);

        // Sender label: only meaningful for incoming bubbles in a group
        // conversation. Hidden everywhere else.
        if (isGroup && m.direction == ChatMessage.Direction.INCOMING
                && m.senderUsername != null
                && !"system".equals(m.senderUsername)) {
            String display = senderDisplayName(ctx, m.senderUsername);
            h.senderLabel.setText(display);
            h.senderLabel.setTextColor(senderColor(m.senderUsername));
            h.senderLabel.setVisibility(View.VISIBLE);
        } else {
            h.senderLabel.setVisibility(View.GONE);
        }

        // System messages (group join/leave notices) get a special neutral
        // look — centered, faded, no sender, no time.
        boolean isSystem = isGroup && "system".equals(m.senderUsername);
        if (isSystem) {
            h.bubble.setBackgroundResource(R.drawable.bg_bubble_in);
            h.bubble.setAlpha(0.6f);
            h.time.setVisibility(View.GONE);
            lp.gravity = Gravity.CENTER;
            h.bubble.setLayoutParams(lp);
        } else {
            h.bubble.setAlpha(1.0f);
            h.time.setVisibility(View.VISIBLE);
        }

        // Always hide non-relevant containers up-front so a recycled view
        // doesn't show stale content for a different kind.
        h.mediaContainer.setVisibility(View.GONE);
        h.voiceContainer.setVisibility(View.GONE);
        h.docContainer.setVisibility(View.GONE);
        h.contactContainer.setVisibility(View.GONE);
        h.locationContainer.setVisibility(View.GONE);

        // Forwarded label
        h.forwardedLabel.setVisibility(m.forwarded ? View.VISIBLE : View.GONE);

        // Reply quote
        if (m.replyToId != null) {
            h.replyQuote.setVisibility(View.VISIBLE);
            // Author of the quoted message: if THIS message is outgoing, then
            // we (the local user) are replying to either ourselves or the peer,
            // so we have to look up the original to know. Easier: look it up.
            String quoteAuthor = m.peer;
            ChatMessage orig = findInList(m.replyToId);
            if (orig != null) {
                quoteAuthor = orig.direction == ChatMessage.Direction.OUTGOING
                        ? "You" : orig.peer;
            }
            h.quoteAuthor.setText(quoteAuthor);
            String preview = m.replyToPreview;
            if (preview == null || preview.isEmpty()) {
                preview = "[message]";
            }
            h.quoteText.setText(preview);
            // Tap a quote to scroll to the original.
            h.replyQuote.setOnClickListener(v -> {
                int idx = indexOfId(m.replyToId);
                if (idx < 0) return;
                // Find the enclosing RecyclerView (depth not fixed — walk up).
                ViewGroup p = (ViewGroup) v.getParent();
                while (p != null
                       && !(p instanceof androidx.recyclerview.widget.RecyclerView)) {
                    if (p.getParent() instanceof ViewGroup) p = (ViewGroup) p.getParent();
                    else { p = null; break; }
                }
                if (p != null) {
                    ((androidx.recyclerview.widget.RecyclerView) p).smoothScrollToPosition(idx);
                }
            });
        } else {
            h.replyQuote.setVisibility(View.GONE);
            h.replyQuote.setOnClickListener(null);
        }

        if (m.deleted) {
            // Replace whatever the bubble would normally show with a small
            // italic placeholder. Reply quote + forwarded-label above remain
            // intact so the conversation flow is preserved.
            h.text.setVisibility(View.VISIBLE);
            h.text.setText("\uD83D\uDEAB this message was deleted");
            h.text.setTypeface(h.text.getTypeface(), android.graphics.Typeface.ITALIC);
            h.text.setAlpha(0.6f);
            // Hide reactions on a deleted message — no point reacting to nothing.
            if (m.reactions != null) m.reactions.clear();
        } else if (m.kind == ChatMessage.Kind.IMAGE) {
            // Reset any italic/alpha state left over from a recycled deleted
            // view, then bind normally.
            h.text.setTypeface(h.text.getTypeface(), android.graphics.Typeface.NORMAL);
            h.text.setAlpha(1.0f);
            bindImage(h, m, ctx);
        } else if (m.kind == ChatMessage.Kind.VOICE) {
            h.text.setTypeface(h.text.getTypeface(), android.graphics.Typeface.NORMAL);
            h.text.setAlpha(1.0f);
            bindVoice(h, m, ctx);
        } else if (m.kind == ChatMessage.Kind.DOCUMENT) {
            h.text.setTypeface(h.text.getTypeface(), android.graphics.Typeface.NORMAL);
            h.text.setAlpha(1.0f);
            bindDocument(h, m);
        } else if (m.kind == ChatMessage.Kind.CONTACT) {
            h.text.setTypeface(h.text.getTypeface(), android.graphics.Typeface.NORMAL);
            h.text.setAlpha(1.0f);
            bindContact(h, m);
        } else if (m.kind == ChatMessage.Kind.LOCATION) {
            h.text.setTypeface(h.text.getTypeface(), android.graphics.Typeface.NORMAL);
            h.text.setAlpha(1.0f);
            bindLocation(h, m);
        } else {
            h.text.setTypeface(h.text.getTypeface(), android.graphics.Typeface.NORMAL);
            h.text.setAlpha(1.0f);
            bindText(h, m);
        }

        // Reactions chip
        if (m.reactions != null && !m.reactions.isEmpty()) {
            h.reactions.setVisibility(View.VISIBLE);
            h.reactions.setText(formatReactions(m.reactions));
            // Tap the chip to add/change/remove your own reaction quickly,
            // without having to long-press the bubble first.
            h.reactions.setOnClickListener(v -> longPress.onLongPressMessage(m, h.itemView));
        } else {
            h.reactions.setVisibility(View.GONE);
            h.reactions.setOnClickListener(null);
        }

        // Long-press the bubble (NOT the play button or image tap) to open
        // the action menu. We attach to the bubble container so the gesture
        // works regardless of message kind.
        h.bubble.setOnLongClickListener(v -> {
            longPress.onLongPressMessage(m, v);
            return true;
        });
        // Long-press also works on the row root, so empty space around the
        // bubble (e.g. on a one-line text message) still triggers it.
        h.itemView.setOnLongClickListener(v -> {
            longPress.onLongPressMessage(m, h.bubble);
            return true;
        });
    }

    private ChatMessage findInList(String id) {
        for (ChatMessage m : messages) {
            if (m.id.equals(id)) return m;
        }
        return null;
    }

    private int indexOfId(String id) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).id.equals(id)) return i;
        }
        return -1;
    }

    /** Render a reactions map as a compact chip string. "❤️👍😂" by default;
     *  if more than one user used the same emoji, append the count. */
    private static String formatReactions(java.util.Map<String, String> reactions) {
        // Count by emoji.
        java.util.LinkedHashMap<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (String e : reactions.values()) {
            if (e == null || e.isEmpty()) continue;
            counts.merge(e, 1, Integer::sum);
        }
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, Integer> e : counts.entrySet()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(e.getKey());
            if (e.getValue() > 1) sb.append(e.getValue());
        }
        return sb.toString();
    }

    private void bindText(VH h, ChatMessage m) {
        h.text.setVisibility(View.VISIBLE);
        h.text.setText(m.text);
    }

    /** Render a document attachment: file icon + name + human-readable size.
     *  Tap → ChatActivity routes through the rich-tap callback to download. */
    private void bindDocument(VH h, ChatMessage m) {
        h.docContainer.setVisibility(View.VISIBLE);
        h.text.setVisibility(View.GONE);
        String name = m.fileName != null && !m.fileName.isEmpty() ? m.fileName : "Document";
        h.docName.setText(name);
        h.docSize.setText(formatSize(m.fileSize));
        h.docContainer.setOnClickListener(v -> {
            if (richTap != null) richTap.onTapRich(m);
        });
    }

    /** Render a shared contact card: name, @username, and a row tap that
     *  offers to save them as a contact (handled by the activity). */
    private void bindContact(VH h, ChatMessage m) {
        h.contactContainer.setVisibility(View.VISIBLE);
        h.text.setVisibility(View.GONE);
        Contact c = RichContent.parseContact(m.text);
        String name = c != null && c.displayName != null ? c.displayName : "(contact)";
        String username = c != null ? c.username : "";
        h.contactName.setText(name);
        h.contactUsername.setText("@" + username);
        h.contactAvatar.setText(name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase());
        h.contactContainer.setOnClickListener(v -> {
            if (richTap != null) richTap.onTapRich(m);
        });
    }

    /** Render a location share: coordinate text + cue to open in Maps. */
    private void bindLocation(VH h, ChatMessage m) {
        h.locationContainer.setVisibility(View.VISIBLE);
        h.text.setVisibility(View.GONE);
        double[] coords = RichContent.parseLocation(m.text);
        if (coords != null) {
            h.locationCoords.setText(String.format(java.util.Locale.US,
                    "%.5f, %.5f", coords[0], coords[1]));
        } else {
            h.locationCoords.setText("Invalid location");
        }
        h.locationContainer.setOnClickListener(v -> {
            if (richTap != null) richTap.onTapRich(m);
        });
    }

    private static String formatSize(long bytes) {
        if (bytes <= 0) return "";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.US,
                "%.1f KB", bytes / 1024.0);
        return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /** Resolve a username to the best name we can show: synced display name
     *  if we have one, otherwise the raw username. */
    private String senderDisplayName(Context ctx, String username) {
        // Avoid bouncing through a fresh Store on every bind — but since the
        // adapter doesn't hold one, do a single lookup per call.
        Store s = new Store(ctx);
        Store.PeerProfile p = s.getPeerProfile(username);
        if (p != null && p.displayName != null && !p.displayName.isEmpty()) {
            return p.displayName;
        }
        return username;
    }

    /** Pick a stable color for a username so each speaker reads at a glance
     *  in the group bubble stream. */
    private static int senderColor(String username) {
        // Six muted, readable colors against the dark bubble palette.
        int[] palette = { 0xFF5DA3FF, 0xFFFF8A65, 0xFF66BB6A, 0xFFBA68C8,
                           0xFFFFC857, 0xFF26C6DA };
        int h = username == null ? 0 : Math.abs(username.toLowerCase().hashCode());
        return palette[h % palette.length];
    }

    private void bindVoice(VH h, ChatMessage m, Context ctx) {
        h.voiceContainer.setVisibility(View.VISIBLE);
        // Voice messages don't have captions today, so the text row is hidden.
        h.text.setVisibility(View.GONE);

        // Duration label: shows full length until playback starts, then
        // counts down the remaining time so the user knows how long is left.
        h.voiceDuration.setText(formatDuration(m.durationMs));
        h.voiceProgress.setProgress(0);

        boolean uploading = m.direction == ChatMessage.Direction.OUTGOING
                            && m.status == ChatMessage.Status.PENDING;
        if (uploading) {
            h.playButton.setText("…");
            h.playButton.setClickable(false);
        } else {
            // Reflect current playback state. The player is a singleton, so
            // recycling a row can correctly show ▶ vs ⏸ based on whether THIS
            // file is currently playing.
            boolean isThisPlaying = m.localPath != null
                                    && VoicePlayer.get().isPlaying(m.localPath);
            h.playButton.setText(isThisPlaying ? "⏸" : "▶");
            h.playButton.setClickable(true);
            h.playButton.setOnClickListener(v -> voiceTap.onTapVoice(m));
        }
    }

    private static String formatDuration(int durationMs) {
        if (durationMs <= 0) return "0:00";
        int totalSec = durationMs / 1000;
        int min = totalSec / 60;
        int sec = totalSec % 60;
        return String.format(java.util.Locale.US, "%d:%02d", min, sec);
    }

    private void bindImage(VH h, ChatMessage m, Context ctx) {
        h.mediaContainer.setVisibility(View.VISIBLE);
        // Caption: only show text view if there's a caption.
        if (m.text != null && !m.text.isEmpty()) {
            h.text.setVisibility(View.VISIBLE);
            h.text.setText(m.text);
        } else {
            h.text.setVisibility(View.GONE);
        }

        h.image.setOnClickListener(v -> imageTap.onTapImage(m));

        // Display strategy, in priority order:
        //   1. If we have a cached decrypted file on disk, use it directly.
        //   2. Else if we have a thumbnail in the message, decrypt+show it.
        //   3. Else show empty placeholder.
        if (m.localPath != null && new File(m.localPath).exists()) {
            h.progress.setVisibility(View.GONE);
            Bitmap bmp = BitmapFactory.decodeFile(m.localPath);
            if (bmp != null) {
                h.image.setImageBitmap(bmp);
                return;
            }
        }

        // Try thumbnail decryption.
        Bitmap thumb = null;
        if (m.thumbnailB64 != null) {
            byte[] key = hub != null ? hub.keyForPeer(m.peer) : null;
            thumb = MediaTransfer.decryptThumbnail(key, m.thumbnailB64);
        }
        if (thumb != null) {
            h.image.setImageBitmap(thumb);
        } else {
            // Could not decrypt the inline thumbnail with any local key.
            // Show a placeholder so the user can see something happened (and
            // tapping will trigger the clearer "ask sender to resend" toast).
            h.image.setImageDrawable(null);
            h.image.setBackgroundColor(0xFF14171F);
        }

        // For outgoing PENDING (still uploading) or incoming (not yet downloaded
        // to full quality) — show progress spinner overlay.
        boolean uploading = m.direction == ChatMessage.Direction.OUTGOING
                            && m.status == ChatMessage.Status.PENDING;
        boolean awaitingDownload = m.direction == ChatMessage.Direction.INCOMING
                                   && (m.localPath == null || !new File(m.localPath).exists());
        h.progress.setVisibility((uploading || awaitingDownload) ? View.VISIBLE : View.GONE);
    }

    private String statusGlyph(ChatMessage.Status s) {
        switch (s) {
            case PENDING:   return "\u29D6";       // hourglass-ish
            case SENT:      return "\u2713";        // single check
            case DELIVERED: return "\u2713\u2713";  // double check (muted)
            case READ:      return "\u2713\u2713";  // double check (success-coloured at bind time)
            case FAILED:    return "\u26A0";        // warning
            default:        return "";
        }
    }

    @Override public int getItemCount() { return messages.size(); }

    static class VH extends RecyclerView.ViewHolder {
        LinearLayout bubble;
        TextView text, time, status;
        FrameLayout mediaContainer;
        ImageView image;
        ProgressBar progress;
        LinearLayout voiceContainer;
        TextView playButton;
        ProgressBar voiceProgress;
        TextView voiceDuration;
        LinearLayout docContainer;
        TextView docName, docSize;
        LinearLayout contactContainer;
        TextView contactAvatar, contactName, contactUsername;
        LinearLayout locationContainer;
        TextView locationCoords;
        TextView forwardedLabel;
        TextView senderLabel;
        LinearLayout replyQuote;
        TextView quoteAuthor, quoteText;
        TextView reactions;
        VH(@NonNull View v) {
            super(v);
            bubble  = v.findViewById(R.id.bubble);
            text    = v.findViewById(R.id.tvText);
            time    = v.findViewById(R.id.tvTime);
            status  = v.findViewById(R.id.tvStatus);
            mediaContainer = v.findViewById(R.id.mediaContainer);
            image   = v.findViewById(R.id.imgMedia);
            progress = v.findViewById(R.id.mediaProgress);
            voiceContainer = v.findViewById(R.id.voiceContainer);
            playButton     = v.findViewById(R.id.btnPlay);
            voiceProgress  = v.findViewById(R.id.voiceProgress);
            voiceDuration  = v.findViewById(R.id.voiceDuration);
            docContainer       = v.findViewById(R.id.docContainer);
            docName            = v.findViewById(R.id.tvDocName);
            docSize            = v.findViewById(R.id.tvDocSize);
            contactContainer   = v.findViewById(R.id.contactContainer);
            contactAvatar      = v.findViewById(R.id.tvContactAvatar);
            contactName        = v.findViewById(R.id.tvContactName);
            contactUsername    = v.findViewById(R.id.tvContactUsername);
            locationContainer  = v.findViewById(R.id.locationContainer);
            locationCoords     = v.findViewById(R.id.tvLocationCoords);
            forwardedLabel = v.findViewById(R.id.tvForwardedLabel);
            senderLabel    = v.findViewById(R.id.tvSenderLabel);
            replyQuote     = v.findViewById(R.id.replyQuote);
            quoteAuthor    = v.findViewById(R.id.tvQuoteAuthor);
            quoteText      = v.findViewById(R.id.tvQuoteText);
            reactions      = v.findViewById(R.id.tvReactions);
        }
    }
}
