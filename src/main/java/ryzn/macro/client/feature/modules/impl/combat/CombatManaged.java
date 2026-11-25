package ryzn.macro.client.feature.modules.impl.combat;

import java.util.Set;
import java.util.function.Consumer;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

/**
 * The bookkeeping behind a macro's borrowed session: Rev Slayer or Dwarven Commissions hands combat
 * a {@link CombatModule.ManagedSettings}, combat fights on its terms, and hands back a {@link
 * CombatModule.ManagedResult} when it is done.
 *
 * <p>Only what the FIGHT never reads lives here - who to tell when the session ends, how long it
 * may go without a target, and the two "what was true before" flags that let the user's own toggle
 * be restored afterwards. The session flag itself and its settings stay on the module, because
 * nearly every decision in the fight forks on them and reading them through a wrapper would only
 * add a dot.
 *
 * <p>Fields are plain and package-visible, like {@link CombatState}: this is a place to keep the
 * session, not a wall of getters around it.
 *
 * <p>Starting and stopping deliberately stay on the module. Beginning a session has to interleave
 * with {@code reset()} and the enabled flag in a specific order, and that ordering is the module's
 * business - the same reason {@link CombatState} is replaced by an explicit sequence rather than
 * owning its own teardown.
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class CombatManaged {

  private final CombatTargeting targeting;

  /** Told once, when the session ends, with why. */
  Consumer<CombatModule.ManagedResult> callback;

  private int noTargetTicks;
  private int noTargetTimeoutTicks;

  void begin(int noTargetSeconds, Consumer<CombatModule.ManagedResult> onDone) {
    callback = onDone;
    noTargetTicks = 0;
    noTargetTimeoutTicks = noTargetSeconds * 20;
  }

  /**
   * Clear the session and hand back whoever asked to be told, or null. Does not undo any side
   * effects.
   */
  Consumer<CombatModule.ManagedResult> end() {
    final Consumer<CombatModule.ManagedResult> onDone = callback;
    callback = null;
    noTargetTicks = 0;
    return onDone;
  }

  /**
   * Count an empty tick. True when the session has now gone its whole allowance without a target
   * and should be handed back. A zero allowance means "run until told to stop" (a continuous
   * patrol), not "stop now".
   */
  boolean outOfTargets(boolean haveTarget) {
    if (haveTarget) {
      noTargetTicks = 0;
      return false;
    }
    noTargetTicks++;
    return noTargetTimeoutTicks > 0 && noTargetTicks >= noTargetTimeoutTicks;
  }

  /**
   * Point targeting at what this session wants: only its named mobs, its bounds, and always in
   * sight.
   */
  void applyFilters(CombatModule.ManagedSettings session) {
    if (session.verifiedType() != null
        && session.requiredTargetId() == null
        && session.targetNames().isEmpty()) {
      // No nametag to confirm with: every mob of the type is a target.
      targeting.setTargetFilter(Set.of(session.verifiedType().type()), "", null);
    } else if (session.verifiedType() != null && session.requiredTargetId() == null) {
      targeting.setVerifiedTypeFilter(
          session.verifiedType().type(),
          String.join(",", session.targetNames()),
          session.verifiedType().verifyRange());
    } else {
      targeting.setTargetFilter(
          Set.of(), String.join(",", session.targetNames()), session.requiredTargetId());
    }
    targeting.setAvoidDanger(false);
    targeting.setBounds(session.bounds());
    targeting.setRequireSight(true);
    targeting.setForwardOnly(
        CombatModule.managedKeepsForwardFlow(session) || CombatModule.managedUsesPatrol(session));
    targeting.setPreferAhead(CombatModule.zealotFlow(session));
  }
}
