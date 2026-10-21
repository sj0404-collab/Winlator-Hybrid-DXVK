package com.winlator;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.Spinner;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.preference.PreferenceManager;

import com.google.android.material.navigation.NavigationView;
import com.winlator.alsaserver.ALSAClient;
import com.winlator.container.AudioDrivers;
import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.container.DXWrappers;
import com.winlator.container.GraphicsDrivers;
import com.winlator.container.Shortcut;
import com.winlator.contentdialog.ActiveWindowsDialog;
import com.winlator.contentdialog.AudioDriverConfigDialog;
import com.winlator.contentdialog.ContentDialog;
import com.winlator.contentdialog.DXVKConfigDialog;
import com.winlator.contentdialog.DebugDialog;
import com.winlator.contentdialog.ScreenEffectDialog;
import com.winlator.contentdialog.TurnipConfigDialog;
import com.winlator.contentdialog.VKD3DConfigDialog;
import com.winlator.contentdialog.VirGLConfigDialog;
import com.winlator.contentdialog.WineD3DConfigDialog;
import com.winlator.core.AppUtils;
import com.winlator.core.DefaultVersion;
import com.winlator.core.EnvVars;
import com.winlator.core.FileUtils;
import com.winlator.core.GeneralComponents;
import com.winlator.core.KeyValueSet;
import com.winlator.core.LocaleHelper;
import com.winlator.core.PreloaderDialog;
import com.winlator.core.ProcessHelper;
import com.winlator.core.StringUtils;
import com.winlator.core.TarCompressorUtils;
import com.winlator.core.Win32AppWorkarounds;
import com.winlator.core.WineInfo;
import com.winlator.core.WineInstaller;
import com.winlator.core.WineRegistryEditor;
import com.winlator.core.WineStartMenuCreator;
import com.winlator.core.WineThemeManager;
import com.winlator.core.WineUtils;
import com.winlator.inputcontrols.ControlsProfile;
import com.winlator.inputcontrols.ExternalController;
import com.winlator.inputcontrols.InputControlsManager;
import com.winlator.math.Mathf;
import com.winlator.pro.WinlatorProSuite;
import com.winlator.renderer.GLRenderer;
import com.winlator.services.ForegroundService;
import com.winlator.widget.FrameRating;
import com.winlator.box64.Box64Preset;
import com.winlator.widget.InputControlsView;
import com.winlator.widget.MagnifierView;
import com.winlator.widget.TouchpadView;
import com.winlator.widget.XServerView;
import com.winlator.winhandler.TaskManagerDialog;
import com.winlator.winhandler.WinHandler;
import com.winlator.xconnector.UnixSocketConfig;
import com.winlator.xenvironment.RootFS;
import com.winlator.xenvironment.XEnvironment;
import com.winlator.xenvironment.components.ALSAServerComponent;
import com.winlator.xenvironment.components.GuestProgramLauncherComponent;
import com.winlator.xenvironment.components.NetworkInfoUpdateComponent;
import com.winlator.xenvironment.components.PulseAudioComponent;
import com.winlator.xenvironment.components.SysVSharedMemoryComponent;
import com.winlator.xenvironment.components.VirGLRendererComponent;
import com.winlator.xenvironment.components.VortekRendererComponent;
import com.winlator.xenvironment.components.XServerComponent;
import com.winlator.xserver.Atom;
import com.winlator.xserver.Property;
import com.winlator.xserver.ScreenInfo;
import com.winlator.xserver.Window;
import com.winlator.xserver.WindowManager;
import com.winlator.xserver.XServer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class XServerDisplayActivity extends AppCompatActivity implements NavigationView.OnNavigationItemSelectedListener {
    private XServerView xServerView;
    private InputControlsView inputControlsView;
    private TouchpadView touchpadView;
    private XEnvironment environment;
    private DrawerLayout drawerLayout;
    private Container container;
    private XServer xServer;
    private InputControlsManager inputControlsManager;
    private RootFS rootFS;
    private FrameRating frameRating;
    private File launchTargetExe;
    public volatile boolean unityLaunch = false;
    public volatile int unityLevel = -1;
    private Runnable editInputControlsCallback;
    private Shortcut shortcut;
    private PreloaderDialog preloaderDialog;
    private final AtomicBoolean isExiting = new AtomicBoolean(false);
    private String pendingExecCommand = null;
    private String pendingExecArgs = "";
    private String[] graphicsDriver = {GraphicsDrivers.DEFAULT_VULKAN_DRIVER, GraphicsDrivers.DEFAULT_OPENGL_DRIVER};
    private String audioDriver = Container.DEFAULT_AUDIO_DRIVER;
    private String dxwrapper = Container.DEFAULT_DXWRAPPER;
    private ScreenInfo screenInfo = new ScreenInfo(Container.DEFAULT_SCREEN_SIZE);
    private KeyValueSet[] dxwrapperConfig;
    private KeyValueSet[] graphicsDriverConfig = {new KeyValueSet(), new KeyValueSet()};
    private KeyValueSet audioDriverConfig;
    private String wincomponents;
    private WineInfo wineInfo;
    private final EnvVars envVars = new EnvVars();
    private EnvVars overrideEnvVars;
    private ClipboardManager clipboardManager;
    private SharedPreferences preferences;
    private final WinHandler winHandler = new WinHandler(this);
    private float globalCursorSpeed = 1.0f;
    private boolean capturePointerOnExternalMouse = true;
    private MagnifierView magnifierView;
    private DebugDialog debugDialog;
    public int frameRatingWindowId = -1;
    private Win32AppWorkarounds win32AppWorkarounds;
    private String screenEffectProfile;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        AppUtils.setActivityTheme(this);
        super.onCreate(savedInstanceState);
        AppUtils.hideSystemUI(this);
        AppUtils.keepScreenOn(this);
        setContentView(R.layout.xserver_display_activity);
        ForegroundService.startSession(this);

        preloaderDialog = new PreloaderDialog(this);
        preferences = PreferenceManager.getDefaultSharedPreferences(this);
        boolean useAndroidClipboardOnWine = preferences.getBoolean("use_android_clipboard_on_wine", false);
        clipboardManager = useAndroidClipboardOnWine ? (ClipboardManager)getSystemService(CLIPBOARD_SERVICE) : null;

        drawerLayout = findViewById(R.id.DrawerLayout);
        drawerLayout.setOnApplyWindowInsetsListener((view, windowInsets) -> windowInsets.replaceSystemWindowInsets(0, 0, 0, 0));
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);

        NavigationView navigationView = findViewById(R.id.NavigationView);
        ProcessHelper.removeAllDebugCallbacks();
        boolean enableLogs = preferences.getBoolean("enable_wine_debug", false) || preferences.getInt("box64_logs", 0) >= 1;
        if (enableLogs) ProcessHelper.addDebugCallback(debugDialog = new DebugDialog(this));
        Menu menu = navigationView.getMenu();
        menu.findItem(R.id.menu_item_logs).setVisible(enableLogs);
        navigationView.setNavigationItemSelectedListener(this);

        rootFS = RootFS.find(this);

        if (!isGenerateWineprefix()) {
            ContainerManager containerManager = new ContainerManager(this);
            container = containerManager.getContainerById(getIntent().getIntExtra("container_id", 0));
            if (container == null) {
                container = containerManager.ensureDefaultHybridContainer();
            }
            if (container == null) {
                finish();
                return;
            }
            containerManager.activateContainer(container);

            boolean wineprefixNeedsUpdate = container.getExtra("wineprefixNeedsUpdate").equals("t");
            if (wineprefixNeedsUpdate) {
                preloaderDialog.show(R.string.updating_system_files);
                WineUtils.updateWineprefix(this, (status) -> {
                    if (status == 0) {
                        container.putExtra("wineprefixNeedsUpdate", null);
                        container.putExtra("wincomponents", null);
                        container.saveData();
                        AppUtils.restartActivity(this);
                    }
                    else finish();
                });
                return;
            }

            win32AppWorkarounds = new Win32AppWorkarounds(this);

            String wineVersion = container.getWineVersion();
            wineInfo = WineInfo.fromIdentifier(this, wineVersion);

            if (wineInfo != WineInfo.MAIN_WINE_INFO) rootFS.setWinePath(wineInfo.path);

            String shortcutPath = getIntent().getStringExtra("shortcut_path");
            if (shortcutPath != null && !shortcutPath.isEmpty()) shortcut = new Shortcut(container, new File(shortcutPath));

            String graphicsDriver = container.getGraphicsDriver();
            audioDriver = container.getAudioDriver();
            String dxwrapper = container.getDXWrapper();
            wincomponents = container.getWinComponents();
            String dxwrapperConfig = container.getDXWrapperConfig();
            String graphicsDriverConfig = container.getGraphicsDriverConfig();
            audioDriverConfig = new KeyValueSet(container.getAudioDriverConfig());
            screenInfo = resolveScreenInfo(container.getScreenSize());

            if (shortcut != null) {
                graphicsDriver = shortcut.getExtra("graphicsDriver", container.getGraphicsDriver());
                audioDriver = shortcut.getExtra("audioDriver", container.getAudioDriver());
                dxwrapper = shortcut.getExtra("dxwrapper", container.getDXWrapper());
                wincomponents = shortcut.getExtra("wincomponents", container.getWinComponents());
                dxwrapperConfig = shortcut.getExtra("dxwrapperConfig", container.getDXWrapperConfig());
                graphicsDriverConfig = shortcut.getExtra("graphicsDriverConfig", container.getGraphicsDriverConfig());
                audioDriverConfig = new KeyValueSet(shortcut.getExtra("audioDriverConfig", container.getAudioDriverConfig()));
                screenInfo = resolveScreenInfo(shortcut.getExtra("screenSize", container.getScreenSize()));

                String dinputMapperType = shortcut.getExtra("dinputMapperType");
                if (!dinputMapperType.isEmpty()) winHandler.gamepadHandler.setDInputMapperType(Byte.parseByte(dinputMapperType));

                win32AppWorkarounds.applyStartupWorkarounds(!shortcut.wmClass.isEmpty() ? shortcut.wmClass : shortcut.path);
            }
            else {
                Intent intent = getIntent();
                if (intent.hasExtra("exec_path")) win32AppWorkarounds.applyStartupWorkarounds(FileUtils.getName(intent.getStringExtra("exec_path")));
            }

            this.graphicsDriver = GraphicsDrivers.parseIdentifiers(graphicsDriver);
            if (this.graphicsDriver[1].equals(GraphicsDrivers.GLADIO)) {
                this.graphicsDriver[1] = GraphicsDrivers.VIRGL;
            }
            if (this.graphicsDriver[0].equals(GraphicsDrivers.TURNIP) && !com.winlator.core.GPUHelper.isTurnipSupported(this)) {
                this.graphicsDriver[0] = GraphicsDrivers.VORTEK;
            }
            this.graphicsDriverConfig = GraphicsDrivers.parseConfigs(graphicsDriver, graphicsDriverConfig);
            this.dxwrapper = DXWrappers.parseIdentifier(dxwrapper);
            this.dxwrapperConfig = DXWrappers.parseConfigs(dxwrapper, dxwrapperConfig);
        }

        preloaderDialog.show(R.string.starting_up);

        inputControlsManager = new InputControlsManager(this);
        xServer = new XServer(this, screenInfo);
        xServer.setWinHandler(winHandler);
        // Всегда оставляем экран Рабочего стола Windows видимым даже при установке/открытии файла из Проводника!
        final boolean[] flags = {false, false};
        xServer.windowManager.addOnWindowModificationListener(new WindowManager.OnWindowModificationListener() {
            @Override
            public void onUpdateWindowContent(Window window) {
                if (window.id == frameRatingWindowId) frameRating.update();
            }

            @Override
            public void onMapWindow(Window window) {
                if (!flags[0] && window.isRenderable() && !window.getClassName().isEmpty()) {
                    xServerView.getRenderer().setCursorVisible(true);
                    preloaderDialog.closeOnUiThread();
                    flags[0] = true;
                }

                if (flags[1] && window.attributes.isViewable() && window.isDesktopWindow()) {
                    window.attributes.setViewable(false);
                    if (window.attributes.isEnabled()) window.disableAllDescendants();
                }

                if (win32AppWorkarounds != null) win32AppWorkarounds.applyWindowWorkarounds(window);
                changeFrameRatingVisibility(window, true);
            }

            @Override
            public void onUnmapWindow(Window window) {
                changeFrameRatingVisibility(window, false);
            }
        });

        setupUI();

        Executors.newSingleThreadExecutor().execute(() -> {
            if (!isGenerateWineprefix()) {
                try {
                    setupWineSystemFiles();
                } catch (Throwable ignored) {}
                try {
                    extractGraphicsDriverFiles();
                } catch (Throwable ignored) {}
                try {
                    changeWineAudioDriver();
                } catch (Throwable ignored) {}
            }
            try {
                setupXEnvironment();
            } catch (Throwable e) {
                preloaderDialog.closeOnUiThread();
            }
        });
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.setSystemLocale(newBase));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == MainActivity.EDIT_INPUT_CONTROLS_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            if (editInputControlsCallback != null) {
                editInputControlsCallback.run();
                editInputControlsCallback = null;
            }
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);

        if (hasFocus) {
            if (capturePointerOnExternalMouse) {
                // Captured events are delivered to the focused view.
                View captureView = inputControlsView.getVisibility() == View.VISIBLE ? inputControlsView : touchpadView;
                if (captureView.requestFocus()) captureView.requestPointerCapture();
            }

            if (winHandler != null && clipboardManager != null && clipboardManager.hasPrimaryClip()) {
                ClipData primaryClip = clipboardManager.getPrimaryClip();
                if (primaryClip != null && primaryClip.getItemCount() > 0) {
                    winHandler.setClipboardData(primaryClip.getItemAt(0).getText().toString());
                }
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (environment != null) {
            xServerView.onResume();
            environment.onResume();
        }
        ForegroundService.onResumeSession(this);
    }

    @Override
    public void onPause() {
        ForegroundService.onPauseSession(this);
        super.onPause();
        if (environment != null && !isInPictureInPictureMode()) {
            environment.onPause();
            xServerView.onPause();
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        ForegroundService.setPipMode(isInPictureInPictureMode);
    }

    @Override
    protected void onDestroy() {
        try {
            if (preloaderDialog != null) preloaderDialog.close();
            WinlatorProSuite.stopWatchdog();
            winHandler.stop();
            if (environment != null) environment.stopEnvironmentComponents();
            ForegroundService.stopSession(this);
        } catch (Throwable ignored) {}
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (environment != null) {
            if (!drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.openDrawer(GravityCompat.START);
            }
            else drawerLayout.closeDrawers();
        }
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        final GLRenderer renderer = xServerView.getRenderer();
        switch (item.getItemId()) {
            case R.id.menu_item_keyboard:
                AppUtils.showKeyboard(this);
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_input_controls:
                showInputControlsDialog();
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_toggle_fullscreen:
                renderer.toggleFullscreen();
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_task_manager:
                (new TaskManagerDialog(this)).show();
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_active_windows:
                (new ActiveWindowsDialog(this)).show();
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_magnifier:
                if (magnifierView == null) {
                    final FrameLayout container = findViewById(R.id.FLXServerDisplay);
                    magnifierView = new MagnifierView(this);
                    magnifierView.setZoomButtonCallback((value) -> {
                        renderer.setMagnifierZoom(Mathf.clamp(renderer.getMagnifierZoom() + value, 1.0f, 3.0f));
                        magnifierView.setZoomValue(renderer.getMagnifierZoom());
                    });
                    magnifierView.setZoomValue(renderer.getMagnifierZoom());
                    magnifierView.setHideButtonCallback(() -> {
                        container.removeView(magnifierView);
                        magnifierView = null;
                    });
                    container.addView(magnifierView);
                }
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_screen_effect:
                (new ScreenEffectDialog(this)).show();
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_pip_mode:
                PictureInPictureParams pipParams = (new PictureInPictureParams.Builder())
                    .setAspectRatio(screenInfo.aspectRatio())
                    .build();
                enterPictureInPictureMode(pipParams);
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_logs:
                debugDialog.show();
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_touchpad_help:
                showTouchpadHelpDialog();
                break;
            case R.id.menu_item_exit:
                exit();
                break;
        }
        return true;
    }

    public SharedPreferences getPreferences() {
        return preferences;
    }

    private void exit() {
        if (!isExiting.compareAndSet(false, true)) return;
        runOnUiThread(() -> {
            try {
                if (preloaderDialog != null) preloaderDialog.close();
                WinlatorProSuite.stopWatchdog();
                ForegroundService.stopSession(this);
                if (xServerView != null) xServerView.onPause();
                winHandler.stop();
                if (environment != null) environment.stopEnvironmentComponents();
            } catch (Throwable ignored) {}

            Intent intent = getIntent();
            if (intent != null && intent.hasExtra("exec_path") && container != null) {
                AppUtils.RestartApplicationOptions options = new AppUtils.RestartApplicationOptions();
                options.containerId = container.id;
                options.startPath = FileUtils.getDirname(intent.getStringExtra("exec_path"));
                AppUtils.restartApplication(this, options);
            }
            else AppUtils.restartApplication(this);
        });
    }

    private void setupWineSystemFiles() {
        String appVersion = String.valueOf(AppUtils.getVersionCode(this));
        String rfsVersion = String.valueOf(rootFS.getVersion());
        boolean containerDataChanged = false;

        boolean wineprefixWasUpdated = WineUtils.isWineprefixWasUpdated(container);
        if (!container.getExtra("appVersion").equals(appVersion) || !container.getExtra("rfsVersion").equals(rfsVersion) || wineprefixWasUpdated) {
            applyGeneralPatches(container);
            container.putExtra("appVersion", appVersion);
            container.putExtra("rfsVersion", rfsVersion);
            containerDataChanged = true;
        }

        if (verifyUserRegistry()) containerDataChanged = true;
        if (extractDXWrapperFiles()) containerDataChanged = true;

        if (!wincomponents.equals(container.getExtra("wincomponents"))) {
            extractWinComponentFiles();
            container.putExtra("wincomponents", wincomponents);
            containerDataChanged = true;
        }

        String desktopTheme = container.getDesktopTheme();
        if (desktopTheme == null || desktopTheme.isEmpty() || desktopTheme.startsWith("LIGHT,IMAGE,#0277bd")) {
            desktopTheme = WineThemeManager.DEFAULT_DESKTOP_THEME;
            container.setDesktopTheme(desktopTheme);
            containerDataChanged = true;
        }
        String themeCacheKey = "v12.4-" + desktopTheme + "," + xServer.screenInfo;
        if (!themeCacheKey.equals(container.getExtra("desktopTheme"))) {
            WineThemeManager.apply(this, new WineThemeManager.ThemeInfo(desktopTheme), xServer.screenInfo);
            container.putExtra("desktopTheme", themeCacheKey);
            containerDataChanged = true;
        }

        WineStartMenuCreator.create(this, container);
        File targetExecFile = null;
        Intent launchIntent = getIntent();
        if (launchIntent != null && launchIntent.hasExtra("exec_path")) {
            String rawExec = launchIntent.getStringExtra("exec_path");
            if (rawExec != null && !rawExec.isEmpty()) targetExecFile = new File(rawExec);
        } else if (shortcut != null && shortcut.path != null && !shortcut.path.isEmpty()) {
            String uPath = WineUtils.dosToUnixPath(shortcut.path, container);
            if (uPath != null && !uPath.isEmpty()) targetExecFile = new File(uPath);
        }
        launchTargetExe = targetExecFile;
        String extraWinePath = WinlatorProSuite.prepareGameFolderAndDependencies(this, container, targetExecFile);
        if (extraWinePath != null && !extraWinePath.isEmpty()) {
            envVars.put("WINEPATH", extraWinePath);
        }
        WineUtils.createDosdevicesSymlinks(container, true);
        if (targetExecFile != null && targetExecFile.exists()) {
            WinlatorProSuite.pinExecutableToDesktopWithIcon(this, container, targetExecFile);
        }

        String startupSelection = String.valueOf(container.getStartupSelection());
        if (!startupSelection.equals(container.getExtra("startupSelection")) || wineprefixWasUpdated) {
            WineUtils.changeServicesStatus(container, container.getStartupSelection());
            container.putExtra("startupSelection", startupSelection);
            containerDataChanged = true;
        }

        boolean openAndroidBrowserFromWine = preferences.getBoolean("open_android_browser_from_wine", true);
        String openAndroidBrowserFromWineStr = openAndroidBrowserFromWine ? "t" : "f";
        if (!openAndroidBrowserFromWineStr.equals(container.getExtra("openAndroidBrowserFromWine")) || wineprefixWasUpdated) {
            WineUtils.changeBrowsersRegistryKey(container, openAndroidBrowserFromWine);
            container.putExtra("openAndroidBrowserFromWine", openAndroidBrowserFromWineStr);
            containerDataChanged = true;
        }

        if (containerDataChanged) container.saveData();
    }

    private void setupXEnvironment() {
        String rootPath = rootFS.getRootDir().getPath();
        envVars.put("MESA_DEBUG", "silent");
        envVars.put("MESA_NO_ERROR", "1");
        envVars.put("WINEPREFIX", rootPath+RootFS.WINEPREFIX);
        envVars.put("WINE_DO_NOT_CREATE_DXGI_DEVICE_MANAGER", "1");

        boolean enableWineDebug = preferences.getBoolean("enable_wine_debug", false);
        String wineDebugChannels = preferences.getString("wine_debug_channels", SettingsFragment.DEFAULT_WINE_DEBUG_CHANNELS);
        envVars.put("WINEDEBUG", enableWineDebug && !wineDebugChannels.isEmpty() ? "+"+wineDebugChannels.replace(",", ",+") : "-all");

        FileUtils.clear(rootFS.getTmpDir());

        GuestProgramLauncherComponent guestProgramLauncherComponent = new GuestProgramLauncherComponent();

        if (container != null) {
            if (container.getHUDMode() == FrameRating.Mode.FULL.ordinal()) envVars.put("X11_WND_GPU_INFO", "1");

            // Всегда запускаем полноценную оболочку Рабочего стола Windows ("shell"), чтобы при установке из Проводника экран Рабочего стола не скрывался!
            String desktopName = "shell";
            String guestExecutable = "wine explorer /desktop="+desktopName+","+xServer.screenInfo+" "+getWineStartCommand();
            guestProgramLauncherComponent.setGuestExecutable(guestExecutable);

            String driverTuDebug = envVars.get("TU_DEBUG");
            envVars.putAll(container.getEnvVars());
            if (shortcut != null) envVars.putAll(shortcut.getExtra("envVars"));
            if (!driverTuDebug.isEmpty()) {
                String currentTuDebug = envVars.get("TU_DEBUG");
                for (String token : driverTuDebug.split(",")) {
                    String trimmed = token.trim();
                    if (!trimmed.isEmpty() && !currentTuDebug.contains(trimmed)) {
                        currentTuDebug = (!currentTuDebug.isEmpty() ? currentTuDebug + "," : "") + trimmed;
                    }
                }
                envVars.put("TU_DEBUG", currentTuDebug);
            }
            if (!envVars.has("WINEESYNC")) envVars.put("WINEESYNC", "1");

            guestProgramLauncherComponent.setBox64Preset(shortcut != null ? shortcut.getExtra("box64Preset", container.getBox64Preset()) : container.getBox64Preset());

            // v12.6.1: кэш шейдеров Mesa не больше 256 МБ (раньше рос до 1 ГБ и «съедал» диск C)
            if (!envVars.has("MESA_SHADER_CACHE_MAX_SIZE")) envVars.put("MESA_SHADER_CACHE_MAX_SIZE", "256M");

            // v12.6.1: игры на Unity (UnityPlayer.dll) — безопасный режим Box64. Пресет PERFORMANCE
            // (CALLRET=1, STRONGMEM=0, BIGBLOCK=3) ломает исключения/многопоточность движка → вылет при загрузке уровня
            if (WinlatorProSuite.isUnityGame(launchTargetExe)) {
                unityLaunch = true;
                String chosenPreset = guestProgramLauncherComponent.getBox64Preset();
                if (Box64Preset.PERFORMANCE.equals(chosenPreset) || Box64Preset.INTERMEDIATE.equals(chosenPreset)) {
                    // v12.6.2: уровни Скорость / Баланс (по умолчанию) / Стабильность; STRONGMEM=1 и CALLRET=0 — всегда
                    unityLevel = WinlatorProSuite.getUnityLevel(this, launchTargetExe);
                    WinlatorProSuite.applyUnityBox64Level(unityLevel, guestProgramLauncherComponent, envVars);
                }
                envVars.put("BOX64_UNITYPLAYER", "1");
            }
        }

        environment = new XEnvironment(this, rootFS);
        environment.addComponent(new SysVSharedMemoryComponent(xServer, UnixSocketConfig.create(rootPath, UnixSocketConfig.SYSVSHM_SERVER_PATH)));
        environment.addComponent(new XServerComponent(xServer, UnixSocketConfig.create(rootPath, UnixSocketConfig.XSERVER_PATH)));
        environment.addComponent(new NetworkInfoUpdateComponent());

        if (audioDriver.equals(AudioDrivers.ALSA)) {
            envVars.put("ANDROID_ALSA_SERVER", rootPath+UnixSocketConfig.ALSA_SERVER_PATH);
            envVars.put("ANDROID_ASERVER_USE_SHM", ALSAClient.USE_SHARED_MEMORY ? "true" : "false");

            ALSAClient.Options options = ALSAClient.Options.fromKeyValueSet(audioDriverConfig);
            environment.addComponent(new ALSAServerComponent(UnixSocketConfig.create(rootPath, UnixSocketConfig.ALSA_SERVER_PATH), options));
        }
        else if (audioDriver.equals(AudioDrivers.PULSEAUDIO)) {
            PulseAudioComponent pulseAudioComponent = new PulseAudioComponent(UnixSocketConfig.create(rootPath, UnixSocketConfig.PULSE_SERVER_PATH));
            envVars.put("PULSE_SERVER", rootPath+UnixSocketConfig.PULSE_SERVER_PATH);

            if (!audioDriverConfig.isEmpty()) {
                envVars.put("PULSE_LATENCY_MSEC", audioDriverConfig.getInt("latencyMillis", AudioDriverConfigDialog.DEFAULT_LATENCY_MILLIS));
                pulseAudioComponent.setVolume(audioDriverConfig.getFloat("volume", AudioDriverConfigDialog.DEFAULT_VOLUME));
                pulseAudioComponent.setPerformanceMode(audioDriverConfig.getInt("performanceMode", AudioDriverConfigDialog.DEFAULT_PERFORMANCE_MODE));
            }
            else envVars.put("PULSE_LATENCY_MSEC", AudioDriverConfigDialog.DEFAULT_LATENCY_MILLIS);
            environment.addComponent(pulseAudioComponent);
        }

        VortekRendererComponent.Options vortekOptions = VortekRendererComponent.Options.fromKeyValueSet(this, graphicsDriverConfig[0]);
        VortekRendererComponent vortekRendererComponent = new VortekRendererComponent(xServer, UnixSocketConfig.create(rootPath, UnixSocketConfig.VORTEK_SERVER_PATH), vortekOptions);
        environment.addComponent(vortekRendererComponent);

        environment.addComponent(new VirGLRendererComponent(xServer, UnixSocketConfig.create(rootPath, UnixSocketConfig.VIRGL_SERVER_PATH)));

        guestProgramLauncherComponent.setEnvVars(envVars);
        guestProgramLauncherComponent.setTerminationCallback((status) -> exit());
        environment.addComponent(guestProgramLauncherComponent);

        if (isGenerateWineprefix()) {
            wineInfo = getIntent().getParcelableExtra("wine_info");
            if (wineInfo != null) WineInstaller.generateWineprefix(wineInfo, environment);
        }
        if (overrideEnvVars != null) {
            envVars.putAll(overrideEnvVars);
            overrideEnvVars = null;
        }
        environment.startEnvironmentComponents();

        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            try {
                if (!isFinishing() && !isDestroyed()) {
                    if (xServerView != null && xServerView.getRenderer() != null) {
                        xServerView.getRenderer().setCursorVisible(true);
                    }
                    if (preloaderDialog != null) {
                        preloaderDialog.close();
                    }
                }
            } catch (Throwable ignored) {}
        }, 7000);

        winHandler.start();
        if (pendingExecCommand != null && !pendingExecCommand.isEmpty()) {
            final String cmdToRun = pendingExecCommand;
            final String argsToRun = pendingExecArgs != null ? pendingExecArgs : "";
            pendingExecCommand = null;
            pendingExecArgs = "";
            winHandler.exec(cmdToRun, argsToRun);
        }
        envVars.clear();
        graphicsDriver = null;
        dxwrapperConfig = null;
        graphicsDriverConfig = null;
        audioDriver = null;
        audioDriverConfig = null;
        wincomponents = null;
    }

    private void setupUI() {
        FrameLayout rootView = findViewById(R.id.FLXServerDisplay);
        xServerView = new XServerView(this, xServer);
        final GLRenderer renderer = xServerView.getRenderer();
        renderer.setCursorVisible(true);
        renderer.setCursorColor(preferences.getInt("cursor_color", 0xffffff));
        renderer.setCursorScale(preferences.getFloat("cursor_scale", 1.35f));
        renderer.setForceWindowsFullscreen(shortcut != null && shortcut.getExtra("forceFullscreen", "0").equals("1"));

        xServer.setRenderer(renderer);
        rootView.addView(xServerView);

        globalCursorSpeed = preferences.getFloat("cursor_speed", 1.15f);
        capturePointerOnExternalMouse = preferences.getBoolean("capture_pointer_on_external_mouse", true);
        if (!preferences.getBoolean("v125_touchpad_relative_fixed", false)) {
            preferences.edit()
                .putBoolean("move_cursor_to_touchpoint", false)
                .putInt("default_touch_controls_profile_id", 5)
                .putBoolean("v125_touchpad_relative_fixed", true)
                .apply();
        }
        touchpadView = new TouchpadView(this, xServer, capturePointerOnExternalMouse);
        touchpadView.setSensitivity(globalCursorSpeed);
        touchpadView.setMoveCursorToTouchpoint(preferences.getBoolean("move_cursor_to_touchpoint", false));
        touchpadView.setFourFingersTapCallback(() -> {
            if (!drawerLayout.isDrawerOpen(GravityCompat.START)) drawerLayout.openDrawer(GravityCompat.START);
        });
        rootView.addView(touchpadView);

        inputControlsView = new InputControlsView(this);
        inputControlsView.setOverlayOpacity(preferences.getFloat("overlay_opacity", InputControlsView.DEFAULT_OVERLAY_OPACITY));
        inputControlsView.setTouchpadView(touchpadView);
        if (capturePointerOnExternalMouse) {
            inputControlsView.setOnCapturedPointerListener((view, event) ->
                touchpadView.isEnabled() && touchpadView.onCapturedPointer(view, event));
        }
        inputControlsView.setXServer(xServer);
        inputControlsView.setVisibility(View.GONE);
        rootView.addView(inputControlsView);

        // v12.6.1: счётчик FPS работает всегда (FPS виден в HUD); сам оверлей — только если включён кнопкой FPS
        frameRating = new FrameRating(this);
        boolean fpsOverlayOn = container != null && container.getHUDMode() != FrameRating.Mode.DISABLED.ordinal();
        frameRating.setMode(fpsOverlayOn ? FrameRating.Mode.values()[container.getHUDMode()] : FrameRating.Mode.SIMPLE);
        frameRating.setShowOverlay(fpsOverlayOn);
        frameRating.setVisibility(View.GONE);
        rootView.addView(frameRating);

        // v12.6: обновляем пресеты ДО первого чтения профилей (иначе в первой сессии грузился старый пресет 5)
        WinlatorProSuite.ensureRichControlPresetsInstalled(this);
        ControlsProfile activeProfile = null;
        if (shortcut != null) {
            String controlsProfile = shortcut.getExtra("controlsProfile");
            if (!controlsProfile.isEmpty()) {
                try {
                    activeProfile = inputControlsManager.getProfile(Integer.parseInt(controlsProfile));
                } catch (NumberFormatException ignored) {}
            }
        }
        // v12.6: на голом Рабочем столе Windows (без игры/ярлыка) геймпад не показываем — включается кнопкой «Геймпад» в HUD
        boolean desktopOnlySession = shortcut == null && !getIntent().hasExtra("exec_path");
        if (activeProfile == null && !desktopOnlySession && preferences.getBoolean("auto_show_touch_controls", true)) {
            int savedProfileId = preferences.getInt("default_touch_controls_profile_id", 5);
            activeProfile = inputControlsManager.getProfile(savedProfileId);
            if (activeProfile == null) activeProfile = inputControlsManager.getProfile(5);
            if (activeProfile == null) activeProfile = inputControlsManager.getProfile(3);
            if (activeProfile == null) {
                ArrayList<ControlsProfile> allProfiles = inputControlsManager.getProfiles(true);
                if (!allProfiles.isEmpty()) activeProfile = allProfiles.get(0);
            }
        }
        if (activeProfile != null) {
            showInputControls(activeProfile);
        }

        if (MainActivity.DEBUG_MODE) rootView.addView(AppUtils.createDebugMsgTextView(this));
        AppUtils.observeSoftKeyboardVisibility(drawerLayout, renderer::setScreenOffsetYRelativeToCursor);

        String hudTargetName = getIntent().getStringExtra("exec_path");
        if ((hudTargetName == null || hudTargetName.isEmpty()) && shortcut != null) {
            hudTargetName = !shortcut.wmClass.isEmpty() ? shortcut.wmClass : shortcut.name;
        }
        WinlatorProSuite.attachLiveDesktopHudAndOverlayCustomizer(
            this,
            rootView,
            inputControlsView,
            touchpadView,
            container,
            hudTargetName
        );
    }

    private void showInputControlsDialog() {
        final ContentDialog dialog = new ContentDialog(this, R.layout.input_controls_dialog);
        dialog.setTitle(R.string.input_controls);
        dialog.setIcon(R.drawable.icon_input_controls);

        final Spinner sProfile = dialog.findViewById(R.id.SProfile);
        Runnable loadProfileSpinner = () -> {
            ArrayList<ControlsProfile> profiles = inputControlsManager.getProfiles(true);
            ArrayList<String> profileItems = new ArrayList<>();
            int selectedPosition = 0;
            profileItems.add("-- "+getString(R.string.disabled)+" --");
            for (int i = 0; i < profiles.size(); i++) {
                ControlsProfile profile = profiles.get(i);
                if (profile == inputControlsView.getProfile()) selectedPosition = i + 1;
                profileItems.add(profile.getName());
            }

            sProfile.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, profileItems));
            sProfile.setSelection(selectedPosition);
        };
        loadProfileSpinner.run();

        final CheckBox cbRelativeMouseMovement = dialog.findViewById(R.id.CBRelativeMouseMovement);
        cbRelativeMouseMovement.setChecked(xServer.isRelativeMouseMovement());

        final CheckBox cbShowTouchscreenControls = dialog.findViewById(R.id.CBShowTouchscreenControls);
        cbShowTouchscreenControls.setChecked(inputControlsView.isShowTouchscreenControls());

        dialog.findViewById(R.id.BTSettings).setOnClickListener((v) -> {
            int position = sProfile.getSelectedItemPosition();
            Intent intent = new Intent(this, MainActivity.class);
            intent.putExtra("edit_input_controls", true);
            intent.putExtra("selected_profile_id", position > 0 ? inputControlsManager.getProfiles().get(position - 1).id : 0);
            editInputControlsCallback = () -> {
                hideInputControls();
                inputControlsManager.loadProfiles(true);
                loadProfileSpinner.run();
            };
            startActivityForResult(intent, MainActivity.EDIT_INPUT_CONTROLS_REQUEST_CODE);
        });

        dialog.setOnConfirmCallback(() -> {
            xServer.setRelativeMouseMovement(cbRelativeMouseMovement.isChecked());
            inputControlsView.setShowTouchscreenControls(cbShowTouchscreenControls.isChecked());
            int position = sProfile.getSelectedItemPosition();
            if (position > 0) {
                showInputControls(inputControlsManager.getProfiles().get(position - 1));
            }
            else hideInputControls();
        });

        dialog.show();
    }

    public void showInputControls(ControlsProfile profile) {
        inputControlsView.setVisibility(View.VISIBLE);
        inputControlsView.requestFocus();
        inputControlsView.setProfile(profile);

        touchpadView.setSensitivity(profile.getCursorSpeed() * globalCursorSpeed);
        touchpadView.setPointerButtonLeftEnabled(true);
        touchpadView.setPointerButtonRightEnabled(true);

        GLRenderer renderer = xServerView.getRenderer();
        renderer.setCursorVisible(true);
        touchpadView.setEnabled(true);

        inputControlsView.invalidate();
    }

    public void hideInputControls() {
        inputControlsView.setShowTouchscreenControls(true);
        inputControlsView.setVisibility(View.GONE);
        inputControlsView.setProfile(null);

        touchpadView.setSensitivity(globalCursorSpeed);
        touchpadView.setPointerButtonLeftEnabled(true);
        touchpadView.setPointerButtonRightEnabled(true);

        if (!touchpadView.isEnabled()) {
            touchpadView.setEnabled(true);
            xServerView.getRenderer().setCursorVisible(true);
        }

        inputControlsView.invalidate();
    }

    private void extractGraphicsDriverFiles() {
        envVars.put("vblank_mode", "0");

        String cacheId = "v12.4-";
        if (graphicsDriver[0].equals(GraphicsDrivers.TURNIP)) {
            cacheId += graphicsDriver[0]+"-"+graphicsDriverConfig[0].get("version", DefaultVersion.TURNIP);
        }
        else cacheId += graphicsDriver[0]+"-"+DefaultVersion.valueOf(graphicsDriver[0]);
        cacheId += "-"+graphicsDriver[1]+"-"+DefaultVersion.valueOf(graphicsDriver[1]);

        String currentGraphicsDriver = preferences.getString("current_graphics_driver", "");
        File rootDir = rootFS.getRootDir();
        File libDir = rootFS.getLibDir();
        File vulkanICDDir = new File(rootDir, "/usr/share/vulkan/icd.d");
        File turnipIcdFile = new File(vulkanICDDir, "freedreno_icd.aarch64.json");
        File vortekIcdFile = new File(vulkanICDDir, "vortek_icd.aarch64.json");
        boolean missingLibs = !new File(libDir, "libvulkan_vortek.so").isFile()
                || !new File(libDir, "libvulkan_freedreno.so").isFile()
                || !new File(libDir, "libGL.so.1.7.0").isFile()
                || !turnipIcdFile.isFile()
                || !vortekIcdFile.isFile();
        boolean changed = !cacheId.equals(currentGraphicsDriver) || missingLibs;

        if (changed) {
            FileUtils.delete(new File(libDir, "libvulkan_freedreno.so"));
            FileUtils.delete(new File(libDir, "libvulkan_vortek.so"));
            FileUtils.delete(new File(libDir, "libGL.so.1.7.0"));

            FileUtils.delete(vulkanICDDir);
            vulkanICDDir.mkdirs();

            String version = graphicsDriverConfig[0].get("version", DefaultVersion.TURNIP);
            GeneralComponents.extractFile(GeneralComponents.Type.TURNIP, this, version, DefaultVersion.TURNIP);
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/vortek-" + DefaultVersion.VORTEK + ".tzst", rootDir);

            preferences.edit().putString("current_graphics_driver", cacheId).apply();
        }

        String icdBase = vulkanICDDir.getAbsolutePath();
        if (graphicsDriver[0].equals(GraphicsDrivers.TURNIP) && com.winlator.core.GPUHelper.isTurnipSupported(this)) {
            TurnipConfigDialog.setEnvVars(this, graphicsDriverConfig[0], envVars);
            envVars.put("VK_ICD_FILENAMES", icdBase + "/freedreno_icd.aarch64.json:" + icdBase + "/vortek_icd.aarch64.json");
        }
        else {
            envVars.put("VK_ICD_FILENAMES", icdBase + "/vortek_icd.aarch64.json:" + icdBase + "/freedreno_icd.aarch64.json");
        }

        switch (graphicsDriver[1]) {
            case GraphicsDrivers.ZINK:
                envVars.put("GALLIUM_DRIVER", "zink");
                envVars.put("ZINK_CONTEXT_THREADED", "1");
                envVars.put("MESA_GL_VERSION_OVERRIDE", "4.3");
                envVars.put("MESA_GLSL_VERSION_OVERRIDE", "430");

                if (changed) TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/zink-"+DefaultVersion.ZINK+".tzst", rootDir);
                break;
            case GraphicsDrivers.VIRGL:
                envVars.put("GALLIUM_DRIVER", "virpipe");
                envVars.put("VIRGL_NO_READBACK", "true");
                envVars.put("VIRGL_SERVER_PATH", rootDir+UnixSocketConfig.VIRGL_SERVER_PATH);
                VirGLConfigDialog.setEnvVars(graphicsDriverConfig[1], envVars);

                if (changed) TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/virgl-"+DefaultVersion.VIRGL+".tzst", rootDir);
                break;
            case GraphicsDrivers.GLADIO:
                envVars.put("GALLIUM_DRIVER", "virpipe");
                envVars.put("VIRGL_NO_READBACK", "true");
                envVars.put("VIRGL_SERVER_PATH", rootDir+UnixSocketConfig.VIRGL_SERVER_PATH);
                VirGLConfigDialog.setEnvVars(graphicsDriverConfig[1], envVars);

                if (changed || MainActivity.DEBUG_MODE) TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/virgl-"+DefaultVersion.VIRGL+".tzst", rootDir);
                break;
        }
    }

    private void showTouchpadHelpDialog() {
        ContentDialog dialog = new ContentDialog(this, R.layout.touchpad_help_dialog);
        dialog.setTitle(R.string.touchpad_help);
        dialog.setIcon(R.drawable.icon_help);
        dialog.findViewById(R.id.BTCancel).setVisibility(View.GONE);
        dialog.show();
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        return !winHandler.onGenericMotionEvent(event) && !touchpadView.onExternalMouseEvent(event) && super.dispatchGenericMotionEvent(event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        return (!inputControlsView.onKeyEvent(event) && !winHandler.onKeyEvent(event) && xServer.keyboard.onKeyEvent(event)) ||
               (!ExternalController.isGameController(event.getDevice()) && super.dispatchKeyEvent(event));
    }

    public InputControlsView getInputControlsView() {
        return inputControlsView;
    }

    private boolean extractDXWrapperFiles() {
        WineD3DConfigDialog.setEnvVars(dxwrapperConfig[0], envVars);
        String cacheId = "v12.4-";
        String requestedDxvkVer = dxwrapperConfig[0].get("version", DefaultVersion.DXVK(graphicsDriver[0]));
        if (requestedDxvkVer.isEmpty() || requestedDxvkVer.equals("1.7.2+2.4.1") || requestedDxvkVer.equals("hybrid") || requestedDxvkVer.equals(WineInfo.MAIN_WINE_VERSION)) {
            requestedDxvkVer = DefaultVersion.HYBRID_DXVK;
            if (dxwrapper.equals(DXWrappers.DXVK)) {
                dxwrapperConfig[0].put("version", requestedDxvkVer);
            }
        }

        if (dxwrapper.equals(DXWrappers.DXVK)) {
            DXVKConfigDialog.setEnvVars(this, dxwrapperConfig[0], envVars);
            envVars.put("WINEDLLOVERRIDES", "mscoree,mshtml=d;d3d11,dxgi,d3d10core,d3d10,d3d10_1,d3d9,d3d8,d3dcompiler_47,d3dcompiler_46,d3dcompiler_43=n,b");
            cacheId += dxwrapper+"-"+requestedDxvkVer;
        }
        else if (dxwrapper.equals(DXWrappers.WINED3D)) {
            envVars.put("WINEDLLOVERRIDES", "mscoree,mshtml=d;d3d11,dxgi,d3d10core,d3d10,d3d10_1,d3d9,d3d8,wined3d=b,n;d3dcompiler_47,d3dcompiler_46,d3dcompiler_43=n,b");
            cacheId += dxwrapper+"-"+WineInfo.MAIN_WINE_VERSION;
        }

        File rootDir = rootFS.getRootDir();
        File windowsDir = new File(rootDir, RootFS.WINEPREFIX+"/drive_c/windows");
        File sys32D3d11 = new File(windowsDir, "system32/d3d11.dll");
        boolean missingDxvkDll = dxwrapper.equals(DXWrappers.DXVK) && (!sys32D3d11.isFile() || sys32D3d11.length() < 1000000L);
        boolean staleDxvkWhenWineD3D = dxwrapper.equals(DXWrappers.WINED3D) && (!sys32D3d11.isFile() || sys32D3d11.length() > 1000000L);

        String ddrawWrapper = dxwrapperConfig[0].get("ddrawWrapper", DXWrappers.WINED3D);
        cacheId += "-"+DXWrappers.VKD3D+"-"+dxwrapperConfig[1].get("version", DefaultVersion.VKD3D)+"-"+ddrawWrapper;
        boolean changed = !cacheId.equals(container.getExtra("dxwrapper")) || missingDxvkDll || staleDxvkWhenWineD3D;
        VKD3DConfigDialog.setEnvVars(dxwrapperConfig[1], envVars);

        if (ddrawWrapper.equals(DXWrappers.CNC_DDRAW)) envVars.put("CNC_DDRAW_CONFIG_FILE", "C:\\ProgramData\\cnc-ddraw\\ddraw.ini");

        if (!changed) return false;
        container.putExtra("dxwrapper", cacheId);

        if (dxwrapper.equals(DXWrappers.WINED3D)) {
            final String[] dlls = {"d3d8.dll", "d3d9.dll", "d3d10.dll", "d3d10_1.dll", "d3d10core.dll", "d3d11.dll", "d3d12.dll", "d3d12core.dll", "dxgi.dll", "ddraw.dll", "wined3d.dll"};
            restoreBuiltinDllFiles(dlls);
            String version = dxwrapperConfig[0].get("version", DefaultVersion.WINED3D);
            if (!version.equals(WineInfo.MAIN_WINE_VERSION) && !version.contains("1.7.2") && !version.contains("1.10.3") && !version.contains("2.4.1")) {
                GeneralComponents.extractFile(GeneralComponents.Type.WINED3D, this, version, DefaultVersion.WINED3D);
            }
        }
        else if (dxwrapper.equals(DXWrappers.DXVK)) {
            final boolean[] hasD3D8DllFile = {false};
            final boolean[] hasD3D10DllFile = {false};

            GeneralComponents.extractFile(GeneralComponents.Type.DXVK, this, requestedDxvkVer, DefaultVersion.DXVK(graphicsDriver[0]), (destination, size) -> {
                String name = destination.getName();
                if (name.equals("d3d10.dll")) {
                    hasD3D10DllFile[0] = true;
                }
                else if (name.equals("d3d8.dll")) {
                    hasD3D8DllFile[0] = true;
                }
                return destination;
            });

            if (!hasD3D8DllFile[0]) {
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "dxwrapper/d8vk-"+DefaultVersion.D8VK+".tzst", windowsDir);
            }
            if (!hasD3D10DllFile[0]) restoreBuiltinDllFiles("d3d10.dll", "d3d10_1.dll");
        }

        GeneralComponents.extractFile(GeneralComponents.Type.VKD3D, this, dxwrapperConfig[1].get("version"), DefaultVersion.VKD3D);

        File containerSysWoW64Dir = new File(rootDir, RootFS.WINEPREFIX+"/drive_c/windows/syswow64");
        FileUtils.delete(new File(containerSysWoW64Dir, "ddraw_.dll"));

        switch (ddrawWrapper) {
            case DXWrappers.CNC_DDRAW:
                final String assetDir = "dxwrapper/cnc-ddraw-"+DefaultVersion.CNC_DDRAW;
                File configFile = new File(rootDir, RootFS.WINEPREFIX+"/drive_c/ProgramData/cnc-ddraw/ddraw.ini");
                if (!configFile.isFile()) FileUtils.copy(this, assetDir+"/ddraw.ini", configFile);
                File shadersDir = new File(rootDir, RootFS.WINEPREFIX+"/drive_c/ProgramData/cnc-ddraw/Shaders");
                FileUtils.delete(shadersDir);
                FileUtils.copy(this, assetDir+"/Shaders", shadersDir);
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, assetDir+"/ddraw.tzst", windowsDir);
                break;
            case DXWrappers.D7VK:
                restoreBuiltinDllFiles("ddraw.dll");
                (new File(containerSysWoW64Dir, "ddraw.dll")).renameTo(new File(containerSysWoW64Dir, "ddraw_.dll"));
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "dxwrapper/d7vk-"+DefaultVersion.D7VK+".tzst", windowsDir);
                break;
            default:
                restoreBuiltinDllFiles("ddraw.dll");
                break;
        }
        return true;
    }

    private void extractWinComponentFiles() {
        File rootDir = rootFS.getRootDir();
        File windowsDir = new File(rootDir, RootFS.WINEPREFIX+"/drive_c/windows");
        File systemRegFile = new File(rootDir, RootFS.WINEPREFIX+"/system.reg");

        try {
            JSONObject wincomponentsJSONObject = new JSONObject(FileUtils.readString(this, "wincomponents/wincomponents.json"));
            Iterator<String[]> oldWinComponentsIter = new KeyValueSet(container.getExtra("wincomponents", Container.FALLBACK_WINCOMPONENTS)).iterator();
            ArrayList<String> builtinDlls = new ArrayList<>();

            for (String[] wincomponent : new KeyValueSet(wincomponents)) {
                if (wincomponent[1].equals(oldWinComponentsIter.next()[1])) continue;
                String identifier = wincomponent[0];
                boolean useNative = wincomponent[1].equals("1");

                if (useNative) {
                    TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "wincomponents/"+identifier+".tzst", windowsDir);
                }
                else {
                    JSONObject wincomponentJSONObject = wincomponentsJSONObject.getJSONObject(identifier);
                    if (wincomponentJSONObject.getBoolean("restoreBuiltinDlls")) {
                        JSONArray dlnames = wincomponentJSONObject.getJSONArray("dlnames");
                        for (int i = 0; i < dlnames.length(); i++) {
                            String dlname = dlnames.getString(i);
                            builtinDlls.add(!dlname.endsWith(".exe") ? dlname+".dll" : dlname);
                        }
                    }
                    else {
                        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "wincomponents/"+identifier+".tzst", windowsDir, (destination, size) -> {
                            String name = destination.getName();
                            if (name.endsWith(".dll") || name.endsWith(".manifest") || name.endsWith("_deadbeef")) FileUtils.delete(destination);
                            return null;
                        });
                    }
                }

                WineUtils.setWinComponentRegistryKeys(systemRegFile, identifier, useNative);
            }

            if (!builtinDlls.isEmpty()) restoreBuiltinDllFiles(builtinDlls.toArray(new String[0]));
            WineUtils.overrideWinComponentDlls(this, container, wincomponents);
        }
        catch (JSONException e) {}
    }

    private void restoreBuiltinDllFiles(final String... dlls) {
        File rootDir = rootFS.getRootDir();
        File wineDir = new File(rootDir, rootFS.getWinePath());
        File wineSystem32Dir = new File(wineDir, "/lib/wine/x86_64-windows");
        File wineSysWoW64Dir = new File(wineDir, "/lib/wine/i386-windows");
        File containerSystem32Dir = new File(rootDir, RootFS.WINEPREFIX+"/drive_c/windows/system32");
        File containerSysWoW64Dir = new File(rootDir, RootFS.WINEPREFIX+"/drive_c/windows/syswow64");;

        for (String dll : dlls) {
            FileUtils.copy(new File(wineSysWoW64Dir, dll), new File(containerSysWoW64Dir, dll));
            FileUtils.copy(new File(wineSystem32Dir, dll), new File(containerSystem32Dir, dll));
        }
    }

    private boolean isGenerateWineprefix() {
        return getIntent().getBooleanExtra("generate_wineprefix", false);
    }

    private String getWineStartCommand() {
        String cmdArgs = "";
        String execPath = null;
        String execArgs = "";
        pendingExecCommand = null;
        pendingExecArgs = "";

        if (shortcut != null) {
            execArgs = shortcut.getExtra("execArgs");
            execArgs = !execArgs.isEmpty() ? " "+execArgs : "";

            Intent intent = getIntent();
            if (intent != null && intent.hasExtra("exec_path")) {
                String intentExec = intent.getStringExtra("exec_path");
                if (intentExec != null && !intentExec.isEmpty()) {
                    execPath = WineUtils.unixToDOSPath(intentExec, container);
                }
            }
            if (execPath == null || execPath.isEmpty()) {
                if (shortcut.isLinkPath()) {
                    String unixLnkPath = WineUtils.dosToUnixPath(shortcut.path, container);
                    com.winlator.win32.MSLink.LinkInfo linkInfo = unixLnkPath != null ? com.winlator.win32.MSLink.extractLinkInfo(new File(unixLnkPath)) : null;
                    if (linkInfo != null && linkInfo.targetPath != null && !linkInfo.targetPath.isEmpty() && !linkInfo.targetPath.endsWith("\\")) {
                        execPath = linkInfo.targetPath;
                        if (linkInfo.arguments != null && !linkInfo.arguments.isEmpty()) {
                            execArgs += " " + linkInfo.arguments;
                        }
                    } else {
                        pendingExecCommand = "C:\\windows\\system32\\cmd.exe";
                        pendingExecArgs = "/c start \"\" \"" + shortcut.path + "\"" + execArgs;
                        String linkDir = FileUtils.getDirname(shortcut.path);
                        if (linkDir != null && !linkDir.isEmpty() && linkDir.contains(":")) {
                            cmdArgs = "/dir " + StringUtils.escapeDOSPath(linkDir) + " \"wfm.exe\"";
                        }
                    }
                }
                else execPath = shortcut.path;
            }
        }
        else {
            Intent intent = getIntent();
            if (intent.hasExtra("exec_path")) {
                String rawIntentPath = intent.getStringExtra("exec_path");
                execPath = WineUtils.unixToDOSPath(rawIntentPath, container);

                if (execPath != null && execPath.toLowerCase(java.util.Locale.ENGLISH).endsWith(".lnk")) {
                    File lnkUnix = new File(rawIntentPath);
                    com.winlator.win32.MSLink.LinkInfo linkInfo = com.winlator.win32.MSLink.extractLinkInfo(lnkUnix);
                    if (linkInfo != null && linkInfo.targetPath != null && !linkInfo.targetPath.isEmpty() && !linkInfo.targetPath.endsWith("\\")) {
                        execPath = linkInfo.targetPath;
                        if (linkInfo.arguments != null && !linkInfo.arguments.isEmpty()) {
                            execArgs = " " + linkInfo.arguments;
                        }
                    } else {
                        pendingExecCommand = "C:\\windows\\system32\\cmd.exe";
                        pendingExecArgs = "/c start \"\" \"" + execPath + "\"";
                        execPath = null;
                    }
                }
            }
        }

        if (execPath != null && !execPath.isEmpty() && !execPath.equals("\\") && !execPath.endsWith("\\")) {
            try {
                String execDir = FileUtils.getDirname(execPath);
                String filename = FileUtils.getName(execPath);
                int dotIndex, spaceIndex;
                if ((dotIndex = filename.lastIndexOf(".")) != -1 && (spaceIndex = filename.indexOf(" ", dotIndex)) != -1) {
                    execArgs = filename.substring(spaceIndex+1)+execArgs;
                    filename = filename.substring(0, spaceIndex);
                }
                if (execDir == null || execDir.isEmpty() || !execDir.contains(":")) {
                    execDir = "C:\\windows";
                }
                String fullTargetDos = execDir + (execDir.endsWith("\\") ? "" : "\\") + filename;
                String lowerFile = filename.toLowerCase(java.util.Locale.ENGLISH);
                if (lowerFile.endsWith(".msi")) {
                    pendingExecCommand = "C:\\windows\\system32\\msiexec.exe";
                    pendingExecArgs = "/i \"" + fullTargetDos + "\"" + execArgs;
                    cmdArgs = "/dir "+StringUtils.escapeDOSPath(execDir)+" \"C:\\windows\\wfm.exe\"";
                } else if (lowerFile.endsWith(".bat") || lowerFile.endsWith(".cmd")) {
                    pendingExecCommand = "C:\\windows\\system32\\cmd.exe";
                    pendingExecArgs = "/c \"" + fullTargetDos + "\"" + execArgs;
                    cmdArgs = "/dir "+StringUtils.escapeDOSPath(execDir)+" \"C:\\windows\\wfm.exe\"";
                } else {
                    cmdArgs = "/dir " + StringUtils.escapeDOSPath(execDir) + " \"" + filename + "\"" + execArgs;
                }
            } catch (Throwable ignored) {}
        }

        if (cmdArgs.isEmpty()) cmdArgs = "\"wfm.exe\"";

        if (overrideEnvVars != null && overrideEnvVars.has("EXTRA_EXEC_ARGS")) {
            cmdArgs += " "+overrideEnvVars.get("EXTRA_EXEC_ARGS");
            overrideEnvVars.remove("EXTRA_EXEC_ARGS");
        }
        return "C:\\windows\\winhandler.exe "+cmdArgs;
    }

    public XServer getXServer() {
        return xServer;
    }

    public File getLaunchTargetExe() {
        return launchTargetExe;
    }

    public FrameRating getFrameRating() {
        return frameRating;
    }

    public WinHandler getWinHandler() {
        return winHandler;
    }

    public XServerView getXServerView() {
        return xServerView;
    }

    public Container getContainer() {
        return container;
    }

    public RootFS getRootFs() {
        return rootFS;
    }

    public EnvVars getOverrideEnvVars() {
        if (overrideEnvVars == null) overrideEnvVars = new EnvVars();
        return overrideEnvVars;
    }

    public String getDXWrapper() {
        return dxwrapper;
    }

    public void setDXWrapper(String dxwrapper) {
        this.dxwrapper = dxwrapper;
    }

    public ScreenInfo getScreenInfo() {
        return screenInfo;
    }

    public void setScreenInfo(ScreenInfo screenInfo) {
        this.screenInfo = screenInfo;
    }

    private ScreenInfo resolveScreenInfo(String value) {
        if (!value.equals("native")) return new ScreenInfo(value);

        int width = AppUtils.getScreenWidth();
        int height = AppUtils.getScreenHeight();
        return new ScreenInfo(width - (width % 2), height - (height % 2));
    }

    public String getWinComponents() {
        return wincomponents;
    }

    public void setWinComponents(String wincomponents) {
        this.wincomponents = wincomponents;
    }

    public DebugDialog getDebugDialog() {
        return debugDialog;
    }

    public String getScreenEffectProfile() {
        return screenEffectProfile;
    }

    public void setScreenEffectProfile(String screenEffectProfile) {
        this.screenEffectProfile = screenEffectProfile;
    }

    private void changeWineAudioDriver() {
        if (!audioDriver.equals(container.getExtra("audioDriver"))) {
            File rootDir = rootFS.getRootDir();
            File userRegFile = new File(rootDir, RootFS.WINEPREFIX+"/user.reg");
            try (WineRegistryEditor registryEditor = new WineRegistryEditor(userRegFile)) {
                if (audioDriver.equals(AudioDrivers.ALSA)) {
                    registryEditor.setStringValue("Software\\Wine\\Drivers", "Audio", "alsa");
                }
                else if (audioDriver.equals(AudioDrivers.PULSEAUDIO)) {
                    registryEditor.setStringValue("Software\\Wine\\Drivers", "Audio", "pulse");
                }
            }
            container.putExtra("audioDriver", audioDriver);
            container.saveData();
        }
    }

    private void applyGeneralPatches(Container container) {
        File rootDir = rootFS.getRootDir();
        FileUtils.delete(new File(rootDir, "/opt/apps"));
        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "rootfs_patches.tzst", rootDir);
        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "pulseaudio.tzst", new File(getFilesDir(), "pulseaudio"));
        WineUtils.applySystemTweaks(this, wineInfo);
        container.putExtra("dxwrapper", null);
        container.putExtra("desktopTheme", null);
        SettingsFragment.resetPreferenceVersions(this);
    }

    public void changeFrameRatingVisibility(Window window, boolean visible) {
        if (frameRating == null) return;
        if (visible) {
            if (window.id == frameRatingWindowId) return;
            Window child = window.getChildAt(0);
            boolean viewable = window.attributes.isMapped() && window.getWidth() >= ScreenInfo.MIN_WIDTH && window.getHeight() >= ScreenInfo.MIN_HEIGHT;
            Window frameRatingWindow = null;
            if (viewable && (window.isSurface() || (child != null && child.isSurface()))) {
                frameRatingWindow = window.isSurface() ? window : child;
            }
            else if (window.isSurface() && !window.isApplicationWindow()) {
                Window parent = window.getParent();
                if (parent != null && parent.isApplicationWindow() && !parent.isSurface()) frameRatingWindow = window;
            }

            if (frameRatingWindow != null) {
                if (frameRating.getMode() == FrameRating.Mode.FULL) {
                    Property gpuInfo = frameRatingWindow.getProperty(Atom._NET_WM_GPU_INFO);
                    frameRating.setGPUInfo(gpuInfo != null ? new String(gpuInfo.data.array()) : "N/A");
                }
                frameRatingWindowId = frameRatingWindow.id;
                frameRating.reset();
            }
        }
        else if (window.id == frameRatingWindowId) {
            frameRatingWindowId = -1;
            runOnUiThread(() -> frameRating.setVisibility(View.GONE));
        }
    }

    public boolean verifyUserRegistry() {
        File userRegFile = new File(rootFS.getRootDir(), RootFS.WINEPREFIX+"/user.reg");
        String lastModified = String.valueOf(userRegFile.lastModified());

        if (!lastModified.equals(container.getExtra("userRegLastModified"))) {
            try (WineRegistryEditor registryEditor = new WineRegistryEditor(userRegFile)) {
                registryEditor.removeKey("Software\\Wow6432Node\\Wine", true);
            }

            container.putExtra("userRegLastModified", lastModified);
            return true;
        }
        else return false;
    }
}