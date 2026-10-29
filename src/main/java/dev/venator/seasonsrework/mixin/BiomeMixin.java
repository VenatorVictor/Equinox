package dev.venator.seasonsrework.mixin;

import dev.venator.seasonsrework.climate.Climate;
import dev.venator.seasonsrework.climate.ClimateBiome;
import dev.venator.seasonsrework.climate.ColorProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The two hooks of the climate. Vanilla decides rain or snow, freezing, snow layers, rain rendering and the rest
 * from these, so all of it follows the season.
 */
@Mixin(Biome.class)
public abstract class BiomeMixin implements ClimateBiome {
    @Unique
    private byte seasonsrework$climate = NONE;
    @Unique
    private float seasonsrework$shift;
    @Unique
    private ColorProfile seasonsrework$colorProfile = ColorProfile.STABLE;

    @Shadow
    protected abstract float getTemperature(BlockPos pos);

    @Override
    public float seasonsrework$temperature(BlockPos pos) {
        return getTemperature(pos);
    }

    @Override
    public byte seasonsrework$climate() {
        return seasonsrework$climate;
    }

    @Override
    public float seasonsrework$shift() {
        return seasonsrework$shift;
    }

    @Override
    public ColorProfile seasonsrework$colorProfile() {
        return seasonsrework$colorProfile;
    }

    @Override
    public void seasonsrework$setColorProfile(ColorProfile profile) {
        seasonsrework$colorProfile = profile;
    }

    @Override
    public void seasonsrework$setClimate(byte climate, float shift) {
        seasonsrework$climate = climate;
        seasonsrework$shift = shift;
    }

    // At RETURN, so vanilla's per-position cache keeps the plain value and the offset is always the current one.
    @Inject(method = "getTemperature", at = @At("RETURN"), cancellable = true)
    private void seasonsrework$seasonalTemperature(BlockPos pos, CallbackInfoReturnable<Float> cir) {
        float shift = seasonsrework$climate == SEASONAL ? seasonsrework$shift + Climate.offset() : seasonsrework$shift;
        if (shift != 0) cir.setReturnValue(cir.getReturnValueF() + shift);
    }

    @Inject(method = "hasPrecipitation", at = @At("HEAD"), cancellable = true)
    private void seasonsrework$wetAndDry(CallbackInfoReturnable<Boolean> cir) {
        if (seasonsrework$climate == TROPICAL) {
            if (Climate.wet()) cir.setReturnValue(true);
            else if (Climate.dry()) cir.setReturnValue(false);
        }
    }
}
