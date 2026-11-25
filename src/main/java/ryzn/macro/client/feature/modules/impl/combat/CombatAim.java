package ryzn.macro.client.feature.modules.impl.combat;

import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import ryzn.macro.client.config.RyznSettings;
import ryzn.macro.client.core.RyznBootstrap;
import ryzn.macro.client.debug.DebugLog;

/**
 * Everything about where the head points during a fight: the frame clock that drives it, the eased
 * aim itself, and the cached "the crosshair is on the mob" answer the swing gate reads.
 *
 * <p>Assisted, not snap-locked. Each frame it eases the player's head a fraction of the way toward
 * a point on the <i>inside</i> of the target's hitbox, so the crosshair settles into the meat of
 * the mob rather than the geometric centre (aimbot-y) or the bounding-box frame.
 *
 * <p>The aim point is found by projecting the player's current look ray to the depth of the hitbox
 * centre and clamping that projected point into an inset (shrunk) copy of the hitbox. When the look
 * ray already passes through the inner box the clamp is a no-op, the angular error is ~0, and the
 * assist relaxes - a natural deadzone that makes the tracking feel purposeful and casual instead of
 * robotically glued to a point.
 *
 * <p>Two wandering offsets sit on top of that, both the same {@link Drift}: one while closing the
 * gap (the gaze sits <i>near</i> the mob, not on it) and one at point-blank range (the aim never
 * rests dead centre). They differ only in scale and pace.
 *
 * <p>Anti-detection: this only sets the visible player yaw/pitch (exactly like a player moving the
 * mouse); vanilla then emits the same look packets. No packet is crafted, blocked, or delayed.
 */
public final class CombatAim {

  /** Inner box as a fraction of the full hitbox half-extents (0.55 = central 55%). */
  private static final double INSET = 0.55;

  /**
   * Stable vertical aim height as a fraction up the hitbox (upper body, inside the box). Shared
   * with the sight test via {@link CombatTuning}, because "can I see it" and "where do I aim" must
   * agree.
   */
  private static final double VERTICAL_FRACTION = CombatTuning.AIM_HEIGHT_FRACTION;

  /**
   * Below this angular error (deg) the crosshair is already inside the inner box - hold, don't
   * twitch.
   */

  /**
   * Head-speed ceiling at the DEFAULT rotation-acceleration (slider 50): the current smooth feel.
   * The live ceiling is scaled off this by the selected movement policy so a fresh / fast-moving
   * target stays vector-eased.
   */

  /**
   * Head-speed ceiling at full rotation-acceleration (slider 100) - faster target swaps without a
   * snap.
   */

  /** Reference framerate the per-frame factor is calibrated at. */
  private static final float REFERENCE_FPS = 60f;

  /**
   * Ramp-in time after a (re)engage before the assist reaches full speed. Without it the first
   * frame of a lock runs at the full speed cap - an instant velocity jump off the path gaze that
   * reads as a snap, both when tracking starts mid-path and when the lock hops to the next mob.
   */

  /**
   * Fraction of full speed the ramp starts at (the head starts moving immediately, just gently).
   */

  // ---- approach (lazy) aim: aim OFF the mob at a wandering point, instead of glued to its hitbox
  // ----
  // Laziness controls how far off the mob the gaze sits, NOT how fast the head moves: the head
  // still tracks
  // its target at full assist speed, but that target is an offset point near (not on) the mob, so
  // it never
  // locks onto the zombie while closing.
  /**
   * Horizontal drift radius (blocks) at 100% laziness - large enough to clearly read as "looking
   * near it".
   */
  private static final double LAZY_MAX_RADIUS = 2.0;

  /**
   * Vertical drift (blocks) at 100% laziness - smaller than the horizontal so the head mostly pans
   * sideways.
   */
  private static final double LAZY_MAX_VERT = 0.7;

  /** Base time a drift target is held before a new one is picked (ms). */
  private static final long LAZY_RETARGET_BASE_MS = 400L;

  /** Random jitter added to the hold time (ms), so the retargets do not land on a fixed beat. */
  private static final long LAZY_RETARGET_JITTER_MS = 350L;

  /**
   * Per-frame (at 60 fps) fraction the current offset eases toward the drift target (a natural pan,
   * never a jump).
   */
  private static final float LAZY_EASE_60 = 0.07f;

  /**
   * Relaxed deadzone (deg) while approaching: a wider "good enough" cone so the gaze is not
   * micro-corrected.
   */

  // ---- close-range precise-aim softness: a slow micro-drift inside the hitbox so a screen-filling
  // mob is not
  // aimed dead-centre after contact. The attack-readiness ray is untouched (it reads the real look
  // ray, which
  // still crosses the hitbox), so hits still fire on time; this only moves the render aim point a
  // hair. ----
  /**
   * Within this eye->box-centre distance the softness fades in (0 at the edge, full point-blank).
   */
  private static final double SOFT_AIM_RANGE = 4.0;

  /**
   * Soft drift radius as a fraction of the inner-box half-extent, so the point always stays well
   * inside.
   */
  private static final double SOFT_AIM_FRACTION = 0.55;

  /** Soft vertical drift (blocks) at full closeness. */
  private static final double SOFT_AIM_VERT = 0.22;

  /**
   * Base hold for a soft-aim point before a new one is picked (ms) - "updated slowly, not every
   * frame".
   */
  private static final long SOFT_RETARGET_BASE_MS = 420L;

  private static final long SOFT_RETARGET_JITTER_MS = 260L;

  /** Per-frame (at 60 fps) ease toward the current soft point, so the head pans to it smoothly. */
  private static final float SOFT_EASE_60 = 0.05f;

  /** Hitbox inflation on the attack ray, so a hair off the edge still counts as on the mob. */
  private static final double ATTACK_RAY_INFLATE = 0.12;

  /**
   * How long a "the ray is on the mob" answer stays usable, so the swing gate can read it
   * off-frame.
   */
  private static final long ATTACK_RAY_CACHE_NS = 100_000_000L;

  /**
   * Laziness is dropped this far outside attack range, so the gaze is already precise on arrival.
   */
  private static final double AIM_PRECISE_BUFFER = 1.5;

  private static final Random rng = new Random();
  private final Clock clock = new Clock();

  @Getter(AccessLevel.PACKAGE)
  private AimSample aimSample;

  private final Readiness<LivingEntity> readiness = new Readiness<>();
  private final Drift approachDrift =
      new Drift(LAZY_RETARGET_BASE_MS, LAZY_RETARGET_JITTER_MS, LAZY_EASE_60);
  private final Drift softDrift =
      new Drift(SOFT_RETARGET_BASE_MS, SOFT_RETARGET_JITTER_MS, SOFT_EASE_60);

  boolean recordedMovement;

  /**
   * Full teardown: forget the drift, the smoother and the cached readiness so the next engage
   * starts clean.
   */
  public void reset() {
    RyznBootstrap.rotationManager().resetAim(this);
    aimSample = null;
    approachDrift.reset();
    softDrift.reset();
    readiness.clear();
  }

  /** The head-lock just took over from the path gaze. */
  void beginTracking(RyznSettings cfg, double distance, double attackRange) {
    clock.reset();
  }

  /** The lock hopped to a different mob. */
  void targetChanged(
      LocalPlayer player, LivingEntity target, RyznSettings cfg, double attackRange) {
    readiness.clear();
  }

  /**
   * Ease the head one frame toward the target. Driven by wall clock rather than the tick, so this
   * runs from both the tick and the render frame and stays smooth at any framerate. Also refreshes
   * the cached attack readiness, since the ray it needs is the one just aimed.
   */
  void update(
      boolean enabled,
      boolean tracking,
      LivingEntity target,
      RyznSettings cfg,
      boolean aimAssist,
      double attackRange) {
    if (!enabled || !tracking || !aimAssist) {
      RyznBootstrap.rotationManager().resetAim(this);
      aimSample = null;
      readiness.clear();
      return;
    }
    final LocalPlayer player = Minecraft.getInstance().player;
    if (player == null || target == null || !target.isAlive()) {
      RyznBootstrap.rotationManager().resetAim(this);
      aimSample = null;
      readiness.clear();
      return;
    }
    final double distance = player.distanceTo(target);
    final boolean precise = recordedMovement || usesPreciseAim(cfg, distance, attackRange);
    assist(
        player,
        target,
        cfg,
        clock.deltaSeconds(System.nanoTime(), recordedMovement),
        precise,
        isHittable(player, target, attackRange));
    final boolean rayReady =
        target.isAlive()
            && player.distanceTo(target) <= attackRange
            && viewRayHitsTarget(player, target, attackRange);
    readiness.update(target, rayReady, System.nanoTime());
  }

  /**
   * The crosshair is on the mob right now: either vanilla already picked it, or our own ray reaches
   * it.
   */
  static boolean isHittable(LocalPlayer player, LivingEntity target, double range) {
    if (target == null || !target.isAlive()) {
      return false;
    }
    if (Minecraft.getInstance().crosshairPickEntity == target) {
      return true;
    }
    return player.distanceTo(target) <= range && viewRayHitsTarget(player, target, range);
  }

  /**
   * The cached form the swing gate reads, so the tick does not re-cast the ray the aim frame
   * already cast.
   */
  boolean isAttackReady(LocalPlayer player, LivingEntity target, double range, long nowNs) {
    return readiness.isReady(
        target,
        target != null && target.isAlive(),
        target != null && player.distanceTo(target) <= range,
        nowNs,
        ATTACK_RAY_CACHE_NS);
  }

  /** Laziness is only spent while closing the gap: near attack range the aim goes exact. */
  static boolean usesPreciseAim(int lazinessPercent, double distance, double attackRange) {
    return lazinessPercent <= 0 || distance <= attackRange + AIM_PRECISE_BUFFER;
  }

  private static boolean usesPreciseAim(RyznSettings cfg, double distance, double attackRange) {
    return usesPreciseAim(cfg.combat().getCombatAimLazinessPercent(), distance, attackRange);
  }

  private void assist(
      LocalPlayer player,
      LivingEntity target,
      RyznSettings cfg,
      float dt,
      boolean precise,
      boolean killWindow) {
    final Vec3 eye = player.getEyePosition(1f);
    final Vec3 aim =
        precise
            ? aimPoint(player, target, eye, dt, killWindow)
            : lazyAimPoint(player, target, cfg, dt);

    final double dx = aim.x - eye.x;
    final double dy = aim.y - eye.y;
    final double dz = aim.z - eye.z;
    final double horiz = Math.sqrt(dx * dx + dz * dz);
    if (horiz < 1.0e-4) {
      return;
    }

    final float curYaw = player.getYRot();
    final float curPitch = player.getXRot();
    final float targetYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
    final float targetPitch = (float) -Math.toDegrees(Math.atan2(dy, horiz));
    final float wantYaw = targetYaw;
    final float wantPitch = targetPitch;
    final float deltaYaw = Mth.wrapDegrees(wantYaw - curYaw);
    final float deltaPitch = wantPitch - curPitch;

    aimSample =
        recordedMovement && DebugLog.isEnabled(DebugLog.Category.MACRO)
            ? new AimSample(
                target.getId(),
                System.nanoTime(),
                target.position(),
                target.getDeltaMovement(),
                target.getBoundingBox(),
                eye,
                aim,
                wantYaw,
                wantPitch,
                deltaYaw,
                deltaPitch,
                dt)
            : null;

    RyznBootstrap.rotationManager().aimTarget(this, player, wantYaw, wantPitch);
  }

  record AimSample(
      int targetId,
      long atNs,
      Vec3 targetPos,
      Vec3 targetVelocity,
      AABB hitbox,
      Vec3 eye,
      Vec3 point,
      float yaw,
      float pitch,
      float deltaYaw,
      float deltaPitch,
      float dt) {}

  private Vec3 aimPoint(
      LocalPlayer player, LivingEntity target, Vec3 eye, float dt, boolean killWindow) {
    final AABB box = target.getBoundingBox();
    final double cx = (box.minX + box.maxX) * 0.5;
    final double cy = (box.minY + box.maxY) * 0.5;
    final double cz = (box.minZ + box.maxZ) * 0.5;
    final double ix = (box.maxX - box.minX) * 0.5 * INSET;
    final double iz = (box.maxZ - box.minZ) * 0.5 * INSET;

    if (recordedMovement) {
      double horizontalDistance = Math.hypot(cx - eye.x, cz - eye.z);
      double preferredPitch = Math.clamp(player.getXRot(), -5f, 5f);
      double preferredY = eye.y - Math.tan(Math.toRadians(preferredPitch)) * horizontalDistance;
      double height = box.maxY - box.minY;
      return new Vec3(
          cx, Math.clamp(preferredY, box.minY + height * 0.1, box.maxY - height * 0.1), cz);
    }

    // Horizontal: slide along the inside of the hitbox under the current look so yaw tracks
    // smoothly.
    final Vec3 look = player.getViewVector(1f);
    final double depth = eye.distanceTo(new Vec3(cx, cy, cz));
    final Vec3 proj = eye.add(look.scale(depth));
    double px = Math.clamp(proj.x, cx - ix, cx + ix);
    double pz = Math.clamp(proj.z, cz - iz, cz + iz);

    // Vertical: a STABLE in-box height (upper body), independent of the current pitch. Coupling Y
    // to the
    // look ray the way X/Z are made the target flip across the box edge each frame, which is why
    // pitch
    // snapped / refused to ease - the target was never a fixed point to ease toward.
    double aimY = aimHeight(box, eye.y);

    // Close-range softness: as the mob fills the screen, add a slow micro-drift inside the inner
    // box so the
    // aim is not glued to a single point. Stays clamped within the inner box, so the readiness ray
    // still hits.
    // Suppressed in the kill window: once the crosshair/ray is on the mob the aim holds on the
    // stable torso
    // point - in particular the vertical micro-drift is dropped so the pitch stops dipping down
    // before the hit.
    if (!killWindow && depth <= SOFT_AIM_RANGE) {
      final double close = Math.clamp(1.0 - depth / SOFT_AIM_RANGE, 0.0, 1.0);
      softDrift.update(
          target.getUUID(),
          ix * SOFT_AIM_FRACTION * close,
          SOFT_AIM_VERT * close,
          iz * SOFT_AIM_FRACTION * close,
          false,
          dt);
      px = Math.clamp(px + softDrift.offsetX, cx - ix, cx + ix);
      pz = Math.clamp(pz + softDrift.offsetZ, cz - iz, cz + iz);
      aimY += softDrift.offsetY;
    }
    return new Vec3(px, aimY, pz);
  }

  /**
   * Approach aim point: a point that drifts slowly inside a small radius around the mob, rather
   * than the exact hitbox. A fresh drift target is chosen every few hundred ms (with jitter) and
   * the live offset eases toward it each frame, so the gaze wanders believably "around" the mob
   * while closing instead of snapping to it. The radius scales with the laziness slider and is
   * capped tiny ({@link #LAZY_MAX_RADIUS}), so it always reads as looking at the mob. At 0% the
   * radius is zero - identical to centring on the mob - and the caller keeps precise aim anyway.
   */
  private Vec3 lazyAimPoint(LocalPlayer player, LivingEntity target, RyznSettings cfg, float dt) {
    final AABB box = target.getBoundingBox();
    final double cx = (box.minX + box.maxX) * 0.5;
    final double cz = (box.minZ + box.maxZ) * 0.5;
    final double aimY = aimHeight(box, player.getEyeY());

    final float frac = Math.clamp(cfg.combat().getCombatAimLazinessPercent() / 100f, 0f, 1f);
    final double radius = LAZY_MAX_RADIUS * frac;

    approachDrift.update(target.getUUID(), radius, LAZY_MAX_VERT * frac, radius, true, dt);
    return new Vec3(
        cx + approachDrift.offsetX, aimY + approachDrift.offsetY, cz + approachDrift.offsetZ);
  }

  private double aimHeight(AABB box, double eyeY) {
    double height = box.maxY - box.minY;
    if (!recordedMovement) {
      return box.minY + height * VERTICAL_FRACTION;
    }
    // Tall Endermen were tracked near level; short mobs still need an in-box downward look.
    return Math.clamp(eyeY, box.minY + height * 0.35, box.minY + height * 0.70);
  }

  /**
   * Convert a per-frame factor calibrated at {@link #REFERENCE_FPS} into one for the actual frame
   * delta.
   */
  private static float frameFactor(float perFrame60, float dt) {
    if (perFrame60 <= 0f) {
      return 0f;
    }
    // No elapsed time means no easing, whatever the rate. The closed form already says so
    // (1 - 0^0 == 0), but the instant-ease shortcut below would skip straight past it and snap.
    if (dt <= 0f) {
      return 0f;
    }
    if (perFrame60 >= 1f) {
      return 1f;
    }
    return 1f - (float) Math.pow(1f - perFrame60, REFERENCE_FPS * dt);
  }

  private static boolean viewRayHitsTarget(LocalPlayer player, LivingEntity target, double range) {
    if (player.level() == null) {
      return false;
    }
    final Vec3 eye = player.getEyePosition(1f);
    final Vec3 end = eye.add(player.getViewVector(1f).scale(range));
    final AABB box = target.getBoundingBox().inflate(ATTACK_RAY_INFLATE);
    final Optional<Vec3> hit = box.clip(eye, end);
    if (hit.isEmpty() && !box.contains(eye)) {
      return false;
    }
    final Vec3 hitPoint = hit.orElse(eye);
    final HitResult blockHit =
        player
            .level()
            .clip(
                new ClipContext(
                    eye, hitPoint, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
    return blockHit.getType() == HitResult.Type.MISS;
  }

  /**
   * A slowly wandering offset. Both aim drifts are the same idea at two scales: pick a random point
   * inside a radius, hold it a few hundred ms (jittered, so retargets never land on a beat), ease
   * toward it every frame, start over. Zeroed when the mob changes, so a new target is aimed at
   * centre first.
   */
  @RequiredArgsConstructor(access = AccessLevel.PACKAGE)
  static final class Drift {
    private final long holdMs;
    private final long jitterMs;
    private final float ease60;

    private UUID id;
    double offsetX;
    double offsetY;
    double offsetZ; // current eased offset
    private double dstX;
    private double dstY;
    private double dstZ; // offset being eased toward
    private long retargetAtMs;

    /**
     * {@code disc}: draw x/z uniformly over the disc of radius {@code radX} instead of per-axis.
     */
    void update(UUID target, double radX, double radY, double radZ, boolean disc, float dt) {
      final long now = System.currentTimeMillis();
      if (!target.equals(id)) {
        id = target;
        offsetX = offsetY = offsetZ = 0.0; // start centred on a new mob, then drift out
        retargetAtMs = 0L; // pick a fresh drift target this frame
      }
      if (now >= retargetAtMs) {
        if (disc) {
          final double ang = rng.nextDouble() * Math.PI * 2.0;
          final double mag =
              radX * Math.sqrt(rng.nextDouble()); // uniform over the disc, not clustered centre
          dstX = Math.cos(ang) * mag;
          dstZ = Math.sin(ang) * mag;
        } else {
          dstX = (rng.nextDouble() * 2.0 - 1.0) * radX;
          dstZ = (rng.nextDouble() * 2.0 - 1.0) * radZ;
        }
        dstY = (rng.nextDouble() * 2.0 - 1.0) * radY;
        retargetAtMs = now + holdMs + (long) (rng.nextDouble() * jitterMs);
      }
      // Keep the held target inside the live radius, so a shrunk radius (slider moved, mob
      // receding)
      // takes effect immediately instead of after the current hold expires.
      dstX = Math.clamp(dstX, -radX, radX);
      dstY = Math.clamp(dstY, -radY, radY);
      dstZ = Math.clamp(dstZ, -radZ, radZ);

      final float ease = frameFactor(ease60, dt);
      offsetX += (dstX - offsetX) * ease;
      offsetY += (dstY - offsetY) * ease;
      offsetZ += (dstZ - offsetZ) * ease;
    }

    void reset() {
      id = null;
      offsetX = offsetY = offsetZ = 0.0;
      dstX = dstY = dstZ = 0.0;
      retargetAtMs = 0L;
    }
  }

  /** Frame delta in seconds, clamped so a stall or a 1000 fps burst cannot jump the aim. */
  static final class Clock {
    private long lastFrameNs;

    float deltaSeconds(long now) {
      return deltaSeconds(now, false);
    }

    float deltaSeconds(long now, boolean realTime) {
      if (lastFrameNs == 0L) {
        lastFrameNs = now;
        return realTime ? 0f : 1f / 60f;
      }
      final float delta = (now - lastFrameNs) / 1.0e9f;
      lastFrameNs = now;
      return Math.max(realTime ? 0f : 1f / 600f, Math.min(1f / 15f, delta));
    }

    void reset() {
      lastFrameNs = 0L;
    }
  }

  /**
   * The last "the ray is on the mob" answer, only usable for the same live in-range target and
   * while fresh.
   */
  static final class Readiness<T> {
    private boolean ready;
    private T target;
    private long updatedAt;

    void update(T candidate, boolean rayReady, long now) {
      ready = rayReady;
      target = rayReady ? candidate : null;
      updatedAt = rayReady ? now : 0L;
    }

    boolean isReady(T expectedTarget, boolean alive, boolean inRange, long now, long maxAge) {
      return ready && target == expectedTarget && alive && inRange && now - updatedAt <= maxAge;
    }

    void clear() {
      ready = false;
      target = null;
      updatedAt = 0L;
    }
  }
}
