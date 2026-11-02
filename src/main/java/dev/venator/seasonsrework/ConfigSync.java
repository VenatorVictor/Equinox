package dev.venator.seasonsrework;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Server to client: the seasons config (Config). It's a global file, so NeoForge doesn't send it; players get the
 * server's values on joining and whenever the server's file changes, so both sides count the year the same way.
 */
public record ConfigSync(CompoundTag values) implements CustomPacketPayload {
    public static final Type<ConfigSync> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SeasonsRework.MOD_ID, "config"));
    public static final StreamCodec<FriendlyByteBuf, ConfigSync> CODEC = ByteBufCodecs.COMPOUND_TAG.map(ConfigSync::new, ConfigSync::values)
            .cast();

    @Override
    public Type<ConfigSync> type() {
        return TYPE;
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").optional().playToClient(TYPE, CODEC, (payload, context) -> {
            // The player hosting the world shares the server's values already.
            if (context.connection().isMemoryConnection()) return;
            context.enqueueWork(() -> Config.receive(payload.values()));
        });
    }

    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && player.connection.hasChannel(TYPE))
            PacketDistributor.sendToPlayer(player, new ConfigSync(Config.syncTag()));
    }

    /** The server's file changed: send the new values to everyone. */
    public static void sendToAll() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) server.execute(() -> PacketDistributor.sendToAllPlayers(new ConfigSync(Config.syncTag())));
    }
}
