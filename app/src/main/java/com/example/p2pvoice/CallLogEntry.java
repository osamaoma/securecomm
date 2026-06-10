package com.example.p2pvoice;

/** One row in the call history. Stored locally only. */
public class CallLogEntry {
    public enum Direction { OUTGOING, INCOMING, MISSED }

    public String peer;          // the other person's username
    public Direction direction;
    public long timestamp;       // ms since epoch, when the call started
    public long durationSec;     // 0 if missed/declined
    public boolean isVideo;      // true if this was a video call

    public CallLogEntry(String peer, Direction direction, long timestamp,
                        long durationSec, boolean isVideo) {
        this.peer = peer;
        this.direction = direction;
        this.timestamp = timestamp;
        this.durationSec = durationSec;
        this.isVideo = isVideo;
    }
}
