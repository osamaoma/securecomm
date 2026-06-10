package com.example.p2pvoice;

import java.util.ArrayList;
import java.util.List;

/**
 * A group chat. Persisted by Store under "groups" as a JSON array.
 *
 * The `groupKey` is a 32-byte AES-GCM key shared by every member; it is
 * delivered to each member via a pairwise-encrypted group_event_msg.
 * Anybody with this key can decrypt every group message — so it MUST be
 * protected at rest the same way pairwise keys are (kept inside this app's
 * private storage).
 *
 * `version` increases on every membership/name change so we can ignore
 * stale roster updates if they arrive out of order.
 */
public class Group {

    public String id;                 // UUID — stable identity of the group
    public String name;               // display name
    public List<String> members;      // usernames (lowercase). Includes ourselves.
    public byte[] groupKey;           // 32 bytes, AES-GCM. Null if we don't have it yet
                                      // (e.g. we received an invite but key was missing).
    public long   version;            // bumped by anyone who changes the group

    public Group() {
        members = new ArrayList<>();
    }

    public Group(String id, String name, List<String> members, byte[] groupKey, long version) {
        this.id = id;
        this.name = name;
        this.members = members != null ? members : new ArrayList<>();
        this.groupKey = groupKey;
        this.version = version;
    }

    /** Storage / wire identifier used wherever a username goes for 1-on-1. */
    public String peerId() { return "group:" + id; }

    public static boolean isGroupPeerId(String peer) {
        return peer != null && peer.startsWith("group:");
    }

    public static String groupIdFromPeerId(String peer) {
        if (!isGroupPeerId(peer)) return null;
        return peer.substring("group:".length());
    }
}
