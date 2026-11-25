package dev.venatorvictor.equinox.client.calendar;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.venatorvictor.equinox.Config;
import dev.venatorvictor.equinox.api.SeasonState;
import dev.venatorvictor.equinox.calendar.CalendarBlock;
import dev.venatorvictor.equinox.calendar.CalendarBlockEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

/**
 * The hanging calendar's live parts on its page (calendar-block-3d preview): the season strip (2 pixels, page rows 3-4)
 * and 14 day dots (5, 5, 4) standing for the month: past red, today in the season's colour, later grey.
 */
public final class CalendarRenderer implements BlockEntityRenderer<CalendarBlockEntity> {
    private static final ResourceLocation WHITE = ResourceLocation.fromNamespaceAndPath("equinox", "textures/block/calendar_mark.png");
    private static final int[][] DOTS = {{3, 6}, {5, 6}, {7, 6}, {9, 6}, {11, 6}, {3, 8}, {5, 8}, {7, 8}, {9, 8}, {11, 8},
            {3, 10}, {5, 10}, {7, 10}, {9, 10}};
    private static final float Z = 14 / 16F - 0.002F;          // just in front of the page

    @Override
    public void render(CalendarBlockEntity calendar, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        if (calendar.getLevel() == null) return;
        SeasonState s = new SeasonState(calendar.getLevel().getDayTime(), Config.daysPerSeason(), Config.daysPerWeek());
        int season = s.season().ordinal();
        pose.pushPose();
        float yRot = switch (calendar.getBlockState().getValue(CalendarBlock.FACING)) {
            case EAST -> 90;
            case SOUTH -> 180;
            case WEST -> 270;
            default -> 0;
        };
        pose.translate(0.5, 0, 0.5);
        pose.mulPose(Axis.YP.rotationDegrees(-yRot));
        pose.translate(-0.5, 0, -0.5);
        VertexConsumer vc = buffers.getBuffer(RenderType.entityCutoutNoCull(WHITE));
        PoseStack.Pose p = pose.last();
        int band = CalendarLook.BAND[season];
        quad(vc, p, 2, 3, 12, 2, band, light);                      // season strip: x 2-13, rows 3-4
        int today = s.dayOfMonth() * DOTS.length / Math.max(1, s.monthDays());
        for (int i = 0; i < DOTS.length; i++) {
            int col = i < today ? CalendarLook.DOT_PAST : i == today ? band : CalendarLook.DOT_LATER;
            quad(vc, p, DOTS[i][0], DOTS[i][1], 1, 1, col, light);
        }
        pose.popPose();
    }

    /** A w x h patch of the page at texture pixel (x, row) (rows counted from the top), facing north. Seen from the
     *  north, the model's x runs right to left, so the texture's x is turned round. */
    private static void quad(VertexConsumer vc, PoseStack.Pose p, int x, int row, int w, int h, int rgb, int light) {
        float x0 = (16 - x - w) / 16F, x1 = (16 - x) / 16F, y1 = (16 - row) / 16F, y0 = (16 - row - h) / 16F;
        int r = rgb >> 16 & 255, g = rgb >> 8 & 255, b = rgb & 255;
        vertex(vc, p, x0, y0, r, g, b, light);
        vertex(vc, p, x0, y1, r, g, b, light);
        vertex(vc, p, x1, y1, r, g, b, light);
        vertex(vc, p, x1, y0, r, g, b, light);
    }

    private static void vertex(VertexConsumer vc, PoseStack.Pose p, float x, float y, int r, int g, int b, int light) {
        vc.addVertex(p, x, y, Z).setColor(r, g, b, 255).setUv(0.5F, 0.5F).setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light).setNormal(p, 0, 0, -1);
    }
}
