package dev.venator.seasonsrework;

import com.mojang.logging.LogUtils;
import dev.venator.seasonsrework.client.SeasonsReworkClient;
import dev.venator.seasonsrework.climate.Climate;
import dev.venator.seasonsrework.crops.Crops;
import dev.venator.seasonsrework.daylength.DayLength;
import dev.venator.seasonsrework.season.SeasonClock;
import dev.venator.seasonsrework.season.SeasonCommand;
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
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.slf4j.Logger;

@Mod(SeasonsRework.MOD_ID)
public final class SeasonsRework {
    public static final String MOD_ID = "seasonsrework";
    public static final Logger LOGGER = LogUtils.getLogger();

    public SeasonsRework(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, Config.SPEC);
        modBus.addListener(ModConfigEvent.Loading.class, event -> Config.refresh());
        modBus.addListener(ModConfigEvent.Reloading.class, event -> Config.refresh());

        NeoForge.EVENT_BUS.addListener(LevelTickEvent.Post.class, SeasonClock::onLevelTick);
        NeoForge.EVENT_BUS.addListener(LevelEvent.Load.class, SeasonClock::onLevelLoad);
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent.class, SeasonClock::onServerStopped);
        NeoForge.EVENT_BUS.addListener(TagsUpdatedEvent.class, Climate::categorize);
        NeoForge.EVENT_BUS.addListener(TagsUpdatedEvent.class, Crops::onTagsUpdated);
        NeoForge.EVENT_BUS.addListener(CropGrowEvent.Pre.class, Crops::onCropGrow);
        NeoForge.EVENT_BUS.addListener(BlockGrowFeatureEvent.class, Crops::onTreeGrow);
        NeoForge.EVENT_BUS.addListener(BonemealEvent.class, Crops::onBonemeal);
        NeoForge.EVENT_BUS.addListener(SleepFinishedTimeEvent.class, DayLength::onSleepFinished);
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent.class, SeasonCommand::register);

        if (FMLEnvironment.dist == Dist.CLIENT) SeasonsReworkClient.init(modBus);
    }
}
