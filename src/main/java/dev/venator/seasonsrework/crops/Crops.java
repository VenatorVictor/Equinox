package dev.venator.seasonsrework.crops;

import dev.venator.seasonsrework.Config;
import dev.venator.seasonsrework.api.Season;
import dev.venator.seasonsrework.api.Seasons;
import dev.venator.seasonsrework.climate.Climate;
import dev.venator.seasonsrework.climate.ClimateBiome;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.entity.player.BonemealEvent;
import net.neoforged.neoforge.event.level.BlockGrowFeatureEvent;
import net.neoforged.neoforge.event.level.block.CropGrowEvent;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Seasonal crops. Each crop's seasons are a bit mask (bit = Season ordinal) built once when tags load, so a growth
 * check is a map lookup. Hooked only into growth events (crops, stems, berries, cane, cactus, bamboo, cocoa: the
 * crop event; saplings, fungi, azaleas: the feature event) and bone meal, not into every block's random tick.
 *
 * Where it grows well: in its seasons; in tropical and hot biomes, summer crops all year; where it's snowing
 * (temperature below 0.15) winter crops only; under glass, underground or in biomes without seasons, always.
 * Elsewhere it grows on 1 in out_of_season_growth chances.
 */
public final class Crops {
    private static final TagKey<Block>[] BLOCK_TAGS = blockTags();
    private static final TagKey<Item>[] ITEM_TAGS = itemTags();
    public static final TagKey<Block> GREENHOUSE_GLASS = TagKey.create(Registries.BLOCK, id("greenhouse_glass"));
    private static final int GLASS_RANGE = 16;

    private static volatile Map<Block, Byte> blockSeasons = Map.of();
    private static volatile Map<Item, Byte> itemSeasons = Map.of();
    // Bone meal that passed the check: the tree it grows on the same tick isn't checked again (server thread only).
    private static BlockPos bonemealPos;
    private static long bonemealTick = -1;

    public static void onTagsUpdated(TagsUpdatedEvent event) {
        Map<Block, Byte> blocks = new IdentityHashMap<>();
        Map<Item, Byte> items = new IdentityHashMap<>();
        for (Season season : Season.VALUES) {
            byte bit = (byte) (1 << season.ordinal());
            for (Holder<Block> holder : BuiltInRegistries.BLOCK.getTagOrEmpty(BLOCK_TAGS[season.ordinal()]))
                blocks.merge(holder.value(), bit, (a, b) -> (byte) (a | b));
            for (Holder<Item> holder : BuiltInRegistries.ITEM.getTagOrEmpty(ITEM_TAGS[season.ordinal()]))
                items.merge(holder.value(), bit, (a, b) -> (byte) (a | b));
        }
        blockSeasons = blocks;
        itemSeasons = items;
    }

    /** The seasons a block grows well in (bit = Season ordinal), 0 if it isn't a seasonal crop. */
    public static int seasonsOf(Block block) {
        return blockSeasons.getOrDefault(block, (byte) 0);
    }

    /** For tooltips: from the item tags, and from the block a seed or crop item places. */
    public static int seasonsOf(Item item) {
        int seasons = itemSeasons.getOrDefault(item, (byte) 0);
        if (item instanceof BlockItem blockItem) seasons |= seasonsOf(blockItem.getBlock());
        return seasons;
    }

    /** Whether the crop at pos grows at full speed now. */
    public static boolean growsWell(Level level, BlockPos pos, BlockState state) {
        int seasons = seasonsOf(state.getBlock());
        if (seasons == 0 || !Config.seasonalCrops()) return true;
        var seasonState = Seasons.get(level);
        if (seasonState == null) return true;
        seasonState = seasonState.lagged(Config.temperatureLagDays()); // crop seasons follow the temperature
        if (pos.getY() < Config.undergroundLevel() && !level.canSeeSky(pos)) return true;
        Biome biome = level.getBiome(pos).value();
        boolean inSeason = switch (Climate.climateOf(biome)) {
            case ClimateBiome.SEASONAL -> biome.warmEnoughToRain(pos)
                    ? (seasons & 1 << seasonState.season().ordinal()) != 0
                    : (seasons & 1 << Season.WINTER.ordinal()) != 0;
            case ClimateBiome.TROPICAL, ClimateBiome.HOT -> (seasons & 1 << Season.SUMMER.ordinal()) != 0;
            default -> true;
        };
        return inSeason || underGlass(level, pos);
    }

    /** Out of season: grows on 1 in N chances. */
    private static boolean blocked(Level level, BlockPos pos, BlockState state) {
        if (growsWell(level, pos, state)) return false;
        int chance = Config.outOfSeasonGrowth();
        return chance == 0 || level.random.nextInt(chance) != 0;
    }

    private static boolean underGlass(Level level, BlockPos pos) {
        BlockPos.MutableBlockPos cursor = pos.mutable();
        for (int i = 0; i < GLASS_RANGE; i++)
            if (level.getBlockState(cursor.move(0, 1, 0)).is(GREENHOUSE_GLASS)) return true;
        return false;
    }

    public static void onCropGrow(CropGrowEvent.Pre event) {
        if (event.getResult() == CropGrowEvent.Pre.Result.DO_NOT_GROW || !(event.getLevel() instanceof Level level)) return;
        if (blocked(level, event.getPos(), event.getState())) event.setResult(CropGrowEvent.Pre.Result.DO_NOT_GROW);
    }

    public static void onTreeGrow(BlockGrowFeatureEvent event) {
        if (!(event.getLevel() instanceof Level level)) return;
        if (event.getPos().equals(bonemealPos) && level.getGameTime() == bonemealTick) return;
        if (blocked(level, event.getPos(), level.getBlockState(event.getPos()))) event.setCanceled(true);
    }

    /** Out of season, bone meal is used up but usually does nothing (server only; the client shows the swing). */
    public static void onBonemeal(BonemealEvent event) {
        Level level = event.getLevel();
        if (level.isClientSide || !event.isValidBonemealTarget()) return;
        if (blocked(level, event.getPos(), event.getState())) {
            event.setSuccessful(true);
            event.getStack().shrink(1);
        } else {
            bonemealPos = event.getPos().immutable();
            bonemealTick = level.getGameTime();
        }
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("seasonsrework", path);
    }

    @SuppressWarnings("unchecked")
    private static TagKey<Block>[] blockTags() {
        TagKey<Block>[] tags = new TagKey[Season.COUNT];
        for (Season season : Season.VALUES) tags[season.ordinal()] = TagKey.create(Registries.BLOCK, id(season.id() + "_crops"));
        return tags;
    }

    @SuppressWarnings("unchecked")
    private static TagKey<Item>[] itemTags() {
        TagKey<Item>[] tags = new TagKey[Season.COUNT];
        for (Season season : Season.VALUES) tags[season.ordinal()] = TagKey.create(Registries.ITEM, id(season.id() + "_crops"));
        return tags;
    }

    private Crops() {}
}
