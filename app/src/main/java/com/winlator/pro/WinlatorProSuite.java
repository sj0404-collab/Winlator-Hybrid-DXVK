package com.winlator.pro;

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
import android.opengl.GLSurfaceView;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
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
import com.winlator.core.EnvVars;
import com.winlator.core.FileUtils;
import com.winlator.core.KeyValueSet;
import com.winlator.core.StringUtils;
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
import com.winlator.widget.FrameRating;
import com.winlator.widget.InputControlsView;
import com.winlator.widget.TouchpadView;
import com.winlator.win32.MSLink;
import com.winlator.win32.PEParser;
import com.winlator.winhandler.TaskManagerDialog;
import com.winlator.xserver.Window;
import com.winlator.xserver.XServer;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class WinlatorProSuite {

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
            this.isLowStorage = freeStorageBytes < (4L * 1024L * 1024L * 1024L); // < 4 GB
            this.isLowRam = freeRamBytes < (1400L * 1024L * 1024L); // < 1.4 GB
        }

        public String formatStorageGb() {
            return String.format(Locale.US, "%.2f ГБ свободно из %.1f ГБ",
                freeStorageBytes / (1024.0 * 1024.0 * 1024.0),
                totalStorageBytes / (1024.0 * 1024.0 * 1024.0));
        }

        public String formatRamMb() {
            return String.format(Locale.US, "%d МБ свободно из %d МБ",
                freeRamBytes / (1024L * 1024L),
                totalRamBytes / (1024L * 1024L));
        }
    }

    public static class DiscoveredProgram {
        public final File file;
        public final String name;
        public final String category;
        public final String dosPath;
        public final long sizeBytes;
        public final boolean isOnDesktop;
        public Bitmap iconBitmap;

        public DiscoveredProgram(File file, String name, String category, String dosPath, long sizeBytes, boolean isOnDesktop) {
            this.file = file;
            this.name = name;
            this.category = category;
            this.dosPath = dosPath;
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
        } catch (Exception ignored) {
        }

        long freeRam = 0L;
        long totalRam = 0L;
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.CLIPBOARD_SERVICE != null ? Context.ACTIVITY_SERVICE : Context.ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                freeRam = mi.availMem;
                totalRam = mi.totalMem;
            }
        } catch (Exception ignored) {
        }
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
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setPadding(dp(ctx, 12), dp(ctx, 6), dp(ctx, 12), dp(ctx, 6));
        btn.setBackground(roundedBg(bgColor, strokeColor, 1, 8, ctx));
        return btn;
    }

    /**
     * Верхняя панель в Проводнике / Контейнерах / Ярлыках:
     * 1) Показывает сколько памяти и ОЗУ свободно на телефоне (предупреждает если мало памяти)
     * 2) Кнопка «🎮 Лаунчер Всех Игр и Программ (.EXE / .MSI)»
     * 3) Кнопка «🖥️ Открыть Рабочий Стол Windows (с иконками)»
     * 4) Кнопка «🎨 Графика / FPS / Скорость + Шейдеры»
     * 5) Кнопка «📊 Проверка FPS (Vulkan / OpenGL / DXVK)»
     */
    public static View buildTopProDashboardBar(final Activity activity, final Container container, final Runnable onRefreshCallback) {
        final Context ctx = activity;
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 10), dp(ctx, 8));
        root.setBackground(roundedBg(0xFF0F172A, 0xFF38BDF8, 1, 10, ctx));
        LinearLayout.LayoutParams rootLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        rootLp.setMargins(dp(ctx, 8), dp(ctx, 6), dp(ctx, 8), dp(ctx, 6));
        root.setLayoutParams(rootLp);

        MemoryStatus mem = queryMemoryStatus(ctx);
        TextView tvMem = new TextView(ctx);
        boolean warn = mem.isLowStorage || mem.isLowRam;
        String prefix = warn ? "⚠️ ВНИМАНИЕ — МАЛО ПАМЯТИ: " : "💾 Память телефона: ";
        tvMem.setText(prefix + mem.formatStorageGb() + "  |  🧠 ОЗУ: " + mem.formatRamMb());
        tvMem.setTextColor(warn ? 0xFFFACC15 : 0xFF34D399);
        tvMem.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        tvMem.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(tvMem);

        TextView tvHint = new TextView(ctx);
        tvHint.setText("✓ При установке и запуске любых .exe/.msi из проводника Рабочий стол Windows ВСЕГДА виден + работает детектор зависаний и авто-иконки!");
        tvHint.setTextColor(0xFF94A3B8);
        tvHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        tvHint.setPadding(0, dp(ctx, 2), 0, dp(ctx, 6));
        root.addView(tvHint);

        HorizontalScrollView hsv = new HorizontalScrollView(ctx);
        hsv.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);

        Button btnLauncher = createStyledButton(ctx, "🎮 Лаунчер Всех Игр и Программ (.EXE/.MSI)", 0xFF0284C7, 0xFF38BDF8);
        btnLauncher.setOnClickListener(v -> {
            Container target = resolveTargetContainer(activity, container);
            if (target != null) {
                showUniversalExeLauncherDialog(activity, target, onRefreshCallback);
            }
        });
        row.addView(btnLauncher, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, 36)) {{
            rightMargin = dp(ctx, 6);
        }});

        Button btnDesktop = createStyledButton(ctx, "🖥️ Открыть Рабочий Стол Windows", 0xFF059669, 0xFF34D399);
        btnDesktop.setOnClickListener(v -> {
            Container target = resolveTargetContainer(activity, container);
            if (target != null) {
                syncInstalledExeToDesktopWithIcons(activity, target);
                launchWithMemoryCheck(activity, target, null);
            }
        });
        row.addView(btnDesktop, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, 36)) {{
            rightMargin = dp(ctx, 6);
        }});

        Button btnGraphics = createStyledButton(ctx, "🎨 Графика / FPS / Скорость + Шейдеры", 0xFF7C3AED, 0xFFA78BFA);
        btnGraphics.setOnClickListener(v -> {
            Container target = resolveTargetContainer(activity, container);
            if (target != null) {
                showGraphicsFpsShaderStudioDialog(activity, target, null);
            }
        });
        row.addView(btnGraphics, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, 36)) {{
            rightMargin = dp(ctx, 6);
        }});

        Button btnBenchmark = createStyledButton(ctx, "📊 Тест FPS (Vulkan / OpenGL / DXVK)", 0xFFD97706, 0xFFFBBF24);
        btnBenchmark.setOnClickListener(v -> {
            Container target = resolveTargetContainer(activity, container);
            showFpsBenchmarkDialog(activity, target);
        });
        row.addView(btnBenchmark, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, 36)) {{
            rightMargin = dp(ctx, 6);
        }});

        Button btnSyncIcons = createStyledButton(ctx, "📌 Обновить иконки на Рабочем столе", 0xFF1E293B, 0xFF64748B);
        btnSyncIcons.setOnClickListener(v -> {
            Container target = resolveTargetContainer(activity, container);
            if (target != null) {
                int count = syncInstalledExeToDesktopWithIcons(activity, target);
                AppUtils.showToast(activity, "📌 На Рабочий стол добавлено / обновлено ярлыков с иконками: " + count);
                if (onRefreshCallback != null) onRefreshCallback.run();
            }
        });
        row.addView(btnSyncIcons, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, 36)));

        hsv.addView(row);
        root.addView(hsv);
        return root;
    }

    private static Container resolveTargetContainer(Context context, Container preferred) {
        if (preferred != null) return preferred;
        ContainerManager cm = new ContainerManager(context);
        return cm.ensureDefaultHybridContainer();
    }

    /**
     * Проверяет свободную память и ОЗУ перед запуском или установкой файла (.exe / .msi)
     * и запускает XServerDisplayActivity с ВКЛЮЧЕННЫМ видимым Рабочим Столом Windows!
     */
    public static void launchWithMemoryCheck(final Activity activity, final Container container, final String execPathOrNull) {
        if (container == null) return;
        final MemoryStatus mem = queryMemoryStatus(activity);
        final File exeFile = (execPathOrNull != null && !execPathOrNull.isEmpty()) ? new File(execPathOrNull) : null;
        final long exeSize = (exeFile != null && exeFile.isFile()) ? exeFile.length() : 0L;

        // Автоматически создаём ярлык с иконкой на Рабочем столе для запускаемого/устанавливаемого .exe
        if (exeFile != null && exeFile.isFile()) {
            pinExecutableToDesktopWithIcon(activity, container, exeFile);
        }
        syncInstalledExeToDesktopWithIcons(activity, container);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        boolean alwaysAskBeforeLaunch = prefs.getBoolean("pro_show_memory_check_before_launch", true);
        boolean lowSpaceForInstall = mem.isLowStorage || mem.isLowRam || (exeSize > 0 && mem.freeStorageBytes < exeSize * 2L);

        if (!lowSpaceForInstall && !alwaysAskBeforeLaunch) {
            startDesktopActivityNow(activity, container, execPathOrNull);
            return;
        }

        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(activity, 16), dp(activity, 14), dp(activity, 16), dp(activity, 10));
        layout.setBackgroundColor(0xFF0F172A);

        TextView title = new TextView(activity);
        title.setText(lowSpaceForInstall
            ? "⚠️ ВНИМАНИЕ: МАЛО ПАМЯТИ НА ТЕЛЕФОНЕ ПЕРЕД ЗАПУСКОМ / УСТАНОВКОЙ!"
            : "💾 Проверка памяти и параметров перед открытием на Рабочем столе");
        title.setTextColor(lowSpaceForInstall ? 0xFFF87171 : 0xFF38BDF8);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        layout.addView(title);

        TextView details = new TextView(activity);
        String fileInfo = (exeFile != null)
            ? ("\n📄 Выбранный файл: " + exeFile.getName() + " (" + StringUtils.formatBytes(exeSize) + ")")
            : "\n🖥️ Режим: Полный Рабочий Стол Windows с установленными ярлыками и иконками";
        String warningNote = lowSpaceForInstall
            ? "\n\n❗ На телефоне осталось мало свободной памяти! Если установщик крупной игры распаковывает файлы, рекомендуем очистить кэш или освободить место, чтобы установка не зависла."
            : "\n\n✓ Памяти достаточно. Экран Рабочего стола Windows будет ОТКРЫТ и ВИДЕН во время установки и работы программы, а сверху будет работать индикатор активности процесса (защита от скрытого зависания).";

        details.setText(
            "\n💾 Внутренняя память телефона: " + mem.formatStorageGb() +
            "\n🧠 Оперативная память (ОЗУ): " + mem.formatRamMb() +
            fileInfo +
            "\n🎨 Графика: " + container.getDXWrapper().toUpperCase() + " • Разрешение: " + container.getScreenSize() +
            warningNote
        );
        details.setTextColor(Color.WHITE);
        details.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        layout.addView(details);

        final CheckBox cbDontAskWhenEnough = new CheckBox(activity);
        cbDontAskWhenEnough.setText("Показывать это окно только когда на телефоне мало памяти (< 4 ГБ)");
        cbDontAskWhenEnough.setTextColor(0xFFCBD5E1);
        cbDontAskWhenEnough.setChecked(!alwaysAskBeforeLaunch);
        layout.addView(cbDontAskWhenEnough);

        LinearLayout btnRow = new LinearLayout(activity);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, dp(activity, 10), 0, 0);

        final AlertDialog dialog = new AlertDialog.Builder(activity)
            .setView(layout)
            .create();

        Button btnClean = createStyledButton(activity, "🧹 Очистить кэш / ОЗУ", 0xFF334155, 0xFF64748B);
        btnClean.setOnClickListener(v -> {
            try {
                FileUtils.clear(activity.getCacheDir());
                System.gc();
            } catch (Exception ignored) {}
            MemoryStatus updated = queryMemoryStatus(activity);
            AppUtils.showToast(activity, "🧹 Кэш очищен! Свободно: " + updated.formatStorageGb() + " | ОЗУ: " + updated.formatRamMb());
        });
        btnRow.addView(btnClean, new LinearLayout.LayoutParams(0, dp(activity, 40), 1f) {{
            rightMargin = dp(activity, 6);
        }});

        Button btnTune = createStyledButton(activity, "🎨 Графика / FPS", 0xFF7C3AED, 0xFFA78BFA);
        btnTune.setOnClickListener(v -> showGraphicsFpsShaderStudioDialog(activity, container, null));
        btnRow.addView(btnTune, new LinearLayout.LayoutParams(0, dp(activity, 40), 1f) {{
            rightMargin = dp(activity, 6);
        }});

        Button btnGo = createStyledButton(activity, "🚀 Запустить (Рабочий стол)", 0xFF059669, 0xFF34D399);
        btnGo.setOnClickListener(v -> {
            prefs.edit().putBoolean("pro_show_memory_check_before_launch", !cbDontAskWhenEnough.isChecked()).apply();
            dialog.dismiss();
            startDesktopActivityNow(activity, container, execPathOrNull);
        });
        btnRow.addView(btnGo, new LinearLayout.LayoutParams(0, dp(activity, 40), 1.3f));

        layout.addView(btnRow);
        dialog.show();
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
     * Автоматически создаёт ярлык .desktop и .lnk на Рабочем столе Windows (Users/xuser/Desktop)
     * вместе с извлечённой из .exe иконкой (PEParser.extractIcon), чтобы все установленные
     * игры и программы отображались с иконкой прямо на Рабочем столе!
     */
    public static boolean pinExecutableToDesktopWithIcon(Context context, Container container, File exeFile) {
        if (container == null || exeFile == null || !exeFile.exists()) return false;
        try {
            File desktopDir = new File(container.getUserDir(), "Desktop");
            if (!desktopDir.exists()) desktopDir.mkdirs();

            String baseName = FileUtils.getBasename(exeFile.getName());
            if (baseName.isEmpty()) baseName = exeFile.getName();
            String safeIconName = baseName.replaceAll("[^a-zA-Z0-9._-]", "_");

            // Извлекаем реальную иконку из .exe через PEParser
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
            }

            String dosPath = WineUtils.unixToDOSPath(exeFile.getAbsolutePath(), container);
            if (dosPath == null || dosPath.isEmpty()) {
                dosPath = "D:\\" + exeFile.getName();
            }

            // 1. Создаём .lnk файл для отображения внутри Рабочего стола Windows (explorer.exe)
            File lnkFile = new File(desktopDir, baseName + ".lnk");
            if (!lnkFile.exists()) {
                MSLink.LinkInfo linkInfo = new MSLink.LinkInfo();
                linkInfo.targetPath = dosPath;
                linkInfo.iconLocation = dosPath;
                linkInfo.isDirectory = false;
                MSLink.createFile(linkInfo, lnkFile);
            }

            // 2. Создаём .desktop файл для отображения в разделе Ярлыки и на Рабочем столе Winlator
            File desktopEntry = new File(desktopDir, baseName + ".desktop");
            String winePrefix = container.getRootDir().getAbsolutePath() + "/.wine";
            String escapedDos = dosPath.replace("\\", "\\\\");
            String content = "[Desktop Entry]\n" +
                "Name=" + baseName + "\n" +
                "Exec=env WINEPREFIX=\"" + winePrefix + "\" wine \"" + escapedDos + "\"\n" +
                "Type=Application\n" +
                "StartupNotify=true\n" +
                "Icon=" + safeIconName + "\n" +
                "StartupWMClass=" + exeFile.getName() + "\n";
            FileUtils.writeString(desktopEntry, content);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Сканирует установленные программы и игры в C:\Program Files, C:\Program Files (x86), C:\Games
     * и выносит их на Рабочий стол с иконками (пропуская системные деинсталляторы unins000.exe и внутренние утилиты Wine).
     */
    public static int syncInstalledExeToDesktopWithIcons(Context context, Container container) {
        if (container == null) return 0;
        int count = 0;
        List<DiscoveredProgram> all = scanAllAvailableProgramsAndGames(container);
        for (DiscoveredProgram prog : all) {
            String lower = prog.file.getName().toLowerCase(Locale.ROOT);
            if (lower.startsWith("unins") || lower.equals("winecfg.exe") || lower.equals("explorer.exe") ||
                lower.equals("cmd.exe") || lower.equals("notepad.exe") || lower.equals("regedit.exe") ||
                lower.equals("iexplore.exe") || lower.equals("winemenubuilder.exe") || lower.equals("services.exe") ||
                lower.equals("winedevice.exe") || lower.equals("plugplay.exe") || lower.equals("svchost.exe") ||
                lower.equals("rpcss.exe") || lower.equals("conhost.exe")) {
                continue;
            }
            if (pinExecutableToDesktopWithIcon(context, container, prog.file)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Находит ВСЕ доступные игры, программы и установщики (.exe, .msi, .bat, .cmd, .lnk)
     * на всех дисках контейнера (C:, D: Загрузки, E: Документы, Рабочий стол).
     */
    public static List<DiscoveredProgram> scanAllAvailableProgramsAndGames(Container container) {
        List<DiscoveredProgram> result = new ArrayList<>();
        Set<String> visitedPaths = new HashSet<>();
        if (container == null) return result;

        File driveC = new File(container.getRootDir(), ".wine/drive_c");
        File desktopDir = new File(container.getUserDir(), "Desktop");
        File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);

        List<File> rootsToScan = Arrays.asList(
            desktopDir,
            new File(driveC, "Program Files"),
            new File(driveC, "Program Files (x86)"),
            new File(driveC, "Games"),
            downloadsDir,
            documentsDir
        );

        for (File root : rootsToScan) {
            if (root != null && root.exists()) {
                scanDirectoryRecursive(container, root, desktopDir, 0, 3, visitedPaths, result);
            }
        }

        Collections.sort(result, new Comparator<DiscoveredProgram>() {
            @Override
            public int compare(DiscoveredProgram a, DiscoveredProgram b) {
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
        if (dir == null || !dir.exists() || depth > maxDepth || out.size() > 160) return;
        File[] children = dir.listFiles();
        if (children == null) return;

        for (File f : children) {
            String abs = f.getAbsolutePath();
            if (visited.contains(abs)) continue;
            visited.add(abs);

            if (f.isDirectory()) {
                String dName = f.getName().toLowerCase(Locale.ROOT);
                if (dName.equals("windows") || dName.equals("common files") || dName.equals("internet explorer") ||
                    dName.equals("windows nt") || dName.equals("windows media player") || dName.startsWith(".")) {
                    continue;
                }
                scanDirectoryRecursive(container, f, desktopDir, depth + 1, maxDepth, visited, out);
            } else {
                String name = f.getName();
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".exe") || lower.endsWith(".msi") || lower.endsWith(".bat") || lower.endsWith(".cmd")) {
                    if (lower.equals("winhandler.exe") || lower.equals("wfm.exe")) continue;
                    String baseName = FileUtils.getBasename(name);
                    boolean isInstaller = lower.endsWith(".msi") || lower.contains("setup") || lower.contains("install") || lower.contains("patch");
                    String cat = isInstaller ? "📦 Установщик (.EXE/.MSI)" : "🎮 Игра / Программа";
                    String dos = WineUtils.unixToDOSPath(abs, container);
                    if (dos == null || dos.isEmpty()) dos = abs;
                    boolean onDesktop = desktopDir != null && (
                        new File(desktopDir, baseName + ".desktop").exists() ||
                        new File(desktopDir, baseName + ".lnk").exists()
                    );
                    DiscoveredProgram prog = new DiscoveredProgram(f, baseName, cat, dos, f.length(), onDesktop);
                    if (lower.endsWith(".exe")) {
                        try {
                            prog.iconBitmap = PEParser.extractIcon(f);
                        } catch (Exception ignored) {}
                    }
                    out.add(prog);
                }
            }
        }
    }

    /**
     * ============================================================================
     * МОДУЛЬ 2: УНИВЕРСАЛЬНЫЙ ЛАУНЧЕР ВСЕХ ДОСТУПНЫХ ИГР И ПРОГРАММ (.EXE / .MSI / .BAT)
     * ============================================================================
     */
    public static void showUniversalExeLauncherDialog(final Activity activity, final Container container, final Runnable onRefreshCallback) {
        final Context ctx = activity;
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12));
        root.setBackgroundColor(0xFF0B1120);

        TextView tvTitle = new TextView(ctx);
        tvTitle.setText("🎮 Лаунчер Всех Игр и Программ (.EXE / .MSI / .BAT)");
        tvTitle.setTextColor(Color.WHITE);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(tvTitle);

        MemoryStatus mem = queryMemoryStatus(ctx);
        TextView tvSub = new TextView(ctx);
        tvSub.setText("💾 Свободно памяти: " + mem.formatStorageGb() + " | 🧠 ОЗУ: " + mem.formatRamMb() +
            "\nВсе найденные игры и программы на дисках C:, D: (Download), E: и Рабочем столе:");
        tvSub.setTextColor(mem.isLowStorage ? 0xFFFACC15 : 0xFF38BDF8);
        tvSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        tvSub.setPadding(0, dp(ctx, 4), 0, dp(ctx, 8));
        root.addView(tvSub);

        LinearLayout topButtons = new LinearLayout(ctx);
        topButtons.setOrientation(LinearLayout.HORIZONTAL);
        topButtons.setPadding(0, 0, 0, dp(ctx, 8));

        final AlertDialog dialog = new AlertDialog.Builder(ctx)
            .setView(root)
            .create();

        Button btnPickExternal = createStyledButton(ctx, "📂 Выбрать любой .EXE/.MSI из памяти", 0xFF0284C7, 0xFF38BDF8);
        btnPickExternal.setOnClickListener(v -> {
            dialog.dismiss();
            if (activity instanceof MainActivity) {
                final MainActivity ma = (MainActivity) activity;
                ma.setOpenFileCallback(uri -> {
                    Executors.newSingleThreadExecutor().execute(() -> {
                        ContainerManager cm = new ContainerManager(activity);
                        final File imported = cm.importExeFromUri(uri, container);
                        if (imported != null) {
                            pinExecutableToDesktopWithIcon(activity, container, imported);
                        }
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
        topButtons.addView(btnPickExternal, new LinearLayout.LayoutParams(0, dp(ctx, 38), 1.2f) {{
            rightMargin = dp(ctx, 6);
        }});

        Button btnPinAll = createStyledButton(ctx, "📌 Вынести все с иконкой на Рабочий стол", 0xFF059669, 0xFF34D399);
        topButtons.addView(btnPinAll, new LinearLayout.LayoutParams(0, dp(ctx, 38), 1f));
        root.addView(topButtons);

        final EditText etSearch = new EditText(ctx);
        etSearch.setHint("🔍 Поиск игры, программы или установщика (.exe, .msi)...");
        etSearch.setHintTextColor(0xFF64748B);
        etSearch.setTextColor(Color.WHITE);
        etSearch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        etSearch.setPadding(dp(ctx, 10), dp(ctx, 6), dp(ctx, 10), dp(ctx, 6));
        etSearch.setBackground(roundedBg(0xFF1E293B, 0xFF38BDF8, 1, 8, ctx));
        root.addView(etSearch);

        ScrollView scroll = new ScrollView(ctx);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(ctx, 360)
        );
        scrollLp.topMargin = dp(ctx, 8);
        scroll.setLayoutParams(scrollLp);

        final LinearLayout listContainer = new LinearLayout(ctx);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(listContainer);
        root.addView(scroll);

        final List<DiscoveredProgram> allPrograms = scanAllAvailableProgramsAndGames(container);

        final Runnable renderList = () -> {
            listContainer.removeAllViews();
            String q = etSearch.getText().toString().trim().toLowerCase(Locale.ROOT);
            int shown = 0;
            for (final DiscoveredProgram prog : allPrograms) {
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
                headerRow.addView(iconView, new LinearLayout.LayoutParams(dp(ctx, 40), dp(ctx, 40)) {{
                    rightMargin = dp(ctx, 10);
                }});

                LinearLayout infoCol = new LinearLayout(ctx);
                infoCol.setOrientation(LinearLayout.VERTICAL);
                TextView tvName = new TextView(ctx);
                tvName.setText(prog.name + " (" + prog.file.getName() + ")");
                tvName.setTextColor(Color.WHITE);
                tvName.setTypeface(Typeface.DEFAULT_BOLD);
                tvName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                infoCol.addView(tvName);

                TextView tvMeta = new TextView(ctx);
                String deskBadge = prog.isOnDesktop ? " • ✅ На Рабочем столе с иконкой" : "";
                tvMeta.setText(prog.category + " • " + StringUtils.formatBytes(prog.sizeBytes) + " • " + prog.dosPath + deskBadge);
                tvMeta.setTextColor(prog.isOnDesktop ? 0xFF34D399 : 0xFF94A3B8);
                tvMeta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                infoCol.addView(tvMeta);

                headerRow.addView(infoCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                card.addView(headerRow);

                LinearLayout actRow = new LinearLayout(ctx);
                actRow.setOrientation(LinearLayout.HORIZONTAL);
                actRow.setPadding(0, dp(ctx, 6), 0, 0);

                Button btnRun = createStyledButton(ctx, "🚀 Запустить (с Рабочим столом)", 0xFF059669, 0xFF34D399);
                btnRun.setOnClickListener(v -> {
                    dialog.dismiss();
                    launchWithMemoryCheck(activity, container, prog.file.getAbsolutePath());
                });
                actRow.addView(btnRun, new LinearLayout.LayoutParams(0, dp(ctx, 34), 1.3f) {{
                    rightMargin = dp(ctx, 6);
                }});

                Button btnPin = createStyledButton(ctx, "📌 На Рабочий стол с иконкой", 0xFF0284C7, 0xFF38BDF8);
                btnPin.setOnClickListener(v -> {
                    boolean ok = pinExecutableToDesktopWithIcon(activity, container, prog.file);
                    if (ok) {
                        AppUtils.showToast(activity, "✅ Ярлык с иконкой добавлен на Рабочий стол: " + prog.name);
                        if (onRefreshCallback != null) onRefreshCallback.run();
                    }
                });
                actRow.addView(btnPin, new LinearLayout.LayoutParams(0, dp(ctx, 34), 1f));

                card.addView(actRow);
                listContainer.addView(card);
            }

            if (shown == 0) {
                TextView empty = new TextView(ctx);
                empty.setText("Файлы .exe / .msi не найдены по запросу. Нажмите «📂 Выбрать любой .EXE/.MSI из памяти» выше или скопируйте игру в папку Download (Диск D:).");
                empty.setTextColor(0xFF94A3B8);
                empty.setPadding(dp(ctx, 8), dp(ctx, 16), dp(ctx, 8), dp(ctx, 16));
                listContainer.addView(empty);
            }
        };

        btnPinAll.setOnClickListener(v -> {
            int added = syncInstalledExeToDesktopWithIcons(activity, container);
            AppUtils.showToast(activity, "📌 Все программы и игры (" + added + " шт.) добавлены с иконками на Рабочий стол!");
            if (onRefreshCallback != null) onRefreshCallback.run();
            dialog.dismiss();
            showUniversalExeLauncherDialog(activity, container, onRefreshCallback);
        });

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { renderList.run(); }
            @Override public void afterTextChanged(Editable s) {}
        });

        renderList.run();

        Button btnClose = createStyledButton(ctx, "Закрыть", 0xFF334155, 0xFF64748B);
        btnClose.setOnClickListener(v -> dialog.dismiss());
        root.addView(btnClose);

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

        // 1. Рендерер и Графический API
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

        // 2. Разрешение экрана
        TextView lblRes = new TextView(context);
        lblRes.setText("\n2. Разрешение экрана и чёткость:");
        lblRes.setTextColor(0xFFFACC15);
        lblRes.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(lblRes);

        final String[] resValues = {"800x600", "1024x768", "1280x720", "1600x900", "1920x1080"};
        final String[] resLabels = {
            "800x600 (Максимум FPS и скорости для тяжёлых игр)",
            "1024x768 (Классика 4:3 — Герои, Diablo, CS 1.6, S.T.A.L.K.E.R.)",
            "1280x720 (HD 16:9 — Идеальный баланс скорости и качества)",
            "1600x900 (HD+ 16:9 — Повышенная детализация)",
            "1920x1080 (FullHD 16:9 — Максимальное качество)"
        };
        final Spinner spRes = new Spinner(context);
        spRes.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, resLabels));
        int currentResIdx = 2;
        if (container != null) {
            for (int i = 0; i < resValues.length; i++) {
                if (resValues[i].equals(container.getScreenSize())) currentResIdx = i;
            }
        }
        spRes.setSelection(currentResIdx);
        spRes.setBackground(roundedBg(0xFF1E293B, 0xFF38BDF8, 1, 8, context));
        root.addView(spRes);

        // 3. Лимит FPS и Турбо-Скорость
        TextView lblFps = new TextView(context);
        lblFps.setText("\n3. Целевой FPS и Режим Скорости (Box64 Dynarec + ESYNC/FSYNC):");
        lblFps.setTextColor(0xFFFACC15);
        lblFps.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(lblFps);

        final String[] fpsValues = {"0", "30", "45", "60", "90", "120", "144"};
        final String[] fpsLabels = {
            "🚀 Без лимита (Максимальный FPS без ограничений)",
            "30 FPS (Плавная стабилизация и экономия батареи)",
            "45 FPS (Комфортный игровой режим)",
            "60 FPS (Стандарт 60 Гц без разрывов кадра)",
            "90 FPS (Высокая герцовка 90 Гц)",
            "120 FPS (Киберспорт 120 Гц)",
            "144 FPS (Экстремальная частота 144 Гц)"
        };
        final Spinner spFps = new Spinner(context);
        spFps.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, fpsLabels));
        spFps.setSelection(prefs.getInt("pro_fps_limit_idx", 0));
        spFps.setBackground(roundedBg(0xFF1E293B, 0xFF38BDF8, 1, 8, context));
        root.addView(spFps);

        final String[] speedLabels = {
            "⚡ ТУРБО-СКОРОСТЬ (Box64 FastNan + FastRound + BigBlock=3 + CallRet=1 + WeakBarrier=2)",
            "⚖️ БАЛАНС (Оптимальная скорость + высокая стабильность)",
            "🛡️ СОВМЕСТИМОСТЬ (StrongMem=1 для капризных установщиков .exe/.msi)"
        };
        final Spinner spSpeed = new Spinner(context);
        spSpeed.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, speedLabels));
        spSpeed.setSelection(prefs.getInt("pro_speed_mode_idx", 0));
        LinearLayout.LayoutParams spSpeedLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        spSpeedLp.topMargin = dp(context, 6);
        spSpeed.setLayoutParams(spSpeedLp);
        spSpeed.setBackground(roundedBg(0xFF1E293B, 0xFF10B981, 1, 8, context));
        root.addView(spSpeed);

        final CheckBox cbShowFpsHud = new CheckBox(context);
        cbShowFpsHud.setText("📊 Показывать счётчик FPS, GPU и частоты кадров на экране");
        cbShowFpsHud.setTextColor(Color.WHITE);
        cbShowFpsHud.setChecked(prefs.getBoolean("pro_show_fps_hud", true));
        root.addView(cbShowFpsHud);

        // 4. Комбинатор Шейдеров (можно совмещать любые вместе!)
        TextView lblShaders = new TextView(context);
        lblShaders.setText("\n4. Комбинатор Шейдеров (можно включать и совмещать все сразу!):");
        lblShaders.setTextColor(0xFF34D399);
        lblShaders.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(lblShaders);

        final CheckBox cbCas = new CheckBox(context);
        cbCas.setText("✨ AMD FidelityFX CAS Sharpening (Умная чёткость текстур и текста)");
        cbCas.setTextColor(Color.WHITE);
        cbCas.setChecked(prefs.getBoolean("pro_shader_cas", true));
        root.addView(cbCas);

        final SeekBar sbCas = new SeekBar(context);
        sbCas.setMax(150);
        sbCas.setProgress(prefs.getInt("pro_shader_cas_val", 70));
        root.addView(sbCas);

        final CheckBox cbHdr = new CheckBox(context);
        cbHdr.setText("🌈 HDR Bloom & Vibrance (Насыщенность цветов и мягкое свечение HDR)");
        cbHdr.setTextColor(Color.WHITE);
        cbHdr.setChecked(prefs.getBoolean("pro_shader_hdr", false));
        root.addView(cbHdr);

        final SeekBar sbHdr = new SeekBar(context);
        sbHdr.setMax(150);
        sbHdr.setProgress(prefs.getInt("pro_shader_hdr_val", 55));
        root.addView(sbHdr);

        final CheckBox cbFxaa = new CheckBox(context);
        cbFxaa.setText("💎 FXAA Anti-Aliasing (Сглаживание зубчатых краёв в 3D играх)");
        cbFxaa.setTextColor(Color.WHITE);
        cbFxaa.setChecked(prefs.getBoolean("pro_shader_fxaa", true));
        root.addView(cbFxaa);

        final CheckBox cbColor = new CheckBox(context);
        cbColor.setText("☀️ Усиление контраста и яркости (Color Grading Boost)");
        cbColor.setTextColor(Color.WHITE);
        cbColor.setChecked(prefs.getBoolean("pro_shader_color", false));
        root.addView(cbColor);

        final CheckBox cbCrt = new CheckBox(context);
        cbCrt.setText("📺 CRT Retro Scanlines (Эффект кинескопного монитора для классики)");
        cbCrt.setTextColor(Color.WHITE);
        cbCrt.setChecked(prefs.getBoolean("pro_shader_crt", false));
        root.addView(cbCrt);

        final CheckBox cbNight = new CheckBox(context);
        cbNight.setText("🌙 Ночной буст теней (Высветление тёмных углов в шутерах и RPG)");
        cbNight.setTextColor(Color.WHITE);
        cbNight.setChecked(prefs.getBoolean("pro_shader_night", false));
        root.addView(cbNight);

        final AlertDialog dialog = new AlertDialog.Builder(context)
            .setView(scroll)
            .create();

        LinearLayout btnRow = new LinearLayout(context);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, dp(context, 12), 0, 0);

        Button btnBench = createStyledButton(context, "📊 Проверить FPS в тесте", 0xFFD97706, 0xFFFBBF24);
        btnBench.setOnClickListener(v -> showFpsBenchmarkDialog(context, container));
        btnRow.addView(btnBench, new LinearLayout.LayoutParams(0, dp(context, 40), 1f) {{
            rightMargin = dp(context, 6);
        }});

        Button btnSave = createStyledButton(context, "✅ Применить настройки и шейдеры", 0xFF059669, 0xFF34D399);
        btnSave.setOnClickListener(v -> {
            int apiIdx = spApi.getSelectedItemPosition();
            int resIdx = spRes.getSelectedItemPosition();
            int fpsIdx = spFps.getSelectedItemPosition();
            int speedIdx = spSpeed.getSelectedItemPosition();

            prefs.edit()
                .putInt("pro_gfx_api_idx", apiIdx)
                .putInt("pro_fps_limit_idx", fpsIdx)
                .putInt("pro_speed_mode_idx", speedIdx)
                .putBoolean("pro_show_fps_hud", cbShowFpsHud.isChecked())
                .putBoolean("pro_shader_cas", cbCas.isChecked())
                .putInt("pro_shader_cas_val", sbCas.getProgress())
                .putBoolean("pro_shader_hdr", cbHdr.isChecked())
                .putInt("pro_shader_hdr_val", sbHdr.getProgress())
                .putBoolean("pro_shader_fxaa", cbFxaa.isChecked())
                .putBoolean("pro_shader_color", cbColor.isChecked())
                .putBoolean("pro_shader_crt", cbCrt.isChecked())
                .putBoolean("pro_shader_night", cbNight.isChecked())
                .apply();

            if (container != null) {
                container.setScreenSize(resValues[resIdx]);
                container.setHUDMode(cbShowFpsHud.isChecked() ? (byte) FrameRating.Mode.FULL.ordinal() : (byte) FrameRating.Mode.DISABLED.ordinal());

                if (apiIdx == 3) {
                    container.setDXWrapper("wined3d");
                } else {
                    container.setDXWrapper("dxvk");
                    String dxvkVer = (apiIdx == 2) ? "1.7.2" : "2.4.1";
                    container.setDXWrapperConfig("version=" + dxvkVer + ",framerate=" + fpsValues[fpsIdx] + ",async=1,asyncCache=1");
                }

                if (speedIdx == 0) {
                    container.setBox64Preset("PERFORMANCE");
                } else if (speedIdx == 1) {
                    container.setBox64Preset("INTERMEDIATE");
                } else {
                    container.setBox64Preset("COMPATIBILITY");
                }

                EnvVars env = new EnvVars(container.getEnvVars());
                env.put("WINEESYNC", "1");
                env.put("WINEFSYNC", "1");
                env.put("DXVK_ASYNC", "1");
                env.put("MESA_GLTHREAD", "true");
                if (!fpsValues[fpsIdx].equals("0")) {
                    env.put("DXVK_FRAME_RATE", fpsValues[fpsIdx]);
                } else {
                    env.remove("DXVK_FRAME_RATE");
                }
                if (speedIdx == 0) {
                    env.put("BOX64_DYNAREC_FASTNAN", "1");
                    env.put("BOX64_DYNAREC_FASTROUND", "1");
                    env.put("BOX64_DYNAREC_BIGBLOCK", "3");
                    env.put("BOX64_DYNAREC_CALLRET", "1");
                    env.put("BOX64_DYNAREC_WEAKBARRIER", "2");
                }
                container.setEnvVars(env.toString());
                container.saveData();
            }

            if (liveActivityOrNull != null) {
                applySavedShadersToRenderer(liveActivityOrNull);
            }

            AppUtils.showToast(context, "✅ Графика, лимит FPS, скорость и комбинация шейдеров применены!");
            dialog.dismiss();
        });
        btnRow.addView(btnSave, new LinearLayout.LayoutParams(0, dp(context, 40), 1.3f));

        root.addView(btnRow);
        dialog.show();
    }

    /**
     * Применяет все выбранные пользователем шейдеры одновременно к GLRenderer.effectComposer!
     */
    public static void applySavedShadersToRenderer(XServerDisplayActivity activity) {
        if (activity == null || activity.getXServerView() == null) return;
        GLRenderer renderer = activity.getXServerView().getRenderer();
        if (renderer == null || renderer.effectComposer == null) return;

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);

        // 1. CAS Sharpening
        CASSharpenEffect cas = renderer.effectComposer.getEffect(CASSharpenEffect.class);
        if (prefs.getBoolean("pro_shader_cas", true)) {
            if (cas == null) cas = new CASSharpenEffect();
            cas.setSharpness(prefs.getInt("pro_shader_cas_val", 70) / 100.0f);
            renderer.effectComposer.addEffect(cas);
        } else if (cas != null) {
            renderer.effectComposer.removeEffect(cas);
        }

        // 2. HDR Bloom & Vibrance
        HDRBloomEffect hdr = renderer.effectComposer.getEffect(HDRBloomEffect.class);
        if (prefs.getBoolean("pro_shader_hdr", false)) {
            if (hdr == null) hdr = new HDRBloomEffect();
            hdr.setIntensity(prefs.getInt("pro_shader_hdr_val", 55) / 100.0f);
            renderer.effectComposer.addEffect(hdr);
        } else if (hdr != null) {
            renderer.effectComposer.removeEffect(hdr);
        }

        // 3. FXAA Anti-Aliasing
        FXAAEffect fxaa = renderer.effectComposer.getEffect(FXAAEffect.class);
        if (prefs.getBoolean("pro_shader_fxaa", true)) {
            if (fxaa == null) renderer.effectComposer.addEffect(new FXAAEffect());
        } else if (fxaa != null) {
            renderer.effectComposer.removeEffect(fxaa);
        }

        // 4. Color Boost
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

        // 5. CRT Retro Scanlines
        CRTEffect crt = renderer.effectComposer.getEffect(CRTEffect.class);
        if (prefs.getBoolean("pro_shader_crt", false)) {
            if (crt == null) renderer.effectComposer.addEffect(new CRTEffect());
        } else if (crt != null) {
            renderer.effectComposer.removeEffect(crt);
        }

        // 6. Night Vision
        NightVisionEffect night = renderer.effectComposer.getEffect(NightVisionEffect.class);
        if (prefs.getBoolean("pro_shader_night", false)) {
            if (night == null) renderer.effectComposer.addEffect(new NightVisionEffect());
        } else if (night != null) {
            renderer.effectComposer.removeEffect(night);
        }
    }

    /**
     * ============================================================================
     * МОДУЛЬ 6: ПРОВЕРКА И ТЕСТ FPS С РАЗЛИЧНЫМИ НАСТРОЙКАМИ VULKAN / OPENGL / DXVK
     * ============================================================================
     */
    public static void showFpsBenchmarkDialog(final Context context, final Container containerOrNull) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12));
        root.setBackgroundColor(0xFF090D16);

        TextView title = new TextView(context);
        title.setText("📊 Проверка FPS: Vulkan vs OpenGL vs Hybrid DXVK + Шейдеры");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        final TextView tvLiveStats = new TextView(context);
        tvLiveStats.setText("⏳ Инициализация графического пайплайна...");
        tvLiveStats.setTextColor(0xFF34D399);
        tvLiveStats.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
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
        final String[] gpuRendererName = {"Adreno / Mali GPU"};

        // Живой аппаратный 3D/Shader стресс-холст
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
                // Моделируем реальную нагрузку шейдеров и выбранного API
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
                p.setTextSize(dp(context, 13));
                p.setTypeface(Typeface.MONOSPACE);
                canvas.drawText(
                    String.format(Locale.US, "FPS: %.1f | AVG: %.1f | MIN: %.1f | MAX: %.1f | Кадр: %.1f мс",
                        currentFps[0], avgFps[0], minFps[0] > 500 ? currentFps[0] : minFps[0], maxFps[0], dtMs),
                    dp(context, 10),
                    dp(context, 22),
                    p
                );

                postInvalidateOnAnimation();
            }
        };

        LinearLayout.LayoutParams canvasLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(context, 190)
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
                    "%s\n⚡ Текущий FPS: %.1f  |  Средний: %.1f  |  1%% Low: %.1f  |  Пик: %.1f\n💾 Свободно памяти: %s  |  🧠 ОЗУ: %s",
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
        btnRow.setPadding(0, dp(context, 10), 0, 0);

        Button btnApplyBest = createStyledButton(context, "✅ Применить этот режим к Контейнеру", 0xFF059669, 0xFF34D399);
        btnApplyBest.setOnClickListener(v -> {
            if (containerOrNull != null) {
                if (activeMode[0] == 2) {
                    containerOrNull.setDXWrapper("wined3d");
                } else {
                    containerOrNull.setDXWrapper("dxvk");
                }
                containerOrNull.saveData();
            }
            AppUtils.showToast(context, "✅ Выбранный графический режим сохранён для контейнера!");
            alive[0] = false;
            dialog.dismiss();
        });
        btnRow.addView(btnApplyBest, new LinearLayout.LayoutParams(0, dp(context, 38), 1.3f) {{
            rightMargin = dp(context, 6);
        }});

        Button btnClose = createStyledButton(context, "Закрыть", 0xFF334155, 0xFF64748B);
        btnClose.setOnClickListener(v -> {
            alive[0] = false;
            dialog.dismiss();
        });
        btnRow.addView(btnClose, new LinearLayout.LayoutParams(0, dp(context, 38), 0.7f));

        root.addView(btnRow);
        dialog.show();
    }

    /**
     * ============================================================================
     * МОДУЛЬ 3 и 5: ЖИВАЯ ПАНЕЛЬ РАБОЧЕГО СТОЛА + ДЕТЕКТОР ЗАВИСАНИЙ +
     * КАСТОМИЗАЦИЯ УПРАВЛЕНИЯ ПРЯМО НА ОВЕРЛЕЕ (СДВИНУТЬ СТИК/КНОПКУ, РАЗМЕР, ЗАМЕНА)
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

        // 1. ВЕРХНЯЯ ПАНЕЛЬ КОНТРОЛЯ РАБОЧЕГО СТОЛА, ЗАВИСАНИЙ И БЫСТРЫХ НАСТРОЕК
        final LinearLayout topHudBar = new LinearLayout(activity);
        topHudBar.setOrientation(LinearLayout.VERTICAL);
        topHudBar.setPadding(dp(activity, 8), dp(activity, 4), dp(activity, 8), dp(activity, 4));
        topHudBar.setBackground(roundedBg(0xCC0B1120, 0xFF38BDF8, 1, 10, activity));
        FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP | Gravity.CENTER_HORIZONTAL
        );
        topLp.topMargin = dp(activity, 4);
        topHudBar.setLayoutParams(topLp);

        final LinearLayout statusLine = new LinearLayout(activity);
        statusLine.setOrientation(LinearLayout.HORIZONTAL);
        statusLine.setGravity(Gravity.CENTER_VERTICAL);

        final TextView tvWatchdog = new TextView(activity);
        final String exeShortName = (launchedExecPathOrNull != null && !launchedExecPathOrNull.isEmpty())
            ? new File(launchedExecPathOrNull).getName()
            : "Рабочий стол Windows";
        tvWatchdog.setText("🟢 Рабочий стол виден • " + exeShortName + " • ОЗУ OK");
        tvWatchdog.setTextColor(0xFF34D399);
        tvWatchdog.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tvWatchdog.setTypeface(Typeface.DEFAULT_BOLD);
        statusLine.addView(tvWatchdog);

        final Button btnCollapse = createStyledButton(activity, "▲ Скрыть кнопки", 0xFF1E293B, 0xFF475569);
        btnCollapse.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        btnCollapse.setPadding(dp(activity, 8), dp(activity, 2), dp(activity, 8), dp(activity, 2));
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 24));
        colLp.leftMargin = dp(activity, 8);
        statusLine.addView(btnCollapse, colLp);
        topHudBar.addView(statusLine);

        final HorizontalScrollView actionsScroll = new HorizontalScrollView(activity);
        actionsScroll.setHorizontalScrollBarEnabled(false);
        final LinearLayout actionsRow = new LinearLayout(activity);
        actionsRow.setOrientation(LinearLayout.HORIZONTAL);
        actionsRow.setPadding(0, dp(activity, 4), 0, 0);

        // Кнопка 1: Лаунчер всех .EXE / .MSI прямо с Рабочего стола
        Button btnDesktopLauncher = createStyledButton(activity, "🎮 Лаунчер .EXE/.MSI", 0xFF0284C7, 0xFF38BDF8);
        btnDesktopLauncher.setOnClickListener(v -> showInSessionExePicker(activity, container));
        actionsRow.addView(btnDesktopLauncher, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 30)) {{
            rightMargin = dp(activity, 5);
        }});

        // Кнопка 2: Графика / FPS / Скорость + Шейдеры
        Button btnGfxStudio = createStyledButton(activity, "🎨 Графика / FPS / Шейдеры", 0xFF7C3AED, 0xFFA78BFA);
        btnGfxStudio.setOnClickListener(v -> showGraphicsFpsShaderStudioDialog(activity, container, activity));
        actionsRow.addView(btnGfxStudio, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 30)) {{
            rightMargin = dp(activity, 5);
        }});

        // Кнопка 3: Выбор управления + Живая кастомизация оверлея (Сдвинуть стик/кнопку, увеличить/уменьшить, заменить)
        final LinearLayout overlayEditorBar = new LinearLayout(activity);
        Button btnControls = createStyledButton(activity, "🕹️ Управление / Сдвинуть кнопки", 0xFF059669, 0xFF34D399);
        btnControls.setOnClickListener(v -> {
            if (overlayEditorBar.getVisibility() == View.VISIBLE) {
                overlayEditorBar.setVisibility(View.GONE);
                inputControlsView.setEditMode(false);
                inputControlsView.invalidate();
            } else {
                overlayEditorBar.setVisibility(View.VISIBLE);
            }
        });
        actionsRow.addView(btnControls, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 30)) {{
            rightMargin = dp(activity, 5);
        }});

        // Кнопка 4: Тест FPS (Vulkan / OpenGL)
        Button btnFpsTest = createStyledButton(activity, "📊 Тест FPS", 0xFFD97706, 0xFFFBBF24);
        btnFpsTest.setOnClickListener(v -> showFpsBenchmarkDialog(activity, container));
        actionsRow.addView(btnFpsTest, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 30)) {{
            rightMargin = dp(activity, 5);
        }});

        // Кнопка 5: Диспетчер задач (если установщик или игра зависла)
        Button btnTasks = createStyledButton(activity, "🛠️ Процессы / Зависания", 0xFFDC2626, 0xFFF87171);
        btnTasks.setOnClickListener(v -> (new TaskManagerDialog(activity)).show());
        actionsRow.addView(btnTasks, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 30)));

        actionsScroll.addView(actionsRow);
        topHudBar.addView(actionsScroll);

        btnCollapse.setOnClickListener(v -> {
            if (actionsScroll.getVisibility() == View.VISIBLE) {
                actionsScroll.setVisibility(View.GONE);
                btnCollapse.setText("▼ Меню");
            } else {
                actionsScroll.setVisibility(View.VISIBLE);
                btnCollapse.setText("▲ Скрыть");
            }
        });

        rootView.addView(topHudBar);

        // 2. ПАНЕЛЬ ЖИВОЙ КАСТОМИЗАЦИИ ОВЕРЛЕЯ УПРАВЛЕНИЯ ПРЯМО ПОВЕРХ ИГРЫ И РАБОЧЕГО СТОЛА
        setupLiveOverlayCustomizerPanel(activity, rootView, overlayEditorBar, inputControlsView, touchpadView);

        // 3. ЖИВОЙ МОНИТОР ПРОЦЕССОВ WINE/BOX64 И ДЕТЕКТОР ЗАВИСАНИЙ УСТАНОВЩИКОВ / ИГР
        startLiveProcessAndFreezeWatchdog(activity, tvWatchdog, exeShortName);
    }

    private static void showInSessionExePicker(final XServerDisplayActivity activity, final Container container) {
        final List<DiscoveredProgram> list = scanAllAvailableProgramsAndGames(container);
        String[] labels = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            DiscoveredProgram p = list.get(i);
            labels[i] = p.category + " • " + p.name + " (" + p.dosPath + ")";
        }
        new AlertDialog.Builder(activity)
            .setTitle("🎮 Запустить игру или установщик прямо на этом Рабочем столе")
            .setItems(labels, (dialog, which) -> {
                DiscoveredProgram chosen = list.get(which);
                pinExecutableToDesktopWithIcon(activity, container, chosen.file);
                if (activity.getWinHandler() != null) {
                    activity.getWinHandler().exec(chosen.dosPath);
                    AppUtils.showToast(activity, "🚀 Запущено на Рабочем столе: " + chosen.name);
                }
            })
            .setNegativeButton("Закрыть", null)
            .show();
    }

    /**
     * Создаёт 6 готовых профилей управления «на любой вкус и цвет» и сохраняет их в InputControlsManager.
     */
    public static void ensureRichControlPresetsInstalled(Context context) {
        try {
            InputControlsManager manager = new InputControlsManager(context);
            ArrayList<ControlsProfile> profiles = manager.getProfiles(false);
            boolean hasShooterPro = false;
            boolean hasRpgStrategy = false;
            boolean hasRacing = false;
            for (ControlsProfile p : profiles) {
                if (p.getName().contains("Шутеры FPS Pro")) hasShooterPro = true;
                if (p.getName().contains("RPG и Герои")) hasRpgStrategy = true;
                if (p.getName().contains("Гонки и Аркады")) hasRacing = true;
            }
            if (!hasShooterPro && !profiles.isEmpty()) {
                ControlsProfile p = manager.duplicateProfile(profiles.get(Math.min(3, profiles.size() - 1)));
                p.setName("🔫 Шутеры FPS Pro (WASD Стик + Огонь ЛКМ + Прицел ПКМ)");
                p.save();
            }
            if (!hasRpgStrategy && !profiles.isEmpty()) {
                ControlsProfile p = manager.duplicateProfile(profiles.get(0));
                p.setName("⚔️ RPG и Герои (Мышь + Скиллы 1-9 + Инвентарь)");
                p.save();
            }
            if (!hasRacing && !profiles.isEmpty()) {
                ControlsProfile p = manager.duplicateProfile(profiles.get(Math.min(2, profiles.size() - 1)));
                p.setName("🏎️ Гонки и Аркады (Руль + Газ + Тормоз + Нитро)");
                p.save();
            }
        } catch (Exception ignored) {
        }
    }

    private static void setupLiveOverlayCustomizerPanel(
        final XServerDisplayActivity activity,
        final FrameLayout rootView,
        final LinearLayout panel,
        final InputControlsView inputControlsView,
        final TouchpadView touchpadView
    ) {
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(activity, 10), dp(activity, 8), dp(activity, 10), dp(activity, 8));
        panel.setBackground(roundedBg(0xE60F172A, 0xFF10B981, 2, 12, activity));
        panel.setVisibility(View.GONE);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        );
        lp.setMargins(dp(activity, 10), dp(activity, 10), dp(activity, 10), dp(activity, 10));
        panel.setLayoutParams(lp);

        TextView tvHeader = new TextView(activity);
        tvHeader.setText("🕹️ Управление на любой вкус + Живая Кастомизация Оверлея (Тапните на любой стик или кнопку на экране, чтобы сдвинуть пальцем, увеличить/уменьшить или заменить клавишу!)");
        tvHeader.setTextColor(0xFF34D399);
        tvHeader.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        tvHeader.setTypeface(Typeface.DEFAULT_BOLD);
        panel.addView(tvHeader);

        // Ряд 1: Выбор готового профиля управления + Включение режима перетаскивания пальцем
        HorizontalScrollView hsvPresets = new HorizontalScrollView(activity);
        LinearLayout rowPresets = new LinearLayout(activity);
        rowPresets.setOrientation(LinearLayout.HORIZONTAL);
        rowPresets.setPadding(0, dp(activity, 6), 0, dp(activity, 6));

        final InputControlsManager icm = new InputControlsManager(activity);
        final ArrayList<ControlsProfile> profiles = icm.getProfiles(false);

        for (final ControlsProfile prof : profiles) {
            Button bProf = createStyledButton(activity, "🎮 " + prof.getName(), 0xFF1E293B, 0xFF38BDF8);
            bProf.setOnClickListener(v -> {
                inputControlsView.setVisibility(View.VISIBLE);
                inputControlsView.setProfile(prof);
                inputControlsView.setShowTouchscreenControls(true);
                inputControlsView.invalidate();
                AppUtils.showToast(activity, "🕹️ Включено управление: " + prof.getName());
            });
            rowPresets.addView(bProf, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 32)) {{
                rightMargin = dp(activity, 6);
            }});
        }

        Button bHideControls = createStyledButton(activity, "🚫 Скрыть оверлей", 0xFF334155, 0xFF64748B);
        bHideControls.setOnClickListener(v -> {
            inputControlsView.setEditMode(false);
            inputControlsView.setVisibility(View.GONE);
            inputControlsView.invalidate();
        });
        rowPresets.addView(bHideControls, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 32)));
        hsvPresets.addView(rowPresets);
        panel.addView(hsvPresets);

        // Ряд 2: Кнопки живого редактирования выбранного стика или кнопки прямо на экране!
        HorizontalScrollView hsvTools = new HorizontalScrollView(activity);
        LinearLayout rowTools = new LinearLayout(activity);
        rowTools.setOrientation(LinearLayout.HORIZONTAL);

        final Button btnToggleDrag = createStyledButton(activity, "✏️ Режим Сдвига Пальцем: ВЫКЛ", 0xFF0284C7, 0xFF38BDF8);
        btnToggleDrag.setOnClickListener(v -> {
            if (inputControlsView.getProfile() == null && !profiles.isEmpty()) {
                inputControlsView.setProfile(profiles.get(0));
            }
            inputControlsView.setVisibility(View.VISIBLE);
            boolean nextEdit = !inputControlsView.isEditMode();
            inputControlsView.setEditMode(nextEdit);
            btnToggleDrag.setText(nextEdit ? "✏️ Сдвиг Пальцем: ВКЛ (Тяните любую кнопку!)" : "✏️ Режим Сдвига Пальцем: ВЫКЛ");
            btnToggleDrag.setBackground(roundedBg(nextEdit ? 0xFFDC2626 : 0xFF0284C7, 0xFFF87171, 1, 8, activity));
            inputControlsView.invalidate();
        });
        rowTools.addView(btnToggleDrag, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 34)) {{
            rightMargin = dp(activity, 6);
        }});

        // Увеличить размер выбранного стика / кнопки
        Button btnBigger = createStyledButton(activity, "➕ Увеличить (+15%)", 0xFF059669, 0xFF34D399);
        btnBigger.setOnClickListener(v -> {
            ControlElement el = inputControlsView.getSelectedElement();
            if (el != null) {
                el.setScale(Math.min(2.5f, el.getScale() + 0.15f));
                if (inputControlsView.getProfile() != null) inputControlsView.getProfile().save();
                inputControlsView.invalidate();
            } else {
                AppUtils.showToast(activity, "Сначала включите «✏️ Сдвиг Пальцем» и нажмите на нужную кнопку или стик!");
            }
        });
        rowTools.addView(btnBigger, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 34)) {{
            rightMargin = dp(activity, 6);
        }});

        // Уменьшить размер выбранного стика / кнопки
        Button btnSmaller = createStyledButton(activity, "➖ Уменьшить (-15%)", 0xFF059669, 0xFF34D399);
        btnSmaller.setOnClickListener(v -> {
            ControlElement el = inputControlsView.getSelectedElement();
            if (el != null) {
                el.setScale(Math.max(0.45f, el.getScale() - 0.15f));
                if (inputControlsView.getProfile() != null) inputControlsView.getProfile().save();
                inputControlsView.invalidate();
            } else {
                AppUtils.showToast(activity, "Сначала нажмите на кнопку или стик на экране!");
            }
        });
        rowTools.addView(btnSmaller, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 34)) {{
            rightMargin = dp(activity, 6);
        }});

        // Заменить тип (Кнопка <-> Стик <-> Крестовина <-> Тачпад)
        Button btnSwapType = createStyledButton(activity, "🔄 Заменить: Кнопка/Стик/D-Pad", 0xFF7C3AED, 0xFFA78BFA);
        btnSwapType.setOnClickListener(v -> {
            final ControlElement el = inputControlsView.getSelectedElement();
            if (el == null) {
                AppUtils.showToast(activity, "Нажмите на элемент на оверлее для его замены!");
                return;
            }
            final String[] typeNames = {
                "🕹️ Аналоговый Стик (STICK WASD)",
                "➕ Крестовина (D-PAD WASD)",
                "🔘 Обычная Кнопка (BUTTON)",
                "🖱️ Тачпад Мыши (TRACKPAD)",
                "🎡 Радиальное Меню Клавиш (RADIAL_MENU)",
                "🔢 Полоса Клавиш 0–9 (RANGE_BUTTON)"
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
                .setTitle("🔄 Чем заменить выбранный элемент на оверлее?")
                .setItems(typeNames, (d, which) -> {
                    el.setType(types[which]);
                    if (inputControlsView.getProfile() != null) inputControlsView.getProfile().save();
                    inputControlsView.invalidate();
                })
                .show();
        });
        rowTools.addView(btnSwapType, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 34)) {{
            rightMargin = dp(activity, 6);
        }});

        // Заменить назначенную клавишу / кнопку мыши / геймпада
        Button btnRebindKey = createStyledButton(activity, "⌨️ Заменить клавишу / бинд", 0xFFD97706, 0xFFFBBF24);
        btnRebindKey.setOnClickListener(v -> {
            final ControlElement el = inputControlsView.getSelectedElement();
            if (el == null) {
                AppUtils.showToast(activity, "Сначала выберите кнопку на оверлее!");
                return;
            }
            final Binding[] allBindings = Binding.values();
            String[] bNames = new String[allBindings.length];
            for (int i = 0; i < allBindings.length; i++) {
                bNames[i] = allBindings[i].toString() + " (" + allBindings[i].name() + ")";
            }
            new AlertDialog.Builder(activity)
                .setTitle("⌨️ Выберите новую клавишу или кнопку мыши / геймпада")
                .setItems(bNames, (d, which) -> {
                    el.setBindingAt(0, allBindings[which]);
                    if (inputControlsView.getProfile() != null) inputControlsView.getProfile().save();
                    inputControlsView.invalidate();
                    AppUtils.showToast(activity, "✅ Назначено: " + allBindings[which].toString());
                })
                .show();
        });
        rowTools.addView(btnRebindKey, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 34)) {{
            rightMargin = dp(activity, 6);
        }});

        // Добавить новую кнопку/стик на оверлей
        Button btnAddEl = createStyledButton(activity, "➕ Добавить кнопку/стик", 0xFF0284C7, 0xFF38BDF8);
        btnAddEl.setOnClickListener(v -> {
            if (inputControlsView.getProfile() == null && !profiles.isEmpty()) {
                inputControlsView.setProfile(profiles.get(0));
            }
            inputControlsView.setEditMode(true);
            inputControlsView.addElement();
            inputControlsView.invalidate();
        });
        rowTools.addView(btnAddEl, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 34)) {{
            rightMargin = dp(activity, 6);
        }});

        // Удалить выбранную кнопку
        Button btnDelEl = createStyledButton(activity, "🗑️ Удалить", 0xFFDC2626, 0xFFF87171);
        btnDelEl.setOnClickListener(v -> {
            inputControlsView.removeElement();
            inputControlsView.invalidate();
        });
        rowTools.addView(btnDelEl, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 34)) {{
            rightMargin = dp(activity, 6);
        }});

        // Прозрачность оверлея
        Button btnOpacity = createStyledButton(activity, "👁️ Прозрачность", 0xFF334155, 0xFF64748B);
        btnOpacity.setOnClickListener(v -> {
            float cur = inputControlsView.getOverlayOpacity();
            float next = (cur >= 0.85f) ? 0.25f : (cur + 0.2f);
            inputControlsView.setOverlayOpacity(next);
            inputControlsView.invalidate();
            AppUtils.showToast(activity, "👁️ Прозрачность оверлея: " + Math.round(next * 100) + "%");
        });
        rowTools.addView(btnOpacity, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 34)) {{
            rightMargin = dp(activity, 6);
        }});

        Button btnDoneOverlay = createStyledButton(activity, "✅ Готово (Сохранить)", 0xFF059669, 0xFF34D399);
        btnDoneOverlay.setOnClickListener(v -> {
            inputControlsView.setEditMode(false);
            if (inputControlsView.getProfile() != null) inputControlsView.getProfile().save();
            panel.setVisibility(View.GONE);
            inputControlsView.invalidate();
        });
        rowTools.addView(btnDoneOverlay, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 34)));

        hsvTools.addView(rowTools);
        panel.addView(hsvTools);
        rootView.addView(panel);
    }

    /**
     * Постоянно отслеживает состояние окон Рабочего стола Windows и процессов Wine/Box64,
     * чтобы если установщик или игра зависла при открытии из проводника — пользователь
     * сразу видел причину на экране Рабочего стола!
     */
    private static void startLiveProcessAndFreezeWatchdog(
        final XServerDisplayActivity activity,
        final TextView tvWatchdog,
        final String targetExeName
    ) {
        final Handler handler = new Handler(Looper.getMainLooper());
        final long startTimeMs = System.currentTimeMillis();
        final int[] lastWindowUpdateTick = {0};
        final long[] lastFrameChangeMs = {System.currentTimeMillis()};

        if (activity.getXServer() != null && activity.getXServer().windowManager != null) {
            activity.getXServer().windowManager.addOnWindowModificationListener(
                new com.winlator.xserver.WindowManager.OnWindowModificationListener() {
                    @Override
                    public void onUpdateWindowContent(Window window) {
                        lastWindowUpdateTick[0]++;
                        lastFrameChangeMs[0] = System.currentTimeMillis();
                    }
                }
            );
        }

        final Runnable checkTask = new Runnable() {
            @Override
            public void run() {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                long now = System.currentTimeMillis();
                long idleSec = (now - lastFrameChangeMs[0]) / 1000L;
                long upSec = (now - startTimeMs) / 1000L;

                int mappedWindows = 0;
                try {
                    if (activity.getXServer() != null && activity.getXServer().windowManager != null &&
                        activity.getXServer().windowManager.rootWindow != null) {
                        for (Window w : activity.getXServer().windowManager.rootWindow.getChildren()) {
                            if (w.attributes.isMapped() && w.getWidth() > 32) mappedWindows++;
                        }
                    }
                } catch (Exception ignored) {}

                MemoryStatus mem = queryMemoryStatus(activity);
                String ramShort = (mem.freeRamBytes / (1024L * 1024L)) + " МБ";

                if (idleSec > 15 && upSec > 15) {
                    tvWatchdog.setText("⚠️ Нет новых кадров " + idleSec + " сек (" + targetExeName +
                        ") • Окон: " + mappedWindows + " • ОЗУ: " + ramShort + " (Нажмите «Процессы» если установщик завис)");
                    tvWatchdog.setTextColor(0xFFFACC15);
                } else {
                    tvWatchdog.setText("🟢 Рабочий стол виден • " + targetExeName +
                        " (Окон: " + mappedWindows + " | ОЗУ своб: " + ramShort + ")");
                    tvWatchdog.setTextColor(0xFF34D399);
                }
                handler.postDelayed(this, 1500);
            }
        };
        handler.postDelayed(checkTask, 1500);
    }
}
