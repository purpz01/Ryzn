package ryzn.macro.client.feature.modules.impl.combat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntSupplier;
import lombok.AccessLevel;
import lombok.Getter;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import ryzn.macro.client.MacroInput;
import ryzn.macro.client.feature.pathfinder.path.KeyBindUtil;
import ryzn.macro.client.input.MacroKeys;

/**
 * The body: everything that decides which movement keys are held during a fight, and holds them.
 *
 * <p>Three jobs that were four files and a slice of {@link CombatModule}, all reading each other's
 * state:
 *
 * <ul>
 *   <li><b>The chase</b> ({@link #chase}) - close-range pursuit. When the mob is near, the A*
 *       pathfinder is the wrong tool: it plans a short route, the executor reaches its end and
 *       parks, and every time the mob shuffles a block the route is torn down and recomputed
 *       (releasing the keys each time), so the body stutters in place. This presses the vanilla
 *       keys toward the mob every tick instead.
 *   <li><b>The boss stutter-step</b> ({@link #bossStep}) - crouched back-and-forth footwork for a
 *       pinned mini-boss, which probes for a wall before committing to a direction.
 *   <li><b>The keys themselves</b> ({@link #walkToward}, {@link #creepForward}, {@link
 *       #releaseKeys}) - the cardinal-key primitives, ported from V5's {@code
 *       Keybind.setKeysForStraightLineCoords}.
 * </ul>
 *
 * <p>The head is aimed separately by {@link CombatAim}, so the direction the body walks and the
 * direction it looks are decided independently and often disagree mid-turn. That is the source of
 * most of the rules here.
 *
 * <p>Anti-detection: motion is produced ONLY by holding vanilla movement keybinds (the same {@link
 * KeyBindUtil} the pathfinder uses); no packet is crafted, blocked, or delayed.
 */
final class CombatBody {

  // ---- chase band ----
  private static final double STUCK_SPEED = 0.05;
  private static final int WALL_HOP_TICKS = 3;
  private static final int STALL_HOP_TICKS = 7;
  private static final int HOP_COOLDOWN_TICKS = 3;
  private static final double MELEE_EDGE_STOP = 0.45;
  private static final double MELEE_EDGE_RESUME = 0.85;
  private static final double MELEE_BRAKE_TICKS = 2.0;
  private static final double DROP_JUMP_DELTA_Y = -3.0;
  private static final double HOLD_SAME_LEVEL_DELTA_Y = 1.5;
  private static final double ROTATE_CREEP_MAX_YAW_DEGREES = 125.0;
  private static final int ROTATE_CREEP_MOVE_TICKS = 3;
  private static final int ROTATE_CREEP_HOLD_TICKS = 2;

  // ---- keys ----
  /**
   * Close-range strafe deadzone: inside melee, lateral errors under this many degrees press no side
   * key, so the bot holds its ground instead of side-gliding while the camera is locked on a
   * point-blank mob. Wide on purpose.
   */
  private static final double MELEE_STRAFE_DEADZONE_DEG = 65.0;

  /**
   * Closing speed (blocks/tick) above which an airborne body is already carrying itself to the mob,
   * so forward can be released and the air input spent on steering instead. Roughly a walking pace,
   * so a hop taken from a standstill still gets forward held.
   */
  private static final double AIR_COAST_SPEED = 0.10;

  // ---- boss footwork ----
  private static final double BOSS_MOVEMENT_PROBE = 0.55;
  private static final double BOSS_RANGE_HOLD_MARGIN = 0.25;
  private static final int BOSS_RETREAT_MOVE_TICKS = 2;
  private static final int BOSS_RETREAT_HOLD_TICKS = 3;

  private final Hop hop = new Hop();
  private final KillPause killPause = new KillPause();

  @Getter(AccessLevel.PACKAGE)
  private boolean active;

  /**
   * Strike-once flow: never brake except when close and the hit cannot land yet, and then only
   * until it can. Set by the module each tick before {@link #chase}.
   */
  boolean stopOnlyToHit;

  boolean recordedMovement;

  @Getter(AccessLevel.PACKAGE)
  private boolean bodyMoving;

  private int rotateCreepTicks;
  private int bossRetreatTicks;
  private boolean bossDodgeRight = true;

  /** Keys held for one tick, plus the debug label {@code /dev debug} prints. */
  record CommandState(String keys, boolean sprint) {
    private static final CommandState STOPPED = new CommandState("none", false);
  }

  // ------------------------------------------------------------------ the chase

  void chase(
      LocalPlayer player,
      LivingEntity target,
      double attackRange,
      boolean oneTap,
      double yawError,
      boolean visible,
      double flowChaseRange,
      CombatTelemetry telemetry) {
    active = true;
    hop.tickCooldown();

    final double distance = player.distanceTo(target);
    double deltaY = target.getY() - player.getY();
    double gap = horizontalHitboxGap(player, target);
    double speed = Math.hypot(player.getDeltaMovement().x, player.getDeltaMovement().z);
    boolean sameLevel = Math.abs(deltaY) <= HOLD_SAME_LEVEL_DELTA_Y;
    final boolean grounded = player.onGround();

    UUID targetId = target.getUUID();
    if (killPause.selectTarget(targetId)) {
      rotateCreepTicks = 0;
    }
    boolean hittable = CombatAim.isHittable(player, target, attackRange);
    double brakingDistance = speed * MELEE_BRAKE_TICKS;
    double stopGap = bodyMoving ? MELEE_EDGE_STOP + brakingDistance : MELEE_EDGE_RESUME;
    boolean inBand = sameLevel && (hittable || gap <= stopGap);
    if (!inBand && !recordedMovement) {
      killPause.rearmIfOutsideBand(gap, MELEE_EDGE_RESUME);
    }

    boolean mustStopToHit = stopOnlyToHit && sameLevel && gap <= stopGap && !hittable;
    double holdGap = gap;
    if (recordedMovement) {
      holdGap = distance;
      stopGap =
          bodyMoving ? RecordedCombatMovement.STOP_RANGE : RecordedCombatMovement.RESUME_RANGE;
      if (bodyMoving && hittable) {
        stopGap += Math.min(0.25, brakingDistance);
      }
      inBand = sameLevel && visible && hittable && distance <= stopGap;
      if (!inBand) {
        killPause.rearmIfOutsideBand(distance, RecordedCombatMovement.RESUME_RANGE);
      }
      mustStopToHit = false;
    }
    // The pause holds movement; attack readiness remains independent.
    boolean microStop = stopOnlyToHit && inBand && !killPause.isSpent();
    CombatApproach.Hold hold =
        CombatApproach.hold(
            recordedMovement ? sameLevel && visible && hittable : sameLevel,
            recordedMovement ? inBand : hittable,
            holdGap,
            recordedMovement && !visible ? 0 : stopGap,
            !killPause.isSpent(),
            oneTap && !mustStopToHit && !microStop);
    if (hold == CombatApproach.Hold.PAUSE_BEFORE_SWING) {
      killPause.tick(
          () ->
              ThreadLocalRandom.current()
                  .nextInt(
                      CombatTuning.PAUSE_BEFORE_SWING_MIN_TICKS,
                      CombatTuning.PAUSE_BEFORE_SWING_MAX_TICKS + 1));
    }
    final String holdReason = hold.name().toLowerCase(Locale.ROOT);
    if (hold.stops()) {
      hop.clearObstruction();
      rotateCreepTicks = 0;
      if (bodyMoving) {
        releaseKeys();
        bodyMoving = false;
      }
      telemetry.observeMovement("none", false);
      telemetry.observeHold(holdReason);
      return;
    }
    telemetry.observeHold(holdReason);

    double absoluteYawError = Math.abs(yawError);
    telemetry.observeAim(absoluteYawError, visible);
    if (absoluteYawError > CombatModule.COMBAT_FORWARD_FOV_DEG && visible) {
      hop.clearObstruction();
      // A flowing hunt never stands to turn: it walks on and lets the turn bend the path.
      if (!recordedMovement && stopOnlyToHit && grounded
          || shouldCreepWhileRotating(
              grounded && !recordedMovement,
              distance,
              gap,
              sameLevel,
              absoluteYawError,
              flowChaseRange)) {
        bodyMoving = true;
        CommandState command = creepForward(false);
        telemetry.observeMovement(command.keys(), command.sprint());
        telemetry.observeHold("rotate-creep");
        return;
      }
      if (bodyMoving) {
        releaseKeys();
        bodyMoving = false;
      }
      telemetry.observeMovement("rotate", false);
      telemetry.observeHold("rotate");
      return;
    }

    rotateCreepTicks = 0;
    bodyMoving = true;
    boolean colliding = grounded && player.horizontalCollision;
    boolean lowSpeed = grounded && speed < STUCK_SPEED;

    // Look at what is actually in the way instead of hopping at everything. A step is clear one
    // block up,
    // so it gets hopped; a wall is not, so it gets gone around, toward whichever side is genuinely
    // open,
    // preferring the side the mob is on. A step in a corner gets both.
    CombatObstacle.Escape escape =
        colliding
            ? CombatObstacle.probe(player, yawError >= 0 ? 1 : -1)
            : CombatObstacle.Escape.NONE;

    // Dropping to a mob well below is a hop for momentum, not an obstruction, so it stands outside
    // the probe.
    boolean dropHop = !recordedMovement && deltaY < DROP_JUMP_DELTA_Y;
    // Wedged on something the block probe cannot see (an entity, a fence corner): the stall streak
    // still
    // earns a speculative hop as a last resort.
    boolean jump =
        hop.requestJump(
            grounded,
            escape.hop() && !recordedMovement,
            lowSpeed && !recordedMovement,
            dropHop,
            WALL_HOP_TICKS,
            STALL_HOP_TICKS,
            HOP_COOLDOWN_TICKS);
    boolean closeMelee = distance <= attackRange || gap <= MELEE_EDGE_RESUME + brakingDistance;
    CommandState command =
        walkToward(
            player,
            target.getX(),
            target.getZ(),
            !closeMelee && (!recordedMovement || distance > RecordedCombatMovement.SPRINT_RANGE),
            jump,
            closeMelee,
            escape.sidestep(),
            recordedMovement);
    // Show WHY it moved that way, so /dev debug can tell a deliberate hop from a wall slide.
    telemetry.observeMovement(command.keys() + describe(escape), command.sprint());
  }

  /** Stop chasing and release the keys, but only if we were the ones holding them. */
  void stop() {
    if (!active) {
      return;
    }
    releaseKeys();
    active = false;
    bodyMoving = false;
    hop.reset();
    rotateCreepTicks = 0;
  }

  void reset() {
    recordedMovement = false;
    active = false;
    bodyMoving = false;
    hop.reset();
    rotateCreepTicks = 0;
    killPause.reset();
    bossRetreatTicks = 0;
    bossDodgeRight = true;
  }

  void clearObstruction() {
    hop.clearObstruction();
  }

  /**
   * Closest horizontal distance between the two hitboxes - 0 while they overlap, unlike centre
   * distance.
   */
  static double horizontalHitboxGap(LocalPlayer player, LivingEntity target) {
    AABB playerBox = player.getBoundingBox();
    AABB targetBox = target.getBoundingBox();
    double deltaX =
        Math.max(0.0, Math.max(playerBox.minX - targetBox.maxX, targetBox.minX - playerBox.maxX));
    double deltaZ =
        Math.max(0.0, Math.max(playerBox.minZ - targetBox.maxZ, targetBox.minZ - playerBox.maxZ));
    return Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
  }

  /** Debug suffix naming what the obstruction was: a step it hopped, or a wall it slid around. */
  private static String describe(CombatObstacle.Escape escape) {
    if (!escape.any()) {
      return "";
    }
    final StringBuilder out = new StringBuilder(" [");
    if (escape.hop()) {
      out.append("step");
    }
    if (escape.sidestep() != 0) {
      if (escape.hop()) {
        out.append('+');
      }
      out.append("wall>").append(escape.sidestep() > 0 ? 'R' : 'L');
    }
    return out.append(']').toString();
  }

  private static String describe(Minecraft mc, List<KeyMapping> keys) {
    final StringBuilder out = new StringBuilder();
    append(out, keys, mc.options.keyUp, "W");
    append(out, keys, mc.options.keyLeft, "A");
    append(out, keys, mc.options.keyDown, "S");
    append(out, keys, mc.options.keyRight, "D");
    append(out, keys, mc.options.keyJump, "JUMP");
    append(out, keys, mc.options.keySprint, "SPRINT");
    return out.length() == 0 ? "none" : out.toString();
  }

  private boolean shouldCreepWhileRotating(
      boolean grounded,
      double distance,
      double gap,
      boolean sameLevel,
      double yawError,
      double flowChaseRange) {
    if (!grounded) {
      rotateCreepTicks = 0;
      return false;
    }
    if (!sameLevel
        || distance > flowChaseRange
        || gap <= MELEE_EDGE_RESUME
        || yawError > ROTATE_CREEP_MAX_YAW_DEGREES) {
      rotateCreepTicks = 0;
      return false;
    }
    int cycle = ROTATE_CREEP_MOVE_TICKS + ROTATE_CREEP_HOLD_TICKS;
    return rotateCreepTicks++ % cycle < ROTATE_CREEP_MOVE_TICKS;
  }

  // ------------------------------------------------------------------ boss footwork

  /**
   * Crouched stutter-step against a pinned mini-boss: hold Shift, and inside the hold margin pulse
   * a step back, or a sideways dodge when something is behind. Every direction is collision-probed
   * first, so the bot never presses into a wall it cannot move through, and a blocked dodge flips
   * sides for next time.
   */
  void bossStep(Minecraft mc, LocalPlayer player, double dist, double range) {
    if (mc.options == null) {
      return;
    }
    MacroInput.releaseMovementKeys(mc.options);
    MacroKeys.set(mc.options.keyShift, true);

    if (dist >= range - BOSS_RANGE_HOLD_MARGIN || !retreatPulse(bossRetreatTicks++)) {
      return;
    }
    if (!movementBlocked(player, -BOSS_MOVEMENT_PROBE, 0.0)) {
      MacroKeys.set(mc.options.keyDown, true);
      return;
    }

    double strafe = bossDodgeRight ? BOSS_MOVEMENT_PROBE : -BOSS_MOVEMENT_PROBE;
    if (movementBlocked(player, 0.0, strafe)) {
      bossDodgeRight = !bossDodgeRight;
      strafe = -strafe;
    }
    if (movementBlocked(player, 0.0, strafe)) {
      return;
    }
    MacroKeys.set(mc.options.keyRight, bossDodgeRight);
    MacroKeys.set(mc.options.keyLeft, !bossDodgeRight);
  }

  private static boolean movementBlocked(LocalPlayer player, double forward, double strafe) {
    if (player.level() == null) {
      return true;
    }
    Vec3 look = player.getLookAngle();
    double horizontal = Math.hypot(look.x, look.z);
    if (horizontal < 1.0e-6) {
      return true;
    }
    double forwardX = look.x / horizontal;
    double forwardZ = look.z / horizontal;
    double moveX = forwardX * forward - forwardZ * strafe;
    double moveZ = forwardZ * forward + forwardX * strafe;
    return !player.level().noCollision(player, player.getBoundingBox().move(moveX, 0.0, moveZ));
  }

  /**
   * Leaving the melee band restarts the pulse, so re-entry begins with a step rather than
   * mid-cycle.
   */
  void resetBossFootwork() {
    bossRetreatTicks = 0;
  }

  /** Move for a few ticks, hold for a few: a human retreat is not a continuous slide. */
  static boolean retreatPulse(int tick) {
    int cycle = BOSS_RETREAT_MOVE_TICKS + BOSS_RETREAT_HOLD_TICKS;
    return Math.floorMod(tick, cycle) < BOSS_RETREAT_MOVE_TICKS;
  }

  // ------------------------------------------------------------------ the keys

  /**
   * Close-combat key policy. The mob is the goal, but the head is aimed separately, so the cardinal
   * set this resolves can point sideways or backward while the head is still rotating onto the mob.
   * Rules:
   *
   * <ul>
   *   <li><b>Never S.</b> Back is dropped from the set unconditionally - the bot never reverses in
   *       melee.
   *   <li><b>Rotate before chasing.</b> Outside the {@link CombatModule#COMBAT_FORWARD_FOV_DEG}
   *       forward cone the mob is not in front: the body releases ALL movement (no
   *       strafe-toward-bearing substitute - that sideways shuffle was the backstepping) and lets
   *       the aim ease rotate it on first. Inside the cone it commits forward (W) even before the
   *       head finishes centering, so it starts moving early.
   *   <li><b>Forward-only strafe.</b> W is always held when the mob is in front, so A/D can only
   *       ride along as a diagonal correction, never as a lone strafe. Inside melee with a small
   *       yaw error A/D are dropped too, so there is no cosmetic sideways slide while standing on
   *       the mob.
   *   <li><b>Sprint off only inside the melee buffer.</b>
   * </ul>
   *
   * <p>{@code sidestep} is -1 for left, +1 for right, 0 for none, and survives the melee deadzone
   * that would otherwise drop the strafe keys: it exists precisely to shrug the body off a corner
   * it is scraping. Sight is an eye ray, so the bot can see the mob perfectly while its shoulder
   * grinds a pillar edge, and a jump cannot fix a horizontal obstruction.
   */
  static CommandState walkToward(
      LocalPlayer player,
      double x,
      double z,
      boolean sprint,
      boolean jump,
      boolean closeMelee,
      int sidestep) {
    return walkToward(player, x, z, sprint, jump, closeMelee, sidestep, false);
  }

  private static CommandState walkToward(
      LocalPlayer player,
      double x,
      double z,
      boolean sprint,
      boolean jump,
      boolean closeMelee,
      int sidestep,
      boolean recordedMovement) {
    final Minecraft mc = Minecraft.getInstance();
    if (mc.options == null) {
      return CommandState.STOPPED;
    }

    final double yawErr = Math.abs(yawErrorTo(player, x, z));
    final List<KeyMapping> keys =
        KeyBindUtil.getNeededKeyPresses(player.getX(), player.getZ(), x, z);
    keys.remove(mc.options.keyDown); // never backpedal

    if (yawErr > CombatModule.COMBAT_FORWARD_FOV_DEG) {
      // Target is outside the forward FOV: do NOT strafe toward the bearing (that sideways shuffle
      // is the
      // backstepping). Release movement and let the aim ease rotate the body onto it first; forward
      // resumes the moment the mob is back inside the cone.
      releaseKeys();
      return CommandState.STOPPED;
    }

    // Inside the forward FOV: commit forward now (hold W) even if the head has not finished
    // centering, so the
    // body starts moving early. W is always held here, so A/D can only ever survive as a small
    // diagonal
    // correction alongside it, never as a lone strafe that orbits the mob.
    final boolean airborne = !player.onGround();

    // The melee strafe deadzone exists to stop the bot side-gliding around a mob it is standing on.
    // In the
    // air there is no gliding to prevent and strafe is the only steering there is, so the deadzone
    // is a
    // ground rule only.
    double strafeDeadzone =
        recordedMovement ? RecordedCombatMovement.STRAFE_DEADZONE_DEG : MELEE_STRAFE_DEADZONE_DEG;
    if (!airborne && closeMelee && yawErr < strafeDeadzone) {
      keys.remove(mc.options.keyLeft);
      keys.remove(mc.options.keyRight);
    }
    if (sidestep != 0) {
      // Applied after the deadzone strip so a corner shrug is never cancelled by it.
      keys.remove(mc.options.keyLeft);
      keys.remove(mc.options.keyRight);
      keys.add(sidestep > 0 ? mc.options.keyRight : mc.options.keyLeft);
    }

    // In the air, spend the input on STEERING rather than propulsion.
    //
    // A jump banks its forward momentum at takeoff, and Minecraft's air acceleration is a fraction
    // of its
    // ground acceleration, so holding W through the arc adds very little speed. What it does do is
    // push
    // the body past a mob it had already reached. Strafe is different: it is the only way to
    // correct an
    // arc once the feet are off the floor, which is exactly what a person does mid-jump.
    //
    // So: already travelling toward the mob fast enough? Release forward and let the arc carry,
    // keeping
    // the strafe keys as the correction. Not actually closing (hopped on the spot, or drifting
    // away)?
    // Keep forward, because then there is no momentum to coast on.
    //
    // Deliberately combat-only: the pathfinder holds W across a hop on purpose, and that policy is
    // shared
    // with routes this code does not own.
    final boolean coasting = airborne && closingSpeedToward(player, x, z) >= AIR_COAST_SPEED;
    if (coasting) {
      keys.remove(mc.options.keyUp);
    } else if (!keys.contains(mc.options.keyUp)) {
      keys.add(mc.options.keyUp);
    }
    if (jump) {
      keys.add(mc.options.keyJump);
    }
    if (sprint && !closeMelee && keys.contains(mc.options.keyUp)) {
      keys.add(mc.options.keySprint);
    }
    KeyBindUtil.holdThese(keys);
    return new CommandState(describe(mc, keys), keys.contains(mc.options.keySprint));
  }

  /** Small humanized forward pulse while the head turns onto a visible side target. */
  static CommandState creepForward(boolean jump) {
    final Minecraft mc = Minecraft.getInstance();
    if (mc.options == null) {
      return CommandState.STOPPED;
    }

    final List<KeyMapping> keys = new ArrayList<>();
    keys.add(mc.options.keyUp);
    if (jump) {
      keys.add(mc.options.keyJump);
    }
    KeyBindUtil.holdThese(keys);
    return new CommandState(describe(mc, keys), false);
  }

  /** Release every movement key (W/A/S/D/jump/sprint); the head keeps aiming and swinging. */
  static void releaseKeys() {
    KeyBindUtil.stopMovement(true); // ignoreAttack: never cancel an in-progress swing
  }

  /**
   * Force-release only the backpedal key (S). Used on a target handoff so forward flow is preserved
   * while still guaranteeing the bot never holds backward, even for the tick before the new keys
   * are derived.
   */
  static void releaseBackpedal() {
    final Minecraft mc = Minecraft.getInstance();
    if (mc.options != null) {
      MacroKeys.set(mc.options.keyDown, false);
    }
  }

  /**
   * How fast the body is actually travelling TOWARD {@code (x, z)}, in blocks per tick. This is the
   * component of the current velocity along the bearing to the mob, so drifting sideways or away
   * reads as near zero (or negative) even at high raw speed.
   */
  private static double closingSpeedToward(LocalPlayer player, double x, double z) {
    final double toX = x - player.getX();
    final double toZ = z - player.getZ();
    final double length = Math.hypot(toX, toZ);
    if (length < 1.0e-6) {
      return 0.0;
    }
    final Vec3 velocity = player.getDeltaMovement();
    return (velocity.x * toX + velocity.z * toZ) / length;
  }

  private static double yawErrorTo(LocalPlayer player, double x, double z) {
    final double dx = x - player.getX();
    final double dz = z - player.getZ();
    if (dx * dx + dz * dz < 1.0e-6) {
      return 0.0;
    }
    final float toYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
    return Mth.wrapDegrees(toYaw - player.getYRot());
  }

  private static void append(
      StringBuilder out, List<KeyMapping> keys, KeyMapping key, String label) {
    if (!keys.contains(key)) {
      return;
    }
    if (out.length() > 0) {
      out.append('+');
    }
    out.append(label);
  }

  // ------------------------------------------------------------------ nested state

  /**
   * When to hop, without hopping every tick. Counts consecutive obstructed ticks (a wall in front)
   * and consecutive stalled ticks (barely moving, which catches an entity the block probe cannot
   * see), and holds a cooldown so a fired hop does not immediately re-fire on landing.
   */
  static final class Hop {
    private int collisionTicks;
    private int stallTicks;
    private int cooldownTicks;

    void tickCooldown() {
      if (cooldownTicks > 0) {
        cooldownTicks--;
      }
    }

    boolean requestJump(
        boolean grounded,
        boolean colliding,
        boolean lowSpeed,
        boolean verticalHop,
        int wallHopTicks,
        int stallHopTicks,
        int hopCooldownTicks) {
      collisionTicks = colliding ? collisionTicks + 1 : 0;
      stallTicks = lowSpeed ? stallTicks + 1 : 0;
      boolean wallHop = collisionTicks >= wallHopTicks || stallTicks >= stallHopTicks;
      boolean jump = grounded && (wallHop || verticalHop) && cooldownTicks == 0;
      if (jump) {
        cooldownTicks = hopCooldownTicks;
        clearObstruction();
      }
      return jump;
    }

    void clearObstruction() {
      collisionTicks = 0;
      stallTicks = 0;
    }

    int cooldownTicks() {
      return cooldownTicks;
    }

    void reset() {
      clearObstruction();
      cooldownTicks = 0;
    }
  }

  /**
   * The beat before the swing. A person closing on a mob does not land the hit on the exact frame
   * they stop moving, so the first arrival at melee range spends a short randomised pause. Once
   * spent it stays spent for that mob until the bot leaves the resume band, so a fight is not a
   * series of pauses.
   */
  static final class KillPause {
    private UUID targetId;
    private int remainingTicks;

    @Getter(AccessLevel.PACKAGE)
    private boolean spent;

    /**
     * Point at a mob; true when that is a change, so the caller can drop its own per-target state.
     */
    boolean selectTarget(UUID currentTargetId) {
      if (currentTargetId.equals(targetId)) {
        return false;
      }

      targetId = currentTargetId;
      remainingTicks = 0;
      spent = false;
      return true;
    }

    void rearmIfOutsideBand(double gap, double resumeGap) {
      if (gap > resumeGap) {
        spent = false;
      }
    }

    /** The duration is sampled once, on the tick the pause starts, not re-rolled every tick. */
    void tick(IntSupplier durationTicks) {
      if (remainingTicks <= 0) {
        remainingTicks = durationTicks.getAsInt();
      }
      if (--remainingTicks <= 0) {
        spent = true;
      }
    }

    int remainingTicks() {
      return remainingTicks;
    }

    void reset() {
      targetId = null;
      remainingTicks = 0;
      spent = false;
    }
  }
}
