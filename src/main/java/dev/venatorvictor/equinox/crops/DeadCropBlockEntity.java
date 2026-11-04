package dev.venatorvictor.equinox.crops;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;
import org.jetbrains.annotations.Nullable;

/** Keeps the crop a dead crop was (block and stage), for its look. Doesn't tick. */
public final class DeadCropBlockEntity extends BlockEntity {
    public static final ModelProperty<BlockState> ORIGINAL = new ModelProperty<>();

    private @Nullable BlockState original;
    /** A wild crop (crops_that_die PLANTED): it comes back as it was when the cold or heat is over. */
    private boolean comesBack;

    public DeadCropBlockEntity(BlockPos pos, BlockState state) {
        super(CropBlocks.DEAD_CROP_ENTITY.get(), pos, state);
    }

    public @Nullable BlockState original() {
        return original;
    }

    public boolean comesBack() {
        return comesBack;
    }

    void setOriginal(BlockState original, boolean comesBack) {
        this.original = original;
        this.comesBack = comesBack;
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (original != null) tag.put("crop", NbtUtils.writeBlockState(original));
        if (comesBack) tag.putBoolean("comes_back", true);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        original = tag.contains("crop") ? NbtUtils.readBlockState(registries.lookupOrThrow(Registries.BLOCK), tag.getCompound("crop")) : null;
        comesBack = tag.getBoolean("comes_back");
        if (level != null && level.isClientSide) {
            requestModelDataUpdate();
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_IMMEDIATE);
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    public @Nullable Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public ModelData getModelData() {
        return original == null ? ModelData.EMPTY : ModelData.builder().with(ORIGINAL, original).build();
    }
}
