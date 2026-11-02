package dev.venatorvictor.equinox.compat.blockinfo;

import dev.venatorvictor.equinox.crops.Crops;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

/**
 * Living crops: how the temperature treats them now (worked out on the client from the same °C as the server, like
 * the crop looks), next to the growth %. No °C shown.
 */
final class CropStateProvider implements IBlockComponentProvider {
    static final CropStateProvider INSTANCE = new CropStateProvider();
    private static final ResourceLocation UID = ResourceLocation.fromNamespaceAndPath("equinox", "crop_state");

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        if (Crops.climateOf(accessor.getBlock()) == null) return;
        Level level = accessor.getLevel();
        Crops.Effect effect = Crops.effect(level, accessor.getPosition(), accessor.getBlockState());
        int look = Crops.look(level, accessor.getPosition(), accessor.getBlockState());
        boolean mortal = Crops.mortal(accessor.getBlock());
        Component line = switch (effect) {
            case VANILLA -> null;
            case FASTER -> Component.translatable("jade.equinox.growing_well").withStyle(ChatFormatting.GREEN);
            case SLOW -> Component.translatable(look < 0 ? "jade.equinox.slow_cold" : "jade.equinox.slow_heat").withStyle(ChatFormatting.YELLOW);
            case DORMANT -> stopped("jade.equinox.dormant", look, ChatFormatting.AQUA);
            case STOP -> stopped("jade.equinox.stopped", look, ChatFormatting.GOLD);
            case FROST -> mortal ? Component.translatable("jade.equinox.will_freeze").withStyle(ChatFormatting.RED)
                    : stopped("jade.equinox.dormant", look, ChatFormatting.AQUA);
            case HEAT -> mortal ? Component.translatable("jade.equinox.will_scorch").withStyle(ChatFormatting.RED)
                    : stopped("jade.equinox.stopped", look, ChatFormatting.GOLD);
        };
        if (line != null) tooltip.add(line);
    }

    /** "Dormant: too cold (3/4)": how far past the limit, 4 = at the killing one. */
    private static Component stopped(String key, int look, ChatFormatting color) {
        return Component.translatable(key, Math.min(4, Math.abs(look))).withStyle(color);
    }

    @Override
    public ResourceLocation getUid() {
        return UID;
    }
}
