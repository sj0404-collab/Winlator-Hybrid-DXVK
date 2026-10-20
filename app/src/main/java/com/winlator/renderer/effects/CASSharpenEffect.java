package com.winlator.renderer.effects;

import com.winlator.renderer.material.ScreenMaterial;
import com.winlator.renderer.material.ShaderMaterial;

public class CASSharpenEffect extends Effect {
    private float sharpness = 0.65f;

    public float getSharpness() {
        return sharpness;
    }

    public void setSharpness(float sharpness) {
        this.sharpness = Math.max(0.0f, Math.min(1.5f, sharpness));
    }

    @Override
    protected ScreenMaterial createMaterial() {
        final ShaderMaterial.Uniform sharpnessUniform = new ShaderMaterial.Uniform("sharpness");
        return new ScreenMaterial() {
            @Override
            protected String getFragmentShader() {
                return String.join("\n",
                    "precision highp float;",
                    "uniform sampler2D screenTexture;",
                    "uniform vec2 resolution;",
                    "uniform float sharpness;",
                    "in vec2 vUV;",
                    "layout(location = 0) out vec4 outFragColor;",
                    "void main() {",
                        "vec2 texel = 1.0 / max(resolution, vec2(1.0, 1.0));",
                        "vec3 c = texture(screenTexture, vUV).rgb;",
                        "vec3 n = texture(screenTexture, vUV + vec2(0.0, -texel.y)).rgb;",
                        "vec3 s = texture(screenTexture, vUV + vec2(0.0, texel.y)).rgb;",
                        "vec3 e = texture(screenTexture, vUV + vec2(texel.x, 0.0)).rgb;",
                        "vec3 w = texture(screenTexture, vUV + vec2(-texel.x, 0.0)).rgb;",
                        "vec3 blur = (n + s + e + w) * 0.25;",
                        "vec3 sharpened = clamp(c + (c - blur) * (sharpness * 1.6), 0.0, 1.0);",
                        "outFragColor = vec4(sharpened, 1.0);",
                    "}"
                );
            }

            @Override
            public void use() {
                super.use();
                setUniformFloat(sharpnessUniform, sharpness);
            }
        };
    }
}
