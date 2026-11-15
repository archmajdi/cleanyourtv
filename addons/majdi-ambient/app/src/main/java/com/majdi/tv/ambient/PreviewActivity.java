package com.majdi.tv.ambient;

import android.app.Activity;
import android.os.Bundle;
import android.view.WindowManager;

/** The screensaver's screen as an ordinary app, to preview it. Back closes it. */
public class PreviewActivity extends Activity {
    private AmbientScreen screen;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        screen = new AmbientScreen(this);
        setContentView(screen.view());
    }

    @Override
    protected void onResume() {
        super.onResume();
        screen.start();
    }

    @Override
    protected void onPause() {
        screen.stop();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        screen.destroy();
        super.onDestroy();
    }
}
