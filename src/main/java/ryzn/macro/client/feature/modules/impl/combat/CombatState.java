package ryzn.macro.client.feature.modules.impl.combat;

import java.util.UUID;
import net.minecraft.world.entity.LivingEntity;

/**
 * Everything true only of the fight currently happening: who is being fought, what the head and the
 * swing timers are doing, and where the managed ability swap has got to.
 *
 * <p>It is one object so that clearing a fight is one act. These fields used to be loose on {@link
 * CombatModule}, and {@code reset()} had to name each one - twice it did not, so a swing delay and
 * a repath hold outlived a target loss, an area exit and a managed start, and the first tick back
 * was silently throttled. {@code reset()} replaces this whole object instead, so a field added here
 * cannot be forgotten.
 *
 * <p>What is NOT here: anything that outlives the fight. The managed session (its settings,
 * callback and no-target timer), the enabled flags, and the helper objects that own their own
 * teardown ({@link CombatNav}, {@link CombatRoam.Patrol}, {@link CombatAbility}, the watchdog, the
 * telemetry) all stay on the module or on the object that owns them.
 *
 * <p>Replacing this object is not by itself a teardown: it drops state, it does not undo side
 * effects. The held hotbar item must be given back BEFORE the swap slots are dropped, and the
 * movement keys, the crouch and the route must still be released explicitly. {@code reset()} stays
 * an ordered sequence for that reason.
 */
final class CombatState {

  /** The mob being fought. Null between fights. */
  LivingEntity target;

  /** Ticks the current target is held before a cost upgrade may switch off it. */
  int commitTicks;

  /**
   * Ticks of forward flow granted across a target handoff, so a kill does not read as a stutter.
   */
  int handoffBridgeTicks;

  /** Combat owns the head (aim + swing); the executor still drives the body. */
  boolean tracking;

  /** Tracking last tick, to detect the gaze hand-off edge. */
  boolean wasTracking;

  /** Ticks after a held target was lost during which the next pick keeps the head lock. */
  int lookHandoffTicks;

  /** This tick a stall recovery forced a direct forward chase. */
  boolean stallBridge;

  /** Ticks remaining before the next allowed swing. */
  int attackDelay;

  /** Ticks remaining before the next managed held-item ability. */
  int abilityDelay;

  /** A strike-once session's last hit, suppressed at the next target pick. Null otherwise. */
  UUID struck;

  /** Ticks {@link #struck} has waited for the mob to show the hit. */
  int struckWaitTicks;

  /** Standing in the pinned-boss melee band; the footwork itself belongs to {@link CombatBody}. */
  boolean bossInMeleeBand;

  /**
   * While a macro has borrowed the head for a scripted look, combat keeps ownership until this
   * passes.
   */
  long managedLookHandoffUntilNanos;
}
