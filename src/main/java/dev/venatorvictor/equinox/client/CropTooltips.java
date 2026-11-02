package dev.venatorvictor.equinox.client;

import dev.venatorvictor.equinox.Config;
import dev.venatorvictor.equinox.api.Season;
import dev.venatorvictor.equinox.crops.Crops;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/** "Grows in: Spring, Autumn" on seeds and crops. */
public final class CropTooltips {
    private static final ChatFormatting[] COLORS = {ChatFormatting.GREEN, ChatFormatting.YELLOW, ChatFormatting.GOLD, ChatFormatting.AQUA};

    public static void onTooltip(ItemTooltipEvent event) {
        int seasons = Crops.seasonsOf(event.getItemStack().getItem());
        if (seasons == 0 || !Config.seasonalCrops()) return;
        MutableComponent line = Component.translatable("equinox.tooltip.grows_in").withStyle(ChatFormatting.GRAY);
        if (seasons == (1 << Season.COUNT) - 1) {
            line.append(Component.translatable("equinox.tooltip.all_year").withStyle(ChatFormatting.LIGHT_PURPLE));
        } else {
            boolean first = true;
            for (Season season : Season.VALUES) {
                if ((seasons & 1 << season.ordinal()) == 0) continue;
                if (!first) line.append(Component.literal(", ").withStyle(ChatFormatting.GRAY));
                line.append(Component.translatable("equinox.season." + season.id()).withStyle(COLORS[season.ordinal()]));
                first = false;
            }
        }
        event.getToolTip().add(line);
    }

    private CropTooltips() {}
}
