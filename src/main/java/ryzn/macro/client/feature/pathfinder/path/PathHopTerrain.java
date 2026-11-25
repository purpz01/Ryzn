package ryzn.macro.client.feature.pathfinder.path;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import ryzn.swift.nativepath.NativeStateEncoder;
import ryzn.swift.nativepath.NativeVoxelFlags;

/** Reads real collision surfaces for stair-aware hop and vault decisions. */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class PathHopTerrain {
  /** How far along the heading to sample the surface about to be entered. Body is 0.6 wide. */
  private static final double PROBE_AHEAD = 0.8;

  /**
   * Maximum collision-surface rise a vanilla jump can clear. A taller riser is a wall, not a hop
   * target: retrying it gains height but never XZ progress. Shared by climb and vault decisions so
   * fences cannot bypass the climb guard through the vault path.
   */
  static final double MAX_HOP_RISE = 1.3;

  private static final double PLAYER_HEIGHT = 1.8;

  /** Rise a walking body clears on its own (vanilla step height). */
  private static final double STEP_UP = 0.6;

  /** Body half-width, just inside 0.3 so a body flush against a face does not count it. */
  private static final double BODY_HALF_WIDTH = 0.299;

  private static final double LINE_SAMPLE_BLOCKS = 0.25;

  private final Minecraft client;

  /**
   * Absolute Y of the surface the body is about to walk into, sampled {@link #PROBE_AHEAD} blocks
   * along the heading toward {@code target} at the player's own feet height. {@code
   * NEGATIVE_INFINITY} when nothing there rises above the feet.
   *
   * <p>Deliberately NOT measured against a path node: nodes sit up to the look-ahead distance away
   * (3.5-7 blocks), and on a half-step staircase every node is another 0.5 higher, so a
   * node-vs-feet delta reads 1.0+ on a run vanilla auto-steps for free - which armed the climb hop
   * the whole way up.
   *
   * <p>Height comes from the collision boxes covering the sampled point, not the shape's bounding
   * box: a bottom stair's bounds are a full cube, so bounds-based height reads its 0.5 tread as a
   * 1.0 wall. Per-point also gets stair facing right for free - front half 0.5, back half 1.0.
   */
  double surfaceAhead(Vec3 target) {
    if (client.player == null || client.level == null) {
      return Double.NEGATIVE_INFINITY;
    }

    double deltaX = target.x - client.player.getX();
    double deltaZ = target.z - client.player.getZ();
    double length = Math.hypot(deltaX, deltaZ);
    if (length < 0.001) {
      return Double.NEGATIVE_INFINITY;
    }

    double probeX = client.player.getX() + deltaX / length * PROBE_AHEAD;
    double probeZ = client.player.getZ() + deltaZ / length * PROBE_AHEAD;
    double feetY = client.player.getY();

    // Only the cell the feet are entering and the one under it can be stepped ONTO. Anything higher
    // is
    // a wall for wallToVaultAhead to judge, not a step.
    int feetBlockY = (int) Math.floor(feetY);
    for (int blockY = feetBlockY; blockY >= feetBlockY - 1; blockY--) {
      BlockPos position = new BlockPos((int) Math.floor(probeX), blockY, (int) Math.floor(probeZ));
      if (!WalkCheck.canStandOn(position)) {
        continue;
      }
      double top = treadTopY(position, probeX, probeZ);
      if (top > feetY + 1.0E-4) {
        // A tread with no room for the body above it is the bottom of a taller wall, not a step.
        return bodyFitsAbove(probeX, position.getY(), top, probeZ) ? top : Double.NEGATIVE_INFINITY;
      }
    }
    return Double.NEGATIVE_INFINITY;
  }

  private static boolean bodyFitsAbove(
      double worldX, int treadBlockY, double surfaceY, double worldZ) {
    int blockX = (int) Math.floor(worldX);
    int blockZ = (int) Math.floor(worldZ);
    // The tread's own block is partly body space above a slab or stair, but it is the step itself.
    int lowY = treadBlockY + 1;
    int highY = (int) Math.floor(surfaceY + PLAYER_HEIGHT - 1.0E-4);
    for (int blockY = lowY; blockY <= highY; blockY++) {
      if (!WalkCheck.canWalkThrough(new BlockPos(blockX, blockY, blockZ))) {
        return false;
      }
    }
    return true;
  }

  private double treadTopY(BlockPos position, double worldX, double worldZ) {
    VoxelShape shape =
        client.level.getBlockState(position).getCollisionShape(client.level, position);
    if (shape.isEmpty()) {
      return position.getY();
    }

    double localX = worldX - position.getX();
    double localZ = worldZ - position.getZ();
    double top = Double.NEGATIVE_INFINITY;
    for (AABB box : shape.toAabbs()) {
      if (localX < box.minX || localX > box.maxX) {
        continue;
      }
      if (localZ < box.minZ || localZ > box.maxZ) {
        continue;
      }
      top = Math.max(top, box.maxY);
    }
    return top == Double.NEGATIVE_INFINITY ? Double.NEGATIVE_INFINITY : position.getY() + top;
  }

  boolean wallToVaultAhead(Vec3 target) {
    if (client.player == null || client.level == null) {
      return false;
    }

    double directionX = target.x - client.player.getX();
    double directionZ = target.z - client.player.getZ();
    double length = Math.hypot(directionX, directionZ);
    if (length < 0.001) {
      return false;
    }
    directionX /= length;
    directionZ /= length;

    BlockPos roundedSupport = client.player.blockPosition().below();
    BlockPos support =
        RimRecovery.supportingBlock(client.player.mainSupportingBlockPos, roundedSupport);
    double floorTopY = WalkCheck.getCollisionTopY(support);

    int footY = (int) Math.floor(client.player.getY());
    for (double distance : new double[] {0.65, 1.05}) {
      int blockX = (int) Math.floor(client.player.getX() + directionX * distance);
      int blockZ = (int) Math.floor(client.player.getZ() + directionZ * distance);
      if (isVaultable(blockX, footY, blockZ, floorTopY)) {
        return true;
      }
    }
    return false;
  }

  double surfaceY(Vec3 node) {
    if (client.level == null) {
      return Double.NEGATIVE_INFINITY;
    }
    int blockX = (int) Math.floor(node.x);
    int blockY = (int) Math.floor(node.y);
    int blockZ = (int) Math.floor(node.z);
    return nearestSurfaceInColumn(blockX, blockZ, node.y, blockY - 1, blockY);
  }

  boolean rampWalkableTo(Vec3 target) {
    if (client.player == null || client.level == null) {
      return false;
    }
    double deltaX = target.x - client.player.getX();
    double deltaZ = target.z - client.player.getZ();
    double length = Math.hypot(deltaX, deltaZ);
    if (length < 0.001) {
      return true;
    }

    int samples = Math.max(1, (int) Math.ceil(length / 0.5));
    double lastTopY = client.player.getY();
    for (int sample = 1; sample <= samples; sample++) {
      double fraction = (double) sample / samples;
      int blockX = (int) Math.floor(client.player.getX() + deltaX * fraction);
      int blockZ = (int) Math.floor(client.player.getZ() + deltaZ * fraction);
      double surface =
          nearestSurfaceInColumn(
              blockX,
              blockZ,
              lastTopY,
              (int) Math.floor(lastTopY - 1.5),
              (int) Math.floor(lastTopY + 1.5));
      if (surface == Double.NEGATIVE_INFINITY) {
        continue;
      }
      if (surface - lastTopY > 0.6) {
        return false;
      }
      lastTopY = surface;
    }
    return true;
  }

  /**
   * Whether the body can walk a straight line from where it stands to {@code (toX, toZ)} without a
   * hop: at every sample along it, no part of the 0.6-wide footprint meets a surface more than a
   * step above the level the body has climbed to so far, so a half step taken first counts. The hop
   * probes read one point against the feet; this is what tells a riser the body must hop from one
   * it reaches by walking up a half step.
   */
  boolean bodyLineWalkable(double toX, double toZ) {
    if (client.player == null || client.level == null) {
      return true;
    }
    return lineWalkable(
        client.player.getX(),
        client.player.getY(),
        client.player.getZ(),
        toX,
        toZ,
        BODY_HALF_WIDTH,
        false);
  }

  /**
   * {@link #bodyLineWalkable} from any standing point, for a footprint {@code halfWidth} wide each
   * side. {@code grounded} also asks that every corner stays on floor no more than a step below the
   * level walked so far, so the line never runs over an edge or a hole.
   */
  boolean lineWalkable(
      double fromX,
      double fromLevel,
      double fromZ,
      double toX,
      double toZ,
      double halfWidth,
      boolean grounded) {
    return lineWalkable(fromX, fromLevel, fromZ, toX, toZ, halfWidth, grounded, false);
  }

  boolean safeWalkingLine(double toX, double toZ) {
    if (client.player == null || client.level == null) {
      return false;
    }
    return lineWalkable(
        client.player.getX(),
        client.player.getY(),
        client.player.getZ(),
        toX,
        toZ,
        client.player.getBbWidth() * 0.5 - 0.001,
        true,
        true);
  }

  boolean safeAirStrafeLine(double floorY, double toX, double toZ) {
    if (client.player == null || client.level == null) {
      return false;
    }
    // Keep an extra quarter-block of supported floor beside a diagonally steered hop.
    double halfWidth = client.player.getBbWidth() * 0.5 + 0.25;
    double fromX = client.player.getX();
    double fromZ = client.player.getZ();
    return lineWalkable(fromX, floorY, fromZ, toX, toZ, halfWidth, true, true);
  }

  boolean safeCombatAirLine(double floorY, double toX, double toZ) {
    if (client.player == null || client.level == null) {
      return false;
    }
    return lineWalkable(
        client.player.getX(),
        floorY,
        client.player.getZ(),
        toX,
        toZ,
        client.player.getBbWidth() * 0.5 + 0.25,
        true,
        true,
        1.5,
        true);
  }

  FallPredictor.Landing combatAirLanding(double floorY) {
    return FallPredictor.simulate(
        client.player.position(), client.player.getDeltaMovement(), floorY, this::clearAirMovement);
  }

  boolean safeCombatAirInput(FallPredictor.Steer steer, double floorY) {
    Vec3 from = client.player.position();
    Vec3 velocity = combatAirInputVelocity(steer);
    return clearAirMovement(
        from, from.add(velocity.x, Math.max(floorY - from.y, velocity.y), velocity.z));
  }

  boolean safeCombatBrakeLanding(FallPredictor.Steer steer, double floorY) {
    FallPredictor.Landing landing =
        FallPredictor.simulate(
            client.player.position(),
            combatAirInputVelocity(steer),
            floorY,
            this::clearAirMovement);
    return landing.reached()
        && lineWalkable(
            landing.x(),
            floorY,
            landing.z(),
            landing.x(),
            landing.z(),
            client.player.getBbWidth() * 0.5 + 0.25,
            true,
            true,
            1.5,
            true);
  }

  private Vec3 combatAirInputVelocity(FallPredictor.Steer steer) {
    double yaw = Math.toRadians(client.player.getYRot());
    double forward =
        steer == FallPredictor.Steer.FORWARD ? 1.0 : steer == FallPredictor.Steer.BACK ? -1.0 : 0.0;
    double right =
        steer == FallPredictor.Steer.RIGHT ? 1.0 : steer == FallPredictor.Steer.LEFT ? -1.0 : 0.0;
    Vec3 velocity = client.player.getDeltaMovement();
    double acceleration = FallPredictor.AIR_ACCEL * (client.player.isSprinting() ? 1.3 : 1.0);
    return velocity.add(
        (-Math.sin(yaw) * forward - Math.cos(yaw) * right) * acceleration,
        0.0,
        (Math.cos(yaw) * forward - Math.sin(yaw) * right) * acceleration);
  }

  private boolean clearAirMovement(Vec3 from, Vec3 to) {
    AABB swept =
        client
            .player
            .getBoundingBox()
            .move(from.subtract(client.player.position()))
            .expandTowards(to.subtract(from))
            .deflate(1.0e-5);
    return client.level.noCollision(client.player, swept);
  }

  private boolean lineWalkable(
      double fromX,
      double fromLevel,
      double fromZ,
      double toX,
      double toZ,
      double halfWidth,
      boolean grounded,
      boolean avoidDanger) {
    return lineWalkable(
        fromX, fromLevel, fromZ, toX, toZ, halfWidth, grounded, avoidDanger, STEP_UP, false);
  }

  private boolean lineWalkable(
      double fromX,
      double fromLevel,
      double fromZ,
      double toX,
      double toZ,
      double halfWidth,
      boolean grounded,
      boolean avoidDanger,
      double maximumStep,
      boolean fixedFloor) {
    if (client.level == null) {
      return true;
    }
    final double deltaX = toX - fromX;
    final double deltaZ = toZ - fromZ;
    final int samples =
        Math.max(1, (int) Math.ceil(Math.hypot(deltaX, deltaZ) / LINE_SAMPLE_BLOCKS));
    final double[] offsets =
        avoidDanger
            ? new double[] {-halfWidth, 0.0, halfWidth}
            : new double[] {-halfWidth, halfWidth};
    double level = fromLevel;
    for (int sample = 1; sample <= samples; sample++) {
      double referenceLevel = fixedFloor ? fromLevel : level;
      final double fraction = (double) sample / samples;
      final double x = fromX + deltaX * fraction;
      final double z = fromZ + deltaZ * fraction;
      double highest = Double.NEGATIVE_INFINITY;
      double lowest = Double.POSITIVE_INFINITY;
      for (double offsetX : offsets) {
        for (double offsetZ : offsets) {
          final double surface =
              surfaceUnderBody(
                  x + offsetX, z + offsetZ, referenceLevel, fixedFloor ? maximumStep : 0.0);
          highest = Math.max(highest, surface);
          lowest = Math.min(lowest, surface);
        }
      }
      if (highest > referenceLevel + maximumStep
          || grounded && lowest < referenceLevel - maximumStep) {
        return false;
      }
      if (highest != Double.NEGATIVE_INFINITY) {
        level = highest;
      }
      if (avoidDanger) {
        for (double offsetX : offsets) {
          for (double offsetZ : offsets) {
            int blockX = (int) Math.floor(x + offsetX);
            int blockZ = (int) Math.floor(z + offsetZ);
            for (int blockY = (int) Math.floor((fixedFloor ? lowest : level) - 1.0e-4);
                blockY < level + PLAYER_HEIGHT;
                blockY++) {
              BlockPos position = new BlockPos(blockX, blockY, blockZ);
              if (WalkCheck.avoidWalkingInto(position)
                  || NativeVoxelFlags.isDanger(
                      NativeStateEncoder.codeForState(client.level.getBlockState(position)))) {
                return false;
              }
            }
          }
        }
      }
    }
    return true;
  }

  /**
   * Y of the block a body standing near {@code level} at (x, z) is supported by, the trail's own
   * convention for a walk point, or NaN over a gap.
   */
  double supportBlockY(double x, double z, double level) {
    final double top = surfaceUnderBody(x, z, level);
    return top == Double.NEGATIVE_INFINITY ? Double.NaN : Math.ceil(top - 1.0E-6) - 1.0;
  }

  double footprintSurfaceY(double x, double z, double level) {
    double halfWidth = client.player.getBbWidth() * 0.5 - 0.001;
    double highest = Double.NEGATIVE_INFINITY;
    for (double offsetX : new double[] {-halfWidth, halfWidth}) {
      for (double offsetZ : new double[] {-halfWidth, halfWidth}) {
        highest = Math.max(highest, surfaceUnderBody(x + offsetX, z + offsetZ, level));
      }
    }
    return highest;
  }

  /**
   * Top of the highest collision box covering this point that a body standing at {@code level}
   * would touch: from the block under the feet up to head height. NEGATIVE_INFINITY over a gap.
   */
  private double surfaceUnderBody(double worldX, double worldZ, double level) {
    return surfaceUnderBody(worldX, worldZ, level, 0.0);
  }

  private double surfaceUnderBody(double worldX, double worldZ, double level, double extraDepth) {
    final int blockX = (int) Math.floor(worldX);
    final int blockZ = (int) Math.floor(worldZ);
    final int lowY = (int) Math.floor(level - extraDepth) - 1;
    final int highY = (int) Math.floor(level + PLAYER_HEIGHT - 1.0E-4);
    final double localX = worldX - blockX;
    final double localZ = worldZ - blockZ;
    for (int blockY = highY; blockY >= lowY; blockY--) {
      final BlockPos position = new BlockPos(blockX, blockY, blockZ);
      final VoxelShape shape =
          client.level.getBlockState(position).getCollisionShape(client.level, position);
      double top = Double.NEGATIVE_INFINITY;
      for (AABB box : shape.toAabbs()) {
        if (localX >= box.minX
            && localX <= box.maxX
            && localZ >= box.minZ
            && localZ <= box.maxZ
            && blockY + box.minY < level + PLAYER_HEIGHT) {
          top = Math.max(top, blockY + box.maxY);
        }
      }
      if (top != Double.NEGATIVE_INFINITY) {
        return top;
      }
    }
    return Double.NEGATIVE_INFINITY;
  }

  private boolean isVaultable(int blockX, int blockY, int blockZ, double floorTopY) {
    BlockPos blockPosition = new BlockPos(blockX, blockY, blockZ);
    BlockState blockState = client.level.getBlockState(blockPosition);
    if (blockState.getBlock() instanceof StairBlock || blockState.getBlock() instanceof SlabBlock) {
      return false;
    }
    if (WalkCheck.canWalkThrough(blockPosition) || !WalkCheck.canStandOn(blockPosition)) {
      return false;
    }
    if (!vaultHeightInRange(WalkCheck.getCollisionTopY(blockPosition), floorTopY)) {
      return false;
    }
    return WalkCheck.canWalkThrough(new BlockPos(blockX, blockY + 1, blockZ))
        && WalkCheck.canWalkThrough(new BlockPos(blockX, blockY + 2, blockZ));
  }

  static boolean vaultHeightInRange(double obstacleTopY, double floorTopY) {
    double rise = obstacleTopY - floorTopY;
    return rise > 0.6 && rise <= MAX_HOP_RISE;
  }

  private double nearestSurfaceInColumn(
      int blockX, int blockZ, double referenceY, int lowY, int highY) {
    if (client.level == null) {
      return Double.NEGATIVE_INFINITY;
    }
    double best = Double.NEGATIVE_INFINITY;
    double bestDistance = Double.MAX_VALUE;
    for (int blockY = highY; blockY >= lowY; blockY--) {
      BlockPos position = new BlockPos(blockX, blockY, blockZ);
      if (!WalkCheck.canStandOn(position)) {
        continue;
      }
      double surface = WalkCheck.getCollisionTopY(position);
      double distance = Math.abs(surface - referenceY);
      if (distance < bestDistance) {
        bestDistance = distance;
        best = surface;
      }
    }
    return best;
  }
}
