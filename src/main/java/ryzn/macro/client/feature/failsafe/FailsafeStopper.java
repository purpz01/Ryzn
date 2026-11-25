package ryzn.macro.client.feature.failsafe;

import net.minecraft.client.Minecraft;
import ryzn.macro.client.MacroInput;
import ryzn.macro.client.core.RyznBootstrap;
import ryzn.macro.client.core.events.api.AfkAutoHarpActionEvent;
import ryzn.macro.client.core.events.api.AutoHarpActionEvent;
import ryzn.macro.client.core.events.api.BeaActionEvent;
import ryzn.macro.client.core.events.api.CarpentryUnlockActionEvent;
import ryzn.macro.client.core.events.api.CombatSessionEvent;
import ryzn.macro.client.core.events.api.ForagingQuestActionEvent;
import ryzn.macro.client.core.events.api.GoldShovelActionEvent;
import ryzn.macro.client.core.events.api.Level7ActionEvent;
import ryzn.macro.client.core.events.api.MiningMerchantActionEvent;
import ryzn.macro.client.core.events.api.RevLevel2ActionEvent;
import ryzn.macro.client.core.events.api.RevSlayerActionEvent;
import ryzn.macro.client.core.events.api.SandMinerActionEvent;
import ryzn.macro.client.core.events.api.TreeChopActionEvent;
import ryzn.macro.client.core.events.api.WheatBreakerActionEvent;
import ryzn.macro.client.debug.DebugLog;
import ryzn.macro.client.feature.macros.impl.bea.BeaMacro;
import ryzn.macro.client.feature.macros.impl.carpentry.CarpentryUnlockMacro;
import ryzn.macro.client.feature.macros.impl.foraging.AfkAutoHarpMacro;
import ryzn.macro.client.feature.macros.impl.foraging.AutoHarpMacro;
import ryzn.macro.client.feature.macros.impl.foragingquest.ForagingQuestUnlockerMacro;
import ryzn.macro.client.feature.macros.impl.goldshovel.GoldShovelMacro;
import ryzn.macro.client.feature.macros.impl.level7.AutoLvl7Macro;
import ryzn.macro.client.feature.macros.impl.level7.RevLevel2Macro;
import ryzn.macro.client.feature.macros.impl.miningmerchant.MiningMerchantMacro;
import ryzn.macro.client.feature.macros.impl.sand.SandMinerMacro;
import ryzn.macro.client.feature.macros.impl.slayer.RevSlayerMacro;
import ryzn.macro.client.feature.macros.impl.tree.TreeChopMacro;
import ryzn.macro.client.feature.macros.impl.wheat.WheatBreakerMacro;
import ryzn.macro.client.feature.modules.impl.combat.CombatModule;
import ryzn.macro.client.feature.pathfinder.WalkingNavigation;
import ryzn.macro.client.util.gui.ScreenOwner;
import ryzn.macro.client.util.text.RyznChat;
import ryzn.pathfinding.PathManager;

final class FailsafeStopper {
  private FailsafeStopper() {}

  /**
   * @return whether anything was actually running, so a no-op stop can stay silent.
   */
  static boolean stopAll(String reason) {
    final boolean wasActive = FailsafeActivity.foregroundActive();
    // Each macro announces its own "Stopped X"; tagging those with the reason beats printing a
    // second
    // summary line, and it names what actually stopped without a registry of macro names here.
    RyznChat.setStopReason(reason);
    try {
      stopEverything(reason);
    } finally {
      RyznChat.clearStopReason();
    }
    // Only report a stop that stopped something. This runs on every ESC and every menu open, so an
    // ungated line means a debug message each time the player pauses with nothing running.
    if (wasActive) {
      DebugLog.trace(DebugLog.Category.MACRO, "Failsafe: stopped everything - %s", reason);
    }
    return wasActive;
  }

  private static void stopEverything(String reason) {
    // Drop every screen claim: nothing is running any more, so nothing owns a menu.
    ScreenOwner.reset();
    if (RyznBootstrap.macroManager().getByClass(AutoLvl7Macro.class).isActive()) {
      RyznBootstrap.eventBus()
          .post(
              new Level7ActionEvent(
                  isWorldChangeStop(reason)
                      ? Level7ActionEvent.Action.WORLD_CHANGE_STOP
                      : Level7ActionEvent.Action.FAILSAFE_STOP));
    }
    if (RyznBootstrap.macroManager().getByClass(RevLevel2Macro.class).isActive()) {
      RyznBootstrap.eventBus().post(new RevLevel2ActionEvent(RevLevel2ActionEvent.Action.STOP));
    }
    if (RyznBootstrap.macroManager().getByClass(BeaMacro.class).isEnabled()) {
      RyznBootstrap.eventBus().post(BeaActionEvent.enabled(false));
    }
    if (RyznBootstrap.macroManager().getByClass(GoldShovelMacro.class).isEnabled()) {
      RyznBootstrap.eventBus().post(GoldShovelActionEvent.enabled(false));
    }
    if (RyznBootstrap.macroManager().getByClass(CarpentryUnlockMacro.class).isEnabled()) {
      RyznBootstrap.eventBus().post(new CarpentryUnlockActionEvent(false));
    }
    if (RyznBootstrap.macroManager().getByClass(MiningMerchantMacro.class).isEnabled()) {
      RyznBootstrap.eventBus().post(new MiningMerchantActionEvent(false));
    }
    if (RyznBootstrap.macroManager().getByClass(ForagingQuestUnlockerMacro.class).isEnabled()) {
      RyznBootstrap.eventBus()
          .post(new ForagingQuestActionEvent(ForagingQuestActionEvent.Action.SET_ENABLED, false));
    }
    if (RyznBootstrap.macroManager().getByClass(WheatBreakerMacro.class).isEnabled()) {
      RyznBootstrap.eventBus()
          .post(new WheatBreakerActionEvent(WheatBreakerActionEvent.Action.SET_ENABLED, false));
    }
    if (RyznBootstrap.macroManager().getByClass(AfkAutoHarpMacro.class).isEnabled()) {
      RyznBootstrap.eventBus().post(new AfkAutoHarpActionEvent(false));
    }
    if (RyznBootstrap.macroManager().getByClass(AutoHarpMacro.class).isEnabled()) {
      RyznBootstrap.eventBus().post(new AutoHarpActionEvent(false));
    }
    if (RyznBootstrap.macroManager().getByClass(SandMinerMacro.class).isEnabled()) {
      RyznBootstrap.eventBus()
          .post(new SandMinerActionEvent(SandMinerActionEvent.Action.SET_ENABLED, false));
    }
    if (RyznBootstrap.macroManager().getByClass(TreeChopMacro.class).isRunning()) {
      RyznBootstrap.eventBus().post(new TreeChopActionEvent(TreeChopActionEvent.Action.STOP));
    }
    if (RyznBootstrap.macroManager().getByClass(RevSlayerMacro.class).isEnabled()) {
      RyznBootstrap.eventBus().post(new RevSlayerActionEvent(false));
    }
    if (RyznBootstrap.moduleManager().getByClass(CombatModule.class).isManaged()) {
      RyznBootstrap.eventBus().post(CombatSessionEvent.stop());
    }

    WalkingNavigation.stopNavQuiet();
    PathManager.cancelSearch();

    Minecraft client = Minecraft.getInstance();
    if (client != null) {
      MacroInput.releaseAllKeys(client.options);
    }
  }

  static boolean isWorldChangeStop(String reason) {
    return reason != null && reason.startsWith(FailsafeType.WORLD_CHANGE.label() + ":");
  }
}
