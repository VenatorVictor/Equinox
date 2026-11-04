package dev.venatorvictor.equinox.trees;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.loot.IGlobalLootModifier;
import net.neoforged.neoforge.common.loot.LootModifier;

/**
 * A bearing leaf block with ripe fruit on it (not picked this year) also drops the fruit when it breaks: broken by
 * hand or tool, decaying after the tree was cut, blown up. Same amount as a pick (TreeFruit). Creative breaking drops
 * nothing, as usual. Listed in data/neoforge/loot_modifiers/global_loot_modifiers.json.
 */
public class RipeFruitDrops extends LootModifier {
    public static final MapCodec<RipeFruitDrops> CODEC = RecordCodecBuilder.mapCodec(inst -> codecStart(inst).apply(inst, RipeFruitDrops::new));

    public RipeFruitDrops(LootItemCondition[] conditions) {
        super(conditions);
    }

    @Override
    protected ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> loot, LootContext context) {
        BlockState state = context.getParamOrNull(LootContextParams.BLOCK_STATE);
        Vec3 origin = context.getParamOrNull(LootContextParams.ORIGIN);
        if (state == null || origin == null) return loot;
        ItemStack fruit = TreeFruit.ripeDrop(context.getLevel(), BlockPos.containing(origin), state, context.getRandom());
        if (!fruit.isEmpty()) loot.add(fruit);
        return loot;
    }

    @Override
    public MapCodec<? extends IGlobalLootModifier> codec() {
        return CODEC;
    }
}
