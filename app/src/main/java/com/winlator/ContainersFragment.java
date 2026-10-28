package com.winlator;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.contentdialog.ContentDialog;
import com.winlator.contentdialog.StorageInfoDialog;
import com.winlator.core.PreloaderDialog;
import com.winlator.pro.WinlatorProSuite;
import com.winlator.xenvironment.RootFS;

import java.util.ArrayList;
import java.util.List;

public class ContainersFragment extends Fragment {
    private static final int REQUEST_IMPORT_CONTAINER_BACKUP = 41;
    private static final int REQUEST_EXPORT_CONTAINER_BACKUP = 42;
    private static final int REQUEST_EXPORT_SAVE_DATA = 43;
    private static final int REQUEST_IMPORT_SAVE_DATA = 44;
    private RecyclerView recyclerView;
    private TextView emptyTextView;
    private ContainerManager manager;
    private PreloaderDialog preloaderDialog;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        preloaderDialog = new PreloaderDialog(getActivity());
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        manager = new ContainerManager(getContext());
        ViewGroup proHeader = view.findViewById(R.id.FLProTopHeader);
        if (proHeader != null && getActivity() != null) {
            Container target = manager.getDefaultOrFirstContainer();
            proHeader.removeAllViews();
            proHeader.addView(WinlatorProSuite.buildTopProDashboardBar(getActivity(), target, this::loadContainersList));
        }
        loadContainersList();
        ((AppCompatActivity)getActivity()).getSupportActionBar().setTitle(R.string.containers);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            if (requestCode == REQUEST_EXPORT_CONTAINER_BACKUP) pendingExportContainer = null;
            if (requestCode == REQUEST_EXPORT_SAVE_DATA) pendingSaveExportContainer = null;
            if (requestCode == REQUEST_IMPORT_SAVE_DATA) pendingSaveRestoreContainer = null;
            return;
        }
        final MainActivity activity = (MainActivity)getActivity();
        if (activity == null) return;
        final android.net.Uri uri = data.getData();

        if (requestCode == REQUEST_EXPORT_CONTAINER_BACKUP) {
            final Container selected = pendingExportContainer;
            pendingExportContainer = null;
            if (selected == null) return;
            preloaderDialog.show(R.string.exporting_container_backup);
            java.util.concurrent.Executors.newSingleThreadExecutor().execute(() -> {
                boolean success = com.winlator.core.ContainerBackupArchive.exportTo(activity, selected, uri);
                if (!success) activity.getContentResolver().delete(uri, null, null);
                activity.runOnUiThread(() -> {
                    preloaderDialog.close();
                    com.winlator.core.AppUtils.showToast(activity, success ? R.string.container_backup_exported : R.string.container_backup_failed);
                });
            });
        }
        else if (requestCode == REQUEST_EXPORT_SAVE_DATA) {
            final Container selected = pendingSaveExportContainer;
            pendingSaveExportContainer = null;
            if (selected == null) return;
            preloaderDialog.show(R.string.exporting_save_data);
            java.util.concurrent.Executors.newSingleThreadExecutor().execute(() -> {
                boolean success = com.winlator.core.SaveDataArchive.exportTo(activity, selected, uri);
                if (!success) activity.getContentResolver().delete(uri, null, null);
                activity.runOnUiThread(() -> {
                    preloaderDialog.close();
                    com.winlator.core.AppUtils.showToast(activity, success ? R.string.save_data_exported : R.string.container_backup_failed);
                });
            });
        }
        else if (requestCode == REQUEST_IMPORT_SAVE_DATA) {
            final Container selected = pendingSaveRestoreContainer;
            pendingSaveRestoreContainer = null;
            if (selected == null) return;
            ContentDialog.confirm(activity, R.string.confirm_save_data_restore, () -> {
                preloaderDialog.show(R.string.importing_save_data);
                java.util.concurrent.Executors.newSingleThreadExecutor().execute(() -> {
                    boolean success = com.winlator.core.SaveDataArchive.importTo(activity, uri, selected);
                    activity.runOnUiThread(() -> {
                        preloaderDialog.close();
                        com.winlator.core.AppUtils.showToast(activity, success ? R.string.save_data_imported : R.string.container_backup_failed);
                    });
                });
            });
        }
        else if (requestCode == REQUEST_IMPORT_CONTAINER_BACKUP) {
            preloaderDialog.show(R.string.importing_container_backup);
            java.util.concurrent.Executors.newSingleThreadExecutor().execute(() -> {
                Container restored = manager.importBackup(uri);
                activity.runOnUiThread(() -> {
                    preloaderDialog.close();
                    loadContainersList();
                    com.winlator.core.AppUtils.showToast(activity, restored != null ? R.string.container_backup_imported : R.string.container_backup_failed);
                });
            });
        }
    }

    private Container pendingExportContainer;
    private Container pendingSaveExportContainer;
    private Container pendingSaveRestoreContainer;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        FrameLayout frameLayout = (FrameLayout)inflater.inflate(R.layout.containers_fragment, container, false);
        recyclerView = frameLayout.findViewById(R.id.RecyclerView);
        Context context = recyclerView.getContext();
        emptyTextView = frameLayout.findViewById(R.id.TVEmptyText);
        recyclerView.setLayoutManager(new LinearLayoutManager(context));

        DividerItemDecoration itemDecoration = new DividerItemDecoration(recyclerView.getContext(), DividerItemDecoration.VERTICAL);
        itemDecoration.setDrawable(ContextCompat.getDrawable(context, R.drawable.list_item_divider));
        recyclerView.addItemDecoration(itemDecoration);
        return frameLayout;
    }

    private void loadContainersList() {
        ArrayList<Container> containers = manager.getContainers();
        recyclerView.setAdapter(new ContainersAdapter(containers));
        emptyTextView.setVisibility(containers.isEmpty() ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater menuInflater) {
        menuInflater.inflate(R.menu.containers_menu, menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem menuItem) {
        if (menuItem.getItemId() == R.id.menu_item_add) {
            if (!RootFS.find(getContext()).isValid()) return false;
            FragmentManager fragmentManager = getParentFragmentManager();
            fragmentManager.beginTransaction()
                .addToBackStack(null)
                .replace(R.id.FLFragmentContainer, new ContainerDetailFragment())
                .commit();
            return true;
        }
        else if (menuItem.getItemId() == R.id.menu_item_import_backup) {
            if (!RootFS.find(getContext()).isValid()) return false;
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, REQUEST_IMPORT_CONTAINER_BACKUP);
            return true;
        }
        else if (menuItem.getItemId() == R.id.menu_item_import_exe) {
            if (!RootFS.find(getContext()).isValid()) return false;
            final MainActivity activity = (MainActivity)getActivity();
            if (activity == null) return false;
            activity.setOpenFileCallback((uri) -> {
                preloaderDialog.show(R.string.loading);
                java.util.concurrent.Executors.newSingleThreadExecutor().execute(() -> {
                    Container target = manager.ensureDefaultHybridContainer();
                    final java.io.File importedExe = manager.importExeFromUri(uri, target);
                    activity.runOnUiThread(() -> {
                        preloaderDialog.close();
                        loadContainersList();
                        if (importedExe != null && target != null) {
                            Intent intent = new Intent(activity, XServerDisplayActivity.class);
                            intent.putExtra("container_id", target.id);
                            intent.putExtra("exec_path", importedExe.getAbsolutePath());
                            activity.startActivity(intent);
                        }
                        else {
                            com.winlator.core.AppUtils.showToast(activity, R.string.unable_to_import_profile);
                        }
                    });
                });
            });
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            activity.startActivityForResult(intent, MainActivity.OPEN_FILE_REQUEST_CODE);
            return true;
        }
        else return super.onOptionsItemSelected(menuItem);
    }

    private class ContainersAdapter extends RecyclerView.Adapter<ContainersAdapter.ViewHolder> {
        private final List<Container> data;

        private class ViewHolder extends RecyclerView.ViewHolder {
            private final ImageView runButton;
            private final ImageView menuButton;
            private final ImageView imageView;
            private final TextView title;

            private ViewHolder(View view) {
                super(view);
                this.imageView = view.findViewById(R.id.ImageView);
                this.title = view.findViewById(R.id.TVTitle);
                this.runButton = view.findViewById(R.id.BTRun);
                this.menuButton = view.findViewById(R.id.BTMenu);
            }
        }

        public ContainersAdapter(List<Container> data) {
            this.data = data;
        }

        @Override
        public final ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.container_list_item, parent, false));
        }

        @Override
        public void onBindViewHolder(final ViewHolder holder, int position) {
            final Container item = data.get(position);
            holder.imageView.setImageResource(R.drawable.icon_container);
            holder.title.setText(item.getName());
            holder.runButton.setOnClickListener((view) -> runContainer(item));
            holder.menuButton.setOnClickListener((view) -> showListItemMenu(view, item));
        }

        @Override
        public final int getItemCount() {
            return data.size();
        }

        private void showListItemMenu(View anchorView, Container container) {
            MainActivity activity = (MainActivity)getActivity();
            PopupMenu listItemMenu = new PopupMenu(activity, anchorView);
            listItemMenu.inflate(R.menu.container_popup_menu);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) listItemMenu.setForceShowIcon(true);

            listItemMenu.setOnMenuItemClickListener((menuItem) -> {
                switch (menuItem.getItemId()) {
                    case R.id.menu_item_export_save_data: {
                        ContentDialog.confirm(activity, R.string.confirm_save_data_export, () -> {
                            pendingSaveExportContainer = container;
                            String filename = com.winlator.core.StringUtils.clearReservedChars(container.getName()).trim();
                            if (filename.isEmpty()) filename = "Winlator-Saves";
                            Intent exportIntent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                            exportIntent.addCategory(Intent.CATEGORY_OPENABLE);
                            exportIntent.setType("application/octet-stream");
                            exportIntent.putExtra(Intent.EXTRA_TITLE, filename + ".wsaves.tar.zst");
                            startActivityForResult(exportIntent, REQUEST_EXPORT_SAVE_DATA);
                        });
                        break;
                    }
                    case R.id.menu_item_import_save_data: {
                        pendingSaveRestoreContainer = container;
                        Intent importIntent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        importIntent.addCategory(Intent.CATEGORY_OPENABLE);
                        importIntent.setType("*/*");
                        startActivityForResult(importIntent, REQUEST_IMPORT_SAVE_DATA);
                        break;
                    }
                    case R.id.menu_item_export_backup: {
                        ContentDialog.confirm(activity, R.string.confirm_container_backup_export, () -> {
                            pendingExportContainer = container;
                            String filename = com.winlator.core.StringUtils.clearReservedChars(container.getName()).trim();
                            if (filename.isEmpty()) filename = "Winlator-Container";
                            Intent exportIntent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                            exportIntent.addCategory(Intent.CATEGORY_OPENABLE);
                            exportIntent.setType("application/octet-stream");
                            exportIntent.putExtra(Intent.EXTRA_TITLE, filename + ".wcontainer.tar.zst");
                            startActivityForResult(exportIntent, REQUEST_EXPORT_CONTAINER_BACKUP);
                        });
                        break;
                    }
                    case R.id.menu_item_file_manager:
                        activity.showFragment(new ContainerFileManagerFragment(container.id));
                        break;
                    case R.id.menu_item_edit:
                        activity.showFragment(new ContainerDetailFragment(container.id));
                        break;
                    case R.id.menu_item_duplicate:
                        ContentDialog.confirm(getContext(), R.string.do_you_want_to_duplicate_this_container, () -> {
                            preloaderDialog.show(R.string.duplicating_container);
                            manager.duplicateContainerAsync(container, () -> {
                                preloaderDialog.close();
                                loadContainersList();
                            });
                        });
                        break;
                    case R.id.menu_item_remove:
                        ContentDialog.confirm(getContext(), R.string.do_you_want_to_remove_this_container, () -> {
                            preloaderDialog.show(R.string.removing_container);
                            manager.removeContainerAsync(container, () -> {
                                preloaderDialog.close();
                                loadContainersList();
                            });
                        });
                        break;
                    case R.id.menu_item_info:
                        (new StorageInfoDialog(activity, container)).show();
                        break;
                }
                return true;
            });
            listItemMenu.show();
        }

        private void runContainer(Container container) {
            Activity activity = getActivity();
            WinlatorProSuite.launchWithMemoryCheck(activity, container, null);
        }
    }
}
