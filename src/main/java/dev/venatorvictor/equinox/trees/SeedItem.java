package dev.venatorvictor.equinox.trees;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

import java.util.Map;

/**
 * A tree seed (acorn...): planting it places the tree's sapling. Its own name, and the sapling block keeps its own
 * item (pick block, drops, Block.asItem() still give the sapling).
 */
public class SeedItem extends BlockItem {
    public SeedItem(Block sapling, Item.Properties properties) {
        super(sapling, properties);
    }

    @Override
    public String getDescriptionId() {
        return getOrCreateDescriptionId();
    }

    @Override
    public void registerBlocks(Map<Block, Item> blockToItemMap, Item item) {
        // Not the sapling's item.
    }
}
