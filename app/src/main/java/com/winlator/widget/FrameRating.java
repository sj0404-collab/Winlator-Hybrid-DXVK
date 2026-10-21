package com.winlator.widget;

import android.app.ActivityManager;
import android.content.Context;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.winlator.R;
import com.winlator.box64.Box64Utils;
import com.winlator.core.CPUStatus;
import com.winlator.core.StringUtils;

import java.util.Locale;

public class FrameRating extends FrameLayout implements Runnable {
    public enum Mode {DISABLED, SIMPLE, FULL}
    private long lastTime = 0;
    private short frameCount = 0;
    private float lastFPS = 0;
    private volatile boolean showOverlay = true;
    private volatile boolean statsPending = false;
    private static final java.util.concurrent.ExecutorService STATS_EXEC = java.util.concurrent.Executors.newSingleThreadExecutor(rn -> {
        Thread t = new Thread(rn, "FrameRatingStats");
        t.setDaemon(true);
        return t;
    });

    private static void setTextIfChanged(TextView tv, String text) {
        if (tv != null && text != null && !text.contentEquals(tv.getText())) tv.setText(text);
    }
    private volatile long lastFrameTime = 0;
    private final LinearLayout fpsPanel;
    private final LinearLayout gpuPanel;
    private final LinearLayout ramPanel;
    private final LinearLayout cpuPanel;
    private Mode mode = Mode.SIMPLE;
    private ActivityManager activityManager;
    private ActivityManager.MemoryInfo memoryInfo;
    private String cpuInfo = null;
    private byte tick = 0;

    public FrameRating(Context context) {
        this(context, null);
    }

    public FrameRating(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public FrameRating(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        View view = LayoutInflater.from(context).inflate(R.layout.frame_rating, this, false);
        fpsPanel = view.findViewById(R.id.LLFPSPanel);
        gpuPanel = view.findViewById(R.id.LLGPUPanel);
        ramPanel = view.findViewById(R.id.LLRAMPanel);
        cpuPanel = view.findViewById(R.id.LLCPUPanel);
        addView(view);
        setupPanels();
    }

    private void setupPanels() {
        switch (mode) {
            case DISABLED:
                fpsPanel.setVisibility(GONE);
                gpuPanel.setVisibility(GONE);
                ramPanel.setVisibility(GONE);
                cpuPanel.setVisibility(GONE);
                
                activityManager = null;
                memoryInfo = null;
                break;
            case SIMPLE:
                fpsPanel.setVisibility(VISIBLE);
                gpuPanel.setVisibility(GONE);
                ramPanel.setVisibility(GONE);
                cpuPanel.setVisibility(GONE);

                activityManager = null;
                memoryInfo = null;
                break;
            case FULL:
                fpsPanel.setVisibility(VISIBLE);
                gpuPanel.setVisibility(VISIBLE);
                ramPanel.setVisibility(VISIBLE);
                cpuPanel.setVisibility(VISIBLE);

                Context context = getContext();
                activityManager = (ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
                memoryInfo = new ActivityManager.MemoryInfo();
                break;
        }
    }

    /** v12.6.1: FPS считается всегда; сам оверлей показываем только если включён. */
    public void setShowOverlay(boolean show) {
        showOverlay = show;
        post(() -> {
            if (!showOverlay) setVisibility(GONE);
        });
    }

    public boolean isShowOverlay() {
        return showOverlay;
    }

    /** Текущий FPS игры (0 — если кадров не было больше 2 секунд). */
    public float getLastFPS() {
        if (SystemClock.elapsedRealtime() - lastFrameTime > 2000) return 0;
        return lastFPS;
    }

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
        setupPanels();
    }

    public void setGPUInfo(String gpuInfo) {
        post(() -> ((TextView)gpuPanel.getChildAt(1)).setText(gpuInfo));
    }

    public void reset() {
        frameCount = 0;
        lastTime = SystemClock.elapsedRealtime();
        lastFPS = 0;
        tick = 2;
    }

    public void update() {
        long time = SystemClock.elapsedRealtime();
        lastFrameTime = time;
        if (time >= lastTime + 500) {
            lastFPS = ((float)(frameCount * 1000) / (time - lastTime));
            post(this);
            lastTime = time;
            frameCount = 0;
        }

        frameCount++;
    }

    @Override
    public void run() {
        if (!showOverlay) {
            if (getVisibility() != GONE) setVisibility(GONE);
            return;
        }
        if (getVisibility() == GONE) setVisibility(View.VISIBLE);
        setTextIfChanged((TextView)fpsPanel.getChildAt(1), String.format(Locale.ENGLISH, "%.1f", lastFPS));

        // v12.6.2: память/частоты читаем в фоне — раньше binder и /sys в UI-потоке давали микрофризы раз в секунду
        if (mode == Mode.FULL && ++tick >= 2 && !statsPending) {
            tick = 0;
            statsPending = true;
            STATS_EXEC.execute(() -> {
                String ramText = null, cpuText = null;
                try {
                    activityManager.getMemoryInfo(memoryInfo);
                    long usedMem = memoryInfo.totalMem - memoryInfo.availMem;
                    ramText = StringUtils.formatBytes(usedMem, false)+"/"+StringUtils.formatBytes(memoryInfo.totalMem);
                    if (cpuInfo == null) cpuInfo = "Box64 v"+ Box64Utils.extractBinVersion(cpuPanel.getContext());
                    short[] clockSpeeds = CPUStatus.getCurrentClockSpeeds();
                    int maxClockSpeed = 0;
                    for (short clockSpeed : clockSpeeds) maxClockSpeed = Math.max(maxClockSpeed, clockSpeed);
                    cpuText = CPUStatus.formatClockSpeed(maxClockSpeed)+" | "+cpuInfo;
                } catch (Throwable ignored) {}
                final String fRam = ramText, fCpu = cpuText;
                post(() -> {
                    statsPending = false;
                    setTextIfChanged((TextView)ramPanel.getChildAt(1), fRam);
                    setTextIfChanged((TextView)cpuPanel.getChildAt(1), fCpu);
                });
            });
        }
    }
}