package dev.venator.seasonsrework.client;

import dev.venator.seasonsrework.climate.Climate;
import dev.venator.seasonsrework.season.SeasonWatcher;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

public final class SeasonsReworkClient {
    private static final SeasonWatcher WATCHER = new SeasonWatcher();

    public static void init(IEventBus modBus) {
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> {
            ClientLevel level = Minecraft.getInstance().level;
            // Remote servers: the client follows its own day time. In single player both sides set the same values.
            if (level != null) {
                WATCHER.tick(level);
                Climate.update(level);
            }
        });
        NeoForge.EVENT_BUS.addListener(ItemTooltipEvent.class, CropTooltips::onTooltip);
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut.class, event -> WATCHER.reset());
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.Clone.class, event -> WATCHER.reset()); // dimension change
    }

    private SeasonsReworkClient() {}
}
