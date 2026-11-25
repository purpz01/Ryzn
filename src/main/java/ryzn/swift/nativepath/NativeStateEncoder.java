package ryzn.swift.nativepath;

import static ryzn.swift.nativepath.NativeVoxelFlags.*;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.AbstractSkullBlock;
import net.minecraft.world.level.block.BannerBlock;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.MagmaBlock;
import net.minecraft.world.level.block.PowderSnowBlock;
import net.minecraft.world.level.block.PressurePlateBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.TorchBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.TripWireBlock;
import net.minecraft.world.level.block.TripWireHookBlock;
import net.minecraft.world.level.block.VegetationBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.WallBannerBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.WebBlock;
import net.minecraft.world.level.block.WitherRoseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.phys.shapes.CollisionContext;

/** Encodes V5 voxel flags and caches them by block-state ID. */
public final class NativeStateEncoder {

  private static final CollisionContext SHAPE_CONTEXT = CollisionContext.empty();

  private static volatile short[] stateCodes = null;

  private NativeStateEncoder() {}

  public static short codeForStateId(int stateId) {
    final short[] cache = ensureCache();
    if (stateId < 0 || stateId >= cache.length) {
      return AIR;
    }
    final short cached = cache[stateId];
    if (cached != Short.MIN_VALUE) {
      return cached;
    }
    final BlockState state = Block.BLOCK_STATE_REGISTRY.byId(stateId);
    final short code = state == null ? AIR : (short) classify(state);
    cache[stateId] = code;
    return code;
  }

  public static short codeForState(BlockState state) {
    return codeForStateId(Block.BLOCK_STATE_REGISTRY.getId(state));
  }

  private static short[] ensureCache() {
    short[] cache = stateCodes;
    if (cache != null) {
      return cache;
    }
    synchronized (NativeStateEncoder.class) {
      cache = stateCodes;
      if (cache == null) {
        cache = new short[Block.BLOCK_STATE_REGISTRY.size()];
        java.util.Arrays.fill(cache, Short.MIN_VALUE);
        stateCodes = cache;
      }
      return cache;
    }
  }

  private static int classify(BlockState state) {
    final Block block = state.getBlock();
    final boolean danger =
        block instanceof BaseFireBlock
            || block instanceof MagmaBlock
            || block instanceof CactusBlock
            || block instanceof SweetBerryBushBlock
            || block instanceof WitherRoseBlock
            || block instanceof PowderSnowBlock
            || block instanceof WebBlock;
    if (state.isAir()) {
      return AIR;
    }

    final var shape =
        state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, SHAPE_CONTEXT);
    int flags = danger ? DANGER_BIT : 0;
    if (!state.getFluidState().isEmpty()) {
      flags |= FLUID;
    }
    if (state.getFluidState().is(FluidTags.LAVA)) {
      return flags | DANGER_BIT | BLOCKING_WALL;
    }
    if (block instanceof CarpetBlock) {
      return flags | PASSABLE | PASSABLE_FLY | CARPET_LIKE;
    }
    if (block instanceof AbstractSkullBlock) {
      return flags | PASSABLE | PASSABLE_FLY | ETHER_PASSABLE | ETHER_FEET_BLOCKER;
    }

    final boolean flyPassable =
        block instanceof LadderBlock
            || block instanceof VineBlock
            || block instanceof BaseRailBlock
            || block instanceof StandingSignBlock
            || block instanceof WallSignBlock
            || block instanceof BannerBlock
            || block instanceof WallBannerBlock
            || block instanceof TripWireBlock
            || block instanceof TripWireHookBlock
            || block instanceof LeverBlock
            || block instanceof ButtonBlock
            || block instanceof TorchBlock
            || block instanceof RedStoneWireBlock
            || block instanceof PressurePlateBlock;
    if (flyPassable) {
      flags |= PASSABLE_FLY;
    }

    if (block instanceof FenceBlock
        || block instanceof FenceGateBlock
        || block instanceof WallBlock) {
      flags |= SOLID | BLOCKING_WALL | FENCE_LIKE;
    }

    if (block instanceof SlabBlock) {
      flags |= SOLID;
      flags |=
          switch (state.getValue(SlabBlock.TYPE)) {
            case BOTTOM -> SLAB_BOTTOM;
            case TOP -> SLAB_TOP | BLOCKING_WALL;
            case DOUBLE -> BLOCKING_WALL;
          };
    } else if (block instanceof StairBlock) {
      flags |= SOLID;
      if (state.getValue(StairBlock.HALF) == Half.BOTTOM) {
        flags |= STAIRS_BOTTOM;
      }
    } else if (shape.isEmpty()) {
      flags |= PASSABLE | PASSABLE_FLY;
    } else {
      flags |= SOLID;
      final var box = shape.bounds();
      if (box.maxY - box.minY >= 0.5
          && !flyPassable
          && !(block instanceof DoorBlock
              || block instanceof TrapDoorBlock
              || block instanceof VegetationBlock
              || block instanceof SnowLayerBlock)) {
        flags |= BLOCKING_WALL;
      }
    }

    final boolean sign = block instanceof StandingSignBlock || block instanceof WallSignBlock;
    final boolean etherPassable =
        block instanceof ComparatorBlock
            || block instanceof FlowerPotBlock
            || block instanceof LadderBlock
            || (!sign && shape.isEmpty());
    final boolean feetBlocker =
        block instanceof ComparatorBlock
            || block instanceof FlowerPotBlock
            || block instanceof LadderBlock
            || block instanceof VineBlock;
    if (etherPassable) {
      flags |= ETHER_PASSABLE;
    }
    if ((etherPassable || sign) && !feetBlocker) {
      flags |= ETHER_TELEPORT_CLEAR;
    }
    if (feetBlocker) {
      flags |= ETHER_FEET_BLOCKER;
    }
    if (block instanceof SnowLayerBlock) {
      flags |= ETHER_FAKE_FULL_BLOCKER;
    }
    return flags;
  }
}
