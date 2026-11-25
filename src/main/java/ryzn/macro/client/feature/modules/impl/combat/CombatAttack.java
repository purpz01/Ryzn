package ryzn.macro.client.feature.modules.impl.combat;

import java.util.concurrent.ThreadLocalRandom;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import ryzn.macro.client.MacroInput;

/**
 * When to hit, and with what. One entry point ({@link #swing}) that picks a mode and stays in it,
 * instead of the ninety-line chain of five mutually exclusive branches and twelve early returns
 * this replaces.
 *
 * <p>The modes are mutually exclusive by construction - each is a whole answer to "how does this
 * session attack" - so they are five short methods rather than a run of ifs that each had to know
 * what the previous ones had already ruled out:
 *
 * <ul>
 *   <li><b>Stationary melee</b> - a pinned boss. Clicks on its own quick delay, no attack-cooldown
 *       wait.
 *   <li><b>Clustered held item</b> - fires a {@link CombatAbility} across a group instead of
 *       swinging.
 *   <li><b>Held item</b> - a plain right-click use at range.
 *   <li><b>Melee</b> - the normal swing, gated on the vanilla attack cooldown unless instant-attack
 *       is on.
 * </ul>
 *
 * <p>Every path reports to {@link CombatTelemetry} with the reason it did or did not swing, which
 * is what {@code /dev debug} prints. That reporting is the reason the early returns exist at all,
 * so each is paired with its reason rather than falling through to a shared one.
 *
 * <p>Anti-detection: swings are vanilla attack clicks through {@link MacroInput}, jittered; nothing
 * is timed more precisely than a person could click.
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class CombatAttack {

  private final CombatAim aim;

  /** Attack-strength recovery fraction (0-1) required before a swing registers full damage. */
  private static final float ATTACK_READY = 0.92f;

  /** Base ticks between swings before jitter (the cooldown gate usually paces this). */
  static final int BASE_ATTACK_INTERVAL = 4;

  /**
   * Instant-attack ticks between swings before jitter: hit again almost every tick, no cooldown
   * wait.
   */
  private static final int INSTANT_ATTACK_INTERVAL = 1;

  /** A pinned boss is clicked on its own fast band rather than the attack-cooldown curve. */
  private static final int STATIONARY_CLICK_DELAY_MIN = 2;

  private static final int STATIONARY_CLICK_DELAY_MAX_EXCLUSIVE = 4;
  private final CombatAbility ability = new CombatAbility();

  CombatAbility ability() {
    return ability;
  }

  /**
   * Attack if this tick allows it. {@code onAbilityCast} runs when the clustered ability actually
   * fires, so the caller can suppress the group it is expected to have killed.
   */
  void swing(
      Minecraft mc,
      LocalPlayer player,
      CombatConfig cfg,
      CombatState state,
      double dist,
      double range,
      boolean clustered,
      CombatTelemetry telemetry,
      Runnable onAbilityCast) {
    final boolean crosshairReady = mc.crosshairPickEntity == state.target;
    final boolean rayReady = aim.isAttackReady(player, state.target, range, System.nanoTime());
    final CombatModule.ManagedAttackMode mode = cfg.session().attackMode();

    if (mode == CombatModule.ManagedAttackMode.STATIONARY_MELEE) {
      stationaryMelee(mc, state, dist, range, crosshairReady, rayReady, telemetry);
      return;
    }
    if (mode == CombatModule.ManagedAttackMode.CLUSTERED_USE_HELD_ITEM) {
      clusteredAbility(
          mc,
          player,
          cfg,
          state,
          dist,
          range,
          clustered,
          crosshairReady,
          rayReady,
          telemetry,
          onAbilityCast);
      return;
    }
    if (state.attackDelay > 0) {
      telemetry.traceAttack(
          dist, range, state.tracking, rayReady, crosshairReady, state.attackDelay, false, "delay");
      return;
    }
    if (mode == CombatModule.ManagedAttackMode.USE_HELD_ITEM) {
      useHeldItem(mc, cfg, state, dist, range, crosshairReady, rayReady, telemetry);
      return;
    }
    melee(mc, player, cfg, state, dist, range, crosshairReady, rayReady, telemetry);
  }

  /** A pinned boss: no cooldown wait, just a fast human click band while the crosshair is on it. */
  private void stationaryMelee(
      Minecraft mc,
      CombatState state,
      double dist,
      double range,
      boolean crosshairReady,
      boolean rayReady,
      CombatTelemetry telemetry) {
    if (dist > range || (!crosshairReady && !rayReady)) {
      telemetry.traceAttack(
          dist, range, state.tracking, rayReady, crosshairReady, state.attackDelay, false, "aim");
      return;
    }
    if (state.attackDelay > 0) {
      telemetry.traceAttack(
          dist, range, state.tracking, rayReady, crosshairReady, state.attackDelay, false, "delay");
      return;
    }
    MacroInput.attackClick(mc);
    state.attackDelay = stationaryClickDelayTicks();
    telemetry.traceAttack(
        dist, range, state.tracking, rayReady, crosshairReady, state.attackDelay, true, "click");
  }

  /**
   * Fire the hotbar ability across a group. The swap has to be started before the cooldown ends or
   * the bot stands holding the wrong item waiting; each stage that is not ready simply reports and
   * waits.
   */
  private void clusteredAbility(
      Minecraft mc,
      LocalPlayer player,
      CombatConfig cfg,
      CombatState state,
      double dist,
      double range,
      boolean clustered,
      boolean crosshairReady,
      boolean rayReady,
      CombatTelemetry telemetry,
      Runnable onAbilityCast) {
    final boolean swapsAbilityItem = ability.usesSwap(cfg.session());
    if (!CombatAbility.shouldCast(clustered, ability.isPrepared())
        || !CombatAbility.shouldPrepare(state.abilityDelay, swapsAbilityItem)) {
      return;
    }
    if (swapsAbilityItem && !ability.prepare(player, cfg.session())) {
      telemetry.traceAttack(
          dist,
          range,
          state.tracking,
          rayReady,
          crosshairReady,
          state.abilityDelay,
          false,
          "ability select");
      return;
    }
    if (!CombatAbility.ready(swapsAbilityItem ? ability.swapTicks() : 0, state.abilityDelay)) {
      telemetry.traceAttack(
          dist,
          range,
          state.tracking,
          rayReady,
          crosshairReady,
          state.abilityDelay,
          false,
          "ability cooldown");
      return;
    }
    if (!MacroInput.useHeldItemNow(mc)) {
      telemetry.traceAttack(
          dist, range, state.tracking, true, crosshairReady, 0, false, "ability input");
      return;
    }
    if (swapsAbilityItem) {
      ability.noteCast();
    }
    onAbilityCast.run();
    state.abilityDelay = cfg.session().attackIntervalTicks();
    telemetry.traceAttack(
        dist, range, state.tracking, true, crosshairReady, state.abilityDelay, true, "use");
  }

  /** A plain right-click use, paced by the session's own interval. */
  private void useHeldItem(
      Minecraft mc,
      CombatConfig cfg,
      CombatState state,
      double dist,
      double range,
      boolean crosshairReady,
      boolean rayReady,
      CombatTelemetry telemetry) {
    if (dist > range) {
      telemetry.traceAttack(
          dist, range, state.tracking, rayReady, crosshairReady, state.attackDelay, false, "range");
      return;
    }
    if (!rayReady) {
      telemetry.traceAttack(
          dist, range, state.tracking, false, crosshairReady, state.attackDelay, false, "aim");
      return;
    }
    MacroInput.useItemClick(mc);
    state.attackDelay = cfg.session().attackIntervalTicks();
    telemetry.traceAttack(
        dist, range, state.tracking, true, crosshairReady, state.attackDelay, true, "use");
  }

  /** The normal swing: in range, on the mob, and off cooldown unless instant-attack. */
  private void melee(
      Minecraft mc,
      LocalPlayer player,
      CombatConfig cfg,
      CombatState state,
      double dist,
      double range,
      boolean crosshairReady,
      boolean rayReady,
      CombatTelemetry telemetry) {
    if (!crosshairReady && dist > range) {
      telemetry.traceAttack(
          dist, range, state.tracking, rayReady, crosshairReady, state.attackDelay, false, "range");
      return;
    }
    if (!crosshairReady && !rayReady) {
      telemetry.traceAttack(
          dist, range, state.tracking, false, crosshairReady, state.attackDelay, false, "aim");
      return;
    }
    // One tap per mob: waiting for a full swing would only hold the body up.
    final boolean instant = cfg.instantAttack() || CombatModule.strikesOnce(cfg.session());
    if (!instant && player.getAttackStrengthScale(0f) < ATTACK_READY) {
      telemetry.traceAttack(
          dist,
          range,
          state.tracking,
          rayReady,
          crosshairReady,
          state.attackDelay,
          false,
          "cooldown");
      return;
    }
    MacroInput.attackClick(mc);
    if (CombatModule.strikesOnce(cfg.session())) {
      state.struck = state.target.getUUID();
    }
    state.attackDelay = MacroInput.jitter(instant ? INSTANT_ATTACK_INTERVAL : BASE_ATTACK_INTERVAL);
    telemetry.traceAttack(
        dist, range, state.tracking, rayReady, crosshairReady, state.attackDelay, true, "click");
  }

  static int stationaryClickDelayTicks() {
    return ThreadLocalRandom.current()
        .nextInt(STATIONARY_CLICK_DELAY_MIN, STATIONARY_CLICK_DELAY_MAX_EXCLUSIVE);
  }

  /** Full teardown: give the weapon back (while the hand is still known). */
  void reset(LocalPlayer player) {
    ability.restore(player);
  }
}
