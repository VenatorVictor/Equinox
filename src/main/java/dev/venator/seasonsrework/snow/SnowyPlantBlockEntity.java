package dev.venator.seasonsrework.snow;

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

/** The plant in a generic snowy plant block (plants without their own snowy block). Doesn't tick. */
public final class SnowyPlantBlockEntity extends BlockEntity {
    public static final ModelProperty<BlockState> PLANT = new ModelProperty<>();

    private @Nullable BlockState plant;

    public SnowyPlantBlockEntity(BlockPos pos, BlockState state) {
        super(SnowyPlants.GENERIC_ENTITY.get(), pos, state);
    }

    public @Nullable BlockState plant() {
        return plant;
    }

    void setPlant(BlockState plant) {
        this.plant = plant;
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (plant != null) tag.put("plant", NbtUtils.writeBlockState(plant));
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        plant = tag.contains("plant") ? NbtUtils.readBlockState(registries.lookupOrThrow(Registries.BLOCK), tag.getCompound("plant")) : null;
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
        return plant == null ? ModelData.EMPTY : ModelData.builder().with(PLANT, plant).build();
    }
}
