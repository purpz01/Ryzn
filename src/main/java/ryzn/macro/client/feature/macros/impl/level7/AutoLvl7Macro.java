package ryzn.macro.client.feature.macros.impl.level7;

import static ryzn.macro.client.feature.macros.impl.level7.Level7Checkpoints.checkpoint;
import static ryzn.macro.client.feature.macros.impl.level7.Level7Checkpoints.resumeStage;
import static ryzn.macro.client.feature.macros.impl.level7.Level7Children.FARMING_FIRST_TARGET;
import static ryzn.macro.client.feature.macros.impl.level7.Level7Children.FARMING_SECOND_TARGET;
import static ryzn.macro.client.feature.macros.impl.level7.Level7Children.MINING_TARGET;
import static ryzn.macro.client.feature.macros.impl.level7.Level7Travel.warpRoute;
import static ryzn.macro.client.util.text.MacroText.normalizeSearchText;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import ryzn.macro.client.config.RyznSettings;
import ryzn.macro.client.config.settings.ConfigSection;
import ryzn.macro.client.config.settings.Setting;
import ryzn.macro.client.core.RyznBootstrap;
import ryzn.macro.client.core.events.api.BeaActionEvent;
import ryzn.macro.client.core.events.api.CarpentryUnlockActionEvent;
import ryzn.macro.client.core.events.api.ForagingQuestActionEvent;
import ryzn.macro.client.core.events.api.GameMessageEvent;
import ryzn.macro.client.core.events.api.GoldShovelActionEvent;
import ryzn.macro.client.core.events.api.Level7ActionEvent;
import ryzn.macro.client.core.events.api.MiningMerchantActionEvent;
import ryzn.macro.client.core.events.api.RevLevel2ActionEvent;
import ryzn.macro.client.core.events.api.SandMinerActionEvent;
import ryzn.macro.client.core.events.api.Subscribe;
import ryzn.macro.client.core.events.api.WheatBreakerActionEvent;
import ryzn.macro.client.feature.api.MenuToggle;
import ryzn.macro.client.feature.hud.card.HudFormat;
import ryzn.macro.client.feature.macros.api.Macro;
import ryzn.macro.client.feature.macros.impl.bea.BeaMacro;
import ryzn.macro.client.feature.macros.impl.carpentry.CarpentryUnlockMacro;
import ryzn.macro.client.feature.macros.impl.goldshovel.GoldShovelMacro;
import ryzn.macro.client.feature.macros.impl.level7.Level7Checkpoints.Checkpoint;
import ryzn.macro.client.feature.macros.impl.level7.Level7Checkpoints.Stage;
import ryzn.macro.client.feature.macros.impl.miningmerchant.MiningMerchantMacro;
import ryzn.macro.client.feature.pathfinder.WalkingNavigation;
import ryzn.macro.client.util.text.RyznChat;

/**
 * Level 7 Test Macro: a deterministic, tick-driven orchestrator that walks a fixed early-game
 * SkyBlock route by detecting skill levels from the TAB list ({@link SkillLevelDetector}) and
 * starting / stopping the existing Ryzn modules. It never sends, blocks, or alters packets; all
 * motion and GUI work is delegated to the child modules and the native pathfinder, exactly as a
 * user driving them by hand would.
 *
 * <p>The route is a single linear state machine (see {@link State}). "Run a module to completion"
 * relies on the shared module contract: every child self-disables ({@code isEnabled() == false})
 * when it finishes or fails, so the orchestrator simply enables a child and waits for it to report
 * inactive. Skill gates read the live detected level each tick and advance the instant the
 * requirement is already met, so the route survives seeing a target level early. Unknown levels (no
 * TAB data yet) never advance; the wait reason surfaces that the macro is waiting for TAB data.
 *
 * <p>The only hardcoded delay is the requested 3 second pause between the second and third pathfind
 * routes.
 */
public final class AutoLvl7Macro extends Macro implements ConfigSection, MenuToggle {

  private final Level7Children children = new Level7Children(this);

  final Level7Travel travel = new Level7Travel(this);

  @RequiredArgsConstructor
  public enum State {
    IDLE("Idle"),
    START("Farm wheat to Farming 6"),
    FARMING_6_REACHED("Sell wheat at Farming 6"),
    AFTER_LEVEL6_SELL("Buy gold at the Mining Merchant"),
    MINING_MERCHANT_DONE("Buy the Bee pet"),
    BEA_DONE("Farm wheat to Farming 12"),
    FARMING_12_REACHED("Sell wheat at Farming 12"),
    AFTER_LEVEL12_SELL("Unlock Carpentry"),
    CARPENTRY_DONE("Make Gold Shovel"),
    GOLD_SHOVEL_DONE("Walk to the warp"),
    SERVER_TRANSFER_DETECTED("Continue to the Desert"),
    SAND_PHASE("Mine sand to Mining 12"),
    HUB_WARP("Warp to the Hub"),
    FORAGING_UNLOCK("Unlock the Foraging quests"),
    FORAGING_COLLECTIONS("All wood collections III, then Oak to Foraging 12"),
    REV_HUB_WARP("Warp to the Hub for Zombie Slayer"),
    REV_LEVEL_2("Reach Zombie Slayer LVL 2"),
    DONE("Complete");

    private final String label;

    public String label() {
      return label;
    }
  }

  @RequiredArgsConstructor
  enum ActiveChild {
    NONE("none"),
    WHEAT_BREAKER("Wheat Breaker"),
    MINING_MERCHANT("Mining Merchant materials"),
    BEA("Bea"),
    CARPENTRY("Carpentry Unlock"),
    GOLD_SHOVEL("Gold Shovel"),
    SAND_MINER("Sand Miner"),
    FORAGING_QUEST("Foraging Quest Unlocker"),
    REV_LEVEL_2("Rev Slayer 2"),
    PATHFINDER("Pathfinder");

    private final String label;
  }

  /**
   * One resume point. {@code shovelStep} names an {@code RyznGoldShovelMacro} state to drop the
   * Gold Shovel into (null = run it from its first step), so the shovel's own legs are stages like
   * any other.
   */
  private static final int POLL_INTERVAL_TICKS = 5;

  // The single permitted hardcoded delay: 3 seconds (20 TPS) between the second and third pathfind
  // routes.

  final SkillLevelDetector skills = new SkillLevelDetector();

  /** Saved 1-based route checkpoint; stored as read and clamped at use. */
  private final Setting<Integer> savedCheckpoint =
      addSetting(
          Setting.integer(
              "resume_checkpoint", "Resume checkpoint", 1, Integer.MIN_VALUE, Integer.MAX_VALUE));

  /** Saved 1-based stage inside the checkpoint; stored as read and clamped at use. */
  private final Setting<Integer> savedStage =
      addSetting(
          Setting.integer("resume_stage", "Resume stage", 1, Integer.MIN_VALUE, Integer.MAX_VALUE));

  private final Level7Recovery recovery = new Level7Recovery(this);
  @Getter private boolean active;
  private long runStartedNanos;
  State state = State.IDLE;
  ActiveChild activeChild = ActiveChild.NONE;
  String waitReason = "";
  private int tickCounter;

  // SERVER_TRANSFER_DETECTED sub-sequence.

  // Set by the chat listener while in GOLD_SHOVEL_DONE when "Sending to server" is received.

  /**
   * The state of the last live tick. Deliberately NOT cleared by {@link #stop()}: the world-change
   * failsafe stops the route before a rejoin is even visible, so this is all {@link Level7Recovery}
   * has left to decide what was interrupted.
   */
  private State lastLiveState = State.IDLE;

  /**
   * True while {@link Level7Recovery} is hopping the route off a server that announced its own
   * restart. The route stays {@code active} (so the failsafe and the reconnect still count it as a
   * live run) but the state machine is frozen, because {@link #state} is the anchor the hop resumes
   * from.
   */
  private boolean restartHold;

  // ===== Registration / commands

  /**
   * @description Creates the process-lifetime route with its saved checkpoint and stage and its
   *     restart and reconnect recovery collaborator.
   */
  public AutoLvl7Macro() {
    super(
        "level7",
        "Level 7 Route",
        "Runs the early-game route from the checkpoint and stage you choose.");
  }

  /**
   * @description Loads the saved checkpoint and stage under their original keys.
   * @param object the section JSON; a missing or mistyped value falls back to 1
   */
  @Override
  public void read(JsonObject object) {
    ConfigSection.readSettings(object, savedCheckpoint, savedStage);
  }

  /**
   * @description Writes the saved checkpoint and stage in their original key order.
   * @return a new section object for the settings file
   */
  @Override
  public JsonObject write() {
    return ConfigSection.writeSettings(savedCheckpoint, savedStage);
  }

  /**
   * @description Registers the recovery collaborator's reconnect listeners and chat slot at the
   *     position the recovery always held.
   */
  public void registerRecoveryListeners() {
    recovery.registerListeners();
  }

  /**
   * @description Advances the restart recovery at its original tick position.
   */
  public void tickRecovery() {
    recovery.onClientTick();
  }

  /**
   * @description Records skill level-ups while the route runs and notices the Barn's server
   *     transfer message on the Gold Shovel leg.
   * @param gamemessageevent the received message; overlays are ignored
   */
  public void onGameMessageEvent(GameMessageEvent gamemessageevent) {
    final Component message = gamemessageevent.message();
    if (gamemessageevent.overlay() || !active || message == null) {
      return;
    }
    skills.recordLevelUpMessage(message.getString());
    if (state != State.GOLD_SHOVEL_DONE) {
      return;
    }
    if (normalizeSearchText(message.getString()).contains("sending to server")) {
      travel.sendingToServerDetected = true;
      // The Barn warp is a portal walk, not a /warp, so the failsafe has to be told it is
      // expected.
      RyznBootstrap.failsafeManager().expectWorldChange();
    }
  }

  /**
   * @description Applies a start or stop request from the menu, command, or failsafe.
   * @param level7actionevent the requested action
   */
  @Subscribe
  public void onLevel7ActionEvent(Level7ActionEvent level7actionevent) {
    switch (level7actionevent.action()) {
      case START -> start();
      case START_FRESH -> start(true);
      case STOP -> {
        stop();
        recovery.cancel();
      }
      case FAILSAFE_STOP -> stop();
      case WORLD_CHANGE_STOP -> {
        stop();
        recovery.onWorldChangeStop();
      }
      default -> throw new IllegalArgumentException("Unknown Level 7 action");
    }
  }

  public void start() {
    start(false);
  }

  private void start(boolean fresh) {
    final Minecraft mc = Minecraft.getInstance();
    if (active) {
      say(
          mc,
          "§eAuto Level 7§7: §ealready running §7("
              + state.name()
              + "). Run §f/ryzn level7 stop§7 to stop it.");
      return;
    }
    if (fresh || runStartedNanos == 0L) {
      runStartedNanos = System.nanoTime();
    }
    skills.reset();
    skills.poll(mc);
    RyznBootstrap.eventBus()
        .post(new WheatBreakerActionEvent(WheatBreakerActionEvent.Action.CLEAR_LEVEL7_PAUSE));
    active = true;
    restartHold = false;
    tickCounter = 0;
    travel.sendingToServerDetected = false;
    final int checkpointIndex = fresh ? 1 : selectedCheckpoint();
    final int stageIndex = fresh ? 1 : selectedStage();
    if (checkpointIndex == 1 && stageIndex == 1) {
      // Starting the route over means a new profile, where Sand collection really is back to 0.
      RyznBootstrap.eventBus()
          .post(new SandMinerActionEvent(SandMinerActionEvent.Action.RESET_COLLECTION));
    }
    final Stage resume = resumeStage(checkpointIndex, stageIndex);
    children.liveShovelResume = null;
    children.pendingShovelStep = resume.shovelStep();
    children.pendingShovelAxeLeg = resume.shovelAxeLeg();
    // A route reaching the quest on its own starts it at "Buy oak logs", never at stale progress.
    children.pendingForagingCheckpoint = Math.max(1, resume.foragingCheckpoint());
    children.pendingCollectionLeg = resume.collectionLeg();
    children.pendingCollectionFinalSale = resume.collectionFinalSale();
    // Resuming AT the Desert leg means the server transfer already happened. transferLevelKey still
    // holds the world captured earlier in this session, so route A would wait for a world change
    // that
    // never comes and the stage would do nothing at all. Same clear the rejoin resume does.
    if (resume.firstState() == State.SERVER_TRANSFER_DETECTED) {
      travel.transferLevelKey = 0;
    }
    setState(resume.firstState());
    say(mc, "§eAuto Level 7§7: §astarted. §7Run §f/ryzn level7 stop§7 to stop it.");
  }

  public void stop() {
    final Minecraft mc = Minecraft.getInstance();
    children.releaseAllControls();
    active = false;
    restartHold = false;
    state = State.IDLE;
    activeChild = ActiveChild.NONE;
    waitReason = "Stopped";
    say(mc, "§eAuto Level 7§7: §fstopped §7- every macro control and the pathfinder are released.");
  }

  /**
   * Holds a stopped route after a rejoin, an in-SkyBlock server swap or a death, so {@link
   * Level7Recovery} can warp to the Hub first. Any of them can leave the player where no stage can
   * start (the private island, a respawn point), so they take the same {@code /hub} leg as the
   * restart hop and resume through {@link #restartResumeState}.
   *
   * @param cause what interrupted the run, for the chat line
   * @return true when the route was interrupted and is now held for the Hub warp
   */
  boolean holdAfterInterrupt(String cause) {
    if (lastLiveState == State.IDLE || lastLiveState == State.DONE) {
      return false;
    }
    children.releaseAllControls();
    active = true;
    restartHold = true;
    state = lastLiveState;
    activeChild = ActiveChild.NONE;
    waitReason = "Back after " + cause + "; warping to the Hub";
    say(
        Minecraft.getInstance(),
        "§eAuto Level 7§7: §eback after " + cause + " §7- warping to the Hub before resuming.");
    return true;
  }

  State lastLiveState() {
    return lastLiveState;
  }

  /**
   * True while the route is running or bringing itself back, so a child's own death recovery does
   * not restart the child behind the route's back.
   *
   * @return true when the route is running or recovering
   */
  public boolean ownsInterruptedRun() {
    return active || recovery.isRecovering();
  }

  /**
   * Like {@link #ownsInterruptedRun}, but for a rejoin, which the route also claims when it was
   * live as the reconnect began.
   *
   * @return true when the rejoin belongs to the route
   */
  public boolean ownsRejoin() {
    return ownsInterruptedRun() || recovery.wasLiveAtReconnect();
  }

  /**
   * Both resumes re-enter in the HUB - the restart hop ends on {@code /hub}, and a drop respawns
   * there - so a foraging child has to warp back to the park before it picks its checkpoint up, or
   * it asks the pathfinder to WALK to a plateau only a transfer pad reaches. Only the restart path
   * armed this, so a plain disconnect re-entered PATH_JUNGLE_TRANSFER standing in Hub Forest and
   * re-routed forever (log 2026-09-03, goal(-439,111,-17) from (-222,73,-16), goal chunk NOT
   * CACHED).
   *
   * <p>Must run BEFORE {@code setState} - that is what enables the child, and the flag is consumed
   * by its {@code beginSession}. Self-gating: a checkpoint that has not burned Charlie's Travel
   * Scroll yet ignores it, because Hypixel refuses {@code /warp park} until then.
   */
  private void armParkWarpForChild(State next) {
    if (next == State.FORAGING_UNLOCK || next == State.FORAGING_COLLECTIONS) {
      RyznBootstrap.eventBus()
          .post(new ForagingQuestActionEvent(ForagingQuestActionEvent.Action.ARM_PARK_WARP_RESUME));
    }
  }

  /**
   * Freeze the route where it stands because the server said it is about to restart. Children are
   * released (they would keep swinging through the evacuation) but {@link #active} and {@link
   * #state} are kept: the first is what stops the failsafe and {@code MacroReconnect} from treating
   * the hop as an abandoned run, the second is the anchor {@link #resumeAfterRestart} re-enters
   * from. See {@link Level7Recovery}.
   *
   * @return true when the route was running and is now held
   */
  public boolean holdForRestart() {
    if (!active || restartHold) {
      return false;
    }
    children.releaseAllControls();
    restartHold = true;
    activeChild = ActiveChild.NONE;
    waitReason = "Server restart announced";
    return true;
  }

  public boolean isHoldingForRestart() {
    return restartHold;
  }

  /**
   * Where the recovery is, shown on the HUD card in place of the frozen state machine's wait
   * reason.
   */
  void setRestartWaitReason(String reason) {
    if (restartHold) {
      waitReason = reason;
    }
  }

  /** Pick the route back up in the Hub the restart hop just came back to. */
  public void resumeAfterRestart() {
    if (!restartHold) {
      return;
    }
    restartHold = false;
    tickCounter = 0;
    travel.sendingToServerDetected = false;
    final State next = restartResumeState(state);
    armParkWarpForChild(next);
    // Pick the shovel up at the step it reached, not the top: its craft already spent the gold.
    if (next == State.CARPENTRY_DONE && children.liveShovelResume != null) {
      children.pendingShovelStep = children.liveShovelResume.step();
      children.pendingShovelAxeLeg = children.liveShovelResume.axeLeg();
    }
    say(
        Minecraft.getInstance(),
        "§eAuto Level 7§7: §eback in the Hub §7- resuming at " + next.label + ".");
    setState(next);
  }

  /**
   * Where a restart hop re-enters, given where the route was when the warning landed. The hop
   * always ends in the Hub, so this is really one question: is this stage reached by walking out of
   * the Hub, or is it already there?
   */
  static State restartResumeState(State interrupted) {
    return switch (interrupted) {
      // The Desert half is reached ON FOOT from the Hub - Barn gate, launch pad, settlement - and
      // the
      // Sand Miner has no pathfinding of its own, so resuming it in the Hub just swings at nothing.
      case GOLD_SHOVEL_DONE, SERVER_TRANSFER_DETECTED, SAND_PHASE -> State.GOLD_SHOVEL_DONE;
      // The hub warp this state exists to send has already happened.
      case HUB_WARP -> State.FORAGING_UNLOCK;
      case REV_HUB_WARP -> State.REV_LEVEL_2;
      // Everything else runs out of the Hub, which is exactly where the hop put us.
      default -> interrupted;
    };
  }

  /**
   * Lines for {@code /level7test status}: current state, Farming, Mining, active child, wait
   * reason.
   *
   * @return the status lines, one per entry
   */
  public List<String> statusLines() {
    skills.poll(Minecraft.getInstance());
    final int farming = skills.level(SkillLevelDetector.FARMING);
    final int mining = skills.level(SkillLevelDetector.MINING);

    final List<String> lines = new ArrayList<>();
    lines.add("§eAuto Level 7§7: §fstatus");
    lines.add("§7 state: §f" + (active ? state.name() + " §7(" + state.label + ")" : "inactive"));
    lines.add("§7 Farming: §f" + levelText(farming));
    lines.add("§7 Mining: §f" + levelText(mining));
    lines.add("§7 TAB data: §f" + (skills.sawTabData() ? "yes" : "no"));
    lines.add("§7 active child: §f" + activeChild.label);
    lines.add("§7 child status: §f" + children.activeChildStatus());
    lines.add(
        "§7 wait reason: §f" + (waitReason == null || waitReason.isBlank() ? "-" : waitReason));
    return lines;
  }

  private static String levelText(int level) {
    return level < 0 ? "unknown (waiting for TAB data)" : Integer.toString(level);
  }

  public long elapsedMillis() {
    return runStartedNanos == 0L
        ? 0L
        : Math.max(0L, (System.nanoTime() - runStartedNanos) / 1_000_000L);
  }

  public String statusText() {
    if (restartHold) {
      return waitReason;
    }
    if (active) {
      return state.label;
    }
    return waitReason == null || waitReason.isBlank() ? "Stopped" : waitReason;
  }

  public static String[] resumeCheckpointLabels() {
    return Level7Checkpoints.resumeCheckpointLabels();
  }

  public static int[] routeCheckpointOrder() {
    return Level7Checkpoints.routeCheckpointOrder();
  }

  public static String[] resumeStageLabels(int checkpointIndex) {
    return Level7Checkpoints.resumeStageLabels(checkpointIndex);
  }

  public int selectedCheckpoint() {
    return Mth.clamp(savedCheckpoint.get(), 1, Checkpoint.values().length);
  }

  public int selectedStage() {
    return Mth.clamp(savedStage.get(), 1, checkpoint(selectedCheckpoint()).stages.length);
  }

  public void selectCheckpoint(int checkpointIndex) {
    savedCheckpoint.set(Mth.clamp(checkpointIndex, 1, Checkpoint.values().length));
    savedStage.set(1);
    RyznSettings.get().save();
  }

  public void selectStage(int stageIndex) {
    savedStage.set(Mth.clamp(stageIndex, 1, checkpoint(selectedCheckpoint()).stages.length));
    RyznSettings.get().save();
  }

  static State resumeState(int checkpointIndex, int stageIndex) {
    return Level7Checkpoints.resumeState(checkpointIndex, stageIndex);
  }

  static int resumeForagingCheckpoint(int checkpointIndex, int stageIndex) {
    return Level7Checkpoints.resumeForagingCheckpoint(checkpointIndex, stageIndex);
  }

  static int resumeCollectionLeg(int checkpointIndex, int stageIndex) {
    return Level7Checkpoints.resumeCollectionLeg(checkpointIndex, stageIndex);
  }

  static boolean resumeCollectionFinalSale(int checkpointIndex, int stageIndex) {
    return Level7Checkpoints.resumeCollectionFinalSale(checkpointIndex, stageIndex);
  }

  // ===== Tick

  public void onClientTick(Minecraft mc) {
    if (!active || restartHold) {
      return;
    }
    final LocalPlayer player = mc.player;
    final ClientLevel level = mc.level;
    if (player == null || level == null) {
      // Mid load / server transfer: hold position in the state machine until the world is back.
      waitReason = "Waiting for world";
      return;
    }

    lastLiveState = state;
    if (tickCounter++ % POLL_INTERVAL_TICKS == 0) {
      skills.poll(mc);
    }

    switch (state) {
      case START -> children.tickInitialFarmingGate();
      case FARMING_6_REACHED -> children.tickAwaitLevel6Sell();
      case AFTER_LEVEL6_SELL -> children.tickAwaitMiningMerchant();
      case MINING_MERCHANT_DONE -> children.tickAwaitBea(State.BEA_DONE);
      case BEA_DONE -> children.tickFarmGate(FARMING_SECOND_TARGET, State.FARMING_12_REACHED);
      case FARMING_12_REACHED -> children.tickAwaitLevel12Sell();
      case AFTER_LEVEL12_SELL -> children.tickAwaitCarpentry();
      case CARPENTRY_DONE -> children.tickAwaitGoldShovel();
      case GOLD_SHOVEL_DONE -> travel.tickAwaitServerTransfer(mc);
      case SERVER_TRANSFER_DETECTED -> travel.tickTransferRoutes(mc);
      case SAND_PHASE -> children.tickMiningGate();
      case HUB_WARP -> travel.tickHubWarp(mc, nextRouteState(State.HUB_WARP));
      case FORAGING_UNLOCK, FORAGING_COLLECTIONS -> children.tickAwaitForagingUnlock();
      case REV_HUB_WARP -> travel.tickHubWarp(mc, nextRouteState(State.REV_HUB_WARP));
      case REV_LEVEL_2 -> children.tickAwaitRevLevel2();
      default -> waitReason = "";
    }
  }

  // ---- skill gates ----

  // ---- child modules ----

  /** The fixed final route: Sand -> Rev Slayer -> Foraging -> complete. */
  static State nextRouteState(State completed) {
    return switch (completed) {
      case SAND_PHASE -> State.REV_HUB_WARP;
      case REV_HUB_WARP -> State.REV_LEVEL_2;
      case REV_LEVEL_2 -> State.HUB_WARP;
      case HUB_WARP -> State.FORAGING_UNLOCK;
      case FORAGING_UNLOCK, FORAGING_COLLECTIONS -> State.DONE;
      default -> throw new IllegalArgumentException("No route continuation for " + completed);
    };
  }

  // ---- pathfind / server transfer ----

  // ===== State entry

  void setState(State next) {
    state = next;
    travel.transferStep = 0;
    travel.transferDelayTicks = 0;
    travel.transferRetryCooldown = 0;
    travel.routeAttempts = 0;
    travel.hubSettleTicks = 0;
    travel.atWarpTicks = 0;
    children.childDownTicks = 0;
    children.childRestarts = 0;
    if (next == State.SERVER_TRANSFER_DETECTED) {
      travel.sandStartNode = null;
    }
    switch (next) {
      case START -> {
        children.enableWheatWithOptions();
        activeChild = ActiveChild.WHEAT_BREAKER;
        waitReason = "Farming to " + FARMING_FIRST_TARGET;
      }
      case FARMING_6_REACHED -> {
        RyznBootstrap.eventBus()
            .post(
                new WheatBreakerActionEvent(
                    WheatBreakerActionEvent.Action.SELL_THEN_PAUSE_FOR_LEVEL7));
        activeChild = ActiveChild.WHEAT_BREAKER;
        waitReason = "Selling inventory at Farm Merchant";
      }
      case AFTER_LEVEL6_SELL -> {
        if (RyznBootstrap.macroManager().getByClass(MiningMerchantMacro.class).isEnabled()) {
          RyznBootstrap.eventBus().post(new MiningMerchantActionEvent(false));
        }
        RyznBootstrap.eventBus().post(new MiningMerchantActionEvent(true));
        activeChild = ActiveChild.MINING_MERCHANT;
        waitReason = "Buying 32 gold ingots from the Mining Merchant";
      }
      case MINING_MERCHANT_DONE -> {
        RyznBootstrap.eventBus().post(BeaActionEvent.start(BeaMacro.Phase.PET_ONLY));
        activeChild = ActiveChild.BEA;
        waitReason = "Buying the Bee pet";
      }
      case BEA_DONE -> {
        children.resumeWheatWithOptions();
        activeChild = ActiveChild.WHEAT_BREAKER;
        waitReason = "Farming to " + FARMING_SECOND_TARGET;
      }
      case FARMING_12_REACHED -> {
        RyznBootstrap.eventBus()
            .post(
                new WheatBreakerActionEvent(
                    WheatBreakerActionEvent.Action.SELL_THEN_PAUSE_FOR_LEVEL7));
        activeChild = ActiveChild.WHEAT_BREAKER;
        waitReason = "Selling inventory at Farm Merchant";
      }
      case AFTER_LEVEL12_SELL -> {
        RyznBootstrap.eventBus()
            .post(new WheatBreakerActionEvent(WheatBreakerActionEvent.Action.SET_ENABLED, false));
        RyznBootstrap.eventBus()
            .post(new WheatBreakerActionEvent(WheatBreakerActionEvent.Action.CLEAR_LEVEL7_PAUSE));
        if (RyznBootstrap.macroManager().getByClass(CarpentryUnlockMacro.class).isEnabled()) {
          RyznBootstrap.eventBus().post(new CarpentryUnlockActionEvent(false));
        }
        RyznBootstrap.eventBus().post(new CarpentryUnlockActionEvent(true));
        activeChild = ActiveChild.CARPENTRY;
        waitReason = "Running Carpentry Unlock";
      }
      case CARPENTRY_DONE -> {
        if (RyznBootstrap.macroManager().getByClass(GoldShovelMacro.class).isEnabled()) {
          RyznBootstrap.eventBus().post(GoldShovelActionEvent.enabled(false));
        }
        // A "Gold Shovel: ..." resume stage drops it straight into that step, once.
        if (children.pendingShovelStep == null
            || !children.startGoldShovelAt(children.pendingShovelStep)) {
          RyznBootstrap.eventBus().post(GoldShovelActionEvent.enabled(true));
        }
        children.pendingShovelStep = null;
        activeChild = ActiveChild.GOLD_SHOVEL;
        waitReason = "Running Gold Shovel";
      }
      case GOLD_SHOVEL_DONE -> {
        travel.sendingToServerDetected = false;
        // Capture the pre-transfer world now (still loaded); route A waits until this identity
        // changes.
        travel.transferLevelKey = travel.levelKey(Minecraft.getInstance());
        activeChild = ActiveChild.PATHFINDER;
        travel.startTransferRoute(warpRoute, "Warp route");
      }
      case SERVER_TRANSFER_DETECTED -> {
        // Route A starts in tickTransferRoutes once the new world has loaded (see
        // transferLevelKey).
        activeChild = ActiveChild.PATHFINDER;
        waitReason = "Waiting for server transfer to finish";
      }
      case SAND_PHASE -> {
        children.enableSandMiner();
        activeChild = ActiveChild.SAND_MINER;
        waitReason = "Mining sand to Mining " + MINING_TARGET;
      }
      case HUB_WARP -> {
        RyznBootstrap.eventBus()
            .post(new SandMinerActionEvent(SandMinerActionEvent.Action.SET_ENABLED, false));
        WalkingNavigation.stopNavQuiet();
        activeChild = ActiveChild.NONE;
        travel.sendHubWarp(Minecraft.getInstance());
      }
      case FORAGING_UNLOCK -> children.startForagingUnlocker(false);
      case FORAGING_COLLECTIONS -> children.startForagingUnlocker(true);
      case REV_HUB_WARP -> {
        RyznBootstrap.eventBus()
            .post(new SandMinerActionEvent(SandMinerActionEvent.Action.SET_ENABLED, false));
        RyznBootstrap.eventBus()
            .post(
                new ForagingQuestActionEvent(
                    ForagingQuestActionEvent.Action.SET_CLEAR_INVENTORY_AT_MERCHANT, false));
        WalkingNavigation.stopNavQuiet();
        activeChild = ActiveChild.NONE;
        travel.sendHubWarp(Minecraft.getInstance());
      }
      case REV_LEVEL_2 -> {
        if (!RyznBootstrap.eventBus()
            .post(new RevLevel2ActionEvent(RevLevel2ActionEvent.Action.START_FROM_LEVEL7))
            .started()) {
          failRoute("Rev Slayer 2 could not start in the Hub");
          return;
        }
        activeChild = ActiveChild.REV_LEVEL_2;
        waitReason = "Running Rev Slayer 2";
      }
      case DONE -> {
        RyznBootstrap.eventBus()
            .post(new SandMinerActionEvent(SandMinerActionEvent.Action.SET_ENABLED, false));
        WalkingNavigation.stopNavQuiet();
        activeChild = ActiveChild.NONE;
        waitReason = "Complete";
        active = false;
        String duration = HudFormat.duration(elapsedMillis());
        runStartedNanos = 0L;
        say(
            Minecraft.getInstance(),
            "§eAuto Level 7§7: §aall done §7- Mining "
                + MINING_TARGET
                + "+ reached, the Foraging quests unlocked, and Zombie Slayer LVL 2 rewards "
                + "claimed."
                + " Every control is released. Total time: §f"
                + duration
                + "§7.");
      }
      default -> {
        activeChild = ActiveChild.NONE;
        waitReason = "";
      }
    }
  }

  // ===== Helpers

  void failRoute(String message) {
    children.releaseAllControls();
    active = false;
    restartHold = false;
    state = State.IDLE;
    activeChild = ActiveChild.NONE;
    waitReason = message;
    say(Minecraft.getInstance(), "§eAuto Level 7§7: §cstopped §7- " + message);
  }

  static String stopReason(String reason) {
    return reason == null || reason.isBlank() ? "unknown reason" : reason;
  }

  static void say(Minecraft mc, String msg) {
    RyznChat.send(msg);
  }

  @Override
  public boolean menuEnabled() {
    return isActive();
  }

  @Override
  public void menuToggle() {
    if (isActive()) {
      RyznBootstrap.eventBus().post(new Level7ActionEvent(Level7ActionEvent.Action.STOP));
    } else {
      RyznBootstrap.eventBus().post(new Level7ActionEvent(Level7ActionEvent.Action.START));
    }
  }
}
