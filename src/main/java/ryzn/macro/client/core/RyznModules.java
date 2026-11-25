package ryzn.macro.client.core;

import static ryzn.macro.client.util.inventory.InventoryHotbarTransfer.registerMacroTick;

import com.mojang.blaze3d.platform.InputConstants;
import ryzn.macro.client.CursorGrabGuard;
import ryzn.macro.client.MacroInput;
import ryzn.macro.client.MacroMouseUnlock;
import ryzn.macro.client.config.ConfigManager;
import ryzn.macro.client.core.events.api.CollectSubmitsEvent;
import ryzn.macro.client.core.events.api.EndClientTickEvent;
import ryzn.macro.client.core.events.api.EventBus;
import ryzn.macro.client.core.events.api.GameMessageEvent;
import ryzn.macro.client.core.events.api.StartMainRenderEvent;
import ryzn.macro.client.feature.commands.api.RyznCommands;
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
import ryzn.macro.client.feature.pathfinder.PathRenderer;
import ryzn.macro.client.feature.pathfinder.WalkingNavigation;
import ryzn.macro.client.feature.pathfinder.path.PathExecutor;
import ryzn.macro.client.feature.reconnect.HypixelLocation;
import ryzn.macro.client.feature.reconnect.MacroReconnect;
import ryzn.macro.client.feature.rotation.impl.abcurves.AbcRotation;
import ryzn.macro.client.util.inventory.InventoryHotbarTransfer;
import ryzn.macro.client.world.WorldChangeDetector;
import ryzn.swift.cache.CachedWorld;
import ryzn.swift.nativepath.NativePathfinderJNI;

final class RyznModules {
  private static boolean escapeWasDown;

  private RyznModules() {}

  static void applySettings() {
    RyznBootstrap.macroManager().getByClass(GoldShovelMacro.class).applyConfig();
    RyznBootstrap.macroManager().getByClass(ForagingQuestUnlockerMacro.class).applyConfig();
    RyznBootstrap.macroManager().getByClass(SandMinerMacro.class).applyConfig();
    RyznBootstrap.macroManager().getByClass(RevSlayerMacro.class).applyConfig();
    RyznBootstrap.macroManager().getByClass(WheatBreakerMacro.class).applyConfig();
    RyznBootstrap.macroManager().getByClass(AfkAutoHarpMacro.class).applyConfig();
  }

  static void prepare() {
    RyznBootstrap.moduleManager().init();
    RyznBootstrap.macroManager().init();
    RyznBootstrap.failsafeManager().init();
    RyznBootstrap.commandManager().init();
  }

  static void register() {
    EventBus bus = RyznBootstrap.eventBus();
    RyznBootstrap.onModulesLoaded();
    applySettings();
    for (var macro : RyznBootstrap.macroManager().elements()) {
      bus.register(macro);
    }
    bus.register(RyznBootstrap.moduleManager().getByClass(CombatModule.class));
    bus.subscribe(EndClientTickEvent.class, event -> ConfigManager.tick());
    bus.subscribe(
        EndClientTickEvent.class, event -> RyznBootstrap.keepBackgroundTicks(event.client()));
    NativePathfinderJNI.initialize();
    AbcRotation.prewarm();
    CachedWorld.registerEvents();
    WorldChangeDetector.register();
    HypixelLocation.register();
    MacroReconnect.register();
    bus.register(RyznBootstrap.commandManager());
    RyznCommands.initialize(bus);
    bus.subscribe(EndClientTickEvent.class, event -> InventoryHotbarTransfer.tick(event.client()));
    bus.subscribe(
        EndClientTickEvent.class,
        event -> {
          boolean down =
              InputConstants.isKeyDown(event.client().getWindow(), InputConstants.KEY_ESCAPE);
          if (down && !escapeWasDown) {
            MacroReconnect.disarm();
            RyznBootstrap.failsafeManager().stopAllMacros("game menu opened");
          }
          escapeWasDown = down;
        });
    bus.subscribe(
        GameMessageEvent.class,
        RyznBootstrap.macroManager().getByClass(BeaMacro.class)::onGameMessageEvent);
    bus.subscribe(
        GameMessageEvent.class,
        RyznBootstrap.macroManager().getByClass(GoldShovelMacro.class)::onGameMessageEvent);
    bus.subscribe(
        GameMessageEvent.class,
        RyznBootstrap.macroManager().getByClass(CarpentryUnlockMacro.class)::onGameMessageEvent);
    bus.subscribe(
        GameMessageEvent.class,
        RyznBootstrap.macroManager().getByClass(ForagingQuestUnlockerMacro.class)
            ::onGameMessageEvent);
    RyznBootstrap.macroManager().getByClass(ForagingQuestUnlockerMacro.class).registerListeners();
    RyznBootstrap.macroManager().getByClass(AutoLvl7Macro.class).registerRecoveryListeners();
    bus.subscribe(
        GameMessageEvent.class,
        RyznBootstrap.macroManager().getByClass(WheatBreakerMacro.class)::onGameMessageEvent);
    bus.subscribe(
        GameMessageEvent.class,
        RyznBootstrap.macroManager().getByClass(AfkAutoHarpMacro.class)::onGameMessageEvent);
    bus.subscribe(
        GameMessageEvent.class,
        RyznBootstrap.macroManager().getByClass(SandMinerMacro.class)::onGameMessageEvent);
    bus.subscribe(
        GameMessageEvent.class,
        RyznBootstrap.macroManager().getByClass(RevSlayerMacro.class)::onGameMessageEvent);
    bus.subscribe(
        GameMessageEvent.class,
        RyznBootstrap.macroManager().getByClass(TreeChopMacro.class)::onGameMessageEvent);
    bus.subscribe(
        GameMessageEvent.class,
        RyznBootstrap.macroManager().getByClass(AutoLvl7Macro.class)::onGameMessageEvent);
    bus.subscribe(
        GameMessageEvent.class,
        RyznBootstrap.macroManager().getByClass(RevLevel2Macro.class)::onGameMessageEvent);
    registerMacroTick(WalkingNavigation::tick);
    registerMacroTick(RyznBootstrap.macroManager().getByClass(BeaMacro.class)::onClientTick);
    registerMacroTick(RyznBootstrap.macroManager().getByClass(GoldShovelMacro.class)::onClientTick);
    registerMacroTick(
        RyznBootstrap.macroManager().getByClass(MiningMerchantMacro.class)::onClientTick);
    registerMacroTick(
        RyznBootstrap.macroManager().getByClass(CarpentryUnlockMacro.class)::onClientTick);
    registerMacroTick(
        RyznBootstrap.macroManager().getByClass(ForagingQuestUnlockerMacro.class)::onClientTick);
    registerMacroTick(
        client ->
            RyznBootstrap.macroManager()
                .getByClass(ForagingQuestUnlockerMacro.class)
                .tickRecovery());
    registerMacroTick(
        client -> RyznBootstrap.macroManager().getByClass(AutoLvl7Macro.class).tickRecovery());
    registerMacroTick(RyznBootstrap.macroManager().getByClass(SandMinerMacro.class)::onClientTick);
    registerMacroTick(
        RyznBootstrap.macroManager().getByClass(WheatBreakerMacro.class)::onClientTick);
    registerMacroTick(RyznBootstrap.macroManager().getByClass(AutoHarpMacro.class)::onClientTick);
    registerMacroTick(
        RyznBootstrap.macroManager().getByClass(AfkAutoHarpMacro.class)::onClientTick);
    registerMacroTick(RyznBootstrap.macroManager().getByClass(RevSlayerMacro.class)::onClientTick);
    registerMacroTick(RyznBootstrap.moduleManager().getByClass(CombatModule.class)::onClientTick);
    registerMacroTick(PathExecutor.instance::onClientTick);
    registerMacroTick(RyznBootstrap.macroManager().getByClass(TreeChopMacro.class)::onClientTick);
    registerMacroTick(
        client -> RyznBootstrap.macroManager().getByClass(TreeChopMacro.class).tickLook());
    registerMacroTick(RyznBootstrap.macroManager().getByClass(AutoLvl7Macro.class)::onClientTick);
    registerMacroTick(RyznBootstrap.macroManager().getByClass(RevLevel2Macro.class)::onClientTick);
    bus.subscribe(EndClientTickEvent.class, event -> MacroMouseUnlock.onClientTick(event.client()));
    bus.subscribe(EndClientTickEvent.class, event -> CursorGrabGuard.onClientTick(event.client()));
    bus.subscribe(
        EndClientTickEvent.class,
        event -> {
          RyznBootstrap.macroManager().getByClass(BeaMacro.class).tickLook();
          RyznBootstrap.macroManager().getByClass(GoldShovelMacro.class).tickLook();
          RyznBootstrap.macroManager().getByClass(MiningMerchantMacro.class).tickLook();
          RyznBootstrap.macroManager().getByClass(CarpentryUnlockMacro.class).tickLook();
          RyznBootstrap.macroManager().getByClass(ForagingQuestUnlockerMacro.class).tickLook();
          RyznBootstrap.macroManager().getByClass(AfkAutoHarpMacro.class).tickLook();
          RyznBootstrap.macroManager().getByClass(WheatBreakerMacro.class).tickLook();
          RyznBootstrap.macroManager().getByClass(SandMinerMacro.class).tickLook();
          RyznBootstrap.moduleManager().getByClass(CombatModule.class).tickLook();
          RyznBootstrap.macroManager().getByClass(RevSlayerMacro.class).tickLook();
          if (event.client().gui.screen() != null) {
            MacroInput.releaseMovementKeys(event.client().options);
          }
        });
    bus.subscribe(
        StartMainRenderEvent.class,
        event -> {
          RyznBootstrap.moduleManager().getByClass(CombatModule.class).onRenderFrame();
          RyznBootstrap.macroManager().getByClass(RevSlayerMacro.class).onRenderFrame();
          RyznBootstrap.macroManager().getByClass(BeaMacro.class).onRenderFrame();
          RyznBootstrap.macroManager().getByClass(GoldShovelMacro.class).onRenderFrame();
          RyznBootstrap.macroManager().getByClass(MiningMerchantMacro.class).onRenderFrame();
          RyznBootstrap.macroManager().getByClass(CarpentryUnlockMacro.class).onRenderFrame();
          RyznBootstrap.macroManager().getByClass(ForagingQuestUnlockerMacro.class).onRenderFrame();
          RyznBootstrap.macroManager().getByClass(SandMinerMacro.class).onRenderFrame();
          RyznBootstrap.macroManager().getByClass(WheatBreakerMacro.class).onRenderFrame();
          RyznBootstrap.macroManager().getByClass(AutoHarpMacro.class).onRenderFrame();
          RyznBootstrap.macroManager().getByClass(AfkAutoHarpMacro.class).onRenderFrame();
          RyznBootstrap.macroManager().getByClass(TreeChopMacro.class).onRenderFrame();
        });
    bus.subscribe(CollectSubmitsEvent.class, event -> PathRenderer.render(event.context()));
    ryzn.macro.client.render.RenderUtil.register();
    RyznBootstrap.failsafeManager().registerCallbacks();
  }
}
