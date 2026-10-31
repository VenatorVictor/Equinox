package dev.venator.seasonsrework.climate;

import dev.venator.seasonsrework.Config;
import dev.venator.seasonsrework.SeasonsRework;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import java.util.ArrayList;
import java.util.List;

/** Server to client: a chunk's climate blend mix (ClimateBlend), sent with the chunk. */
public record ClimateBlendPayload(long chunk, List<Holder<Biome>> biomes, List<Float> weights) implements CustomPacketPayload {
    public static final Type<ClimateBlendPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SeasonsRework.MOD_ID, "climate_blend"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ClimateBlendPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, ClimateBlendPayload::chunk,
            ByteBufCodecs.holderRegistry(Registries.BIOME).apply(ByteBufCodecs.list()), ClimateBlendPayload::biomes,
            ByteBufCodecs.FLOAT.apply(ByteBufCodecs.list()), ClimateBlendPayload::weights,
            ClimateBlendPayload::new);

    @Override
    public Type<ClimateBlendPayload> type() {
        return TYPE;
    }

    // Optional: a client without the mod still connects (without blending).
    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").optional().playToClient(TYPE, CODEC, (payload, context) -> context.enqueueWork(payload::receive));
    }

    private void receive() {
        int count = Math.min(biomes.size(), weights.size());
        Biome[] array = new Biome[count];
        float[] values = new float[count];
        for (int i = 0; i < count; i++) {
            array[i] = biomes.get(i).value();
            values[i] = weights.get(i);
        }
        ClimateBlend.put(chunk, new ClimateBlend.Mix(array, values));
    }

    /** Server: work out mixes as chunks load, send them as chunks are sent; forget them when the server stops. */
    public static void init() {
        NeoForge.EVENT_BUS.addListener(ChunkEvent.Load.class, e -> {
            if (e.getLevel() instanceof ServerLevel level && Config.hasSeasons(level.dimension())) ClimateBlend.compute(level, e.getChunk().getPos());
        });
        NeoForge.EVENT_BUS.addListener(ChunkWatchEvent.Sent.class, e -> {
            ServerLevel level = e.getLevel();
            if (!Config.hasSeasons(level.dimension())) return;
            ChunkPos pos = e.getPos();
            ClimateBlend.Mix mix = ClimateBlend.compute(level, pos);
            if (mix == null) return;
            List<Holder<Biome>> holders = new ArrayList<>();
            List<Float> weights = new ArrayList<>();
            var registry = level.registryAccess().registryOrThrow(Registries.BIOME);
            for (int i = 0; i < mix.biomes().length; i++) {
                // Only registered biomes can be sent (by id).
                var key = registry.getResourceKey(mix.biomes()[i]);
                if (key.isEmpty()) continue;
                holders.add(registry.getHolderOrThrow(key.get()));
                weights.add(mix.weights()[i]);
            }
            if (holders.isEmpty()) return;
            PacketDistributor.sendToPlayer(e.getPlayer(), new ClimateBlendPayload(pos.toLong(), holders, weights));
        });
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent.class, e -> ClimateBlend.clearServer());
    }
}
