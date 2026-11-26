package dev.venatorvictor.equinox.client.clouds;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.venatorvictor.equinox.Equinox;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.opengl.GL43C.*;

/**
 * What goes with the cloud cubes: the palette (their colours for the time of day), cloud shadows on the ground and
 * distant rain under raining cloud bases.
 */
public final class CloudExtras {
    public static final ResourceLocation PALETTE = ResourceLocation.fromNamespaceAndPath(Equinox.MOD_ID, "clouds_palette");
    private static DynamicTexture palette;
    private static float[][] colours = new float[CloudGpu.PALETTE_W * CloudGpu.PALETTE_H][];

    private static int shadowProgram = -1, shadowTexture, emptyArray;
    private static TextureTarget depthCopy;
    private static CloudSky.Sky shadowSky;

    private static float smooth(float a, float b, float x) {
        float t = Mth.clamp((x - a) / (b - a), 0, 1);
        return t * t * (3 - 2 * t);
    }

    // ---- palette

    /**
     * Remakes the palette: light across (0 shade .. 1 full sun), storm level down (0..2). The sunlit side is white
     * (warm at sunset), the shade side bluish lavender; storm greys darken the shade side most (a lit storm top stays
     * bright). All times vanilla's cloud colour for the time of day.
     */
    public static void palette(float timeOfDay) {
        float a = timeOfDay * Mth.TWO_PI;
        float day = Mth.clamp(Mth.cos(a) * 2 + 0.5F, 0, 1);
        float[] base = {day * 0.9F + 0.1F, day * 0.9F + 0.1F, day * 0.85F + 0.15F};
        float sunY = Mth.cos(a);
        float sunset = (1 - smooth(0.05F, 0.4F, Math.abs(sunY))) * smooth(-0.25F, 0.0F, sunY);
        float[] lit = {1, Mth.lerp(sunset * 0.8F, 1, 0.76F), Mth.lerp(sunset * 0.8F, 1, 0.56F)};
        float[] shade = {Mth.lerp(sunset, 0.60F, 0.58F), Mth.lerp(sunset, 0.65F, 0.50F), Mth.lerp(sunset, 0.80F, 0.66F)};
        if (palette == null) {
            palette = new DynamicTexture(CloudGpu.PALETTE_W, CloudGpu.PALETTE_H, false);
            palette.setFilter(true, false);
            Minecraft.getInstance().getTextureManager().register(PALETTE, palette);
        }
        NativeImage image = palette.getPixels();
        for (int v = 0; v < CloudGpu.PALETTE_H; v++) {
            float g = CloudSky.grey(v / (float) (CloudGpu.PALETTE_H - 1) * 2);
            for (int u = 0; u < CloudGpu.PALETTE_W; u++) {
                float light = u / (float) (CloudGpu.PALETTE_W - 1);
                float[] c = new float[3];
                for (int k = 0; k < 3; k++)
                    c[k] = Mth.clamp(Mth.lerp(light, shade[k] * g, lit[k] * (0.55F + 0.45F * g)) * base[k], 0, 1);
                colours[v * CloudGpu.PALETTE_W + u] = c;
                image.setPixelRGBA(u, v, 0xFF000000 | Math.round(c[2] * 255) << 16 | Math.round(c[1] * 255) << 8 | Math.round(c[0] * 255));
            }
        }
        palette.upload();
    }

    /** The palette colour for a light and storm level (for renderers that take colours). */
    public static float[] colour(float light, float storm) {
        int u = Mth.clamp(Math.round(light * (CloudGpu.PALETTE_W - 1)), 0, CloudGpu.PALETTE_W - 1);
        int v = Mth.clamp(Math.round(storm * 0.5F * (CloudGpu.PALETTE_H - 1)), 0, CloudGpu.PALETTE_H - 1);
        float[] c = colours[v * CloudGpu.PALETTE_W + u];
        return c == null ? new float[] {1, 1, 1} : c;
    }

    // ---- ground shadows

    private static int shadowProgram() {
        if (shadowProgram >= 0) return shadowProgram;
        shadowProgram = 0;
        try {
            int vs = shader(GL_VERTEX_SHADER, CloudGpu.read("shadow.vsh")), fs = shader(GL_FRAGMENT_SHADER, CloudGpu.read("shadow.fsh"));
            int program = glCreateProgram();
            glAttachShader(program, vs);
            glAttachShader(program, fs);
            glLinkProgram(program);
            glDeleteShader(vs);
            glDeleteShader(fs);
            if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) throw new IllegalStateException(glGetProgramInfoLog(program));
            int previous = glGetInteger(GL_CURRENT_PROGRAM);
            glUseProgram(program);
            glUniform1i(glGetUniformLocation(program, "uDepth"), 0);
            glUniform1i(glGetUniformLocation(program, "uShadow"), 1);
            glUseProgram(previous);
            shadowTexture = CloudGpu.texture();
            emptyArray = glGenVertexArrays();
            shadowProgram = program;
        } catch (Exception e) {
            Equinox.LOGGER.warn("Equinox: cloud shadows didn't work here, so there are none", e);
        }
        return shadowProgram;
    }

    private static int shader(int type, String source) {
        int id = glCreateShader(type);
        glShaderSource(id, source);
        glCompileShader(id);
        if (glGetShaderi(id, GL_COMPILE_STATUS) == GL_FALSE) throw new IllegalStateException(glGetShaderInfoLog(id));
        return id;
    }

    /**
     * Darkens the ground under the clouds (multiplies the picture where the sun is blocked). Drawn before the clouds,
     * into the main picture; sun: toward the sun; base: the cloud base, relative to the camera; offX, offZ, camX, camZ:
     * to place the shadow map; strength 0..1.
     */
    public static void shadows(CloudSky.Sky sky, Matrix4f view, Matrix4f projection, float[] sun, float base,
                               double offX, double offZ, double camX, double camZ, float strength) {
        if (strength <= 0.01F || sun[1] < 0.05F) return;
        int program = shadowProgram();
        if (program == 0) return;
        if (sky != shadowSky) {
            CloudGpu.upload(shadowTexture, sky.shadow(), sky.shadowSize(), 1);
            shadowSky = sky;
        }
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        if (depthCopy == null) depthCopy = new TextureTarget(main.width, main.height, true, Minecraft.ON_OSX);
        if (main.isStencilEnabled() && !depthCopy.isStencilEnabled()) depthCopy.enableStencil();
        if (depthCopy.width != main.width || depthCopy.height != main.height) depthCopy.resize(main.width, main.height, Minecraft.ON_OSX);
        depthCopy.copyDepthFrom(main);
        main.bindWrite(true);

        Matrix4f inverse = new Matrix4f(projection).mul(view).invert();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.DST_COLOR, GlStateManager.DestFactor.ZERO);
        int previous = glGetInteger(GL_CURRENT_PROGRAM);
        glUseProgram(program);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            glUniformMatrix4fv(glGetUniformLocation(program, "uInverse"), false, inverse.get(stack.mallocFloat(16)));
        }
        glUniform3f(glGetUniformLocation(program, "uSun"), sun[0], sun[1], sun[2]);
        glUniform1f(glGetUniformLocation(program, "uCloudY"), base);
        glUniform2f(glGetUniformLocation(program, "uShadowOrigin"), (float) (sky.centerX() + offX + sky.shadowX0() - camX),
                (float) (sky.centerZ() + offZ + sky.shadowZ0() - camZ));
        glUniform1f(glGetUniformLocation(program, "uShadowSpan"), (float) sky.shadowSize() * CloudSky.SHADOW_CELL);
        glUniform1f(glGetUniformLocation(program, "uStrength"), strength);
        glUniform1f(glGetUniformLocation(program, "uReach"), sky.shadowSize() * CloudSky.SHADOW_CELL * 0.5F);
        GlStateManager._activeTexture(GL_TEXTURE0 + 1);
        GlStateManager._bindTexture(shadowTexture);
        GlStateManager._activeTexture(GL_TEXTURE0);
        GlStateManager._bindTexture(depthCopy.getDepthTextureId());
        BufferUploader.invalidate();
        GlStateManager._glBindVertexArray(emptyArray);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        GlStateManager._glBindVertexArray(0);
        GlStateManager._bindTexture(0);
        glUseProgram(previous);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
    }

    // ---- distant rain

    /**
     * Grey veils of rain under raining cloud bases, from the base down to the ground, beyond the rain the game draws
     * around you (they fade in from `near` blocks). Upright quads facing the camera, one per map texel that rains.
     */
    public static void rain(CloudSky.Sky sky, Matrix4f view, double offX, double offZ, double camX, double camY, double camZ,
                            float height, float ground, float near, float reach, float timeOfDay) {
        float day = Mth.clamp(Mth.cos(timeOfDay * Mth.TWO_PI) * 2 + 0.5F, 0, 1) * 0.85F + 0.15F;
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        int n = sky.mapSize();
        float half = CloudSky.MAP_CELL * 0.6F;
        float bottom = (float) (ground - camY);
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                int o = (j * n + i) * 4;
                float storm = sky.map0()[o + 3];
                if (storm < 1.05F) continue;
                float x = (float) (sky.centerX() + sky.mapX0() + (i + 0.5) * CloudSky.MAP_CELL + offX - camX);
                float z = (float) (sky.centerZ() + sky.mapZ0() + (j + 0.5) * CloudSky.MAP_CELL + offZ - camZ);
                float dist = Mth.sqrt(x * x + z * z);
                float fade = smooth(near, near + 100, dist) * (1 - smooth(reach * 0.7F, reach, dist));
                if (fade <= 0.01F) continue;
                float rain = Mth.clamp(storm - 1, 0, 1);
                float alpha = 0.32F * Mth.clamp(rain * 2.5F, 0, 1) * fade;
                float g = CloudSky.grey(storm) * day;
                float top = (float) (height + sky.map0()[o + 1] - camY);
                if (top <= bottom) continue;
                float sx = -z / dist * half, sz = x / dist * half;
                float r = g * 0.70F, gg = g * 0.73F, b = g * 0.80F;
                builder.addVertex(x - sx, bottom, z - sz).setColor(r, gg, b, alpha * 0.35F);
                builder.addVertex(x + sx, bottom, z + sz).setColor(r, gg, b, alpha * 0.35F);
                builder.addVertex(x + sx, top, z + sz).setColor(r, gg, b, alpha);
                builder.addVertex(x - sx, top, z - sz).setColor(r, gg, b, alpha);
            }
        }
        MeshData mesh = builder.build();
        if (mesh == null) return;
        Matrix4fStack stack = RenderSystem.getModelViewStack();
        stack.pushMatrix();
        stack.identity();
        stack.mul(view);
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        BufferUploader.drawWithShader(mesh);
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        stack.popMatrix();
        RenderSystem.applyModelViewMatrix();
    }

    /** Everything dropped (leaving a world). */
    public static void clear() {
        shadowSky = null;
    }

    private CloudExtras() {
    }
}
