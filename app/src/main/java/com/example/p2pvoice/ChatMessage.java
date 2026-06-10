package com.example.p2pvoice;

/** A single chat message persisted in local history. */
public class ChatMessage {

    public enum Direction { OUTGOING, INCOMING }

    /** Lifecycle states for outgoing messages; incoming are always DELIVERED. */
    public enum Status { PENDING, SENT, DELIVERED, READ, FAILED }

    /** Distinguishes text from media so the UI can render each appropriately.
     *
     *  CONTACT and LOCATION are sent as regular encrypted text messages with
     *  a sentinel + JSON payload; we mark the kind after decrypt so the UI
     *  can render the right bubble template. DOCUMENT travels through the
     *  same upload/download pipeline as images but without the image-specific
     *  resizing or thumbnail. */
    public enum Kind { TEXT, IMAGE, VOICE, DOCUMENT, CONTACT, LOCATION }

    public String id;            // server-assigned for received, locally-generated for sent
    public String peer;          // For 1-on-1: the OTHER user's username.
                                 // For group chats: "group:<groupUuid>" — the conversation key
                                 // we store/route under.
    /** For group chats: who actually sent this message (always set).
     *  For 1-on-1 OUTGOING: the local user; for 1-on-1 INCOMING: same as peer. */
    public String senderUsername;
    /** Set on every message that belongs to a group conversation (= the group's UUID).
     *  Null for 1-on-1 messages. */
    public String groupId;
    public Direction direction;
    public String text;          // plaintext for display; ciphertext only exists on the wire.
                                 // For IMAGE messages this holds the optional caption (may be empty).
                                 // For VOICE messages this is unused (empty string).
    public long timestamp;       // ms since epoch
    public Status status;

    public Kind kind = Kind.TEXT;

    // --- Image fields (Kind.IMAGE) ---
    public String mediaUrl;         // URL of the encrypted blob on the signaling server
    public String mediaType;        // MIME (image/jpeg, audio/mp4 ...)
    public int    mediaWidth;       // pixels, for layout before download finishes
    public int    mediaHeight;
    public String thumbnailB64;     // small encrypted preview, decryptable with the conversation key
                                    // (Base64 of [iv||ct+tag], same wire format as text)
    public String localPath;        // populated after decrypt+cache so we can render instantly next time

    // --- Voice fields (Kind.VOICE) ---
    public int durationMs;          // length of the recording, so the bubble can show
                                    // "0:12" without downloading first

    // --- Document fields (Kind.DOCUMENT) ---
    // We reuse mediaUrl/mediaType from above for the server URL and MIME.
    public String fileName;         // original filename (e.g. "report.pdf")
    public long   fileSize;         // bytes — shown as KB/MB in the bubble

    // --- Edit / unsend tracking ---
    // editedAt is the wall-clock ms of the most recent edit, or 0 if the
    // message has never been edited. The bubble shows "· edited" next to
    // the timestamp when non-zero. Only TEXT messages can be edited.
    public long editedAt;
    // deleted = true means the sender unsent this message ("delete for
    // everyone"). The original content fields stay populated locally so we
    // could revert if we ever needed to, but the bubble renders a
    // placeholder ("[message deleted]") instead.
    public boolean deleted;

    // --- Reply-to (quoting another message) ---
    // When non-null, the bubble shows a quote box above the main content.
    // replyToPreview is the plaintext preview of the quoted message; it's
    // encrypted on the wire as replyToPreviewCt and decrypted on receipt.
    public String replyToId;
    public String replyToPreview;

    // --- Forwarded flag ---
    // True if this message was forwarded from another conversation. The bubble
    // shows a small "↪ Forwarded" label above the content.
    public boolean forwarded;

    // --- Reactions ---
    // Map of username -> emoji. Latest reaction from each user replaces the
    // previous one; sending an empty emoji removes that user's reaction.
    // Persisted as a JSON object alongside the message.
    public java.util.Map<String, String> reactions = new java.util.LinkedHashMap<>();

    public ChatMessage(String id, String peer, Direction direction, String text,
                       long timestamp, Status status) {
        this.id = id;
        this.peer = peer;
        this.direction = direction;
        this.text = text;
        this.timestamp = timestamp;
        this.status = status;
    }
}
