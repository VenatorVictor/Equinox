package dev.venator.seasonsrework.client;

import dev.venator.seasonsrework.SeasonsRework;
import dev.venator.seasonsrework.client.colors.ColorClock;
import dev.venator.seasonsrework.client.colors.SeasonColors;
import dev.venator.seasonsrework.client.dh.DhRefresher;
import dev.venator.seasonsrework.client.textures.SeasonalTextures;
import dev.venator.seasonsrework.climate.Climate;
import dev.venator.seasonsrework.season.SeasonWatcher;
import dev.venator.seasonsrework.snow.ClientSnow;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.neoforge.client.event.RegisterSpriteSourceTypesEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

public final class SeasonsReworkClient {
    private static final SeasonWatcher WATCHER = new SeasonWatcher();
    private static boolean dhLoaded;
    private static int lastStep = -1;

    public static void init(ModContainer container, IEventBus modBus) {
        container.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);
        dhLoaded = ModList.get().isLoaded("distanthorizons");
        // LOWEST so our leaf colors replace vanilla's (and any other mod's).
        modBus.addListener(EventPriority.LOWEST, RegisterColorHandlersEvent.Block.class, SeasonColors::registerLeaves);
        modBus.addListener(EventPriority.LOWEST, RegisterColorHandlersEvent.Block.class, SeasonColors::registerPlants);
        modBus.addListener(ModelEvent.ModifyBakingResult.class, SeasonalTextures::onModifyBakingResult);
        modBus.addListener(ModelEvent.ModifyBakingResult.class, DeadCropModel::onModifyBakingResult);
        modBus.addListener(ModelEvent.ModifyBakingResult.class, CropLookModel::onModifyBakingResult);
        modBus.addListener(ModelEvent.ModifyBakingResult.class, SnowyPlantModel::onModifyBakingResult);
        modBus.addListener(RegisterSpriteSourceTypesEvent.class, DeadCropTextures::register);
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> tick());
        NeoForge.EVENT_BUS.addListener(ItemTooltipEvent.class, CropTooltips::onTooltip);
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut.class, event -> dev.venator.seasonsrework.climate.ClimateBlend.clear());
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingIn.class, event -> {
            ColorClock.reset();
            CropLookModel.reset();
            lastStep = -1;
            if (dhLoaded) DhRefresher.clearCache();
        });
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut.class, event -> {
            WATCHER.reset();
            ColorClock.reset();
            ClientSnow.reset();
        });
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.Clone.class, event -> WATCHER.reset()); // dimension change
        SeasonsRework.LOGGER.info("Seasons Rework: seasonal colors on, Distant Horizons {}", dhLoaded ? "found" : "not installed");
    }

    private static void tick() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        // Remote servers: the client follows its own day time. In single player both sides set the same values.
        WATCHER.tick(level);
        Climate.update(level);
        CropLookModel.tick(level);

        boolean changed = ColorClock.tick(level);
        int step = ColorClock.active ? ColorClock.step : -1;
        // Textures change once per color step and can be anywhere (also after joining, when the first chunks
        // were meshed before the season was known); snowy textures follow the daily temperature, so with those
        // every color update redraws everything; colors only need the surface.
        if (SeasonalTextures.any && step != lastStep || SeasonalTextures.anySnow && changed) ChunkRefresher.refreshAll();
        else if (changed) ChunkRefresher.refreshVisible();
        lastStep = step;
        if (ClientSnow.takeRefresh()) snowChanged();
        if (!changed) return;
        // Color updates are also when LOD snow appears or melts.
        if (dhLoaded && (ClientConfig.DH_REFRESH.get() || ClientConfig.DH_SNOW.get())) DhRefresher.refresh(level);
    }

    /** The snowpack changed (snowfall, thaw): repaint LOD snow and snowy textures. */
    public static void snowChanged() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        if (SeasonalTextures.anySnow) ChunkRefresher.refreshAll();
        if (dhLoaded && ClientConfig.DH_SNOW.get()) DhRefresher.refresh(level);
    }

    private SeasonsReworkClient() {}
}
