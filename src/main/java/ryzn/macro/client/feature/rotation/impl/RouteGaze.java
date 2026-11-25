package ryzn.macro.client.feature.rotation.impl;

import lombok.Getter;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import ryzn.macro.client.feature.modules.impl.combat.CombatSight;
import ryzn.macro.client.feature.pathfinder.WalkingNavigation;
import ryzn.macro.client.feature.rotation.api.LookController;

/**
 * Watch the thing you are walking to while the pathfinder drives the body - the handover Chillshot
 * uses for its quarry, in a form any macro can hold an instance of. A macro that owns the gaze must
 * publish it through {@code RotationManager.externalOwnsLook}, which is what makes the path
 * executor yield ONLY the head and keep steering the body along the live route.
 *
 * <p>The three rules here were all paid for in-game by the hunt; do not re-tune them without
 * re-testing there:
 *
 * <ul>
 *   <li><b>Asymmetric sight.</b> 3 ticks of clear sight to take the head, 20 to give it back.
 *       Symmetric thresholds make the head flick between target and path every time a column
 *       crosses it - a pillar blocks sight for well under a second, and a person keeps their head
 *       where the target was.
 *   <li><b>Range hysteresis.</b> Take the head only once close enough that the target is the next
 *       thing that matters, hold it until it is clearly far away. Watching from across the island
 *       is the same flicker.
 *   <li><b>Never let the route ask for S.</b> Key selection is body-relative, so with the head
 *       turned away a node past 112.5 degrees behind the facing resolves to the back key and the
 *       bot travels backwards - or, once that key is stripped, stands still with nothing able to
 *       break the deadlock. Releasing at 100 degrees hands the head back with room to spare, so the
 *       body turns onto the path and walks forward.
 * </ul>
 *
 * <p>Measured against the BODY yaw, not the bearing to the target: those agree only while the head
 * is really tracking, and the controller parks the head whenever the aim point is occluded.
 *
 * <p>{@code ChillshotHunt} still carries its own copy of this gate, wired into its phase machine.
 * It is left alone deliberately - it is tuned and in-game validated - but the two should converge
 * if it is ever touched.
 */
public final class RouteGaze {

  private static final int SIGHT_LOCK_ON_TICKS = 3;
  private static final int SIGHT_LOCK_OFF_TICKS = 20;

  /**
   * Default watch ranges, tuned for an NPC you walk up to. A macro whose target sits at the far end
   * of a long route wants more than this - at 9 blocks the head only comes across for the last
   * stride of the approach, so most of the walk is spent facing the path and the gaze looks like it
   * is not working at all.
   */
  private static final double DEFAULT_TAKE_RANGE = 9.0;

  private static final double DEFAULT_RELEASE_RANGE = 13.0;

  private final double takeRange;
  private final double releaseRange;

  public RouteGaze() {
    this(DEFAULT_TAKE_RANGE, DEFAULT_RELEASE_RANGE);
  }

  /**
   * {@code takeRange} to look over, {@code releaseRange} to look away - keep them apart, the gap is
   * the hysteresis that stops the boundary itself chattering the head.
   *
   * @param takeRange the distance within which the head is taken over
   * @param releaseRange the distance beyond which the head is given back
   */
  public RouteGaze(double takeRange, double releaseRange) {
    this.takeRange = takeRange;
    this.releaseRange = Math.max(releaseRange, takeRange);
  }

  /**
   * How far the route may bend away before the head goes back on the path.
   *
   * <p>This was 100, picked against vanilla's back-key cone alone. That ignored the executor's own
   * gates, which all measure the same angle and every one of which bites first: the hop gate needs
   * the facing gap under 45, the movement controller cuts the backstep allowance to 2 ticks while a
   * macro owns the head, and the 135 facing gate clears every key - normally recovering when the
   * head swings back, which it never does while a gaze is holding it. 40 sits under the strictest
   * with margin. Same numbers Tree Chopper arrived at.
   */
  private static final float BACKSTEP_LIMIT_DEG = 40f;

  /**
   * Re-take only when the TARGET is near the route heading, measured as a BEARING rather than off
   * the body.
   *
   * <p>This is the fix for the head stuttering between the path and the target. Releasing turns the
   * body back onto the route, so a body-relative re-take is true again the very next tick: head
   * swings over, the gap re-opens, head goes back, and the two trade it every few ticks - each
   * trade calling clear() on the aim, which resets the ease, which is what makes it visibly stutter
   * rather than glide. The bearing to the target does not move when the body turns, so it settles
   * the argument once.
   */
  private static final float REACQUIRE_LIMIT_DEG = 25f;

  private int sightTicks;
  private int blindTicks;

  /**
   * True while this macro is holding the head mid-route. Publish through {@code externalOwnsLook}.
   */
  @Getter private boolean owned;

  /**
   * Decide who holds the head this tick, and aim at {@code target} through {@code look} while it is
   * ours. Returns true when the caller should leave the aim alone rather than clearing it.
   *
   * @param player the local player
   * @param target the entity to watch
   * @param look the owner's aim controller
   * @return true when the caller should leave the aim alone
   */
  public boolean update(LocalPlayer player, Entity target, LookController look) {
    if (!shouldWatch(player, target, look::reanchor)) {
      return false;
    }
    look.aimAtEntity(player, target);
    return true;
  }

  /**
   * The gate on its own, for a macro whose look plumbing is not a {@link MacroLookController} - the
   * aim call is then the caller's. {@code onTakeOver} runs on the tick the head changes hands and
   * must re-anchor the caller's ease from where the executor left the head, or the takeover snaps
   * instead of easing.
   *
   * @param player the local player
   * @param target the entity to watch
   * @param onTakeOver runs on the tick the head changes hands, to re-anchor the ease
   * @return true while this owner holds the head
   */
  public boolean shouldWatch(LocalPlayer player, Entity target, Runnable onTakeOver) {
    if (player == null || target == null || !target.isAlive()) {
      release();
      return false;
    }
    final double range = owned ? releaseRange : takeRange;
    if (player.distanceTo(target) > range) {
      release();
      return false;
    }
    if (conflictsWithRoute(player, MacroAim.entityLookPoint(target))) {
      release();
      return false;
    }
    return latch(CombatSight.canSee(player, target), onTakeOver);
  }

  /**
   * Same gate for a target that is not an entity - a block, a spot on the ground. There is no
   * shared line-of-sight test for those (what counts as "blocked" is the caller's business: a
   * tree's own canopy is not cover, a wall is), so {@code visible} is passed in.
   *
   * @param player the local player
   * @param point the point to watch
   * @param visible whether the caller's own sight test passes
   * @param onTakeOver runs on the tick the head changes hands, to re-anchor the ease
   * @return true while this owner holds the head
   */
  public boolean shouldWatch(LocalPlayer player, Vec3 point, boolean visible, Runnable onTakeOver) {
    if (player == null || point == null) {
      release();
      return false;
    }
    final double range = owned ? releaseRange : takeRange;
    if (player.getEyePosition().distanceToSqr(point) > range * range
        || conflictsWithRoute(player, point)) {
      release();
      return false;
    }
    return latch(visible, onTakeOver);
  }

  /** The asymmetric sight counters, shared by both target kinds. */
  private boolean latch(boolean visible, Runnable onTakeOver) {
    if (visible) {
      blindTicks = 0;
      if (++sightTicks >= SIGHT_LOCK_ON_TICKS && !owned) {
        owned = true;
        if (onTakeOver != null) {
          onTakeOver.run();
        }
      }
    } else {
      sightTicks = 0;
      if (owned && ++blindTicks >= SIGHT_LOCK_OFF_TICKS) {
        release();
      }
    }
    return owned;
  }

  public void release() {
    owned = false;
    sightTicks = 0;
    blindTicks = 0;
  }

  /**
   * Would holding the head here make the route walk backwards? Only meaningful while a route is
   * driving.
   *
   * <p>Release is measured against the BODY, like the executor's own move error. Re-taking is
   * measured against the bearing to the target - see {@link #REACQUIRE_LIMIT_DEG} for why the two
   * cannot be the same test without the head oscillating.
   */
  private boolean conflictsWithRoute(LocalPlayer player, Vec3 aim) {
    // Curve heading first, node bearing as the fallback. Both limits below are angles to "the way
    // we are
    // going", and the node bearing only approximates that: it steps as nodes retire and swings the
    // full
    // width of a corner, so a gate reading it takes and releases the head on route geometry rather
    // than
    // on anything about the target. The curve is a fit over the next few blocks and holds still
    // through
    // the same corner. NaN (no curve, or the run-out at the end of a route) falls back unchanged.
    float routeYaw = WalkingNavigation.lookPathHeadingYaw();
    if (Float.isNaN(routeYaw)) {
      routeYaw = WalkingNavigation.routeHeadingYaw();
    }
    if (Float.isNaN(routeYaw)) {
      return false;
    }
    final float reference = owned ? player.getYRot() : bearingYaw(player, aim);
    final float limit = owned ? BACKSTEP_LIMIT_DEG : REACQUIRE_LIMIT_DEG;
    return Math.abs(Mth.wrapDegrees(routeYaw - reference)) > limit;
  }

  private static float bearingYaw(LocalPlayer player, Vec3 aim) {
    return (float)
        (Math.toDegrees(Math.atan2(aim.z - player.getZ(), aim.x - player.getX())) - 90.0);
  }
}
