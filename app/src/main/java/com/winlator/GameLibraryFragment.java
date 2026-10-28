package com.winlator;

import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import androidx.appcompat.widget.AppCompatButton;
import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.container.Shortcut;
import com.winlator.contentdialog.ContentDialog;
import com.winlator.core.AppUtils;
import com.winlator.core.FileUtils;
import com.winlator.core.WineUtils;
import com.winlator.pro.WinlatorProSuite;
import com.winlator.store.EpicLibraryBackend;
import com.winlator.store.SteamLibraryBackend;
import com.winlator.store.StoreInstallerDownloader;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Hybrid-native library view backed by the existing container desktop shortcuts.
 * Store adapters can add their owned/installed games to this same launch surface
 * without introducing a second runtime or another application.
 */
public class GameLibraryFragment extends Fragment {
    private ContainerManager containerManager;
    private RecyclerView recyclerView;
    private TextView emptyView;
    private GameAdapter adapter;
    private boolean installerDownloadActive;
    private boolean storeSyncActive;
    private long lastStoreSyncMillis;
    private final ExecutorService storeWorker = Executors.newSingleThreadExecutor();

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        containerManager = new ContainerManager(requireContext());
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup parent, @Nullable Bundle state) {
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        int horizontal = dp(20);
        root.setPadding(horizontal, dp(12), horizontal, dp(8));

        TextView header = new TextView(requireContext());
        header.setText(R.string.game_library);
        header.setTextSize(24);
        header.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView description = new TextView(requireContext());
        description.setText(R.string.game_library_description);
        description.setTextSize(14);
        description.setPadding(0, dp(4), 0, dp(12));
        root.addView(description, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(createSteamCard(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(createEpicCard(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        recyclerView = new RecyclerView(requireContext());
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new GameAdapter();
        recyclerView.setAdapter(adapter);
        root.addView(recyclerView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        emptyView = new TextView(requireContext());
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setText(R.string.game_library_empty);
        emptyView.setTextSize(16);
        root.addView(emptyView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return root;
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshLibrary();
        autoSyncInstalledStoreGames();
    }

    @Override
    public void onDestroy() {
        storeWorker.shutdown();
        super.onDestroy();
    }

    private void autoSyncInstalledStoreGames() {
        long now = System.currentTimeMillis();
        if (storeSyncActive || now - lastStoreSyncMillis < 60000L) return;
        ArrayList<Container> containers = containerManager.getContainers();
        MainActivity activity = (MainActivity) getActivity();
        if (containers.isEmpty() || activity == null) return;
        storeSyncActive = true;
        storeWorker.execute(() -> {
            int changed = 0;
            try {
                for (Container container : containers) {
                    changed += SteamLibraryBackend.syncInstalledGames(container);
                    changed += EpicLibraryBackend.syncInstalledGames(container);
                }
            }
            catch (Throwable ignored) {}
            final int imported = changed;
            activity.runOnUiThread(() -> {
                storeSyncActive = false;
                lastStoreSyncMillis = System.currentTimeMillis();
                if (imported > 0 && isAdded() && getView() != null) refreshLibrary();
            });
        });
    }

    private View createSteamCard() {
        LinearLayout card = new LinearLayout(requireContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(createCardBackground());
        card.setElevation(dp(2));
        LinearLayout column = new LinearLayout(requireContext());
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(16), dp(12), dp(16), dp(12));

        TextView title = new TextView(requireContext());
        title.setText(R.string.steam_backend_title);
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        column.addView(title);

        TextView details = new TextView(requireContext());
        details.setText(R.string.steam_backend_description);
        details.setTextSize(13);
        details.setPadding(0, dp(4), 0, dp(8));
        column.addView(details);

        LinearLayout buttons = new LinearLayout(requireContext());
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        AppCompatButton installOrOpen = new AppCompatButton(requireContext());
        installOrOpen.setText(R.string.steam_backend_install_open);
        installOrOpen.setOnClickListener(view -> chooseContainer(this::installOrOpenSteam));
        buttons.addView(installOrOpen, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        AppCompatButton sync = new AppCompatButton(requireContext());
        sync.setText(R.string.steam_backend_sync);
        sync.setOnClickListener(view -> chooseContainer(this::syncSteam));
        LinearLayout.LayoutParams syncParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        syncParams.setMarginStart(dp(8));
        buttons.addView(sync, syncParams);
        column.addView(buttons);
        card.addView(column);
        return card;
    }

    private View createEpicCard() {
        LinearLayout card = new LinearLayout(requireContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(createCardBackground());
        card.setElevation(dp(2));
        LinearLayout column = new LinearLayout(requireContext());
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(16), dp(12), dp(16), dp(12));

        TextView title = new TextView(requireContext());
        title.setText(R.string.epic_backend_title);
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        column.addView(title);

        TextView details = new TextView(requireContext());
        details.setText(R.string.epic_backend_description);
        details.setTextSize(13);
        details.setPadding(0, dp(4), 0, dp(8));
        column.addView(details);

        LinearLayout buttons = new LinearLayout(requireContext());
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        AppCompatButton installOrOpen = new AppCompatButton(requireContext());
        installOrOpen.setText(R.string.epic_backend_install_open);
        installOrOpen.setOnClickListener(view -> chooseContainer(this::installOrOpenEpic));
        buttons.addView(installOrOpen, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        AppCompatButton sync = new AppCompatButton(requireContext());
        sync.setText(R.string.epic_backend_sync);
        sync.setOnClickListener(view -> chooseContainer(this::syncEpic));
        LinearLayout.LayoutParams syncParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        syncParams.setMarginStart(dp(8));
        buttons.addView(sync, syncParams);
        column.addView(buttons);
        card.addView(column);
        return card;
    }

    private void chooseContainer(ContainerChoice choice) {
        ArrayList<Container> containers = containerManager.getContainers();
        if (containers.isEmpty()) {
            AppUtils.showToast(requireContext(), R.string.steam_backend_no_container);
            return;
        }
        if (containers.size() == 1) {
            choice.onChosen(containers.get(0));
            return;
        }
        String[] names = new String[containers.size()];
        for (int i = 0; i < containers.size(); i++) names[i] = containers.get(i).getName();
        new AlertDialog.Builder(requireContext())
            .setTitle(R.string.steam_backend_choose_container)
            .setItems(names, (dialog, index) -> choice.onChosen(containers.get(index)))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void installOrOpenSteam(Container container) {
        MainActivity activity = (MainActivity) getActivity();
        if (activity == null) return;
        File steamExe = SteamLibraryBackend.getSteamExecutable(container);
        if (steamExe != null) {
            WinlatorProSuite.launchWithMemoryCheck(activity, container, steamExe.getAbsolutePath());
            return;
        }
        ContentDialog.confirm(activity, R.string.steam_backend_install_confirm, () -> {
            if (installerDownloadActive) {
                AppUtils.showToast(activity, R.string.store_download_in_progress);
                return;
            }
            installerDownloadActive = true;
            File installer = SteamLibraryBackend.getInstallerDestination(container);
            StoreInstallerDownloader.download(activity, SteamLibraryBackend.STEAM_SETUP_URL, installer,
                new String[]{"steamstatic.com", "steampowered.com"}, 100000L, 100L * 1024L * 1024L, success -> {
                    installerDownloadActive = false;
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    if (!success) {
                        AppUtils.showToast(activity, R.string.steam_backend_download_failed);
                        return;
                    }
                    WinlatorProSuite.launchWithMemoryCheck(activity, container, installer.getAbsolutePath());
                });
        });
    }

    private void installOrOpenEpic(Container container) {
        MainActivity activity = (MainActivity) getActivity();
        if (activity == null) return;
        File launcher = EpicLibraryBackend.getLauncherExecutable(container);
        if (launcher != null) {
            WinlatorProSuite.launchWithMemoryCheck(activity, container, launcher.getAbsolutePath());
            return;
        }
        ContentDialog.confirm(activity, R.string.epic_backend_install_confirm, () -> {
            if (installerDownloadActive) {
                AppUtils.showToast(activity, R.string.store_download_in_progress);
                return;
            }
            installerDownloadActive = true;
            File installer = EpicLibraryBackend.getInstallerDestination(container);
            StoreInstallerDownloader.download(activity, EpicLibraryBackend.EPIC_INSTALLER_URL, installer,
                new String[]{"epicgames.com", "epicgames-download1.akamaized.net"},
                1000000L, 600L * 1024L * 1024L, success -> {
                    installerDownloadActive = false;
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    if (!success) {
                        AppUtils.showToast(activity, R.string.epic_backend_download_failed);
                        return;
                    }
                    WinlatorProSuite.launchWithMemoryCheck(activity, container, installer.getAbsolutePath());
                });
        });
    }

    private void syncEpic(Container container) {
        syncStoreContainer(container, true);
    }

    private void syncSteam(Container container) {
        syncStoreContainer(container, false);
    }

    private void syncStoreContainer(Container container, boolean epic) {
        MainActivity activity = (MainActivity) getActivity();
        if (activity == null) return;
        if (storeSyncActive) {
            AppUtils.showToast(activity, R.string.store_sync_in_progress);
            return;
        }
        storeSyncActive = true;
        storeWorker.execute(() -> {
            int changed = 0;
            try {
                changed = epic ? EpicLibraryBackend.syncInstalledGames(container) : SteamLibraryBackend.syncInstalledGames(container);
            }
            catch (Throwable ignored) {}
            final int imported = changed;
            activity.runOnUiThread(() -> {
                storeSyncActive = false;
                lastStoreSyncMillis = System.currentTimeMillis();
                if (!isAdded() || getView() == null) return;
                refreshLibrary();
                int messageId = epic ? R.string.epic_backend_sync_complete : R.string.steam_backend_sync_complete;
                AppUtils.showToast(activity, getString(messageId, imported));
            });
        });
    }

    private interface ContainerChoice {
        void onChosen(Container container);
    }

    private void refreshLibrary() {
        if (adapter == null || containerManager == null) return;
        List<Shortcut> games = new ArrayList<>();
        for (Shortcut shortcut : containerManager.loadShortcuts(null)) {
            if (!shortcut.file.isDirectory()) games.add(shortcut);
        }
        adapter.setItems(games);
        boolean isEmpty = games.isEmpty();
        recyclerView.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
        emptyView.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
    }

    private void launchGame(Shortcut shortcut) {
        if (!(getActivity() instanceof MainActivity)) return;
        MainActivity activity = (MainActivity) getActivity();
        Executors.newSingleThreadExecutor().execute(() -> {
            String unixPath = shortcut.getExtra("execUnixPath", "");
            if ((unixPath.isEmpty() || !new File(unixPath).exists()) && shortcut.path != null && !shortcut.path.isEmpty()) {
                unixPath = WineUtils.dosToUnixPath(shortcut.path, shortcut.container);
            }
            if ((unixPath == null || unixPath.isEmpty() || !new File(unixPath).exists()) && shortcut.wmClass != null && !shortcut.wmClass.isEmpty()) {
                File found = FileUtils.findExistingFileOnStorage(shortcut.wmClass, -1);
                if (found != null) unixPath = found.getAbsolutePath();
            }

            File exeFile = (unixPath != null && !unixPath.isEmpty()) ? new File(unixPath) : null;
            boolean steamLaunch = "Steam".equalsIgnoreCase(shortcut.getExtra("storeSource", ""));
            if (exeFile != null && exeFile.exists()) {
                exeFile = WinlatorProSuite.resolveFullFolderExeIfOrphan(exeFile);
                WinlatorProSuite.prepareGameFolderAndDependencies(activity, shortcut.container, steamLaunch ? null : exeFile);
                if (!steamLaunch) WinlatorProSuite.pinExecutableToDesktopWithIcon(activity, shortcut.container, exeFile);
            }
            else {
                WinlatorProSuite.prepareGameFolderAndDependencies(activity, shortcut.container, null);
            }

            final String executablePath = exeFile != null && exeFile.exists() ? exeFile.getAbsolutePath() : null;
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                Intent intent = new Intent(activity, XServerDisplayActivity.class);
                intent.putExtra("container_id", shortcut.container.id);
                intent.putExtra("shortcut_path", shortcut.file.getPath());
                intent.putExtra("always_show_desktop", true);
                if (executablePath != null) intent.putExtra("exec_path", executablePath);
                activity.startActivity(intent);
            });
        });
    }

    private GradientDrawable createCardBackground() {
        TypedValue value = new TypedValue();
        int color = requireContext().getTheme().resolveAttribute(R.attr.colorSecondarySurface, value, true)
            ? value.data : 0xFF2D3238;
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(12));
        return background;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class GameAdapter extends RecyclerView.Adapter<GameAdapter.GameViewHolder> {
        private final List<Shortcut> items = new ArrayList<>();

        void setItems(List<Shortcut> games) {
            items.clear();
            items.addAll(games);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public GameViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout card = new LinearLayout(parent.getContext());
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(createCardBackground());
            card.setElevation(dp(2));
            RecyclerView.LayoutParams cardParams = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cardParams.bottomMargin = dp(10);
            card.setLayoutParams(cardParams);
            card.setClickable(true);
            card.setFocusable(true);

            LinearLayout row = new LinearLayout(parent.getContext());
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(14), dp(12), dp(14));
            row.setOrientation(LinearLayout.HORIZONTAL);

            LinearLayout textColumn = new LinearLayout(parent.getContext());
            textColumn.setOrientation(LinearLayout.VERTICAL);
            row.addView(textColumn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

            TextView title = new TextView(parent.getContext());
            title.setTextSize(17);
            title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            textColumn.addView(title);

            TextView metadata = new TextView(parent.getContext());
            metadata.setTextSize(13);
            metadata.setPadding(0, dp(3), 0, 0);
            textColumn.addView(metadata);

            TextView play = new TextView(parent.getContext());
            play.setText(R.string.run);
            play.setTextColor(0xFF0288D1);
            play.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            play.setPadding(dp(12), dp(8), dp(4), dp(8));
            row.addView(play);
            card.addView(row);
            return new GameViewHolder(card, title, metadata);
        }

        @Override
        public void onBindViewHolder(@NonNull GameViewHolder holder, int position) {
            Shortcut game = items.get(position);
            holder.title.setText(game.name);
            String source = game.getExtra("storeSource", "").trim();
            if (source.isEmpty()) source = getString(R.string.game_library_hybrid_source);
            holder.metadata.setText(getString(R.string.game_library_item_metadata, game.container.getName(), source));
            holder.itemView.setOnClickListener(view -> launchGame(game));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        final class GameViewHolder extends RecyclerView.ViewHolder {
            final TextView title;
            final TextView metadata;

            GameViewHolder(@NonNull View itemView, TextView title, TextView metadata) {
                super(itemView);
                this.title = title;
                this.metadata = metadata;
            }
        }
    }
}
