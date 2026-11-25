package ryzn.macro.client.feature.macros.impl.level7;

import static ryzn.macro.client.feature.macros.impl.level7.RevLevel2Gear.INVENTORY_TIMEOUT_TICKS;
import static ryzn.macro.client.feature.macros.impl.level7.RevLevel2Merchants.WEAPONSMITH_POSITION;
import static ryzn.macro.client.feature.macros.impl.level7.RevLevel2SlayerRoute.MADDOX_POSITION;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import ryzn.macro.client.MacroInput;
import ryzn.macro.client.core.RyznBootstrap;
import ryzn.macro.client.core.events.api.GameMessageEvent;
import ryzn.macro.client.core.events.api.GoldShovelActionEvent;
import ryzn.macro.client.core.events.api.RevLevel2ActionEvent;
import ryzn.macro.client.core.events.api.Subscribe;
import ryzn.macro.client.feature.api.MenuToggle;
import ryzn.macro.client.feature.macros.api.Macro;
import ryzn.macro.client.feature.macros.impl.goldshovel.GoldShovelMacro;
import ryzn.macro.client.feature.pathfinder.WalkingNavigation;
import ryzn.macro.client.feature.rotation.api.LookController;
import ryzn.macro.client.util.gui.MacroScreens;
import ryzn.macro.client.util.text.RyznChat;

/**
 * Gets the Level 2 Revenant starter equipment from the Hub merchants.
 *
 * <p>The macro verifies every purchase in the local inventory before advancing. Its armor transfer
 * uses normal pickup clicks and confirms that the cursor is empty before it moves to the next armor
 * slot.
 */
public final class RevLevel2Macro extends Macro implements MenuToggle {

  private final RevLevel2SlayerRoute slayer = new RevLevel2SlayerRoute(this);

  final RevLevel2Merchants merchants = new RevLevel2Merchants(this);

  final RevLevel2Gear gear = new RevLevel2Gear(this);

  @RequiredArgsConstructor
  enum State {
    IDLE("Idle"),
    PATH_TO_WEAPONSMITH("Pathfinding to Weaponsmith"),
    OPEN_WEAPONSMITH("Opening Weaponsmith"),
    BUY_UNDEAD_SWORD("Buying Undead Sword"),
    CLOSE_WEAPONSMITH("Closing Weaponsmith"),
    MOVE_SWORD_TO_HOTBAR("Moving Undead Sword to hotbar"),
    RETURN_ENCHANTED_SWORD_TO_HOTBAR("Returning enchanted Undead Sword to hotbar"),
    PATH_TO_ROSETTA("Pathfinding to Rosetta"),
    OPEN_ROSETTA("Opening Rosetta"),
    OPEN_IRON_ARMOR("Opening Iron Armor"),
    BUY_IRON_ARMOR("Buying Iron Armor"),
    CLOSE_ROSETTA("Closing Rosetta"),
    EQUIP_IRON_ARMOR("Equipping Iron Armor"),
    RECOVER_GEAR("Recovering starter gear"),
    START_UNDEAD_SWORD_SMITE("Preparing Smite V on Undead Sword"),
    WAIT_UNDEAD_SWORD_SMITE("Enchanting Undead Sword with Smite V"),
    PATH_TO_MADDOX("Pathfinding to Maddox"),
    OPEN_MADDOX("Opening Maddox"),
    SELECT_REVENANT("Selecting Revenant Horror"),
    SELECT_REVENANT_ONE("Selecting Revenant Horror I"),
    CONFIRM_REVENANT("Confirming Slayer quest"),
    WAIT_QUEST_STARTED("Waiting for Slayer quest"),
    RUN_SLAYER("Killing Graveyard Zombies"),
    WAIT_DEATH_RESULT("Recovering from death"),
    RETURN_TO_ZOMBIES("Returning to the zombies"),
    PATH_TO_MADDOX_REWARD("Returning to Maddox"),
    OPEN_MADDOX_REWARD("Opening Maddox reward"),
    COLLECT_QUEST_REWARD("Collecting Slayer reward"),
    CLOSE_MADDOX_REWARD("Closing Maddox reward"),
    OPEN_MADDOX_AGAIN("Opening Maddox again"),
    PREPARE_LEVEL_REWARDS("Preparing Zombie Slayer rewards"),
    PATH_TO_MADDOX_LEVEL_REWARDS("Returning to Maddox for level rewards"),
    OPEN_MADDOX_LEVEL_ONE("Opening Maddox for level 1 reward"),
    SELECT_REVENANT_LEVEL_ONE("Selecting Revenant Horror for level 1 reward"),
    OPEN_LEVELING_REWARDS_ONE("Opening Boss Leveling Rewards for level 1"),
    CLAIM_ZOMBIE_LEVEL_ONE("Claiming Zombie Slayer LVL 1"),
    CLOSE_LEVEL_ONE_REWARD("Closing Zombie Slayer LVL 1 reward"),
    OPEN_MADDOX_LEVEL_TWO("Opening Maddox for level 2 reward"),
    SELECT_REVENANT_LEVEL_TWO("Selecting Revenant Horror for level 2 reward"),
    OPEN_LEVELING_REWARDS_TWO("Opening Boss Leveling Rewards for level 2"),
    CLAIM_ZOMBIE_LEVEL_TWO("Claiming Zombie Slayer LVL 2"),
    CLOSE_LEVEL_TWO_REWARD("Closing Zombie Slayer LVL 2 reward"),
    COMPLETE("Complete");

    final String label;
  }

  private static final double MADDOX_ARRIVAL_DISTANCE_SQUARED = 0.75 * 0.75;

  final RevLevel2SlayerCombat combat = new RevLevel2SlayerCombat();

  @Getter private boolean active;
  State state = State.IDLE;
  String status = "Idle";
  int stateTicks;
  int nextActionTicks;
  int pathRetryTicks;
  int pathAttempts;

  int menuGoneTicks;

  private boolean lastRunCompleted;
  private String lastStopReason = "";

  /**
   * @description Creates the process-lifetime Rev lvl 2 route with its slayer combat collaborator.
   */
  public RevLevel2Macro() {
    super(
        "rev_lvl_2",
        "Rev lvl 2",
        "Buys starter gear, enchants the Undead Sword with Smite V, then starts Revenant Horror I"
            + " with Maddox.");
  }

  public String statusText() {
    return active ? state.label + ": " + status : status;
  }

  public boolean wasLastRunCompleted() {
    return lastRunCompleted;
  }

  public String lastStopReason() {
    return lastStopReason;
  }

  /**
   * @description Records quest start, death, and Zombie Slayer level signals while running, then
   *     forwards the line to the slayer combat collaborator.
   * @param gamemessageevent the received message; overlays are ignored
   */
  public void onGameMessageEvent(GameMessageEvent gamemessageevent) {
    final Component message = gamemessageevent.message();
    if (gamemessageevent.overlay() || !active || message == null) {
      return;
    }
    final String text = message.getString();
    if (state == State.WAIT_QUEST_STARTED && RevLevel2SlayerCombat.isQuestStartedMessage(text)) {
      slayer.slayerQuestStarted = true;
    }
    if (inSlayerFight(state)) {
      if (RevLevel2SlayerCombat.isPlayerDeathMessage(text)) {
        slayer.slayerRunDied = true;
      }
      if (RevLevel2SlayerCombat.isQuestFailedMessage(text)) {
        slayer.slayerQuestFailed = true;
      }
    }
    if (acceptsLevelTwoSignal(state) && RevLevel2SlayerCombat.isZombieSlayerLevelTwoMessage(text)) {
      slayer.zombieSlayerLevelTwo = true;
      slayer.levelRewardsPending = true;
    }
    combat.onChat(text);
  }

  /**
   * @description Applies a start or stop request from the menu, command, failsafe, or Level 7. A
   *     Level 7 start reports whether the child actually started.
   * @param revlevel2actionevent the requested action and its response field
   */
  @Subscribe
  public void onRevLevel2ActionEvent(RevLevel2ActionEvent revlevel2actionevent) {
    switch (revlevel2actionevent.action()) {
      case START -> start();
      case START_FROM_LEVEL7 -> revlevel2actionevent.setStarted(startFromLevel7());
      case STOP -> stop();
      default -> throw new IllegalArgumentException("Unknown Rev lvl 2 action");
    }
  }

  public void start() {
    start(false);
  }

  private boolean start(boolean managedByLevel7) {
    final Minecraft mc = Minecraft.getInstance();
    if (active) {
      RyznChat.send("§bRev lvl 2§7: §ealready running §7(" + state.label + ").");
      return false;
    }
    if (!managedByLevel7 && RyznBootstrap.failsafeManager().automationActive()) {
      RyznChat.send("§bRev lvl 2§7: §estop the running macro or pathfinder first.");
      return false;
    }
    if (mc.player == null || mc.level == null || mc.gameMode == null) {
      RyznChat.send("§bRev lvl 2§7: §ecannot start until you are fully in the Hub.");
      return false;
    }
    active = true;
    lastRunCompleted = false;
    lastStopReason = "";
    gear.armorPieceIndex = 0;
    pathAttempts = 0;
    pathRetryTicks = 0;
    gear.gearRecoveries = 0;
    merchants.approachMerchant = false;
    slayer.slayerQuestStarted = false;
    slayer.slayerRunDied = false;
    slayer.slayerQuestFailed = false;
    slayer.slayerFightSpot = null;
    slayer.zombieSlayerLevelTwo = false;
    combat.stop();
    gear.clearInventoryMove();
    final State firstState = initialState(slayer.levelRewardsPending);
    setState(
        firstState,
        slayer.levelRewardsPending
            ? "Resuming Zombie Slayer level rewards"
            : "Starting route to Weaponsmith");
    RyznChat.announce("Rev lvl 2", true);
    return true;
  }

  boolean startFromLevel7() {
    return RyznBootstrap.macroManager().getByClass(AutoLvl7Macro.class).isActive() && start(true);
  }

  public void stop() {
    if (!active) {
      return;
    }
    releaseControls();
    active = false;
    state = State.IDLE;
    status = "Stopped";
    lastRunCompleted = false;
    lastStopReason = "Stopped";
    RyznChat.announce("Rev lvl 2", false);
  }

  public void onClientTick(Minecraft mc) {
    if (!active) {
      return;
    }
    final LocalPlayer player = mc.player;
    final ClientLevel level = mc.level;
    if (player == null || level == null || mc.gameMode == null) {
      fail("left the world before finishing");
      return;
    }
    stateTicks++;
    if (nextActionTicks > 0) {
      nextActionTicks--;
    }
    if (pathRetryTicks > 0) {
      pathRetryTicks--;
    }
    if (slayer.zombieSlayerLevelTwo) {
      slayer.zombieSlayerLevelTwo = false;
      slayer.slayerRunDied = false;
      slayer.slayerQuestFailed = false;
      combat.stop();
      setState(State.PREPARE_LEVEL_REWARDS, "Zombie Slayer LVL 2 reached");
    } else if (slayer.slayerQuestFailed) {
      slayer.slayerQuestFailed = false;
      slayer.slayerRunDied = false;
      slayer.slayerQuestStarted = false;
      combat.stop();
      setState(recoveryStateAfterDeath(true), "Slayer quest failed; starting a new quest");
    } else if (slayer.slayerRunDied) {
      slayer.slayerRunDied = false;
      combat.stop();
      setState(State.WAIT_DEATH_RESULT, "Player died");
    }

    switch (state) {
      case PATH_TO_WEAPONSMITH ->
          merchants.tickPathToMerchant(
              player, level, "Weaponsmith", WEAPONSMITH_POSITION, State.OPEN_WEAPONSMITH);
      case OPEN_WEAPONSMITH ->
          merchants.tickOpenMerchant(
              mc,
              player,
              level,
              WEAPONSMITH_POSITION,
              "Weaponsmith",
              State.BUY_UNDEAD_SWORD,
              State.PATH_TO_WEAPONSMITH);
      case BUY_UNDEAD_SWORD -> gear.tickBuySword(mc, player);
      case CLOSE_WEAPONSMITH -> tickCloseScreen(mc, State.MOVE_SWORD_TO_HOTBAR, "Weaponsmith");
      case MOVE_SWORD_TO_HOTBAR, RETURN_ENCHANTED_SWORD_TO_HOTBAR ->
          gear.tickMoveSwordToHotbar(mc, player, swordHotbarMoveDestination(state));
      case PATH_TO_ROSETTA ->
          merchants.tickPathToMerchant(player, level, "Rosetta", null, State.OPEN_ROSETTA);
      case OPEN_ROSETTA ->
          merchants.tickOpenMerchant(
              mc,
              player,
              level,
              WEAPONSMITH_POSITION,
              "Rosetta",
              State.OPEN_IRON_ARMOR,
              State.PATH_TO_ROSETTA);
      case OPEN_IRON_ARMOR -> gear.tickOpenIronArmor(mc, player);
      case BUY_IRON_ARMOR -> gear.tickBuyArmor(mc, player);
      case CLOSE_ROSETTA -> tickCloseScreen(mc, State.EQUIP_IRON_ARMOR, "Rosetta");
      case EQUIP_IRON_ARMOR -> gear.tickEquipArmor(mc, player);
      case RECOVER_GEAR -> gear.tickRecoverGear(mc, player);
      case START_UNDEAD_SWORD_SMITE -> gear.tickStartUndeadSwordSmite(mc);
      case WAIT_UNDEAD_SWORD_SMITE -> gear.tickAwaitUndeadSwordSmite(mc);
      case PATH_TO_MADDOX -> slayer.tickCombatPathToMaddox(mc, player, State.OPEN_MADDOX);
      case OPEN_MADDOX ->
          merchants.tickOpenMerchant(
              mc, player, level, MADDOX_POSITION, "Maddox", State.SELECT_REVENANT, null);
      case SELECT_REVENANT ->
          slayer.tickMenuChoice(
              mc, player, "Revenant Horror", "click to view boss", State.SELECT_REVENANT_ONE);
      case SELECT_REVENANT_ONE ->
          slayer.tickMenuChoice(
              mc, player, "Revenant Horror I", "click to slay", State.CONFIRM_REVENANT);
      case CONFIRM_REVENANT ->
          slayer.tickMenuChoice(
              mc, player, "Confirm", "click to start quest", State.WAIT_QUEST_STARTED);
      case WAIT_QUEST_STARTED -> slayer.tickWaitQuestStarted(mc);
      case RUN_SLAYER -> slayer.tickSlayerCombat(mc, player);
      case WAIT_DEATH_RESULT -> slayer.tickWaitDeathResult(mc);
      case RETURN_TO_ZOMBIES -> slayer.tickReturnToZombies(mc, player);
      case PATH_TO_MADDOX_REWARD ->
          slayer.tickCombatPathToMaddox(mc, player, State.OPEN_MADDOX_REWARD);
      case OPEN_MADDOX_REWARD ->
          merchants.tickOpenMerchant(
              mc, player, level, MADDOX_POSITION, "Maddox", State.COLLECT_QUEST_REWARD, null);
      case COLLECT_QUEST_REWARD ->
          slayer.tickMenuChoice(
              mc,
              player,
              "Slayer Quest Complete!",
              "click to collect reward",
              State.CLOSE_MADDOX_REWARD);
      case CLOSE_MADDOX_REWARD -> tickCloseScreen(mc, State.OPEN_MADDOX_AGAIN, "Maddox reward");
      case OPEN_MADDOX_AGAIN ->
          merchants.tickOpenMerchant(
              mc, player, level, MADDOX_POSITION, "Maddox", State.SELECT_REVENANT, null);
      case PREPARE_LEVEL_REWARDS ->
          tickCloseScreen(mc, State.PATH_TO_MADDOX_LEVEL_REWARDS, "Slayer quest menu");
      case PATH_TO_MADDOX_LEVEL_REWARDS ->
          slayer.tickPathToMaddox(mc, player, State.OPEN_MADDOX_LEVEL_ONE);
      case OPEN_MADDOX_LEVEL_ONE ->
          merchants.tickOpenMerchant(
              mc, player, level, MADDOX_POSITION, "Maddox", State.SELECT_REVENANT_LEVEL_ONE, null);
      case SELECT_REVENANT_LEVEL_ONE ->
          slayer.tickMenuChoice(
              mc, player, "Revenant Horror", "click to view boss", State.OPEN_LEVELING_REWARDS_ONE);
      case OPEN_LEVELING_REWARDS_ONE ->
          slayer.tickMenuChoice(
              mc,
              player,
              "Boss Leveling Rewards",
              "click to view levels",
              State.CLAIM_ZOMBIE_LEVEL_ONE);
      case CLAIM_ZOMBIE_LEVEL_ONE ->
          slayer.tickLevelReward(mc, player, "Zombie Slayer LVL 1", State.CLOSE_LEVEL_ONE_REWARD);
      case CLOSE_LEVEL_ONE_REWARD ->
          tickCloseScreen(mc, State.OPEN_MADDOX_LEVEL_TWO, "Zombie Slayer LVL 1 reward");
      case OPEN_MADDOX_LEVEL_TWO ->
          merchants.tickOpenMerchant(
              mc, player, level, MADDOX_POSITION, "Maddox", State.SELECT_REVENANT_LEVEL_TWO, null);
      case SELECT_REVENANT_LEVEL_TWO ->
          slayer.tickMenuChoice(
              mc, player, "Revenant Horror", "click to view boss", State.OPEN_LEVELING_REWARDS_TWO);
      case OPEN_LEVELING_REWARDS_TWO ->
          slayer.tickMenuChoice(
              mc,
              player,
              "Boss Leveling Rewards",
              "click to view levels",
              State.CLAIM_ZOMBIE_LEVEL_TWO);
      case CLAIM_ZOMBIE_LEVEL_TWO ->
          slayer.tickLevelReward(mc, player, "Zombie Slayer LVL 2", State.CLOSE_LEVEL_TWO_REWARD);
      case CLOSE_LEVEL_TWO_REWARD ->
          tickCloseScreen(mc, State.COMPLETE, "Zombie Slayer LVL 2 reward");
      case COMPLETE -> finish();
      default -> fail("invalid state");
    }
  }

  private void tickCloseScreen(Minecraft mc, State next, String screenName) {
    if (nextActionTicks > 0) {
      status = "Waiting to close " + screenName;
      return;
    }
    if (MacroScreens.closeCurrentScreenIfCursorEmpty(mc)) {
      setState(next, "Closed " + screenName);
      return;
    }
    if (stateTicks > INVENTORY_TIMEOUT_TICKS) {
      gear.gearFailure("put down the item on your cursor before closing " + screenName);
    } else {
      status = "Waiting for cursor to clear";
    }
  }

  static State stateAfterSmiteCompletion() {
    return State.RETURN_ENCHANTED_SWORD_TO_HOTBAR;
  }

  static State swordHotbarMoveDestination(State moveState) {
    return moveState == State.RETURN_ENCHANTED_SWORD_TO_HOTBAR
        ? State.PATH_TO_MADDOX
        : State.PATH_TO_ROSETTA;
  }

  static boolean isWithinMaddoxArrival(double playerX, double playerY, double playerZ) {
    final double x = playerX - (MADDOX_POSITION.getX() + 0.5);
    final double z = playerZ - (MADDOX_POSITION.getZ() + 0.5);
    return x * x + z * z <= MADDOX_ARRIVAL_DISTANCE_SQUARED
        && Math.abs(playerY - MADDOX_POSITION.getY()) <= 3.0;
  }

  static State initialState(boolean rewardsPending) {
    return rewardsPending ? State.PREPARE_LEVEL_REWARDS : State.PATH_TO_WEAPONSMITH;
  }

  /** A failed quest (died to the boss) starts over at Maddox; otherwise the quest is still on. */
  static State recoveryStateAfterDeath(boolean questFailed) {
    return questFailed ? State.PATH_TO_MADDOX : State.RETURN_TO_ZOMBIES;
  }

  static boolean inSlayerFight(State current) {
    return current == State.RUN_SLAYER
        || current == State.WAIT_DEATH_RESULT
        || current == State.RETURN_TO_ZOMBIES;
  }

  static boolean acceptsLevelTwoSignal(State current) {
    return current.ordinal() >= State.SELECT_REVENANT.ordinal()
        && current.ordinal() <= State.OPEN_MADDOX_AGAIN.ordinal();
  }

  boolean isServerMenuOpen(Minecraft mc) {
    return MacroScreens.currentContainerMenu(mc) != null
        && !(mc.gui.screen() instanceof InventoryScreen);
  }

  void setState(State next, String nextStatus) {
    state = next;
    status = nextStatus;
    stateTicks = 0;
    nextActionTicks = 0;
    pathRetryTicks = 0;
    gear.purchaseRequested = false;
    gear.purchaseClicks = 0;
    gear.purchaseWaitTicks = 0;
    menuGoneTicks = 0;
    merchants.noViewTicks = 0;
    if (next == State.EQUIP_IRON_ARMOR || next == State.BUY_IRON_ARMOR) {
      gear.armorPieceIndex = 0;
    }
    if (next == State.BUY_UNDEAD_SWORD
        || next == State.OPEN_IRON_ARMOR
        || next == State.RECOVER_GEAR) {
      merchants.approachMerchant = false;
    }
    if (next == State.PATH_TO_ROSETTA
        || next == State.PATH_TO_MADDOX
        || next == State.PATH_TO_MADDOX_REWARD
        || next == State.PATH_TO_MADDOX_LEVEL_REWARDS) {
      pathAttempts = 0;
    }
    look().clear();
    if (next != State.PATH_TO_WEAPONSMITH) {
      WalkingNavigation.stopNavQuiet();
    }
  }

  private void finish() {
    releaseControls();
    active = false;
    state = State.IDLE;
    status = "Complete";
    slayer.levelRewardsPending = false;
    lastRunCompleted = true;
    lastStopReason = "";
    RyznChat.send("§bRev lvl 2§7: §aZombie Slayer LVL 1 and LVL 2 rewards claimed.");
  }

  void fail(String reason) {
    releaseControls();
    active = false;
    state = State.IDLE;
    status = "Failed: " + reason;
    lastRunCompleted = false;
    lastStopReason = reason;
    RyznChat.send("§bRev lvl 2§7: §cfailed §7- " + reason + ".");
  }

  private void releaseControls() {
    final Minecraft mc = Minecraft.getInstance();
    WalkingNavigation.stopNavQuiet();
    look().clear();
    combat.stop();
    if (RyznBootstrap.macroManager().getByClass(GoldShovelMacro.class).isEnabled()) {
      RyznBootstrap.eventBus().post(GoldShovelActionEvent.enabled(false));
    }
    gear.clearInventoryMove();
    if (mc != null) {
      MacroInput.releaseAllKeys(mc.options);
    }
  }

  @Override
  public boolean menuEnabled() {
    return isActive();
  }

  @Override
  public void menuToggle() {
    if (isActive()) {
      RyznBootstrap.eventBus().post(new RevLevel2ActionEvent(RevLevel2ActionEvent.Action.STOP));
    } else {
      RyznBootstrap.eventBus().post(new RevLevel2ActionEvent(RevLevel2ActionEvent.Action.START));
    }
  }

  /**
   * @description Returns this macro's aim controller from the shared rotation subsystem.
   * @return the controller the rotation manager keeps for this owner
   */
  LookController look() {
    return RyznBootstrap.rotationManager().lookController(this, 45.0f);
  }
}
