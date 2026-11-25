package ryzn.macro.client.feature.macros.impl.foragingquest;

import static ryzn.macro.client.MacroInput.releaseAllKeys;
import static ryzn.macro.client.feature.macros.impl.foragingquest.ForagingQuestUnlockerMacro.PATH_TIMEOUT_TICKS;
import static ryzn.macro.client.feature.macros.impl.foragingquest.ForagingQuestUnlockerMacro.transferChain;
import static ryzn.macro.client.feature.macros.impl.foragingquest.ForagingQuestUnlockerMacro.transferStates;
import static ryzn.macro.client.util.gui.MacroScreens.closeCurrentScreen;
import static ryzn.macro.client.util.text.MacroText.normalizeSearchText;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import ryzn.macro.client.core.RyznBootstrap;
import ryzn.macro.client.core.events.api.TreeChopActionEvent;
import ryzn.macro.client.feature.commands.impl.MacroCommands;
import ryzn.macro.client.feature.hud.SkyBlockHud;
import ryzn.macro.client.feature.macros.impl.foragingquest.ForagingRoute.Target;
import ryzn.macro.client.feature.pathfinder.WalkingNavigation;
import ryzn.macro.client.feature.rotation.impl.RouteGaze;
import ryzn.macro.client.input.MacroKeys;
import ryzn.macro.client.world.WorldChangeDetector;

/**
 * @description Walks quest and recovery legs, including Park transfers, warps, and the existing Oak
 *     lobby hop.
 */
final class ForagingQuestTravel {

  /**
   * Per leg of the walk back. Looser than the in-area hops: a leg can be ~100 blocks and a climb.
   */
  private static final int WALK_BACK_TIMEOUT_TICKS = 20 * 180;

  /**
   * Let him settle before creeping in again, else the restart walks straight back into the same
   * shout.
   */
  private static final int RYAN_REJECT_WAIT_TICKS = 20 * 5;

  private static final int LOBBY_HOP_GIVE_UP_TICKS = 20 * 180;

  private static final int LOBBY_HOP_HUB_RESEND_TICKS = 40;

  private static final int LOBBY_HOP_HUB_SETTLE_TICKS = 60;

  private static final int LOBBY_HOP_ISLAND_WAIT_TICKS = 100;

  private static final int PARK_WARP_GIVE_UP_TICKS = 20 * 60 * 2;

  private static final int PARK_WARP_RESEND_TICKS = 20 * 5;

  private static final String HUB_AREA_NEEDLE = "hub";

  /**
   * {@code /warp park} is confirmed by the sidebar zone, never assumed - Hypixel refuses a warp
   * outright while a transfer cooldown is running, and a flat timer would walk the next step in the
   * Jungle.
   */
  private static final String PARK_AREA_NEEDLE = "park";

  private static final double PATH_ARRIVAL_SLACK_HEIGHT = 5.0;

  private static final double PATH_ARRIVAL_SLACK_DIST_SQ = 8.0 * 8.0;

  /** One re-route per second while a search keeps coming back empty - not one per tick. */
  private static final int PATH_RETRY_INTERVAL_TICKS = 20;

  private final ForagingQuestUnlockerMacro quest;
  private Target contactedTransfer;

  ForagingQuestTravel(ForagingQuestUnlockerMacro quest) {
    this.quest = quest;
  }

  void tickPathing(Minecraft mc, LocalPlayer player, Target target, ForagingQuestState next) {
    tickPathing(mc, player, target, next, PATH_TIMEOUT_TICKS);
  }

  /**
   * Walk to an NPC while watching it - see the {@code watch} note on the full {@link #tickPathing}.
   */
  void tickPathing(
      Minecraft mc,
      LocalPlayer player,
      ClientLevel level,
      ForagingRoute.Npc npc,
      ForagingQuestState next) {
    tickPathing(mc, player, npc.target(), next, PATH_TIMEOUT_TICKS, quest.route.npc(level, npc));
  }

  void tickPathing(
      Minecraft mc, LocalPlayer player, Target target, ForagingQuestState next, int timeoutTicks) {
    tickPathing(mc, player, target, next, timeoutTicks, null);
  }

  /**
   * {@code watch} is the NPC this walk is heading for, when there is one: the head goes onto it for
   * the last stretch while the executor keeps driving the body, instead of staring down the path
   * and only turning on arrival. {@link RouteGaze} owns the when; null just walks.
   *
   * <p>A null {@code next} means the caller owns the finish line: reaching {@code target} ends
   * nothing, the walk just keeps re-aiming at it until the caller changes state. That is what
   * walking onto a transfer pad needs - being near the pad is not the same as having crossed.
   */
  void tickPathing(
      Minecraft mc,
      LocalPlayer player,
      Target target,
      ForagingQuestState next,
      int timeoutTicks,
      Entity watch) {
    final Target leg = parkDetour(target);
    if (leg != target) {
      target = leg;
      next = null;
      watch = null;
    }
    if (!target.equals(quest.pathTarget)) {
      contactedTransfer = null;
      if (quest.pathTarget != null) {
        WalkingNavigation.stopNavQuiet();
        quest.actionTiming.clearPathRequest();
        quest.stateTicks = 0;
      }
      quest.pathTarget = target;
    }
    quest.stateTicks++;
    releaseAllKeys(mc.options);
    if (!quest.gaze.update(player, watch, quest.look())) {
      quest.look().clear();
    }

    if (mc.gui.screen() != null) {
      closeCurrentScreen(mc);
      quest.status = "Closing screen before walking";
      return;
    }
    if (next != null && hasArrived(player, target)) {
      WalkingNavigation.stopNavQuiet();
      quest.setState(next);
      return;
    }
    if (quest.timedOut(timeoutTicks)) {
      quest.fail("Path to " + target.label() + " timed out");
      return;
    }
    if (target.equals(contactedTransfer) && !player.onGround()) {
      WalkingNavigation.stopNavQuiet();
      quest.actionTiming.clearPathRequest();
      quest.look().clear();
      quest.status = "Waiting to land after " + target.label();
      return;
    }
    if (walkOntoTransfer(mc, player, target)) {
      return;
    }
    if (!quest.actionTiming.isPathRequested()) {
      if (!WalkingNavigation.startToQuiet(target.x(), target.y(), target.z())) {
        quest.fail("Could not start path to " + target.label());
        return;
      }
      quest.actionTiming.markPathRequested();
      quest.status = "Walking to " + target.label();
      return;
    }
    if (!WalkingNavigation.isNavigationActive() && quest.stateTicks > 20) {
      // Navigation ended without arriving (hasArrived already ruled that out above), so this is a
      // no-path answer - re-arm rather than start the next step from across the island. The timeout
      // still bounds the whole state.
      if (quest.stateTicks % PATH_RETRY_INTERVAL_TICKS == 0) {
        quest.actionTiming.clearPathRequest();
      }
      quest.status = "Re-routing to " + target.label();
      return;
    }
  }

  private boolean walkOntoTransfer(Minecraft mc, LocalPlayer player, Target target) {
    if (transferIndex(target) < 0 && !ForagingRoute.LEAVE_A.equals(target)) {
      return false;
    }
    final double dx = target.x() + 0.5 - player.getX();
    final double dz = target.z() + 0.5 - player.getZ();
    if (!player.onGround()
        || dx * dx + dz * dz > 2.0 * 2.0
        || Math.abs(player.getY() - target.y()) > 0.6) {
      return false;
    }
    WalkingNavigation.stopNavQuiet();
    quest.actionTiming.clearPathRequest();
    contactedTransfer = target;
    if (dx * dx + dz * dz > 0.15 * 0.15) {
      quest
          .look()
          .aimAtPoint(player, new Vec3(target.x() + 0.5, player.getEyeY(), target.z() + 0.5));
      final double[] steer = ForagingQuestUnlockerMacro.centerSteering(dx, dz, player.getYRot());
      MacroKeys.set(mc.options.keyUp, steer[0] > 0.05);
      MacroKeys.set(mc.options.keyDown, steer[0] < -0.05);
      MacroKeys.set(mc.options.keyRight, steer[1] > 0.05);
      MacroKeys.set(mc.options.keyLeft, steer[1] < -0.05);
    } else {
      quest.look().clear();
    }
    quest.status = "Walking onto " + target.label();
    return true;
  }

  /**
   * The park areas only connect through their transfers, in order, so a walk that starts an area
   * short (a void death respawns the player areas back, log 2026-10-01 16:57) walks the next
   * transfer first instead of pathing straight across. The sidebar location says where we are.
   */
  Target parkDetour(Target target) {
    final int required = requiredParkArea(quest.state);
    if (required < 0) {
      return target;
    }
    final int current = ForagingRoute.parkAreaIndex(SkyBlockHud.currentArea());
    return current >= 0 && current < required ? transferChain[current + 1] : target;
  }

  /**
   * @return the park area a quest state is walked from, or -1 when it has none (before Birch Park,
   *     the deliberate walks off Molbert's island, and everything after the quest)
   */
  static int requiredParkArea(ForagingQuestState state) {
    if (state.ordinal() > ForagingQuestState.CLICK_MOLE_HAT.ordinal() || isLeaveWalk(state)) {
      return -1;
    }
    for (int k = transferStates.length - 1; k >= 0; k--) {
      if (state.ordinal() >= transferStates[k].ordinal()) {
        return state == transferStates[k] ? k - 1 : k;
      }
    }
    return -1;
  }

  static int transferIndex(Target target) {
    for (int i = 0; i < transferChain.length; i++) {
      if (transferChain[i].equals(target)) {
        return i;
      }
    }
    return -1;
  }

  static boolean isLeaveWalk(ForagingQuestState state) {
    return switch (state) {
      case PATH_LEAVE_ONE_A,
          DELAY_LEAVE_ONE_A,
          PATH_LEAVE_ONE_B,
          DELAY_LEAVE_ONE_B,
          PATH_LEAVE_TWO_A,
          DELAY_LEAVE_TWO_A,
          PATH_LEAVE_TWO_B,
          DELAY_LEAVE_TWO_B ->
          true;
      default -> false;
    };
  }

  /**
   * Transfers finish only after entering the destination area. Other walks accept the target block
   * or navigation ending nearby.
   */
  boolean hasArrived(LocalPlayer player, Target target) {
    if (ForagingRoute.LEAVE_A.equals(target)) {
      return ForagingRoute.hasLeftJungleIsland(player);
    }
    final int leadsInto = transferIndex(target);
    if (leadsInto >= 0) {
      return ForagingRoute.parkAreaIndex(SkyBlockHud.currentArea()) >= leadsInto
          && player.onGround()
          && player.getY() >= target.y() + 2.0
          && (!ForagingRoute.JUNGLE_TRANSFER.equals(target)
              || ForagingRoute.isOnJungleIsland(player));
    }
    if (quest.route.isAtTarget(player, target)) {
      return true;
    }
    return quest.stateTicks > 20
        && !WalkingNavigation.isNavigationActive()
        && isNearTarget(player, target);
  }

  /**
   * Walk the transfer chain back to the area the run was interrupted in, one leg at a time, then
   * re-enter at the saved checkpoint. Legs are walked in order because the park's areas are laid
   * out in a line - getting to acacia means passing spruce and dark oak on the way.
   */
  void tickWalkBack(Minecraft mc, LocalPlayer player) {
    while (quest.walkBackLeg < quest.walkBackLegs
        && hasArrived(player, transferChain[quest.walkBackLeg])) {
      quest.walkBackLeg++;
      WalkingNavigation.stopNavQuiet();
      quest.stateTicks = 0;
      quest.actionTiming.enterState();
    }
    if (quest.walkBackLeg >= quest.walkBackLegs) {
      quest.setState(quest.walkBackNext);
      return;
    }
    tickPathing(
        mc,
        player,
        transferChain[quest.walkBackLeg],
        ForagingQuestState.RESUME_TRAVEL,
        WALK_BACK_TIMEOUT_TICKS);
  }

  /**
   * Walk back onto the Jungle transfer pad, and call the leg done only once we are actually back ON
   * the island. Arrival slack cannot stand in for that: the "Leave the area" launcher throws the
   * player clear of the plateau and drops them a few blocks short of the pad - well inside the
   * slack - so the leg reported success from the wrong side of it and the run then spent its whole
   * timeout asking for a walking route up to a Molbert that only the pad reaches (log 2026-08-28,
   * 13:12:48). Handing {@code tickPathing} a null next state keeps it aiming at the pad until the
   * pad itself fires.
   */
  void tickPathToJungleIsland(Minecraft mc, LocalPlayer player, ForagingQuestState next) {
    if (ForagingRoute.isOnJungleIsland(player)) {
      WalkingNavigation.stopNavQuiet();
      quest.setState(next);
      return;
    }
    tickPathing(mc, player, ForagingRoute.JUNGLE_TRANSFER, null, PATH_TIMEOUT_TICKS);
  }

  /**
   * States allowed to change world under the failsafe without it reading as an eviction.
   * Unannounced, a world change stops the whole route mid-quest (log 2026-08-15, 12:06:47);
   * re-arming every tick of these steps keeps the grace open however long the step takes.
   *
   * <p>Only the scroll and the warp are real server hops. The park transfers were listed here on
   * the assumption they were too, but they are NOT: entering Spruce Woods printed "NEW AREA
   * DISCOVERED" with no "Sending to server", no login packet and no instance change (log
   * 2026-08-24, 17:11:16). The park is one server. They stay listed because the grace is harmless,
   * and the same fact is what lets {@code RESUME_TRAVEL} simply WALK back to a far-side area after
   * a rejoin.
   */
  boolean isTravellingBetweenServers() {
    return switch (quest.state) {
      case PATH_BIRCH_TRANSFER,
          WAIT_BIRCH_TRANSFER,
          PATH_SPRUCE_TRANSFER,
          WAIT_SPRUCE_AREA,
          DELAY_SPRUCE_AREA,
          PATH_DARK_TRANSFER,
          WAIT_DARK_AREA,
          DELAY_DARK_AREA,
          PATH_ACACIA_TRANSFER,
          WAIT_ACACIA_AREA,
          DELAY_ACACIA_AREA,
          PATH_JUNGLE_TRANSFER,
          WAIT_JUNGLE_AREA,
          DELAY_JUNGLE_AREA,
          USE_PARK_SCROLL,
          WAIT_SCROLL_CONSUMED,
          WARP_HUB,
          WAIT_HUB_AREA,
          WARP_PARK,
          WAIT_PARK_AREA,
          LOBBY_HOP_SEND_IS,
          LOBBY_HOP_WAIT_IS,
          LOBBY_HOP_SEND_HUB,
          LOBBY_HOP_SETTLE,
          // The collection legs walk to those same pads, and the Hub -> Park one IS a real hop
          // ("Sending to server mini120BT", log 2026-09-02 11:22:27). It landed two seconds AFTER
          // the walk handed over to the chop, so the run died reading its own travel as an
          // eviction;
          // the 15 s grace armed here covers that handover.
          PATH_COLLECTION_AREA,
          PATH_COLLECTION_JUNGLE ->
          true;
      default -> false;
    };
  }

  boolean isRyanApproach(ForagingQuestState s) {
    return s == ForagingQuestState.PATH_RYAN_BACK || s == ForagingQuestState.INTERACT_RYAN_START;
  }

  /**
   * Sit out his cooldown, then approach his live blind side again. He teleports us back to the top
   * of his area, and nothing owns the look while we wait, so keep the rotation failsafe standing
   * down for the whole cooldown - otherwise his yank reads as a forced rotation and stops us.
   */
  void tickRyanRejected(Minecraft mc) {
    RyznBootstrap.failsafeManager().expectServerRotation();
    quest.tickDelay(ForagingQuestState.PATH_RYAN_BACK, RYAN_REJECT_WAIT_TICKS);
  }

  /** Only the approach restarts on his brush-off; later in the quest he talks to us regardless. */
  boolean consumeRyanReject() {
    if (!quest.ryanRejected) {
      return false;
    }
    quest.ryanRejected = false;
    return isRyanApproach(quest.state);
  }

  /**
   * Walk toward Ryan normally until his player model loads. West/North continues that direct route;
   * East/South first takes the fixed safe waypoint, then routes directly to Ryan.
   *
   * <p>His player model only exists once the server has him in range, and this state starts at the
   * Dark Oak arrival some fifty blocks out - standing still until he appears burned 54 of the 60
   * second path budget (log 2026-09-02 10:58:09), leaving no time to walk, and his post-rejection
   * teleport does it again. So walk his way on the fixed coords while he is out of range. He loads
   * well outside the distance he can notice anything at, and the walk owns no finish line of its
   * own, so the blind-side target takes over the moment he resolves.
   */
  void tickPathBehindRyan(Minecraft mc, LocalPlayer player, ClientLevel level) {
    final Entity ryan = quest.route.ryanPlayer(level, player);
    if (ryan == null) {
      tickPathing(mc, player, ForagingRoute.RYAN.target(), null, PATH_TIMEOUT_TICKS);
      return;
    }
    final boolean needsWaypoint = ForagingRoute.ryanNeedsWaypoint(ryan.getViewYRot(1.0f));
    if (needsWaypoint
        && !quest.ryanWaypointReached
        && hasArrived(player, ForagingRoute.RYAN_EAST_SOUTH_APPROACH)) {
      quest.ryanWaypointReached = true;
      quest.ryanApproachTarget = null;
      WalkingNavigation.stopNavQuiet();
      quest.actionTiming.clearPathRequest();
    }
    final Target target =
        needsWaypoint && !quest.ryanWaypointReached
            ? ForagingRoute.RYAN_EAST_SOUTH_APPROACH
            : ForagingRoute.RYAN.target();
    if (!target.equals(quest.ryanApproachTarget)) {
      quest.ryanApproachTarget = target;
      WalkingNavigation.stopNavQuiet();
      quest.actionTiming.clearPathRequest();
    }
    tickPathing(mc, player, target, ForagingQuestState.INTERACT_RYAN_START);
  }

  /**
   * Close enough that the walk really did finish here, whatever the exact block under the feet is.
   */
  boolean isNearTarget(LocalPlayer player, Target target) {
    final double dx = target.x() + 0.5 - player.getX();
    final double dz = target.z() + 0.5 - player.getZ();
    return dx * dx + dz * dz <= PATH_ARRIVAL_SLACK_DIST_SQ
        && Math.abs(player.getY() - target.y()) <= PATH_ARRIVAL_SLACK_HEIGHT;
  }

  /**
   * The Oak landmark is only a search center: once within ten blocks, Tree Chop owns target
   * selection.
   */
  boolean isWithinTargetRadius(LocalPlayer player, Target target, double radius) {
    final double dx = target.x() + 0.5 - player.getX();
    final double dy = target.y() - player.getY();
    final double dz = target.z() + 0.5 - player.getZ();
    return dx * dx + dy * dy + dz * dz <= radius * radius;
  }

  /**
   * Sends {@code /warp park} and advances only once the sidebar says we are there, resending on a
   * cadence until it lands. A warp can be refused (PLAYER_TRANSFER_COOLDOWN), so "command sent"
   * proves nothing.
   */
  void tickWarpPark(Minecraft mc) {
    quest.stateTicks++;
    releaseAllKeys(mc.options);
    quest.look().clear();
    if (mc.gui.screen() != null) {
      closeCurrentScreen(mc);
      quest.status = "Closing screen before warping";
      return;
    }
    final String area = SkyBlockHud.currentArea();
    if (area != null && normalizeSearchText(area).contains(PARK_AREA_NEEDLE)) {
      quest.setState(ForagingQuestState.WAIT_PARK_AREA);
      return;
    }
    if (quest.stateTicks % PARK_WARP_RESEND_TICKS == 1) {
      MacroCommands.sendCommand(mc, "warp park");
    }
    quest.status = "Warping to the Park";
    if (quest.timedOut(PARK_WARP_GIVE_UP_TICKS)) {
      quest.fail("Could not warp to the Park");
    }
  }

  /** Sends {@code /hub} and waits for the Hub before the Oak collection path starts. */
  void tickWarpHub(Minecraft mc, ForagingQuestState next) {
    quest.stateTicks++;
    releaseAllKeys(mc.options);
    quest.look().clear();
    if (mc.gui.screen() != null) {
      closeCurrentScreen(mc);
      quest.status = "Closing screen before warping";
      return;
    }
    final String area = WorldChangeDetector.currentArea();
    if (area != null && normalizeSearchText(area).contains(HUB_AREA_NEEDLE)) {
      quest.setState(next);
      return;
    }
    if (quest.stateTicks % PARK_WARP_RESEND_TICKS == 1) {
      MacroCommands.sendCommand(mc, "hub");
    }
    quest.status = "Warping to the Hub";
    if (quest.timedOut(PARK_WARP_GIVE_UP_TICKS)) {
      quest.fail("Could not warp to the Hub");
    }
  }

  void tickCheckHubPlayers(Minecraft mc) {
    quest.setState(ForagingQuestState.PATH_COLLECTION_AREA);
  }

  void tickLobbyHopSendIs(Minecraft mc) {
    quest.stopQuestChop();
    RyznBootstrap.eventBus()
        .post(new TreeChopActionEvent(TreeChopActionEvent.Action.RESET_LOST_TREES));
    quest.stateTicks++;
    releaseAllKeys(mc.options);
    WalkingNavigation.stopNavQuiet();
    closeCurrentScreen(mc);
    MacroCommands.sendCommand(mc, "is");
    quest.setState(ForagingQuestState.LOBBY_HOP_WAIT_IS);
  }

  void tickLobbyHopWaitIs(Minecraft mc) {
    quest.stateTicks++;
    releaseAllKeys(mc.options);
    WalkingNavigation.stopNavQuiet();
    closeCurrentScreen(mc);
    if (quest.stateTicks >= LOBBY_HOP_ISLAND_WAIT_TICKS) {
      quest.setState(ForagingQuestState.LOBBY_HOP_SEND_HUB);
    }
  }

  void tickLobbyHopSendHub(Minecraft mc) {
    quest.stateTicks++;
    releaseAllKeys(mc.options);
    WalkingNavigation.stopNavQuiet();
    closeCurrentScreen(mc);
    MacroCommands.sendCommand(mc, "hub");
    quest.hubHopSettleTicks = 0;
    quest.setState(ForagingQuestState.LOBBY_HOP_SETTLE);
  }

  void tickLobbyHopSettle(Minecraft mc) {
    quest.stateTicks++;
    releaseAllKeys(mc.options);
    WalkingNavigation.stopNavQuiet();
    closeCurrentScreen(mc);
    final String area = WorldChangeDetector.currentArea();
    if (area == null || !normalizeSearchText(area).contains(HUB_AREA_NEEDLE)) {
      quest.hubHopSettleTicks = 0;
      if (quest.stateTicks >= LOBBY_HOP_GIVE_UP_TICKS) {
        quest.fail("Could not return to the Hub during lobby hop");
      } else if (quest.stateTicks > 0 && quest.stateTicks % LOBBY_HOP_HUB_RESEND_TICKS == 0) {
        MacroCommands.sendCommand(mc, "hub");
      }
      return;
    }
    if (++quest.hubHopSettleTicks >= LOBBY_HOP_HUB_SETTLE_TICKS) {
      quest.setState(ForagingQuestState.PATH_COLLECTION_AREA);
    }
  }
}
