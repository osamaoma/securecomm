package com.example.p2pvoice;

import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Full-screen viewer for a decrypted image file on disk. Tap anywhere to close.
 */
public class ImageViewerActivity extends AppCompatActivity {

    public static final String EXTRA_PATH = "path";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        ImageView iv = new ImageView(this);
        iv.setBackgroundColor(0xFF000000);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setOnClickListener(v -> finish());
        setContentView(iv);

        String path = getIntent().getStringExtra(EXTRA_PATH);
        if (path != null) {
            try {
                iv.setImageBitmap(BitmapFactory.decodeFile(path));
            } catch (Throwable t) {
                finish();
            }
        } else {
            finish();
        }
    }
}
