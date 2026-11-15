package com.majdi.tv.ambient;

import android.service.dreams.DreamService;

/** The system screensaver. Shows {@link AmbientScreen}; any key wakes the TV as usual. */
public class AmbientDream extends DreamService {
    private AmbientScreen screen;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(true);
        screen = new AmbientScreen(this);
        setContentView(screen.view());
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        if (screen != null) screen.start();
    }

    @Override
    public void onDreamingStopped() {
        if (screen != null) screen.stop();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        if (screen != null) screen.destroy();
        screen = null;
        super.onDetachedFromWindow();
    }
}
