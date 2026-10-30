package dev.venator.seasonsrework.compat.jade;

import dev.venator.seasonsrework.client.DeadCropModel;
import dev.venator.seasonsrework.crops.DeadCropBlock;
import dev.venator.seasonsrework.crops.DeadCropBlockEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec2;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;
import snownee.jade.api.ui.Element;
import snownee.jade.api.ui.IElement;

/**
 * Dead crops and spoiled fruit: the original crop's look in its dead colours as the icon (the same sprite the block
 * shows), and the cause of death.
 */
final class DeadCropProvider implements IBlockComponentProvider {
    static final DeadCropProvider INSTANCE = new DeadCropProvider();
    private static final ResourceLocation UID = ResourceLocation.fromNamespaceAndPath("seasonsrework", "dead_crop");

    @Override
    public IElement getIcon(BlockAccessor accessor, IPluginConfig config, IElement currentIcon) {
        if (!(accessor.getBlockEntity() instanceof DeadCropBlockEntity entity)) return currentIcon;
        BlockState original = entity.original();
        if (original == null) return currentIcon;
        TextureAtlasSprite sprite = DeadCropModel.deadIcon(original, accessor.getBlockState());
        return sprite == null ? currentIcon : new SpriteElement(sprite);
    }

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        boolean heat = accessor.getBlockState().getValue(DeadCropBlock.CAUSE) == DeadCropBlock.Cause.HEAT;
        tooltip.add(Component.translatable(heat ? "jade.seasonsrework.killed_heat" : "jade.seasonsrework.killed_frost")
                .withStyle(heat ? ChatFormatting.GOLD : ChatFormatting.AQUA));
        if (accessor.getBlockEntity() instanceof DeadCropBlockEntity entity && entity.original() != null)
            tooltip.add(Component.translatable("jade.seasonsrework.was", entity.original().getBlock().getName()).withStyle(ChatFormatting.GRAY));
    }

    @Override
    public ResourceLocation getUid() {
        return UID;
    }

    /** A block atlas sprite drawn 16x16, like an item icon. */
    private static final class SpriteElement extends Element {
        private final TextureAtlasSprite sprite;

        SpriteElement(TextureAtlasSprite sprite) {
            this.sprite = sprite;
        }

        @Override
        public Vec2 getSize() {
            return new Vec2(18, 18);
        }

        @Override
        public void render(GuiGraphics graphics, float x, float y, float maxX, float maxY) {
            graphics.pose().pushPose();
            graphics.pose().translate(x + 1, y + 1, 0);
            graphics.blit(0, 0, 0, 16, 16, sprite);
            graphics.pose().popPose();
        }

        @Override
        public String getMessage() {
            return sprite.contents().name().toString();
        }
    }
}
