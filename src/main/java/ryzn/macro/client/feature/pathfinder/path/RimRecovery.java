package ryzn.macro.client.feature.pathfinder.path;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Executor-only escape from a supported block edge whose usable center is obstructed at body
 * height.
 */
public final class RimRecovery {

  private static final double BODY_OBSTRUCTION_MIN_HEIGHT = 0.6;
  private static final double KEY_SWITCH_MARGIN_DEGREES = 12.0;
  private static final int ESCAPE_SEARCH_RADIUS = 2;
  private static final double ESCAPE_SAMPLE_STEP = 0.15;
  private static final double KEY_STEP_LOOKAHEAD = 0.20;
  private static final double EFFECTIVE_DISTANCE_TIE_SQUARED = 0.05;
  private static final double FLOOR_HEIGHT_EPSILON = 0.08;
  private static final double SHAPE_EPSILON = 1.0E-5;

  public enum MovementIntent {
    FORWARD,
    LEFT,
    BACKWARD,
    RIGHT
  }

  public record EscapeCandidate(
      BlockPos feetCell, double playerDistanceSquared, double routeDistanceSquared, boolean safe) {}

  record HorizontalStep(double x, double z) {}

  private RimRecovery() {}

  public static boolean shouldRecover(
      Optional<BlockPos> mainSupport, BlockPos roundedSupport, boolean bodyHeightObstruction) {
    return mainSupport.isPresent()
        && bodyHeightObstruction
        && mainSupport.get().getY() == roundedSupport.getY()
        && !mainSupport.get().equals(roundedSupport);
  }

  public static boolean hasBodyHeightObstruction(Level level, BlockPos support) {
    if (level == null || support == null) {
      return false;
    }
    BlockPos bodyCell = support.above();
    VoxelShape shape = level.getBlockState(bodyCell).getCollisionShape(level, bodyCell);
    return isBodyHeightObstruction(shape);
  }

  static boolean isBodyHeightObstruction(VoxelShape shape) {
    if (shape == null || shape.isEmpty()) {
      return false;
    }
    for (AABB box : shape.toAabbs()) {
      boolean overlapsCenter =
          box.maxX > 0.2 + SHAPE_EPSILON
              && box.minX < 0.8 - SHAPE_EPSILON
              && box.maxZ > 0.2 + SHAPE_EPSILON
              && box.minZ < 0.8 - SHAPE_EPSILON;
      if (overlapsCenter && box.maxY > BODY_OBSTRUCTION_MIN_HEIGHT + SHAPE_EPSILON) {
        return true;
      }
    }
    return false;
  }

  public static BlockPos supportingBlock(Optional<BlockPos> mainSupport, BlockPos roundedSupport) {
    return mainSupport.orElse(roundedSupport);
  }

  static boolean supportsCenteredFootprint(
      VoxelShape floorShape, double localFloorTop, double halfWidth) {
    return supportsFootprint(
        floorShape,
        0.5 - halfWidth,
        0.5 + halfWidth,
        0.5 - halfWidth,
        0.5 + halfWidth,
        localFloorTop);
  }

  static boolean hasSupportContact(
      VoxelShape floorShape, double minX, double maxX, double minZ, double maxZ, double floorTop) {
    if (floorShape == null || floorShape.isEmpty() || floorTop <= SHAPE_EPSILON) {
      return false;
    }
    VoxelShape footprint = Shapes.box(minX, floorTop - SHAPE_EPSILON, minZ, maxX, floorTop, maxZ);
    return Shapes.joinIsNotEmpty(footprint, floorShape, BooleanOp.AND);
  }

  static boolean footprintInsideCell(
      double playerX, double playerZ, double halfWidth, BlockPos feetCell) {
    double epsilon = 1.0E-6;
    return playerX - halfWidth >= feetCell.getX() - epsilon
        && playerX + halfWidth <= feetCell.getX() + 1.0 + epsilon
        && playerZ - halfWidth >= feetCell.getZ() - epsilon
        && playerZ + halfWidth <= feetCell.getZ() + 1.0 + epsilon;
  }

  public static Optional<BlockPos> findEscapeTarget(
      Level level, LocalPlayer player, BlockPos obstructedSupport, Vec3 routeTarget) {
    if (level == null || player == null || obstructedSupport == null) {
      return Optional.empty();
    }

    BlockPos obstructedFeet = obstructedSupport.above();
    List<EscapeCandidate> candidates = new ArrayList<>();
    for (int offsetX = -ESCAPE_SEARCH_RADIUS; offsetX <= ESCAPE_SEARCH_RADIUS; offsetX++) {
      for (int offsetZ = -ESCAPE_SEARCH_RADIUS; offsetZ <= ESCAPE_SEARCH_RADIUS; offsetZ++) {
        BlockPos feetCell = obstructedFeet.offset(offsetX, 0, offsetZ);
        boolean safe =
            !feetCell.equals(obstructedFeet)
                && isSafeEscapeCell(level, player, feetCell)
                && pathToCellIsClear(level, player, feetCell);
        candidates.add(
            new EscapeCandidate(
                feetCell,
                horizontalDistanceSquared(
                    player.getX(), player.getZ(), feetCell.getX() + 0.5, feetCell.getZ() + 0.5),
                routeDistanceSquared(feetCell, routeTarget),
                safe));
      }
    }
    return chooseEscapeTarget(candidates, obstructedFeet);
  }

  static Optional<BlockPos> chooseEscapeTarget(
      List<EscapeCandidate> candidates, BlockPos obstructedFeet) {
    if (candidates == null || candidates.isEmpty()) {
      return Optional.empty();
    }
    List<EscapeCandidate> safeCandidates =
        candidates.stream()
            .filter(EscapeCandidate::safe)
            .filter(candidate -> !candidate.feetCell().equals(obstructedFeet))
            .toList();
    double nearestDistance =
        safeCandidates.stream()
            .mapToDouble(EscapeCandidate::playerDistanceSquared)
            .min()
            .orElse(Double.POSITIVE_INFINITY);
    return safeCandidates.stream()
        .filter(
            candidate ->
                candidate.playerDistanceSquared()
                    <= nearestDistance + EFFECTIVE_DISTANCE_TIE_SQUARED)
        .min(
            java.util.Comparator.comparingDouble(EscapeCandidate::routeDistanceSquared)
                .thenComparingDouble(EscapeCandidate::playerDistanceSquared)
                .thenComparingInt(candidate -> candidate.feetCell().getX())
                .thenComparingInt(candidate -> candidate.feetCell().getZ()))
        .map(EscapeCandidate::feetCell);
  }

  public static boolean safelyAtTarget(Level level, LocalPlayer player, BlockPos feetCell) {
    if (level == null || player == null || feetCell == null || !player.onGround()) {
      return false;
    }
    double halfWidth = player.getBbWidth() * 0.5;
    if (Math.abs(player.getY() - feetCell.getY()) > FLOOR_HEIGHT_EPSILON
        || !footprintInsideCell(player.getX(), player.getZ(), halfWidth, feetCell)) {
      return false;
    }

    BlockPos floor = feetCell.below();
    VoxelShape floorShape = level.getBlockState(floor).getCollisionShape(level, floor);
    return supportsFootprint(
            floorShape,
            player.getX() - halfWidth - floor.getX(),
            player.getX() + halfWidth - floor.getX(),
            player.getZ() - halfWidth - floor.getZ(),
            player.getZ() + halfWidth - floor.getZ(),
            player.getY() - floor.getY())
        && level.noCollision(player, player.getBoundingBox());
  }

  public static MovementIntent movementIntent(
      double playerX,
      double playerZ,
      float playerYaw,
      BlockPos targetFeetCell,
      MovementIntent previous) {
    double targetX = targetFeetCell.getX() + 0.5;
    double targetZ = targetFeetCell.getZ() + 0.5;
    double requiredYaw = Math.toDegrees(Math.atan2(playerX - targetX, targetZ - playerZ));
    double relativeYaw = wrapDegrees(requiredYaw - playerYaw) * -1.0;

    MovementIntent best = MovementIntent.FORWARD;
    double bestError = Double.MAX_VALUE;
    MovementIntent[] intents = MovementIntent.values();
    double[] intentYaws = {0.0, 90.0, 180.0, -90.0};
    for (int i = 0; i < intents.length; i++) {
      double error = Math.abs(wrapDegrees(relativeYaw - intentYaws[i]));
      if (error < bestError) {
        bestError = error;
        best = intents[i];
      }
    }

    if (previous == null
        || previous == best
        || !intentReducesDistance(playerX, playerZ, playerYaw, targetFeetCell, previous)) {
      return best;
    }
    double previousYaw = intentYaws[previous.ordinal()];
    double previousError = Math.abs(wrapDegrees(relativeYaw - previousYaw));
    return previousError - bestError > KEY_SWITCH_MARGIN_DEGREES ? best : previous;
  }

  static HorizontalStep movementStep(float playerYaw, MovementIntent intent, double distance) {
    double[] intentYaws = {0.0, 90.0, 180.0, -90.0};
    double movementYaw = Math.toRadians(playerYaw - intentYaws[intent.ordinal()]);
    return new HorizontalStep(-Math.sin(movementYaw) * distance, Math.cos(movementYaw) * distance);
  }

  static boolean intentReducesDistance(
      double playerX,
      double playerZ,
      float playerYaw,
      BlockPos targetFeetCell,
      MovementIntent intent) {
    if (targetFeetCell == null || intent == null) {
      return false;
    }
    HorizontalStep step = movementStep(playerYaw, intent, 1.0);
    double targetDx = targetFeetCell.getX() + 0.5 - playerX;
    double targetDz = targetFeetCell.getZ() + 0.5 - playerZ;
    return targetDx * step.x() + targetDz * step.z() > SHAPE_EPSILON;
  }

  public static boolean nextMovementStepIsSafe(
      Level level, LocalPlayer player, MovementIntent intent) {
    if (level == null || player == null || intent == null || !player.onGround()) {
      return false;
    }
    HorizontalStep step = movementStep(player.getYRot(), intent, KEY_STEP_LOOKAHEAD);
    AABB nextBox = player.getBoundingBox().move(step.x(), 0.0, step.z());
    boolean currentCollisionFree = level.noCollision(player, player.getBoundingBox());
    boolean nextCollisionFree = level.noCollision(player, nextBox);
    return collisionStepIsAllowed(currentCollisionFree, nextCollisionFree)
        && hasSupportContactAt(
            level,
            (nextBox.minX + nextBox.maxX) * 0.5,
            nextBox.minY,
            (nextBox.minZ + nextBox.maxZ) * 0.5,
            player.getBbWidth() * 0.5);
  }

  static boolean collisionStepIsAllowed(boolean currentCollisionFree, boolean nextCollisionFree) {
    return !currentCollisionFree || nextCollisionFree;
  }

  private static boolean isSafeEscapeCell(Level level, LocalPlayer player, BlockPos feetCell) {
    BlockPos floor = feetCell.below();
    if (WalkCheck.avoidWalkingInto(floor)
        || WalkCheck.avoidWalkingInto(feetCell)
        || WalkCheck.avoidWalkingInto(feetCell.above())
        || !WalkCheck.canStandOn(floor)
        || !WalkCheck.canWalkThrough(feetCell)
        || !WalkCheck.canWalkThrough(feetCell.above())) {
      return false;
    }

    VoxelShape floorShape = level.getBlockState(floor).getCollisionShape(level, floor);
    double localFloorTop = feetCell.getY() - floor.getY();
    double halfWidth = player.getBbWidth() * 0.5;
    if (!supportsCenteredFootprint(floorShape, localFloorTop, halfWidth)) {
      return false;
    }

    AABB targetBox =
        bodyBoxAt(feetCell.getX() + 0.5, feetCell.getY(), feetCell.getZ() + 0.5, player);
    return level.noCollision(player, targetBox);
  }

  private static boolean pathToCellIsClear(Level level, LocalPlayer player, BlockPos feetCell) {
    double targetX = feetCell.getX() + 0.5;
    double targetY = feetCell.getY();
    double targetZ = feetCell.getZ() + 0.5;
    double distance = Math.hypot(targetX - player.getX(), targetZ - player.getZ());
    int samples = Math.max(1, (int) Math.ceil(distance / ESCAPE_SAMPLE_STEP));
    List<Boolean> collisionFreeSamples = new ArrayList<>(samples);
    for (int sample = 1; sample <= samples; sample++) {
      double progress = (double) sample / samples;
      AABB box =
          bodyBoxAt(
              lerp(player.getX(), targetX, progress),
              lerp(player.getY(), targetY, progress),
              lerp(player.getZ(), targetZ, progress),
              player);
      collisionFreeSamples.add(level.noCollision(player, box));
      if (!hasSupportContactAt(
          level,
          (box.minX + box.maxX) * 0.5,
          box.minY,
          (box.minZ + box.maxZ) * 0.5,
          player.getBbWidth() * 0.5)) {
        return false;
      }
    }
    return collisionRunIsEscapable(collisionFreeSamples);
  }

  static boolean collisionRunIsEscapable(List<Boolean> collisionFreeSamples) {
    if (collisionFreeSamples == null || collisionFreeSamples.isEmpty()) {
      return false;
    }
    boolean becameClear = false;
    for (boolean collisionFree : collisionFreeSamples) {
      if (collisionFree) {
        becameClear = true;
      } else if (becameClear) {
        return false;
      }
    }
    return becameClear;
  }

  private static boolean hasSupportContactAt(
      Level level, double centerX, double feetY, double centerZ, double halfWidth) {
    double minX = centerX - halfWidth;
    double maxX = centerX + halfWidth;
    double minZ = centerZ - halfWidth;
    double maxZ = centerZ + halfWidth;
    int floorY = (int) Math.floor(feetY - SHAPE_EPSILON);
    int minBlockX = (int) Math.floor(minX + SHAPE_EPSILON);
    int maxBlockX = (int) Math.floor(maxX - SHAPE_EPSILON);
    int minBlockZ = (int) Math.floor(minZ + SHAPE_EPSILON);
    int maxBlockZ = (int) Math.floor(maxZ - SHAPE_EPSILON);

    VoxelShape supportingShapes = Shapes.empty();
    for (int blockX = minBlockX; blockX <= maxBlockX; blockX++) {
      for (int blockZ = minBlockZ; blockZ <= maxBlockZ; blockZ++) {
        BlockPos floor = new BlockPos(blockX, floorY, blockZ);
        VoxelShape shape =
            level.getBlockState(floor).getCollisionShape(level, floor).move(blockX, floorY, blockZ);
        supportingShapes = Shapes.or(supportingShapes, shape);
      }
    }
    return hasSupportContact(supportingShapes, minX, maxX, minZ, maxZ, feetY);
  }

  private static AABB bodyBoxAt(double centerX, double feetY, double centerZ, LocalPlayer player) {
    double halfWidth = player.getBbWidth() * 0.5;
    return new AABB(
        centerX - halfWidth,
        feetY,
        centerZ - halfWidth,
        centerX + halfWidth,
        feetY + player.getBbHeight(),
        centerZ + halfWidth);
  }

  private static boolean supportsFootprint(
      VoxelShape floorShape,
      double minX,
      double maxX,
      double minZ,
      double maxZ,
      double localFloorTop) {
    if (floorShape == null
        || floorShape.isEmpty()
        || localFloorTop <= SHAPE_EPSILON
        || localFloorTop > 1.0 + SHAPE_EPSILON) {
      return false;
    }
    double plateBottom = Math.max(0.0, localFloorTop - SHAPE_EPSILON);
    VoxelShape footprint = Shapes.box(minX, plateBottom, minZ, maxX, localFloorTop, maxZ);
    return !Shapes.joinIsNotEmpty(footprint, floorShape, BooleanOp.ONLY_FIRST);
  }

  private static double routeDistanceSquared(BlockPos feetCell, Vec3 routeTarget) {
    if (routeTarget == null) {
      return Double.POSITIVE_INFINITY;
    }
    return horizontalDistanceSquared(
        feetCell.getX() + 0.5, feetCell.getZ() + 0.5, routeTarget.x, routeTarget.z);
  }

  private static double horizontalDistanceSquared(double x1, double z1, double x2, double z2) {
    double dx = x1 - x2;
    double dz = z1 - z2;
    return dx * dx + dz * dz;
  }

  private static double lerp(double start, double end, double progress) {
    return start + (end - start) * progress;
  }

  private static double wrapDegrees(double degrees) {
    double wrapped = degrees % 360.0;
    if (wrapped >= 180.0) {
      wrapped -= 360.0;
    }
    if (wrapped < -180.0) {
      wrapped += 360.0;
    }
    return wrapped;
  }
}
