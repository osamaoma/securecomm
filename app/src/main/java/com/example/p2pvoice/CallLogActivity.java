package com.example.p2pvoice;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.example.p2pvoice.databinding.ActivityCallLogBinding;

import java.util.ArrayList;
import java.util.List;

/**
 * Shows the local call history. Tapping the Call button on any entry redials
 * that user. Long-press the screen header to clear.
 */
public class CallLogActivity extends AppCompatActivity implements CallLogAdapter.OnCallBack {

    private ActivityCallLogBinding b;
    private Store store;
    private SignalingHub hub;
    private final List<CallLogEntry> entries = new ArrayList<>();
    private CallLogAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityCallLogBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());

        store = new Store(this);
        hub = SignalingHub.get(this);

        adapter = new CallLogAdapter(entries, this);
        b.rvLog.setLayoutManager(new LinearLayoutManager(this));
        b.rvLog.setAdapter(adapter);

        b.btnClear.setOnClickListener(v -> confirmClear());

        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        entries.clear();
        entries.addAll(store.getCallLog());
        adapter.notifyDataSetChanged();
        boolean empty = entries.isEmpty();
        b.tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        b.rvLog.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private void confirmClear() {
        if (entries.isEmpty()) return;
        new AlertDialog.Builder(this)
                .setTitle("Clear history?")
                .setMessage("This removes the local call log only.")
                .setPositiveButton("Clear", (d, w) -> { store.clearCallLog(); load(); })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public void onCallBack(CallLogEntry entry) {
        // Need RECORD_AUDIO before placing a call.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            new AlertDialog.Builder(this)
                    .setMessage("Microphone permission required. Open the contacts screen first to grant it.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        hub.placeCall(entry.peer, entry.isVideo);
        Intent i = new Intent(this, CallActivity.class);
        i.putExtra(CallActivity.EXTRA_PEER, entry.peer);
        i.putExtra(CallActivity.EXTRA_MODE, CallActivity.MODE_OUTGOING);
        i.putExtra(CallActivity.EXTRA_IS_VIDEO, entry.isVideo);
        startActivity(i);
    }
}
