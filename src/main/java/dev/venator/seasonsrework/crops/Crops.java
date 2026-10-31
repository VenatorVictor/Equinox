package dev.venator.seasonsrework.crops;

import dev.venator.seasonsrework.Config;
import dev.venator.seasonsrework.api.Season;
import dev.venator.seasonsrework.api.Seasons;
import dev.venator.seasonsrework.api.Temperatures;
import dev.venator.seasonsrework.mixin.AttachedStemBlockAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.AttachedStemBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.FarmlandWaterManager;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.entity.player.BonemealEvent;
import net.neoforged.neoforge.event.level.BlockGrowFeatureEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.block.CropGrowEvent;
import net.neoforged.neoforge.registries.datamaps.DataMapsUpdatedEvent;

import org.jetbrains.annotations.Nullable;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Crops by temperature in °C (dev.venator.seasonsrework.api.Temperatures, plus heaters and coolers, CropWarmth). Each
 * crop has a range (CropClimate: data map, else from its season tags). On the day's average: below grows_from =
 * dormant, ideal = faster, cool or warm = 1 in slow_growth chances, above grows_to = stops (dies on dry farmland).
 * Tonight's low below the cold limit or today's high above the heat limit (or the heat stop zone on dry farmland)
 * kills crops standing on farmland (CropBlock, StemBlock; others like rice panicles or tomatoes on rope only stop):
 * they become a dead crop. Other plants (saplings, berries, cane, cocoa...) only grow slower or stop. Hooked into the growth
 * events, bone meal and the crop's own random tick (RandomTickMixin); ripe crops random tick too (CropBlockMixin) so
 * frost reaches them. Dimensions without seasons and plants without a range grow as in vanilla.
 */
public final class Crops {
    private static final TagKey<Block>[] BLOCK_TAGS = blockTags();
    private static final TagKey<Item>[] ITEM_TAGS = itemTags();

    /** DORMANT: too cold to grow. STOP: too hot to grow. */
    public enum Effect { VANILLA, FASTER, SLOW, DORMANT, STOP, FROST, HEAT }

    private static volatile Map<Block, Byte> blockSeasons = Map.of();
    private static volatile Map<Item, Byte> itemSeasons = Map.of();
    // Bone meal that passed the check: the tree it grows on the same tick isn't checked again (server thread only).
    private static BlockPos bonemealPos;
    private static long bonemealTick = -1;
    // The last check (the random tick hook and the growth event right after ask for the same crop; server thread).
    private static long lastPos, lastTick = -1;
    private static Effect lastEffect;

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

    public static void onDataMapsUpdated(DataMapsUpdatedEvent event) {
        if (event.getRegistryKey() == Registries.BLOCK) CropWarmth.reset();
    }

    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof Level level) CropWarmth.onChunkUnload(level, event.getChunk().getPos());
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

    /** A block's range: the data map's, else from its season tags, else null. */
    public static CropClimate climateOf(Block block) {
        CropClimate climate = block.builtInRegistryHolder().getData(CropClimate.TYPE);
        return climate != null ? climate : CropClimate.ofSeasons(seasonsOf(block));
    }

    /** What the temperature does to the plant at pos now. */
    public static Effect effect(Level level, BlockPos pos, BlockState state) {
        if (!Config.seasonalCrops() || Seasons.get(level) == null) return Effect.VANILLA;
        if (pos.asLong() == lastPos && level.getGameTime() == lastTick && !level.isClientSide) return lastEffect;
        CropClimate climate = climateOf(state.getBlock());
        Effect effect = Effect.VANILLA;
        if (climate != null) {
            Temperatures.DayRange today = Temperatures.airToday(level, pos);
            float low = CropWarmth.apply(level, pos, today.low()), high = CropWarmth.apply(level, pos, today.high());
            float mean = (low + high) / 2;
            if (low < climate.min()) effect = Effect.FROST;
            else if (high > climate.max()) effect = Effect.HEAT;
            else if (mean < climate.growsFrom()) effect = Effect.DORMANT;
            else if (mean > climate.growsTo()) effect = dryFarmland(level, pos) ? Effect.HEAT : Effect.STOP;
            else if (mean < climate.idealMin() || mean > climate.idealMax()) effect = Effect.SLOW;
            else effect = Effect.FASTER;
        }
        if (!level.isClientSide) {
            lastPos = pos.asLong();
            lastTick = level.getGameTime();
            lastEffect = effect;
        }
        return effect;
    }

    /**
     * How far a living crop is from growing well, for its look (client, CropLookModel): 0 = fine, 1 = growing slowly
     * near a limit (the warning), 2..4 = stopped, further past the limit (4 = at the killing limit). Negative = cold,
     * positive = heat.
     */
    public static int look(Level level, BlockPos pos, BlockState state) {
        if (!Config.seasonalCrops() || Seasons.get(level) == null) return 0;
        CropClimate climate = climateOf(state.getBlock());
        if (climate == null) return 0;
        Temperatures.DayRange today = Temperatures.airToday(level, pos);
        float low = CropWarmth.apply(level, pos, today.low()), high = CropWarmth.apply(level, pos, today.high());
        float mean = (low + high) / 2;
        if (low < climate.min()) return -4;
        if (high > climate.max()) return 4;
        if (mean < climate.growsFrom()) return -step(climate.growsFrom() - mean, climate.growsFrom() - climate.min());
        if (mean > climate.growsTo()) return step(mean - climate.growsTo(), climate.max() - climate.growsTo());
        if (mean < climate.idealMin()) return -1;
        if (mean > climate.idealMax()) return 1;
        return 0;
    }

    /** Stopped: 2, 3 or 4 by how far past the limit, out of the room left before it dies. */
    private static int step(float past, float room) {
        float f = room <= 0 ? 1 : past / room;
        return f < 1 / 3F ? 2 : f < 2 / 3F ? 3 : 4;
    }

    /** Frost and heat kill only crops on farmland. */
    public static boolean mortal(Block block) {
        return block instanceof CropBlock || block instanceof StemBlock || block instanceof AttachedStemBlock;
    }

    /** The fruit block an attached stem grows (pumpkin, melon), or null. */
    public static @Nullable Block fruitOf(Block block) {
        if (!(block instanceof AttachedStemBlock)) return null;
        return BuiltInRegistries.BLOCK.getOptional(((AttachedStemBlockAccessor) block).seasonsrework$fruit()).orElse(null);
    }

    /** Every block an attached stem grows (pumpkin, melon, modded ones). */
    public static java.util.Set<Block> fruits() {
        java.util.Set<Block> fruits = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (Block block : BuiltInRegistries.BLOCK) {
            Block fruit = fruitOf(block);
            if (fruit != null) fruits.add(fruit);
        }
        return fruits;
    }

    /** The attached stem holding the fruit at pos (a stem next to it, facing it), or null. */
    public static @Nullable BlockPos stemOf(LevelReader level, BlockPos pos, Block fruit) {
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos stem = pos.relative(side);
            BlockState state = level.getBlockState(stem);
            if (state.getBlock() instanceof AttachedStemBlock && state.getValue(AttachedStemBlock.FACING) == side.getOpposite()
                    && fruitOf(state.getBlock()) == fruit) return stem;
        }
        return null;
    }

    /** RandomTickMixin, before every random tick of a block: kills a farmland crop past its limits. True = it died. */
    public static boolean onRandomTick(BlockState state, ServerLevel level, BlockPos pos) {
        if (!mortal(state.getBlock())) return false;
        if (state.hasProperty(SnowyCrops.SNOWY)) { // snow settles or melts; this tick does nothing else
            boolean snowy = SnowyCrops.snowy(state, level, pos);
            if (snowy != state.getValue(SnowyCrops.SNOWY)) {
                level.setBlock(pos, state.setValue(SnowyCrops.SNOWY, snowy), Block.UPDATE_CLIENTS);
                return true;
            }
        }
        if (!(level.getBlockState(pos.below()).getBlock() instanceof FarmBlock)) return false;
        Effect effect = effect(level, pos, state);
        if (effect != Effect.FROST && effect != Effect.HEAT) return false;
        DeadCropBlock.Cause cause = effect == Effect.FROST ? DeadCropBlock.Cause.FROST : DeadCropBlock.Cause.HEAT;
        BlockState dead = CropBlocks.DEAD_CROP.get().defaultBlockState().setValue(DeadCropBlock.CAUSE, cause);
        boolean snowy = cause == DeadCropBlock.Cause.FROST && state.hasProperty(SnowyCrops.SNOWY) && state.getValue(SnowyCrops.SNOWY);
        level.setBlock(pos, dead.setValue(DeadCropBlock.SNOWY, snowy), Block.UPDATE_ALL);
        if (level.getBlockEntity(pos) instanceof DeadCropBlockEntity entity) entity.setOriginal(state);
        Block fruit = fruitOf(state.getBlock());
        if (fruit != null) { // the pumpkin or melon it holds dies with it
            BlockPos fruitPos = pos.relative(state.getValue(AttachedStemBlock.FACING));
            BlockState fruitState = level.getBlockState(fruitPos);
            if (fruitState.is(fruit)) {
                level.setBlock(fruitPos, CropBlocks.DEAD_FRUIT.get().defaultBlockState().setValue(DeadCropBlock.CAUSE, cause), Block.UPDATE_ALL);
                if (level.getBlockEntity(fruitPos) instanceof DeadCropBlockEntity entity) entity.setOriginal(fruitState);
            }
        }
        return true;
    }

    /**
     * FarmBlockMixin: in heat (today's high at least farmland_heat) farmland stays wet only with water within 2 blocks
     * instead of 4. Null: vanilla's check.
     */
    public static Boolean nearWater(LevelReader reader, BlockPos pos) {
        if (!(reader instanceof Level level) || !Config.seasonalCrops() || Seasons.get(level) == null) return null;
        if (Temperatures.airToday(level, pos.above()).high() < Config.farmlandHeat()) return null;
        BlockState state = level.getBlockState(pos);
        for (BlockPos water : BlockPos.betweenClosed(pos.offset(-2, 0, -2), pos.offset(2, 1, 2)))
            if (state.canBeHydrated(level, pos, level.getFluidState(water), water)) return true;
        return FarmlandWaterManager.hasBlockWaterTicket(level, pos);
    }

    private static boolean dryFarmland(Level level, BlockPos pos) {
        BlockState below = level.getBlockState(pos.below());
        return below.getBlock() instanceof FarmBlock && below.getValue(FarmBlock.MOISTURE) == 0;
    }

    /** Slow: grows on 1 in slow_growth chances. Stop, frost, heat: never. */
    private static boolean blocked(Level level, BlockPos pos, BlockState state) {
        return switch (effect(level, pos, state)) {
            case VANILLA, FASTER -> false;
            case SLOW -> {
                int chance = Config.slowGrowth();
                yield chance == 0 || level.random.nextInt(chance) != 0;
            }
            default -> true;
        };
    }

    public static void onCropGrow(CropGrowEvent.Pre event) {
        if (event.getResult() == CropGrowEvent.Pre.Result.DO_NOT_GROW || !(event.getLevel() instanceof Level level)) return;
        Effect effect = effect(level, event.getPos(), event.getState());
        if (effect == Effect.FASTER) {
            if (level.random.nextFloat() < Config.idealBonus()) event.setResult(CropGrowEvent.Pre.Result.GROW);
        } else if (blocked(level, event.getPos(), event.getState())) {
            event.setResult(CropGrowEvent.Pre.Result.DO_NOT_GROW);
        }
    }

    public static void onTreeGrow(BlockGrowFeatureEvent event) {
        if (!(event.getLevel() instanceof Level level)) return;
        if (event.getPos().equals(bonemealPos) && level.getGameTime() == bonemealTick) return;
        if (blocked(level, event.getPos(), level.getBlockState(event.getPos()))) event.setCanceled(true);
    }

    /** Slow or stopped: bone meal is used up but usually does nothing (server only; the client shows the swing). */
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
