package com.winlator.renderer.effects;

import com.winlator.renderer.material.ScreenMaterial;

public class NightVisionEffect extends Effect {
    @Override
    protected ScreenMaterial createMaterial() {
        return new ScreenMaterial() {
            @Override
            protected String getFragmentShader() {
                return String.join("\n",
                    "precision highp float;",
                    "uniform sampler2D screenTexture;",
                    "in vec2 vUV;",
                    "layout(location = 0) out vec4 outFragColor;",
                    "void main() {",
                        "vec4 tex = texture(screenTexture, vUV);",
                        "vec3 lifted = pow(clamp(tex.rgb * 1.35 + vec3(0.04, 0.05, 0.02), 0.0, 1.0), vec3(0.82));",
                        "outFragColor = vec4(lifted, tex.a);",
                    "}"
                );
            }
        };
    }
}
