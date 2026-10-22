package com.winlator;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.util.TypedValue;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.navigation.NavigationView;
import androidx.recyclerview.widget.RecyclerView;

import com.winlator.container.Container;
import com.winlator.container.Shortcut;
import com.winlator.contentdialog.ContentDialog;
import com.winlator.contentdialog.CreateFolderDialog;
import com.winlator.contentdialog.ShortcutSettingsDialog;
import com.winlator.core.AppUtils;
import com.winlator.core.ArrayUtils;
import com.winlator.pro.WinlatorProSuite;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class ShortcutsFragment extends BaseFileManagerFragment<Shortcut> {
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        viewStyle = ViewStyle.valueOf(preferences.getString("shortcuts_view_style", "GRID"));
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        FrameLayout header = view.findViewById(R.id.FLProTopHeader);
        if (header == null || getActivity() == null) return;
        header.removeAllViews();
        header.setVisibility(View.VISIBLE);
        header.addView(buildLauncherHomeCard());
        ((AppCompatActivity)getActivity()).getSupportActionBar().setTitle("Библиотека");
    }

    /** A compact game-library home with direct access to the graphics/performance dashboard. */
    private View buildLauncherHomeCard() {
        Context context = requireContext();
        int pad = dp(14);
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(pad, dp(12), pad, dp(12));
        GradientDrawable background = new GradientDrawable(
            GradientDrawable.Orientation.TL_BR, new int[]{0xFF111C32, 0xFF0B1220});
        background.setCornerRadius(dp(18));
        background.setStroke(dp(1), 0xFF263A5B);
        card.setBackground(background);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(dp(12), dp(8), dp(12), dp(6));
        card.setLayoutParams(cardLp);

        TextView eyebrow = new TextView(context);
        eyebrow.setText("WINLATOR  /  GAME LIBRARY");
        eyebrow.setTextColor(0xFF38BDF8);
        eyebrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        eyebrow.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        card.addView(eyebrow);

        TextView title = new TextView(context);
        title.setText("Игры и приложения");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.topMargin = dp(2);
        card.addView(title, titleLp);

        TextView hint = new TextView(context);
        hint.setText("Ярлыки запускаются отсюда. Настройки графики и производительности — отдельно.");
        hint.setTextColor(0xFF9AAAC2);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.topMargin = dp(2);
        card.addView(hint, hintLp);

        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionsLp.topMargin = dp(10);
        card.addView(actions, actionsLp);
        actions.addView(headerButton(context, "+ Игра / приложение", 0xFF0369A1),
            weightedActionParams());
        actions.addView(headerButton(context, "Графика / FPS", 0xFF5B21B6),
            weightedActionParams());
        actions.addView(headerButton(context, "Контейнеры", 0xFF334155),
            weightedActionParams());
        return card;
    }

    private Button headerButton(Context context, String label, int color) {
        Button button = new Button(context);
        button.setText(label);
        button.setTextColor(Color.WHITE);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        button.setAllCaps(false);
        button.setMinHeight(dp(42));
        button.setMinimumHeight(dp(42));
        button.setPadding(dp(4), 0, dp(4), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(12));
        button.setBackground(bg);
        button.setOnClickListener(v -> {
            Activity activity = getActivity();
            if (activity == null) return;
            int index = ((LinearLayout)v.getParent()).indexOfChild(v);
            Container target = manager != null ? manager.getDefaultOrFirstContainer() : null;
            if (index == 2) {
                NavigationView nav = activity.findViewById(R.id.NavigationView);
                if (nav != null) ((MainActivity)activity).onNavigationItemSelected(
                    nav.getMenu().findItem(R.id.menu_item_containers));
                return;
            }
            if (target == null && manager != null) target = manager.ensureDefaultHybridContainer();
            if (target == null) {
                AppUtils.showToast(activity, "Сначала завершите установку Wine/RootFS.");
                return;
            }
            if (index == 0) WinlatorProSuite.showUniversalExeLauncherDialog(activity, target, this::refreshContent);
            else WinlatorProSuite.showGraphicsFpsShaderStudioDialog(activity, target, null);
        });
        return button;
    }

    private LinearLayout.LayoutParams weightedActionParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(42), 1f);
        lp.rightMargin = dp(5);
        return lp;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void refreshContent() {
        super.refreshContent();

        if (getContext() != null && manager != null) {
            com.winlator.container.Container defCont = manager.getDefaultOrFirstContainer();
            if (defCont != null) {
                // v12.6.2: поиск игр/ярлыков — в фоне и не чаще раза в 30 с (раньше блокировал меню на каждом открытии)
                WinlatorProSuite.syncDesktopShortcutsInBackground(getContext().getApplicationContext(), defCont, added -> {
                    if (added > 0 && isAdded() && getView() != null) refreshListOnly();
                });
            }
        }
        Shortcut selectedFolder = !folderStack.isEmpty() ? folderStack.peek() : null;
        ArrayList<Shortcut> shortcuts = manager.loadShortcuts(selectedFolder);
        recyclerView.setAdapter(new ShortcutsAdapter(shortcuts));
        emptyTextView.setVisibility(shortcuts.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void refreshListOnly() {
        Shortcut selectedFolder = !folderStack.isEmpty() ? folderStack.peek() : null;
        ArrayList<Shortcut> shortcuts = manager.loadShortcuts(selectedFolder);
        recyclerView.setAdapter(new ShortcutsAdapter(shortcuts));
        emptyTextView.setVisibility(shortcuts.isEmpty() ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater menuInflater) {
        menuInflater.inflate(R.menu.shortcuts_menu, menu);
        refreshViewStyleMenuItem(menu.findItem(R.id.menu_item_view_style));
    }

    private void createFolder() {
        clearClipboard();
        if (manager.getContainers().isEmpty()) return;
        CreateFolderDialog createFolderDialog = new CreateFolderDialog(manager);
        createFolderDialog.setOnCreateFolderListener((container, name) -> {
            File desktopDir = new File(container.getUserDir(), "Desktop");
            File parent = !folderStack.isEmpty() ? folderStack.peek().file : desktopDir;
            File file = new File(parent, name);
            if (file.isDirectory()) {
                AppUtils.showToast(getContext(), R.string.there_already_file_with_that_name);
            }
            else {
                file.mkdir();
                refreshContent();
            }
        });
        createFolderDialog.show();
    }

    @Override
    protected void pasteFiles() {
        if (folderStack.isEmpty()) {
            clearClipboard();
            AppUtils.showToast(getContext(), R.string.you_cannot_paste_files_here);
            return;
        }

        clipboard.targetDir = folderStack.peek().file;
        super.pasteFiles();
    }

    private void instantiateClipboard(Shortcut shortcut, boolean cutMode) {
        clearClipboard();
        File[] files = {new File(shortcut.file.getParentFile(), shortcut.file.getName())};
        if (shortcut.file.isFile() && !shortcut.isLinkPath()) {
            File linkFile = shortcut.getLinkFile();
            files = ArrayUtils.concat(files, new File[]{new File(linkFile.getParentFile(), linkFile.getName())});
        }

        clipboard = new Clipboard(files, cutMode);
        pasteButton.setVisibility(View.VISIBLE);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem menuItem) {
        int itemId = menuItem.getItemId();
        if (itemId == R.id.menu_item_view_style) {
            setViewStyle(viewStyle == ViewStyle.GRID ? ViewStyle.LIST : ViewStyle.GRID);
            preferences.edit().putString("shortcuts_view_style", viewStyle.name()).apply();
            refreshViewStyleMenuItem(menuItem);
            return true;
        }
        else if (itemId == R.id.menu_item_new_folder) {
            createFolder();
            return true;
        }
        else return super.onOptionsItemSelected(menuItem);
    }

    @Override
    protected String getHomeTitle() {
        return "Библиотека";
    }

    private class ShortcutsAdapter extends RecyclerView.Adapter<ShortcutsAdapter.ViewHolder> {
        private final List<Shortcut> data;

        private class ViewHolder extends RecyclerView.ViewHolder {
            private final ImageView runButton;
            private final ImageView menuButton;
            private final ImageView imageView;
            private final TextView title;
            private final TextView subtitle;

            private ViewHolder(View view) {
                super(view);
                this.imageView = view.findViewById(R.id.ImageView);
                this.title = view.findViewById(R.id.TVTitle);
                this.subtitle = view.findViewById(R.id.TVSubtitle);
                this.runButton = view.findViewById(R.id.BTRun);
                this.menuButton = view.findViewById(R.id.BTMenu);
            }
        }

        public ShortcutsAdapter(List<Shortcut> data) {
            this.data = data;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            int resource = viewStyle == ViewStyle.LIST ? R.layout.file_list_item : R.layout.file_grid_item;
            return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(resource, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            final Shortcut item = data.get(position);

            if (item.icon == null) {
                int iconResId = item.file.isDirectory() ? R.drawable.container_folder : R.drawable.container_file_link;
                holder.imageView.setImageResource(iconResId);
            }
            else holder.imageView.setImageBitmap(item.icon);

            holder.title.setText(item.name);
            holder.subtitle.setText(item.container.getName());

            if (item.file.isDirectory()) {
                holder.runButton.setImageResource(R.drawable.icon_open);
            }
            else holder.runButton.setImageResource(R.drawable.icon_run);

            holder.imageView.setOnClickListener((v) -> runFromShortcut(item));
            holder.runButton.setOnClickListener((v) -> runFromShortcut(item));
            holder.menuButton.setOnClickListener((v) -> showListItemMenu(v, item));
        }

        @Override
        public final int getItemCount() {
            return data.size();
        }

        private void showListItemMenu(View anchorView, final Shortcut shortcut) {
            final Context context = getContext();
            PopupMenu listItemMenu = new PopupMenu(context, anchorView);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) listItemMenu.setForceShowIcon(true);

            listItemMenu.inflate(R.menu.file_manager_popup_menu);

            Menu menu = listItemMenu.getMenu();
            menu.findItem(R.id.menu_item_rename).setVisible(false);
            menu.findItem(R.id.menu_item_add_favorite).setVisible(false);
            menu.findItem(R.id.menu_item_info).setVisible(false);

            listItemMenu.setOnMenuItemClickListener((menuItem) -> {
                int itemId = menuItem.getItemId();
                switch (itemId) {
                    case R.id.menu_item_settings:
                        clearClipboard();
                        (new ShortcutSettingsDialog(ShortcutsFragment.this, shortcut)).show();
                        break;
                    case R.id.menu_item_copy:
                    case R.id.menu_item_cut:
                        instantiateClipboard(shortcut, itemId == R.id.menu_item_cut);
                        break;
                    case R.id.menu_item_remove:
                        clearClipboard();
                        ContentDialog.confirm(context, R.string.do_you_want_to_remove_this_file, () -> {
                            shortcut.remove();
                            refreshContent();
                        });
                        break;
                }
                return true;
            });
            listItemMenu.show();
        }

        private void runFromShortcut(Shortcut shortcut) {
            AppCompatActivity activity = (AppCompatActivity)getActivity();

            if (shortcut.file.isDirectory()) {
                folderStack.push(shortcut);
                refreshContent();

                ActionBar actionBar = activity.getSupportActionBar();
                actionBar.setHomeAsUpIndicator(R.drawable.icon_action_bar_back);
                actionBar.setTitle(shortcut.name);
            }
            else {
                java.util.concurrent.Executors.newSingleThreadExecutor().execute(() -> {
                    String unixPath = shortcut.getExtra("execUnixPath", "");
                    if ((unixPath.isEmpty() || !new File(unixPath).exists()) && shortcut.path != null && !shortcut.path.isEmpty()) {
                        unixPath = com.winlator.core.WineUtils.dosToUnixPath(shortcut.path, shortcut.container);
                    }
                    if ((unixPath == null || unixPath.isEmpty() || !new File(unixPath).exists()) && shortcut.wmClass != null && !shortcut.wmClass.isEmpty()) {
                        File found = com.winlator.core.FileUtils.findExistingFileOnStorage(shortcut.wmClass, -1);
                        if (found != null) unixPath = found.getAbsolutePath();
                    }

                    File exeFile = (unixPath != null && !unixPath.isEmpty()) ? new File(unixPath) : null;
                    if (exeFile != null && exeFile.exists()) {
                        exeFile = WinlatorProSuite.resolveFullFolderExeIfOrphan(exeFile);
                        WinlatorProSuite.prepareGameFolderAndDependencies(activity, shortcut.container, exeFile);
                        WinlatorProSuite.pinExecutableToDesktopWithIcon(activity, shortcut.container, exeFile);
                    } else {
                        WinlatorProSuite.prepareGameFolderAndDependencies(activity, shortcut.container, null);
                    }

                    final String finalExecPath = (exeFile != null && exeFile.exists()) ? exeFile.getAbsolutePath() : null;
                    activity.runOnUiThread(() -> {
                        Intent intent = new Intent(activity, XServerDisplayActivity.class);
                        intent.putExtra("container_id", shortcut.container.id);
                        intent.putExtra("shortcut_path", shortcut.file.getPath());
                        intent.putExtra("always_show_desktop", true);
                        if (finalExecPath != null) {
                            intent.putExtra("exec_path", finalExecPath);
                        }
                        activity.startActivity(intent);
                    });
                });
            }
        }
    }
}
