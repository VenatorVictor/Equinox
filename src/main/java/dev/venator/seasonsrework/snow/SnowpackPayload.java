package dev.venator.seasonsrework.snow;

import dev.venator.seasonsrework.SeasonsRework;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import java.util.HashMap;
import java.util.Map;

/** Server to client: the snowpack (warmest since the last snowfall, per biome), for LOD snow and snowy textures. */
public record SnowpackPayload(Map<ResourceLocation, Float> warmest) implements CustomPacketPayload {
    public static final Type<SnowpackPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SeasonsRework.MOD_ID, "snowpack"));
    public static final StreamCodec<ByteBuf, SnowpackPayload> CODEC = ByteBufCodecs.<ByteBuf, ResourceLocation, Float, Map<ResourceLocation, Float>>map(
            HashMap::new, ResourceLocation.STREAM_CODEC, ByteBufCodecs.FLOAT).map(SnowpackPayload::new, SnowpackPayload::warmest);

    @Override
    public Type<SnowpackPayload> type() {
        return TYPE;
    }

    // Optional: a client or server without the mod still connects (the client then uses the temperature now).
    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("2").optional().playToClient(TYPE, CODEC,
                (payload, context) -> context.enqueueWork(() -> ClientSnow.receive(payload.warmest())));
    }
}
