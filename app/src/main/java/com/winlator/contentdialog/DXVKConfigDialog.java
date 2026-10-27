package com.winlator.contentdialog;

import android.content.Context;
import android.view.View;
import android.widget.Spinner;

import com.winlator.R;
import com.winlator.container.DXWrappers;
import com.winlator.core.AppUtils;
import com.winlator.core.DefaultVersion;
import com.winlator.core.EnvVars;
import com.winlator.core.FileUtils;
import com.winlator.core.GeneralComponents;
import com.winlator.core.KeyValueSet;
import com.winlator.core.StringUtils;
import com.winlator.widget.GPUCardAdapter;
import com.winlator.xenvironment.RootFS;

import java.io.File;

public class DXVKConfigDialog extends ContentDialog {
    public DXVKConfigDialog(String graphicsDriver, final View anchor) {
        super(anchor.getContext(), R.layout.dxvk_config_dialog);
        setIcon(R.drawable.icon_display_settings);
        Context context = anchor.getContext();
        setTitle("DXVK "+context.getString(R.string.configuration));

        final Spinner sVersion = findViewById(R.id.SVersion);
        final Spinner sFramerate = findViewById(R.id.SFramerate);
        final Spinner sMaxDeviceMemory = findViewById(R.id.SMaxDeviceMemory);
        final Spinner sCustomDevice = findViewById(R.id.SCustomDevice);
        final Spinner sDDrawWrapper = findViewById(R.id.SDDrawWrapper);

        KeyValueSet config = new KeyValueSet(anchor.getTag());
        AppUtils.setSpinnerSelectionFromIdentifier(sFramerate, config.get("framerate", "0"));
        AppUtils.setSpinnerSelectionFromMemorySize(sMaxDeviceMemory, config.get("maxDeviceMemory", "0"));
        AppUtils.setSpinnerSelectionFromIdentifier(sDDrawWrapper, config.get("ddrawWrapper", DXWrappers.WINED3D));

        String version = config.get("version");
        String defaultVersion = DefaultVersion.DXVK(graphicsDriver);
        GeneralComponents.initViews(GeneralComponents.Type.DXVK, findViewById(R.id.DXVKToolbox), sVersion, version, defaultVersion);

        GPUCardAdapter adapter = new GPUCardAdapter(context, android.R.layout.simple_spinner_dropdown_item, R.string.none);
        sCustomDevice.setAdapter(adapter);

        String customDevice = config.get("customDevice");
        if (customDevice.contains(":")) {
            try {
                int deviceId = Integer.parseInt(customDevice.split(":")[0], 16);
                sCustomDevice.setSelection(adapter.getPositionByDeviceId(deviceId));
            }
            catch (NumberFormatException e) {}
        }

        setOnConfirmCallback(() -> {
            KeyValueSet newConfig = new KeyValueSet();
            newConfig.put("version", sVersion.getSelectedItem().toString());
            newConfig.put("framerate", StringUtils.parseNumber(sFramerate.getSelectedItem()));
            newConfig.put("maxDeviceMemory", StringUtils.parseMemorySize(sMaxDeviceMemory.getSelectedItem()));

            String ddrawWrapper = StringUtils.parseIdentifier(sDDrawWrapper.getSelectedItem());
            if (!ddrawWrapper.equals(DXWrappers.WINED3D)) newConfig.put("ddrawWrapper", ddrawWrapper);

            GPUCardAdapter.GPUCard gpuCard = (GPUCardAdapter.GPUCard)sCustomDevice.getSelectedItem();
            if (gpuCard.deviceId > 0) {
                newConfig.put("customDevice", String.format("%04x", gpuCard.deviceId)+":"+String.format("%04x", gpuCard.vendorId)+":"+gpuCard.name);
            }

            anchor.setTag(newConfig.toString());
        });
    }

    public static void setEnvVars(Context context, KeyValueSet config, EnvVars envVars) {
        String ver = config.get("version", DefaultVersion.HYBRID_DXVK);
        if (ver.contains("async") || ver.contains("hybrid") || ver.contains("1.7.2")) {
            envVars.put("DXVK_ASYNC", "1");
            envVars.put("DXVK_GPLASYNCCACHE", "1");
        }
        envVars.put("DXVK_STATE_CACHE_PATH", RootFS.getDosUserCachePath());
        envVars.put("DXVK_LOG_LEVEL", "none");

        File rootDir = RootFS.find(context).getRootDir();
        File dxvkConfigFile = new File(rootDir, RootFS.USER_CONFIG_PATH+"/dxvk.conf");

        FileUtils.delete(dxvkConfigFile);
        String content = getDXVKConfigContent(config);
        if (FileUtils.writeString(dxvkConfigFile, content)) {
            envVars.put("DXVK_CONFIG_FILE", RootFS.getDosUserConfigPath()+"\\dxvk.conf");
        }
    }

    private static String getDXVKConfigContent(KeyValueSet config) {
        String content = "";

        String maxDeviceMemory = config.get("maxDeviceMemory");
        if (!maxDeviceMemory.isEmpty() && !maxDeviceMemory.equals("0")) {
            content += "dxgi.maxDeviceMemory = "+maxDeviceMemory+"\n";
            content += "dxgi.maxSharedMemory = "+maxDeviceMemory+"\n";
        } else {
            content += "dxgi.maxDeviceMemory = 4096\n";
            content += "dxgi.maxSharedMemory = 4096\n";
        }

        String framerate = config.get("framerate");
        if (!framerate.isEmpty() && !framerate.equals("0")) {
            content += "dxgi.maxFrameRate = "+framerate+"\n";
            content += "d3d9.maxFrameRate = "+framerate+"\n";
        }

        String customDevice = config.get("customDevice");
        if (customDevice.contains(":")) {
            String[] parts = customDevice.split(":");
            content += "dxgi.customDeviceId = "+parts[0]+"\n";
            content += "dxgi.customVendorId = "+parts[1]+"\n";

            content += "d3d9.customDeviceId = "+parts[0]+"\n";
            content += "d3d9.customVendorId = "+parts[1]+"\n";

            content += "dxgi.customDeviceDesc = \""+parts[2]+"\"\n";
            content += "d3d9.customDeviceDesc = \""+parts[2]+"\"\n";
        }

        content += "d3d11.maxFeatureLevel = 11_1\n";
        content += "d3d11.relaxedBarriers = True\n";
        content += "d3d11.ignoreGraphicsBarriers = True\n";
        content += "dxvk.enableAsync = True\n";
        content += "dxvk.gplAsyncCache = True\n";
        content += "d3d9.deferSurfaceCreation = True\n";
        content += "d3d11.dcSingleUseMode = False\n";
        content += "d3d11.constantBufferRangeCheck = \"True\"\n\n";

        content += "[GTA5.exe]\n";
        content += "dxgi.maxDeviceMemory = 512\n";
        content += "dxgi.maxSharedMemory = 512\n";
        // v12.7: limit shader compilation to 2–4 workers so the game retains CPU headroom.
        int cores = Runtime.getRuntime().availableProcessors();
        int compilerThreads = Math.max(2, Math.min(4, cores / 2));
        content += "dxvk.numCompilerThreads = " + compilerThreads + "\n";
        content += "dxvk.numAsyncThreads = " + Math.max(1, compilerThreads - 1) + "\n";
        return content;
    }
}
