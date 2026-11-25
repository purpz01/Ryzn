package ryzn.macro.client.feature.pathfinder.path;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.CauldronBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LilyPadBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SkullBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.StainedGlassBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.TrappedChestBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Vanilla-world walkability checks used by the path executor. Reimplements block-walkability
 * semantics against the live Minecraft world (no BlockStateAccessor abstraction). Every method
 * queries {@code Minecraft.getInstance().level} directly and returns a safe default when the world
 * is null.
 */
public final class WalkCheck {

  private WalkCheck() {}

  private static Level world() {
    return Minecraft.getInstance().level;
  }

  // -----------------------------------------------------------------
  // Public API
  // -----------------------------------------------------------------

  /**
   * Can the player stand on top of the block at {@code pos}? Mirrors {@code
   * MovementHelper.canStandOn}: solid full cubes, redstone blocks, ladders, farmland/grass, chests,
   * glass, sea lanterns, bottom stairs, slabs, and snow layers all return true. Lily-padded water
   * counts. Lava and air return false.
   */
  public static boolean canStandOn(BlockPos pos) {
    Level w = world();
    if (w == null) {
      return false;
    }
    BlockState state = w.getBlockState(pos);
    return canStandOn(w, pos, state);
  }

  private static boolean canStandOn(BlockGetter view, BlockPos pos, BlockState state) {
    if (state.isAir()) {
      return false;
    }
    if (isLava(state)) {
      return false;
    }
    if (isWater(state)) {
      // Treat water as standable only if the block above carries a lily
      // pad or carpet, matching the "lily pad on water" case.
      BlockPos above = pos.above();
      BlockState up = view.getBlockState(above);
      return up.getBlock() instanceof LilyPadBlock || up.getBlock() instanceof CarpetBlock;
    }

    // Bottom stairs and slabs are explicitly standable.
    if (state.getBlock() instanceof StairBlock) {
      return state.getValue(StairBlock.HALF) == Half.BOTTOM;
    }
    if (state.getBlock() instanceof SlabBlock) {
      return true;
    }
    if (state.getBlock() instanceof SnowLayerBlock) {
      return true;
    }
    if (state.getBlock() instanceof LadderBlock) {
      return true;
    }

    // Chests / ender chests / glass / sea lantern - all stood-on by players
    // even though their collision shapes are not "full normal cubes".
    if (state.getBlock() instanceof ChestBlock
        || state.getBlock() instanceof TrappedChestBlock
        || state.getBlock() instanceof EnderChestBlock) {
      return true;
    }
    if (state.getBlock() == Blocks.GLASS || state.getBlock() instanceof StainedGlassBlock) {
      return true;
    }
    if (state.getBlock() == Blocks.SEA_LANTERN
        || state.getBlock() == Blocks.REDSTONE_BLOCK
        || state.getBlock() == Blocks.FARMLAND
        || state.getBlock() == Blocks.GRASS_BLOCK) {
      return true;
    }

    // Fall back to collision-shape geometry: a "normal cube" has a 1x1
    // top face at maxY == 1.0. Anything shorter than that (carpets,
    // pressure plates, etc.) we treat as not stand-on-able since the
    // pathing logic always lands the player on the top of the *next*
    // block, not on a sub-block lip.
    VoxelShape shape = state.getCollisionShape(view, pos);
    if (shape.isEmpty()) {
      return false;
    }
    AABB box = shape.bounds();
    return box.maxY >= 1.0 - 1.0E-6;
  }

  /**
   * Can the player's body occupy this block (no blocking collision)? Mirrors {@code
   * MovementHelper.canWalkThrough}: air, most plants, open doors and fence gates, signs, rails,
   * torches and similar non-collidable blocks return true. Iron doors, fences, walls, trapdoors,
   * skulls, cauldrons, ladders, cobwebs, fire, tripwire and flowing/non-source liquids return
   * false. Snow layers and carpets are walkable only when the block underneath is itself standable.
   */
  public static boolean canWalkThrough(BlockPos pos) {
    Level w = world();
    if (w == null) {
      return false;
    }
    BlockState state = w.getBlockState(pos);
    return canWalkThrough(w, pos, state);
  }

  private static boolean canWalkThrough(BlockGetter view, BlockPos pos, BlockState state) {
    if (state.isAir()) {
      return true;
    }

    // Hard-stop blocks. Doors, fence gates, snow layers, carpets and
    // liquids need positional context, so they bypass this fast path
    // and are decided below.
    if (state.getBlock() == Blocks.FIRE
        || state.getBlock() == Blocks.SOUL_FIRE
        || state.getBlock() == Blocks.TRIPWIRE
        || state.getBlock() == Blocks.COBWEB
        || state.getBlock() == Blocks.END_PORTAL
        || state.getBlock() == Blocks.COCOA
        || state.getBlock() instanceof SkullBlock
        || state.getBlock() instanceof TrapDoorBlock
        || state.getBlock() instanceof CauldronBlock
        || state.getBlock() instanceof FenceBlock
        || state.getBlock() instanceof WallBlock
        || state.getBlock() instanceof LadderBlock) {
      return false;
    }

    // Doors / fence gates - all openable except iron door.
    if (state.getBlock() instanceof DoorBlock || state.getBlock() instanceof FenceGateBlock) {
      return state.getBlock() != Blocks.IRON_DOOR;
    }

    // Carpets: walkable only if the block under us is itself standable
    // (you can sit on a carpet on a chest, etc.).
    if (state.getBlock() instanceof CarpetBlock) {
      BlockPos below = pos.below();
      BlockState ground = view.getBlockState(below);
      return canStandOn(view, below, ground);
    }

    // Snow layers: layer 1 is steppable; deeper layers block movement.
    if (state.getBlock() instanceof SnowLayerBlock) {
      if (state.getValue(SnowLayerBlock.LAYERS) >= 2) {
        return false;
      }
      BlockPos below = pos.below();
      BlockState ground = view.getBlockState(below);
      return canStandOn(view, below, ground);
    }

    // Liquids: water is OK iff source level and nothing solid above;
    // lava (and any flowing fluid) is impassable.
    if (state.getBlock() instanceof LiquidBlock) {
      if (isLava(state)) {
        return false;
      }
      // Source level == 0; anything else is flowing.
      if (state.getValue(LiquidBlock.LEVEL) != 0) {
        return false;
      }
      BlockState up = view.getBlockState(pos.above());
      if (up.getBlock() instanceof LiquidBlock || up.getBlock() instanceof LilyPadBlock) {
        return false;
      }
      return isWater(state);
    }

    // Generic fallback: if the block's collision shape is empty it is
    // passable (flowers, tall grass, torches, signs, rails, crops,
    // pressure plates, buttons, etc.).
    return state.getCollisionShape(view, pos).isEmpty();
  }

  /**
   * Absolute world Y of the top collision surface at {@code pos}. Computed from the block's
   * collision-shape bounding box. If the shape is empty (passable block) returns {@code
   * pos.getY()}.
   */
  public static double getCollisionTopY(BlockPos pos) {
    Level w = world();
    if (w == null) {
      return pos.getY();
    }
    BlockState state = w.getBlockState(pos);
    VoxelShape shape = state.getCollisionShape(w, pos);
    if (shape.isEmpty()) {
      return pos.getY();
    }
    AABB box = shape.bounds();
    return pos.getY() + box.maxY;
  }

  /** True if the block at {@code pos} is a bottom slab. */
  public static boolean isBottomSlab(BlockPos pos) {
    Level w = world();
    if (w == null) {
      return false;
    }
    return isBottomSlab(w.getBlockState(pos));
  }

  private static boolean isBottomSlab(BlockState state) {
    if (!(state.getBlock() instanceof SlabBlock)) {
      return false;
    }
    return state.getValue(SlabBlock.TYPE) == SlabType.BOTTOM;
  }

  /**
   * True if the block at {@code pos} is a bottom stair whose front side faces the direction {@code
   * (dx, dz)} (the side a walker is travelling across when going forward in that step).
   */
  public static boolean isValidStair(BlockPos pos, int dx, int dz) {
    Level w = world();
    if (w == null) {
      return false;
    }
    return isValidStair(w.getBlockState(pos), dx, dz);
  }

  private static boolean isValidStair(BlockState state, int dx, int dz) {
    if (dx == dz) {
      return false;
    }
    if (!(state.getBlock() instanceof StairBlock)) {
      return false;
    }
    if (state.getValue(StairBlock.HALF) != Half.BOTTOM) {
      return false;
    }
    Direction facing = state.getValue(StairBlock.FACING);
    if (dz == -1) {
      return facing == Direction.NORTH;
    }
    if (dz == 1) {
      return facing == Direction.SOUTH;
    }
    if (dx == -1) {
      return facing == Direction.WEST;
    }
    if (dx == 1) {
      return facing == Direction.EAST;
    }
    return false;
  }

  /**
   * True for blocks the path executor must never enter: liquids, fire, cactus, end portal, cobweb.
   */
  public static boolean avoidWalkingInto(BlockPos pos) {
    Level w = world();
    if (w == null) {
      return true;
    }
    BlockState state = w.getBlockState(pos);
    return avoidWalkingInto(state);
  }

  private static boolean avoidWalkingInto(BlockState state) {
    if (state.getBlock() instanceof LiquidBlock) {
      return true;
    }
    if (state.getBlock() == Blocks.FIRE || state.getBlock() == Blocks.SOUL_FIRE) {
      return true;
    }
    if (state.getBlock() == Blocks.CACTUS) {
      return true;
    }
    if (state.getBlock() == Blocks.END_PORTAL) {
      return true;
    }
    return state.getBlock() == Blocks.COBWEB;
  }

  /** True if the block at {@code pos} is a water source / flow. */
  public static boolean isWater(BlockPos pos) {
    Level w = world();
    if (w == null) {
      return false;
    }
    BlockState state = w.getBlockState(pos);
    return state.getBlock() == Blocks.WATER;
  }

  private static boolean isWater(BlockState state) {
    return state.getBlock() == Blocks.WATER;
  }

  /** True if the block at {@code pos} is a lava source / flow. */
  public static boolean isLava(BlockPos pos) {
    Level w = world();
    if (w == null) {
      return false;
    }
    BlockState state = w.getBlockState(pos);
    return state.getBlock() == Blocks.LAVA;
  }

  private static boolean isLava(BlockState state) {
    return state.getBlock() == Blocks.LAVA;
  }

  // -----------------------------------------------------------------
  // Internal helpers (operate on a supplied state to avoid double lookups)
  // -----------------------------------------------------------------
}
