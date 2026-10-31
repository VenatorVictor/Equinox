package dev.venator.seasonsrework.client.dh;

import com.seibel.distanthorizons.common.wrappers.block.BlockStateWrapper_neoforge;
import com.seibel.distanthorizons.core.dataObjects.fullData.FullDataPointIdMap;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos;
import com.seibel.distanthorizons.core.util.FullDataPointUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.block.IBlockStateWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IBiomeWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import dev.venator.seasonsrework.client.ClientConfig;
import dev.venator.seasonsrework.climate.ClimateBiome;
import dev.venator.seasonsrework.snow.ClientSnow;
import dev.venator.seasonsrework.snow.SnowRules;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Seasonal snow and ice on Distant Horizons LODs, called from DH's column builder (worker threads).
 * Columns are listed top to bottom, so index - 1 is the run of blocks right above.
 * Uses the same rule as the real snow (SnowRules.lies, with the server's snowpack); ice follows the temperature.
 */
public final class DhSnow {
    private static final ThreadLocal<BlockPos.MutableBlockPos> POS = ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);
    private static volatile int snowColor = 0;
    private static final Map<Biome, Holder<Biome>> CLIENT_BIOMES = new ConcurrentHashMap<>();

    /** Freezes open water where it's cold, and melts snow/ice that DH saved while it was cold. */
    public static IBlockStateWrapper replaceBlock(IBlockStateWrapper block, IClientLevelWrapper levelWrapper, IBiomeWrapper biomeWrapper,
                                                  LongArrayList column, FullDataPointIdMap mapping, int index, int x, int y, int z) {
        if (!(block.getWrappedMcObject() instanceof BlockState state)) return block;
        boolean water = state.is(Blocks.WATER) && state.getFluidState().isSource();
        boolean snow = state.is(Blocks.SNOW);
        boolean ice = state.is(Blocks.ICE);
        if (!water && !snow && !ice || !ClientConfig.DH_SNOW.get()) return block;
        if (!(levelWrapper.getWrappedMcObject() instanceof Level level)) return block;
        Holder<Biome> biome = biome(biomeWrapper, level);
        if (biome == null) return block;
        BlockPos pos = POS.get().set(x, y, z);
        if (snow) return SnowRules.lies(biome, pos, ClientSnow.warmest()) ? block : BlockStateWrapper_neoforge.AIR;
        boolean cold = SnowRules.cold(biome, pos);
        if (!above(column, mapping, index).isAir()) return block;
        if (water && cold) return BlockStateWrapper_neoforge.getIceBlockStateWrapper(levelWrapper);
        if (ice && !cold) return BlockStateWrapper_neoforge.getWaterBlockStateWrapper(levelWrapper);
        return block;
    }

    /** Snow color for the top of a column where snow would lie. */
    public static int color(int original, IClientLevelWrapper levelWrapper, IBiomeWrapper biomeWrapper, FullDataSourceV2 source, IBlockStateWrapper block,
                            DhBlockPos dhPos, LongArrayList column, FullDataPointIdMap mapping, int index, int topY, int skyLight, int blockLight) {
        // Cheap checks first: this runs for every block run DH colors.
        if (skyLight == 0 || blockLight >= 10 || original >>> 24 == 0) return original;
        if (block.isAir() || block.isLiquid() || block.isIceBlock() || !ClientConfig.DH_SNOW.get()) return original;
        IBlockStateWrapper above = above(column, mapping, index);
        if (above.isSolid() || above.isLiquid()) return original;
        if (!(levelWrapper.getWrappedMcObject() instanceof Level level)) return original;
        Holder<Biome> biome = biome(biomeWrapper, level);
        if (biome == null) return original;
        BlockPos pos = POS.get().set(dhPos.getX(), topY + levelWrapper.getMinHeight(), dhPos.getZ());
        if (!SnowRules.lies(biome, pos, ClientSnow.warmest())) return original;
        return snowColor(levelWrapper, biomeWrapper, source, dhPos);
    }

    static void clearCache() {
        snowColor = 0;
        CLIENT_BIOMES.clear();
    }

    /** The color DH gives real snow, so painted and real snow match. */
    private static int snowColor(IClientLevelWrapper levelWrapper, IBiomeWrapper biome, FullDataSourceV2 source, DhBlockPos pos) {
        int color = snowColor;
        if (color == 0) {
            color = levelWrapper.getBlockColor(pos, biome, source, BlockStateWrapper_neoforge.getSnowBlockStateWrapper(levelWrapper)) | 0xFF000000;
            snowColor = color;
        }
        return color;
    }

    private static IBlockStateWrapper above(LongArrayList column, FullDataPointIdMap mapping, int index) {
        if (index == 0) return BlockStateWrapper_neoforge.AIR;
        try {
            return mapping.getBlockStateWrapper(FullDataPointUtil.getId(column.getLong(index - 1)));
        } catch (IndexOutOfBoundsException e) {
            return BlockStateWrapper_neoforge.AIR;
        }
    }

    /**
     * The client's biome: in single player DH may hand over the server's biome objects, which don't see the client's
     * climate blending (ClimateBlend keeps the far mixes on the client side), so they're swapped for the client's.
     */
    @SuppressWarnings("unchecked")
    private static Holder<Biome> biome(IBiomeWrapper wrapper, Level level) {
        Object biome = wrapper.getWrappedMcObject();
        Holder<Biome> holder;
        if (biome instanceof Holder<?> h && h.value() instanceof Biome) holder = (Holder<Biome>) h;
        else if (biome instanceof Biome raw) holder = Holder.direct(raw);
        else return null;
        if (((ClimateBiome) (Object) holder.value()).seasonsrework$clientSide()) return holder;
        return CLIENT_BIOMES.computeIfAbsent(holder.value(), server -> {
            var registry = level.registryAccess().registryOrThrow(Registries.BIOME);
            var key = holder.unwrapKey();
            return key.isPresent() ? registry.getHolder(key.get()).<Holder<Biome>>map(h -> h).orElse(holder) : holder;
        });
    }

    private DhSnow() {}
}
