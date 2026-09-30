package com.github.epsilon.graphics.shaders;

import com.github.epsilon.assets.resources.ResourceLocationUtils;
import com.github.epsilon.graphics.LuminBindGroupLayouts;
import com.github.epsilon.graphics.LuminRenderSystem;
import com.github.epsilon.graphics.immediate.LuminImmediateRenderer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.renderer.DynamicGpuDataStorage;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.nio.ByteBuffer;
import java.util.Optional;

import static com.github.epsilon.Constants.mc;

public class BlurShader {

    public static final BlurShader INSTANCE = new BlurShader();

    private static final int MAX_SEGMENTS = 64;

    /**
     * 液态玻璃材质参数，由 {@link #renderGlass} 一次性交给着色器合成。
     * <p>
     * {@link #PLAIN} 表示“只做背景模糊”：不折射、不调色、不加高光，HUD / 世界侧的模糊继续走这一档，
     * 外观与旧的纯模糊实现一致。
     *
     * @param refraction    边缘折射强度（framebuffer 像素；调用方负责从 GUI 单位换算），越大透镜感越强
     * @param dispersion    边缘色散比例（0~1），0 表示不做三通道分离采样
     * @param saturation    背景饱和度增益（1 = 不变）
     * @param brightness    背景亮度增益（1 = 不变）
     * @param innerShade    背光侧内阴影强度（0~1）
     * @param specular      受光侧高光强度
     * @param specularWidth 高光宽度（framebuffer 像素；调用方负责从 GUI 单位换算）
     */
    public record GlassMaterial(
            float refraction,
            float dispersion,
            float saturation,
            float brightness,
            float innerShade,
            float specular,
            float specularWidth
    ) {
        /** 纯模糊材质：行为与引入液态玻璃材质之前完全一致。 */
        public static final GlassMaterial PLAIN = new GlassMaterial(0.0f, 0.0f, 1.0f, 1.0f, 0.0f, 0.0f, 0.0f);
    }

    private static final Identifier BLUR_PATH = ResourceLocationUtils.getIdentifier("blur");
    private static final Identifier BLUR_3D_BOX_PATH = ResourceLocationUtils.getIdentifier("blur_3d_box");

    private static final int UNIFORMS_SIZE = blurUniformsSize();

    private static final int BOX_UNIFORMS_SIZE = new Std140SizeCalculator().putVec4().get();

    private RenderPipeline pipeline;
    private RenderPipeline boxPipeline;
    private RenderTarget input;

    private void ensureProgram() {
        if (this.pipeline == null) {
            this.pipeline = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
                    .withLocation(ResourceLocationUtils.getIdentifier("pipeline/blur"))
                    .withVertexShader(BLUR_PATH)
                    .withFragmentShader(BLUR_PATH)
                    .withBindGroupLayout(LuminBindGroupLayouts.BLUR)
                    .withBindGroupLayout(LuminBindGroupLayouts.INPUT_SAMPLER)
                    .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                    .withCull(false)
                    .build();
        }
    }

    private void ensureBoxProgram() {
        if (this.boxPipeline == null) {
            this.boxPipeline = RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                    .withLocation(ResourceLocationUtils.getIdentifier("pipeline/blur_3d_box"))
                    .withVertexShader(BLUR_3D_BOX_PATH)
                    .withFragmentShader(BLUR_3D_BOX_PATH)
                    .withBindGroupLayout(LuminBindGroupLayouts.BOX_BLUR)
                    .withBindGroupLayout(LuminBindGroupLayouts.INPUT_SAMPLER)
                    .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                    .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
                    .withCull(false)
                    .build();
        }
    }

    public void render(float x, float y, float width, float height, float rTL, float rTR, float rBR, float rBL, float blurStrength) {
        render(null, x, y, width, height, rTL, rTR, rBR, rBL, blurStrength, 1.0f, null, null, 0, GlassMaterial.PLAIN);
    }

    public void render(float x, float y, float width, float height, float rTL, float rTR, float rBR, float rBL, float blurStrength, float[] segmentRects, float[] segmentRadii, int segmentCount) {
        render(null, x, y, width, height, rTL, rTR, rBR, rBL, blurStrength, 1.0f, segmentRects, segmentRadii, segmentCount, GlassMaterial.PLAIN);
    }

    public void render(LuminRenderSystem.LuminRenderTarget source, float x, float y, float width, float height, float radius, float blurStrength) {
        render(source, x, y, width, height, radius, radius, radius, radius, blurStrength, 1.0f, null, null, 0, GlassMaterial.PLAIN);
    }

    /**
     * 带表面不透明度的圆角模糊。
     * <p>
     * 本方法会往当前 target 写一块 alpha 接近 1 的模糊斑，即模糊层的“实体”就是面板背景本身；
     * GUI 必须通过 {@code opacity} 把它挂到 Background Opacity 上，否则调透明背景后会残留不透明模糊斑。
     * HUD/世界侧的模糊直接沿用其它重载（opacity = 1.0），不受 GUI 设置影响。
     */
    public void render(float x, float y, float width, float height, float radius, float blurStrength, float opacity) {
        render(null, x, y, width, height, radius, radius, radius, radius, blurStrength, opacity, null, null, 0, GlassMaterial.PLAIN);
    }

    /**
     * 用指定的液态玻璃材质渲染一块圆角模糊斑。
     * <p>
     * 与其它重载一样是立即执行的：着色器在同一帧里先把当前取样源模糊、折射、调色并叠加高光，
     * 再把这些结果写回当前 target，因此调用方必须先提交本方法、再记录玻璃表面，否则会把已经画好的 UI 一起糊掉。
     *
     * @param opacity 表面不透明度（0~1）：GUI 传 Background Opacity，HUD / 世界侧传 1.0
     */
    public void renderGlass(float x, float y, float width, float height, float rTL, float rTR, float rBR, float rBL,
                            float blurStrength, float opacity, GlassMaterial material) {
        render(null, x, y, width, height, rTL, rTR, rBR, rBL, blurStrength, opacity, null, null, 0,
                material == null ? GlassMaterial.PLAIN : material);
    }

    public void renderGlass(float x, float y, float width, float height, float radius,
                            float blurStrength, float opacity, GlassMaterial material) {
        renderGlass(x, y, width, height, radius, radius, radius, radius, blurStrength, opacity, material);
    }

    private void render(LuminRenderSystem.LuminRenderTarget source, float x, float y, float width, float height, float rTL, float rTR, float rBR, float rBL, float blurStrength, float opacity, float[] segmentRects, float[] segmentRadii, int segmentCount, GlassMaterial material) {
        this.ensureProgram();

        if (width <= 0.0f || height <= 0.0f) {
            return;
        }

        RenderTarget mainTarget = mc.gameRenderer.mainRenderTarget();
        LuminRenderSystem.LuminRenderTarget activeTarget = LuminRenderSystem.getActiveTarget();
        GpuTexture sourceTexture = source == null ? mainTarget.getColorTexture() : source.colorTexture();
        int sourceWidth = source == null ? mainTarget.width : source.width();
        int sourceHeight = source == null ? mainTarget.height : source.height();
        GpuTexture targetTexture = activeTarget == null ? mainTarget.getColorTexture() : activeTarget.colorTexture();
        GpuTextureView targetView = activeTarget == null ? mainTarget.getColorTextureView() : activeTarget.colorView();
        int targetWidth = activeTarget == null ? mainTarget.width : activeTarget.width();
        int targetHeight = activeTarget == null ? mainTarget.height : activeTarget.height();

        if (sourceWidth <= 0 || sourceHeight <= 0 || targetWidth <= 0 || targetHeight <= 0 || sourceTexture == null || targetTexture == null || targetView == null) {
            return;
        }

        if (input == null) {
            input = new TextureTarget("Lumin Blur Input", sourceWidth, sourceHeight, GpuFormat.RGBA8_UNORM, null);
        }

        if (this.input.width != sourceWidth || this.input.height != sourceHeight) {
            this.input.resize(sourceWidth, sourceHeight);
        }

        if (this.input.getColorTexture() == null || this.input.getColorTextureView() == null) {
            return;
        }

        float scale = (float) LuminRenderSystem.getGuiScale();
        float pxX = x * scale;
        float pxY = targetHeight - (y + height) * scale;
        float pxW = width * scale;
        float pxH = height * scale;

        float rTLPx = Math.max(0.0f, rTL * scale);
        float rTRPx = Math.max(0.0f, rTR * scale);
        float rBRPx = Math.max(0.0f, rBR * scale);
        float rBLPx = Math.max(0.0f, rBL * scale);

        float quality = Math.max(0.0f, blurStrength);
        int count = clampSegmentCount(segmentRects, segmentCount);

        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.copyTextureToTexture(
                sourceTexture,
                input.getColorTexture(),
                0, 0, 0, 0, 0,
                sourceWidth, sourceHeight
        );

        GpuBufferSlice blurUniforms = LuminRenderSystem.writeDynamicUniform(
                "blur_uniforms",
                "Lumin Blur UBO",
                UNIFORMS_SIZE,
                16,
                new BlurUniforms(
                        sourceWidth, sourceHeight, quality,
                        pxW, pxH, pxX, pxY,
                        rTLPx, rTRPx, rBRPx, rBLPx,
                        scale, targetHeight, Mth.clamp(opacity, 0.0f, 1.0f),
                        material.refraction(), material.dispersion(), material.saturation(), material.brightness(),
                        material.innerShade(), material.specular(), material.specularWidth(),
                        segmentRects, segmentRadii, count
                )
        );

        try (RenderPass renderPass = encoder.createRenderPass(
                () -> "Lumin Blur",
                targetView,
                Optional.empty()
        )) {
            renderPass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));
            RenderSystem.bindDefaultUniforms(renderPass);
            renderPass.setUniform("BlurUniforms", blurUniforms);
            renderPass.setUniform("InputSampler", input.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            renderPass.draw(3, 1, 0, 0);
        }
    }

    public void render(float x, float y, float width, float height, float radius, float blurStrength) {
        render(x, y, width, height, radius, radius, radius, radius, blurStrength);
    }

    public void render(float x, float y, float width, float height, float radius, float blurStrength, float[] segmentRects, float[] segmentRadii, int segmentCount) {
        render(x, y, width, height, radius, radius, radius, radius, blurStrength, segmentRects, segmentRadii, segmentCount);
    }

    public void render3DBox(AABB box, double blurStrength) {
        this.ensureBoxProgram();

        RenderTarget fb = mc.gameRenderer.mainRenderTarget();
        if (fb.width <= 0 || fb.height <= 0) {
            return;
        }

        if (fb.getColorTexture() == null || fb.getColorTextureView() == null) {
            return;
        }

        if (input == null) {
            input = new TextureTarget("Lumin Blur Input", fb.width, fb.height, GpuFormat.RGBA8_UNORM, null);
        }

        if (this.input.width != fb.width || this.input.height != fb.height) {
            this.input.resize(fb.width, fb.height);
        }

        if (this.input.getColorTexture() == null || this.input.getColorTextureView() == null) {
            return;
        }

        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.copyTextureToTexture(
                fb.getColorTexture(),
                input.getColorTexture(),
                0, 0, 0, 0, 0,
                fb.width, fb.height
        );

        float quality = Math.max(0.0f, (float) blurStrength);
        GpuBufferSlice boxBlurUniforms = LuminRenderSystem.writeDynamicUniform(
                "box_blur_uniforms",
                "Lumin 3D Box Blur UBO",
                BOX_UNIFORMS_SIZE,
                16,
                new BoxBlurUniforms(fb.width, fb.height, quality)
        );

        LuminImmediateRenderer.PosColorQuads renderer = LuminImmediateRenderer.beginPosColorQuads(this.boxPipeline, pass -> {
            pass.setUniform("BoxBlurUniforms", boxBlurUniforms);
            pass.setUniform("InputSampler", input.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
        });
        addBoxVertices(renderer, box);
        renderer.end();
    }

    private void addBoxVertices(LuminImmediateRenderer.PosColorQuads renderer, AABB box) {
        Vec3 camPos = mc.getEntityRenderDispatcher().camera.position();

        float minX = (float) (box.minX - camPos.x);
        float minY = (float) (box.minY - camPos.y);
        float minZ = (float) (box.minZ - camPos.z);
        float maxX = (float) (box.maxX - camPos.x);
        float maxY = (float) (box.maxY - camPos.y);
        float maxZ = (float) (box.maxZ - camPos.z);

        Matrix4f matrix = mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.viewRotationMatrix;

        vertex(renderer, matrix, minX, minY, minZ);
        vertex(renderer, matrix, minX, minY, maxZ);
        vertex(renderer, matrix, maxX, minY, maxZ);
        vertex(renderer, matrix, maxX, minY, minZ);

        vertex(renderer, matrix, minX, maxY, minZ);
        vertex(renderer, matrix, maxX, maxY, minZ);
        vertex(renderer, matrix, maxX, maxY, maxZ);
        vertex(renderer, matrix, minX, maxY, maxZ);

        vertex(renderer, matrix, minX, minY, minZ);
        vertex(renderer, matrix, minX, maxY, minZ);
        vertex(renderer, matrix, maxX, maxY, minZ);
        vertex(renderer, matrix, maxX, minY, minZ);

        vertex(renderer, matrix, maxX, minY, minZ);
        vertex(renderer, matrix, maxX, maxY, minZ);
        vertex(renderer, matrix, maxX, maxY, maxZ);
        vertex(renderer, matrix, maxX, minY, maxZ);

        vertex(renderer, matrix, minX, minY, maxZ);
        vertex(renderer, matrix, maxX, minY, maxZ);
        vertex(renderer, matrix, maxX, maxY, maxZ);
        vertex(renderer, matrix, minX, maxY, maxZ);

        vertex(renderer, matrix, minX, minY, minZ);
        vertex(renderer, matrix, minX, minY, maxZ);
        vertex(renderer, matrix, minX, maxY, maxZ);
        vertex(renderer, matrix, minX, maxY, minZ);
    }

    private void vertex(LuminImmediateRenderer.PosColorQuads renderer, Matrix4f matrix, float x, float y, float z) {
        renderer.vertex(matrix, x, y, z, -1);
    }

    private static int blurUniformsSize() {
        Std140SizeCalculator calculator = new Std140SizeCalculator().putVec3().putVec4().putVec4().putVec4()
                .putVec4().putVec4();
        for (int i = 0; i < MAX_SEGMENTS * 2; i++) {
            calculator.putVec4();
        }
        return calculator.get();
    }

    private static int clampSegmentCount(float[] segmentRects, int segmentCount) {
        if (segmentRects == null || segmentCount <= 0) return 0;
        return Math.min(MAX_SEGMENTS, Math.min(segmentCount, segmentRects.length / 4));
    }

    private record BlurUniforms(
            float width,
            float height,
            float quality,
            float rectWidth,
            float rectHeight,
            float rectX,
            float rectY,
            float radiusTopLeft,
            float radiusTopRight,
            float radiusBottomRight,
            float radiusBottomLeft,
            float scale,
            float targetHeight,
            float opacity,
            float refraction,
            float dispersion,
            float saturation,
            float brightness,
            float innerShade,
            float specular,
            float specularWidth,
            float[] segmentRects,
            float[] segmentRadii,
            int segmentCount
    ) implements DynamicGpuDataStorage.DynamicGpuData {
        @Override
        public void write(ByteBuffer buffer) {
            Std140Builder builder = Std140Builder.intoBuffer(buffer)
                    .putVec3(width, height, quality)
                    .putVec4(rectWidth, rectHeight, rectX, rectY)
                    .putVec4(radiusTopLeft, radiusTopRight, radiusBottomRight, radiusBottomLeft)
                    // SegmentInfo.y 承载模糊层的表面不透明度，着色器用它缩放最终 alpha。
                    .putVec4(segmentCount, opacity, 0.0f, 0.0f)
                    // GlassParams / GlassSpecular 承载液态玻璃材质；PLAIN 档两个 vec4 均为中性值。
                    .putVec4(refraction, dispersion, saturation, brightness)
                    .putVec4(innerShade, specular, specularWidth, 0.0f);

            for (int i = 0; i < MAX_SEGMENTS; i++) {
                if (i < segmentCount) {
                    int offset = i * 4;
                    float segmentX = segmentRects[offset];
                    float segmentY = segmentRects[offset + 1];
                    float segmentWidth = segmentRects[offset + 2];
                    float segmentHeight = segmentRects[offset + 3];
                    builder.putVec4(segmentX * scale, targetHeight - (segmentY + segmentHeight) * scale, segmentWidth * scale, segmentHeight * scale);
                } else {
                    builder.putVec4(0.0f, 0.0f, 0.0f, 0.0f);
                }
            }

            for (int i = 0; i < MAX_SEGMENTS; i++) {
                float radius = segmentRadii != null && i < segmentCount && i < segmentRadii.length
                        ? Math.max(0.0f, segmentRadii[i] * scale)
                        : 0.0f;
                builder.putVec4(radius, 0.0f, 0.0f, 0.0f);
            }
        }
    }

    private record BoxBlurUniforms(
            float width, float height, float quality
    ) implements DynamicGpuDataStorage.DynamicGpuData {
        @Override
        public void write(ByteBuffer buffer) {
            Std140Builder.intoBuffer(buffer).putVec4(width, height, quality, 0.0f);
        }
    }

}
