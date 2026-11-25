package ryzn.macro.client.feature.macros.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import ryzn.macro.client.config.RyznSettings;
import ryzn.macro.client.core.RyznBootstrap;
import ryzn.macro.client.feature.macros.api.MacroManager;
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

/** Active-macro query owner while legacy implementations migrate into registered elements. */
public final class MacroManagerImpl extends MacroManager {
  /** Macros without their own reconnect checkpoint restart from their normal entry point. */
  public List<Runnable> captureReconnectStarts() {
    List<Runnable> starts = new ArrayList<>();
    if (getByClass(AutoLvl7Macro.class).isActive()) {
      return starts; // Its recovery owns the route and every child macro it launched.
    }
    var bea = getByClass(BeaMacro.class);
    captureEnabled(starts, bea.isEnabled(), bea::setEnabled);
    var goldShovel = getByClass(GoldShovelMacro.class);
    captureEnabled(starts, goldShovel.isEnabled(), goldShovel::setEnabled);
    var carpentryUnlock = getByClass(CarpentryUnlockMacro.class);
    captureEnabled(starts, carpentryUnlock.isEnabled(), carpentryUnlock::setEnabled);
    var sandMiner = getByClass(SandMinerMacro.class);
    captureEnabled(starts, sandMiner.isEnabled(), sandMiner::setEnabled);
    var afkAutoHarp = getByClass(AfkAutoHarpMacro.class);
    captureEnabled(starts, afkAutoHarp.isEnabled(), afkAutoHarp::setEnabled);
    var autoHarp = getByClass(AutoHarpMacro.class);
    captureEnabled(starts, autoHarp.isEnabled(), autoHarp::setEnabled);
    var revSlayer = getByClass(RevSlayerMacro.class);
    captureEnabled(starts, revSlayer.isEnabled(), revSlayer::setEnabled);
    if (getByClass(TreeChopMacro.class).isRunning())
      starts.add(() -> getByClass(TreeChopMacro.class).start());
    var wheatBreaker = getByClass(WheatBreakerMacro.class);
    captureEnabled(starts, wheatBreaker.isEnabled(), wheatBreaker::setEnabled);
    if (getByClass(RevLevel2Macro.class).isActive())
      starts.add(() -> getByClass(RevLevel2Macro.class).start());
    return starts;
  }

  private static void captureEnabled(
      List<Runnable> starts, boolean enabled, Consumer<Boolean> setEnabled) {
    if (enabled) {
      starts.add(() -> setEnabled.accept(true));
    }
  }

  /**
   * @description Keeps registration explicit until each macro becomes a process-lifetime element.
   */
  @Override
  protected void registerElements() {
    register(RyznSettings.get().autoHarp());
    register(RyznSettings.get().afkAutoHarp());
    register(RyznSettings.get().foragingQuest());
    register(RyznSettings.get().level7());
    register(new RevLevel2Macro());
    register(RyznSettings.get().revSlayer());
    register(RyznSettings.get().wheat());
    register(RyznSettings.get().treeChop());
    register(RyznSettings.get().sand());
    register(RyznSettings.get().goldShovel());
    register(new BeaMacro());
    register(new MiningMerchantMacro());
    register(new CarpentryUnlockMacro());
  }

  /**
   * @description Preserves the existing active-automation predicate for input and reconnect gates.
   * @return true when any current automation reports itself active
   */
  @Override
  public boolean anyMacroRunning() {
    return getByClass(BeaMacro.class).isEnabled()
        || getByClass(GoldShovelMacro.class).isEnabled()
        || getByClass(CarpentryUnlockMacro.class).isEnabled()
        || getByClass(ForagingQuestUnlockerMacro.class).isEnabled()
        || getByClass(SandMinerMacro.class).isEnabled()
        || getByClass(AfkAutoHarpMacro.class).isEnabled()
        || getByClass(AutoHarpMacro.class).isEnabled()
        || getByClass(RevSlayerMacro.class).isEnabled()
        || getByClass(TreeChopMacro.class).isRunning()
        || getByClass(WheatBreakerMacro.class).isEnabled()
        || getByClass(AutoLvl7Macro.class).isActive()
        || getByClass(RevLevel2Macro.class).isActive()
        || getByClass(MiningMerchantMacro.class).isEnabled()
        || RyznBootstrap.moduleManager().getByClass(CombatModule.class).isEnabled();
  }
}
