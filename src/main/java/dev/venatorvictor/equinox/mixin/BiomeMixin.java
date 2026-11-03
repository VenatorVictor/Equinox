package dev.venatorvictor.equinox.mixin;

import dev.venatorvictor.equinox.climate.Climate;
import dev.venatorvictor.equinox.climate.ClimateBiome;
import dev.venatorvictor.equinox.climate.ClimateBlend;
import dev.venatorvictor.equinox.climate.ColorProfile;
import dev.venatorvictor.equinox.climate.Zone;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The two hooks of the climate (temperature: the biome's, from its °C climate in seasonal zones, its season, the snow locks and the climate blending). Vanilla decides rain or snow, freezing, snow layers, rain rendering and the rest
 * from these, so all of it follows the season.
 */
@Mixin(Biome.class)
public abstract class BiomeMixin implements ClimateBiome {
    @Unique
    private Zone equinox$zone = Zone.NONE;
    @Unique
    private float equinox$shift;
    @Unique
    private float equinox$lockShift;
    @Unique
    private float[] equinox$changes;
    @Unique
    private ColorProfile equinox$colorProfile = ColorProfile.STABLE;
    @Unique
    private byte equinox$blendKind = ClimateBiome.BLEND_NONE;
    @Unique
    private float[] equinox$celsiusChanges;
    @Unique
    private boolean equinox$clientSide;
    @Unique
    private byte equinox$snowLock = ClimateBiome.SNOW_FREE;

    @Shadow
    protected abstract float getTemperature(BlockPos pos);

    @Override
    public float equinox$temperature(BlockPos pos) {
        return getTemperature(pos);
    }

    @Override
    public byte equinox$climate() {
        return equinox$zone.kind;
    }

    @Override
    public Zone equinox$zone() {
        return equinox$zone;
    }

    @Override
    public float[] equinox$changes() {
        return equinox$changes;
    }

    @Override
    public float equinox$shift() {
        return equinox$shift;
    }

    @Override
    public float equinox$lockShift() {
        return equinox$lockShift;
    }

    @Override
    public ColorProfile equinox$colorProfile() {
        return equinox$colorProfile;
    }

    @Override
    public void equinox$setColorProfile(ColorProfile profile) {
        equinox$colorProfile = profile;
    }

    @Override
    public void equinox$setClimate(Zone zone, float shift, float lockShift, float[] changes) {
        equinox$zone = zone;
        equinox$shift = shift;
        equinox$lockShift = lockShift;
        equinox$changes = changes;
    }

    @Override
    public float[] equinox$celsiusChanges() {
        return equinox$celsiusChanges;
    }

    @Override
    public void equinox$setCelsiusChanges(float[] changes) {
        equinox$celsiusChanges = changes;
    }

    @Override
    public byte equinox$blendKind() {
        return equinox$blendKind;
    }

    @Override
    public void equinox$setBlendKind(byte kind) {
        equinox$blendKind = kind;
    }

    @Override
    public byte equinox$snowLock() {
        return equinox$snowLock;
    }

    @Override
    public void equinox$setSnowLock(byte lock) {
        equinox$snowLock = lock;
    }

    @Override
    public boolean equinox$clientSide() {
        return equinox$clientSide;
    }

    @Override
    public void equinox$setClientSide(boolean client) {
        equinox$clientSide = client;
    }

    // At RETURN, so vanilla's per-position cache keeps the plain value and the offset is always the current one.
    @Inject(method = "getTemperature", at = @At("RETURN"), cancellable = true)
    private void equinox$seasonalTemperature(BlockPos pos, CallbackInfoReturnable<Float> cir) {
        if (Climate.WORLDGEN.get()) return; // world generation keeps vanilla's climate
        float[] changes = equinox$changes;
        float seasonal = equinox$lockShift;
        if (changes != null) seasonal += Climate.change(changes, equinox$zone.factor);
        float shift = equinox$shift + ClimateBlend.adjust((Biome) (Object) this, pos, equinox$blendKind, seasonal);
        if (shift != 0) cir.setReturnValue(cir.getReturnValueF() + shift);
    }

    @Inject(method = "hasPrecipitation", at = @At("HEAD"), cancellable = true)
    private void equinox$wetAndDry(CallbackInfoReturnable<Boolean> cir) {
        if (Climate.WORLDGEN.get()) return;
        Boolean rain = Climate.precipitation(equinox$zone);
        if (rain != null) cir.setReturnValue(rain);
    }
}
