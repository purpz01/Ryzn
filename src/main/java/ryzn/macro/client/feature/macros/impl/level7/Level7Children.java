package ryzn.macro.client.feature.macros.impl.level7;

import static ryzn.macro.client.feature.macros.impl.level7.Level7Travel.HUB_SETTLE_TICKS;

import net.minecraft.client.Minecraft;
import ryzn.macro.client.core.RyznBootstrap;
import ryzn.macro.client.core.events.api.BeaActionEvent;
import ryzn.macro.client.core.events.api.CarpentryUnlockActionEvent;
import ryzn.macro.client.core.events.api.ForagingQuestActionEvent;
import ryzn.macro.client.core.events.api.GoldShovelActionEvent;
import ryzn.macro.client.core.events.api.MiningMerchantActionEvent;
import ryzn.macro.client.core.events.api.RevLevel2ActionEvent;
import ryzn.macro.client.core.events.api.SandMinerActionEvent;
import ryzn.macro.client.core.events.api.WheatBreakerActionEvent;
import ryzn.macro.client.feature.commands.impl.pathfinder.PathfindCommand;
import ryzn.macro.client.feature.macros.impl.bea.BeaMacro;
import ryzn.macro.client.feature.macros.impl.carpentry.CarpentryUnlockMacro;
import ryzn.macro.client.feature.macros.impl.foragingquest.ForagingQuestUnlockerMacro;
import ryzn.macro.client.feature.macros.impl.goldshovel.GoldShovelMacro;
import ryzn.macro.client.feature.macros.impl.level7.AutoLvl7Macro.ActiveChild;
import ryzn.macro.client.feature.macros.impl.level7.AutoLvl7Macro.State;
import ryzn.macro.client.feature.macros.impl.miningmerchant.MiningMerchantMacro;
import ryzn.macro.client.feature.macros.impl.sand.SandMinerMacro;
import ryzn.macro.client.feature.macros.impl.wheat.WheatBreakerMacro;

/**
 * @description Runs Level 7 child sessions, skill gates, completion checks and restart limits.
 */
final class Level7Children {

  private final AutoLvl7Macro hunt;

  Level7Children(AutoLvl7Macro hunt) {
    this.hunt = hunt;
  }

  static final int FARMING_FIRST_TARGET = 6;

  static final int FARMING_SECOND_TARGET = 12;

  static final int MINING_TARGET = 12;

  /** A child that reads stopped this long is really down, not between two of its own phases. */
  static final int CHILD_DOWN_GRACE_TICKS = 20 * 3;

  static final int MAX_CHILD_RESTARTS = 5;

  int childDownTicks;

  int childRestarts;

  /**
   * The Gold Shovel step the picked resume stage asked for; spent the first time the shovel starts.
   */
  String pendingShovelStep;

  boolean pendingShovelAxeLeg;

  /** Where the running Gold Shovel would pick back up, sampled every tick it runs. */
  GoldShovelMacro.ResumePoint liveShovelResume;

  /** The Foraging checkpoint selected in the parent UI; spent when the unlocker is enabled. */
  int pendingForagingCheckpoint;

  /** The collection-grind leg selected in the parent UI; spent when the unlocker is enabled. */
  int pendingCollectionLeg = -1;

  /** The collection cleanup stage selected in the parent UI; spent when the unlocker is enabled. */
  boolean pendingCollectionFinalSale;

  void tickInitialFarmingGate() {
    final int farming = hunt.skills.level(SkillLevelDetector.FARMING);
    if (farming >= FARMING_SECOND_TARGET) {
      AutoLvl7Macro.say(
          Minecraft.getInstance(),
          "§bLevel 7 Test§7: §aFarming " + farming + " reached §7- starting Wool Weaver.");
      hunt.setState(State.AFTER_LEVEL12_SELL);
      return;
    }
    tickFarmGate(FARMING_FIRST_TARGET, State.FARMING_6_REACHED);
  }

  void tickFarmGate(int target, State next) {
    final int farming = hunt.skills.level(SkillLevelDetector.FARMING);
    if (farming >= target) {
      AutoLvl7Macro.say(
          Minecraft.getInstance(),
          "§bLevel 7 Test§7: §aFarming "
              + farming
              + " reached §7- moving on to "
              + next.label()
              + ".");
      hunt.setState(next);
      return;
    }
    if (keepWheatRunning()) {
      return;
    }
    hunt.waitReason =
        farming < 0
            ? "Waiting for TAB data (Farming), need " + target
            : "Farming " + farming + " / " + target;
  }

  void tickMiningGate() {
    final int mining = hunt.skills.level(SkillLevelDetector.MINING);
    if (mining >= MINING_TARGET) {
      hunt.setState(AutoLvl7Macro.nextRouteState(State.SAND_PHASE));
      return;
    }
    if (keepChildRunning(
        RyznBootstrap.macroManager().getByClass(SandMinerMacro.class).isEnabled(),
        "Sand Miner",
        this::enableSandMiner)) {
      return;
    }
    hunt.waitReason =
        mining < 0
            ? "Waiting for TAB data (Mining), need " + MINING_TARGET
            : "Mining " + mining + " / " + MINING_TARGET;
  }

  void tickAwaitCarpentry() {
    final CarpentryUnlockMacro carpentry =
        RyznBootstrap.macroManager().getByClass(CarpentryUnlockMacro.class);
    if (carpentry.isEnabled()) {
      hunt.waitReason = "Running Carpentry Unlock: " + carpentry.statusText();
      return;
    }
    if (!carpentry.wasLastRunCompleted()) {
      hunt.failRoute(
          "Carpentry Unlock stopped before completion: "
              + AutoLvl7Macro.stopReason(carpentry.lastStopReason()));
      return;
    }
    hunt.setState(State.CARPENTRY_DONE);
  }

  /**
   * Restarts a grind child that stopped on its own, since the skill gate above it only watches the
   * level and would otherwise wait forever.
   *
   * @return true when the child is down and the gate should hold this tick
   */
  boolean keepChildRunning(boolean running, String label, Runnable restart) {
    if (running) {
      childDownTicks = 0;
      return false;
    }
    if (++childDownTicks < CHILD_DOWN_GRACE_TICKS) {
      hunt.waitReason = label + " stopped; checking";
      return true;
    }
    childDownTicks = 0;
    if (++childRestarts > MAX_CHILD_RESTARTS) {
      hunt.failRoute(label + " kept stopping after " + MAX_CHILD_RESTARTS + " restarts");
      return true;
    }
    AutoLvl7Macro.say(
        Minecraft.getInstance(),
        "§bLevel 7 Test§7: §e"
            + label
            + " stopped on its own §7- restarting it ("
            + childRestarts
            + "/"
            + MAX_CHILD_RESTARTS
            + ").");
    restart.run();
    hunt.waitReason = "Restarting " + label;
    return true;
  }

  boolean keepWheatRunning() {
    return keepChildRunning(
        RyznBootstrap.macroManager().getByClass(WheatBreakerMacro.class).isEnabled(),
        "Wheat Breaker",
        this::enableWheatWithOptions);
  }

  void tickAwaitMiningMerchant() {
    if (RyznBootstrap.macroManager().getByClass(MiningMerchantMacro.class).isEnabled()) {
      hunt.waitReason =
          "Running Mining Merchant: "
              + RyznBootstrap.macroManager().getByClass(MiningMerchantMacro.class).statusText();
      return;
    }
    if (!RyznBootstrap.macroManager().getByClass(MiningMerchantMacro.class).wasLastRunCompleted()) {
      hunt.failRoute(
          "Mining Merchant stopped before completion: "
              + AutoLvl7Macro.stopReason(
                  RyznBootstrap.macroManager()
                      .getByClass(MiningMerchantMacro.class)
                      .lastStopReason()));
      return;
    }
    hunt.setState(State.MINING_MERCHANT_DONE);
  }

  void tickAwaitBea(State next) {
    if (RyznBootstrap.macroManager().getByClass(BeaMacro.class).isEnabled()) {
      hunt.waitReason =
          "Running Bea: " + RyznBootstrap.macroManager().getByClass(BeaMacro.class).statusText();
      return;
    }
    if (!RyznBootstrap.macroManager().getByClass(BeaMacro.class).wasLastRunCompleted()) {
      hunt.failRoute(
          "Bea stopped before completion: "
              + AutoLvl7Macro.stopReason(
                  RyznBootstrap.macroManager().getByClass(BeaMacro.class).lastStopReason()));
      return;
    }
    hunt.setState(next);
  }

  /**
   * @description Starts the Gold Shovel child at one resume step on the pending leg.
   * @param step the Gold Shovel step name
   * @return true when the child accepted the step
   */
  boolean startGoldShovelAt(String step) {
    final GoldShovelActionEvent request = GoldShovelActionEvent.startAt(step, pendingShovelAxeLeg);
    RyznBootstrap.eventBus().post(request);
    return request.started();
  }

  void tickAwaitGoldShovel() {
    if (RyznBootstrap.macroManager().getByClass(GoldShovelMacro.class).isEnabled()) {
      liveShovelResume =
          RyznBootstrap.macroManager().getByClass(GoldShovelMacro.class).resumePoint();
      hunt.waitReason =
          "Running Gold Shovel: "
              + RyznBootstrap.macroManager().getByClass(GoldShovelMacro.class).statusText();
      return;
    }
    if (!RyznBootstrap.macroManager().getByClass(GoldShovelMacro.class).wasLastRunCompleted()) {
      hunt.failRoute(
          "Gold Shovel stopped before completion: "
              + AutoLvl7Macro.stopReason(
                  RyznBootstrap.macroManager().getByClass(GoldShovelMacro.class).lastStopReason()));
      return;
    }
    hunt.setState(State.GOLD_SHOVEL_DONE);
  }

  void tickAwaitLevel6Sell() {
    tickAwaitLevelSell(State.AFTER_LEVEL6_SELL);
  }

  void tickAwaitLevel12Sell() {
    tickAwaitLevelSell(State.AFTER_LEVEL12_SELL);
  }

  void tickAwaitLevelSell(State next) {
    final WheatBreakerMacro wheat =
        RyznBootstrap.macroManager().getByClass(WheatBreakerMacro.class);
    if (!wheat.isLevel7PausedAfterSell() && wheat.isEnabled()) {
      hunt.waitReason = "Selling inventory at Farm Merchant";
      return;
    }
    // Every next stage (Mining Merchant, Bea, Wool Weaver) starts in the Hub. A wheat lobby hop or
    // a failed sell can leave us on the island, where they fail at once and end the route.
    if (!hunt.travel.isInHub()) {
      hunt.travel.tickReturnToHub(Minecraft.getInstance());
      return;
    }
    // Fresh off a warp, the block cache needs a moment before the next child can pathfind.
    if (hunt.travel.hubReturnTicks > 0 && ++hunt.travel.hubSettleTicks < HUB_SETTLE_TICKS) {
      hunt.waitReason = "In the Hub; letting the world cache settle";
      return;
    }
    hunt.travel.hubReturnTicks = 0;
    hunt.setState(next);
  }

  void tickAwaitForagingUnlock() {
    if (RyznBootstrap.macroManager().getByClass(ForagingQuestUnlockerMacro.class).isEnabled()) {
      hunt.waitReason =
          "Running Foraging Quest Unlocker: "
              + RyznBootstrap.macroManager()
                  .getByClass(ForagingQuestUnlockerMacro.class)
                  .statusText();
      return;
    }
    if (!RyznBootstrap.macroManager()
        .getByClass(ForagingQuestUnlockerMacro.class)
        .wasLastRunCompleted()) {
      hunt.failRoute(
          "Foraging Quest Unlocker stopped before completion: "
              + AutoLvl7Macro.stopReason(
                  RyznBootstrap.macroManager()
                      .getByClass(ForagingQuestUnlockerMacro.class)
                      .lastStopReason()));
      return;
    }
    hunt.setState(AutoLvl7Macro.nextRouteState(hunt.state));
  }

  void tickAwaitRevLevel2() {
    if (RyznBootstrap.macroManager().getByClass(RevLevel2Macro.class).isActive()) {
      hunt.waitReason =
          "Running Rev Slayer 2: "
              + RyznBootstrap.macroManager().getByClass(RevLevel2Macro.class).statusText();
      return;
    }
    if (!RyznBootstrap.macroManager().getByClass(RevLevel2Macro.class).wasLastRunCompleted()) {
      hunt.failRoute(
          "Rev Slayer 2 stopped before completion: "
              + AutoLvl7Macro.stopReason(
                  RyznBootstrap.macroManager().getByClass(RevLevel2Macro.class).lastStopReason()));
      return;
    }
    hunt.setState(AutoLvl7Macro.nextRouteState(State.REV_LEVEL_2));
  }

  void enableWheatWithOptions() {
    // Options first so the breaker's session begins with the route's configuration applied.
    RyznBootstrap.eventBus()
        .post(new WheatBreakerActionEvent(WheatBreakerActionEvent.Action.SET_AUTO_SELL, true));
    RyznBootstrap.eventBus()
        .post(new WheatBreakerActionEvent(WheatBreakerActionEvent.Action.SET_AUTO_BUY_HOE, true));
    RyznBootstrap.eventBus()
        .post(new WheatBreakerActionEvent(WheatBreakerActionEvent.Action.SET_AUTO_EQUIP_HOE, true));
    RyznBootstrap.eventBus()
        .post(
            new WheatBreakerActionEvent(
                WheatBreakerActionEvent.Action.SET_LOBBY_HOP_AFTER_CLEAR, true));
    RyznBootstrap.eventBus()
        .post(new WheatBreakerActionEvent(WheatBreakerActionEvent.Action.SET_LOOP_ZONES, true));
    RyznBootstrap.eventBus()
        .post(new WheatBreakerActionEvent(WheatBreakerActionEvent.Action.SET_ENABLED, true));
  }

  void enableSandMiner() {
    SandMinerMacro sand = RyznBootstrap.macroManager().getByClass(SandMinerMacro.class);
    Minecraft mc = Minecraft.getInstance();
    if (!sand.isEnabled() && !sand.hasPreparedRoute() && mc.player != null) {
      sand.prepareRandomRoute(mc.player);
    }
    RyznBootstrap.eventBus()
        .post(new SandMinerActionEvent(SandMinerActionEvent.Action.SET_ENABLED, true));
    RyznBootstrap.eventBus()
        .post(new SandMinerActionEvent(SandMinerActionEvent.Action.SET_CRAFT_ENCHANTED, true));
  }

  void resumeWheatWithOptions() {
    RyznBootstrap.eventBus()
        .post(new WheatBreakerActionEvent(WheatBreakerActionEvent.Action.SET_AUTO_SELL, true));
    RyznBootstrap.eventBus()
        .post(new WheatBreakerActionEvent(WheatBreakerActionEvent.Action.SET_AUTO_BUY_HOE, true));
    RyznBootstrap.eventBus()
        .post(new WheatBreakerActionEvent(WheatBreakerActionEvent.Action.SET_AUTO_EQUIP_HOE, true));
    RyznBootstrap.eventBus()
        .post(
            new WheatBreakerActionEvent(
                WheatBreakerActionEvent.Action.SET_LOBBY_HOP_AFTER_CLEAR, true));
    RyznBootstrap.eventBus()
        .post(new WheatBreakerActionEvent(WheatBreakerActionEvent.Action.SET_LOOP_ZONES, true));
    RyznBootstrap.eventBus()
        .post(
            new WheatBreakerActionEvent(WheatBreakerActionEvent.Action.RESUME_LEVEL7_PAUSED_ROUTE));
  }

  void startForagingUnlocker(boolean collectionsOnly) {
    RyznBootstrap.eventBus()
        .post(
            new ForagingQuestActionEvent(
                ForagingQuestActionEvent.Action.SET_CLEAR_INVENTORY_AT_MERCHANT, !collectionsOnly));
    if (collectionsOnly) {
      RyznBootstrap.eventBus()
          .post(
              new ForagingQuestActionEvent(
                  ForagingQuestActionEvent.Action.PICK_RESUME_CHECKPOINT,
                  ForagingQuestUnlockerMacro.resumeCheckpointLabels().length));
      if (pendingCollectionFinalSale) {
        RyznBootstrap.eventBus()
            .post(new ForagingQuestActionEvent(ForagingQuestActionEvent.Action.SELECT_FINAL_SALE));
      } else if (pendingCollectionLeg >= 0) {
        RyznBootstrap.eventBus()
            .post(
                new ForagingQuestActionEvent(
                    ForagingQuestActionEvent.Action.SELECT_GRIND_LEG, pendingCollectionLeg));
      }
    } else if (pendingForagingCheckpoint > 0) {
      RyznBootstrap.eventBus()
          .post(
              new ForagingQuestActionEvent(
                  ForagingQuestActionEvent.Action.PICK_RESUME_CHECKPOINT,
                  pendingForagingCheckpoint));
    }
    pendingForagingCheckpoint = 0;
    pendingCollectionLeg = -1;
    pendingCollectionFinalSale = false;
    if (RyznBootstrap.macroManager().getByClass(ForagingQuestUnlockerMacro.class).isEnabled()) {
      RyznBootstrap.eventBus()
          .post(new ForagingQuestActionEvent(ForagingQuestActionEvent.Action.SET_ENABLED, false));
    }
    RyznBootstrap.eventBus()
        .post(new ForagingQuestActionEvent(ForagingQuestActionEvent.Action.SET_ENABLED, true));
    hunt.activeChild = ActiveChild.FORAGING_QUEST;
    hunt.waitReason =
        collectionsOnly
            ? "Grinding wood collections III, then Oak to Foraging 12"
            : "Running Foraging Quest Unlocker";
  }

  void releaseAllControls() {
    if (RyznBootstrap.macroManager().getByClass(WheatBreakerMacro.class).isEnabled()) {
      RyznBootstrap.eventBus()
          .post(new WheatBreakerActionEvent(WheatBreakerActionEvent.Action.SET_ENABLED, false));
    }
    RyznBootstrap.eventBus()
        .post(new WheatBreakerActionEvent(WheatBreakerActionEvent.Action.CLEAR_LEVEL7_PAUSE));
    if (RyznBootstrap.macroManager().getByClass(CarpentryUnlockMacro.class).isEnabled()) {
      RyznBootstrap.eventBus().post(new CarpentryUnlockActionEvent(false));
    }
    if (RyznBootstrap.macroManager().getByClass(MiningMerchantMacro.class).isEnabled()) {
      RyznBootstrap.eventBus().post(new MiningMerchantActionEvent(false));
    }
    if (RyznBootstrap.macroManager().getByClass(BeaMacro.class).isEnabled()) {
      RyznBootstrap.eventBus().post(BeaActionEvent.enabled(false));
    }
    if (RyznBootstrap.macroManager().getByClass(GoldShovelMacro.class).isEnabled()) {
      RyznBootstrap.eventBus().post(GoldShovelActionEvent.enabled(false));
    }
    if (RyznBootstrap.macroManager().getByClass(SandMinerMacro.class).isEnabled()) {
      RyznBootstrap.eventBus()
          .post(new SandMinerActionEvent(SandMinerActionEvent.Action.SET_ENABLED, false));
    }
    if (RyznBootstrap.macroManager().getByClass(ForagingQuestUnlockerMacro.class).isEnabled()) {
      RyznBootstrap.eventBus()
          .post(new ForagingQuestActionEvent(ForagingQuestActionEvent.Action.SET_ENABLED, false));
    }
    if (RyznBootstrap.macroManager().getByClass(RevLevel2Macro.class).isActive()) {
      RyznBootstrap.eventBus().post(new RevLevel2ActionEvent(RevLevel2ActionEvent.Action.STOP));
    }
    RyznBootstrap.eventBus()
        .post(
            new ForagingQuestActionEvent(
                ForagingQuestActionEvent.Action.SET_CLEAR_INVENTORY_AT_MERCHANT, false));
    PathfindCommand.stopNavQuiet();
  }

  String activeChildStatus() {
    return switch (hunt.activeChild) {
      case CARPENTRY ->
          RyznBootstrap.macroManager().getByClass(CarpentryUnlockMacro.class).statusText();
      case MINING_MERCHANT ->
          RyznBootstrap.macroManager().getByClass(MiningMerchantMacro.class).statusText();
      case BEA -> RyznBootstrap.macroManager().getByClass(BeaMacro.class).statusText();
      case GOLD_SHOVEL ->
          RyznBootstrap.macroManager().getByClass(GoldShovelMacro.class).statusText();
      case SAND_MINER -> RyznBootstrap.macroManager().getByClass(SandMinerMacro.class).statusText();
      case FORAGING_QUEST ->
          RyznBootstrap.macroManager().getByClass(ForagingQuestUnlockerMacro.class).statusText();
      case REV_LEVEL_2 ->
          RyznBootstrap.macroManager().getByClass(RevLevel2Macro.class).statusText();
      case WHEAT_BREAKER ->
          RyznBootstrap.macroManager().getByClass(WheatBreakerMacro.class).statusText();
      case PATHFINDER -> PathfindCommand.isNavigationActive() ? "Pathfinding" : "Pathfinder idle";
      default -> "-";
    };
  }
}
