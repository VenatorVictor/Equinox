package dev.venatorvictor.equinox.mixin;

import dev.venatorvictor.equinox.climate.Climate;
import dev.venatorvictor.equinox.climate.ClimateBiome;
import dev.venatorvictor.equinox.climate.ClimateBlend;
import dev.venatorvictor.equinox.climate.ColorProfile;
import dev.venatorvictor.equinox.climate.Geothermal;
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
    private boolean equinox$geothermal;
    @Unique
    private byte equinox$snowLock = ClimateBiome.SNOW_FREE;
    @Unique
    private float equinox$seasonStrength = 1F;

    @Override
    public float equinox$seasonStrength() {
        return equinox$seasonStrength;
    }

    @Override
    public void equinox$setSeasonStrength(float strength) {
        equinox$seasonStrength = strength;
    }

    @Shadow
    protected abstract float getTemperature(BlockPos pos);

    @Shadow
    public abstract float getBaseTemperature();

    /** 0 not known yet, 1 plain temperature (no frozen-ocean modifier), 2 modified. */
    @Unique
    private byte equinox$plain;

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

    @Override
    public boolean equinox$geothermal() {
        return equinox$geothermal;
    }

    @Override
    public void equinox$setGeothermal(boolean geothermal) {
        equinox$geothermal = geothermal;
    }

    // At RETURN, so vanilla's per-position cache keeps the plain value and the offset is always the current one.
    @Inject(method = "getTemperature", at = @At("RETURN"), cancellable = true)
    private void equinox$seasonalTemperature(BlockPos pos, CallbackInfoReturnable<Float> cir) {
        if (Climate.WORLDGEN.get()) return; // world generation keeps vanilla's climate
        float shift = equinox$shiftAt(pos);
        float temperature = cir.getReturnValueF() + shift;
        if (equinox$geothermal) temperature = Geothermal.apply(temperature, pos.getX(), pos.getZ());
        if (shift != 0 || equinox$geothermal) cir.setReturnValue(temperature);
    }

    /** The climate's change to the biome's temperature at this column now (season, snow lock, blending). */
    @Unique
    private float equinox$shiftAt(BlockPos pos) {
        float seasonal = equinox$lockShift;
        float[] changes = equinox$changes;
        if (changes != null) seasonal += Climate.change(changes, equinox$zone.factor);
        return equinox$shift + ClimateBlend.adjust((Biome) (Object) this, pos, equinox$blendKind, seasonal);
    }

    /**
     * Rain or snow from the column's snow height (Climate.rainAt) instead of the full temperature: rain drawing asks
     * every column around the player every frame. Geothermal patches and frozen-ocean temperatures take the full way.
     */
    @Inject(method = "warmEnoughToRain", at = @At("HEAD"), cancellable = true)
    private void equinox$snowHeight(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (equinox$geothermal || Climate.WORLDGEN.get()) return;
        if (equinox$plain == 0) equinox$plain = (byte) (((Biome) (Object) this).getModifiedClimateSettings().temperatureModifier()
                == Biome.TemperatureModifier.NONE ? 1 : 2);
        if (equinox$plain != 1) return;
        Boolean rain = Climate.rainAt(getBaseTemperature() + equinox$shiftAt(pos), pos.getY());
        if (rain != null) cir.setReturnValue(rain);
    }

    @Inject(method = "hasPrecipitation", at = @At("HEAD"), cancellable = true)
    private void equinox$wetAndDry(CallbackInfoReturnable<Boolean> cir) {
        if (Climate.WORLDGEN.get()) return;
        Boolean rain = Climate.precipitation(equinox$zone);
        if (rain != null) cir.setReturnValue(rain);
    }
}
