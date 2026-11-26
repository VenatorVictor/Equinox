package dev.venatorvictor.equinox.weather;

import dev.venatorvictor.equinox.Equinox;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Server to client: the storm seed and the /weather override, so the client works out the same storms. */
public record WeatherPayload(long seed, int override, long from, long until, java.util.List<Weather.Forced> forced) implements CustomPacketPayload {
    private static final StreamCodec<ByteBuf, Weather.Forced> FORCED = StreamCodec.composite(
            ByteBufCodecs.DOUBLE, Weather.Forced::x, ByteBufCodecs.DOUBLE, Weather.Forced::z,
            ByteBufCodecs.VAR_LONG, Weather.Forced::from, ByteBufCodecs.VAR_LONG, Weather.Forced::until, Weather.Forced::new);
    public static final Type<WeatherPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Equinox.MOD_ID, "weather"));
    public static final StreamCodec<ByteBuf, WeatherPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, WeatherPayload::seed,
            ByteBufCodecs.VAR_INT, WeatherPayload::override,
            ByteBufCodecs.VAR_LONG, WeatherPayload::from,
            ByteBufCodecs.VAR_LONG, WeatherPayload::until,
            FORCED.apply(ByteBufCodecs.list(64)), WeatherPayload::forced,
            WeatherPayload::new);

    @Override
    public Type<WeatherPayload> type() {
        return TYPE;
    }

    /** Tiles of the climate grid: keys, then CELLS * 2 bytes per tile. */
    public record Grid(long[] keys, byte[] data) implements CustomPacketPayload {
        public static final Type<Grid> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Equinox.MOD_ID, "climate_grid"));
        public static final StreamCodec<ByteBuf, Grid> CODEC = StreamCodec.of(
                (buf, grid) -> {
                    buf.writeInt(grid.keys.length);
                    for (long key : grid.keys) buf.writeLong(key);
                    buf.writeBytes(grid.data);
                },
                buf -> {
                    int count = Math.min(buf.readInt(), 4096);
                    long[] keys = new long[count];
                    for (int i = 0; i < count; i++) keys[i] = buf.readLong();
                    byte[] data = new byte[count * ClimateGrid.CELLS * 2];
                    buf.readBytes(data);
                    return new Grid(keys, data);
                });

        @Override
        public Type<Grid> type() {
            return TYPE;
        }
    }

    // Optional: a client without the mod still connects (it gets vanilla's clear sky).
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("2").optional();
        registrar.playToClient(TYPE, CODEC, (payload, context) -> context.enqueueWork(() -> receive(payload)));
        registrar.playToClient(Grid.TYPE, Grid.CODEC, (payload, context) -> context.enqueueWork(() -> receive(payload)));
    }

    private static void receive(WeatherPayload payload) {
        Weather.Side side = Weather.client();
        if (side == null || side.seed != payload.seed) Weather.setClient(side = new Weather.Side(payload.seed, new ClimateGrid(null)));
        Weather.Override[] kinds = Weather.Override.values();
        side.setOverride(payload.override >= 0 && payload.override < kinds.length ? kinds[payload.override] : Weather.Override.NONE,
                payload.from, payload.until);
        side.setForced(payload.forced);
    }

    private static void receive(Grid payload) {
        Weather.Side side = Weather.client();
        if (side == null) return;
        int size = ClimateGrid.CELLS * 2;
        for (int i = 0; i < payload.keys.length; i++)
            side.grid.put(payload.keys[i], java.util.Arrays.copyOfRange(payload.data, i * size, (i + 1) * size));
        side.forget();
    }
}
