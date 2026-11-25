package ryzn.macro.client.feature.macros.impl.wheat;

import static ryzn.macro.client.MacroInput.releaseMovementKeys;
import static ryzn.macro.client.MacroInput.setAttackHeld;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import ryzn.macro.client.config.RyznSettings;
import ryzn.macro.client.core.RyznBootstrap;
import ryzn.macro.client.debug.DebugLog;
import ryzn.macro.client.feature.commands.impl.pathfinder.PathfindCommand;
import ryzn.macro.client.input.MacroKeys;

@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class WheatHarvestController {

  /** A key that keeps its state for a few ticks after changing, so it cannot flicker. */
  private static final class KeyLatch {
    private boolean held;
    private int lockTicks;

    boolean update(boolean want) {
      if (lockTicks > 0) {
        lockTicks--;
        return held;
      }
      if (want != held) {
        held = want;
        lockTicks = ThreadLocalRandom.current().nextInt(3, 7);
      }
      return held;
    }

    void reset() {
      held = false;
      lockTicks = 0;
    }
  }

  enum Action {
    CONTINUE,
    REPATH,
    ADVANCE_ZONE
  }

  record Result(Action action, String status) {
    static Result continueWith(String status) {
      return new Result(Action.CONTINUE, status);
    }
  }

  record Settings(
      boolean continuousMovement,
      boolean autoEquipHoe,
      boolean requireHoe,
      int scanRadius,
      int clusterRemainingThreshold,
      int moveSpeed,
      int sweepAmount,
      int sweepPitchOffset) {}

  private static final double LOOK_Y_OFFSET = 0.85;
  private static final int STUCK_TICKS = 80;
  private static final int CLUSTER_STUCK_TICKS = 30;
  private static final int MAX_ARRIVAL_HOPS = 4;
  private static final double SPRINT_FAR_SQ = 3.5 * 3.5;
  private static final double WALK_LOOK_DISTANCE = 2.6;
  private static final double STAND_LOOK_DISTANCE = 2.1;
  private static final double CROP_AIM_HEIGHT = 0.45;
  private static final double CORNER_BLEND_DISTANCE = 1.8;

  /** How far the walk leans toward the side being looked at; the rest is strafed away. */
  private static final float WEAVE_FRACTION = 0.3f;

  private static final float COMBO_HYSTERESIS = 10.0f;
  private static final int COMBO_W = 0;
  private static final int COMBO_WD = 1;
  private static final int COMBO_D = 2;
  private static final int COMBO_A = 3;
  private static final int COMBO_WA = 4;
  private static final int[] WALK_COMBOS = {COMBO_W, COMBO_WD, COMBO_D, COMBO_A, COMBO_WA};
  private static final int UNSTICK_AFTER_TICKS = 8;

  private final WheatLookMotion lookMotion = new WheatLookMotion();
  private final WheatSweepMotion sweepMotion = new WheatSweepMotion();
  private final KeyLatch sprintKey = new KeyLatch();
  private int heldCombo = COMBO_W;
  private int comboHoldTicks;
  private int collisionTicks;
  private int unstickTicks;
  private int unstickSide = 1;
  private final WheatSweepProgress sweepProgress = new WheatSweepProgress();
  private final WheatFieldRoute fieldRoute = new WheatFieldRoute();
  private final WheatClusterTracker clusterTracker = new WheatClusterTracker(fieldRoute);
  private final WheatInventory inventory;

  @Getter(AccessLevel.PACKAGE)
  private boolean zoneCaptured;

  private boolean preZoneInward;
  private boolean attackHeld;
  private boolean walkKeysHeld;
  private boolean sweeping;
  private BlockPos miningTarget;
  private BlockPos moveAnchorPos;

  int clustersRemaining() {
    return zoneCaptured ? clusterTracker.liveCount() : 0;
  }

  float clusterProgress() {
    return zoneCaptured ? clusterTracker.progress() : 0.0f;
  }

  boolean hasInputHeld() {
    return attackHeld || walkKeysHeld;
  }

  WheatLookMotion lookMotion() {
    return lookMotion;
  }

  void begin(ClientLevel level, BlockPos zoneCenter, Settings settings) {
    clearZone();
    fieldRoute.rebuild(
        level, zoneCenter, settings.scanRadius(), sweepLoopSpacing(settings.moveSpeed()));
    clusterTracker.captureInitial(level, zoneCenter, zoneRadiusSq());
    zoneCaptured = true;
  }

  void beginPreZone(ClientLevel level, BlockPos zoneCenter, Settings settings) {
    clearZone();
    fieldRoute.rebuild(
        level, zoneCenter, settings.scanRadius(), sweepLoopSpacing(settings.moveSpeed()));
    clusterTracker.captureInitial(level, zoneCenter, zoneRadiusSq());
    zoneCaptured = true;
    preZoneInward = true;
  }

  void reset() {
    clearZone();
    clearAim();
  }

  void clearZone() {
    fieldRoute.clear();
    sweepProgress.reset();
    clusterTracker.reset();
    moveAnchorPos = null;
    zoneCaptured = false;
    preZoneInward = false;
  }

  Result tick(
      Minecraft mc,
      LocalPlayer player,
      ClientLevel level,
      BlockPos zoneCenter,
      Settings settings,
      boolean playerInArrivalZone,
      boolean playerInLooseZone,
      boolean playerNearPreZone) {
    if (settings.autoEquipHoe()) {
      inventory.tryEquipHoe(mc, player);
    }
    if (settings.requireHoe() && !inventory.isHoldingHoe(player)) {
      releaseAttack(mc);
      releaseWalkKeys(mc.options);
      lookMotion.clearTarget();
      stopSweep();
      return Result.continueWith("Waiting for hoe");
    }
    if (!zoneCaptured) {
      fieldRoute.rebuild(
          level, zoneCenter, settings.scanRadius(), sweepLoopSpacing(settings.moveSpeed()));
      clusterTracker.captureInitial(level, zoneCenter, zoneRadiusSq());
      zoneCaptured = true;
    }
    if (fieldRoute.isEmpty()) {
      fieldRoute.rebuild(
          level, zoneCenter, settings.scanRadius(), sweepLoopSpacing(settings.moveSpeed()));
    }
    if (preZoneInward) {
      if (playerInArrivalZone || fieldRoute.playerOn(player)) {
        preZoneInward = false;
        sweepProgress.resetMovement();
      } else {
        return tickPreZone(mc, player, level, zoneCenter, settings, playerNearPreZone);
      }
    }
    if (fieldRoute.playerLeft(player, playerInLooseZone)) {
      return new Result(Action.REPATH, "Pathing to wheat zone");
    }
    clusterTracker.refresh(level, false, zoneCenter, zoneRadiusSq());
    if (clusterTracker.liveCount() <= settings.clusterRemainingThreshold()) {
      return new Result(Action.ADVANCE_ZONE, "Zone clear");
    }
    WheatClusterTracker.Cluster cluster = clusterTracker.selectSticky(player, sweepHeading(player));
    if (!settings.continuousMovement() || (cluster == null && !fieldRoute.hasWaypoints())) {
      return standAndSweep(mc, player, level, cluster, zoneCenter, settings, false);
    }
    // Reaching a bunch hands straight on to the next one instead of standing in it: the swing has
    // already passed over what was in reach on the way in, and the next pass picks up the rest.
    double arriveSq = sweepWaypointArriveSq(settings.moveSpeed());
    for (int hops = 0;
        cluster != null
            && hops < MAX_ARRIVAL_HOPS
            && horizontalDistanceSq(player, clusterTracker.centerVector(cluster)) <= arriveSq;
        hops++) {
      clusterTracker.markVisited(cluster);
      cluster = clusterTracker.selectSticky(player, sweepHeading(player));
    }
    boolean steering =
        cluster != null
            && horizontalDistanceSq(player, clusterTracker.centerVector(cluster)) > arriveSq;
    BlockPos anchorBlock;
    Vec3 moveTarget;
    Vec3 nextTarget = null;
    if (steering) {
      anchorBlock = cluster.center();
      moveTarget = clusterTracker.centerVector(cluster);
    } else {
      List<BlockPos> waypoints = fieldRoute.waypoints();
      int index = sweepProgress.currentIndex(waypoints.size());
      anchorBlock = waypoints.get(index);
      moveTarget = waypointVector(anchorBlock);
      if (waypoints.size() > 1) {
        nextTarget = waypointVector(waypoints.get((index + 1) % waypoints.size()));
      }
    }
    if (!anchorBlock.equals(moveAnchorPos)) {
      moveAnchorPos = anchorBlock;
      sweepProgress.resetMovement();
    }
    double distSq = horizontalDistanceSq(player, moveTarget);
    if (!steering && distSq <= arriveSq) {
      sweepProgress.advance(fieldRoute.waypoints().size());
      sweepProgress.resetMovement();
      return new Result(Action.CONTINUE, null);
    }
    if (sweepProgress.stalled(distSq, 0.02, steering ? CLUSTER_STUCK_TICKS : STUCK_TICKS)) {
      if (!steering) {
        return new Result(Action.REPATH, "Pathing to wheat zone");
      }
      // A bunch the walk cannot get closer to (a fence, a step it will not take) is dropped for
      // a while rather than pinning the body against it.
      clusterTracker.markVisited(cluster);
      sweepProgress.resetMovement();
    }
    walkAndSweep(mc, player, moveTarget, nextTarget, Math.sqrt(distSq), settings);
    boolean breaking = holdHarvestAttack(mc, level, zoneCenter);
    return Result.continueWith(harvestStatus(cluster, breaking, steering));
  }

  boolean shouldBeginPreZoneSweep(
      LocalPlayer player,
      ClientLevel level,
      BlockPos zoneCenter,
      Settings settings,
      boolean playerNearPreZone) {
    if (!playerNearPreZone) {
      return false;
    }
    if (fieldRoute.isEmpty()) {
      fieldRoute.rebuild(
          level, zoneCenter, settings.scanRadius(), sweepLoopSpacing(settings.moveSpeed()));
    }
    double reach = Math.max(3.0, player.blockInteractionRange());
    double reachSq = reach * reach;
    int radius = (int) Math.ceil(reach);
    Vec3 eye = player.getEyePosition();
    BlockPos origin = BlockPos.containing(player.getX(), player.getY(), player.getZ());
    BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    for (int dx = -radius; dx <= radius; dx++) {
      for (int dz = -radius; dz <= radius; dz++) {
        for (int dy = -2; dy <= 2; dy++) {
          cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
          if (isMatureWheat(level, cursor)
              && isActiveCrop(cursor, zoneCenter)
              && targetLookDistanceSqr(eye, cursor) <= reachSq) {
            return true;
          }
        }
      }
    }
    return false;
  }

  /**
   * Whether the harvest walk can take the body from here to the zone in a straight line: nothing
   * taller than a step in the way, no gap in the floor and no water. The harvest walk has no
   * pathfinding of its own, so taking the body off a route without this walked it into fences and
   * off ledges.
   */
  boolean hasClearWalk(LocalPlayer player, ClientLevel level, BlockPos zoneCenter) {
    Vec3 from = player.position();
    Vec3 to = zoneCenterVector(zoneCenter);
    double length = Math.sqrt(horizontalDistanceSq(player, to));
    int feetY = Mth.floor(player.getY() + 0.5);
    BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    for (double travelled = 0.4; travelled < length; travelled += 0.4) {
      double t = travelled / length;
      int x = Mth.floor(from.x + (to.x - from.x) * t);
      int z = Mth.floor(from.z + (to.z - from.z) * t);
      cursor.set(x, feetY, z);
      if (!level.getFluidState(cursor).isEmpty()) {
        return false;
      }
      double feetTop =
          level.getBlockState(cursor).getCollisionShape(level, cursor).max(Direction.Axis.Y);
      if (feetTop > 1.0) {
        return false; // a fence or wall, not a step
      }
      if (feetTop > 0.6) {
        cursor.set(x, feetY + 1, z);
        if (isSolid(level, cursor) || isSolid(level, cursor.set(x, feetY + 2, z))) {
          return false;
        }
        feetY++; // one block up, which the walk jumps
        continue;
      }
      if (isSolid(level, cursor.set(x, feetY + 1, z))) {
        return false;
      }
      if (!isSolid(level, cursor.set(x, feetY - 1, z))) {
        return false;
      }
    }
    return true;
  }

  private static boolean isSolid(ClientLevel level, BlockPos pos) {
    return !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
  }

  private void aimAtPoint(LocalPlayer player) {
    float[] aim = new float[2];
    ryzn.macro.client.feature.rotation.impl.MacroAim.desiredYawPitch(
        player.getEyePosition(), lookMotion.target(), aim);
    RyznBootstrap.rotationManager().aimTarget(this, player, aim[0], aim[1]);
  }

  /**
   * Wall-clock aim. Driven from the game tick (so the aim keeps turning while rendering is
   * throttled) and again from the render frame, which re-samples the same curve rather than lerping
   * between two tick samples. A point target (an NPC being walked to) wins over the harvest sweep.
   */
  void applyLookFrameTick(
      LocalPlayer player, int lookSpeed, double plannerSpeed, boolean harvesting) {
    if (!lookMotion.hasTarget()) {
      if (harvesting) {
        applySweepFrame(player, lookSpeed, plannerSpeed);
      } else {
        stopSweep();
      }
      return;
    }
    sweepMotion.reset();
    aimAtPoint(player);
  }

  private void applySweepFrame(LocalPlayer player, int lookSpeed, double plannerSpeed) {
    if (!sweeping) {
      return;
    }
    float[] target = sweepMotion.sampleTarget(player.getYRot(), player.getXRot(), lookSpeed);
    RyznBootstrap.rotationManager()
        .aimTarget(sweepMotion, player, target[0], target[1], plannerSpeed);
  }

  void releaseAttack(Minecraft mc) {
    ryzn.macro.client.MacroInput.releaseAttack(mc.options);
    if (attackHeld) {
      DebugLog.trace(DebugLog.Category.MACRO, "Wheat: stopped swinging");
    }
    attackHeld = false;
    miningTarget = null;
  }

  void releaseWalkKeys(Options options) {
    if (options == null) {
      walkKeysHeld = false;
      return;
    }
    releaseMovementKeys(options);
    walkKeysHeld = false;
    sprintKey.reset();
    heldCombo = COMBO_W;
    comboHoldTicks = 0;
    collisionTicks = 0;
    unstickTicks = 0;
  }

  void stop(boolean stopNavigation) {
    Minecraft mc = Minecraft.getInstance();
    releaseAttack(mc);
    releaseWalkKeys(mc.options);
    if (stopNavigation) {
      PathfindCommand.stopNavQuiet();
    }
    clearAim();
  }

  void clearAim() {
    RyznBootstrap.rotationManager().resetAim(this);
    lookMotion.reset();
    stopSweep();
    miningTarget = null;
  }

  private void stopSweep() {
    RyznBootstrap.rotationManager().resetAim(sweepMotion);
    sweeping = false;
    sweepMotion.reset();
  }

  private Result tickPreZone(
      Minecraft mc,
      LocalPlayer player,
      ClientLevel level,
      BlockPos zoneCenter,
      Settings settings,
      boolean playerNearPreZone) {
    if (!playerNearPreZone) {
      return new Result(Action.REPATH, "Pathing to wheat zone");
    }
    clusterTracker.refresh(level, false, zoneCenter, zoneRadiusSq());
    if (clusterTracker.liveCount() <= settings.clusterRemainingThreshold()) {
      return new Result(Action.ADVANCE_ZONE, "Zone clear");
    }
    Vec3 center = zoneCenterVector(zoneCenter);
    double distSq = horizontalDistanceSq(player, center);
    if (sweepProgress.stalled(distSq, 0.02, STUCK_TICKS)) {
      return new Result(Action.REPATH, "Pathing to wheat zone");
    }
    walkAndSweep(mc, player, center, null, Math.sqrt(distSq), settings);
    boolean breaking = holdHarvestAttack(mc, level, zoneCenter);
    return Result.continueWith(breaking ? "Pre-zone breaking" : "Moving into wheat zone");
  }

  /**
   * Stands still and swings across the crops in front, turning the body toward the densest wheat
   * still in reach - including behind - the way a player turns round in a patch.
   */
  private Result standAndSweep(
      Minecraft mc,
      LocalPlayer player,
      ClientLevel level,
      WheatClusterTracker.Cluster cluster,
      BlockPos zoneCenter,
      Settings settings,
      boolean steering) {
    releaseWalkKeys(mc.options);
    float heading = sweepHeading(player);
    double lookDistance = STAND_LOOK_DISTANCE;
    BlockPos wheat =
        clusterTracker.bestInCone(
            player, level, heading, settings.scanRadius(), 180.0f, zoneCenter, zoneRadiusSq());
    if (wheat != null) {
      Vec3 point = new Vec3(wheat.getX() + 0.5, player.getY(), wheat.getZ() + 0.5);
      double distance = Math.sqrt(horizontalDistanceSq(player, point));
      // Right underfoot the bearing is noise; the current heading already covers it.
      if (distance > 0.9) {
        heading = yawTo(player.position(), point);
        lookDistance = Mth.clamp(distance, 1.2, 3.5);
      }
    }
    startSweep(heading, cropPitch(player, lookDistance), settings);
    boolean breaking = holdHarvestAttack(mc, level, zoneCenter);
    return Result.continueWith(harvestStatus(cluster, breaking, steering));
  }

  /**
   * Walks toward {@code target} while swinging. Near the target the heading leans toward the next
   * one, so the walk curves through the waypoint instead of touching it and turning on the spot.
   */
  private void walkAndSweep(
      Minecraft mc,
      LocalPlayer player,
      Vec3 target,
      Vec3 next,
      double distance,
      Settings settings) {
    float walkYaw = yawTo(player.position(), target);
    if (next != null && distance < CORNER_BLEND_DISTANCE) {
      float nextYaw = yawTo(target, next);
      float blend = (float) (1.0 - distance / CORNER_BLEND_DISTANCE) * 0.6f;
      walkYaw += Mth.wrapDegrees(nextYaw - walkYaw) * blend;
    }
    startSweep(walkYaw, cropPitch(player, WALK_LOOK_DISTANCE), settings);
    applyWalkKeys(mc.options, player, walkYaw, distance, settings.moveSpeed());
  }

  private void startSweep(float heading, float pitch, Settings settings) {
    lookMotion.clearTarget();
    sweepMotion.setGoal(
        heading, Mth.clamp(pitch + settings.sweepPitchOffset(), -90f, 90f), settings.sweepAmount());
    sweeping = true;
  }

  /** The heading the swing is centred on, or the body yaw before the swing has started. */
  private float sweepHeading(LocalPlayer player) {
    return sweepMotion.heading(player.getYRot());
  }

  /**
   * Picks the key combination that walks the body where it should go given where the camera points
   * right now. While the view swings right the body keeps its line with W+A, while it swings left
   * with W+D - the strafe a player uses to sweep a row without wandering off it. The walk also
   * weaves a little toward the side being looked at, so the strafe follows the sway.
   *
   * <p>Holding plain W through the swing (the previous version) steered the body up to the full
   * swing angle off the line, which is what walked it into fences and hedges.
   */
  private void applyWalkKeys(
      Options options, LocalPlayer player, float walkYaw, double distance, int moveSpeed) {
    if (options == null) {
      return;
    }
    float camera = player.getYRot();
    float swing = Mth.wrapDegrees(camera - sweepHeading(player));
    float desired = walkYaw + swing * WEAVE_FRACTION;
    if (unstickTicks > 0) {
      unstickTicks--;
      desired += unstickSide * 90.0f;
    }
    float relative = Mth.wrapDegrees(desired - camera);
    int combo = walkCombo(relative);
    boolean forward = combo == COMBO_W || combo == COMBO_WA || combo == COMBO_WD;
    boolean left = combo == COMBO_A || combo == COMBO_WA;
    boolean right = combo == COMBO_D || combo == COMBO_WD;
    boolean far = distance * distance > SPRINT_FAR_SQ;
    boolean sprint =
        sprintKey.update(
            forward
                && unstickTicks == 0
                && (far
                    || RyznSettings.get().pathfinder().isSprint()
                        && Math.abs(Mth.wrapDegrees(walkYaw - sweepHeading(player)))
                            <= sweepSprintCone(moveSpeed)));
    MacroKeys.set(options.keyUp, forward);
    MacroKeys.set(options.keyDown, false);
    MacroKeys.set(options.keyLeft, left);
    MacroKeys.set(options.keyRight, right);
    MacroKeys.set(options.keyJump, shouldJump(player));
    MacroKeys.set(options.keyShift, false);
    MacroKeys.set(options.keySprint, sprint);
    walkKeysHeld = true;
  }

  /**
   * Nearest of W, W+D, D, A, W+A to {@code relative} degrees off the camera, kept for a few ticks
   * and past a margin so a key never flickers on the boundary between two combinations.
   */
  private int walkCombo(float relative) {
    int best = COMBO_W;
    float bestError = Float.MAX_VALUE;
    for (int combo : WALK_COMBOS) {
      float error = Math.abs(Mth.wrapDegrees(relative - comboAngle(combo)));
      if (error < bestError) {
        bestError = error;
        best = combo;
      }
    }
    if (comboHoldTicks > 0) {
      comboHoldTicks--;
      return heldCombo;
    }
    float heldError = Math.abs(Mth.wrapDegrees(relative - comboAngle(heldCombo)));
    if (best != heldCombo && heldError > 22.5f + COMBO_HYSTERESIS) {
      heldCombo = best;
      comboHoldTicks = ThreadLocalRandom.current().nextInt(2, 5);
    }
    return heldCombo;
  }

  private static float comboAngle(int combo) {
    return switch (combo) {
      case COMBO_WD -> 45.0f;
      case COMBO_D -> 90.0f;
      case COMBO_A -> -90.0f;
      case COMBO_WA -> -45.0f;
      default -> 0.0f;
    };
  }

  /**
   * Jump a step or hedge edge; if the body stays pinned against something anyway, side-step for a
   * moment, alternating sides, instead of pressing into it until the stall check repaths.
   */
  private boolean shouldJump(LocalPlayer player) {
    if (!player.horizontalCollision) {
      collisionTicks = 0;
      return false;
    }
    if (++collisionTicks > UNSTICK_AFTER_TICKS && unstickTicks == 0) {
      collisionTicks = 0;
      unstickSide = -unstickSide;
      unstickTicks = ThreadLocalRandom.current().nextInt(6, 11);
    }
    return player.onGround();
  }

  /** Pitch that puts the crosshair on the wheat {@code distance} blocks ahead of the feet. */
  private static float cropPitch(LocalPlayer player, double distance) {
    double drop = player.getEyeY() - (player.getY() + CROP_AIM_HEIGHT);
    return (float) Math.toDegrees(Math.atan2(drop, distance));
  }

  private boolean holdHarvestAttack(Minecraft mc, ClientLevel level, BlockPos zoneCenter) {
    if (mc.options != null) {
      setAttackHeld(mc.options, true);
    }
    if (!attackHeld) {
      DebugLog.trace(DebugLog.Category.MACRO, "Wheat: swinging");
    }
    attackHeld = true;
    if (mc.gameMode == null) {
      return false;
    }
    HitResult hit = mc.hitResult;
    if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) {
      return false;
    }
    BlockPos hitPos = blockHit.getBlockPos();
    if (!isActiveCrop(hitPos, zoneCenter) || !isMatureWheat(level, hitPos)) {
      return false;
    }
    if (!hitPos.equals(miningTarget)) {
      miningTarget = hitPos;
      DebugLog.detail(
          DebugLog.Category.MACRO,
          "[wheat] breaking at %d %d %d",
          hitPos.getX(),
          hitPos.getY(),
          hitPos.getZ());
    }
    return true;
  }

  private boolean isActiveCrop(BlockPos position, BlockPos zoneCenter) {
    double dx = position.getX() - zoneCenter.getX();
    double dz = position.getZ() - zoneCenter.getZ();
    return dx * dx + dz * dz <= zoneRadiusSq() || fieldRoute.containsCrop(position);
  }

  private static String harvestStatus(
      WheatClusterTracker.Cluster cluster, boolean breaking, boolean steering) {
    if (steering && cluster != null) {
      return breaking ? "Clearing wheat bunch (" + cluster.size() + ")" : "Approaching wheat bunch";
    }
    if (cluster != null) {
      return breaking ? "Breaking cluster wheat" : "Sweeping to clusters";
    }
    return breaking ? "Breaking stray wheat" : "Sweeping for wheat";
  }

  private static boolean isMatureWheat(ClientLevel level, BlockPos position) {
    BlockState state = level.getBlockState(position);
    return state.is(Blocks.WHEAT)
        && state.getBlock() instanceof CropBlock crop
        && crop.isMaxAge(state);
  }

  private static Vec3 waypointVector(BlockPos position) {
    return new Vec3(position.getX() + 0.5, position.getY() + 1.0, position.getZ() + 0.5);
  }

  private static Vec3 zoneCenterVector(BlockPos center) {
    return new Vec3(center.getX() + 0.5, center.getY(), center.getZ() + 0.5);
  }

  private static float yawTo(Vec3 from, Vec3 to) {
    return (float) Math.toDegrees(Math.atan2(-(to.x - from.x), to.z - from.z));
  }

  private static double targetLookDistanceSqr(Vec3 eye, BlockPos target) {
    double dx = target.getX() + 0.5 - eye.x;
    double dy = target.getY() + LOOK_Y_OFFSET - eye.y;
    double dz = target.getZ() + 0.5 - eye.z;
    return dx * dx + dy * dy + dz * dz;
  }

  private static double horizontalDistanceSq(LocalPlayer player, Vec3 target) {
    double dx = target.x - player.getX();
    double dz = target.z - player.getZ();
    return dx * dx + dz * dz;
  }

  private static double sweepWaypointArriveSq(int moveSpeed) {
    double radius =
        switch (moveSpeed) {
          case 1 -> 0.55;
          case 2 -> 0.72;
          case 3 -> 0.9;
          case 4 -> 1.08;
          default -> 1.25;
        };
    return radius * radius;
  }

  private static int sweepLoopSpacing(int moveSpeed) {
    return switch (moveSpeed) {
      case 1, 2 -> 1;
      default -> 2;
    };
  }

  private static float sweepSprintCone(int moveSpeed) {
    return switch (moveSpeed) {
      case 1 -> 0.0f;
      case 2 -> 12.0f;
      case 3 -> 20.0f;
      case 4 -> 28.0f;
      default -> 35.0f;
    };
  }

  private static double zoneRadiusSq() {
    return 5.0 * 5.0;
  }
}
