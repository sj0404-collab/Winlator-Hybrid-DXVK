package com.winlator.devtools;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.LinearLayout;

import com.winlator.R;
import com.winlator.XServerDisplayActivity;
import com.winlator.container.Container;
import com.winlator.core.WineUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Creates per-session cmd scripts in the selected container; each session remains alive in its own Winlator window. */
public final class DeveloperToolsLauncher {
    private DeveloperToolsLauncher() {}

    public static void openTerminal(Activity activity, Container container, File folder) {
        launch(activity, container, folder, false);
    }

    public static void openInOpenCode(Activity activity, Container container, File folder) {
        if (DeveloperToolsInstaller.isInstalled(container, DeveloperToolsInstaller.Tool.OPENCODE)) {
            launch(activity, container, folder, true);
            return;
        }
        new AlertDialog.Builder(activity)
            .setTitle(R.string.devtools_opencode)
            .setMessage(R.string.devtools_opencode_install_prompt)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.devtools_install, (dialog, which) -> installOpenCodeAndLaunch(activity, container, folder))
            .show();
    }

    public static void showCatalog(Activity activity, Container container, File folder) {
        java.util.ArrayList<String> labels = new java.util.ArrayList<>();
        java.util.ArrayList<Integer> actions = new java.util.ArrayList<>();
        if (folder != null) {
            labels.add(activity.getString(R.string.devtools_open_terminal)); actions.add(0);
            labels.add(activity.getString(R.string.devtools_open_opencode)); actions.add(1);
        }
        labels.add(activity.getString(R.string.devtools_install_opencode)); actions.add(2);
        labels.add(activity.getString(R.string.devtools_install_node)); actions.add(3);
        if (DeveloperToolsInstaller.isInstalled(container, DeveloperToolsInstaller.Tool.OPENCODE)) {
            labels.add(activity.getString(R.string.devtools_remove_opencode)); actions.add(4);
        }
        if (DeveloperToolsInstaller.isInstalled(container, DeveloperToolsInstaller.Tool.NODE)) {
            labels.add(activity.getString(R.string.devtools_remove_node)); actions.add(5);
        }
        new AlertDialog.Builder(activity)
            .setTitle(R.string.devtools_catalog)
            .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                int action = actions.get(which);
                if (action == 0) openTerminal(activity, container, folder);
                else if (action == 1) openInOpenCode(activity, container, folder);
                else if (action == 2) install(activity, container, DeveloperToolsInstaller.Tool.OPENCODE, null);
                else if (action == 3) install(activity, container, DeveloperToolsInstaller.Tool.NODE, null);
                else if (action == 4) confirmRemove(activity, container, DeveloperToolsInstaller.Tool.OPENCODE);
                else if (action == 5) confirmRemove(activity, container, DeveloperToolsInstaller.Tool.NODE);
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private static void confirmRemove(Activity activity, Container container, DeveloperToolsInstaller.Tool tool) {
        String label = tool == DeveloperToolsInstaller.Tool.NODE ? activity.getString(R.string.devtools_node) : activity.getString(R.string.devtools_opencode);
        new AlertDialog.Builder(activity)
            .setTitle(R.string.devtools_remove_title)
            .setMessage(activity.getString(R.string.devtools_remove_prompt, label))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.devtools_remove, (d, w) -> {
                DeveloperToolsInstaller.remove(container, tool);
                android.widget.Toast.makeText(activity, R.string.devtools_removed, android.widget.Toast.LENGTH_SHORT).show();
            }).show();
    }

    private static void installOpenCodeAndLaunch(Activity activity, Container container, File folder) {
        install(activity, container, DeveloperToolsInstaller.Tool.OPENCODE, folder);
    }

    private static void install(Activity activity, Container container, DeveloperToolsInstaller.Tool tool, File folderToOpen) {
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * activity.getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);
        TextView status = new TextView(activity);
        status.setText(R.string.devtools_starting_download);
        layout.addView(status);
        ProgressBar progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        layout.addView(progress);
        AlertDialog dialog = new AlertDialog.Builder(activity)
            .setTitle(tool == DeveloperToolsInstaller.Tool.NODE ? R.string.devtools_node : R.string.devtools_opencode)
            .setView(layout)
            .setCancelable(false)
            .create();
        dialog.show();
        DeveloperToolsInstaller.install(activity, container, tool, new DeveloperToolsInstaller.Listener() {
            @Override public void onProgress(String message, int percent) {
                if (!activity.isFinishing() && dialog.isShowing()) {
                    status.setText(message);
                    if (percent > 0) { progress.setIndeterminate(false); progress.setProgress(percent); }
                }
            }
            @Override public void onComplete(File installDir) {
                if (dialog.isShowing()) dialog.dismiss();
                android.widget.Toast.makeText(activity,
                    activity.getString(R.string.devtools_installed, tool == DeveloperToolsInstaller.Tool.NODE ? "Node.js" : "OpenCode"),
                    android.widget.Toast.LENGTH_LONG).show();
                if (folderToOpen != null && tool == DeveloperToolsInstaller.Tool.OPENCODE && !activity.isFinishing()) {
                    launch(activity, container, folderToOpen, true);
                }
            }
            @Override public void onError(String message) {
                if (dialog.isShowing()) dialog.dismiss();
                if (!activity.isFinishing()) new AlertDialog.Builder(activity)
                    .setTitle(R.string.devtools_install_failed)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            }
        });
    }

    private static void launch(Activity activity, Container container, File folder, boolean openCode) {
        if (activity == null || container == null || folder == null || !folder.isDirectory()) return;
        try {
            File toolsDir = new File(container.getRootDir(), ".wine/drive_c/WinlatorTools/Sessions");
            if (!toolsDir.isDirectory() && !toolsDir.mkdirs()) throw new Exception("Cannot create the terminal session folder");
            File script = File.createTempFile("dev-session-", ".cmd", toolsDir);
            String dosFolder = WineUtils.unixToDOSPath(folder.getCanonicalPath(), container);
            if (dosFolder == null || dosFolder.isEmpty()) throw new Exception("The selected folder is not mapped to a Windows drive");
            String title = "Winlator Dev - " + (openCode ? "OpenCode: " : "Terminal: ") + safeTitle(folder.getName());
            StringBuilder content = new StringBuilder();
            content.append("@echo off\r\n");
            content.append("chcp 65001 >nul\r\n");
            content.append("title ").append(title).append("\r\n");
            content.append("set \"PATH=C:\\WinlatorTools\\Node;%PATH%\"\r\n");
            content.append("cd /d \"").append(escapeBatchPath(dosFolder)).append("\"\r\n");
            if (openCode) {
                File opencodeFile = DeveloperToolsInstaller.getOpenCodeExecutable(container);
                if (opencodeFile == null) throw new Exception("OpenCode is not installed in this container");
                String dosExe = WineUtils.unixToDOSPath(opencodeFile.getAbsolutePath(), container);
                if (dosExe == null || dosExe.isEmpty()) throw new Exception("Could not map the OpenCode executable into the container");
                content.append("\"").append(escapeBatchPath(dosExe)).append("\" .\r\n");
                content.append("echo.\r\n");
                content.append("echo OpenCode has exited. This terminal session remains open.\r\n");
            }
            content.append("cmd /k\r\n");
            try (FileOutputStream output = new FileOutputStream(script)) {
                output.write(content.toString().getBytes(StandardCharsets.UTF_8));
            }
            XServerDisplayActivity running = XServerDisplayActivity.getCurrentDisplayActivity();
            if (running != null && !running.isFinishing() && running.getContainer() != null) {
                if (running.getContainer().id != container.id) {
                    throw new Exception("Close the other container session before opening a developer window for this container.");
                }
                running.runDeveloperScript(script);
                activity.startActivity(new Intent(activity, XServerDisplayActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
                return;
            }
            Intent intent = new Intent(activity, XServerDisplayActivity.class);
            intent.putExtra("container_id", container.id);
            intent.putExtra("exec_path", script.getAbsolutePath());
            intent.putExtra("developer_tool_session", true);
            intent.putExtra("developer_working_directory", folder.getAbsolutePath());
            activity.startActivity(intent);
        } catch (Exception e) {
            new AlertDialog.Builder(activity)
                .setTitle(R.string.devtools_launch_failed)
                .setMessage(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())
                .setPositiveButton(android.R.string.ok, null)
                .show();
        }
    }

    private static String escapeBatchPath(String value) {
        return value.replace("%", "%%");
    }

    private static String safeTitle(String value) {
        if (value == null || value.isEmpty()) return "Workspace";
        return value.replace('\r', ' ').replace('\n', ' ').replace('"', '\'').replace('%', '_');
    }
}
