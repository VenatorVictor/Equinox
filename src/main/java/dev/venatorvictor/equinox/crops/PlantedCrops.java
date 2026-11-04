package dev.venatorvictor.equinox.crops;

import com.mojang.serialization.Codec;
import dev.venatorvictor.equinox.Equinox;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

/**
 * Crops a player planted (the block placed, the bottom of the plant), kept by position with the chunk (server only).
 * With crops_that_die = PLANTED only these die for good; the others (world generation, villagers) come back when the
 * cold or heat is over (Crops, DeadCropBlock). The mark goes when the block there is no longer a crop or a dead crop.
 * Crops planted before this existed have no mark.
 */
public final class PlantedCrops {
    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS = DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, Equinox.MOD_ID);
    private static final Codec<IntOpenHashSet> CODEC = Codec.INT_STREAM.xmap(stream -> new IntOpenHashSet(stream.toArray()),
            set -> java.util.Arrays.stream(set.toIntArray()));
    public static final Supplier<AttachmentType<IntOpenHashSet>> TYPE = ATTACHMENTS.register("planted_crops",
            () -> AttachmentType.builder(() -> new IntOpenHashSet()).serialize(CODEC, set -> !set.isEmpty()).build());

    public static void init(IEventBus modBus) {
        ATTACHMENTS.register(modBus);
        NeoForge.EVENT_BUS.addListener(BlockEvent.EntityPlaceEvent.class, event -> {
            if (event.getEntity() instanceof Player && event.getLevel() instanceof ServerLevel level && Crops.mortal(event.getPlacedBlock().getBlock()))
                set(level, event.getPos(), true);
        });
    }

    /** Local position in a chunk: x and z 4 bits each, y + 2048 above them. */
    private static int key(BlockPos pos) {
        return (pos.getY() + 2048) << 8 | (pos.getZ() & 15) << 4 | (pos.getX() & 15);
    }

    public static boolean planted(ServerLevel level, BlockPos pos) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        IntOpenHashSet set = chunk == null ? null : chunk.getExistingDataOrNull(TYPE);
        return set != null && set.contains(key(pos));
    }

    private static void set(ServerLevel level, BlockPos pos, boolean planted) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null) return;
        IntOpenHashSet set = planted ? chunk.getData(TYPE) : chunk.getExistingDataOrNull(TYPE);
        if (set == null) return;
        if (planted ? set.add(key(pos)) : set.remove(key(pos))) chunk.setUnsaved(true);
    }

    /** LevelChunkMixin: the block at pos changed; the mark goes if it's no longer a crop (living or dead). */
    public static void onBlockChanged(Level level, BlockPos pos, BlockState old, BlockState now) {
        if (level instanceof ServerLevel server && old.getBlock() != now.getBlock() && !crop(now) && crop(old)) set(server, pos, false);
    }

    private static boolean crop(BlockState state) {
        return Crops.mortal(state.getBlock()) || state.getBlock() instanceof DeadCropBlock;
    }

    private PlantedCrops() {}
}
