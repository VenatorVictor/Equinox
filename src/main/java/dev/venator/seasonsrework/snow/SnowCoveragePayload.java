package dev.venator.seasonsrework.snow;

import dev.venator.seasonsrework.SeasonsRework;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Server to client: whether the first snow has fallen, so LODs show snow when the ground gets it. */
public record SnowCoveragePayload(float coverage) implements CustomPacketPayload {
    public static final Type<SnowCoveragePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SeasonsRework.MOD_ID, "snow_coverage"));
    public static final StreamCodec<ByteBuf, SnowCoveragePayload> CODEC = ByteBufCodecs.FLOAT.map(SnowCoveragePayload::new, SnowCoveragePayload::coverage);

    @Override
    public Type<SnowCoveragePayload> type() {
        return TYPE;
    }

    // Optional: a client or server without the mod still connects (the client then paints full coverage).
    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").optional().playToClient(TYPE, CODEC,
                (payload, context) -> context.enqueueWork(() -> ClientSnow.receive(payload.coverage())));
    }
}
