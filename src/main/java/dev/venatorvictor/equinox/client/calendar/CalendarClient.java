package dev.venatorvictor.equinox.client.calendar;

import dev.venatorvictor.equinox.Config;
import dev.venatorvictor.equinox.api.SeasonState;
import dev.venatorvictor.equinox.calendar.Calendar;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/** Client side of the calendar: the item's season sprite, the hanging calendar's renderer, the line shown while held. */
public final class CalendarClient {
    private static final ResourceLocation SEASON = ResourceLocation.fromNamespaceAndPath("equinox", "season");

    public static void init(IEventBus modBus) {
        modBus.addListener(FMLClientSetupEvent.class, event -> event.enqueueWork(() ->
                ItemProperties.register(Calendar.ITEM.get(), SEASON, (stack, level, entity, seed) -> {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.level == null) return 0;
                    return state(mc).season().ordinal() / 4F;      // 0 spring, .25 summer, .5 autumn, .75 winter
                })));
        modBus.addListener(EntityRenderersEvent.RegisterRenderers.class, event ->
                event.registerBlockEntityRenderer(Calendar.BLOCK_ENTITY.get(), context -> new CalendarRenderer()));
        modBus.addListener(RegisterGuiLayersEvent.class, event ->
                event.registerAbove(VanillaGuiLayers.HOTBAR, ResourceLocation.fromNamespaceAndPath("equinox", "calendar"), CalendarClient::hud));
    }

    private static SeasonState state(Minecraft mc) {
        return new SeasonState(mc.level.getDayTime(), Config.daysPerSeason(), Config.daysPerWeek());
    }

    /** Held in either hand: the calendar icon, month, season and year on top, the day below (top left). */
    private static void hud(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.level == null || mc.options.hideGui) return;
        ItemStack held = player.getMainHandItem().is(Calendar.ITEM.get()) ? player.getMainHandItem()
                : player.getOffhandItem().is(Calendar.ITEM.get()) ? player.getOffhandItem() : null;
        if (held == null) return;
        var d = dev.venatorvictor.equinox.calendar.CalendarDates.of(mc.level);
        long today = d.today();
        String a = I18n.get("equinox.calendar.hud.top", CalendarLook.month(d.month(today)), CalendarLook.season(d.season(today)), d.year(today));
        String b = I18n.get("equinox.calendar.hud.day", d.dayOfMonth(today) + 1);
        int w = 4 + 16 + 4 + Math.max(mc.font.width(a), mc.font.width(b)) + 5;
        int x = 2, y = 2;
        g.fill(x, y, x + w, y + 24, 0x96000000);
        g.renderItem(held, x + 4, y + 4);
        g.drawString(mc.font, a, x + 24, y + 3, 0xFFFFFFFF, true);
        g.drawString(mc.font, b, x + 24, y + 13, 0xFFBEBEBE, true);
    }

    private CalendarClient() {}
}
