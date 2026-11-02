package dev.venatorvictor.equinox.snow;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;

/** The block inside a SnowyBlock. No ticker; sent to clients with the chunk and when it changes. */
public class SnowyBlockEntity extends BlockEntity {
    /** SnowyBlock.place: the inner block for the block entity created by its setBlock. */
    static final ThreadLocal<BlockState> PENDING = new ThreadLocal<>();
    public static final ModelProperty<BlockState> INNER = new ModelProperty<>();

    private BlockState inner = Blocks.AIR.defaultBlockState();

    public SnowyBlockEntity(BlockPos pos, BlockState state) {
        super(SnowBlocks.SNOWY_ENTITY.get(), pos, state);
        BlockState pending = PENDING.get();
        if (pending != null) {
            inner = pending;
            PENDING.remove();
        }
    }

    public BlockState inner() {
        return inner;
    }

    void setInner(BlockState state) {
        inner = state;
        setChanged();
        if (level != null && !level.isClientSide) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("inner", NbtUtils.writeBlockState(inner));
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        inner = NbtUtils.readBlockState(registries.lookupOrThrow(Registries.BLOCK), tag.getCompound("inner"));
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onDataPacket(Connection connection, ClientboundBlockEntityDataPacket packet, HolderLookup.Provider registries) {
        super.onDataPacket(connection, packet, registries);
        redraw();
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        super.handleUpdateTag(tag, registries);
        redraw();
    }

    // Client: the inner block arrived after the block itself, so the model is redone.
    private void redraw() {
        requestModelDataUpdate();
        if (level != null && level.isClientSide) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_IMMEDIATE);
    }

    @Override
    public ModelData getModelData() {
        return ModelData.builder().with(INNER, inner).build();
    }
}
