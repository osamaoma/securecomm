package com.example.p2pvoice;

import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class CallLogAdapter extends RecyclerView.Adapter<CallLogAdapter.VH> {

    public interface OnCallBack {
        void onCallBack(CallLogEntry entry);
    }

    private final List<CallLogEntry> entries;
    private final OnCallBack callback;

    public CallLogAdapter(List<CallLogEntry> entries, OnCallBack callback) {
        this.entries = entries;
        this.callback = callback;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_call_log, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        CallLogEntry e = entries.get(position);
        h.peer.setText(e.peer);

        String icon; int color; String dirLabel;
        switch (e.direction) {
            case OUTGOING: icon = "\u2197"; color = 0xFF22A45D; dirLabel = "Outgoing"; break;
            case INCOMING: icon = "\u2199"; color = 0xFF2E5BFF; dirLabel = "Incoming"; break;
            case MISSED:   icon = "\u2718"; color = 0xFFE03A4A; dirLabel = "Missed";   break;
            default:       icon = "\u2022"; color = 0xFF7C8597; dirLabel = "";
        }
        h.dirIcon.setText(icon);
        h.dirIcon.setTextColor(color);

        String sub = dirLabel;
        // Modality after direction. Group videocall isn't a thing in this app
        // so we use the simple "Video" / "Voice" label.
        sub += "  ·  " + (e.isVideo ? "Video" : "Voice");
        if (e.durationSec > 0) {
            long m = e.durationSec / 60, s = e.durationSec % 60;
            sub += "  ·  " + (m > 0 ? m + "m " + s + "s" : s + "s");
        }
        h.subline.setText(sub);

        CharSequence rel = DateUtils.getRelativeTimeSpanString(
                e.timestamp, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS);
        h.time.setText(rel);

        h.callBtn.setOnClickListener(v -> callback.onCallBack(e));
    }

    @Override
    public int getItemCount() { return entries.size(); }

    static class VH extends RecyclerView.ViewHolder {
        TextView dirIcon, peer, subline, time, callBtn;
        VH(@NonNull View v) {
            super(v);
            dirIcon = v.findViewById(R.id.tvDirIcon);
            peer = v.findViewById(R.id.tvPeer);
            subline = v.findViewById(R.id.tvSubline);
            time = v.findViewById(R.id.tvTime);
            callBtn = v.findViewById(R.id.btnCall);
        }
    }
}
