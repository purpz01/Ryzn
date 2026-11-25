package ryzn.macro.client.feature.pathfinder.path;

import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import ryzn.macro.client.config.RyznSettings;
import ryzn.macro.client.config.groups.WalkingSettings.Control;
import ryzn.macro.client.core.RyznBootstrap;
import ryzn.macro.client.debug.DebugLog;
import ryzn.macro.client.feature.modules.impl.combat.CombatModule;
import ryzn.macro.client.feature.modules.impl.combat.RecordedCombatMovement;
import ryzn.macro.client.feature.rotation.impl.AngleUtil;

/** Selects vanilla movement key mappings while keeping corridor and combat policies cohesive. */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class PathMovementController {
  private static final double NEAR_TARGET_RANGE = 4.0;

  /**
   * Ticks the back key may run before it stops being a transition; see
   * MovementGate#isBackstepTravel.
   */
  private static final int BACKSTEP_MAX_TICKS = 10;

  /** The same cap while a macro holds the head: no turn is coming, so only allow a brief settle. */
  private static final int BACKSTEP_HELD_LOOK_TICKS = 2;

  private static final float WIDE_FACING_GATE_DEGREES = 135f;
  // Deliberately contact range. The probes cast at feet+0.35 and feet+1.0, so anything longer
  // starts
  // reporting ordinary terrain the route means to walk past - step risers, ledge lips, a trunk a
  // block to
  // the side - as a wall. A speed-scaled version of this (0.45 + speed*4) was tried and reverted:
  // at sprint
  // it reached ~1.57 and left the gates true most of the time, which strafed the body off its own
  // path.
  private static final double SIDE_PROBE_RANGE = 0.42;
  private static final double AHEAD_PROBE_RANGE = 0.65;
  private static final double CORRIDOR_AHEAD_MIN = 0.05;

  /**
   * Ticks a corridor probe must hold its new reading before it is believed. See {@link StickyGate}.
   */
  private static final int PROBE_HOLD_TICKS = 3;

  private static final float STRAFE_ENGAGE_BIAS_DEGREES = 6.25f;
  private static final int STRAFE_HOLD_TICKS = 3;
  private static final double MOMENTUM_BEARING_MIN_SPEED = 0.05;

  private final Minecraft client;
  private final PathSpatialQueries spatialQueries;
  // The three corridor probes are short raycasts (0.42 / 0.65) cast from a body that is rubbing the
  // very
  // block they graze, so read clean they flip every tick and add/strip W and A/D with them.
  private final StickyGate leftWallGate = new StickyGate(PROBE_HOLD_TICKS, false);
  private final StickyGate rightWallGate = new StickyGate(PROBE_HOLD_TICKS, false);
  private final StickyGate aheadClearGate = new StickyGate(PROBE_HOLD_TICKS, true);
  private boolean movementBlocked;

  /**
   * Consecutive ticks the node bearing has SELECTED the back key, counted even while it is being
   * dropped - otherwise suppressing it would reset the run and let S back in every other tick.
   */
  private int backstepTicks;

  private int routeStrafe;
  private int routeStrafeTicks;

  List<KeyMapping> select(
      Vec3 playerPosition,
      Vec3 targetPosition,
      float aimGap,
      double targetRange,
      boolean crouching,
      boolean recordedWalk) {
    final CombatModule combat = RyznBootstrap.moduleManager().getByClass(CombatModule.class);
    final boolean recordedCombat = combat.isCombatNavActive() && combat.usesRecordedMovement();
    List<KeyMapping> keys =
        new ArrayList<>(KeyBindUtil.getNeededKeyPresses(playerPosition, targetPosition));
    float bodyAngle = bodyRelativeAngle(playerPosition, targetPosition);

    if (recordedCombat) {
      keys.clear();
      KeyBindUtil.selectAxisKeys(keys, bodyAngle, 0.25);
      if (client.player.onGround() && !crouching && keys.contains(client.options.keyUp)) {
        stabilizeRouteStrafe(
            keys,
            bodyAngle,
            momentumBearing(playerPosition, targetPosition, bodyAngle),
            (float) RecordedCombatMovement.PATH_STRAFE_DEADZONE_DEG,
            4f);
      } else {
        routeStrafe = 0;
        routeStrafeTicks = 0;
      }
    } else {
      stabilizeRouteStrafe(keys, bodyAngle, recordedWalk);
    }

    preserveHeldInteractionKeys(keys);
    biasInCorridor(keys, bodyAngle);

    boolean canMove =
        MovementGate.shouldAllowMovement(
            targetRange, aimGap, NEAR_TARGET_RANGE, WIDE_FACING_GATE_DEGREES);
    if (!canMove) {
      if (!movementBlocked) {
        log(
            "Holding still until the head turns to face the route (off by %.1f deg, limit %.1f)",
            aimGap, WIDE_FACING_GATE_DEGREES);
        movementBlocked = true;
      }
      keys.clear();
      preserveHeldInteractionKeys(keys);
    } else if (movementBlocked) {
      log("Facing the route again - walking (off by %.1f deg)", aimGap);
      movementBlocked = false;
    }

    // Combat only. The Chill Hunt deliberately does NOT opt in: it holds the head on its Chill
    // while the
    // route drives the body, and the strafes that falls out of are the point - a person walking a
    // line while
    // watching something off to one side sidesteps, they do not swivel and march straight at it.
    if (combat.isCombatNavActive() && !combat.usesRecordedMovement()) {
      applyCombatForwardPolicy(keys, bodyAngle, combat.prefersForwardArc());
    }
    // Last, so nothing downstream can put the back key back. The natural S -> S+A -> A -> A+W -> W
    // blend
    // through a turn is left alone; only a RUN of S is dropped, which is the moonwalk. See
    // MovementGate#isBackstepTravel.
    //
    // The whole allowance is "the head is mid-turn, let the body blend through S". When a macro
    // OWNS the
    // head (the Chill Hunt watching its Chill) the head is never coming round, so there is no turn
    // to blend and
    // the allowance buys nothing but half a second of walking a route backwards - the "backsteps
    // then walks
    // forward again" on a repath. Cut it to a couple of ticks of settle there.
    final boolean wantsBack = keys.contains(client.options.keyDown);
    backstepTicks = wantsBack ? backstepTicks + 1 : 0;
    int backstepCap =
        RyznBootstrap.rotationManager().externalOwnsLook()
            ? BACKSTEP_HELD_LOOK_TICKS
            : BACKSTEP_MAX_TICKS;
    // A crouched body backing onto a spot is slow and deliberate, not the moonwalk the cap is for,
    // and with the head held elsewhere it is often the only key that points at the target.
    if (!crouching
        && !(recordedCombat && combat.ownsLook())
        && MovementGate.isBackstepTravel(wantsBack, backstepTicks, backstepCap)) {
      keys.remove(client.options.keyDown);
    }
    return keys;
  }

  private void stabilizeRouteStrafe(List<KeyMapping> keys, float bearing, boolean recordedWalk) {
    final double threshold = RyznSettings.get().pathfinder().strafeBlendThreshold();
    if (!recordedWalk || !keys.contains(client.options.keyUp) || threshold >= 0.5) {
      routeStrafe = 0;
      routeStrafeTicks = 0;
      return;
    }
    var walking = RyznSettings.get().pathfinder().walking();
    float engage = walking.get(Control.STRAFE_ENGAGE);
    if (engage == 0) {
      engage = Math.min(45f, (float) (90.0 * threshold) + STRAFE_ENGAGE_BIAS_DEGREES);
    }
    float release = engage * walking.get(Control.STRAFE_RELEASE) / 100f;
    stabilizeRouteStrafe(keys, bearing, bearing, engage, release);
  }

  /**
   * {@code bearing} decides which side the goal is on; {@code motion} is the same goal bearing from
   * the current heading of travel, which releases the strafe once momentum already carries the body
   * at the goal.
   */
  private void stabilizeRouteStrafe(
      List<KeyMapping> keys, float bearing, float motion, float engage, float release) {
    int previousStrafe = routeStrafe;
    int side = bearing > 0f ? -1 : 1;
    if (Math.abs(bearing) >= engage && -side * motion > release) {
      routeStrafe = side;
    } else if (routeStrafe == -1 && motion <= 0f || routeStrafe == 1 && motion >= 0f) {
      routeStrafe = 0;
    } else if (routeStrafeTicks >= STRAFE_HOLD_TICKS
        && (routeStrafe == -1 && motion < release || routeStrafe == 1 && motion > -release)) {
      routeStrafe = 0;
    }
    routeStrafeTicks =
        routeStrafe == 0 ? 0 : routeStrafe == previousStrafe ? routeStrafeTicks + 1 : 1;
    keys.remove(client.options.keyLeft);
    keys.remove(client.options.keyRight);
    if (routeStrafe != 0) {
      keys.add(routeStrafe == -1 ? client.options.keyLeft : client.options.keyRight);
    }
  }

  /**
   * Clear debounced probe + backstep state so a fresh route never inherits the last one's readings.
   */
  void reset() {
    leftWallGate.reset(false);
    rightWallGate.reset(false);
    aheadClearGate.reset(true);
    backstepTicks = 0;
    routeStrafe = 0;
    routeStrafeTicks = 0;
    movementBlocked = false;
  }

  /**
   * Goal bearing measured from where the body is already moving. With the head held off the route,
   * plain W is itself angled away from the goal, so a facing-relative bearing keeps the strafe held
   * until the body has crossed well past the line.
   */
  private float momentumBearing(Vec3 playerPosition, Vec3 targetPosition, float facingBearing) {
    Vec3 velocity = client.player.getDeltaMovement();
    if (Math.hypot(velocity.x, velocity.z) < MOMENTUM_BEARING_MIN_SPEED) {
      return facingBearing;
    }
    float heading = (float) Math.toDegrees(Math.atan2(-velocity.x, velocity.z));
    return relativeAngle(playerPosition, targetPosition, heading);
  }

  float bodyRelativeAngle(Vec3 playerPosition, Vec3 targetPosition) {
    return relativeAngle(playerPosition, targetPosition, client.player.getYRot());
  }

  static float relativeAngle(Vec3 playerPosition, Vec3 targetPosition, float playerYaw) {
    double deltaX = playerPosition.x - targetPosition.x;
    double deltaZ = playerPosition.z - targetPosition.z;
    float targetYaw = (float) Math.toDegrees(Math.atan2(deltaX, -deltaZ));
    return AngleUtil.normalizeAngle(targetYaw - playerYaw) * -1;
  }

  private void preserveHeldInteractionKeys(List<KeyMapping> keys) {
    if (client.options.keyUse.isDown()) {
      addOnce(keys, client.options.keyUse);
    }
    if (client.options.keyAttack.isDown()) {
      addOnce(keys, client.options.keyAttack);
    }
  }

  private void applyCombatForwardPolicy(
      List<KeyMapping> keys, float bodyAngleDegrees, boolean forwardArc) {
    keys.remove(client.options.keyDown);
    // A flowing hunt keeps walking through the turn, so the head bends the path into an arc.
    if (forwardArc || Math.abs(bodyAngleDegrees) <= CombatModule.COMBAT_FORWARD_FOV_DEG) {
      addOnce(keys, client.options.keyUp);
    } else {
      keys.remove(client.options.keyUp);
    }
  }

  private void biasInCorridor(List<KeyMapping> keys, float bodyAngleDegrees) {
    if (client.player == null || client.level == null) {
      return;
    }

    boolean back = keys.contains(client.options.keyDown);
    boolean left = keys.contains(client.options.keyLeft);
    boolean right = keys.contains(client.options.keyRight);
    if (back || Math.cos(Math.toRadians(bodyAngleDegrees)) <= CORRIDOR_AHEAD_MIN) {
      return;
    }

    boolean leftWall =
        leftWallGate.update(spatialQueries.blockedAtYawOffset(90f, SIDE_PROBE_RANGE));
    boolean rightWall =
        rightWallGate.update(spatialQueries.blockedAtYawOffset(-90f, SIDE_PROBE_RANGE));
    boolean aheadClear =
        aheadClearGate.update(!spatialQueries.blockedAtYawOffset(0f, AHEAD_PROBE_RANGE));

    // Drop the blocked strafe and let the forward add below carry the body ALONG the wall. Actively
    // pushing off it instead (press right when the left probe trips) was tried and reverted: it
    // shoves
    // the body off its own planned line, which lands it at edges the route never planned for and
    // fires
    // the anti-stuck it was meant to prevent.
    if (left && leftWall && !right) {
      keys.remove(client.options.keyLeft);
      left = false;
    }
    if (right && rightWall && !left) {
      keys.remove(client.options.keyRight);
      right = false;
    }

    // A plain strafe is left alone: the head looks down the route, so stepping sideways onto the
    // path is how the body follows it, and adding W there pulled it diagonally off the line.
    boolean bothWalls = leftWall && rightWall;
    boolean wallPinned = (left && leftWall) || (right && rightWall);
    if (aheadClear && (bothWalls || wallPinned || !hasMoveKey(keys))) {
      addOnce(keys, client.options.keyUp);
      keys.remove(client.options.keyDown);
    }
  }

  private boolean hasMoveKey(List<KeyMapping> keys) {
    return keys.contains(client.options.keyUp)
        || keys.contains(client.options.keyDown)
        || keys.contains(client.options.keyLeft)
        || keys.contains(client.options.keyRight);
  }

  private static void addOnce(List<KeyMapping> keys, KeyMapping key) {
    if (key != null && !keys.contains(key)) {
      keys.add(key);
    }
  }

  private static void log(String message, Object... parts) {
    DebugLog.trace(DebugLog.Category.NAV, message, parts);
  }
}
