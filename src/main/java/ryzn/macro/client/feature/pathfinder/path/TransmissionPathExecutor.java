package ryzn.macro.client.feature.pathfinder.path;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntToDoubleFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ryzn.macro.client.MacroInput;
import ryzn.macro.client.config.RyznSettings;
import ryzn.macro.client.config.groups.PathfinderSettings;
import ryzn.macro.client.debug.DebugLog;
import ryzn.macro.client.feature.rotation.impl.Angle;
import ryzn.macro.client.feature.rotation.impl.AngleUtil;
import ryzn.macro.client.util.inventory.MacroItems;
import ryzn.macro.client.util.inventory.MacroSlots;

/**
 * Instant Transmission (Aspect of the Void / End) hops along a ground route, run as its own
 * executor. The ground walker only walks between hops; from the moment a hop is picked until the
 * landing settles this class owns the keys, the look and the click.
 *
 * <p>A hop is chosen by where the straight ability ray lands, not by how straight the path is: the
 * route between the player and the landing may zig-zag freely, so micro turns and corners are
 * skipped as long as the ray is clear and the landing is a safe spot on the trail further ahead.
 * Sharp aims stop the body first so the ray is cast from a still, settled position.
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class TransmissionPathExecutor {

  /** Only ranges an Aspect can actually have; anything else means the lore was misread. */
  private static final int MIN_RANGE = 8;

  private static final int MAX_RANGE = 12;
  private static final double DEFAULT_RANGE = 12.0;
  private static final double PATH_SCAN_FACTOR = 3.0;
  private static final double MIN_PATH_SAVED = 4.0;
  private static final double MIN_CLIMB_RISE = 0.6;
  private static final double MIN_CLIMB_DISTANCE = 1.0;

  /** How far a jump lifts the eyes by the time the climb teleport fires, conservatively. */
  private static final double JUMP_EYE_LIFT = 1.0;

  private static final double MIN_EXACT_DISTANCE = 2.0;
  private static final int EXACT_HOP_BONUS = 2;
  private static final double START_WALK_MIN = 2.0;
  private static final double START_WALK_MAX = 3.0;
  private static final double WALK_AFTER_LANDING = 0.5;
  private static final double WALK_STEP_LIMIT = 1.5;
  private static final float MAX_HOP_TURN_DEGREES = 70.0f;
  private static final float PREVIEW_ENGAGE_DEGREES = 35.0f;
  private static final float PREVIEW_RELEASE_DEGREES = 55.0f;
  private static final double PREVIEW_EXPIRE_FRACTION = 0.5;
  private static final int PREVIEW_RETRY_TICKS = 3;
  private static final int AIM_RESOLVE_GRACE_TICKS = 6;
  private static final int AIM_RETRY_TICKS = 4;
  private static final float USE_TOLERANCE_DEGREES = 2.0f;
  private static final int AIM_TIMEOUT_TICKS = 40;
  private static final int AIM_DWELL_JITTER_TICKS = 3;
  private static final int USE_CONFIRM_TIMEOUT_TICKS = 10;
  private static final int MIN_SETTLE_TICKS = 2;
  private static final int MAX_SETTLE_TICKS = 40;
  private static final int LATE_LANDING_WATCH_TICKS = 20;
  // Held through a run of hops: swapping back after every landing replays the equip animation
  // twice a second, which reads as the item glitching out of the hand.
  private static final int RESTORE_MIN_TICKS = 50;
  private static final int RESTORE_MAX_TICKS = 60;

  /** Progress an exact hop needs when the body is already in the air from an unplanned jump. */
  private static final double MIN_AIRBORNE_SAVED = 2.0;

  private static final int FAILED_REUSE_DELAY_TICKS = 20;
  private static final int MIN_REFUSAL_COOLDOWN_TICKS = 5;
  private static final int REFUSED_HOP_TICKS = 40;
  private static final int REFUSED_HOP_SPREAD = 2;
  private static final int NO_MANA_BACKOFF_TICKS = 200;
  private static final int MAX_ROUTE_FAILURES = 3;
  private static final int REASON_LOG_EVERY_TICKS = 40;
  private static final double TELEPORT_CONFIRM_BLOCKS = 4.0;
  private static final double EXPECTED_LANDING_TOLERANCE = 2.5;
  private static final double ROLLBACK_BLOCKS = 4.0;
  private static final Pattern RANGE_LORE =
      Pattern.compile("(?i)Instant Transmission.*?Teleport\\s+(\\d+)\\s+blocks?\\s+ahead");
  private static final Pattern MANA_LORE =
      Pattern.compile("(?i)Instant Transmission.*?Mana Cost:\\s*([0-9][0-9,]*)");

  /** One usable Aspect in the hotbar. */
  record Aspect(int slot, double range, int manaCost) {}

  /**
   * A reachable hop: the trail step it lands on, where the feet end up, the point to look at, and
   * where the teleport itself puts the feet (above the landing for a climb, which then drops).
   */
  record Hop(int trailIndex, Vec3 landing, Vec3 aimPoint, Vec3 arrival, boolean needsJump) {}

  enum Phase {
    IDLE,
    AIMING,
    CLICKED,
    SETTLING,
    COOLDOWN
  }

  private static final Logger logger = LoggerFactory.getLogger("ryzn-transmission");

  private final Minecraft client;
  private Phase phase = Phase.IDLE;
  private Hop hop;
  private Aspect aspect;
  private int phaseTicks;
  private int alignedTicks;
  private int aimDwellTicks;
  private int reuseDelayTicks;
  private int restoreSlot = -1;
  private int heldAspectSlot = -1;
  private int restoreDelayTicks;
  private Angle heldAim;
  private Hop preview;

  /** True when the last {@link #update} look is only a heads-up toward the next hop. */
  @Getter(AccessLevel.PACKAGE)
  private boolean previewAim;

  private boolean previewEngaged;
  private int previewRetryTicks;
  private int aimResolveMisses;
  private Vec3 preUsePosition;
  private Vec3 lastClickedPosition;
  private double preUseSpeed;
  private int lateWatchTicks;
  private int lateTargetIndex = -1;
  private Vec3 landedPosition;
  private Vec3 hopOrigin;
  private int rollbackWatchTicks;
  private String rejectReason = "";
  private int reasonLogTicks;
  private int refusedIndex = -1;
  private int refusedTicks;

  // Route-wide state: survives replans toward the same goal.
  private double walkedOnRoute;
  private double startWalkNeeded = START_WALK_MIN;
  private double walkedSinceLanding = Double.POSITIVE_INFINITY;
  private Vec3 lastWalkPosition;
  private int routeFailures;
  private Level blockedLevel;

  private final ItemStack[] cachedStacks = new ItemStack[MacroSlots.RESERVED_HOTBAR_SLOT];
  private final Aspect[] cachedAspects = new Aspect[MacroSlots.RESERVED_HOTBAR_SLOT];

  /**
   * A fresh start re-arms the short walk-in and clears the failure count. A live retarget while
   * walking (a replan, or combat chasing a moving mob) keeps both, so hops keep flowing.
   */
  void beginRoute(BlockPos goal, boolean walking) {
    // Trail indices are renumbered by any new route, so a refused landing no longer points at it.
    refusedTicks = 0;
    if (walking) {
      return;
    }
    walkedOnRoute = 0.0;
    startWalkNeeded =
        constantUse()
            ? 0.0
            : ThreadLocalRandom.current().nextDouble(START_WALK_MIN, START_WALK_MAX);
    walkedSinceLanding = Double.POSITIVE_INFINITY;
    lastWalkPosition = null;
    routeFailures = 0;
  }

  /**
   * True from the click until the landing settles: the position jump is not drift, a wedge or a
   * stall, so the walker's watchdogs stand down. The walker keeps its keys the whole time.
   */
  boolean suppressesWatchdogs() {
    return phase == Phase.CLICKED || phase == Phase.SETTLING;
  }

  /** The walker keeps walking and sprinting through a hop; only a jump around the click is held. */
  void shapeKeys(List<KeyMapping> keys) {
    if (client.options == null) {
      return;
    }
    // A climb teleport rides the walker's own jump up the step; any other hop holds the jump.
    boolean climbing = phase == Phase.AIMING && hop != null && hop.needsJump();
    if (!climbing && (phase == Phase.AIMING || phase == Phase.CLICKED)) {
      keys.remove(client.options.keyJump);
    }
  }

  /**
   * Watches the previous tick's click. Returns the trail step to resume from after a confirmed
   * landing, or -1.
   */
  int observe(List<Vec3> trail, int cursor) {
    LocalPlayer player = client.player;
    if (player == null) {
      return -1;
    }
    Vec3 position = player.position();

    if (phase == Phase.CLICKED) {
      phaseTicks++;
      if (confirmed(
          preUsePosition,
          lastClickedPosition,
          position,
          hop == null ? null : hop.arrival(),
          preUseSpeed,
          phaseTicks)) {
        int target = hop == null ? cursor : hop.trailIndex();
        int resynced = resyncCursor(trail, cursor, target, position);
        phase = Phase.SETTLING;
        phaseTicks = 0;
        routeFailures = 0;
        landed(position);
        log(
            "Instant Transmission landed: moved %.2f blocks, step %d -> %d",
            flatDistance(preUsePosition, position), cursor, resynced);
        return resynced;
      }
      lastClickedPosition = position;
      if (phaseTicks >= USE_CONFIRM_TIMEOUT_TICKS) {
        log("Instant Transmission never landed after %d ticks", phaseTicks);
        Vec3 before = preUsePosition;
        Vec3 expected = hop == null ? null : hop.arrival();
        int target = hop == null ? cursor : hop.trailIndex();
        double speed = preUseSpeed;
        failed(FAILED_REUSE_DELAY_TICKS);
        // A laggy server can still apply it: keep watching so a late jump resyncs the cursor.
        preUsePosition = before;
        lastClickedPosition = position;
        hop = expected == null ? null : new Hop(target, expected, expected, expected, false);
        preUseSpeed = speed;
        lateTargetIndex = target;
        lateWatchTicks = LATE_LANDING_WATCH_TICKS;
      }
      return -1;
    }

    if (lateWatchTicks > 0) {
      lateWatchTicks--;
      if (confirmed(
          preUsePosition,
          lastClickedPosition,
          position,
          hop == null ? null : hop.arrival(),
          preUseSpeed,
          USE_CONFIRM_TIMEOUT_TICKS + LATE_LANDING_WATCH_TICKS - lateWatchTicks)) {
        lateWatchTicks = 0;
        routeFailures = Math.max(0, routeFailures - 1);
        landed(position);
        hop = null;
        log("Instant Transmission landed late, resyncing from step %d", cursor);
        return resyncCursor(trail, cursor, Math.max(cursor, lateTargetIndex), position);
      }
      lastClickedPosition = position;
    }

    if (rollbackWatchTicks > 0 && landedPosition != null && hopOrigin != null) {
      rollbackWatchTicks--;
      if (flatDistance(position, landedPosition) > ROLLBACK_BLOCKS
          && flatDistance(position, hopOrigin) < flatDistance(position, landedPosition)) {
        // The server put us back. The walker's drift watch replans from here.
        log("Instant Transmission was rolled back by the server");
        rollbackWatchTicks = 0;
        failed(FAILED_REUSE_DELAY_TICKS);
      }
    }

    if (restoreDelayTicks > 0 && --restoreDelayTicks == 0) {
      restoreSelectedSlot();
    }

    if (phase == Phase.SETTLING) {
      phaseTicks++;
      boolean down = player.onGround() || player.isInWater();
      if (phaseTicks >= MAX_SETTLE_TICKS || phaseTicks >= MIN_SETTLE_TICKS && down) {
        enterCooldown(relaxedWaitTicks());
      }
    } else if (phase == Phase.COOLDOWN && reuseDelayTicks > 0 && --reuseDelayTicks == 0) {
      phase = Phase.IDLE;
    }
    return -1;
  }

  /**
   * Picks or refreshes a hop. Returns the look it needs, or null when the walker keeps the head.
   */
  Angle update(
      List<Vec3> trail,
      int cursor,
      IntToDoubleFunction footY,
      float movementYaw,
      boolean mayOwnLook) {
    PathfinderSettings settings = RyznSettings.get().pathfinder();
    LocalPlayer player = client.player;
    if (player != null) {
      trackWalk(player);
    }
    if (!settings.isTransmissionEnabled()
        || player == null
        || client.level == null
        || client.gui.screen() != null
        || client.level == blockedLevel) {
      reset();
      return null;
    }
    if (phase == Phase.IDLE || phase == Phase.AIMING) {
      aspect = findAspect(player);
      if (aspect == null) {
        reject("no Aspect of the Void/End with a readable range in hotbar slots 1-8");
        reset();
        return null;
      }
    }
    if (handleRefusal()) {
      return null;
    }
    previewAim = false;
    if (!mayOwnLook) {
      if (phase == Phase.AIMING) {
        enterCooldown(relaxedWaitTicks());
      }
      return null;
    }

    switch (phase) {
      case CLICKED -> {
        // Hold the bearing the ability fired along until the landing is confirmed.
        return heldAim;
      }
      case SETTLING -> {
        // Just landed facing the hop direction: keep that look unless the next hop is in view.
        Angle ahead = previewLook(trail, cursor, footY, player, movementYaw);
        return ahead != null ? ahead : heldAim;
      }
      case COOLDOWN -> {
        return previewLook(trail, cursor, footY, player, movementYaw);
      }
      case IDLE -> {
        if (!readyForNextHop(player)) {
          return previewLook(trail, cursor, footY, player, movementYaw);
        }
        Hop next = findHop(trail, cursor, footY, player, false);
        if (next == null) {
          return previewLook(trail, cursor, footY, player, movementYaw);
        }
        float turn =
            Math.abs(
                AngleUtil.normalizeAngle(
                    AngleUtil.getRotation(player.getEyePosition(1.0f), next.aimPoint()).getYaw()
                        - movementYaw));
        if (turn > MAX_HOP_TURN_DEGREES) {
          // The walker keeps walking the route while the head aims; a bearing this far off it
          // would have the body crab sideways.
          reject(String.format("hop bearing %.0f deg off the route heading", turn));
          return previewLook(trail, cursor, footY, player, movementYaw);
        }
        if (ThreadLocalRandom.current().nextInt(100)
            < settings.getTransmissionSkipChancePercent()) {
          // Walk this stretch like a player who did not bother; decide again a little later.
          reject("skip chance rolled, walking this stretch");
          enterCooldown(relaxedWaitTicks() / 2);
          return null;
        }
        hop = next;
        phase = Phase.AIMING;
        phaseTicks = 0;
        alignedTicks = 0;
        aimResolveMisses = 0;
        // Still holding the Aspect from the last hop: keep it out instead of swapping back.
        restoreDelayTicks = 0;
        int aimDelay = settings.getTransmissionAimDelayTicks();
        aimDwellTicks =
            aimDelay
                + ThreadLocalRandom.current()
                    .nextInt(Math.min(AIM_DWELL_JITTER_TICKS, aimDelay) + 1);
        log(
            "Instant Transmission aiming: step %d -> %d, %.1f blocks, turn %.0f deg",
            cursor, hop.trailIndex(), flatDistance(player.position(), hop.landing()), aimTurn());
      }
      default -> {
        // AIMING: the body may still be coasting, so the hop is re-solved from where it is now.
        phaseTicks++;
        Hop next = findHop(trail, cursor, footY, player, false);
        aimResolveMisses = next == null ? aimResolveMisses + 1 : 0;
        // One bad re-solve while the body coasts is noise; keep the last good hop through it.
        if (aimResolveMisses > AIM_RESOLVE_GRACE_TICKS || phaseTicks > AIM_TIMEOUT_TICKS) {
          log(
              "Instant Transmission aim dropped (%s)",
              phaseTicks > AIM_TIMEOUT_TICKS ? "timeout" : rejectReason);
          enterCooldown(AIM_RETRY_TICKS);
          return null;
        }
        if (next != null) {
          hop = next;
        }
      }
    }

    heldAim = AngleUtil.getRotation(nextEye(player), hop.aimPoint());
    preview = hop;
    previewEngaged = true;
    return heldAim;
  }

  /**
   * Between hops the head eases onto the next hop's landing, level like the hop itself, so the aim
   * that follows is a small correction instead of a flick. The landing is a fixed world point kept
   * until the body closes on it, so its bearing drifts smoothly instead of hopping between
   * candidates, and the look engages and releases on separate thresholds so it never flickers.
   */
  private Angle previewLook(
      List<Vec3> trail, int cursor, IntToDoubleFunction footY, LocalPlayer player, float yaw) {
    if (routeFailures >= MAX_ROUTE_FAILURES) {
      preview = null;
      previewEngaged = false;
      return null;
    }
    if (preview != null
        && (preview.trailIndex() <= cursor
            || player.position().distanceTo(preview.landing())
                < aspectRange() * PREVIEW_EXPIRE_FRACTION)) {
      preview = null;
    }
    if (preview == null && player.onGround() && --previewRetryTicks <= 0) {
      previewRetryTicks = PREVIEW_RETRY_TICKS;
      preview = findHop(trail, cursor, footY, player, true);
    }
    if (preview == null) {
      previewEngaged = false;
      return null;
    }
    // Level at eye height over the landing, whatever the hop's own pitch will be.
    Vec3 eye = player.getEyePosition(1.0f);
    Angle look = AngleUtil.getRotation(eye, preview.landing().add(0.0, eye.y - player.getY(), 0.0));
    float off = Math.abs(AngleUtil.normalizeAngle(look.getYaw() - yaw));
    previewEngaged = off <= (previewEngaged ? PREVIEW_RELEASE_DEGREES : PREVIEW_ENGAGE_DEGREES);
    if (!previewEngaged) {
      return null;
    }
    previewAim = true;
    return look;
  }

  void reset() {
    restoreSelectedSlot();
    restoreDelayTicks = 0;
    lateWatchTicks = 0;
    rollbackWatchTicks = 0;
    landedPosition = null;
    hopOrigin = null;
    lateTargetIndex = -1;
    aspect = null;
    phase = Phase.IDLE;
    hop = null;
    alignedTicks = 0;
    phaseTicks = 0;
    reuseDelayTicks = 0;
    preview = null;
    aimResolveMisses = 0;
    preUsePosition = null;
    lastClickedPosition = null;
    preUseSpeed = 0.0;
    heldAim = null;
    previewEngaged = false;
    refusedTicks = 0;
  }

  /** Clicks once the head is on the hop bearing; the body keeps walking throughout. */
  void tryUse(Angle target) {
    LocalPlayer player = client.player;
    if (phase != Phase.AIMING || target == null || player == null || hop == null) {
      alignedTicks = 0;
      return;
    }
    // Sneak + right click is Ether Transmission on a merged Aspect: never click while crouching.
    if (client.options.keyShift.isDown() || player.isShiftKeyDown()) {
      reject("sneaking");
      enterCooldown(relaxedWaitTicks() / 2);
      return;
    }

    float yawError = Math.abs(Mth.wrapDegrees(target.getYaw() - player.getYRot()));
    float pitchError = Math.abs(target.getPitch() - player.getXRot());
    // Hold the Aspect out for the whole aim, the way a player readies it before turning.
    if (player.getInventory().getSelectedSlot() != aspect.slot()) {
      if (restoreSlot < 0) {
        restoreSlot = player.getInventory().getSelectedSlot();
      }
      restoreDelayTicks = 0;
      MacroInput.selectHotbarSlot(player, aspect.slot());
      heldAspectSlot = aspect.slot();
      alignedTicks = 0;
      return;
    }

    double speed = Math.hypot(player.getDeltaMovement().x, player.getDeltaMovement().z);
    if (yawError > USE_TOLERANCE_DEGREES
        || pitchError > USE_TOLERANCE_DEGREES
        || hop.needsJump() && player.onGround()) {
      alignedTicks = 0;
      return;
    }
    if (++alignedTicks < aimDwellTicks) {
      return;
    }
    if (!crosshairClear()) {
      reject("something under the crosshair");
      refuseHop();
      return;
    }
    if (!enoughMana()) {
      reject("not enough mana on the action bar");
      enterCooldown(relaxedWaitTicks());
      return;
    }

    preUsePosition = player.position();
    lastClickedPosition = preUsePosition;
    preUseSpeed = speed;
    // Through the use key, so the game sends exactly what a real right click sends.
    MacroInput.useItemClick(client);
    phase = Phase.CLICKED;
    phaseTicks = 0;
    alignedTicks = 0;
    log("Used Instant Transmission toward step %d (speed %.3f)", hop.trailIndex(), speed);
  }

  /**
   * The best hop along the trail, of three kinds:
   *
   * <ul>
   *   <li>Range crossing: the ability travels its full range, so a trail point that far from the
   *       feet is a level landing. A zig-zag crosses the range several times; how the trail bends
   *       before the landing does not matter.
   *   <li>Exact: looking down at a trail step's floor within range stops the ability on that very
   *       block, so the feet land exactly there. Preferred at equal progress.
   *   <li>Climb: instead of jumping a step up, look up over it so the full-range ray ends straight
   *       above the step, then drop onto it. Works at any distance, even under the minimum range.
   * </ul>
   *
   * The furthest landing along the trail wins.
   */
  private Hop findHop(
      List<Vec3> trail, int cursor, IntToDoubleFunction footY, LocalPlayer player, boolean quiet) {
    if (trail == null || trail.isEmpty()) {
      if (!quiet) {
        reject("no trail");
      }
      return null;
    }
    double range = aspectRange();
    // Solved from where the body will be next tick, so momentum cannot carry the aim off.
    Vec3 position = player.position().add(player.getDeltaMovement());
    Vec3 eye = nextEye(player);
    double eyeHeight = eye.y - position.y;
    boolean airborne = !player.onGround();
    double endBuffer = RyznSettings.get().pathfinder().getTransmissionEndBufferBlocks();
    double[] along = cumulativeDistance(trail, position, cursor);
    int start = Math.clamp(cursor, 0, trail.size() - 1);
    Hop best = null;
    int bestScore = Integer.MIN_VALUE;
    int rayMisses = 0;
    int landingMisses = 0;
    Vec3 previous = position;
    double previousGap = -range;
    for (int i = start; i < trail.size(); i++) {
      if (along[i - start] > range * PATH_SCAN_FACTOR) {
        break;
      }
      Vec3 point = trail.get(i);
      Vec3 node = new Vec3(point.x, footY.applyAsDouble(i), point.z);
      boolean usable =
          remainingDistance(trail, i) >= endBuffer
              && (refusedTicks <= 0 || Math.abs(i - refusedIndex) > REFUSED_HOP_SPREAD);
      double gap = position.distanceTo(node) - range;
      // A range crossing assumes a level eye, so it is only taken from the ground.
      if (!airborne
          && previousGap < 0.0 != gap < 0.0
          && usable
          && along[i - start] >= MIN_PATH_SAVED) {
        Hop crossing = hopAt(i, previous, node, previousGap, gap, eye, eyeHeight, player);
        if (crossing == null) {
          rayMisses++;
        } else if (!landingSafe(player, crossing.landing())) {
          landingMisses++;
        } else if (i > bestScore) {
          best = crossing;
          bestScore = i;
        }
      }
      // A step up is jumped as usual; the teleport fires from the jump, looking down at the step.
      boolean climb = !airborne && node.y - player.getY() >= MIN_CLIMB_RISE;
      double minSaved = climb ? MIN_CLIMB_DISTANCE : airborne ? MIN_AIRBORNE_SAVED : MIN_PATH_SAVED;
      if (usable && along[i - start] >= minSaved && i + EXACT_HOP_BONUS > bestScore) {
        Vec3 from = climb ? eye.add(0.0, JUMP_EYE_LIFT, 0.0) : eye;
        Hop exact = exactHop(i, node, from, range, player, climb);
        if (exact != null) {
          best = exact;
          bestScore = i + EXACT_HOP_BONUS;
        }
      }
      previous = node;
      previousGap = gap;
    }
    if (best == null && !quiet) {
      reject(
          String.format(
              "no hop in reach (range %.0f, ray blocked %d, landing unsafe %d)",
              range, rayMisses, landingMisses));
    }
    return best;
  }

  /**
   * The landing where segment {@code a -> b} crosses the range, or null when the ray is blocked.
   */
  private Hop hopAt(
      int index,
      Vec3 a,
      Vec3 b,
      double gapA,
      double gapB,
      Vec3 eye,
      double eyeHeight,
      LocalPlayer player) {
    double t = Math.clamp(gapA / (gapA - gapB), 0.0, 1.0);
    Vec3 crossing = a.lerp(b, t);
    // Between steps of different height, aim for the higher one and drop onto the lower.
    Vec3 landing = new Vec3(crossing.x, Math.max(a.y, b.y), crossing.z);
    Vec3 aimPoint = landing.add(0.0, eyeHeight, 0.0);
    if (!lineClear(player, eye, aimPoint)) {
      return null;
    }
    return new Hop(index, landing, aimPoint, landing, false);
  }

  /**
   * Looking down at the floor of trail step {@code index}: the ray must reach that floor's top face
   * first and within range, which stops the ability exactly on it.
   */
  private Hop exactHop(
      int index, Vec3 node, Vec3 eye, double range, LocalPlayer player, boolean needsJump) {
    if (flatDistance(eye, node) < MIN_EXACT_DISTANCE || node.y > eye.y - 0.5) {
      return null;
    }
    Vec3 floor = new Vec3(node.x, node.y, node.z);
    Vec3 ray = floor.subtract(eye);
    double length = ray.length();
    if (length > range || length < 1.0e-6) {
      return null;
    }
    Vec3 past = floor.add(ray.scale(0.5 / length));
    BlockHitResult hit =
        client.level.clip(
            new ClipContext(eye, past, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
    BlockPos support = BlockPos.containing(node.x, node.y - 0.05, node.z);
    if (hit.getType() != HitResult.Type.BLOCK
        || !hit.getBlockPos().equals(support)
        || hit.getDirection() != Direction.UP
        || eye.distanceTo(hit.getLocation()) > range) {
      return null;
    }
    Vec3 landing = new Vec3(hit.getLocation().x, node.y, hit.getLocation().z);
    if (!landingSafe(player, landing)) {
      return null;
    }
    return new Hop(index, landing, hit.getLocation(), landing, needsJump);
  }

  /** The eyes one tick from now, carried by the current velocity. */
  private static Vec3 nextEye(LocalPlayer player) {
    return player.getEyePosition(1.0f).add(player.getDeltaMovement());
  }

  /** Walking distance from the player to each trail step from {@code cursor} on. */
  private static double[] cumulativeDistance(List<Vec3> trail, Vec3 position, int cursor) {
    int start = Math.clamp(cursor, 0, trail.size() - 1);
    double[] along = new double[trail.size() - start];
    Vec3 previous = position;
    double run = 0.0;
    for (int i = start; i < trail.size(); i++) {
      run += previous.distanceTo(trail.get(i));
      along[i - start] = run;
      previous = trail.get(i);
    }
    return along;
  }

  private boolean landingSafe(LocalPlayer player, Vec3 landing) {
    BlockPos feet = BlockPos.containing(landing.x, landing.y + 0.05, landing.z);
    BlockPos support = BlockPos.containing(landing.x, landing.y - 0.05, landing.z);
    if (!WalkCheck.canStandOn(support)) {
      // Aimed at the higher of two steps: a short drop onto the lower one is fine.
      support = support.below();
    }
    if (!WalkCheck.canStandOn(support)
        || WalkCheck.isWater(feet)
        || WalkCheck.isWater(support)
        || WalkCheck.isLava(feet)
        || WalkCheck.isLava(support)) {
      return false;
    }
    return client.level.noCollision(
        player,
        player
            .getBoundingBox()
            .move(landing.x - player.getX(), landing.y - player.getY(), landing.z - player.getZ()));
  }

  private boolean lineClear(LocalPlayer player, Vec3 from, Vec3 to) {
    BlockHitResult hit =
        client.level.clip(
            new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
    return hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceToSqr(to) < 0.25;
  }

  private float aimTurn() {
    LocalPlayer player = client.player;
    if (player == null || hop == null) {
      return 0.0f;
    }
    Angle aim = AngleUtil.getRotation(player.getEyePosition(1.0f), hop.aimPoint());
    return Math.abs(AngleUtil.normalizeAngle(aim.getYaw() - player.getYRot()));
  }

  /**
   * The aimed hop cannot be clicked from here. Skip its landing for a while and wait at least a
   * short beat: with a 0 s wait the old 1-tick cooldown re-picked the same hop every tick, flicking
   * the head onto it and away again without ever firing.
   */
  private void refuseHop() {
    if (hop != null) {
      refusedIndex = hop.trailIndex();
      refusedTicks = REFUSED_HOP_TICKS;
    }
    enterCooldown(Math.max(MIN_REFUSAL_COOLDOWN_TICKS, relaxedWaitTicks() / 2));
  }

  private void enterCooldown(int ticks) {
    if (phase == Phase.SETTLING && restoreSlot >= 0) {
      // Swap back a beat after landing rather than on the same tick.
      restoreDelayTicks =
          ThreadLocalRandom.current().nextInt(RESTORE_MIN_TICKS, RESTORE_MAX_TICKS + 1);
    } else {
      restoreSelectedSlot();
    }
    lateWatchTicks = 0;
    phase = Phase.COOLDOWN;
    reuseDelayTicks = Math.max(1, ticks);
    hop = null;
    alignedTicks = 0;
    phaseTicks = 0;
    aimResolveMisses = 0;
    preUsePosition = null;
    lastClickedPosition = null;
    preUseSpeed = 0.0;
  }

  private void restoreSelectedSlot() {
    LocalPlayer player = client.player;
    // Only undo our own swap: if a macro has since taken out its weapon, leave the hand alone.
    if (restoreSlot >= 0
        && player != null
        && player.getInventory().getSelectedSlot() == heldAspectSlot) {
      MacroInput.selectHotbarSlot(player, restoreSlot);
    }
    restoreSlot = -1;
    heldAspectSlot = -1;
  }

  private void trackWalk(LocalPlayer player) {
    Vec3 position = player.position();
    if (lastWalkPosition != null) {
      double step = flatDistance(lastWalkPosition, position);
      // A teleport is not walking, so it never counts toward the walk-in.
      if (step < WALK_STEP_LIMIT) {
        walkedOnRoute += step;
        walkedSinceLanding += step;
      }
    }
    lastWalkPosition = position;
    if (reasonLogTicks > 0) {
      reasonLogTicks--;
    }
    if (refusedTicks > 0) {
      refusedTicks--;
    }
  }

  private boolean readyForNextHop(LocalPlayer player) {
    if (routeFailures >= MAX_ROUTE_FAILURES) {
      reject("failed " + routeFailures + " times, off for this route");
      return false;
    }
    double afterLanding = constantUse() ? 0.0 : WALK_AFTER_LANDING;
    if (walkedOnRoute < startWalkNeeded || walkedSinceLanding < afterLanding) {
      return false;
    }
    return true;
  }

  private void landed(Vec3 position) {
    hopOrigin = preUsePosition;
    landedPosition = position;
    walkedSinceLanding = 0.0;
    rollbackWatchTicks = LATE_LANDING_WATCH_TICKS;
    lastWalkPosition = position;
  }

  private void failed(int backoffTicks) {
    routeFailures++;
    if (routeFailures >= MAX_ROUTE_FAILURES) {
      log("Instant Transmission failed %d times, off for the rest of this route", routeFailures);
    }
    enterCooldown(backoffTicks);
  }

  /** True when Hypixel refused the ability; backs off by how it refused. */
  private boolean handleRefusal() {
    switch (TransmissionSignals.INSTANCE.takeRefusal()) {
      case NO_MANA -> {
        log("Instant Transmission: not enough mana, backing off");
        enterCooldown(NO_MANA_BACKOFF_TICKS);
        return true;
      }
      case COOLDOWN -> {
        enterCooldown(relaxedWaitTicks());
        return true;
      }
      case NOT_HERE -> {
        log("Instant Transmission is not allowed here, off until the world changes");
        blockedLevel = client.level;
        reset();
        return true;
      }
      default -> {
        return false;
      }
    }
  }

  private boolean enoughMana() {
    int mana = TransmissionSignals.INSTANCE.mana();
    // Unknown mana is allowed; a refusal in chat backs it off instead.
    return mana < 0 || aspect == null || mana >= aspect.manaCost();
  }

  /**
   * Nothing under the crosshair a right click would interact with, so the click can only fire the
   * ability, never open, press or hit. Plain terrain is fine: a use on a block that does nothing
   * with it falls through to the item, exactly as a player's click would. Refusing every block in
   * reach blocked any hop with a wall or the floor within a few blocks of the aim.
   */
  private boolean crosshairClear() {
    HitResult hit = client.hitResult;
    if (hit == null || hit.getType() == HitResult.Type.MISS) {
      return true;
    }
    if (!(hit instanceof BlockHitResult blockHit) || client.level == null) {
      return false; // an entity: the click would interact with or hit it
    }
    BlockPos pos = blockHit.getBlockPos();
    BlockState state = client.level.getBlockState(pos);
    return client.level.getBlockEntity(pos) == null
        && state.getMenuProvider(client.level, pos) == null
        && !state.is(BlockTags.DOORS)
        && !state.is(BlockTags.TRAPDOORS)
        && !state.is(BlockTags.FENCE_GATES)
        && !state.is(BlockTags.BUTTONS)
        && !state.is(Blocks.LEVER);
  }

  private double aspectRange() {
    return aspect == null ? DEFAULT_RANGE : aspect.range();
  }

  /** Logs why no hop was taken, at most every couple of seconds so the log stays readable. */
  private void reject(String reason) {
    boolean changed = !reason.equals(rejectReason);
    rejectReason = reason;
    if (changed || reasonLogTicks == 0) {
      reasonLogTicks = REASON_LOG_EVERY_TICKS;
      logger.info("[transmission] {}", reason);
    }
  }

  /** A 0 s maximum wait is the "use it constantly" setup: no walk-in and no walk between hops. */
  private static boolean constantUse() {
    return RyznSettings.get().pathfinder().getTransmissionWaitMaxTenths() == 0;
  }

  private static int relaxedWaitTicks() {
    PathfinderSettings settings = RyznSettings.get().pathfinder();
    int min = settings.getTransmissionWaitMinTenths() * 2;
    int max = Math.max(min, settings.getTransmissionWaitMaxTenths() * 2);
    return ThreadLocalRandom.current().nextInt(min, max + 1);
  }

  /** The longest-range Aspect in the usable hotbar, or null. */
  private Aspect findAspect(LocalPlayer player) {
    Aspect best = null;
    for (int slot = 0; slot < MacroSlots.RESERVED_HOTBAR_SLOT; slot++) {
      ItemStack stack = player.getInventory().getItem(slot);
      if (stack != cachedStacks[slot]) {
        cachedStacks[slot] = stack;
        cachedAspects[slot] = readAspect(slot, stack);
      }
      Aspect candidate = cachedAspects[slot];
      if (candidate != null && (best == null || candidate.range() > best.range())) {
        best = candidate;
      }
    }
    return best;
  }

  static Aspect readAspect(int slot, ItemStack stack) {
    String id = MacroItems.skyblockId(stack);
    String name = MacroItems.stackName(stack);
    if (!id.equals("ASPECT_OF_THE_VOID")
        && !id.equals("ASPECT_OF_THE_END")
        && !name.contains("Aspect of the Void")
        && !name.contains("Aspect of the End")) {
      return null;
    }
    String lore = MacroItems.stackLoreText(stack);
    int range = parseInt(RANGE_LORE, lore);
    if (range < 0) {
      range = MIN_RANGE + tunedTransmission(stack);
    }
    return new Aspect(
        slot, Math.clamp(range, MIN_RANGE, MAX_RANGE), Math.max(0, parseInt(MANA_LORE, lore)));
  }

  private static int tunedTransmission(ItemStack stack) {
    CustomData data = stack.get(DataComponents.CUSTOM_DATA);
    if (data == null || data.isEmpty()) {
      return 0;
    }
    CompoundTag root = data.copyTag();
    return root.getCompound("ExtraAttributes").orElse(root).getInt("tuned_transmission").orElse(0);
  }

  private static int parseInt(Pattern pattern, String text) {
    Matcher matcher = pattern.matcher(text);
    if (!matcher.find()) {
      return -1;
    }
    try {
      return Integer.parseInt(matcher.group(1).replace(",", ""));
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  static boolean confirmed(
      Vec3 before,
      Vec3 previous,
      Vec3 after,
      Vec3 expected,
      double measuredSpeed,
      int elapsedTicks) {
    if (before == null || previous == null || after == null) {
      return false;
    }
    double total = before.distanceTo(after);
    double oneTick = previous.distanceTo(after);
    double ordinaryTravel = Math.max(0.0, measuredSpeed) * (Math.max(1, elapsedTicks) + 1.0) + 1.0;
    double jumpThreshold =
        Math.max(TELEPORT_CONFIRM_BLOCKS, Math.max(0.0, measuredSpeed) * 2.0 + 1.0);
    return oneTick >= jumpThreshold
        || expected != null
            && after.distanceTo(expected) <= EXPECTED_LANDING_TOLERANCE
            && total >= TELEPORT_CONFIRM_BLOCKS * 0.5
            && total > ordinaryTravel;
  }

  static int resyncCursor(List<Vec3> trail, int cursor, int target, Vec3 position) {
    if (trail == null || trail.isEmpty() || position == null) {
      return Math.max(0, cursor);
    }
    int start = Math.clamp(cursor, 0, trail.size() - 1);
    int end = Math.min(trail.size() - 1, Math.max(start, target + 4));
    int best = start;
    double bestDistance = position.distanceTo(trail.get(start));
    for (int i = start + 1; i <= end; i++) {
      double distance = position.distanceTo(trail.get(i));
      if (distance < bestDistance) {
        best = i;
        bestDistance = distance;
      }
    }
    if (best + 1 <= end) {
      Vec3 previous = trail.get(best > start ? best - 1 : best);
      Vec3 next = trail.get(best + 1);
      double tangentX = next.x - previous.x;
      double tangentZ = next.z - previous.z;
      double playerX = position.x - trail.get(best).x;
      double playerZ = position.z - trail.get(best).z;
      if (playerX * tangentX + playerZ * tangentZ >= 0.0) {
        best++;
      }
    }
    return Math.max(start, best);
  }

  private static double remainingDistance(List<Vec3> trail, int start) {
    double distance = 0.0;
    for (int i = start + 1; i < trail.size(); i++) {
      distance += trail.get(i - 1).distanceTo(trail.get(i));
    }
    return distance;
  }

  private static double flatDistance(Vec3 a, Vec3 b) {
    if (a == null || b == null) {
      return Double.POSITIVE_INFINITY;
    }
    return Math.hypot(a.x - b.x, a.z - b.z);
  }

  /** Hop events always reach latest.log while transmission is on; chat only with NAV debug. */
  private static void log(String message, Object... parts) {
    logger.info("[transmission] {}", String.format(message, parts));
    DebugLog.trace(DebugLog.Category.NAV, message, parts);
  }
}
