package com.winlator.renderer.effects;

import com.winlator.renderer.material.ScreenMaterial;
import com.winlator.renderer.material.ShaderMaterial;

public class HDRBloomEffect extends Effect {
    private float intensity = 0.5f;

    public float getIntensity() {
        return intensity;
    }

    public void setIntensity(float intensity) {
        this.intensity = Math.max(0.0f, Math.min(1.5f, intensity));
    }

    @Override
    protected ScreenMaterial createMaterial() {
        final ShaderMaterial.Uniform intensityUniform = new ShaderMaterial.Uniform("intensity");
        return new ScreenMaterial() {
            @Override
            protected String getFragmentShader() {
                return String.join("\n",
                    "precision highp float;",
                    "uniform sampler2D screenTexture;",
                    "uniform vec2 resolution;",
                    "uniform float intensity;",
                    "in vec2 vUV;",
                    "layout(location = 0) out vec4 outFragColor;",
                    "void main() {",
                        "vec2 stepUV = 2.0 / max(resolution, vec2(1.0, 1.0));",
                        "vec4 base = texture(screenTexture, vUV);",
                        "vec3 b1 = texture(screenTexture, vUV + vec2(stepUV.x, stepUV.y)).rgb;",
                        "vec3 b2 = texture(screenTexture, vUV + vec2(-stepUV.x, stepUV.y)).rgb;",
                        "vec3 b3 = texture(screenTexture, vUV + vec2(stepUV.x, -stepUV.y)).rgb;",
                        "vec3 b4 = texture(screenTexture, vUV + vec2(-stepUV.x, -stepUV.y)).rgb;",
                        "vec3 avg = (b1 + b2 + b3 + b4) * 0.25;",
                        "float luma = dot(avg, vec3(0.299, 0.587, 0.114));",
                        "vec3 bloom = max(avg - vec3(0.55), vec3(0.0)) * (intensity * 0.9);",
                        "float gray = dot(base.rgb, vec3(0.299, 0.587, 0.114));",
                        "vec3 vibrance = mix(vec3(gray), base.rgb, 1.0 + intensity * 0.45);",
                        "vec3 hdr = clamp(vibrance + bloom + (luma * 0.04 * intensity), 0.0, 1.0);",
                        "outFragColor = vec4(hdr, base.a);",
                    "}"
                );
            }

            @Override
            public void use() {
                super.use();
                setUniformFloat(intensityUniform, intensity);
            }
        };
    }
}
