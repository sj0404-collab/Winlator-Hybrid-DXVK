package com.winlator.xenvironment.components;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;

import com.winlator.core.FileUtils;
import com.winlator.core.NetworkHelper;
import com.winlator.xenvironment.EnvironmentComponent;

import java.io.File;
import java.util.List;

public class NetworkInfoUpdateComponent extends EnvironmentComponent {
    private BroadcastReceiver broadcastReceiver;

    @Override
    public void start() {
        Context context = environment.getContext();
        final NetworkHelper networkHelper = new NetworkHelper(context);
        updateIFAddrsFile(networkHelper.getIFAddresses());
        updateEtcHostsFile(networkHelper.getIPv4Address());
        updateResolvConf(context);

        broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                updateIFAddrsFile(networkHelper.getIFAddresses());
                updateEtcHostsFile(networkHelper.getIPv4Address());
                updateResolvConf(context);
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        context.registerReceiver(broadcastReceiver, filter);
    }

    @Override
    public void stop() {
        if (broadcastReceiver != null) {
            environment.getContext().unregisterReceiver(broadcastReceiver);
            broadcastReceiver = null;
        }
    }

    private void updateIFAddrsFile(List<NetworkHelper.IFAddress> ifAddresses) {
        File file = new File(environment.getRootFS().getTmpDir(), "ifaddrs");

        String content = "";
        if (!ifAddresses.isEmpty()) {
            for (NetworkHelper.IFAddress ifAddress : ifAddresses) {
                content += (!content.isEmpty() ? "\n" : "")+ifAddress.toString();
            }
        }
        else content = (new NetworkHelper.IFAddress()).toString();

        FileUtils.writeString(file, content);
    }

    /** v12.7: DNS текущей сети телефона (Wi-Fi/мобильная/VPN) для Wine вместо жёстких 8.8.8.8. */
    private void updateResolvConf(Context context) {
        try {
            java.util.List<String> v4 = new java.util.ArrayList<>();
            java.util.List<String> v6 = new java.util.ArrayList<>();
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            android.net.Network net = cm != null ? cm.getActiveNetwork() : null;
            android.net.LinkProperties lp = net != null ? cm.getLinkProperties(net) : null;
            if (lp != null) {
                for (java.net.InetAddress a : lp.getDnsServers()) {
                    String h = a.getHostAddress();
                    if (h == null || h.isEmpty()) continue;
                    if (a instanceof java.net.Inet4Address) {
                        if (!v4.contains(h)) v4.add(h);
                    }
                    else if (!a.isLinkLocalAddress()) {
                        int pct = h.indexOf('%');
                        if (pct > 0) h = h.substring(0, pct);
                        if (!v6.contains(h)) v6.add(h);
                    }
                }
            }
            java.util.List<String> all = new java.util.ArrayList<>(v4);
            all.addAll(v6);
            for (String fb : new String[]{"1.1.1.1", "8.8.8.8"}) if (all.size() < 3 && !all.contains(fb)) all.add(fb);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < all.size() && i < 3; i++) sb.append("nameserver ").append(all.get(i)).append('\n');
            sb.append("options timeout:2 attempts:2\n");
            FileUtils.writeString(new File(environment.getRootFS().getRootDir(), "etc/resolv.conf"), sb.toString());
        } catch (Throwable ignored) {}
    }

    /**
     * v12.7: корневые сертификаты Android одним файлом для Wine (HTTPS в играх и программах).
     * Android 14+ держит их в /apex/com.android.conscrypt/cacerts, который Wine сам не находит.
     */
    public static File ensureCaBundle(File rootDir) {
        File out = new File(rootDir, "etc/ssl/certs/ca-certificates.crt");
        try {
            if (out.isFile() && out.length() > 20000 && System.currentTimeMillis() - out.lastModified() < 7L * 24 * 3600 * 1000) return out;
            java.util.LinkedHashSet<String> pems = new java.util.LinkedHashSet<>();
            for (String dir : new String[]{"/apex/com.android.conscrypt/cacerts", "/system/etc/security/cacerts"}) {
                File[] files = new File(dir).listFiles();
                if (files == null) continue;
                java.util.Arrays.sort(files);
                for (File f : files) {
                    String s = FileUtils.readString(f);
                    if (s == null) continue;
                    int b = s.indexOf("-----BEGIN CERTIFICATE-----");
                    int e = b >= 0 ? s.indexOf("-----END CERTIFICATE-----", b) : -1;
                    if (b >= 0 && e > b) pems.add(s.substring(b, e + 25).trim());
                }
            }
            if (pems.isEmpty()) return out.isFile() ? out : null;
            StringBuilder sb = new StringBuilder();
            for (String p : pems) sb.append(p).append('\n');
            File parent = out.getParentFile();
            if (parent != null) parent.mkdirs();
            FileUtils.writeString(out, sb.toString());
            FileUtils.writeString(new File(rootDir, "etc/ssl/cert.pem"), sb.toString());
        } catch (Throwable ignored) {}
        return out.isFile() ? out : null;
    }

    private void updateEtcHostsFile(String ipAddress) {
        String ip = ipAddress != null ? ipAddress : "127.0.0.1";
        File file = new File(environment.getRootFS().getRootDir(), "etc/hosts");
        FileUtils.writeString(file, ip+"\tlocalhost\n" + (ip.equals("127.0.0.1") ? "" : "127.0.0.1\tlocalhost\n"));
    }
}
