package com.winlator.pro;

import com.winlator.xserver.extensions.PresentExtension;
import com.winlator.core.GPUHelper;
import com.winlator.core.EnvVars;
import com.winlator.xenvironment.components.GuestProgramLauncherComponent;
import com.winlator.box64.Box64Preset;
import android.os.Build;
import com.winlator.widget.FrameRating;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.text.TextUtils;
import com.winlator.inputcontrols.GamepadState;
import com.winlator.winhandler.WinHandler;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.preference.PreferenceManager;

import com.winlator.MainActivity;
import com.winlator.R;
import com.winlator.XServerDisplayActivity;
import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.core.AppUtils;
import com.winlator.core.FileUtils;
import com.winlator.core.KeyValueSet;
import com.winlator.core.StringUtils;
import com.winlator.core.TarCompressorUtils;
import com.winlator.core.WineRegistryEditor;
import com.winlator.core.WineUtils;
import com.winlator.inputcontrols.Binding;
import com.winlator.inputcontrols.ControlElement;
import com.winlator.inputcontrols.ControlsProfile;
import com.winlator.inputcontrols.InputControlsManager;
import com.winlator.renderer.GLRenderer;
import com.winlator.renderer.effects.CASSharpenEffect;
import com.winlator.renderer.effects.CRTEffect;
import com.winlator.renderer.effects.ColorEffect;
import com.winlator.renderer.effects.FXAAEffect;
import com.winlator.renderer.effects.HDRBloomEffect;
import com.winlator.renderer.effects.NightVisionEffect;
import com.winlator.widget.InputControlsView;
import com.winlator.widget.TouchpadView;
import com.winlator.win32.MSLink;
import com.winlator.win32.PEParser;
import com.winlator.winhandler.TaskManagerDialog;
import com.winlator.xenvironment.RootFS;
import com.winlator.xserver.Pointer;
import com.winlator.xserver.Window;
import com.winlator.xserver.XLock;
import com.winlator.xserver.XServer;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;

public class WinlatorProSuite {

    public static final String CAT_ALL = "ALL";
    public static final String CAT_GAME = "GAME";
    public static final String CAT_PROGRAM = "PROGRAM";
    public static final String CAT_SYSTEM = "SYSTEM";

    private static volatile boolean crashGuardInstalled = false;
    private static volatile Handler activeWatchdogHandler = null;

    /**
     * Защищает приложение от случайных падений фоновых потоков (иконки, сокеты, файловый сканер),
     * чтобы Android не показывал окно «Winlator Hybrid DXVK остановлено».
     */
    public static void installGlobalCrashGuard() {
        if (crashGuardInstalled) return;
        crashGuardInstalled = true;
        final Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                if (thread != Looper.getMainLooper().getThread()) {
                    // Фоновый поток — игнорируем нефатальную ошибку вместо убийства процесса
                    return;
                }
            } catch (Throwable ignored) {}
            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, throwable);
            }
        });
    }

    public static void stopWatchdog() {
        Handler h = activeWatchdogHandler;
        activeWatchdogHandler = null;
        if (h != null) {
            try {
                h.removeCallbacksAndMessages(null);
            } catch (Throwable ignored) {}
        }
    }

    public static class MemoryStatus {
        public final long freeStorageBytes;
        public final long totalStorageBytes;
        public final long freeRamBytes;
        public final long totalRamBytes;
        public final boolean isLowStorage;
        public final boolean isLowRam;

        public MemoryStatus(long freeStorageBytes, long totalStorageBytes, long freeRamBytes, long totalRamBytes) {
            this.freeStorageBytes = freeStorageBytes;
            this.totalStorageBytes = totalStorageBytes;
            this.freeRamBytes = freeRamBytes;
            this.totalRamBytes = totalRamBytes;
            this.isLowStorage = freeStorageBytes < (2500L * 1024L * 1024L); // < 2.5 GB
            this.isLowRam = freeRamBytes < (1200L * 1024L * 1024L); // < 1.2 GB
        }

        public String formatStorageGb() {
            return String.format(Locale.US, "%.2f/%.0f ГБ",
                freeStorageBytes / (1024.0 * 1024.0 * 1024.0),
                totalStorageBytes / (1024.0 * 1024.0 * 1024.0));
        }

        public String formatRamMb() {
            return String.format(Locale.US, "%d/%d МБ",
                freeRamBytes / (1024L * 1024L),
                totalRamBytes / (1024L * 1024L));
        }
    }

    public static class DiscoveredProgram {
        public final File file;
        public final String name;
        public final String categoryCode; // CAT_GAME, CAT_PROGRAM, CAT_SYSTEM
        public final String categoryLabel;
        public final String dosPath;
        public final String workingDosDir;
        public final long sizeBytes;
        public boolean isOnDesktop;
        public Bitmap iconBitmap;

        public DiscoveredProgram(
            File file,
            String name,
            String categoryCode,
            String categoryLabel,
            String dosPath,
            String workingDosDir,
            long sizeBytes,
            boolean isOnDesktop
        ) {
            this.file = file;
            this.name = name;
            this.categoryCode = categoryCode;
            this.categoryLabel = categoryLabel;
            this.dosPath = dosPath;
            this.workingDosDir = workingDosDir;
            this.sizeBytes = sizeBytes;
            this.isOnDesktop = isOnDesktop;
        }
    }

    public static MemoryStatus queryMemoryStatus(Context context) {
        long freeStorage = 0L;
        long totalStorage = 0L;
        try {
            StatFs statFs = new StatFs(Environment.getDataDirectory().getAbsolutePath());
            freeStorage = statFs.getAvailableBytes();
            totalStorage = statFs.getTotalBytes();
        } catch (Throwable ignored) {}

        long freeRam = 0L;
        long totalRam = 0L;
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                freeRam = mi.availMem;
                totalRam = mi.totalMem;
            }
        } catch (Throwable ignored) {}
        return new MemoryStatus(freeStorage, totalStorage, freeRam, totalRam);
    }

    private static int dp(Context context, int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.getResources().getDisplayMetrics());
    }

    private static GradientDrawable roundedBg(int fillColor, int strokeColor, int strokeWidthDp, int radiusDp, Context ctx) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(fillColor);
        gd.setCornerRadius(dp(ctx, radiusDp));
        if (strokeWidthDp > 0) {
            gd.setStroke(dp(ctx, strokeWidthDp), strokeColor);
        }
        return gd;
    }

    private static Button createStyledButton(Context ctx, String text, int bgColor, int strokeColor) {
        Button btn = new Button(ctx);
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setMinHeight(dp(ctx, 34));
        btn.setMinimumHeight(dp(ctx, 34));
        btn.setPadding(dp(ctx, 10), dp(ctx, 5), dp(ctx, 10), dp(ctx, 5));
        btn.setBackground(roundedBg(bgColor, strokeColor, 1, 8, ctx));
        return btn;
    }

    /**
     * Делает любую View (HUD или панель оверлея) перетаскиваемой пальцем в любое место экрана!
     */
    private static void makeViewDraggable(final View targetView, final View dragHandleView, final View parentContainer) {
        dragHandleView.setOnTouchListener(new View.OnTouchListener() {
            private float dX, dY;
            private float downRawX, downRawY;
            private boolean dragging = false;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                ViewGroup.LayoutParams rawLp = targetView.getLayoutParams();
                if (!(rawLp instanceof FrameLayout.LayoutParams)) return false;
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) rawLp;

                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        if ((lp.gravity & Gravity.CENTER_HORIZONTAL) != 0 || (lp.gravity & Gravity.BOTTOM) != 0 || (lp.gravity & Gravity.END) != 0) {
                            int curLeft = targetView.getLeft();
                            int curTop = targetView.getTop();
                            lp.gravity = Gravity.TOP | Gravity.START;
                            lp.leftMargin = Math.max(0, curLeft);
                            lp.topMargin = Math.max(0, curTop);
                            lp.rightMargin = 0;
                            lp.bottomMargin = 0;
                            targetView.setLayoutParams(lp);
                        }
                        dX = lp.leftMargin - event.getRawX();
                        dY = lp.topMargin - event.getRawY();
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        dragging = false;
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        float moveX = Math.abs(event.getRawX() - downRawX);
                        float moveY = Math.abs(event.getRawY() - downRawY);
                        if (moveX > 8f || moveY > 8f) {
                            dragging = true;
                        }
                        if (dragging) {
                            int maxW = parentContainer != null && parentContainer.getWidth() > 0
                                ? Math.max(0, parentContainer.getWidth() - targetView.getWidth())
                                : 2000;
                            int maxH = parentContainer != null && parentContainer.getHeight() > 0
                                ? Math.max(0, parentContainer.getHeight() - targetView.getHeight())
                                : 2000;
                            lp.leftMargin = Math.max(0, Math.min(maxW, Math.round(event.getRawX() + dX)));
                            lp.topMargin = Math.max(0, Math.min(maxH, Math.round(event.getRawY() + dY)));
                            targetView.setLayoutParams(lp);
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                        if (!dragging) {
                            v.performClick();
                        }
                        return true;
                }
                return false;
            }
        });
    }

    /**
     * Единственная компактная разноцветная панель в главном окне Контейнеров (без дубликатов и
     * без лишней кнопки «Открыть Рабочий Стол Windows», так как у контейнера уже есть кнопка запуска ▶️).
     * Можно свернуть в одну тонкую полоску кнопкой [▲/▼].
     */
    public static View buildTopProDashboardBar(final Activity activity, final Container container, final Runnable onRefreshCallback) {
        final Context ctx = activity;
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(ctx, 8), dp(ctx, 5), dp(ctx, 8), dp(ctx, 5));
        root.setBackground(roundedBg(0xFF0F172A, 0xFF38BDF8, 1, 10, ctx));
        LinearLayout.LayoutParams rootLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        rootLp.setMargins(dp(ctx, 8), dp(ctx, 4), dp(ctx, 8), dp(ctx, 4));
        root.setLayoutParams(rootLp);

        LinearLayout topHeaderRow = new LinearLayout(ctx);
        topHeaderRow.setOrientation(LinearLayout.HORIZONTAL);
        topHeaderRow.setGravity(Gravity.CENTER_VERTICAL);

        MemoryStatus mem = queryMemoryStatus(ctx);
        TextView tvMem = new TextView(ctx);
        boolean warn = mem.isLowStorage || mem.isLowRam;
        tvMem.setText((warn ? "⚠️ " : "💾 ") + "Память: " + mem.formatStorageGb() + " | 🧠 ОЗУ: " + mem.formatRamMb());
        tvMem.setTextColor(warn ? 0xFFFACC15 : 0xFF34D399);
        tvMem.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        tvMem.setTypeface(Typeface.DEFAULT_BOLD);
        topHeaderRow.addView(tvMem, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final HorizontalScrollView hsv = new HorizontalScrollView(ctx);
        hsv.setHorizontalScrollBarEnabled(false);

        final boolean initialCollapsed = prefs.getBoolean("pro_top_bar_collapsed", false);
        hsv.setVisibility(initialCollapsed ? View.GONE : View.VISIBLE);

        final Button btnToggleBar = createStyledButton(ctx, initialCollapsed ? "▼ Панель" : "▲ Скрыть", 0xFF1E293B, 0xFF475569);
        btnToggleBar.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        btnToggleBar.setMinHeight(dp(ctx, 24));
        btnToggleBar.setMinimumHeight(dp(ctx, 24));
        btnToggleBar.setPadding(dp(ctx, 8), dp(ctx, 2), dp(ctx, 8), dp(ctx, 2));
        btnToggleBar.setOnClickListener(v -> {
            boolean nowVisible = hsv.getVisibility() != View.VISIBLE;
            hsv.setVisibility(nowVisible ? View.VISIBLE : View.GONE);
            btnToggleBar.setText(nowVisible ? "▲ Скрыть" : "▼ Панель");
            prefs.edit().putBoolean("pro_top_bar_collapsed", !nowVisible).apply();
        });
        topHeaderRow.addView(btnToggleBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(topHeaderRow);

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(ctx, 5), 0, 0);

        // 1. Универсальный Лаунчер (Игры / Программы / Система)
        Button btnLauncher = createStyledButton(ctx, "🎮 Лаунчер (Игра / Программа / Система)", 0xFF0284C7, 0xFF38BDF8);
        btnLauncher.setOnClickListener(v -> {
            BG.execute(() -> {
                final Container target = resolveTargetContainer(activity, container);
                activity.runOnUiThread(() -> {
                    if (target != null) {
                        showUniversalExeLauncherDialog(activity, target, onRefreshCallback);
                    }
                });
            });
        });
        row.addView(btnLauncher, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT) {{
            rightMargin = dp(ctx, 6);
        }});

        // 2. Открыть папку с игрой целиком
        Button btnPickFolder = createStyledButton(ctx, "📁 Открыть папку с игрой", 0xFF059669, 0xFF34D399);
        btnPickFolder.setOnClickListener(v -> {
            BG.execute(() -> {
                final Container target = resolveTargetContainer(activity, container);
                activity.runOnUiThread(() -> {
                    if (target != null) {
                        promptPickWholeGameFolder(activity, target, onRefreshCallback);
                    }
                });
            });
        });
        row.addView(btnPickFolder, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT) {{
            rightMargin = dp(ctx, 6);
        }});

        // 3. Графика / FPS / Скорость + Шейдеры
        Button btnGraphics = createStyledButton(ctx, "🎨 Графика / FPS / Шейдеры", 0xFF7C3AED, 0xFFA78BFA);
        btnGraphics.setOnClickListener(v -> {
            BG.execute(() -> {
                final Container target = resolveTargetContainer(activity, container);
                activity.runOnUiThread(() -> {
                    if (target != null) {
                        showGraphicsFpsShaderStudioDialog(activity, target, null);
                    }
                });
            });
        });
        row.addView(btnGraphics, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT) {{
            rightMargin = dp(ctx, 6);
        }});

        // 4. Тест FPS (Vulkan / OpenGL / DXVK)
        Button btnBenchmark = createStyledButton(ctx, "📊 Тест FPS", 0xFFD97706, 0xFFFBBF24);
        btnBenchmark.setOnClickListener(v -> {
            Container target = (container != null) ? container : (new ContainerManager(activity)).getDefaultOrFirstContainer();
            showFpsBenchmarkDialog(activity, target);
        });
        row.addView(btnBenchmark, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT) {{
            rightMargin = dp(ctx, 6);
        }});

        // 5. Обновить ярлыки на Рабочем столе
        Button btnSyncIcons = createStyledButton(ctx, "📌 Ярлыки на Рабочий стол", 0xFF1E293B, 0xFF64748B);
        btnSyncIcons.setOnClickListener(v -> {
            BG.execute(() -> {
                final Container target = resolveTargetContainer(activity, container);
                if (target != null) {
                    final int count = syncInstalledExeToDesktopWithIcons(activity, target);
                    activity.runOnUiThread(() -> {
                        AppUtils.showToast(activity, "📌 На Рабочий стол вынесено ярлыков с иконками: " + count);
                        if (onRefreshCallback != null) onRefreshCallback.run();
                    });
                }
            });
        });
        row.addView(btnSyncIcons, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        hsv.addView(row);
        root.addView(hsv);
        return root;
    }

    private static Container resolveTargetContainer(Context context, Container preferred) {
        if (preferred != null) return preferred;
        ContainerManager cm = new ContainerManager(context);
        Container existing = cm.getDefaultOrFirstContainer();
        if (existing != null) return existing;
        return cm.ensureDefaultHybridContainer();
    }

    /**
     * Запускает контейнер или выбранный .exe / .msi файл БЕЗ гигантского дублирующего окна уведомления!
     * Автоматически находит родную папку игры со всеми её данными (UnityPlayer.dll, *_Data, DirectX, vc15),
     * создаёт рабочий ярлык на Рабочем столе и запускает приложение прямо из его папки!
     */
    public static void launchWithMemoryCheck(final Activity activity, final Container container, final String execPathOrNull) {
        if (activity == null || container == null) return;

        BG.execute(() -> {
            String resolvedPath = execPathOrNull;
            if (resolvedPath != null && !resolvedPath.isEmpty()) {
                File f = resolveFullFolderExeIfOrphan(new File(resolvedPath));
                resolvedPath = f.getAbsolutePath();
                prepareGameFolderAndDependencies(activity, container, f);
                pinExecutableToDesktopWithIcon(activity, container, f);
            } else {
                prepareGameFolderAndDependencies(activity, container, null);
            }

            final String finalExecPath = resolvedPath;
            final MemoryStatus mem = queryMemoryStatus(activity);
            activity.runOnUiThread(() -> {
                if (mem.isLowStorage) {
                    AppUtils.showToast(activity, "💾 Свободно памяти: " + mem.formatStorageGb() + " • Запуск...");
                }
                startDesktopActivityNow(activity, container, finalExecPath);
            });
        });
    }

    private static void startDesktopActivityNow(Activity activity, Container container, String execPathOrNull) {
        Intent intent = new Intent(activity, XServerDisplayActivity.class);
        intent.putExtra("container_id", container.id);
        intent.putExtra("always_show_desktop", true);
        if (execPathOrNull != null && !execPathOrNull.isEmpty()) {
            intent.putExtra("exec_path", execPathOrNull);
        }
        activity.startActivity(intent);
    }

    /**
     * Если .exe ранее был скопирован в одиночку в C:\Games или кэш (например, Souldiers.exe без папки Souldiers_Data),
     * автоматически находит полный оригинал файла в его родной папке на памяти устройства (/storage/emulated/0/...).
     */
    public static File resolveFullFolderExeIfOrphan(File exeFile) {
        if (exeFile == null || !exeFile.exists()) return exeFile;
        File parent = exeFile.getParentFile();
        if (parent == null) return exeFile;

        String abs = exeFile.getAbsolutePath();
        boolean inContainerGamesOrCache = abs.contains("/.wine/drive_c/Games/") || abs.contains("/uri_imports/");
        String baseName = FileUtils.getBasename(exeFile.getName());
        boolean missingUnityData = !new File(parent, baseName + "_Data").isDirectory() && !new File(parent, "UnityPlayer.dll").isFile();

        if (inContainerGamesOrCache && missingUnityData) {
            File realOnStorage = FileUtils.findExistingFileOnStorage(exeFile.getName(), exeFile.length());
            if (realOnStorage != null && realOnStorage.isFile() && !realOnStorage.getAbsolutePath().equals(abs)) {
                return realOnStorage;
            }
        }
        return exeFile;
    }

    /**
     * Подготавливает папку с игрой / программой целиком, монтирует все диски памяти телефона (D:, E:, F:, G:, H:),
     * устанавливает/проверяет библиотеки DirectX (d3dx9/10/11, d3dcompiler_47, xinput, xaudio) и VC15 (VC++ 2015–2022:
     * vcruntime140.dll, vcruntime140_1.dll, msvcp140.dll, concrt140.dll, vcomp140.dll, ucrtbase.dll),
     * а также находит все .dll и редистрибутивы (_CommonRedist, Redist, DirectX, vc15, *_Data/Plugins, MonoBleedingEdge)
     * внутри папки с игрой и возвращает строку WINEPATH для их автоматической подгрузки!
     */
    public static String prepareGameFolderAndDependencies(Context context, Container container, File targetExeOrNull) {
        if (context == null || container == null) return "";
        try {
            // 1. Гарантируем монтирование всей внутренней памяти телефона (/storage/emulated/0 -> G:) и загрузок (D:)
            String extRoot = Environment.getExternalStorageDirectory().getPath();
            String drives = container.getDrives();
            if (drives == null) drives = Container.DEFAULT_DRIVES;
            boolean drivesChanged = false;
            if (!drives.contains("D:")) {
                drives += "D:" + AppUtils.DIRECTORY_DOWNLOADS;
                drivesChanged = true;
            }
            if (!drives.contains("G:")) {
                drives += "G:" + extRoot;
                drivesChanged = true;
            }

            File resolvedExe = targetExeOrNull != null ? resolveFullFolderExeIfOrphan(targetExeOrNull) : null;
            File gameFolder = (resolvedExe != null && resolvedExe.getParentFile() != null) ? resolvedExe.getParentFile() : null;

            // Если папка игры находится на внешней SD-карте или нестандартном разделе — монтируем её как диск H:
            if (gameFolder != null && gameFolder.isDirectory()) {
                String gPath = gameFolder.getAbsolutePath();
                if (!gPath.startsWith(extRoot) && !gPath.contains("/.wine/drive_c")) {
                    if (!drives.contains("H:")) {
                        drives += "H:" + gPath;
                        drivesChanged = true;
                    }
                }
            }

            // 2. Включаем все компоненты DirectX и Visual C++ в контейнере
            String fullWinComponents = Container.DEFAULT_WINCOMPONENTS;
            if (!fullWinComponents.equals(container.getWinComponents())) {
                container.setWinComponents(fullWinComponents);
                drivesChanged = true;
            }
            if (drivesChanged) {
                container.setDrives(drives);
                container.saveData();
            }
            WineUtils.createDosdevicesSymlinks(container, true);

            // 3. Проверяем и докопируем в system32 / syswow64 библиотеки DirectX 9/10/11 и VC15 (VC++ 2015–2022)
            ensureDirectXAndVc15RuntimeDlls(context, container);

            // 4. Сканируем папку самой игры и её подпапки (_CommonRedist, Redist, DirectX, vc15, Plugins, MonoBleedingEdge, bin, x64)
            if (gameFolder != null && gameFolder.isDirectory()) {
                Set<String> dllDirsDos = new LinkedHashSet<>();
                String mainDosDir = WineUtils.unixToDOSPath(gameFolder.getAbsolutePath(), container);
                if (mainDosDir != null && !mainDosDir.isEmpty() && !mainDosDir.equals("\\")) {
                    dllDirsDos.add(mainDosDir);
                }
                scanGameFolderForRedistsAndDlls(container, gameFolder, gameFolder, 0, 3, dllDirsDos);

                StringBuilder winePathSb = new StringBuilder();
                for (String d : dllDirsDos) {
                    if (winePathSb.length() > 0) winePathSb.append(";");
                    winePathSb.append(d);
                }
                return winePathSb.toString();
            }
        } catch (Throwable ignored) {}
        return "";
    }

    private static void ensureDirectXAndVc15RuntimeDlls(Context context, Container container) {
        try {
            File rootDir = RootFS.find(context).getRootDir();
            File wineX64 = new File(rootDir, "/opt/wine/lib/wine/x86_64-windows");
            File wineX86 = new File(rootDir, "/opt/wine/lib/wine/i386-windows");
            File sys32 = new File(container.getRootDir(), ".wine/drive_c/windows/system32");
            File syswow64 = new File(container.getRootDir(), ".wine/drive_c/windows/syswow64");
            if (!sys32.isDirectory()) sys32.mkdirs();
            if (!syswow64.isDirectory()) syswow64.mkdirs();

            // 1. Сначала распаковываем настоящие нативные архивы DirectX 9/10/11 (direct3d.tzst с Microsoft d3dcompiler_47.dll, d3dx11_43.dll),
            // DirectSound, DirectMusic, DirectPlay, VC++ 2010 и WMDecoder, чтобы они НЕ перекрывались встроенными заглушками Wine!
            File marker = new File(container.getRootDir(), ".wine/.pro_dx_vc15_v123_ready");
            File d3dComp64 = new File(sys32, "d3dcompiler_47.dll");
            if (!marker.isFile() || !d3dComp64.isFile() || d3dComp64.length() < 2000000L) {
                File winDir = new File(container.getRootDir(), ".wine/drive_c/windows");
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, "wincomponents/direct3d.tzst", winDir);
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, "wincomponents/directsound.tzst", winDir);
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, "wincomponents/directmusic.tzst", winDir);
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, "wincomponents/directplay.tzst", winDir);
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, "wincomponents/vcrun2010.tzst", winDir);
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, "wincomponents/wmdecoder.tzst", winDir);
                FileUtils.writeString(marker, "1");
            }

            // 2. Докопируем недостающие библиотеки VC15 (VC++ 2015-2022), XInput и XAudio, не трогая уже распакованные нативные DLL
            final String[] criticalDlls = {
                "vcruntime140.dll", "vcruntime140_1.dll",
                "msvcp140.dll", "msvcp140_1.dll", "msvcp140_2.dll",
                "msvcp140_atomic_wait.dll", "msvcp140_codecvt_ids.dll",
                "concrt140.dll", "vcomp140.dll", "ucrtbase.dll",
                "msvcp120.dll", "msvcr120.dll", "vcomp120.dll",
                "msvcp110.dll", "msvcr110.dll", "vcomp110.dll",
                "msvcp100.dll", "msvcr100.dll", "vcomp100.dll",
                "msvcp90.dll", "msvcr90.dll", "msvcp80.dll", "msvcr80.dll",
                "d3dcompiler_43.dll", "d3dcompiler_46.dll", "d3dcompiler_47.dll",
                "d3dx9_41.dll", "d3dx9_42.dll", "d3dx9_43.dll",
                "d3dx10_43.dll", "d3dx11_42.dll", "d3dx11_43.dll",
                "xinput1_1.dll", "xinput1_2.dll", "xinput1_3.dll", "xinput1_4.dll", "xinput9_1_0.dll",
                "xaudio2_7.dll", "xaudio2_8.dll", "xaudio2_9.dll", "x3daudio1_7.dll"
            };

            for (String dll : criticalDlls) {
                File dst64 = new File(sys32, dll);
                if (!dst64.isFile()) {
                    File src64 = new File(wineX64, dll);
                    if (src64.isFile()) FileUtils.copy(src64, dst64);
                }
                File dst32 = new File(syswow64, dll);
                if (!dst32.isFile()) {
                    File src32 = new File(wineX86, dll);
                    if (src32.isFile()) FileUtils.copy(src32, dst32);
                }
            }

            patchWinHandlerBinaryToStayAlive(rootDir, container);
        } catch (Throwable ignored) {}
    }

    /**
     * Патчит 6 байт условного перехода `je 0x140002f3c` (`0F 84 C1 00 00 00` после `80 7D C0 02`)
     * на `90 90 90 90 90 90` (NOP) внутри `winhandler.exe`, чтобы `winhandler.exe` не завершался
     * через 2 секунды после закрытия `wfm.exe` и всегда поддерживал «Диспетчер задач» и геймпад!
     */
    public static void patchWinHandlerBinaryToStayAlive(File rootDir, Container container) {
        try {
            List<File> candidates = new ArrayList<>();
            if (container != null && container.getRootDir() != null) {
                candidates.add(new File(container.getRootDir(), ".wine/drive_c/windows/winhandler.exe"));
            }
            if (rootDir != null) {
                candidates.add(new File(rootDir, "home/xuser/.wine/drive_c/windows/winhandler.exe"));
                candidates.add(new File(rootDir, "opt/wine/lib/wine/x86_64-windows/winhandler.exe"));
            }
            final byte[] sig = new byte[]{
                (byte) 0x80, (byte) 0x7D, (byte) 0xC0, (byte) 0x02,
                (byte) 0x0F, (byte) 0x84, (byte) 0xC1, (byte) 0x00, (byte) 0x00, (byte) 0x00
            };
            for (File exe : candidates) {
                if (exe == null || !exe.isFile()) continue;
                byte[] data = FileUtils.read(exe);
                if (data == null || data.length < 1024) continue;
                boolean modified = false;
                for (int i = 0; i <= data.length - sig.length; i++) {
                    boolean match = true;
                    for (int j = 0; j < sig.length; j++) {
                        if (data[i + j] != sig[j]) {
                            match = false;
                            break;
                        }
                    }
                    if (match) {
                        for (int k = 4; k < 10; k++) {
                            data[i + k] = (byte) 0x90;
                        }
                        modified = true;
                        break;
                    }
                }
                if (modified) {
                    try (FileOutputStream fos = new FileOutputStream(exe)) {
                        fos.write(data);
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void scanGameFolderForRedistsAndDlls(
        Container container,
        File rootGameDir,
        File currentDir,
        int depth,
        int maxDepth,
        Set<String> outDllDirsDos
    ) {
        if (currentDir == null || !currentDir.isDirectory() || depth > maxDepth) return;
        File[] files = currentDir.listFiles();
        if (files == null) return;

        boolean hasDll = false;
        String dirNameLower = currentDir.getName().toLowerCase(Locale.ROOT);
        boolean isRedistSubdir = dirNameLower.contains("redist") || dirNameLower.contains("directx") ||
            dirNameLower.contains("vc15") || dirNameLower.contains("vcredist") || dirNameLower.contains("prereq");

        for (File f : files) {
            if (f.isDirectory()) {
                if (!f.getName().startsWith(".")) {
                    scanGameFolderForRedistsAndDlls(container, rootGameDir, f, depth + 1, maxDepth, outDllDirsDos);
                }
            } else {
                String lower = f.getName().toLowerCase(Locale.ROOT);
                if (lower.endsWith(".dll")) {
                    hasDll = true;
                    // Если DLL лежит внутри подпапки редистрибутивов (_CommonRedist, DirectX, vc15) —
                    // дублируем её в корень папки с игрой, чтобы .exe гарантированно нашёл её при старте!
                    if (isRedistSubdir && !currentDir.equals(rootGameDir)) {
                        File targetInGameRoot = new File(rootGameDir, f.getName());
                        if (!targetInGameRoot.exists()) {
                            FileUtils.copy(f, targetInGameRoot);
                        }
                    }
                }
            }
        }

        if (hasDll) {
            String dosDir = WineUtils.unixToDOSPath(currentDir.getAbsolutePath(), container);
            if (dosDir != null && !dosDir.isEmpty() && !dosDir.equals("\\")) {
                outDllDirsDos.add(dosDir);
            }
        }
    }

    /**
     * Диалог выбора целой папки с игрой (через системный выбор папки Android или список папок в памяти),
     * чтобы игра открывалась вместе со всей своей папкой, данными, DirectX и VC15!
     */
    public static void promptPickWholeGameFolder(final Activity activity, final Container container, final Runnable onRefreshCallback) {
        if (!(activity instanceof MainActivity)) return;
        final MainActivity ma = (MainActivity) activity;
        ma.setOpenDirectoryCallback(treeUri -> {
            if (treeUri == null) return;
            BG.execute(() -> {
                String folderPath = FileUtils.getFilePathFromUri(treeUri);
                if (folderPath == null || folderPath.isEmpty()) {
                    String last = treeUri.getLastPathSegment();
                    if (last != null && last.contains(":")) {
                        String[] parts = last.split(":", 2);
                        folderPath = Environment.getExternalStorageDirectory() + "/" + (parts.length > 1 ? parts[1] : "");
                    }
                }
                final File folder = (folderPath != null) ? new File(folderPath) : null;
                if (folder == null || !folder.isDirectory()) {
                    activity.runOnUiThread(() -> AppUtils.showToast(activity, "⚠️ Выберите папку внутри памяти телефона"));
                    return;
                }

                // Ищем все .exe/.msi внутри выбранной папки с игрой
                final List<File> foundExes = new ArrayList<>();
                findExecutablesInGameFolder(folder, 0, 2, foundExes);
                if (foundExes.isEmpty()) {
                    activity.runOnUiThread(() -> AppUtils.showToast(activity, "⚠️ В выбранной папке (" + folder.getName() + ") не найдено .exe или .msi файлов"));
                    return;
                }

                // Выбираем главный .exe игры (пропуская unins000.exe, UnityCrashHandler и vc_redist)
                File bestGameExe = foundExes.get(0);
                for (File cand : foundExes) {
                    if (classifyExecutable(cand, folder).equals(CAT_GAME)) {
                        bestGameExe = cand;
                        break;
                    }
                }
                for (File cand : foundExes) {
                    if (!classifyExecutable(cand, folder).equals(CAT_SYSTEM)) {
                        pinExecutableToDesktopWithIcon(activity, container, cand);
                    }
                }
                prepareGameFolderAndDependencies(activity, container, bestGameExe);
                pinExecutableToDesktopWithIcon(activity, container, bestGameExe);

                final File launchTarget = bestGameExe;
                activity.runOnUiThread(() -> {
                    if (onRefreshCallback != null) onRefreshCallback.run();
                    AppUtils.showToast(activity, "✅ Папка «" + folder.getName() + "» подключена целиком! Ярлык создан: " + launchTarget.getName());
                    launchWithMemoryCheck(activity, container, launchTarget.getAbsolutePath());
                });
            });
        });

        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            ma.startActivityForResult(intent, MainActivity.OPEN_DIRECTORY_REQUEST_CODE);
        } catch (Throwable e) {
            showUniversalExeLauncherDialog(activity, container, onRefreshCallback);
        }
    }

    private static void findExecutablesInGameFolder(File dir, int depth, int maxDepth, List<File> out) {
        if (dir == null || !dir.isDirectory() || depth > maxDepth) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                findExecutablesInGameFolder(f, depth + 1, maxDepth, out);
            } else {
                String lower = f.getName().toLowerCase(Locale.ROOT);
                if (lower.endsWith(".exe") || lower.endsWith(".msi") || lower.endsWith(".bat")) {
                    out.add(f);
                }
            }
        }
    }

    /**
     * Создаёт рабочий ярлык .desktop и .lnk на Рабочем столе Windows (Users/xuser/Desktop)
     * с правильным путём (без одиночного '\'), рабочей директорией (workingDir) и извлечённой иконкой!
     */
    public static boolean pinExecutableToDesktopWithIcon(Context context, Container container, File rawExeFile) {
        if (container == null || rawExeFile == null || !rawExeFile.exists()) return false;
        try {
            File exeFile = resolveFullFolderExeIfOrphan(rawExeFile);

            File desktopDir = new File(container.getUserDir(), "Desktop");
            if (!desktopDir.exists()) desktopDir.mkdirs();

            String baseName = FileUtils.getBasename(exeFile.getName());
            if (baseName.isEmpty()) baseName = exeFile.getName();
            String safeIconName = baseName.replaceAll("[^a-zA-Z0-9._-]", "_");

            // v12.6.2: ярлык уже есть и указывает на этот же .exe — ничего не делаем (раньше при каждом открытии меню
            // заново разбиралась иконка, дважды сжимался PNG и перезаписывался ярлык, стирая его личные настройки)
            File existingEntry = new File(desktopDir, baseName + ".desktop");
            String preservedExtra = "";
            if (existingEntry.isFile()) {
                String old = FileUtils.readString(existingEntry);
                String dosNow = WineUtils.unixToDOSPath(exeFile.getAbsolutePath(), container);
                if (old != null && dosNow != null && !dosNow.isEmpty() && old.contains(StringUtils.escapeDOSPath(dosNow))) return false;
                int ix = old != null ? old.indexOf("[Extra Data]") : -1;
                if (ix >= 0) {
                    StringBuilder keep = new StringBuilder();
                    for (String line : old.substring(ix + 12).split("\n")) {
                        String t = line.trim();
                        if (t.startsWith("[")) break;
                        if (!t.isEmpty() && !t.startsWith("container_id=")) keep.append(t).append('\n');
                    }
                    preservedExtra = keep.toString();
                }
            }

            // Извлекаем реальную иконку из .exe через PEParser во все стандартные папки иконок контейнера
            Bitmap iconBmp = null;
            if (exeFile.getName().toLowerCase(Locale.ROOT).endsWith(".exe")) {
                iconBmp = PEParser.extractIcon(exeFile);
            }
            if (iconBmp != null) {
                File icons64Dir = container.getIconsDir(64);
                if (!icons64Dir.exists()) icons64Dir.mkdirs();
                File iconPng = new File(icons64Dir, safeIconName + ".png");
                try (FileOutputStream fos = new FileOutputStream(iconPng)) {
                    iconBmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
                }
                File iconDirectName = new File(icons64Dir, baseName + ".png");
                if (!iconDirectName.equals(iconPng)) {
                    try (FileOutputStream fos2 = new FileOutputStream(iconDirectName)) {
                        iconBmp.compress(Bitmap.CompressFormat.PNG, 100, fos2);
                    }
                }
            }

            String dosPath = WineUtils.unixToDOSPath(exeFile.getAbsolutePath(), container);
            if (dosPath == null || dosPath.isEmpty() || dosPath.equals("\\")) {
                dosPath = "G:\\" + exeFile.getName();
            }
            String dosWorkingDir = FileUtils.getDirname(dosPath);
            if (dosWorkingDir == null || dosWorkingDir.isEmpty() || !dosWorkingDir.contains(":")) {
                dosWorkingDir = "C:\\windows";
            }

            // 1. Создаём/обновляем .lnk файл для отображения внутри Рабочего стола Windows (explorer.exe) с рабочей папкой!
            File lnkFile = new File(desktopDir, baseName + ".lnk");
            MSLink.LinkInfo linkInfo = new MSLink.LinkInfo();
            linkInfo.targetPath = dosPath;
            linkInfo.workingDir = dosWorkingDir;
            linkInfo.iconLocation = dosPath;
            linkInfo.isDirectory = false;
            MSLink.createFile(linkInfo, lnkFile);

            // 2. Создаём/обновляем .desktop файл для отображения во вкладке «Ярлыки» (ShortcutsFragment)
            // ВАЖНО: используем StringUtils.escapeDOSPath(dosPath) БЕЗ внешних кавычек вокруг пути,
            // чтобы Shortcut.java корректно извлекал чистый путь без двойных кавычек!
            File desktopEntry = new File(desktopDir, baseName + ".desktop");
            String winePrefix = container.getRootDir().getAbsolutePath() + "/.wine";
            String escapedDos = StringUtils.escapeDOSPath(dosPath);
            String content = "[Desktop Entry]\n" +
                "Name=" + baseName + "\n" +
                "Exec=env WINEPREFIX=\"" + winePrefix + "\" wine " + escapedDos + "\n" +
                "Path=" + dosWorkingDir + "\n" +
                "Type=Application\n" +
                "StartupNotify=true\n" +
                "Icon=" + safeIconName + "\n" +
                "StartupWMClass=" + exeFile.getName() + "\n" +
                "\n[Extra Data]\n" +
                "container_id=" + container.id + "\n" + preservedExtra;
            FileUtils.writeString(desktopEntry, content);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * Проверяет, является ли файл системной утилитой / деинсталлятором / редистрибутивом,
     * которые должны относиться к категории «⚙️ Система» и не засорять Рабочий стол.
     */
    private static boolean isSystemOrHelperExecutable(String fileNameLower, String fullPathLower) {
        if (fileNameLower.startsWith("unins") || fileNameLower.contains("uninstall") ||
            fileNameLower.contains("crashhandler") || fileNameLower.contains("crashpad") ||
            fileNameLower.contains("vc_redist") || fileNameLower.contains("vcredist") ||
            fileNameLower.contains("dxsetup") || fileNameLower.contains("ue4prereq") ||
            fileNameLower.equals("add_path.exe") || fileNameLower.equals("msend.exe") ||
            fileNameLower.equals("cz2slub.exe") || fileNameLower.equals("winhandler.exe") ||
            fileNameLower.equals("wfm.exe") || fileNameLower.equals("winecfg.exe") ||
            fileNameLower.equals("explorer.exe") || fileNameLower.equals("cmd.exe") ||
            fileNameLower.equals("notepad.exe") || fileNameLower.equals("regedit.exe") ||
            fileNameLower.equals("iexplore.exe") || fileNameLower.equals("winemenubuilder.exe") ||
            fileNameLower.equals("services.exe") || fileNameLower.equals("winedevice.exe") ||
            fileNameLower.equals("plugplay.exe") || fileNameLower.equals("svchost.exe") ||
            fileNameLower.equals("rpcss.exe") || fileNameLower.equals("conhost.exe") ||
            fileNameLower.equals("taskmgr.exe") || fileNameLower.equals("control.exe") ||
            fileNameLower.equals("msiexec.exe") || fileNameLower.equals("testd3d.exe") ||
            fileNameLower.equals("gpuinfo.exe") || fileNameLower.equals("winaddons.exe")) {
            return true;
        }
        return fullPathLower.contains("/_commonredist/") || fullPathLower.contains("/redist/") ||
               fullPathLower.contains("/directx/") || fullPathLower.contains("/vc15/");
    }

    /**
     * Точная классификация каждого файла на 3 категории:
     * - CAT_GAME («🎮 Игра»)
     * - CAT_PROGRAM («💼 Программа»)
     * - CAT_SYSTEM («⚙️ Система»)
     */
    public static String classifyExecutable(File exeFile, File parentDir) {
        if (exeFile == null) return CAT_PROGRAM;
        String lower = exeFile.getName().toLowerCase(Locale.ROOT);
        String absLower = exeFile.getAbsolutePath().toLowerCase(Locale.ROOT).replace('\\', '/');

        // 1. Система: деинсталляторы (unins000.exe), установщики (setup/install/.msi), редистрибутивы (DirectX, vc15) и системные утилиты
        if (isSystemOrHelperExecutable(lower, absLower) ||
            lower.endsWith(".msi") || lower.endsWith(".bat") || lower.endsWith(".cmd") ||
            lower.contains("setup") || lower.contains("install") || lower.contains("patch") ||
            lower.startsWith("is-") || absLower.contains("/windows/")) {
            return CAT_SYSTEM;
        }

        // 2. Игра: находится в папке Games / GOG Games / Steam или содержит игровые движки/данные (UnityPlayer.dll, *_Data, steam_api, .pak, .pck)
        String baseName = FileUtils.getBasename(exeFile.getName());
        if (parentDir != null && parentDir.isDirectory()) {
            if (new File(parentDir, baseName + "_Data").isDirectory() ||
                new File(parentDir, "UnityPlayer.dll").isFile() ||
                new File(parentDir, "MonoBleedingEdge").isDirectory() ||
                new File(parentDir, "steam_api.dll").isFile() ||
                new File(parentDir, "steam_api64.dll").isFile() ||
                new File(parentDir, "Galaxy.dll").isFile() ||
                new File(parentDir, "Galaxy64.dll").isFile() ||
                new File(parentDir, "Engine").isDirectory() ||
                new File(parentDir, "SDL2.dll").isFile() ||
                new File(parentDir, "bink2w64.dll").isFile() ||
                new File(parentDir, "fmod.dll").isFile() ||
                new File(parentDir, "fmod64.dll").isFile()) {
                return CAT_GAME;
            }
        }
        if (absLower.contains("/games/") || absLower.contains("/gog games/") ||
            absLower.contains("/steamapps/") || lower.contains("souldiers")) {
            return CAT_GAME;
        }

        // 3. Программа: прикладные приложения (ZipGenius, 7-Zip, браузеры, редакторы и др.)
        return CAT_PROGRAM;
    }

    public static String getCategoryBadgeLabel(String categoryCode, String fileNameLower) {
        if (CAT_GAME.equals(categoryCode)) {
            return "🎮 Игра";
        } else if (CAT_SYSTEM.equals(categoryCode)) {
            if (fileNameLower.startsWith("unins") || fileNameLower.contains("uninstall")) {
                return "⚙️ Система (Деинсталлятор)";
            } else if (fileNameLower.endsWith(".msi") || fileNameLower.contains("setup") || fileNameLower.contains("install")) {
                return "⚙️ Система (Установщик)";
            }
            return "⚙️ Система";
        } else {
            return "💼 Программа";
        }
    }

    /**
     * Очищает Рабочий стол от мусорных системных ярлыков (add_path, cz2slub, msend, unins000, vc_redist, winhandler)
     * и автоматически выносит все настоящие Игры и Программы с их иконками и рабочими папками!
     */
    public static int syncInstalledExeToDesktopWithIcons(Context context, Container container) {
        if (container == null) return 0;
        try {
            File desktopDir = new File(container.getUserDir(), "Desktop");
            if (desktopDir.isDirectory()) {
                File[] deskFiles = desktopDir.listFiles();
                if (deskFiles != null) {
                    final String[] junkNames = {"add_path", "cz2slub", "msend", "unins000", "vc_redist.x64", "vc_redist.x86", "winhandler", "unitycrashhandler64", "unitycrashhandler32"};
                    for (File df : deskFiles) {
                        String b = FileUtils.getBasename(df.getName()).toLowerCase(Locale.ROOT);
                        for (String junk : junkNames) {
                            if (b.equals(junk) || b.startsWith("unins0")) {
                                df.delete();
                                break;
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}

        int count = 0;
        List<DiscoveredProgram> all = scanAllAvailableProgramsAndGames(container);
        for (DiscoveredProgram prog : all) {
            if (CAT_SYSTEM.equals(prog.categoryCode)) {
                // Не засоряем Рабочий стол системными утилитами и деинсталляторами unins000.exe,
                // кроме явно нужного SteamSetup.exe
                if (!prog.file.getName().equalsIgnoreCase("SteamSetup.exe")) {
                    continue;
                }
            }
            if (pinExecutableToDesktopWithIcon(context, container, prog.file)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Сканирует все доступные игры, программы и системные файлы (.exe, .msi, .bat, .cmd)
     * на всех дисках контейнера (C:, D: Download, G: память телефона, GOG Games, Games, Program Files).
     */
    public static List<DiscoveredProgram> scanAllAvailableProgramsAndGames(Container container) {
        List<DiscoveredProgram> result = new ArrayList<>();
        Set<String> visitedPaths = new HashSet<>();
        if (container == null) return result;

        File driveC = new File(container.getRootDir(), ".wine/drive_c");
        File desktopDir = new File(container.getUserDir(), "Desktop");
        File extRoot = Environment.getExternalStorageDirectory();
        File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);

        List<File> rootsToScan = Arrays.asList(
            new File(driveC, "Games"),
            new File(driveC, "GOG Games"),
            new File(driveC, "Program Files"),
            new File(driveC, "Program Files (x86)"),
            downloadsDir,
            new File(extRoot, "Games"),
            new File(extRoot, "GOG Games"),
            new File(extRoot, "Winlator"),
            documentsDir,
            desktopDir
        );

        for (File root : rootsToScan) {
            if (root != null && root.exists()) {
                scanDirectoryRecursive(container, root, desktopDir, 0, 3, visitedPaths, result);
            }
        }

        Collections.sort(result, new Comparator<DiscoveredProgram>() {
            @Override
            public int compare(DiscoveredProgram a, DiscoveredProgram b) {
                int rankA = CAT_GAME.equals(a.categoryCode) ? 0 : (CAT_PROGRAM.equals(a.categoryCode) ? 1 : 2);
                int rankB = CAT_GAME.equals(b.categoryCode) ? 0 : (CAT_PROGRAM.equals(b.categoryCode) ? 1 : 2);
                if (rankA != rankB) return Integer.compare(rankA, rankB);
                if (a.isOnDesktop != b.isOnDesktop) return a.isOnDesktop ? -1 : 1;
                return a.name.compareToIgnoreCase(b.name);
            }
        });
        return result;
    }

    private static void scanDirectoryRecursive(
        Container container,
        File dir,
        File desktopDir,
        int depth,
        int maxDepth,
        Set<String> visited,
        List<DiscoveredProgram> out
    ) {
        if (dir == null || !dir.exists() || depth > maxDepth || out.size() > 180) return;
        File[] children = dir.listFiles();
        if (children == null) return;

        for (File f : children) {
            File targetFile = f.getName().toLowerCase(Locale.ROOT).endsWith(".exe") ? resolveFullFolderExeIfOrphan(f) : f;
            String abs = targetFile.getAbsolutePath();
            if (visited.contains(abs)) continue;
            visited.add(abs);

            if (targetFile.isDirectory()) {
                String dName = targetFile.getName().toLowerCase(Locale.ROOT);
                if (dName.equals("windows") || dName.equals("common files") || dName.equals("internet explorer") ||
                    dName.equals("windows nt") || dName.equals("windows media player") ||
                    dName.equals("monobleedingedge") || dName.endsWith("_data") || dName.startsWith(".")) {
                    continue;
                }
                scanDirectoryRecursive(container, targetFile, desktopDir, depth + 1, maxDepth, visited, out);
            } else {
                String name = targetFile.getName();
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".exe") || lower.endsWith(".msi") || lower.endsWith(".bat") || lower.endsWith(".cmd")) {
                    if (lower.equals("winhandler.exe") || lower.equals("wfm.exe") ||
                        lower.equals("add_path.exe") || lower.equals("msend.exe") || lower.equals("cz2slub.exe") ||
                        lower.contains("unitycrashhandler")) {
                        continue;
                    }
                    String baseName = FileUtils.getBasename(name);
                    String catCode = classifyExecutable(targetFile, targetFile.getParentFile());
                    String catLabel = getCategoryBadgeLabel(catCode, lower);
                    String dos = WineUtils.unixToDOSPath(abs, container);
                    if (dos == null || dos.isEmpty() || dos.equals("\\")) {
                        dos = "G:\\" + name;
                    }
                    String workingDosDir = FileUtils.getDirname(dos);
                    boolean onDesktop = desktopDir != null && (
                        new File(desktopDir, baseName + ".desktop").exists() ||
                        new File(desktopDir, baseName + ".lnk").exists()
                    );
                    DiscoveredProgram prog = new DiscoveredProgram(
                        targetFile, baseName, catCode, catLabel, dos, workingDosDir, targetFile.length(), onDesktop
                    );
                    if (lower.endsWith(".exe")) {
                        prog.iconBitmap = PEParser.extractIcon(targetFile);
                    }
                    out.add(prog);
                }
            }
        }
    }

    /**
     * ============================================================================
     * МОДУЛЬ 2: УНИВЕРСАЛЬНЫЙ ЛАУНЧЕР С ФИЛЬТРАЦИЕЙ «ИГРА — ПРОГРАММА — СИСТЕМА»
     * И ОТКРЫТИЕМ ЛЮБОГО .EXE/.MSI ЦЕЛИКОМ С ЕГО ПАПКОЙ, DIRECTX И VC15!
     * ============================================================================
     */
    public static void showUniversalExeLauncherDialog(final Activity activity, final Container container, final Runnable onRefreshCallback) {
        final Context ctx = activity;
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10));
        root.setBackgroundColor(0xFF0B1120);

        TextView tvTitle = new TextView(ctx);
        tvTitle.setText("🎮 Лаунчер: Игры • Программы • Система (.EXE / .MSI)");
        tvTitle.setTextColor(Color.WHITE);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(tvTitle);

        MemoryStatus mem = queryMemoryStatus(ctx);
        TextView tvSub = new TextView(ctx);
        tvSub.setText("💾 Свободно: " + mem.formatStorageGb() + " | 🧠 ОЗУ: " + mem.formatRamMb() +
            "\nОткрывает любой .exe/.msi целиком со всей его папкой данных, DirectX и VC15 (VC++ 2015-2022):");
        tvSub.setTextColor(mem.isLowStorage ? 0xFFFACC15 : 0xFF38BDF8);
        tvSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        tvSub.setPadding(0, dp(ctx, 2), 0, dp(ctx, 6));
        root.addView(tvSub);

        final AlertDialog dialog = new AlertDialog.Builder(ctx)
            .setView(root)
            .create();

        // Кнопки выбора файла (с родной папкой) и выбора целой папки игры
        LinearLayout topButtons = new LinearLayout(ctx);
        topButtons.setOrientation(LinearLayout.HORIZONTAL);
        topButtons.setPadding(0, 0, 0, dp(ctx, 6));

        Button btnPickExternal = createStyledButton(ctx, "📂 Выбрать .EXE/.MSI (с его папкой)", 0xFF0284C7, 0xFF38BDF8);
        btnPickExternal.setOnClickListener(v -> {
            dialog.dismiss();
            if (activity instanceof MainActivity) {
                final MainActivity ma = (MainActivity) activity;
                ma.setOpenFileCallback(uri -> {
                    BG.execute(() -> {
                        ContainerManager cm = new ContainerManager(activity);
                        final File imported = cm.importExeFromUri(uri, container);
                        activity.runOnUiThread(() -> {
                            if (onRefreshCallback != null) onRefreshCallback.run();
                            if (imported != null) {
                                launchWithMemoryCheck(activity, container, imported.getAbsolutePath());
                            }
                        });
                    });
                });
                Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                pick.addCategory(Intent.CATEGORY_OPENABLE);
                pick.setType("*/*");
                ma.startActivityForResult(pick, MainActivity.OPEN_FILE_REQUEST_CODE);
            }
        });
        topButtons.addView(btnPickExternal, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) {{
            rightMargin = dp(ctx, 6);
        }});

        Button btnPickFolder = createStyledButton(ctx, "📁 Открыть папку с игрой целиком", 0xFF059669, 0xFF34D399);
        btnPickFolder.setOnClickListener(v -> {
            dialog.dismiss();
            promptPickWholeGameFolder(activity, container, onRefreshCallback);
        });
        topButtons.addView(btnPickFolder, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(topButtons);

        // Поле поиска
        final EditText etSearch = new EditText(ctx);
        etSearch.setHint("🔍 Поиск игры, программы или установщика...");
        etSearch.setHintTextColor(0xFF64748B);
        etSearch.setTextColor(Color.WHITE);
        etSearch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        etSearch.setSingleLine(true);
        etSearch.setPadding(dp(ctx, 10), dp(ctx, 6), dp(ctx, 10), dp(ctx, 6));
        etSearch.setBackground(roundedBg(0xFF1E293B, 0xFF38BDF8, 1, 8, ctx));
        root.addView(etSearch);

        // ВКЛАДКИ ФИЛЬТРАЦИИ: Все | 🎮 Игра | 💼 Программа | ⚙️ Система
        final List<DiscoveredProgram> allPrograms = scanAllAvailableProgramsAndGames(container);
        int countAll = allPrograms.size();
        int countGames = 0, countProgs = 0, countSys = 0;
        for (DiscoveredProgram p : allPrograms) {
            if (CAT_GAME.equals(p.categoryCode)) countGames++;
            else if (CAT_PROGRAM.equals(p.categoryCode)) countProgs++;
            else countSys++;
        }

        HorizontalScrollView filterScroll = new HorizontalScrollView(ctx);
        filterScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout filterRow = new LinearLayout(ctx);
        filterRow.setOrientation(LinearLayout.HORIZONTAL);
        filterRow.setPadding(0, dp(ctx, 6), 0, dp(ctx, 4));

        final String[] activeFilter = {CAT_ALL};
        final Button btnTabAll = createStyledButton(ctx, "📋 Все (" + countAll + ")", 0xFF0284C7, 0xFF38BDF8);
        final Button btnTabGame = createStyledButton(ctx, "🎮 Игра (" + countGames + ")", 0xFF1E293B, 0xFF475569);
        final Button btnTabProg = createStyledButton(ctx, "💼 Программа (" + countProgs + ")", 0xFF1E293B, 0xFF475569);
        final Button btnTabSys = createStyledButton(ctx, "⚙️ Система (" + countSys + ")", 0xFF1E293B, 0xFF475569);

        LinearLayout.LayoutParams tabLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tabLp.rightMargin = dp(ctx, 6);
        filterRow.addView(btnTabAll, tabLp);
        filterRow.addView(btnTabGame, tabLp);
        filterRow.addView(btnTabProg, tabLp);
        filterRow.addView(btnTabSys, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        filterScroll.addView(filterRow);
        root.addView(filterScroll);

        ScrollView scroll = new ScrollView(ctx);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(ctx, 320)
        );
        scrollLp.topMargin = dp(ctx, 4);
        scroll.setLayoutParams(scrollLp);

        final LinearLayout listContainer = new LinearLayout(ctx);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(listContainer);
        root.addView(scroll);

        final Runnable updateTabStyles = () -> {
            btnTabAll.setBackground(roundedBg(CAT_ALL.equals(activeFilter[0]) ? 0xFF0284C7 : 0xFF1E293B, 0xFF38BDF8, 1, 8, ctx));
            btnTabGame.setBackground(roundedBg(CAT_GAME.equals(activeFilter[0]) ? 0xFF059669 : 0xFF1E293B, 0xFF34D399, 1, 8, ctx));
            btnTabProg.setBackground(roundedBg(CAT_PROGRAM.equals(activeFilter[0]) ? 0xFF7C3AED : 0xFF1E293B, 0xFFA78BFA, 1, 8, ctx));
            btnTabSys.setBackground(roundedBg(CAT_SYSTEM.equals(activeFilter[0]) ? 0xFFD97706 : 0xFF1E293B, 0xFFFBBF24, 1, 8, ctx));
        };

        final Runnable renderList = () -> {
            updateTabStyles.run();
            listContainer.removeAllViews();
            String q = etSearch.getText().toString().trim().toLowerCase(Locale.ROOT);
            int shown = 0;
            for (final DiscoveredProgram prog : allPrograms) {
                if (!CAT_ALL.equals(activeFilter[0]) && !activeFilter[0].equals(prog.categoryCode)) {
                    continue;
                }
                if (!q.isEmpty() && !prog.name.toLowerCase(Locale.ROOT).contains(q) &&
                    !prog.file.getName().toLowerCase(Locale.ROOT).contains(q)) {
                    continue;
                }
                shown++;
                LinearLayout card = new LinearLayout(ctx);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 10), dp(ctx, 8));
                card.setBackground(roundedBg(0xFF111827, prog.isOnDesktop ? 0xFF10B981 : 0xFF334155, 1, 8, ctx));
                LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                );
                cardLp.bottomMargin = dp(ctx, 6);
                card.setLayoutParams(cardLp);

                LinearLayout headerRow = new LinearLayout(ctx);
                headerRow.setOrientation(LinearLayout.HORIZONTAL);
                headerRow.setGravity(Gravity.CENTER_VERTICAL);

                ImageView iconView = new ImageView(ctx);
                if (prog.iconBitmap != null) {
                    iconView.setImageBitmap(prog.iconBitmap);
                } else {
                    iconView.setImageResource(R.drawable.container_file_window);
                }
                headerRow.addView(iconView, new LinearLayout.LayoutParams(dp(ctx, 38), dp(ctx, 38)) {{
                    rightMargin = dp(ctx, 10);
                }});

                LinearLayout infoCol = new LinearLayout(ctx);
                infoCol.setOrientation(LinearLayout.VERTICAL);
                TextView tvName = new TextView(ctx);
                tvName.setText(prog.name + " (" + prog.file.getName() + ")");
                tvName.setTextColor(Color.WHITE);
                tvName.setTypeface(Typeface.DEFAULT_BOLD);
                tvName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
                infoCol.addView(tvName);

                TextView tvMeta = new TextView(ctx);
                String deskBadge = prog.isOnDesktop ? " • ✅ На Рабочем столе" : "";
                tvMeta.setText(prog.categoryLabel + " • " + StringUtils.formatBytes(prog.sizeBytes) +
                    "\n📁 Папка: " + prog.workingDosDir + deskBadge);
                tvMeta.setTextColor(prog.isOnDesktop ? 0xFF34D399 : 0xFF94A3B8);
                tvMeta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
                infoCol.addView(tvMeta);

                headerRow.addView(infoCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                card.addView(headerRow);

                LinearLayout actRow = new LinearLayout(ctx);
                actRow.setOrientation(LinearLayout.HORIZONTAL);
                actRow.setPadding(0, dp(ctx, 6), 0, 0);

                Button btnRun = createStyledButton(ctx, "🚀 Запустить (с папкой)", 0xFF059669, 0xFF34D399);
                btnRun.setOnClickListener(v -> {
                    dialog.dismiss();
                    launchWithMemoryCheck(activity, container, prog.file.getAbsolutePath());
                });
                actRow.addView(btnRun, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.15f) {{
                    rightMargin = dp(ctx, 6);
                }});

                final Button btnPin = createStyledButton(
                    ctx,
                    prog.isOnDesktop ? "✅ Ярлык создан" : "📌 На Рабочий стол",
                    prog.isOnDesktop ? 0xFF1E293B : 0xFF0284C7,
                    0xFF38BDF8
                );
                btnPin.setOnClickListener(v -> {
                    boolean ok = pinExecutableToDesktopWithIcon(activity, container, prog.file);
                    if (ok) {
                        prog.isOnDesktop = true;
                        btnPin.setText("✅ Ярлык создан");
                        btnPin.setBackground(roundedBg(0xFF1E293B, 0xFF10B981, 1, 8, ctx));
                        AppUtils.showToast(activity, "✅ Ярлык с иконкой и рабочей папкой создан на Рабочем столе: " + prog.name);
                        if (onRefreshCallback != null) onRefreshCallback.run();
                    }
                });
                actRow.addView(btnPin, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                card.addView(actRow);
                listContainer.addView(card);
            }

            if (shown == 0) {
                TextView empty = new TextView(ctx);
                empty.setText("В выбранной категории нет файлов. Нажмите «📂 Выбрать .EXE/.MSI» или «📁 Открыть папку с игрой целиком» выше.");
                empty.setTextColor(0xFF94A3B8);
                empty.setPadding(dp(ctx, 8), dp(ctx, 16), dp(ctx, 8), dp(ctx, 16));
                listContainer.addView(empty);
            }
        };

        btnTabAll.setOnClickListener(v -> { activeFilter[0] = CAT_ALL; renderList.run(); });
        btnTabGame.setOnClickListener(v -> { activeFilter[0] = CAT_GAME; renderList.run(); });
        btnTabProg.setOnClickListener(v -> { activeFilter[0] = CAT_PROGRAM; renderList.run(); });
        btnTabSys.setOnClickListener(v -> { activeFilter[0] = CAT_SYSTEM; renderList.run(); });

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { renderList.run(); }
            @Override public void afterTextChanged(Editable s) {}
        });

        renderList.run();

        LinearLayout bottomRow = new LinearLayout(ctx);
        bottomRow.setOrientation(LinearLayout.HORIZONTAL);
        bottomRow.setPadding(0, dp(ctx, 6), 0, 0);

        Button btnPinAll = createStyledButton(ctx, "📌 Вынести Игры/Программы на Рабочий стол", 0xFF059669, 0xFF34D399);
        btnPinAll.setOnClickListener(v -> {
            dialog.dismiss();
            BG.execute(() -> {
                final int added = syncInstalledExeToDesktopWithIcons(activity, container);
                activity.runOnUiThread(() -> {
                    AppUtils.showToast(activity, "📌 Игры и программы (" + added + " шт.) вынесены с иконками на Рабочий стол!");
                    if (onRefreshCallback != null) onRefreshCallback.run();
                });
            });
        });
        bottomRow.addView(btnPinAll, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f) {{
            rightMargin = dp(ctx, 6);
        }});

        Button btnClose = createStyledButton(ctx, "Закрыть", 0xFF334155, 0xFF64748B);
        btnClose.setOnClickListener(v -> dialog.dismiss());
        bottomRow.addView(btnClose, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.6f));
        root.addView(bottomRow);

        dialog.show();
    }

    /**
     * ============================================================================
     * МОДУЛЬ 4: НАСТРОЙКИ ГРАФИКИ, FPS, СКОРОСТИ + КОМБИНАТОР ШЕЙДЕРОВ
     * ============================================================================
     */
    public static void showGraphicsFpsShaderStudioDialog(
        final Context context,
        final Container container,
        final XServerDisplayActivity liveActivityOrNull
    ) {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        ScrollView scroll = new ScrollView(context);
        scroll.setBackgroundColor(0xFF0B1120);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 14));
        scroll.addView(root);

        TextView title = new TextView(context);
        title.setText("🎨 Настройки Графики, FPS, Скорости + Комбинатор Шейдеров");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView sub = new TextView(context);
        sub.setText("Настройте рендерер (Vulkan / OpenGL / Hybrid DXVK), лимит FPS, турбо-скорость процессора и включите сразу несколько шейдеров одновременно!");
        sub.setTextColor(0xFF38BDF8);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        sub.setPadding(0, dp(context, 4), 0, dp(context, 10));
        root.addView(sub);

        TextView lblApi = new TextView(context);
        lblApi.setText("1. Графический API и Драйвер (Vulkan / OpenGL / DXVK):");
        lblApi.setTextColor(0xFFFACC15);
        lblApi.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(lblApi);

        final String[] apiLabels = {
            "🔥 Hybrid DXVK 1.7.2 + 2.4.1 (Vulkan Turnip/Vortek — Макс. FPS)",
            "⚡ DXVK 2.4.1 Async (Vulkan 1.3 для современных 3D игр)",
            "🛡️ DXVK 1.7.2 Legacy (Лёгкий Vulkan 1.1 для старых GPU)",
            "🌐 WineD3D OpenGL ES 3.2 (Высокая совместимость для старых игр)",
            "🖥️ VirGL + Zink OpenGL (Аппаратный OpenGL рендерер)"
        };
        final Spinner spApi = new Spinner(context);
        spApi.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, apiLabels));
        spApi.setSelection(prefs.getInt("pro_gfx_api_idx", 0));
        spApi.setBackground(roundedBg(0xFF1E293B, 0xFF38BDF8, 1, 8, context));
        root.addView(spApi);

        TextView lblRes = new TextView(context);
        lblRes.setText("\n2. Разрешение экрана и чёткость:");
        lblRes.setTextColor(0xFFFACC15);
        lblRes.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(lblRes);

        final String[] resValues = {"800x600", "1024x768", "1280x720", "1366x768", "1600x900", "1920x1080"};
        final Spinner spRes = new Spinner(context);
        spRes.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, resValues));
        int currentResIdx = 2;
        if (container != null) {
            for (int i = 0; i < resValues.length; i++) {
                if (resValues[i].equals(container.getScreenSize())) currentResIdx = i;
            }
        }
        spRes.setSelection(currentResIdx);
        spRes.setBackground(roundedBg(0xFF1E293B, 0xFF38BDF8, 1, 8, context));
        root.addView(spRes);

        final int[] fpsLimitVal = {prefs.getInt("pro_fps_limit", 120)};
        final TextView lblFps = new TextView(context);
        lblFps.setText("\n3. Лимит частоты кадров (FPS): " + (fpsLimitVal[0] >= 240 ? "240 FPS (Без лимита)" : fpsLimitVal[0] + " FPS"));
        lblFps.setTextColor(0xFF34D399);
        lblFps.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(lblFps);

        SeekBar sbFps = new SeekBar(context);
        sbFps.setMax(210);
        sbFps.setProgress(Math.max(0, fpsLimitVal[0] - 30));
        sbFps.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                fpsLimitVal[0] = 30 + progress;
                lblFps.setText("\n3. Лимит частоты кадров (FPS): " + (fpsLimitVal[0] >= 240 ? "240 FPS (Без лимита)" : fpsLimitVal[0] + " FPS"));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        root.addView(sbFps);

        TextView lblSpeed = new TextView(context);
        lblSpeed.setText("\n4. Скорость работы CPU / Box64 Dynarec и ESYNC:");
        lblSpeed.setTextColor(0xFFFACC15);
        lblSpeed.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(lblSpeed);

        final String[] speedModes = {
            "🚀 Ультра-Турбо Скорость (Box64 BigBlock=3, FastNan=1, StrongMem=0, ESYNC+FSYNC)",
            "⚖️ Сбалансированная Скорость (Стабильный FPS + Быстрая загрузка)",
            "🛡️ Максимальная Совместимость (Для чувствительных установщиков и античитов)"
        };
        final Spinner spSpeed = new Spinner(context);
        spSpeed.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, speedModes));
        spSpeed.setSelection(prefs.getInt("pro_speed_idx", 0));
        spSpeed.setBackground(roundedBg(0xFF1E293B, 0xFF38BDF8, 1, 8, context));
        root.addView(spSpeed);

        TextView lblShaders = new TextView(context);
        lblShaders.setText("\n5. Комбинатор Шейдеров (можно включить сразу несколько!):");
        lblShaders.setTextColor(0xFFA78BFA);
        lblShaders.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(lblShaders);

        final CheckBox cbCas = new CheckBox(context);
        cbCas.setText("✨ AMD FidelityFX CAS Sharpening (Ультра-чёткость текстур)");
        cbCas.setTextColor(Color.WHITE);
        cbCas.setChecked(prefs.getBoolean("pro_shader_cas", false));
        root.addView(cbCas);

        final int[] casStrength = {prefs.getInt("pro_shader_cas_val", 70)};
        final TextView tvCasVal = new TextView(context);
        tvCasVal.setText("   Интенсивность резкости CAS: " + casStrength[0] + "%");
        tvCasVal.setTextColor(0xFF94A3B8);
        root.addView(tvCasVal);

        SeekBar sbCas = new SeekBar(context);
        sbCas.setMax(100);
        sbCas.setProgress(casStrength[0]);
        sbCas.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int p, boolean b) {
                casStrength[0] = p;
                tvCasVal.setText("   Интенсивность резкости CAS: " + p + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        root.addView(sbCas);

        final CheckBox cbHdr = new CheckBox(context);
        cbHdr.setText("🌟 HDR10 Bloom & Vibrance (Сочные цвета и свечение)");
        cbHdr.setTextColor(Color.WHITE);
        cbHdr.setChecked(prefs.getBoolean("pro_shader_hdr", false));
        root.addView(cbHdr);

        final int[] hdrStrength = {prefs.getInt("pro_shader_hdr_val", 55)};
        final TextView tvHdrVal = new TextView(context);
        tvHdrVal.setText("   Сила HDR свечения и насыщенности: " + hdrStrength[0] + "%");
        tvHdrVal.setTextColor(0xFF94A3B8);
        root.addView(tvHdrVal);

        SeekBar sbHdr = new SeekBar(context);
        sbHdr.setMax(100);
        sbHdr.setProgress(hdrStrength[0]);
        sbHdr.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int p, boolean b) {
                hdrStrength[0] = p;
                tvHdrVal.setText("   Сила HDR свечения и насыщенности: " + p + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        root.addView(sbHdr);

        final CheckBox cbFxaa = new CheckBox(context);
        cbFxaa.setText("📐 FXAA Subpixel Anti-Aliasing (Сглаживание лесенок на краях)");
        cbFxaa.setTextColor(Color.WHITE);
        cbFxaa.setChecked(prefs.getBoolean("pro_shader_fxaa", false));
        root.addView(cbFxaa);

        final CheckBox cbColor = new CheckBox(context);
        cbColor.setText("🎨 Dynamic Contrast & Color Boost (Глубокий контраст и гамма)");
        cbColor.setTextColor(Color.WHITE);
        cbColor.setChecked(prefs.getBoolean("pro_shader_color", false));
        root.addView(cbColor);

        final CheckBox cbCrt = new CheckBox(context);
        cbCrt.setText("📺 CRT Retro Scanlines (Эффект классического монитора)");
        cbCrt.setTextColor(Color.WHITE);
        cbCrt.setChecked(prefs.getBoolean("pro_shader_crt", false));
        root.addView(cbCrt);

        final CheckBox cbNight = new CheckBox(context);
        cbNight.setText("🌙 Night Vision / Shadow Boost (Осветление тёмных сцен в играх)");
        cbNight.setTextColor(Color.WHITE);
        cbNight.setChecked(prefs.getBoolean("pro_shader_night", false));
        root.addView(cbNight);

        final AlertDialog dialog = new AlertDialog.Builder(context)
            .setView(scroll)
            .create();

        LinearLayout btnRow = new LinearLayout(context);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, dp(context, 12), 0, 0);

        Button btnBench = createStyledButton(context, "📊 Проверить FPS", 0xFFD97706, 0xFFFBBF24);
        btnBench.setOnClickListener(v -> showFpsBenchmarkDialog(context, container));
        btnRow.addView(btnBench, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) {{
            rightMargin = dp(context, 6);
        }});

        Button btnSave = createStyledButton(context, "✅ Сохранить и Применить", 0xFF059669, 0xFF34D399);
        btnSave.setOnClickListener(v -> {
            int apiIdx = spApi.getSelectedItemPosition();
            int speedIdx = spSpeed.getSelectedItemPosition();
            String chosenRes = resValues[spRes.getSelectedItemPosition()];

            prefs.edit()
                .putInt("pro_gfx_api_idx", apiIdx)
                .putInt("pro_speed_idx", speedIdx)
                .putInt("pro_fps_limit", fpsLimitVal[0])
                .putBoolean("pro_shader_cas", cbCas.isChecked())
                .putInt("pro_shader_cas_val", casStrength[0])
                .putBoolean("pro_shader_hdr", cbHdr.isChecked())
                .putInt("pro_shader_hdr_val", hdrStrength[0])
                .putBoolean("pro_shader_fxaa", cbFxaa.isChecked())
                .putBoolean("pro_shader_color", cbColor.isChecked())
                .putBoolean("pro_shader_crt", cbCrt.isChecked())
                .putBoolean("pro_shader_night", cbNight.isChecked())
                .apply();

            if (container != null) {
                container.setScreenSize(chosenRes);
                applySelectedGraphicsWrapperImmediately(context, container, apiIdx, fpsLimitVal[0]);

                if (speedIdx == 0) {
                    container.setBox64Preset("PERFORMANCE");
                    container.setEnvVars("ZINK_DESCRIPTORS=lazy MESA_SHADER_CACHE_DISABLE=false MESA_SHADER_CACHE_MAX_SIZE=1024MB mesa_glthread=true WINEESYNC=1 WINEFSYNC=1 DXVK_ASYNC=1 BOX64_DYNAREC_BIGBLOCK=3 BOX64_DYNAREC_FASTNAN=1 BOX64_DYNAREC_STRONGMEM=0");
                } else if (speedIdx == 1) {
                    container.setBox64Preset("INTERMEDIATE");
                    container.setEnvVars("ZINK_DESCRIPTORS=lazy MESA_SHADER_CACHE_DISABLE=false WINEESYNC=1 DXVK_ASYNC=1");
                } else {
                    container.setBox64Preset("COMPATIBILITY");
                    container.setEnvVars("WINEESYNC=1 BOX64_DYNAREC_STRONGMEM=1");
                }
                container.saveData();
            }

            if (liveActivityOrNull != null) {
                applySavedShadersToRenderer(liveActivityOrNull);
            }

            AppUtils.showToast(context, "✅ Графика, лимит FPS, скорость и шейдеры применены!");
            dialog.dismiss();
        });
        btnRow.addView(btnSave, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f));

        root.addView(btnRow);
        dialog.show();
    }

    /**
     * Мгновенно применяет выбранный графический враппер (DXVK Hybrid / 2.4.1 / 1.7.2 или WineD3D OpenGL)
     * с распаковкой соответствующих DLL в system32/syswow64 и синхронизацией ярлыков.
     */
    public static void applySelectedGraphicsWrapperImmediately(Context context, Container container, int apiIdx, int fpsLimit) {
        if (container == null) return;
        try {
            boolean turnipSupported = com.winlator.core.GPUHelper.isTurnipSupported(context);
            String vkDriver = turnipSupported ? com.winlator.container.GraphicsDrivers.TURNIP : com.winlator.container.GraphicsDrivers.VORTEK;
            int fr = fpsLimit >= 240 ? 0 : fpsLimit;

            KeyValueSet[] cfgs = com.winlator.container.DXWrappers.parseConfigs(container.getDXWrapper(), container.getDXWrapperConfig());
            File rootDir = RootFS.find(context).getRootDir();
            File windowsDir = new File(rootDir, RootFS.WINEPREFIX + "/drive_c/windows");
            File wineDir = new File(rootDir, RootFS.find(context).getWinePath());
            File wineSystem32Dir = new File(wineDir, "/lib/wine/x86_64-windows");
            File wineSysWoW64Dir = new File(wineDir, "/lib/wine/i386-windows");
            File containerSystem32Dir = new File(windowsDir, "system32");
            File containerSysWoW64Dir = new File(windowsDir, "syswow64");

            if (apiIdx == 3 || apiIdx == 4) {
                container.setDXWrapper(com.winlator.container.DXWrappers.WINED3D);
                String oglDriver = (apiIdx == 4) ? com.winlator.container.GraphicsDrivers.ZINK : com.winlator.container.GraphicsDrivers.VIRGL;
                container.setGraphicsDriver(vkDriver + "," + oglDriver);
                cfgs[0].put("version", com.winlator.core.WineInfo.MAIN_WINE_VERSION);
                container.setDXWrapperConfig(cfgs[0].toString() + "|" + cfgs[1].toString());
                container.putExtra("dxwrapper", "");

                final String[] dlls = {"d3d8.dll", "d3d9.dll", "d3d10.dll", "d3d10_1.dll", "d3d10core.dll", "d3d11.dll", "d3d12.dll", "d3d12core.dll", "dxgi.dll", "ddraw.dll", "wined3d.dll"};
                for (String dll : dlls) {
                    File src32 = new File(wineSysWoW64Dir, dll);
                    File src64 = new File(wineSystem32Dir, dll);
                    if (src32.isFile()) com.winlator.core.FileUtils.copy(src32, new File(containerSysWoW64Dir, dll));
                    if (src64.isFile()) com.winlator.core.FileUtils.copy(src64, new File(containerSystem32Dir, dll));
                }
            } else {
                container.setDXWrapper(com.winlator.container.DXWrappers.DXVK);
                String ver = (apiIdx == 1) ? "2.4.1" : (apiIdx == 2 ? "1.7.2" : com.winlator.core.DefaultVersion.HYBRID_DXVK);
                if (apiIdx == 2) {
                    container.setGraphicsDriver(com.winlator.container.GraphicsDrivers.VORTEK + "," + com.winlator.container.GraphicsDrivers.VIRGL);
                } else {
                    container.setGraphicsDriver(vkDriver + "," + com.winlator.container.GraphicsDrivers.VIRGL);
                }
                cfgs[0].put("version", ver);
                cfgs[0].put("framerate", String.valueOf(fr));
                cfgs[0].put("async", "1");
                cfgs[0].put("asyncCache", "1");
                container.setDXWrapperConfig(cfgs[0].toString() + "|" + cfgs[1].toString());
                container.putExtra("dxwrapper", "");

                com.winlator.core.TarCompressorUtils.extract(
                    com.winlator.core.TarCompressorUtils.Type.ZSTD,
                    context,
                    "dxwrapper/dxvk-" + ver + ".tzst",
                    windowsDir
                );
                com.winlator.core.TarCompressorUtils.extract(
                    com.winlator.core.TarCompressorUtils.Type.ZSTD,
                    context,
                    "dxwrapper/d8vk-" + com.winlator.core.DefaultVersion.D8VK + ".tzst",
                    windowsDir
                );
            }

            File desktopDir = new File(container.getUserDir(), "Desktop");
            if (desktopDir != null && desktopDir.isDirectory()) {
                File[] files = desktopDir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        if (f.isFile() && f.getName().endsWith(".desktop")) {
                            try {
                                com.winlator.container.Shortcut sc = new com.winlator.container.Shortcut(container, f);
                                sc.putExtra("dxwrapper", null);
                                sc.putExtra("dxwrapperConfig", null);
                                sc.putExtra("graphicsDriver", null);
                                sc.putExtra("graphicsDriverConfig", null);
                                sc.saveData();
                            } catch (Throwable ignored) {}
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Потокобезопасно применяет выбранные шейдеры в GLThread через queueEvent.
     */
    public static void applySavedShadersToRenderer(final XServerDisplayActivity activity) {
        if (activity == null || activity.getXServerView() == null) return;
        final GLRenderer renderer = activity.getXServerView().getRenderer();
        if (renderer == null || renderer.effectComposer == null) return;

        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        activity.getXServerView().queueEvent(() -> {
            try {
                CASSharpenEffect cas = renderer.effectComposer.getEffect(CASSharpenEffect.class);
                if (prefs.getBoolean("pro_shader_cas", false)) {
                    if (cas == null) cas = new CASSharpenEffect();
                    cas.setSharpness(prefs.getInt("pro_shader_cas_val", 70) / 100.0f);
                    renderer.effectComposer.addEffect(cas);
                } else if (cas != null) {
                    renderer.effectComposer.removeEffect(cas);
                }

                HDRBloomEffect hdr = renderer.effectComposer.getEffect(HDRBloomEffect.class);
                if (prefs.getBoolean("pro_shader_hdr", false)) {
                    if (hdr == null) hdr = new HDRBloomEffect();
                    hdr.setIntensity(prefs.getInt("pro_shader_hdr_val", 55) / 100.0f);
                    renderer.effectComposer.addEffect(hdr);
                } else if (hdr != null) {
                    renderer.effectComposer.removeEffect(hdr);
                }

                FXAAEffect fxaa = renderer.effectComposer.getEffect(FXAAEffect.class);
                if (prefs.getBoolean("pro_shader_fxaa", false)) {
                    if (fxaa == null) renderer.effectComposer.addEffect(new FXAAEffect());
                } else if (fxaa != null) {
                    renderer.effectComposer.removeEffect(fxaa);
                }

                ColorEffect color = renderer.effectComposer.getEffect(ColorEffect.class);
                if (prefs.getBoolean("pro_shader_color", false)) {
                    if (color == null) color = new ColorEffect();
                    color.setBrightness(0.06f);
                    color.setContrast(0.18f);
                    color.setGamma(1.05f);
                    renderer.effectComposer.addEffect(color);
                } else if (color != null) {
                    renderer.effectComposer.removeEffect(color);
                }

                CRTEffect crt = renderer.effectComposer.getEffect(CRTEffect.class);
                if (prefs.getBoolean("pro_shader_crt", false)) {
                    if (crt == null) renderer.effectComposer.addEffect(new CRTEffect());
                } else if (crt != null) {
                    renderer.effectComposer.removeEffect(crt);
                }

                NightVisionEffect night = renderer.effectComposer.getEffect(NightVisionEffect.class);
                if (prefs.getBoolean("pro_shader_night", false)) {
                    if (night == null) renderer.effectComposer.addEffect(new NightVisionEffect());
                } else if (night != null) {
                    renderer.effectComposer.removeEffect(night);
                }
            } catch (Throwable ignored) {}
        });
    }

    /**
     * ============================================================================
     * МОДУЛЬ 6: ПРОВЕРКА И ТЕСТ FPS С РАЗЛИЧНЫМИ НАСТРОЙКАМИ VULKAN / OPENGL / DXVK
     * ============================================================================
     */
    public static void showFpsBenchmarkDialog(final Context context, final Container containerOrNull) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10));
        root.setBackgroundColor(0xFF090D16);

        TextView title = new TextView(context);
        title.setText("📊 Проверка FPS: Vulkan vs OpenGL vs Hybrid DXVK + Шейдеры");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        final TextView tvLiveStats = new TextView(context);
        tvLiveStats.setText("⏳ Инициализация графического пайплайна...");
        tvLiveStats.setTextColor(0xFF34D399);
        tvLiveStats.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        tvLiveStats.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tvLiveStats.setPadding(dp(context, 8), dp(context, 6), dp(context, 8), dp(context, 6));
        tvLiveStats.setBackground(roundedBg(0xFF111827, 0xFF10B981, 1, 8, context));
        root.addView(tvLiveStats);

        final String[] benchApis = {
            "🔥 Режим 1: Vulkan 1.3 + Hybrid DXVK (1.7.2 + 2.4.1 Async)",
            "⚡ Режим 2: Vulkan Turnip / Vortek + VKD3D (DirectX 12)",
            "🌐 Режим 3: OpenGL ES 3.2 + WineD3D (Классический)",
            "🖥️ Режим 4: OpenGL VirGL / Zink (Многопоточный)",
            "✨ Режим 5: Ультра-стресс (Vulkan + CAS + HDR + FXAA + 4000 частиц)"
        };
        final Spinner spBenchMode = new Spinner(context);
        spBenchMode.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, benchApis));
        spBenchMode.setBackground(roundedBg(0xFF1E293B, 0xFF38BDF8, 1, 8, context));
        LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        spLp.topMargin = dp(context, 6);
        spLp.bottomMargin = dp(context, 6);
        spBenchMode.setLayoutParams(spLp);
        root.addView(spBenchMode);

        final int[] activeMode = {0};
        final float[] currentFps = {60f};
        final float[] avgFps = {60f};
        final float[] minFps = {999f};
        final float[] maxFps = {0f};

        final View stressView = new View(context) {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            private long lastFrameNs = System.nanoTime();
            private int framesCount = 0;
            private float accumFps = 0f;
            private float phase = 0f;

            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                long now = System.nanoTime();
                float dtMs = Math.max(1.0f, (now - lastFrameNs) / 1_000_000.0f);
                lastFrameNs = now;

                float instFps = 1000.0f / dtMs;
                int mode = activeMode[0];
                int objectsCount = (mode == 4) ? 550 : (mode == 2 ? 320 : 220);

                int w = getWidth();
                int h = getHeight();
                canvas.drawColor(0xFF060B14);

                phase += 0.06f;
                for (int i = 0; i < objectsCount; i++) {
                    float angle = phase + i * 0.17f;
                    float r = ((i % 25) / 25.0f) * (Math.min(w, h) * 0.44f);
                    float cx = w * 0.5f + (float) Math.cos(angle) * r * 1.4f;
                    float cy = h * 0.5f + (float) Math.sin(angle * 1.3f) * r;
                    p.setColor(Color.HSVToColor(190, new float[]{(i * 7 + phase * 30) % 360f, 0.8f, 0.95f}));
                    canvas.drawCircle(cx, cy, (i % 5) + 3, p);
                }

                framesCount++;
                accumFps += instFps;
                currentFps[0] = instFps;
                avgFps[0] = accumFps / framesCount;
                if (framesCount > 5) {
                    if (instFps < minFps[0]) minFps[0] = instFps;
                    if (instFps > maxFps[0]) maxFps[0] = instFps;
                }

                p.setColor(Color.WHITE);
                p.setTextSize(dp(context, 12));
                p.setTypeface(Typeface.MONOSPACE);
                canvas.drawText(
                    String.format(Locale.US, "FPS: %.1f | AVG: %.1f | MIN: %.1f | MAX: %.1f",
                        currentFps[0], avgFps[0], minFps[0] > 500 ? currentFps[0] : minFps[0], maxFps[0]),
                    dp(context, 8),
                    dp(context, 20),
                    p
                );

                postInvalidateOnAnimation();
            }
        };

        LinearLayout.LayoutParams canvasLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(context, 175)
        );
        stressView.setLayoutParams(canvasLp);
        root.addView(stressView);

        spBenchMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                activeMode[0] = position;
                minFps[0] = 999f;
                maxFps[0] = 0f;
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        final Handler handler = new Handler(Looper.getMainLooper());
        final boolean[] alive = {true};
        final Runnable updater = new Runnable() {
            @Override
            public void run() {
                if (!alive[0]) return;
                MemoryStatus m = queryMemoryStatus(context);
                String apiTag = benchApis[activeMode[0]];
                tvLiveStats.setText(String.format(Locale.US,
                    "%s\n⚡ FPS: %.1f | Средний: %.1f | Мин: %.1f | Пик: %.1f\n💾 Память: %s | 🧠 ОЗУ: %s",
                    apiTag,
                    currentFps[0],
                    avgFps[0],
                    minFps[0] > 500 ? currentFps[0] : minFps[0],
                    maxFps[0],
                    m.formatStorageGb(),
                    m.formatRamMb()
                ));
                handler.postDelayed(this, 450);
            }
        };
        handler.postDelayed(updater, 300);

        final AlertDialog dialog = new AlertDialog.Builder(context)
            .setView(root)
            .setOnDismissListener(d -> alive[0] = false)
            .create();

        LinearLayout btnRow = new LinearLayout(context);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, dp(context, 8), 0, 0);

        Button btnApplyBest = createStyledButton(context, "✅ Применить этот режим", 0xFF059669, 0xFF34D399);
        btnApplyBest.setOnClickListener(v -> {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            int mode = activeMode[0];
            int mappedApiIdx = 0;
            if (mode == 2) mappedApiIdx = 3; // WineD3D OpenGL ES
            else if (mode == 3) mappedApiIdx = 4; // Zink + VirGL OpenGL
            else mappedApiIdx = 0; // Hybrid DXVK Vulkan

            SharedPreferences.Editor ed = prefs.edit().putInt("pro_gfx_api_idx", mappedApiIdx);
            if (mode == 4) {
                ed.putBoolean("pro_shader_cas", true)
                  .putBoolean("pro_shader_hdr", true)
                  .putBoolean("pro_shader_fxaa", true);
            }
            ed.apply();

            Container targetC = containerOrNull != null ? containerOrNull : resolveTargetContainer(context, null);
            if (targetC != null) {
                applySelectedGraphicsWrapperImmediately(context, targetC, mappedApiIdx, prefs.getInt("pro_fps_limit", 120));
                if (mode == 1) {
                    try {
                        File winDir = new File(RootFS.find(context).getRootDir(), RootFS.WINEPREFIX + "/drive_c/windows");
                        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, "dxwrapper/vkd3d-2.14.1.tzst", winDir);
                    } catch (Throwable ignored) {}
                }
                targetC.saveData();
            }
            if (context instanceof XServerDisplayActivity) {
                applySavedShadersToRenderer((XServerDisplayActivity) context);
            }
            AppUtils.showToast(context, "✅ Выбранный режим графики и шейдеров применён!");
            alive[0] = false;
            dialog.dismiss();
        });
        btnRow.addView(btnApplyBest, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f) {{
            rightMargin = dp(context, 6);
        }});

        if (context instanceof XServerDisplayActivity) {
            final XServerDisplayActivity xAct = (XServerDisplayActivity) context;
            Button btnRun3d = createStyledButton(context, "🎮 3D Тест DX", 0xFF0284C7, 0xFF38BDF8);
            btnRun3d.setOnClickListener(v -> {
                if (xAct.getWinHandler() != null) {
                    xAct.getWinHandler().exec("C:\\windows\\system32\\testd3d.exe", "");
                    AppUtils.showToast(context, "🚀 Запущен аппаратный 3D-тест DirectX (testd3d.exe)");
                }
                alive[0] = false;
                dialog.dismiss();
            });
            btnRow.addView(btnRun3d, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f) {{
                rightMargin = dp(context, 6);
            }});
        }

        Button btnClose = createStyledButton(context, "Закрыть", 0xFF334155, 0xFF64748B);
        btnClose.setOnClickListener(v -> {
            alive[0] = false;
            dialog.dismiss();
        });
        btnRow.addView(btnClose, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.65f));

        root.addView(btnRow);
        dialog.show();
    }

    /**
     * ============================================================================
     * МОДУЛЬ 3 и 5: ПЕРЕТАСКИВАЕМЫЙ ПАЛЬЦЕМ, КОМПАКТНЫЙ И ПОЛНОСТЬЮ СКРЫВАЕМЫЙ
     * HUD И ОВЕРЛЕЙ ВНУТРИ РАБОЧЕГО СТОЛА WINDOWS (НЕ ЗАКРЫВАЕТ ПОЛЭКРАНА И ЯРЛЫКИ!)
     * ============================================================================
     */
    public static void attachLiveDesktopHudAndOverlayCustomizer(
        final XServerDisplayActivity activity,
        final FrameLayout rootView,
        final InputControlsView inputControlsView,
        final TouchpadView touchpadView,
        final Container container,
        final String launchedExecPathOrNull
    ) {
        ensureRichControlPresetsInstalled(activity);
        applySavedShadersToRenderer(activity);

        // 1. HUD v12.6: одна спокойная полупрозрачная капсула сверху по центру — не перекрывает R2/L2 и
        //    интерфейс игры, без разноцветных эмодзи. Через 4 с без касаний тускнеет; позиция запоминается.
        final SharedPreferences hudPrefs = PreferenceManager.getDefaultSharedPreferences(activity);
        final boolean desktopSession = launchedExecPathOrNull == null || launchedExecPathOrNull.trim().isEmpty();
        final String exeShortName = desktopSession ? "Рабочий стол" : new File(launchedExecPathOrNull.replace('\\', '/')).getName();

        final LinearLayout topHudBar = new LinearLayout(activity);
        topHudBar.setOrientation(LinearLayout.VERTICAL);
        topHudBar.setPadding(dp(activity, 4), dp(activity, 3), dp(activity, 4), dp(activity, 3));
        topHudBar.setBackground(roundedBg(UI_PANEL_BG, UI_PANEL_STROKE, 1, 17, activity));
        FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP | Gravity.START
        );
        topLp.topMargin = dp(activity, 2);
        topHudBar.setLayoutParams(topLp);
        topHudBar.setVisibility(View.INVISIBLE);

        final LinearLayout statusLine = new LinearLayout(activity);
        statusLine.setOrientation(LinearLayout.HORIZONTAL);
        statusLine.setGravity(Gravity.CENTER_VERTICAL);
        topHudBar.addView(statusLine);

        final TextView tvWatchdog = new TextView(activity);
        tvWatchdog.setText("⠿  " + exeShortName);
        tvWatchdog.setTextColor(UI_TEXT_DIM);
        tvWatchdog.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        tvWatchdog.setSingleLine(true);
        tvWatchdog.setEllipsize(TextUtils.TruncateAt.END);
        tvWatchdog.setMaxWidth(dp(activity, 210));
        tvWatchdog.setPadding(dp(activity, 8), dp(activity, 4), dp(activity, 6), dp(activity, 4));
        statusLine.addView(tvWatchdog);

        // v12.6.1: живой FPS и время кадра прямо в HUD (обновляется каждую секунду)
        final TextView tvFps = new TextView(activity);
        tvFps.setTextColor(UI_ACCENT);
        tvFps.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        tvFps.setTypeface(Typeface.DEFAULT_BOLD);
        tvFps.setSingleLine(true);
        tvFps.setPadding(dp(activity, 2), dp(activity, 4), dp(activity, 6), dp(activity, 4));
        tvFps.setVisibility(View.GONE);
        statusLine.addView(tvFps, new LinearLayout.LayoutParams(dp(activity, 104), LinearLayout.LayoutParams.WRAP_CONTENT));
        final Handler fpsHandler = new Handler(Looper.getMainLooper());
        fpsHandler.post(new Runnable() {
            @Override
            public void run() {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                FrameRating fr = activity.getFrameRating();
                float fps = fr != null ? fr.getLastFPS() : 0f;
                fpsGovernorTick(activity, fps);
                if (fps >= 0.5f) {
                    String t = String.format(Locale.US, "%.0f FPS · %.0f мс", fps, 1000f / fps);
                    if (!t.contentEquals(tvFps.getText())) tvFps.setText(t);
                    if (tvFps.getVisibility() != View.VISIBLE) tvFps.setVisibility(View.VISIBLE);
                } else if (tvFps.getVisibility() != View.GONE) {
                    tvFps.setVisibility(View.GONE);
                }
                fpsHandler.postDelayed(this, 1000);
            }
        });

        final Button btnQuickGamepad = hudButton(activity, "Геймпад");
        final Button btnCenterCursor = hudButton(activity, "Мышь");
        final Button btnCollapse = hudButton(activity, "☰");
        final Button btnHideAll = hudButton(activity, "✕");
        for (Button hb : new Button[]{btnQuickGamepad, btnCenterCursor, btnCollapse, btnHideAll}) {
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 28));
            blp.leftMargin = dp(activity, 3);
            statusLine.addView(hb, blp);
        }

        final HorizontalScrollView actionsScroll = new HorizontalScrollView(activity);
        actionsScroll.setHorizontalScrollBarEnabled(false);
        actionsScroll.setVisibility(View.GONE);
        final LinearLayout actionsRow = new LinearLayout(activity);
        actionsRow.setOrientation(LinearLayout.HORIZONTAL);
        actionsRow.setPadding(dp(activity, 2), dp(activity, 4), dp(activity, 2), dp(activity, 1));
        actionsScroll.addView(actionsRow);
        topHudBar.addView(actionsScroll);

        final LinearLayout floatingTouchpadPanel = new LinearLayout(activity);
        final LinearLayout overlayEditorBar = new LinearLayout(activity);

        // Маленькая кнопка «☰» вместо HUD, когда его полностью скрыли (её тоже можно перетащить)
        final Button edgeRestoreTab = hudButton(activity, "☰");
        edgeRestoreTab.setAlpha(0.55f);
        edgeRestoreTab.setVisibility(View.GONE);
        FrameLayout.LayoutParams edgeLp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            dp(activity, 28),
            Gravity.TOP | Gravity.CENTER_HORIZONTAL
        );
        edgeLp.topMargin = dp(activity, 2);
        edgeRestoreTab.setLayoutParams(edgeLp);
        makeViewDraggable(edgeRestoreTab, edgeRestoreTab, rootView);

        // Активные режимы подсвечиваются одним спокойным акцентом
        final Runnable refreshHudStates = () -> {
            setHudActive(activity, btnQuickGamepad, isGamepadOverlayVisible(inputControlsView));
            setHudActive(activity, btnCenterCursor, floatingTouchpadPanel.getVisibility() == View.VISIBLE);
            setHudActive(activity, btnCollapse, actionsScroll.getVisibility() == View.VISIBLE);
        };

        // Автозатухание: через 4 с без касаний HUD становится полупрозрачным
        final Handler hudDimHandler = new Handler(Looper.getMainLooper());
        final Runnable[] hudDim = new Runnable[1];
        hudDim[0] = () -> {
            if (actionsScroll.getVisibility() == View.VISIBLE) {
                hudDimHandler.postDelayed(hudDim[0], 4000);
                return;
            }
            topHudBar.animate().alpha(0.35f).setDuration(350).start();
        };
        final Runnable wakeHud = () -> {
            topHudBar.animate().cancel();
            topHudBar.setAlpha(1f);
            refreshHudStates.run();
            hudDimHandler.removeCallbacks(hudDim[0]);
            hudDimHandler.postDelayed(hudDim[0], 4000);
        };

        btnQuickGamepad.setOnClickListener(v -> {
            if (isGamepadOverlayVisible(inputControlsView)) {
                hideGamepadOverlay(activity, inputControlsView);
            } else {
                // Геймпад и тачпад-мышь не смешиваем: игры Unity иначе прыгают «Клавиатура ⇄ Контроллер»
                floatingTouchpadPanel.setVisibility(View.GONE);
                showGamepadOverlay(activity, inputControlsView, touchpadView);
            }
            wakeHud.run();
        });

        btnCenterCursor.setOnClickListener(v -> {
            try {
                XServer xs = activity.getXServer();
                if (xs != null && xs.getRenderer() != null) {
                    xs.getRenderer().setCursorVisible(true);
                    xs.getRenderer().setCursorScale(1.45f);
                }
                touchpadView.setEnabled(true);
                // Курсор двигается относительно пальца и не прыгает в точку касания посреди экрана
                touchpadView.setMoveCursorToTouchpoint(false);
                if (floatingTouchpadPanel.getVisibility() == View.VISIBLE) {
                    floatingTouchpadPanel.setVisibility(View.GONE);
                } else {
                    if (isGamepadOverlayVisible(inputControlsView)) hideGamepadOverlay(activity, inputControlsView);
                    floatingTouchpadPanel.setVisibility(View.VISIBLE);
                }
            } catch (Throwable ignored) {}
            wakeHud.run();
        });

        btnCollapse.setOnClickListener(v -> {
            actionsScroll.setVisibility(actionsScroll.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            wakeHud.run();
        });

        btnHideAll.setOnClickListener(v -> {
            actionsScroll.setVisibility(View.GONE);
            topHudBar.setVisibility(View.GONE);
            edgeRestoreTab.setVisibility(View.VISIBLE);
        });

        edgeRestoreTab.setOnClickListener(v -> {
            edgeRestoreTab.setVisibility(View.GONE);
            topHudBar.setVisibility(View.VISIBLE);
            wakeHud.run();
        });

        // Меню (☰): Лаунчер · Графика · Оверлей · FPS · Задачи — одинаковые нейтральные кнопки
        final Button btnDesktopLauncher = hudButton(activity, "Лаунчер");
        btnDesktopLauncher.setOnClickListener(v -> {
            wakeHud.run();
            showInSessionExePicker(activity, container, inputControlsView, touchpadView);
        });
        final Button btnGfxStudio = hudButton(activity, "Графика");
        btnGfxStudio.setOnClickListener(v -> {
            wakeHud.run();
            showGraphicsFpsShaderStudioDialog(activity, container, activity);
        });
        final Button btnControls = hudButton(activity, "Оверлей");
        btnControls.setOnClickListener(v -> {
            if (overlayEditorBar.getVisibility() == View.VISIBLE) {
                overlayEditorBar.setVisibility(View.GONE);
                inputControlsView.setEditMode(false);
                inputControlsView.invalidate();
            } else {
                overlayEditorBar.setVisibility(View.VISIBLE);
                actionsScroll.setVisibility(View.GONE);
            }
            wakeHud.run();
        });
        final Button btnFpsTest = hudButton(activity, "FPS");
        btnFpsTest.setOnClickListener(v -> {
            wakeHud.run();
            showPerformancePanel(activity, container);
        });
        btnFpsTest.setOnLongClickListener(v -> {
            wakeHud.run();
            showFpsBenchmarkDialog(activity, container);
            return true;
        });
        final Button btnStorage = hudButton(activity, "Место");
        btnStorage.setOnClickListener(v -> {
            wakeHud.run();
            showStorageDialog(activity, container);
        });
        // v12.6.2: режим Unity для этой игры (Скорость / Баланс / Стабильность), применяется при следующем запуске
        final Button btnUnity = hudButton(activity, "Unity");
        btnUnity.setVisibility(View.GONE);
        btnUnity.setOnClickListener(v -> {
            wakeHud.run();
            cycleUnityLevel(activity, btnUnity);
        });
        final Handler unityBtnHandler = new Handler(Looper.getMainLooper());
        unityBtnHandler.postDelayed(new Runnable() {
            int tries = 0;

            @Override
            public void run() {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                File exe = activity.getLaunchTargetExe();
                if (activity.unityLevel >= 0 && exe != null) {
                    btnUnity.setText("Unity: " + unityLevelName(getUnityLevel(activity, exe)));
                    btnUnity.setVisibility(View.VISIBLE);
                    return;
                }
                if (++tries < 40) unityBtnHandler.postDelayed(this, 3000);
            }
        }, 3000);
        final Button btnTasks = hudButton(activity, "Задачи");
        btnTasks.setOnClickListener(v -> {
            wakeHud.run();
            (new TaskManagerDialog(activity)).show();
        });
        for (Button ab : new Button[]{btnDesktopLauncher, btnGfxStudio, btnControls, btnFpsTest, btnUnity, btnStorage, btnTasks}) {
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 28));
            alp.rightMargin = dp(activity, 4);
            actionsRow.addView(ab, alp);
        }

        // Перетаскивание HUD за название (позиция сохраняется); короткое касание по названию = меню
        tvWatchdog.setOnTouchListener(new View.OnTouchListener() {
            private float offX, offY, downX, downY;
            private boolean dragging;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) topHudBar.getLayoutParams();
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        wakeHud.run();
                        offX = lp.leftMargin - e.getRawX();
                        offY = lp.topMargin - e.getRawY();
                        downX = e.getRawX();
                        downY = e.getRawY();
                        dragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (!dragging && (Math.abs(e.getRawX() - downX) > dp(activity, 6) || Math.abs(e.getRawY() - downY) > dp(activity, 6))) {
                            dragging = true;
                        }
                        if (dragging) {
                            int maxX = Math.max(0, rootView.getWidth() - topHudBar.getWidth());
                            int maxY = Math.max(0, rootView.getHeight() - topHudBar.getHeight());
                            lp.gravity = Gravity.TOP | Gravity.START;
                            lp.leftMargin = Math.max(0, Math.min(maxX, Math.round(e.getRawX() + offX)));
                            lp.topMargin = Math.max(0, Math.min(maxY, Math.round(e.getRawY() + offY)));
                            topHudBar.setLayoutParams(lp);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (dragging) {
                            if (rootView.getWidth() > 0 && rootView.getHeight() > 0) {
                                hudPrefs.edit()
                                    .putFloat("hud_pos_x", lp.leftMargin / (float) rootView.getWidth())
                                    .putFloat("hud_pos_y", lp.topMargin / (float) rootView.getHeight())
                                    .apply();
                            }
                        } else {
                            btnCollapse.performClick();
                        }
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        return true;
                    default:
                        return false;
                }
            }
        });

        // Пока пользователь не перетаскивал HUD — держим его строго по центру (ширина меняется с текстом ОЗУ/меню)
        topHudBar.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if ((right - left) == (oldRight - oldLeft)) return;
            if (hudPrefs.getFloat("hud_pos_x", -1f) >= 0f) return;
            final FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) topHudBar.getLayoutParams();
            final int centered = Math.max(0, (rootView.getWidth() - (right - left)) / 2);
            if (lp.leftMargin != centered) {
                lp.leftMargin = centered;
                topHudBar.post(() -> topHudBar.setLayoutParams(lp));
            }
        });

        rootView.addView(topHudBar);
        rootView.addView(edgeRestoreTab);
        topHudBar.post(() -> {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) topHudBar.getLayoutParams();
            int w = rootView.getWidth();
            int h = rootView.getHeight();
            float px = hudPrefs.getFloat("hud_pos_x", -1f);
            float py = hudPrefs.getFloat("hud_pos_y", -1f);
            if (px >= 0f && py >= 0f && w > 0 && h > 0) {
                lp.leftMargin = Math.max(0, Math.min(w - topHudBar.getWidth(), Math.round(px * w)));
                lp.topMargin = Math.max(0, Math.min(h - topHudBar.getHeight(), Math.round(py * h)));
            } else {
                lp.leftMargin = Math.max(0, (w - topHudBar.getWidth()) / 2);
                lp.topMargin = dp(activity, 2);
            }
            topHudBar.setLayoutParams(lp);
            topHudBar.setVisibility(View.VISIBLE);
            wakeHud.run();
        });

        // 3. КОМПАКТНАЯ ПЕРЕТАСКИВАЕМАЯ И СКРЫВАЕМАЯ ПАНЕЛЬ ОВЕРЛЕЯ УПРАВЛЕНИЯ
        setupLiveOverlayCustomizerPanel(activity, rootView, overlayEditorBar, inputControlsView, touchpadView);

        // 4. ВЫДЕЛЕННЫЙ ПЕРЕТАСКИВАЕМЫЙ ТАЧПАД + МЫШЬ (ЛКМ | ▲ Скролл вверх / СКМ / ▼ Скролл вниз | ПКМ)
        setupFloatingTouchpadAndMouseWidget(activity, rootView, floatingTouchpadPanel, touchpadView);

        // 5. ПОТОКОБЕЗОПАСНЫЙ МОНИТОР ОКОН И ОЗУ (В ФОНОВОМ ПОТОКЕ С tryLock, БЕЗ БЛОКИРОВКИ UI И ANR!)
        startLiveProcessAndFreezeWatchdog(activity, tvWatchdog, exeShortName, container);
    }

    private static void showInSessionExePicker(final XServerDisplayActivity activity, final Container container,
                                               final InputControlsView inputControlsView, final TouchpadView touchpadView) {
        final List<DiscoveredProgram> list = scanAllAvailableProgramsAndGames(container);
        String[] labels = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            DiscoveredProgram p = list.get(i);
            labels[i] = p.categoryLabel + " • " + p.name + " (" + p.dosPath + ")";
        }

        new AlertDialog.Builder(activity)
            .setTitle("Запустить игру / программу")
            .setItems(labels, (dialog, which) -> {
                DiscoveredProgram chosen = list.get(which);
                prepareGameFolderAndDependencies(activity, container, chosen.file);
                pinExecutableToDesktopWithIcon(activity, container, chosen.file);
                if (activity.getWinHandler() != null) {
                    String cmd = "C:\\windows\\system32\\cmd.exe";
                    String args = "/c start \"\" /D \"" + chosen.workingDosDir + "\" \"" + chosen.dosPath + "\"";
                    activity.getWinHandler().exec(cmd, args);
                    AppUtils.showToast(activity, "Запущено: " + chosen.name);
                    // v12.6: для игр сразу показываем чистый геймпад (скрывается кнопкой «Геймпад» в HUD)
                    if (chosen.categoryLabel != null && chosen.categoryLabel.contains("Игра")
                            && PreferenceManager.getDefaultSharedPreferences(activity).getBoolean("auto_show_touch_controls", true)) {
                        showGamepadOverlay(activity, inputControlsView, touchpadView);
                    }
                }
            })
            .setNegativeButton("Закрыть", null)
            .show();
    }

    // ===================== v12.6.1: Unity, тихие вылеты, FPS, место на диске =====================
    private static volatile boolean gameWindowSeen = false;
    private static volatile int gameMissingTicks = 0;

    /** Игра на Unity: рядом с .exe лежит UnityPlayer.dll или папка <Имя>_Data. */
    public static boolean isUnityGame(File exe) {
        try {
            if (exe == null) return false;
            File dir = exe.getParentFile();
            if (dir == null || !dir.isDirectory()) return false;
            if (new File(dir, "UnityPlayer.dll").isFile()) return true;
            String base = FileUtils.getBasename(exe.getName());
            return base != null && !base.isEmpty() && new File(dir, base + "_Data").isDirectory();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Ключ для поиска окна игры по WM_CLASS (Wine пишет туда имя .exe в нижнем регистре). */
    private static String gameWindowKey(String targetExeName) {
        if (targetExeName == null) return "";
        String n = targetExeName.trim().toLowerCase(Locale.ROOT);
        int slash = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
        if (slash >= 0) n = n.substring(slash + 1);
        if (n.endsWith(".exe")) n = n.substring(0, n.length() - 4);
        if (n.equals("рабочий стол") || n.equals("explorer") || n.equals("wfm") || n.length() < 3) return "";
        return n;
    }

    private static String buildReportHeader(XServerDisplayActivity activity, Container container, String gameName) {
        StringBuilder sb = new StringBuilder();
        sb.append("Игра: ").append(gameName != null ? gameName : "?").append('\n');
        try {
            sb.append("Unity: ").append(activity != null && activity.unityLaunch ? "да (режим совместимости Box64 включён)" : "нет/не определено").append('\n');
            if (container != null) {
                sb.append("DX-обёртка: ").append(container.getDXWrapper()).append(" • Box64 пресет контейнера: ").append(container.getBox64Preset()).append('\n');
            }
            sb.append("Устройство: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(" • Android ").append(Build.VERSION.RELEASE).append('\n');
        } catch (Throwable ignored) {}
        sb.append('\n');
        return sb.toString();
    }

    private static File findNewestUnityErrorLog(Container container) {
        File newest = null;
        try {
            File driveC = container != null ? new File(container.getRootDir(), ".wine/drive_c") : null;
            for (File user : listDirs(driveC != null ? new File(driveC, "users") : null)) {
                for (File company : listDirs(new File(user, "AppData/Local/Temp"))) {
                    for (File product : listDirs(company)) {
                        for (File crash : listDirs(new File(product, "Crashes"))) {
                            File err = new File(crash, "error.log");
                            if (err.isFile() && (newest == null || err.lastModified() > newest.lastModified())) newest = err;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return newest;
    }

    private static boolean looksLikeCrash(String report) {
        String low = report.toLowerCase(Locale.ROOT);
        String[] markers = {"crash!!!", "received signal", "access violation", "fatal error", "native crash",
            "unhandled exception", "outputting stack trace", "sigsegv", "caught fatal signal", "abnormal program termination"};
        for (String m : markers) if (low.contains(m)) return true;
        return false;
    }

    /** Окно игры исчезло: если в логах есть признаки вылета — показываем причину, иначе короткое уведомление. */
    private static void onGameWindowClosed(final XServerDisplayActivity activity, final Container container, final String gameName) {
        if (unityCrashShown || activity == null) return;
        final long closedAt = System.currentTimeMillis();
        BG.execute(() -> {
            String body = collectUnityCrashReport(container);
            File err = findNewestUnityErrorLog(container);
            boolean freshCrashFolder = err != null && closedAt - err.lastModified() < 5 * 60_000L;
            final boolean crashed = freshCrashFolder || looksLikeCrash(body);
            final String report = buildReportHeader(activity, container, gameName) + (crashed ? downgradeUnityLevelAfterCrash(activity) : "") + body;
            final File saved = saveReportToDownloads(gameName, report);
            new Handler(Looper.getMainLooper()).post(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                if (crashed) {
                    showUnityCrashDialog(activity, report + (saved != null ? "\nЛог сохранён: " + saved.getAbsolutePath() + "\n" : ""));
                } else {
                    AppUtils.showToast(activity, "Игра закрыта" + (saved != null ? " • лог: Загрузки/Winlator-logs" : ""));
                }
            });
        });
    }

    private static File saveReportToDownloads(String gameName, String report) {
        try {
            File dir = new File(Environment.getExternalStorageDirectory(), "Download/Winlator-logs");
            if (!dir.isDirectory() && !dir.mkdirs()) return null;
            String safe = (gameName == null || gameName.trim().isEmpty() ? "game" : gameName.trim()).replaceAll("[^A-Za-z0-9._-]+", "_");
            String stamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new java.util.Date());
            File out = new File(dir, safe + "_" + stamp + ".txt");
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(report.getBytes(StandardCharsets.UTF_8));
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Кнопка FPS: выкл → простой (FPS) → подробный (FPS, ОЗУ, CPU, GPU) → выкл. FPS в HUD виден всегда. */
    private static void cycleFpsOverlay(XServerDisplayActivity activity, Container container) {
        FrameRating fr = activity.getFrameRating();
        if (fr == null) return;
        String msg;
        byte mode;
        if (!fr.isShowOverlay()) {
            fr.setMode(FrameRating.Mode.SIMPLE);
            fr.setShowOverlay(true);
            mode = (byte) FrameRating.Mode.SIMPLE.ordinal();
            msg = "Оверлей FPS: простой";
        } else if (fr.getMode() == FrameRating.Mode.SIMPLE) {
            fr.setMode(FrameRating.Mode.FULL);
            mode = (byte) FrameRating.Mode.FULL.ordinal();
            msg = "Оверлей FPS: подробный (FPS, ОЗУ, CPU, GPU)";
        } else {
            fr.setShowOverlay(false);
            mode = (byte) FrameRating.Mode.DISABLED.ordinal();
            msg = "Оверлей FPS выключен (FPS остаётся в HUD)";
        }
        try {
            if (container != null) {
                container.setHUDMode(mode);
                container.saveData();
            }
        } catch (Throwable ignored) {}
        AppUtils.showToast(activity, msg);
    }

    private static long dirSize(File root) {
        long total = 0;
        if (root == null || !root.exists()) return 0;
        java.util.ArrayDeque<File> stack = new java.util.ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            File f = stack.pop();
            try {
                if (FileUtils.isSymlink(f)) continue;
                if (f.isDirectory()) {
                    File[] kids = f.listFiles();
                    if (kids != null) for (File k : kids) stack.push(k);
                } else {
                    total += f.length();
                }
            } catch (Throwable ignored) {}
        }
        return total;
    }

    /** «Место на диске C»: что занимает контейнер + очистка кэша шейдеров. Игры не копируются — читаются на месте. */
    public static void showStorageDialog(final Activity activity, final Container container) {
        if (activity == null || container == null) return;
        final AlertDialog dlg = new AlertDialog.Builder(activity)
            .setTitle("Место на диске C")
            .setMessage("Считаю размеры…")
            .setPositiveButton("Очистить кэш шейдеров", null)
            .setNegativeButton("Закрыть", null)
            .create();
        dlg.show();
        final File root = container.getRootDir();
        BG.execute(() -> {
            File driveC = new File(root, ".wine/drive_c");
            long total = dirSize(root);
            long windows = dirSize(new File(driveC, "windows"));
            long cache = dirSize(new File(root, ".cache"));
            long users = dirSize(new File(driveC, "users"));
            long progs = dirSize(new File(driveC, "Program Files")) + dirSize(new File(driveC, "Program Files (x86)"));
            long games = dirSize(new File(driveC, "Games"));
            long uri = dirSize(new File(activity.getCacheDir(), "uri_imports"));
            long imageFs = 0;
            try {
                File rf = RootFS.find(activity).getRootDir();
                imageFs = Math.max(0, dirSize(rf) - dirSize(new File(rf, "home")));
            } catch (Throwable ignored) {}
            final String msg = "Всего в контейнере: " + StringUtils.formatBytes(total) + "\n\n"
                + "• Windows (система, DirectX, VC++): " + StringUtils.formatBytes(windows) + "\n"
                + "• Кэш шейдеров и кода (Mesa/DXVK/Box64): " + StringUtils.formatBytes(cache) + " (лимиты 256 + 512 МБ)\n"
                + "• Профили и сохранения (users): " + StringUtils.formatBytes(users) + "\n"
                + "• Program Files: " + StringUtils.formatBytes(progs) + "\n"
                + "• C:\\Games (то, что копировали сами): " + StringUtils.formatBytes(games) + "\n"
                + "• Временные копии файлов: " + StringUtils.formatBytes(uri) + "\n"
                + "• Образ системы (Wine, Box64, драйверы — распаковывается один раз): " + StringUtils.formatBytes(imageFs) + "\n\n"
                + "Игры из памяти телефона на диск C не копируются: они читаются на месте через диски "
                + "D: (Загрузки), G: (вся память) и H: (SD-карта). Диск C растёт из-за кэша шейдеров и библиотек DirectX.";
            new Handler(Looper.getMainLooper()).post(() -> {
                if (!dlg.isShowing()) return;
                dlg.setMessage(msg);
                dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(b -> {
                    dlg.setMessage("Очищаю кэш…");
                    BG.execute(() -> {
                        long before = dirSize(new File(root, ".cache")) + dirSize(new File(activity.getCacheDir(), "uri_imports"));
                        File[] caches = new File(root, ".cache").listFiles();
                        if (caches != null) {
                            for (File c : caches) {
                                String n = c.getName().toLowerCase(Locale.ROOT);
                                if (n.contains("mesa") || n.contains("dxvk") || n.contains("vkd3d") || n.contains("box64") || n.endsWith(".dxvk-cache")) FileUtils.delete(c);
                            }
                        }
                        FileUtils.delete(new File(activity.getCacheDir(), "uri_imports"));
                        long after = dirSize(new File(root, ".cache")) + dirSize(new File(activity.getCacheDir(), "uri_imports"));
                        final long freed = Math.max(0, before - after);
                        new Handler(Looper.getMainLooper()).post(() -> {
                            AppUtils.showToast(activity, "Освобождено: " + StringUtils.formatBytes(freed));
                            if (dlg.isShowing()) dlg.dismiss();
                        });
                    });
                });
            });
        });
    }

    // ===================== v12.6.2: фоновые задачи, режимы Unity, быстрые ядра =====================
    /** Общий пул фоновых потоков (daemon, простаивающие потоки завершаются) — вместо утечки нового потока на каждый вызов. */
    private static final java.util.concurrent.ExecutorService BG = Executors.newCachedThreadPool(rn -> {
        Thread t = new Thread(rn, "WinlatorPro-bg");
        t.setDaemon(true);
        return t;
    });
    private static volatile long lastShortcutSyncMs = 0;
    private static volatile boolean shortcutSyncRunning = false;

    /** Поиск игр и ярлыков для меню — в фоне, не чаще раза в 30 с. onDone(число новых ярлыков) — в UI-потоке. */
    public static void syncDesktopShortcutsInBackground(final Context ctx, final Container container, final java.util.function.IntConsumer onDone) {
        long now = System.currentTimeMillis();
        if (ctx == null || container == null || shortcutSyncRunning || now - lastShortcutSyncMs < 30_000L) return;
        shortcutSyncRunning = true;
        lastShortcutSyncMs = now;
        BG.execute(() -> {
            int added = 0;
            try {
                added = syncInstalledExeToDesktopWithIcons(ctx, container);
            } catch (Throwable ignored) {
            } finally {
                shortcutSyncRunning = false;
            }
            final int fAdded = added;
            if (onDone != null) new Handler(Looper.getMainLooper()).post(() -> onDone.accept(fAdded));
        });
    }

    public static final int UNITY_SPEED = 0;
    public static final int UNITY_BALANCE = 1;
    public static final int UNITY_STABLE = 2;

    public static String unityLevelName(int level) {
        return level <= UNITY_SPEED ? "Скорость" : (level == UNITY_BALANCE ? "Баланс" : "Стабильность");
    }

    private static String unityPrefKey(File exe) {
        return "unity_level_" + gameWindowKey(exe != null ? exe.getName() : "");
    }

    public static int getUnityLevel(Context ctx, File exe) {
        try {
            return PreferenceManager.getDefaultSharedPreferences(ctx).getInt(unityPrefKey(exe), UNITY_BALANCE);
        } catch (Throwable t) {
            return UNITY_BALANCE;
        }
    }

    public static void setUnityLevel(Context ctx, File exe, int level) {
        try {
            PreferenceManager.getDefaultSharedPreferences(ctx).edit().putInt(unityPrefKey(exe), Math.max(UNITY_SPEED, Math.min(UNITY_STABLE, level))).apply();
        } catch (Throwable ignored) {}
    }

    /**
     * Настройки Box64 для Unity. Всегда: STRONGMEM=1 (многопоточность движка) и CALLRET=0 (исключения движка) —
     * именно они убрали вылет. Остальное: Скорость (как PERFORMANCE), Баланс (быстро, по умолчанию), Стабильность (осторожно).
     */
    public static void applyUnityBox64Level(int level, GuestProgramLauncherComponent gplc, EnvVars env) {
        if (level >= UNITY_STABLE) {
            gplc.setBox64Preset(Box64Preset.CONSERVATIVE);
            env.put("BOX64_DYNAREC_BIGBLOCK", "1");
            env.put("BOX64_DYNAREC_SAFEFLAGS", "2");
            env.put("BOX64_DYNAREC_NATIVEFLAGS", "0");
            env.put("BOX64_DYNAREC_WEAKBARRIER", "1");
        } else if (level == UNITY_BALANCE) {
            gplc.setBox64Preset(Box64Preset.INTERMEDIATE);
            env.put("BOX64_DYNAREC_BIGBLOCK", "2");
            env.put("BOX64_DYNAREC_SAFEFLAGS", "1");
            env.put("BOX64_DYNAREC_FASTNAN", "1");
            env.put("BOX64_DYNAREC_X87DOUBLE", "0");
            env.put("BOX64_DYNAREC_NATIVEFLAGS", "1");
            env.put("BOX64_DYNAREC_FORWARD", "512");
            env.put("BOX64_DYNAREC_WEAKBARRIER", "2");
        } else {
            gplc.setBox64Preset(Box64Preset.PERFORMANCE);
            env.put("BOX64_DYNAREC_WEAKBARRIER", "2");
        }
        env.put("BOX64_DYNAREC_STRONGMEM", "1");
        env.put("BOX64_DYNAREC_CALLRET", "0");
    }

    private static void cycleUnityLevel(XServerDisplayActivity activity, Button btn) {
        File exe = activity.getLaunchTargetExe();
        if (exe == null) return;
        int cur = getUnityLevel(activity, exe);
        int next = cur == UNITY_BALANCE ? UNITY_SPEED : (cur == UNITY_SPEED ? UNITY_STABLE : UNITY_BALANCE);
        setUnityLevel(activity, exe, next);
        btn.setText("Unity: " + unityLevelName(next));
        AppUtils.showToast(activity, "Режим Unity: " + unityLevelName(next) + " — применится при следующем запуске игры");
    }

    /** После вылета следующий запуск этой игры — на шаг ближе к «Стабильности». */
    private static String downgradeUnityLevelAfterCrash(XServerDisplayActivity activity) {
        try {
            if (activity == null || activity.unityLevel < 0) return "";
            File exe = activity.getLaunchTargetExe();
            if (exe == null) return "";
            int cur = getUnityLevel(activity, exe);
            if (cur >= UNITY_STABLE) return "";
            setUnityLevel(activity, exe, cur + 1);
            return "Следующий запуск — автоматически в режиме Unity «" + unityLevelName(cur + 1) + "».\n\n";
        } catch (Throwable t) {
            return "";
        }
    }

    private static volatile int cachedFastMask = -1;

    /** Маска быстрых ядер (по cpu_capacity, иначе по макс. частоте). 0 — если ядра одинаковые или быстрых меньше 3. */
    static int fastCoresMask() {
        if (cachedFastMask >= 0) return cachedFastMask;
        int mask = 0;
        try {
            boolean useCapacity = new File("/sys/devices/system/cpu/cpu0/cpu_capacity").isFile();
            long[] val = new long[16];
            int n = 0;
            long top = 0;
            for (int i = 0; i < 16; i++) {
                File base = new File("/sys/devices/system/cpu/cpu" + i);
                if (!base.isDirectory()) break;
                File src = useCapacity ? new File(base, "cpu_capacity") : new File(base, "cpufreq/cpuinfo_max_freq");
                long v = 0;
                try {
                    String txt = FileUtils.readString(src);
                    if (txt != null) v = Long.parseLong(txt.trim());
                } catch (Throwable ignored) {}
                val[i] = v;
                n = i + 1;
                top = Math.max(top, v);
            }
            int count = 0;
            if (top > 0) {
                double threshold = useCapacity ? 0.5 : 0.75;
                for (int i = 0; i < n; i++) {
                    if (val[i] >= top * threshold) {
                        mask |= 1 << i;
                        count++;
                    }
                }
            }
            if (count < 3 || count >= n) mask = 0;
        } catch (Throwable t) {
            mask = 0;
        }
        cachedFastMask = mask;
        return mask;
    }

    private static String maskToList(int mask) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i++) if ((mask & (1 << i)) != 0) sb.append(sb.length() > 0 ? "," : "").append(i);
        return sb.toString();
    }

    /** Закрепляет игру за быстрыми ядрами (меньше рывков), если пользователь не выбрал ядра сам в настройках контейнера. */
    private static void applyFastCoresAffinity(XServerDisplayActivity activity, Container container, int pid, String exeName) {
        try {
            if (!isFastCoresEnabled(activity)) return;
            if (container != null && container.getCPUList() != null) return;
            int mask = fastCoresMask();
            if (mask == 0) return;
            WinHandler wh = activity.getWinHandler();
            if (wh == null) return;
            if (pid > 0) {
                wh.setProcessAffinity(pid, mask);
            } else if (exeName != null && !exeName.trim().isEmpty()) {
                String proc = exeName.trim();
                if (!proc.toLowerCase(Locale.ROOT).endsWith(".exe")) proc = proc + ".exe";
                wh.setProcessAffinity(proc, mask);
            } else {
                return;
            }
            AppUtils.showToast(activity, "⚡ Игра работает на быстрых ядрах: " + maskToList(mask));
        } catch (Throwable ignored) {}
    }

    // ===================== v12.6.3: ровные кадры, панель производительности =====================
    public static final int FPS_AUTO = -1;

    private static String gamePrefSuffix(XServerDisplayActivity a) {
        File exe = a != null ? a.getLaunchTargetExe() : null;
        String k = gameWindowKey(exe != null ? exe.getName() : "");
        return k.isEmpty() ? "desktop" : k;
    }

    public static int getFpsMode(XServerDisplayActivity a) {
        try {
            return PreferenceManager.getDefaultSharedPreferences(a).getInt("fps_limit_" + gamePrefSuffix(a), FPS_AUTO);
        } catch (Throwable t) {
            return FPS_AUTO;
        }
    }

    private static void setFpsMode(XServerDisplayActivity a, int mode) {
        try {
            PreferenceManager.getDefaultSharedPreferences(a).edit().putInt("fps_limit_" + gamePrefSuffix(a), mode).apply();
        } catch (Throwable ignored) {}
        GOV_MODE = Integer.MIN_VALUE; // применится на следующем тике (через секунду)
    }

    public static boolean isFastCoresEnabled(XServerDisplayActivity a) {
        try {
            return PreferenceManager.getDefaultSharedPreferences(a).getBoolean("fast_cores_" + gamePrefSuffix(a), true);
        } catch (Throwable t) {
            return true;
        }
    }

    private static float currentRefresh(Activity a) {
        try {
            return a.getWindowManager().getDefaultDisplay().getRefreshRate();
        } catch (Throwable t) {
            return 60f;
        }
    }

    /** Частоты экрана, доступные при текущем разрешении (60/90/120/144…). */
    private static float[] supportedRefreshRates(Activity a) {
        try {
            android.view.Display d = a.getWindowManager().getDefaultDisplay();
            android.view.Display.Mode cur = d.getMode();
            java.util.TreeSet<Float> set = new java.util.TreeSet<>();
            for (android.view.Display.Mode m : d.getSupportedModes()) {
                if (m.getPhysicalWidth() == cur.getPhysicalWidth() && m.getPhysicalHeight() == cur.getPhysicalHeight()) {
                    set.add((float) Math.round(m.getRefreshRate()));
                }
            }
            float[] out = new float[set.size()];
            int i = 0;
            for (Float f : set) out[i++] = f;
            if (out.length > 0) return out;
        } catch (Throwable ignored) {}
        return new float[]{Math.round(currentRefresh(a))};
    }

    /** Лимиты, при которых каждый кадр держится на экране одинаковое число тактов. */
    private static int[] evenCaps(Activity a) {
        float[] rates = supportedRefreshRates(a);
        java.util.ArrayList<Integer> caps = new java.util.ArrayList<>();
        for (int cap : new int[]{60, 45, 40, 30}) {
            for (float r : rates) {
                float k = r / cap;
                if (k >= 0.99f && Math.abs(k - Math.round(k)) < 0.03f) {
                    caps.add(cap);
                    break;
                }
            }
        }
        if (caps.isEmpty()) caps.add(30);
        int[] out = new int[caps.size()];
        for (int i = 0; i < out.length; i++) out[i] = caps.get(i);
        return out;
    }

    /** Режим экрана под лимит: 30→60 Гц, 40→120 Гц, 45→90 Гц, 60→120 Гц (если есть). 0 — вернуть системный. */
    private static int requestDisplayModeFor(Activity a, int cap) {
        int chosenRate = 0;
        try {
            android.view.Display d = a.getWindowManager().getDefaultDisplay();
            android.view.Display.Mode cur = d.getMode();
            int bestId = 0;
            float bestRate = 0;
            if (cap > 0) {
                float bestScore = Float.MAX_VALUE;
                for (android.view.Display.Mode m : d.getSupportedModes()) {
                    if (m.getPhysicalWidth() != cur.getPhysicalWidth() || m.getPhysicalHeight() != cur.getPhysicalHeight()) continue;
                    float r = m.getRefreshRate();
                    float k = r / cap;
                    if (k < 0.99f || Math.abs(k - Math.round(k)) >= 0.03f) continue;
                    // лучше всего ×2 (запас на такт), затем минимальная подходящая частота (меньше расход батареи)
                    float score = (Math.round(k) >= 2 ? 0 : 1000) + r;
                    if (score < bestScore) {
                        bestScore = score;
                        bestId = m.getModeId();
                        bestRate = r;
                    }
                }
            }
            android.view.WindowManager.LayoutParams lp = a.getWindow().getAttributes();
            if (lp.preferredDisplayModeId != bestId) {
                lp.preferredDisplayModeId = bestId;
                a.getWindow().setAttributes(lp);
            }
            chosenRate = Math.round(bestRate);
        } catch (Throwable ignored) {}
        return chosenRate;
    }

    private static int GOV_MODE = Integer.MIN_VALUE;
    private static int GOV_PHASE = 0;
    private static int GOV_CAP = 0;
    private static int GOV_LOW = 0;
    private static int GOV_N = 0;
    private static final float[] GOV_SAMPLES = new float[32];

    private static void applyFpsCap(XServerDisplayActivity a, int cap) {
        GOV_CAP = cap;
        PresentExtension.fpsLimit = cap;
        requestDisplayModeFor(a, cap);
    }

    /** Раз в секунду (UI-поток): Авто-режим меряет FPS 12 с без лимита и ставит ровный лимит, кратный частоте экрана. */
    private static void fpsGovernorTick(XServerDisplayActivity a, float fps) {
        int mode = getFpsMode(a);
        if (mode != GOV_MODE) {
            GOV_MODE = mode;
            GOV_PHASE = 0;
            GOV_N = 0;
            GOV_LOW = 0;
            if (mode == FPS_AUTO) {
                GOV_CAP = 0;
                PresentExtension.fpsLimit = 0;
                requestDisplayModeFor(a, 0);
            } else {
                applyFpsCap(a, Math.max(0, mode));
            }
        }
        if (mode != FPS_AUTO || fps < 5f) return;
        if (GOV_PHASE == 0) {
            GOV_SAMPLES[GOV_N++] = fps;
            if (GOV_N >= 12) {
                float[] sorted = java.util.Arrays.copyOf(GOV_SAMPLES, GOV_N);
                java.util.Arrays.sort(sorted);
                float p30 = sorted[(int) (sorted.length * 0.4f)];
                int[] caps = evenCaps(a);
                int cap = caps[caps.length - 1];
                for (int c : caps) {
                    if (c <= p30 * 1.03f) {
                        cap = c;
                        break;
                    }
                }
                applyFpsCap(a, cap);
                GOV_PHASE = 1;
                GOV_N = 0;
                AppUtils.showToast(a, "Плавность: ровные " + cap + " FPS (игра даёт ~" + Math.round(p30) + "). Изменить: ☰ ▸ FPS");
            }
        } else if (GOV_CAP > 0) {
            if (fps < GOV_CAP * 0.9f) GOV_LOW++;
            else if (GOV_LOW > 0) GOV_LOW--;
            if (GOV_LOW >= 6) {
                GOV_LOW = 0;
                int[] caps = evenCaps(a);
                for (int c : caps) {
                    if (c < GOV_CAP) {
                        applyFpsCap(a, c);
                        AppUtils.showToast(a, "Плавность: лимит снижен до " + c + " FPS");
                        break;
                    }
                }
            }
        }
    }

    private static TextView perfTitle(Context ctx, String text) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextColor(UI_TEXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(ctx, 10), 0, dp(ctx, 2));
        return t;
    }

    private static TextView perfHint(Context ctx, String text) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextColor(UI_TEXT_DIM);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        t.setPadding(0, 0, 0, dp(ctx, 4));
        return t;
    }

    /** Ряд кнопок-переключателей; выбранная подсвечивается. */
    private static View perfChips(Context ctx, String[] labels, int[] values, int current, java.util.function.IntConsumer onPick) {
        HorizontalScrollView hsv = new HorizontalScrollView(ctx);
        hsv.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        final Button[] buttons = new Button[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            Button b = hudButton(ctx, labels[i]);
            buttons[i] = b;
            setHudActive(ctx, b, values[i] == current);
            b.setOnClickListener(v -> {
                for (int j = 0; j < buttons.length; j++) setHudActive(ctx, buttons[j], j == idx);
                onPick.accept(values[idx]);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, 36));
            lp.rightMargin = dp(ctx, 6);
            row.addView(b, lp);
        }
        hsv.addView(row);
        return hsv;
    }

    private static int allCoresMask() {
        int n = Math.max(1, Math.min(16, Runtime.getRuntime().availableProcessors()));
        return (1 << n) - 1;
    }

    /** Панель «Производительность»: ровные кадры, оверлей, ядра, режим Unity, сведения об устройстве. */
    public static void showPerformancePanel(final XServerDisplayActivity activity, final Container container) {
        final Context ctx = activity;
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(ctx, 18), dp(ctx, 4), dp(ctx, 18), dp(ctx, 6));

        root.addView(perfTitle(ctx, "Ровные кадры (лимит FPS)"));
        root.addView(perfHint(ctx, "Дёрганье — это когда кадры висят на экране то 2, то 3 такта. Лимит, кратный частоте экрана, "
            + "делает их ровными. «Авто» сам подбирает самый высокий лимит, который игра держит. Работает сразу, без перезапуска."));
        root.addView(perfChips(ctx, new String[]{"Авто", "30", "40", "45", "60", "Без лимита"}, new int[]{FPS_AUTO, 30, 40, 45, 60, 0},
            getFpsMode(activity), v -> setFpsMode(activity, v)));

        root.addView(perfTitle(ctx, "Оверлей FPS"));
        final FrameRating fr = activity.getFrameRating();
        int ov = fr == null || !fr.isShowOverlay() ? 0 : (fr.getMode() == FrameRating.Mode.FULL ? 2 : 1);
        root.addView(perfChips(ctx, new String[]{"Выкл", "Простой", "Подробный"}, new int[]{0, 1, 2}, ov, v -> {
            if (fr == null) return;
            byte mode;
            if (v == 0) {
                fr.setShowOverlay(false);
                mode = (byte) FrameRating.Mode.DISABLED.ordinal();
            } else {
                fr.setMode(v == 2 ? FrameRating.Mode.FULL : FrameRating.Mode.SIMPLE);
                fr.setShowOverlay(true);
                mode = (byte) (v == 2 ? FrameRating.Mode.FULL : FrameRating.Mode.SIMPLE).ordinal();
            }
            try {
                if (container != null) {
                    container.setHUDMode(mode);
                    container.saveData();
                }
            } catch (Throwable ignored) {}
        }));

        final int fastMask = fastCoresMask();
        if (fastMask != 0) {
            root.addView(perfTitle(ctx, "Ядра процессора"));
            root.addView(perfHint(ctx, "«Быстрые» (" + maskToList(fastMask) + ") — меньше рывков; «Все» — больше потоков. Применяется сразу."));
            root.addView(perfChips(ctx, new String[]{"Быстрые", "Все"}, new int[]{1, 0}, isFastCoresEnabled(activity) ? 1 : 0, v -> {
                try {
                    PreferenceManager.getDefaultSharedPreferences(activity).edit().putBoolean("fast_cores_" + gamePrefSuffix(activity), v == 1).apply();
                    File exe = activity.getLaunchTargetExe();
                    WinHandler wh = activity.getWinHandler();
                    if (exe != null && wh != null) wh.setProcessAffinity(exe.getName().toLowerCase(Locale.ROOT), v == 1 ? fastMask : allCoresMask());
                } catch (Throwable ignored) {}
            }));
        }

        if (activity.unityLevel >= 0 && activity.getLaunchTargetExe() != null) {
            final File exe = activity.getLaunchTargetExe();
            root.addView(perfTitle(ctx, "Режим Unity (со следующего запуска)"));
            root.addView(perfHint(ctx, "«Скорость» — быстрее всего, «Стабильность» — если игра вылетает. Защита от вылета включена во всех режимах."));
            root.addView(perfChips(ctx, new String[]{"Скорость", "Баланс", "Стабильность"}, new int[]{UNITY_SPEED, UNITY_BALANCE, UNITY_STABLE},
                getUnityLevel(activity, exe), v -> setUnityLevel(activity, exe, v)));
        }

        StringBuilder info = new StringBuilder();
        float[] rates = supportedRefreshRates(activity);
        info.append("Экран: ").append(Math.round(currentRefresh(activity))).append(" Гц (доступно:");
        for (float r : rates) info.append(' ').append(Math.round(r));
        info.append(")\n");
        try {
            String gpu = GPUHelper.glGetRenderer(activity);
            if (gpu != null && !gpu.isEmpty()) info.append("GPU: ").append(gpu).append('\n');
        } catch (Throwable ignored) {}
        try {
            String soc = Build.VERSION.SDK_INT >= 31 ? (Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL) : Build.HARDWARE;
            info.append("Процессор: ").append(soc).append(" • ядер: ").append(Runtime.getRuntime().availableProcessors()).append('\n');
        } catch (Throwable ignored) {}
        info.append("Сейчас: ").append(PresentExtension.fpsLimit > 0 ? "лимит " + PresentExtension.fpsLimit + " FPS" : "без лимита")
            .append(" • кэш кода Box64 включён");
        TextView tvInfo = perfHint(ctx, info.toString());
        tvInfo.setPadding(0, dp(ctx, 12), 0, 0);
        root.addView(tvInfo);

        ScrollView scroll = new ScrollView(ctx);
        scroll.addView(root);
        new AlertDialog.Builder(ctx)
            .setTitle("Производительность")
            .setView(scroll)
            .setPositiveButton("Готово", null)
            .setNeutralButton("Тест FPS", (d, w) -> showFpsBenchmarkDialog(activity, container))
            .show();
    }

    public static void ensureRichControlPresetsInstalled(Context context) {
        try {
            File profilesDir = InputControlsManager.getProfilesDir(context);
            File p5 = new File(profilesDir, "controls-5.icp");
            File p6 = new File(profilesDir, "controls-6.icp");
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            // v12.6: пресет 5 = чистый геймпад Xbox. Перезаписываем один раз (флаг в настройках),
            // дальше пользовательские правки пресета сохраняются.
            if (!p5.isFile() || !prefs.getBoolean("preset5_pure_gamepad_v2", false)) {
                FileUtils.copy(context, "inputcontrols/profiles/controls-5.icp", p5);
                prefs.edit().putBoolean("preset5_pure_gamepad_v2", true).apply();
            }
            if (!p6.isFile()) {
                FileUtils.copy(context, "inputcontrols/profiles/controls-6.icp", p6);
            }
        } catch (Throwable ignored) {}
    }

    // ===================== v12.6: нейтральная палитра, геймпад-оверлей, сбои Unity =====================
    private static final int UI_PANEL_BG = 0xE01C1D21;
    private static final int UI_PANEL_STROKE = 0x2EFFFFFF;
    private static final int UI_BTN_BG = 0x1AFFFFFF;
    private static final int UI_BTN_STROKE = 0x24FFFFFF;
    private static final int UI_BTN_ACTIVE_BG = 0x3D8AB4F8;
    private static final int UI_ACCENT = 0xFF8AB4F8;
    private static final int UI_TEXT = 0xFFE8EAED;
    private static final int UI_TEXT_DIM = 0xFFA8ADB4;
    private static volatile boolean unityCrashShown = false;

    private static Button hudButton(Context ctx, String text) {
        Button b = createStyledButton(ctx, text, UI_BTN_BG, UI_BTN_STROKE);
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT);
        b.setTextColor(UI_TEXT);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(dp(ctx, 28));
        b.setMinimumHeight(dp(ctx, 28));
        b.setPadding(dp(ctx, 10), 0, dp(ctx, 10), 0);
        b.setStateListAnimator(null);
        b.setBackground(roundedBg(UI_BTN_BG, UI_BTN_STROKE, 1, 14, ctx));
        return b;
    }

    private static void setHudActive(Context ctx, Button b, boolean active) {
        b.setBackground(roundedBg(active ? UI_BTN_ACTIVE_BG : UI_BTN_BG, active ? UI_ACCENT : UI_BTN_STROKE, 1, 14, ctx));
        b.setTextColor(active ? UI_ACCENT : UI_TEXT);
    }

    private static boolean isGamepadOverlayVisible(InputControlsView icv) {
        return icv != null && icv.getVisibility() == View.VISIBLE && icv.getProfile() != null && icv.isShowTouchscreenControls();
    }

    /** Показывает чистый геймпад (пресет 5 «Геймпад Xbox») — без клавиатуры и мыши. */
    public static void showGamepadOverlay(XServerDisplayActivity activity, InputControlsView icv, TouchpadView touchpadView) {
        if (activity == null || icv == null) return;
        try {
            ControlsProfile prof = icv.getProfile();
            if (prof == null || !prof.isVirtualGamepad()) {
                InputControlsManager icm = new InputControlsManager(activity);
                ControlsProfile pad = icm.getProfile(5);
                if (pad == null) pad = icm.getProfile(3);
                if (pad != null) prof = pad;
            }
            if (prof == null) {
                AppUtils.showToast(activity, "Профиль геймпада не найден");
                return;
            }
            icv.setShowTouchscreenControls(true);
            activity.showInputControls(prof);
        } catch (Throwable t) {
            AppUtils.showToast(activity, "Не удалось показать геймпад: " + t.getMessage());
        }
    }

    /** Прячет геймпад и сначала отпускает все виртуальные кнопки/стики, чтобы в игре ничего не «залипло». */
    public static void hideGamepadOverlay(XServerDisplayActivity activity, InputControlsView icv) {
        if (activity == null || icv == null) return;
        try {
            ControlsProfile prof = icv.getProfile();
            if (prof != null) {
                GamepadState st = prof.getGamepadState();
                if (st != null) {
                    st.buttons = 0;
                    st.thumbLX = 0; st.thumbLY = 0; st.thumbRX = 0; st.thumbRY = 0;
                    st.triggerL = 0; st.triggerR = 0;
                    Arrays.fill(st.dpad, false);
                    WinHandler wh = activity.getWinHandler();
                    if (wh != null && prof.isVirtualGamepad()) wh.gamepadHandler.sendGamepadState(prof);
                }
            }
            icv.setEditMode(false);
            activity.hideInputControls();
        } catch (Throwable t) {
            icv.setVisibility(View.GONE);
        }
    }

    /** Окно UnityCrashHandler = игра упала. Даём ему 3 с дописать error.log, закрываем и показываем причину. */
    private static void handleUnityCrash(final XServerDisplayActivity activity, final Container container, final String gameName) {
        final Handler h = new Handler(Looper.getMainLooper());
        AppUtils.showToast(activity, "Игра Unity аварийно закрылась — собираю лог…");
        h.postDelayed(() -> {
            try {
                WinHandler wh = activity.getWinHandler();
                if (wh != null) {
                    wh.killProcess("UnityCrashHandler64.exe");
                    wh.killProcess("UnityCrashHandler32.exe");
                }
            } catch (Throwable ignored) {}
            BG.execute(() -> {
                final String report = buildReportHeader(activity, container, gameName) + downgradeUnityLevelAfterCrash(activity) + collectUnityCrashReport(container);
                final File saved = saveReportToDownloads(gameName, report);
                h.post(() -> showUnityCrashDialog(activity, report + (saved != null ? "\nЛог сохранён: " + saved.getAbsolutePath() + "\n" : "")));
            });
        }, 3000);
    }

    private static File[] listDirs(File dir) {
        File[] f = dir != null ? dir.listFiles(File::isDirectory) : null;
        return f != null ? f : new File[0];
    }

    /** error.log: %TEMP%\<Компания>\<Игра>\Crashes\Crash_*\error.log ; Player.log: AppData\LocalLow\<Компания>\<Игра>\Player.log */
    private static String collectUnityCrashReport(Container container) {
        StringBuilder sb = new StringBuilder();
        try {
            File driveC = container != null ? new File(container.getRootDir(), ".wine/drive_c") : null;
            File newestError = null;
            File newestPlayerLog = null;
            for (File user : listDirs(driveC != null ? new File(driveC, "users") : null)) {
                for (File company : listDirs(new File(user, "AppData/Local/Temp"))) {
                    for (File product : listDirs(company)) {
                        for (File crash : listDirs(new File(product, "Crashes"))) {
                            File err = new File(crash, "error.log");
                            if (err.isFile() && (newestError == null || err.lastModified() > newestError.lastModified())) newestError = err;
                        }
                    }
                }
                for (File company : listDirs(new File(user, "AppData/LocalLow"))) {
                    for (File product : listDirs(company)) {
                        File pl = new File(product, "Player.log");
                        if (pl.isFile() && (newestPlayerLog == null || pl.lastModified() > newestPlayerLog.lastModified())) newestPlayerLog = pl;
                    }
                }
            }
            if (newestError != null) {
                sb.append("== error.log (").append(newestError.getParentFile().getName()).append(") ==\n");
                sb.append(readHeadLines(newestError, 30)).append('\n');
            }
            if (newestPlayerLog != null) {
                sb.append("== Player.log: ").append(newestPlayerLog.getParentFile().getName()).append(" ==\n");
                sb.append(readImportantTail(newestPlayerLog)).append('\n');
            }
            if (sb.length() == 0) sb.append("Логи Unity не найдены (drive_c/users/*/AppData).\n");
        } catch (Throwable t) {
            sb.append("Не удалось прочитать логи: ").append(t).append('\n');
        }
        return sb.toString();
    }

    private static String readHeadLines(File f, int maxLines) {
        StringBuilder sb = new StringBuilder();
        try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            int n = 0;
            while ((line = br.readLine()) != null && n < maxLines) {
                if (line.trim().isEmpty()) continue;
                sb.append(line).append('\n');
                n++;
            }
        } catch (Throwable t) {
            sb.append("(не удалось прочитать ").append(f.getName()).append(")\n");
        }
        return sb.toString();
    }

    /** Из хвоста Player.log берём строки с ошибками (последние 40) + последние 15 строк перед падением. */
    private static String readImportantTail(File f) {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            long len = raf.length();
            long start = Math.max(0L, len - 256L * 1024L);
            raf.seek(start);
            byte[] buf = new byte[(int) (len - start)];
            raf.readFully(buf);
            String[] lines = new String(buf, StandardCharsets.UTF_8).split("\r?\n");
            String[] keys = {"exception", "error", "crash", "failed", "fatal", "access violation", "d3d", "dxgi",
                             "vulkan", "unable", "cannot", "not supported", "timezone", "nullreference", "missing"};
            List<String> important = new ArrayList<>();
            for (String line : lines) {
                String low = line.toLowerCase(Locale.ROOT);
                for (String k : keys) {
                    if (low.contains(k)) {
                        important.add(line);
                        break;
                    }
                }
            }
            StringBuilder sb = new StringBuilder();
            for (int i = Math.max(0, important.size() - 40); i < important.size(); i++) sb.append(important.get(i)).append('\n');
            sb.append("-- последние строки --\n");
            for (int i = Math.max(0, lines.length - 15); i < lines.length; i++) sb.append(lines[i]).append('\n');
            return sb.toString();
        } catch (Throwable t) {
            return "(не удалось прочитать " + f.getName() + ")\n";
        }
    }

    private static void showUnityCrashDialog(final XServerDisplayActivity activity, final String report) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        final String advice = "Игра закрылась с ошибкой, Unity показал окно сбоя (UnityCrashHandler) — оно закрыто автоматически.\n\n"
            + "Что попробовать:\n"
            + (activity.unityLaunch ? "• Режим совместимости Unity уже включён автоматически (Box64 CONSERVATIVE + STRONGMEM).\n" : "")
            + "• В настройках контейнера смените DX-обёртку: DXVK 2.4.1 ⇄ DXVK 1.7.2 ⇄ WineD3D и запустите снова.\n"
            + "• Если падает сразу на заставке — включите в контейнере d3dcompiler_47 / проверьте часовой пояс.\n"
            + "• Нажмите «Скопировать» и пришлите текст — по нему видна точная причина.\n\n";
        final String full = advice + report;
        TextView tv = new TextView(activity);
        tv.setText(full);
        tv.setTextIsSelectable(true);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        tv.setPadding(dp(activity, 16), dp(activity, 8), dp(activity, 16), dp(activity, 8));
        ScrollView sv = new ScrollView(activity);
        sv.addView(tv);
        new AlertDialog.Builder(activity)
            .setTitle("Сбой игры (Unity)")
            .setView(sv)
            .setPositiveButton("Скопировать", (d, w) -> {
                try {
                    ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Unity crash log", report));
                    AppUtils.showToast(activity, "Лог скопирован");
                } catch (Throwable ignored) {}
            })
            .setNegativeButton("Закрыть", null)
            .show();
    }

    /**
     * Выделенный перетаскиваемый Тачпад + Кнопки мыши в правильном порядке:
     * Слева: [ ЛКМ ]
     * Посередине: [ ▲ Скролл вверх ] / [ СКМ ] / [ ▼ Скролл вниз ]
     * Справа: [ ПКМ ]
     * При работе с этим тачпадом курсор мыши двигается плавно относительно пальца и НЕ прыгает посреди экрана!
     */
    private static void setupFloatingTouchpadAndMouseWidget(
        final XServerDisplayActivity activity,
        final FrameLayout rootView,
        final LinearLayout widget,
        final TouchpadView touchpadView
    ) {
        widget.setOrientation(LinearLayout.VERTICAL);
        widget.setPadding(dp(activity, 6), dp(activity, 5), dp(activity, 6), dp(activity, 6));
        widget.setBackground(roundedBg(UI_PANEL_BG, UI_PANEL_STROKE, 1, 14, activity));
        widget.setVisibility(View.GONE);

        int wPx = dp(activity, 236);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            wPx,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP | Gravity.START
        );
        lp.leftMargin = Math.max(dp(activity, 16), AppUtils.getScreenWidth() - wPx - dp(activity, 18));
        lp.topMargin = Math.max(dp(activity, 44), AppUtils.getScreenHeight() - dp(activity, 255));
        widget.setLayoutParams(lp);

        // Шапка виджета для перетаскивания пальцем + кнопка Центрирования + кнопка Скрытия
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvTitle = new TextView(activity);
        tvTitle.setText("⠿  Тачпад");
        tvTitle.setTextColor(UI_TEXT_DIM);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvTitle.setPadding(dp(activity, 2), dp(activity, 1), dp(activity, 4), dp(activity, 2));
        header.addView(tvTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        makeViewDraggable(widget, tvTitle, rootView);

        Button btnCenter = createStyledButton(activity, "В центр", UI_BTN_BG, UI_BTN_STROKE);
        btnCenter.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f);
        btnCenter.setMinHeight(dp(activity, 20));
        btnCenter.setMinimumHeight(dp(activity, 20));
        btnCenter.setPadding(dp(activity, 5), dp(activity, 1), dp(activity, 5), dp(activity, 1));
        btnCenter.setOnClickListener(v -> {
            XServer xs = activity.getXServer();
            if (xs != null) {
                xs.injectPointerMove(xs.screenInfo.width / 2, xs.screenInfo.height / 2);
                if (xs.getRenderer() != null) xs.getRenderer().setCursorVisible(true);
            }
            touchpadView.setMoveCursorToTouchpoint(false);
        });
        header.addView(btnCenter, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT) {{
            rightMargin = dp(activity, 4);
        }});

        Button btnHide = createStyledButton(activity, "✕", UI_BTN_BG, UI_BTN_STROKE);
        btnHide.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f);
        btnHide.setMinHeight(dp(activity, 20));
        btnHide.setMinimumHeight(dp(activity, 20));
        btnHide.setPadding(dp(activity, 5), dp(activity, 1), dp(activity, 5), dp(activity, 1));
        btnHide.setOnClickListener(v -> widget.setVisibility(View.GONE));
        header.addView(btnHide, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        widget.addView(header);

        // Сенсорная поверхность ТАЧПАДА
        final TextView padSurface = new TextView(activity);
        padSurface.setText("Водите пальцем • тап = ЛКМ");
        padSurface.setTextColor(0x99E8EAED);
        padSurface.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        padSurface.setGravity(Gravity.CENTER);
        padSurface.setBackground(roundedBg(0x14FFFFFF, UI_PANEL_STROKE, 1, 10, activity));
        LinearLayout.LayoutParams padLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(activity, 105)
        );
        padLp.topMargin = dp(activity, 4);
        padLp.bottomMargin = dp(activity, 5);
        padSurface.setLayoutParams(padLp);

        padSurface.setOnTouchListener(new View.OnTouchListener() {
            private float lastX, lastY, downX, downY;
            private long downTime;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                XServer xs = activity.getXServer();
                if (xs == null) return true;
                touchpadView.setMoveCursorToTouchpoint(false);

                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        lastX = downX = event.getX();
                        lastY = downY = event.getY();
                        downTime = System.currentTimeMillis();
                        padSurface.setBackground(roundedBg(0x26FFFFFF, UI_ACCENT, 1, 10, activity));
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        float curX = event.getX();
                        float curY = event.getY();
                        float rawDx = (curX - lastX) * 1.45f;
                        float rawDy = (curY - lastY) * 1.45f;
                        if (Math.abs(rawDx) > 5f) rawDx *= 1.35f;
                        if (Math.abs(rawDy) > 5f) rawDy *= 1.35f;
                        lastX = curX;
                        lastY = curY;
                        int dx = Math.round(rawDx);
                        int dy = Math.round(rawDy);
                        if (dx != 0 || dy != 0) {
                            xs.injectPointerMoveDelta(dx, dy);
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        padSurface.setBackground(roundedBg(0x14FFFFFF, UI_PANEL_STROKE, 1, 10, activity));
                        long dt = System.currentTimeMillis() - downTime;
                        double dist = Math.hypot(event.getX() - downX, event.getY() - downY);
                        if (event.getActionMasked() == MotionEvent.ACTION_UP && dt < 210 && dist < dp(activity, 8)) {
                            xs.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT);
                            v.postDelayed(() -> xs.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT), 30);
                        }
                        return true;
                }
                return true;
            }
        });
        widget.addView(padSurface);

        // Ряд кнопок мыши в правильном порядке: [ ЛКМ ]  |  [ ▲ / СКМ / ▼ ]  |  [ ПКМ ]
        LinearLayout mouseButtonsRow = new LinearLayout(activity);
        mouseButtonsRow.setOrientation(LinearLayout.HORIZONTAL);
        mouseButtonsRow.setGravity(Gravity.CENTER_VERTICAL);

        // 1. Левая кнопка мыши (ЛКМ) — слева!
        final Button btnLmb = createStyledButton(activity, "ЛКМ", UI_BTN_BG, UI_BTN_STROKE);
        btnLmb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        btnLmb.setMinHeight(dp(activity, 68));
        btnLmb.setMinimumHeight(dp(activity, 68));
        btnLmb.setOnTouchListener((v, event) -> {
            XServer xs = activity.getXServer();
            if (xs == null) return true;
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                btnLmb.setBackground(roundedBg(UI_BTN_ACTIVE_BG, UI_ACCENT, 1, 8, activity));
                xs.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT);
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                btnLmb.setBackground(roundedBg(UI_BTN_BG, UI_BTN_STROKE, 1, 8, activity));
                xs.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT);
            }
            return true;
        });
        mouseButtonsRow.addView(btnLmb, new LinearLayout.LayoutParams(0, dp(activity, 68), 1.05f) {{
            rightMargin = dp(activity, 4);
        }});

        // 2. Средняя колонка (Колесо прокрутки со стрелками ▲ Вверх / СКМ / ▼ Вниз) — строго посередине!
        LinearLayout middleScrollCol = new LinearLayout(activity);
        middleScrollCol.setOrientation(LinearLayout.VERTICAL);

        final Handler scrollHandler = new Handler(Looper.getMainLooper());
        final boolean[] scrollUpHeld = {false};
        final boolean[] scrollDownHeld = {false};

        final Runnable repeatScrollUp = new Runnable() {
            @Override
            public void run() {
                if (!scrollUpHeld[0]) return;
                XServer xs = activity.getXServer();
                if (xs != null) {
                    xs.injectPointerButtonPress(Pointer.Button.BUTTON_SCROLL_UP);
                    xs.injectPointerButtonRelease(Pointer.Button.BUTTON_SCROLL_UP);
                }
                scrollHandler.postDelayed(this, 90);
            }
        };

        final Runnable repeatScrollDown = new Runnable() {
            @Override
            public void run() {
                if (!scrollDownHeld[0]) return;
                XServer xs = activity.getXServer();
                if (xs != null) {
                    xs.injectPointerButtonPress(Pointer.Button.BUTTON_SCROLL_DOWN);
                    xs.injectPointerButtonRelease(Pointer.Button.BUTTON_SCROLL_DOWN);
                }
                scrollHandler.postDelayed(this, 90);
            }
        };

        final Button btnScrollUp = createStyledButton(activity, "▲", UI_BTN_BG, UI_BTN_STROKE);
        btnScrollUp.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
        btnScrollUp.setPadding(0, 0, 0, 0);
        btnScrollUp.setMinHeight(dp(activity, 21));
        btnScrollUp.setMinimumHeight(dp(activity, 21));
        btnScrollUp.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                scrollUpHeld[0] = true;
                btnScrollUp.setBackground(roundedBg(UI_BTN_ACTIVE_BG, UI_ACCENT, 1, 6, activity));
                repeatScrollUp.run();
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                scrollUpHeld[0] = false;
                scrollHandler.removeCallbacks(repeatScrollUp);
                btnScrollUp.setBackground(roundedBg(UI_BTN_BG, UI_BTN_STROKE, 1, 6, activity));
            }
            return true;
        });
        middleScrollCol.addView(btnScrollUp, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 21)) {{
            bottomMargin = dp(activity, 2);
        }});

        final Button btnMmb = createStyledButton(activity, "СКМ", UI_BTN_BG, UI_BTN_STROKE);
        btnMmb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
        btnMmb.setPadding(0, 0, 0, 0);
        btnMmb.setMinHeight(dp(activity, 22));
        btnMmb.setMinimumHeight(dp(activity, 22));
        btnMmb.setOnTouchListener((v, event) -> {
            XServer xs = activity.getXServer();
            if (xs == null) return true;
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                btnMmb.setBackground(roundedBg(UI_BTN_ACTIVE_BG, UI_ACCENT, 1, 6, activity));
                xs.injectPointerButtonPress(Pointer.Button.BUTTON_MIDDLE);
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                btnMmb.setBackground(roundedBg(UI_BTN_BG, UI_BTN_STROKE, 1, 6, activity));
                xs.injectPointerButtonRelease(Pointer.Button.BUTTON_MIDDLE);
            }
            return true;
        });
        middleScrollCol.addView(btnMmb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 22)) {{
            bottomMargin = dp(activity, 2);
        }});

        final Button btnScrollDown = createStyledButton(activity, "▼", UI_BTN_BG, UI_BTN_STROKE);
        btnScrollDown.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
        btnScrollDown.setPadding(0, 0, 0, 0);
        btnScrollDown.setMinHeight(dp(activity, 21));
        btnScrollDown.setMinimumHeight(dp(activity, 21));
        btnScrollDown.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                scrollDownHeld[0] = true;
                btnScrollDown.setBackground(roundedBg(UI_BTN_ACTIVE_BG, UI_ACCENT, 1, 6, activity));
                repeatScrollDown.run();
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                scrollDownHeld[0] = false;
                scrollHandler.removeCallbacks(repeatScrollDown);
                btnScrollDown.setBackground(roundedBg(UI_BTN_BG, UI_BTN_STROKE, 1, 6, activity));
            }
            return true;
        });
        middleScrollCol.addView(btnScrollDown, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 21)));

        mouseButtonsRow.addView(middleScrollCol, new LinearLayout.LayoutParams(0, dp(activity, 68), 0.75f) {{
            rightMargin = dp(activity, 4);
        }});

        // 3. Правая кнопка мыши (ПКМ) — справа!
        final Button btnRmb = createStyledButton(activity, "ПКМ", UI_BTN_BG, UI_BTN_STROKE);
        btnRmb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        btnRmb.setMinHeight(dp(activity, 68));
        btnRmb.setMinimumHeight(dp(activity, 68));
        btnRmb.setOnTouchListener((v, event) -> {
            XServer xs = activity.getXServer();
            if (xs == null) return true;
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                btnRmb.setBackground(roundedBg(UI_BTN_ACTIVE_BG, UI_ACCENT, 1, 8, activity));
                xs.injectPointerButtonPress(Pointer.Button.BUTTON_RIGHT);
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                btnRmb.setBackground(roundedBg(UI_BTN_BG, UI_BTN_STROKE, 1, 8, activity));
                xs.injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT);
            }
            return true;
        });
        mouseButtonsRow.addView(btnRmb, new LinearLayout.LayoutParams(0, dp(activity, 68), 1.05f));

        widget.addView(mouseButtonsRow);
        rootView.addView(widget);
    }

    private static void setupLiveOverlayCustomizerPanel(
        final XServerDisplayActivity activity,
        final FrameLayout rootView,
        final LinearLayout panel,
        final InputControlsView inputControlsView,
        final TouchpadView touchpadView
    ) {
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(activity, 8), dp(activity, 5), dp(activity, 8), dp(activity, 5));
        panel.setBackground(roundedBg(UI_PANEL_BG, UI_PANEL_STROKE, 1, 14, activity));
        panel.setVisibility(View.GONE);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP | Gravity.START
        );
        lp.leftMargin = dp(activity, 16);
        lp.topMargin = Math.max(dp(activity, 40), AppUtils.getScreenHeight() - dp(activity, 130));
        panel.setLayoutParams(lp);

        // Верхняя строка панели оверлея: ручка для перетаскивания пальцем + Свернуть + Закрыть
        LinearLayout headerBar = new LinearLayout(activity);
        headerBar.setOrientation(LinearLayout.HORIZONTAL);
        headerBar.setGravity(Gravity.CENTER_VERTICAL);

        final TextView tvDragHandle = new TextView(activity);
        tvDragHandle.setText("⠿  Редактор кнопок");
        tvDragHandle.setTextColor(UI_TEXT_DIM);
        tvDragHandle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        tvDragHandle.setTypeface(Typeface.DEFAULT_BOLD);
        tvDragHandle.setPadding(dp(activity, 2), dp(activity, 2), dp(activity, 8), dp(activity, 2));
        headerBar.addView(tvDragHandle);
        makeViewDraggable(panel, tvDragHandle, rootView);

        final LinearLayout bodyContainer = new LinearLayout(activity);
        bodyContainer.setOrientation(LinearLayout.VERTICAL);

        final Button btnMinOverlay = createStyledButton(activity, "Свернуть", UI_BTN_BG, UI_BTN_STROKE);
        btnMinOverlay.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
        btnMinOverlay.setMinHeight(dp(activity, 22));
        btnMinOverlay.setMinimumHeight(dp(activity, 22));
        btnMinOverlay.setPadding(dp(activity, 6), dp(activity, 1), dp(activity, 6), dp(activity, 1));
        btnMinOverlay.setOnClickListener(v -> {
            if (bodyContainer.getVisibility() == View.VISIBLE) {
                bodyContainer.setVisibility(View.GONE);
                btnMinOverlay.setText("Развернуть");
            } else {
                bodyContainer.setVisibility(View.VISIBLE);
                btnMinOverlay.setText("Свернуть");
            }
        });
        headerBar.addView(btnMinOverlay, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT) {{
            rightMargin = dp(activity, 4);
        }});

        Button btnCloseOverlay = createStyledButton(activity, "✕", UI_BTN_BG, UI_BTN_STROKE);
        btnCloseOverlay.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
        btnCloseOverlay.setMinHeight(dp(activity, 22));
        btnCloseOverlay.setMinimumHeight(dp(activity, 22));
        btnCloseOverlay.setPadding(dp(activity, 6), dp(activity, 1), dp(activity, 6), dp(activity, 1));
        btnCloseOverlay.setOnClickListener(v -> {
            inputControlsView.setEditMode(false);
            if (inputControlsView.getProfile() != null) inputControlsView.getProfile().save();
            panel.setVisibility(View.GONE);
            inputControlsView.invalidate();
        });
        headerBar.addView(btnCloseOverlay, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(headerBar);

        // Ряд 1: Профили управления
        HorizontalScrollView hsvPresets = new HorizontalScrollView(activity);
        hsvPresets.setHorizontalScrollBarEnabled(false);
        LinearLayout rowPresets = new LinearLayout(activity);
        rowPresets.setOrientation(LinearLayout.HORIZONTAL);
        rowPresets.setPadding(0, dp(activity, 4), 0, dp(activity, 4));

        final InputControlsManager icm = new InputControlsManager(activity);
        final ArrayList<ControlsProfile> profiles = icm.getProfiles(false);

        for (final ControlsProfile prof : profiles) {
            Button bProf = createStyledButton(activity, prof.getName(), UI_BTN_BG, UI_BTN_STROKE);
            bProf.setOnClickListener(v -> {
                inputControlsView.setVisibility(View.VISIBLE);
                inputControlsView.setProfile(prof);
                inputControlsView.setShowTouchscreenControls(true);
                inputControlsView.invalidate();
                AppUtils.showToast(activity, "Профиль: " + prof.getName());
            });
            rowPresets.addView(bProf, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT) {{
                rightMargin = dp(activity, 5);
            }});
        }

        Button bHideControls = createStyledButton(activity, "Выкл. кнопки", UI_BTN_BG, UI_BTN_STROKE);
        bHideControls.setOnClickListener(v -> {
            inputControlsView.setEditMode(false);
            inputControlsView.setVisibility(View.GONE);
            inputControlsView.invalidate();
        });
        rowPresets.addView(bHideControls, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        hsvPresets.addView(rowPresets);
        bodyContainer.addView(hsvPresets);

        // Ряд 2: Компактные инструменты редактирования кнопок
        HorizontalScrollView hsvTools = new HorizontalScrollView(activity);
        hsvTools.setHorizontalScrollBarEnabled(false);
        LinearLayout rowTools = new LinearLayout(activity);
        rowTools.setOrientation(LinearLayout.HORIZONTAL);

        final Button btnToggleDrag = createStyledButton(activity, "Сдвиг: выкл", UI_BTN_BG, UI_BTN_STROKE);
        btnToggleDrag.setOnClickListener(v -> {
            if (inputControlsView.getProfile() == null && !profiles.isEmpty()) {
                inputControlsView.setProfile(profiles.get(0));
            }
            inputControlsView.setVisibility(View.VISIBLE);
            boolean nextEdit = !inputControlsView.isEditMode();
            inputControlsView.setEditMode(nextEdit);
            btnToggleDrag.setText(nextEdit ? "Сдвиг: вкл" : "Сдвиг: выкл");
            btnToggleDrag.setBackground(roundedBg(nextEdit ? UI_BTN_ACTIVE_BG : UI_BTN_BG, nextEdit ? UI_ACCENT : UI_BTN_STROKE, 1, 8, activity));
            inputControlsView.invalidate();
        });
        rowTools.addView(btnToggleDrag, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT) {{
            rightMargin = dp(activity, 5);
        }});

        Button btnBigger = createStyledButton(activity, "Размер +", UI_BTN_BG, UI_BTN_STROKE);
        btnBigger.setOnClickListener(v -> {
            ControlElement el = inputControlsView.getSelectedElement();
            if (el != null) {
                el.setScale(Math.min(2.5f, el.getScale() + 0.15f));
                if (inputControlsView.getProfile() != null) inputControlsView.getProfile().save();
                inputControlsView.invalidate();
            } else {
                AppUtils.showToast(activity, "Включите «Сдвиг» и нажмите на кнопку/стик");
            }
        });
        rowTools.addView(btnBigger, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT) {{
            rightMargin = dp(activity, 5);
        }});

        Button btnSmaller = createStyledButton(activity, "Размер −", UI_BTN_BG, UI_BTN_STROKE);
        btnSmaller.setOnClickListener(v -> {
            ControlElement el = inputControlsView.getSelectedElement();
            if (el != null) {
                el.setScale(Math.max(0.45f, el.getScale() - 0.15f));
                if (inputControlsView.getProfile() != null) inputControlsView.getProfile().save();
                inputControlsView.invalidate();
            }
        });
        rowTools.addView(btnSmaller, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT) {{
            rightMargin = dp(activity, 5);
        }});

        Button btnSwapType = createStyledButton(activity, "Тип", UI_BTN_BG, UI_BTN_STROKE);
        btnSwapType.setOnClickListener(v -> {
            final ControlElement el = inputControlsView.getSelectedElement();
            if (el == null) return;
            final String[] typeNames = {
                "🕹️ Аналоговый Стик (STICK)",
                "➕ Крестовина (D-PAD)",
                "🔘 Кнопка (BUTTON)",
                "🖱️ Тачпад (TRACKPAD)",
                "🎡 Радиальное Меню (RADIAL_MENU)",
                "🔢 Полоса 0–9 (RANGE_BUTTON)"
            };
            final ControlElement.Type[] types = {
                ControlElement.Type.STICK,
                ControlElement.Type.D_PAD,
                ControlElement.Type.BUTTON,
                ControlElement.Type.TRACKPAD,
                ControlElement.Type.RADIAL_MENU,
                ControlElement.Type.RANGE_BUTTON
            };
            new AlertDialog.Builder(activity)
                .setTitle("🔄 Выберите тип элемента")
                .setItems(typeNames, (d, which) -> {
                    el.setType(types[which]);
                    if (inputControlsView.getProfile() != null) inputControlsView.getProfile().save();
                    inputControlsView.invalidate();
                })
                .show();
        });
        rowTools.addView(btnSwapType, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT) {{
            rightMargin = dp(activity, 5);
        }});

        Button btnRebindKey = createStyledButton(activity, "Клавиша", UI_BTN_BG, UI_BTN_STROKE);
        btnRebindKey.setOnClickListener(v -> {
            final ControlElement el = inputControlsView.getSelectedElement();
            if (el == null) return;
            final Binding[] allBindings = Binding.values();
            String[] bNames = new String[allBindings.length];
            for (int i = 0; i < allBindings.length; i++) {
                bNames[i] = allBindings[i].toString() + " (" + allBindings[i].name() + ")";
            }
            new AlertDialog.Builder(activity)
                .setTitle("⌨️ Выберите клавишу / кнопку")
                .setItems(bNames, (d, which) -> {
                    el.setBindingAt(0, allBindings[which]);
                    if (inputControlsView.getProfile() != null) inputControlsView.getProfile().save();
                    inputControlsView.invalidate();
                })
                .show();
        });
        rowTools.addView(btnRebindKey, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT) {{
            rightMargin = dp(activity, 5);
        }});

        Button btnAddEl = createStyledButton(activity, "Добавить", UI_BTN_BG, UI_BTN_STROKE);
        btnAddEl.setOnClickListener(v -> {
            if (inputControlsView.getProfile() == null && !profiles.isEmpty()) {
                inputControlsView.setProfile(profiles.get(0));
            }
            inputControlsView.setEditMode(true);
            inputControlsView.addElement();
            inputControlsView.invalidate();
        });
        rowTools.addView(btnAddEl, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT) {{
            rightMargin = dp(activity, 5);
        }});

        Button btnDelEl = createStyledButton(activity, "Удалить", UI_BTN_BG, UI_BTN_STROKE);
        btnDelEl.setOnClickListener(v -> {
            inputControlsView.removeElement();
            inputControlsView.invalidate();
        });
        rowTools.addView(btnDelEl, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT) {{
            rightMargin = dp(activity, 5);
        }});

        Button btnOpacity = createStyledButton(activity, "Прозр.", UI_BTN_BG, UI_BTN_STROKE);
        btnOpacity.setOnClickListener(v -> {
            float cur = inputControlsView.getOverlayOpacity();
            float next = (cur >= 0.85f) ? 0.25f : (cur + 0.2f);
            inputControlsView.setOverlayOpacity(next);
            inputControlsView.invalidate();
        });
        rowTools.addView(btnOpacity, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        hsvTools.addView(rowTools);
        bodyContainer.addView(hsvTools);
        panel.addView(bodyContainer);
        rootView.addView(panel);
    }

    /**
     * Потокобезопасный монитор состояния Рабочего стола:
     * выполняет проверку окон и ОЗУ в фоновом потоке через неблокирующий tryLock,
     * чтобы главный UI-поток Android НИКОГДА не блокировался и не вызывал ANR!
     */
    private static void startLiveProcessAndFreezeWatchdog(
        final XServerDisplayActivity activity,
        final TextView tvWatchdog,
        final String targetExeName,
        final Container container
    ) {
        stopWatchdog();
        unityCrashShown = false;
        gameWindowSeen = false;
        gameMissingTicks = 0;
        final Handler handler = new Handler(Looper.getMainLooper());
        activeWatchdogHandler = handler;

        final Runnable checkTask = new Runnable() {
            @Override
            public void run() {
                if (activeWatchdogHandler != handler || activity == null || activity.isFinishing() || activity.isDestroyed()) {
                    return;
                }
                BG.execute(() -> {
                    if (activeWatchdogHandler != handler || activity.isFinishing() || activity.isDestroyed()) return;
                    int mappedWindows = 0;
                    final boolean[] crashSeen = {false, false}; // [0] окно сбоя найдено, [1] сканирование прошло
                    final boolean[] gameNow = {false};
                    final int[] gamePid = {0};
                    final String targetBase = gameWindowKey(targetExeName);
                    try {
                        XServer xs = activity.getXServer();
                        if (xs != null && xs.windowManager != null && xs.windowManager.rootWindow != null) {
                            XLock lock = xs.tryLock(5, XServer.Lockable.WINDOW_MANAGER);
                            if (lock != null) {
                                try (XLock ignored = lock) {
                                    crashSeen[1] = true;
                                    for (Window w : xs.windowManager.rootWindow.getChildren()) {
                                        if (w == null) continue;
                                        if (w.attributes.isMapped() && w.getWidth() > 32) mappedWindows++;
                                        // v12.6: окно UnityCrashHandler = игра Unity упала
                                        String cls = w.getClassName();
                                        String nm = w.getName();
                                        String probe = ((cls != null ? cls : "") + " " + (nm != null ? nm : "")).toLowerCase(Locale.ROOT);
                                        if (probe.contains("unitycrashhandler")) crashSeen[0] = true;
                                        else if (!targetBase.isEmpty() && cls != null && cls.toLowerCase(Locale.ROOT).contains(targetBase)) {
                                            gameNow[0] = true;
                                            if (gamePid[0] <= 0) gamePid[0] = w.getProcessId();
                                        }
                                    }
                                }
                            }
                        }
                    } catch (Throwable ignored) {}

                    final int finalWindows = mappedWindows;
                    final MemoryStatus mem = queryMemoryStatus(activity);
                    final long ramMb = mem.freeRamBytes / (1024L * 1024L);
                    handler.post(() -> {
                        if (activeWatchdogHandler != handler || activity.isFinishing() || activity.isDestroyed()) return;
                        String wdText = "⠿  " + targetExeName + " · " + String.format(Locale.US, "%.1f", ramMb / 1024f) + " ГБ своб.";
                        if (!wdText.contentEquals(tvWatchdog.getText())) tvWatchdog.setText(wdText);
                        tvWatchdog.setTextColor(UI_TEXT_DIM);
                        if (crashSeen[0] && !unityCrashShown) {
                            unityCrashShown = true;
                            gameWindowSeen = false;
                            handleUnityCrash(activity, container, targetExeName);
                        } else if (!crashSeen[0] && crashSeen[1] && unityCrashShown) {
                            unityCrashShown = false;
                        }
                        // v12.6.1: окно игры пропало (тихий вылет без окна UnityCrashHandler) — показываем причину из лога
                        if (gameNow[0]) {
                            if (!gameWindowSeen) applyFastCoresAffinity(activity, container, gamePid[0], targetExeName);
                            gameWindowSeen = true;
                            gameMissingTicks = 0;
                        } else if (gameWindowSeen && crashSeen[1] && !crashSeen[0]) {
                            gameMissingTicks++;
                            if (gameMissingTicks >= 2) {
                                gameWindowSeen = false;
                                gameMissingTicks = 0;
                                onGameWindowClosed(activity, container, targetExeName);
                            }
                        }
                    });
                });

                if (activeWatchdogHandler == handler) {
                    handler.postDelayed(this, 3000);
                }
            }
        };
        handler.postDelayed(checkTask, 2500);
    }
}
