package com.winlator.core;

import com.winlator.container.GraphicsDrivers;

import java.util.Locale;

public abstract class DefaultVersion {
    public static final String BOX64 = "0.4.4";
    public static final String TURNIP = "26.2.0";
    public static final String VORTEK = "2.1";
    public static final String ZINK = "22.2.5";
    public static final String VIRGL = "23.1.9";
    public static final String GLADIO = "1.1";
    public static final String D7VK = "1.11";
    public static final String D8VK = "1.0";
    public static final String VKD3D = "2.14.1";
    public static final String WINED3D = WineInfo.MAIN_WINE_VERSION;
    public static final String CNC_DDRAW = "6.6";
    public static final String SOUNDFONT = "SONiVOX-EAS-GM-Wavetable";
    public static final String HYBRID_DXVK = "1.7.2-2.4.1-hybrid";
    /** v12.6.4: настоящий асинхронный DXVK (GPL async), шейдеры компилируются в фоне. */
    public static final String ASYNC_DXVK = "2.4.1-gplasync";
    /** v12.7.1: асинхронный DXVK 1.10.3 — совместим с Vortek/Mali (DXVK 2.x там не создаёт устройство D3D11). */
    public static final String ASYNC_DXVK_COMPAT = "1.10.3-async";
    /** v12.7: PanVK-kbase (Mesa) — Vulkan прямо к ядру Mali, без моста Vortek. */
    public static final String PANVK = "0.1.0-rc3lm";
    public static final String FAST_DXVK = "1.7.2";
    public static final String MINOR_DXVK = "1.10.3";
    public static final String MAJOR_DXVK = "2.4.1";

    public static String DXVK() {
        return DXVK(null);
    }

    public static String DXVK(String vulkanDriver) {
        return HYBRID_DXVK;
    }

    public static String valueOf(String name) {
        switch (name.toUpperCase(Locale.ENGLISH)) {
            case "BOX64": return BOX64;
            case "TURNIP": return TURNIP;
            case "VORTEK": return VORTEK;
            case "ZINK": return ZINK;
            case "VIRGL": return VIRGL;
            case "GLADIO": return GLADIO;
            case "D7VK": return D7VK;
            case "D8VK": return D8VK;
            case "VKD3D": return VKD3D;
            case "WINED3D": return WINED3D;
            case "CNC_DDRAW": return CNC_DDRAW;
            case "SOUNDFONT": return SOUNDFONT;
            default: return "0.0";
        }
    }
}