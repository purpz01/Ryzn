package ryzn.macro.client.feature.macros.impl.foragingquest;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import ryzn.macro.client.core.RyznBootstrap;
import ryzn.macro.client.feature.macros.impl.level7.AutoLvl7Macro;
import ryzn.macro.client.feature.reconnect.EvictionLines;
import ryzn.macro.client.feature.reconnect.MacroReconnect;

/**
 * Puts the Foraging Quest Unlocker back to work after something took the player away from the wood
 * it was cutting: {@link MacroReconnect} walking the client back into SkyBlock, or a death.
 *
 * <p>"Was this run interrupted" is measured against the START of the recovery, not against arrival:
 * a limbo route stops the unlocker through the world-change failsafe before the chat line even
 * lands, and the walk back (settle, {@code /lobby}, a laggy {@code /skyblock}) eats most of a
 * minute, so measuring on arrival timed out a run that really had just been evicted.
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class ForagingQuestRecovery {

  /**
   * How long before the eviction the unlocker must have been running for this to count as its run.
   */
  private static final long RESUME_WINDOW_MS = 60_000L;

  /**
   * Respawn, then the rotation failsafe stopping the run (it trips on the respawn's own camera
   * snap).
   */
  private static final int DEATH_SETTLE_TICKS = 20 * 8;

  /** The macro whose run this recovers. */
  private final ForagingQuestUnlockerMacro unlocker;

  private long lastRunningMs = 0L;
  private int deathSettleTicks;

  /**
   * @description Registers the reconnect rejoin and disarm listeners. A manual stop, import, or
   *     loader shutdown disarms the pending recovery so it cannot restart the old run.
   */
  void registerListeners() {
    MacroReconnect.registerRecoveryListeners(this::onRejoin, this::disarm);
  }

  /**
   * @description Forgets the last running time and any pending death countdown.
   */
  private void disarm() {
    lastRunningMs = 0L;
    deathSettleTicks = 0;
  }

  /**
   * @description Records when the run was last live and resumes it once a death has settled.
   */
  void onClientTick() {
    if (unlocker.isEnabled()) {
      lastRunningMs = System.currentTimeMillis();
      // The countdown deliberately does not run while the unlocker is still up: dying arms it, and
      // the
      // failsafe stopping the run a few seconds later is what lets it tick.
      // ponytail: a death with the rotation failsafe off leaves the run going at spawn - the
      // grind's own
      // stall watchdog is what handles that, this only recovers a run that was actually stopped.
      return;
    }
    if (deathSettleTicks > 0 && --deathSettleTicks == 0 && !levelSevenOwnsRun()) {
      unlocker.resumeAfterDeath();
    }
  }

  /**
   * @description Arms the walk back on the player's own death line. Tested while the run is still
   *     live - the death line lands before the failsafe stop.
   * @param clean the normalized chat line
   */
  void onChat(String clean) {
    if (!unlocker.isEnabled() || !EvictionLines.isOwnDeath(clean)) {
      return;
    }
    deathSettleTicks = DEATH_SETTLE_TICKS;
  }

  /**
   * @description Inside the Level 7 route, the route takes the run back to the Hub and restarts
   *     this unlocker itself; resuming it here too would race that.
   * @return true while the Level 7 route owns the interrupted run
   */
  private static boolean levelSevenOwnsRun() {
    return RyznBootstrap.macroManager().getByClass(AutoLvl7Macro.class).ownsInterruptedRun();
  }

  /**
   * @description Resumes a run that was live within the resume window before the reconnect began.
   */
  private void onRejoin() {
    if (unlocker.isEnabled()
        || RyznBootstrap.macroManager().getByClass(AutoLvl7Macro.class).ownsRejoin()) {
      return;
    }
    if (lastRunningMs == 0L
        || MacroReconnect.recoveryStartedMs() == 0L
        || MacroReconnect.recoveryStartedMs() - lastRunningMs >= RESUME_WINDOW_MS) {
      return;
    }
    unlocker.resumeAfterRejoin();
  }
}
