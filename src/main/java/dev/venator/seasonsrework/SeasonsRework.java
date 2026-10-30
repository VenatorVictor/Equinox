package dev.venator.seasonsrework;

import com.mojang.logging.LogUtils;
import dev.venator.seasonsrework.client.SeasonsReworkClient;
import dev.venator.seasonsrework.climate.BiomeCelsius;
import dev.venator.seasonsrework.climate.Climate;
import dev.venator.seasonsrework.crops.CropBlocks;
import dev.venator.seasonsrework.crops.CropClimate;
import dev.venator.seasonsrework.crops.CropWarmth;
import dev.venator.seasonsrework.crops.Crops;
import dev.venator.seasonsrework.daylength.DayLength;
import dev.venator.seasonsrework.season.SeasonClock;
import dev.venator.seasonsrework.season.SeasonCommand;
import dev.venator.seasonsrework.snow.FirstSnow;
import dev.venator.seasonsrework.snow.SnowConfig;
import dev.venator.seasonsrework.snow.SnowCover;
import dev.venator.seasonsrework.snow.SnowCoveragePayload;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.AddPackFindersEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.datamaps.DataMapsUpdatedEvent;
import net.neoforged.neoforge.registries.datamaps.RegisterDataMapTypesEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.entity.player.BonemealEvent;
import net.neoforged.neoforge.event.level.BlockGrowFeatureEvent;
import net.neoforged.neoforge.event.level.block.CropGrowEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.level.SleepFinishedTimeEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.slf4j.Logger;

@Mod(SeasonsRework.MOD_ID)
public final class SeasonsRework {
    public static final String MOD_ID = "seasonsrework";
    public static final Logger LOGGER = LogUtils.getLogger();

    public SeasonsRework(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, Config.SPEC);
        // The mod has three configs; only the server one feeds Config's copies.
        modBus.addListener(ModConfigEvent.Loading.class, event -> { if (event.getConfig().getSpec() == Config.SPEC) Config.refresh(); });
        modBus.addListener(ModConfigEvent.Reloading.class, event -> { if (event.getConfig().getSpec() == Config.SPEC) Config.refresh(); });
        container.registerConfig(ModConfig.Type.COMMON, SnowConfig.SPEC);

        NeoForge.EVENT_BUS.addListener(LevelTickEvent.Post.class, SeasonClock::onLevelTick);
        NeoForge.EVENT_BUS.addListener(LevelTickEvent.Post.class, dev.venator.seasonsrework.crops.DeadCropBlock::onLevelTick);
        NeoForge.EVENT_BUS.addListener(LevelEvent.Load.class, SeasonClock::onLevelLoad);
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent.class, SeasonClock::onServerStopped);
        NeoForge.EVENT_BUS.addListener(ServerAboutToStartEvent.class, Config::onServerAboutToStart);
        NeoForge.EVENT_BUS.addListener(TagsUpdatedEvent.class, Climate::categorize);
        NeoForge.EVENT_BUS.addListener(TagsUpdatedEvent.class, Crops::onTagsUpdated);
        NeoForge.EVENT_BUS.addListener(CropGrowEvent.Pre.class, Crops::onCropGrow);
        NeoForge.EVENT_BUS.addListener(BlockGrowFeatureEvent.class, Crops::onTreeGrow);
        NeoForge.EVENT_BUS.addListener(BonemealEvent.class, Crops::onBonemeal);
        NeoForge.EVENT_BUS.addListener(SleepFinishedTimeEvent.class, DayLength::onSleepFinished);
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent.class, SeasonCommand::register);

        // Snow cover and first snow (sent to clients for LOD snow and snowy textures).
        modBus.addListener(RegisterPayloadHandlersEvent.class, SnowCoveragePayload::register);
        FirstSnow.init();
        SnowCover.init(modBus);
        modBus.addListener(AddPackFindersEvent.class, SeasonsRework::addPacks);
        modBus.addListener(RegisterDataMapTypesEvent.class, event -> {
            event.register(BiomeCelsius.TYPE);
            event.register(CropClimate.TYPE);
            event.register(CropWarmth.TYPE);
        });
        CropBlocks.register(modBus);
        NeoForge.EVENT_BUS.addListener(DataMapsUpdatedEvent.class, Crops::onDataMapsUpdated);
        NeoForge.EVENT_BUS.addListener(ChunkEvent.Unload.class, Crops::onChunkUnload);

        if (FMLEnvironment.dist == Dist.CLIENT) SeasonsReworkClient.init(container, modBus);
    }

    /** Built-in data packs putting more plants in Snow! Real Magic's plants tag, so snow covers them too. */
    private static void addPacks(AddPackFindersEvent event) {
        if (!ModList.get().isLoaded("snowrealmagic")) return;
        if (SnowConfig.SNOWY_LEAF_LITTER.get())
            event.addPackFinders(ResourceLocation.fromNamespaceAndPath(MOD_ID, "packs/snowy_leaf_litter"), PackType.SERVER_DATA,
                    Component.literal("Seasons Rework: snowy leaf litter"), PackSource.BUILT_IN, true, Pack.Position.TOP);
        if (ModList.get().isLoaded("farmersdelight"))
            event.addPackFinders(ResourceLocation.fromNamespaceAndPath(MOD_ID, "packs/srm_farmers_delight"), PackType.SERVER_DATA,
                    Component.literal("Seasons Rework: snowy Farmer's Delight wild crops"), PackSource.BUILT_IN, true, Pack.Position.TOP);
    }
}
