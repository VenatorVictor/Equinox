package dev.venatorvictor.equinox.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockElementFace;
import net.minecraft.client.renderer.block.model.BlockFaceUV;
import net.minecraft.client.renderer.block.model.FaceBakery;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BlockModelRotation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.joml.Vector3f;

/**
 * The snow on a stair or slab (SnowMap, SnowMapModel): one layer on every step, made from the block's own shape raised
 * by a layer minus the block itself, with the snow layer's texture. Built once per block state, cleared on reload.
 */
final class SnowTopQuads {
    /** A face of the snow and the block side it lies on (null: inside the block), to hide it against a full block. */
    record Face(BakedQuad quad, @Nullable Direction boundary) {}

    private static final Map<BlockState, List<Face>> CACHE = new ConcurrentHashMap<>();
    private static final double LAYER = 1 / 8.0;

    static List<Face> of(BlockState state) {
        return CACHE.computeIfAbsent(state, SnowTopQuads::build);
    }

    static void clear() {
        CACHE.clear();
    }

    private static List<Face> build(BlockState state) {
        VoxelShape own = state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        VoxelShape snow = Shapes.join(own.move(0, LAYER, 0), own, BooleanOp.ONLY_FIRST);
        TextureAtlasSprite sprite = Minecraft.getInstance().getBlockRenderer().getBlockModel(Blocks.SNOW.defaultBlockState())
                .getParticleIcon(ModelData.EMPTY);
        FaceBakery bakery = new FaceBakery();
        List<Face> faces = new ArrayList<>();
        for (AABB box : snow.toAabbs()) {
            Vector3f from = new Vector3f((float) box.minX * 16, (float) box.minY * 16, (float) box.minZ * 16);
            Vector3f to = new Vector3f((float) box.maxX * 16, (float) box.maxY * 16, (float) box.maxZ * 16);
            float v = 16 - (to.y - from.y); // sides: the bottom rows of the texture, like vanilla's snow layer
            for (Direction direction : Direction.values()) {
                if (direction == Direction.DOWN) continue; // it lies on the block
                float[] uv = switch (direction.getAxis()) {
                    case Y -> new float[] {from.x, from.z, to.x, to.z};
                    case X -> new float[] {from.z, v, to.z, 16};
                    case Z -> new float[] {from.x, v, to.x, 16};
                };
                BlockElementFace face = new BlockElementFace(null, -1, "#snow", new BlockFaceUV(uv, 0));
                BakedQuad quad = bakery.bakeQuad(from, to, face, sprite, direction, BlockModelRotation.X0_Y0, null, true);
                faces.add(new Face(quad, onSide(box, direction) ? direction : null));
            }
        }
        return List.copyOf(faces);
    }

    private static boolean onSide(AABB box, Direction direction) {
        return switch (direction) {
            case NORTH -> box.minZ <= 0;
            case SOUTH -> box.maxZ >= 1;
            case WEST -> box.minX <= 0;
            case EAST -> box.maxX >= 1;
            default -> false;
        };
    }

    private SnowTopQuads() {}
}
