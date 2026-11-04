package dev.venatorvictor.equinox.client;

import dev.venatorvictor.equinox.Equinox;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/** The shader loader's block id map and its chunk renderer's id override, looked up once by name (ShaderSnowIdMixin). */
public final class ShaderIds {
    private static volatile boolean looked;
    private static Class<?> encoder;
    private static MethodHandle override, restore, idMap;

    public static boolean ready(Object renderer) {
        if (!looked) find(renderer.getClass().getClassLoader());
        return encoder != null && encoder.isInstance(renderer);
    }

    private static synchronized void find(ClassLoader loader) {
        if (looked) return;
        try {
            encoder = Class.forName("net.irisshaders.iris.vertices.sodium.terrain.VertexEncoderInterface", false, loader);
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            override = lookup.findVirtual(encoder, "overrideBlock", MethodType.methodType(void.class, int.class));
            restore = lookup.findVirtual(encoder, "restoreBlock", MethodType.methodType(void.class));
            Class<?> settings = Class.forName("net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings", false, loader);
            Object instance = settings.getField("INSTANCE").get(null);
            idMap = lookup.findVirtual(settings, "getBlockStateIds", MethodType.methodType(Object2IntMap.class)).bindTo(instance);
            Equinox.LOGGER.info("Equinox: shader ids for snow in plants found");
        } catch (ReflectiveOperationException | LinkageError e) {
            encoder = null;
        }
        looked = true;
    }

    /** snow: the snow layer's id; null: the block's own id. */
    @SuppressWarnings("unchecked")
    public static void set(Object renderer, BlockState snow) {
        try {
            Object2IntMap<BlockState> ids = (Object2IntMap<BlockState>) idMap.invoke();
            if (ids == null) return;
            if (snow != null && ids.containsKey(snow)) override.invoke(renderer, ids.getInt(snow));
            else restore.invoke(renderer);
        } catch (Throwable e) {
            Equinox.LOGGER.warn("Equinox: couldn't set the shader id of snow in plants: {}", e.toString());
            encoder = null;
        }
    }
}
