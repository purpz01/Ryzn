package ryzn.macro.client.feature.macros.impl.level7;

import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import ryzn.macro.client.core.RyznBootstrap;
import ryzn.macro.client.feature.commands.impl.MacroCommands;
import ryzn.macro.client.feature.commands.impl.pathfinder.PathfindCommand;
import ryzn.macro.client.feature.macros.impl.level7.AutoLvl7Macro.State;
import ryzn.macro.client.feature.macros.impl.sand.SandMinerMacro;
import ryzn.macro.client.world.WorldChangeDetector;

/**
 * @description Owns the Level 7 world-transfer routes, Hub warps and their retry progress.
 */
final class Level7Travel {

  private final AutoLvl7Macro hunt;

  Level7Travel(AutoLvl7Macro hunt) {
    this.hunt = hunt;
  }

  static final int ROUTE_DELAY_TICKS = 60;

  static final int TRANSFER_RETRY_COOLDOWN_TICKS = 20;

  /**
   * A loaded Hub can take a while to hand over; only resend /hub once the wait is clearly not
   * normal.
   */
  static final int HUB_WARP_TIMEOUT_TICKS = 20 * 30;

  /** Ryzn's block cache switched ~2 s after the new world appeared in the log; 5 s covers it. */
  static final int HUB_SETTLE_TICKS = 20 * 5;

  /**
   * ~15 s of back-to-back no-path answers. A goal in the world we already left never becomes
   * reachable, so the honest move is to stop and say which resume stage matches where the player is
   * standing.
   */
  static final int MAX_ROUTE_ATTEMPTS = 15;

  static final double TRANSFER_ARRIVAL_HORIZONTAL_DIST_SQ = 3.0 * 3.0;

  static final double TRANSFER_ARRIVAL_VERTICAL_DIST = 4.0;

  /** The pad throws the player well above it; nothing on the walk there gains this much height. */
  static final double PAD_LAUNCH_HEIGHT = 6.0;

  /** Upward speed only the pad gives; a normal jump starts at 0.42. */
  static final double PAD_LAUNCH_VELOCITY = 0.6;

  /** The failsafe's warp grace is 15 s, so re-arm it well inside that while waiting at the gate. */
  static final int WARP_EXPECT_REARM_TICKS = 20 * 10;

  /** Standing at the Barn gate this long with no transfer means the portal was never entered. */
  static final int WARP_WAIT_TIMEOUT_TICKS = 20 * 30;

  static final int[] warpRoute = {88, 72, -153};

  static final int[] transferRouteA = {142, 92, -312};

  int transferStep;

  int transferDelayTicks;

  int transferRetryCooldown;

  int transferLevelKey;

  int routeAttempts;

  int hubSettleTicks;

  int hubReturnTicks;

  int atWarpTicks;

  int[] sandStartNode;

  boolean sendingToServerDetected;

  /** Sends /hub once, then again only if the warp has clearly not happened. */
  void tickReturnToHub(Minecraft mc) {
    if (mc.player == null || mc.level == null) {
      hunt.waitReason = "Warping to the Hub";
      return;
    }
    if (hubReturnTicks++ % HUB_WARP_TIMEOUT_TICKS == 0) {
      sendHubWarp(mc);
    }
    hunt.waitReason = "Not in the Hub; warping back before the next stage";
  }

  /**
   * The warp lands as a fresh world instance; resend rather than hang if Hypixel swallowed the
   * command.
   */
  void tickHubWarp(Minecraft mc, State next) {
    // Arrival is "the TAB area says Hub", not "the world object changed". Waiting on the world swap
    // meant
    // that starting this stage while ALREADY in the Hub never finished: /hub is a no-op there, so
    // the
    // level identity never changed and the 30 s timeout resent /hub forever. Same lesson as the
    // wheat
    // breaker's lobby hop and the unlocker's /warp park - confirm where you are, never that you
    // asked.
    if (isInHub()) {
      if (++hubSettleTicks < HUB_SETTLE_TICKS) {
        hunt.waitReason = "In the Hub; letting the world cache settle";
        return;
      }
      hunt.setState(next);
      return;
    }
    if (levelKey(mc) == transferLevelKey) {
      if (++transferDelayTicks > HUB_WARP_TIMEOUT_TICKS) {
        transferDelayTicks = 0;
        sendHubWarp(mc);
        return;
      }
      hunt.waitReason = "Warping to the Hub";
      return;
    }
    // The new ClientLevel exists a couple of seconds before Ryzn's block cache has switched over
    // to
    // it. Handing the route on at the level swap alone makes the unlocker's first search run
    // against
    // the island we just left, which answers "no path" (log 2026-08-15, 12:03:55).
    if (++hubSettleTicks < HUB_SETTLE_TICKS) {
      hunt.waitReason = "Hub loaded; letting the world cache settle";
      return;
    }
    hunt.setState(next);
  }

  void sendHubWarp(Minecraft mc) {
    if (isInHub()) {
      hunt.waitReason = "Already in the Hub";
      return;
    }
    transferLevelKey = levelKey(mc);
    MacroCommands.sendCommand(mc, "hub");
    hunt.waitReason = "Warping to the Hub";
  }

  boolean isInHub() {
    final String area = WorldChangeDetector.currentArea();
    return area != null && area.toLowerCase(Locale.ROOT).contains("hub");
  }

  void tickAwaitServerTransfer(Minecraft mc) {
    // The world swap after reaching the gate is the transfer too, in case the chat line was missed.
    if (sendingToServerDetected || (atWarpTicks > 0 && levelKey(mc) != transferLevelKey)) {
      // transferLevelKey was captured on entry (the pre-transfer world); route A waits for it to
      // change.
      PathfindCommand.stopNavQuiet();
      hunt.setState(State.SERVER_TRANSFER_DETECTED);
      return;
    }
    final LocalPlayer player = mc.player;
    if (player != null && isNearTransferTarget(player, warpRoute)) {
      if (atWarpTicks % WARP_EXPECT_REARM_TICKS == 0) {
        RyznBootstrap.failsafeManager().expectWorldChange();
      }
      if (++atWarpTicks > WARP_WAIT_TIMEOUT_TICKS) {
        hunt.failRoute(
            "stood at the Barn gate for "
                + WARP_WAIT_TIMEOUT_TICKS / 20
                + "s with no server transfer - the portal was not entered");
        return;
      }
      hunt.waitReason = "At the warp, waiting for \"Sending to server\"";
      return;
    }
    // A search that comes back no-path just ends the route, so the walk has to be re-armed like the
    // transfer routes are; without this the macro sat here forever reporting that it was
    // pathfinding.
    keepTransferRouteRunning(warpRoute, "Warp route");
  }

  void tickTransferRoutes(Minecraft mc) {
    final LocalPlayer player = mc.player;
    if (player == null) {
      hunt.waitReason = "Waiting for world";
      return;
    }
    switch (transferStep) {
      case 0 -> {
        if (levelKey(mc) == transferLevelKey) {
          hunt.waitReason = "Waiting for server transfer to finish";
          return;
        }
        // New world loaded: finish route A, then hold the required 3 seconds before route B.
        transferStep = 1;
        transferRetryCooldown = 0;
        startTransferRoute(transferRouteA, "Route A");
      }
      case 1 -> {
        if (wasLaunched(player, transferRouteA)) {
          PathfindCommand.stopNavQuiet();
          transferStep = 2;
          transferDelayTicks = 0;
          hunt.waitReason = "Route A reached; waiting 3s before Sand route";
          return;
        }
        keepTransferRouteRunning(transferRouteA, "Route A");
      }
      case 2 -> {
        if (!player.onGround()) {
          hunt.waitReason = "In flight to the Desert Settlement";
          return; // the 3s only starts once the pad has set us down
        }
        if (++transferDelayTicks < ROUTE_DELAY_TICKS) {
          hunt.waitReason = "Route A reached; waiting 3s before Sand route";
          return;
        }
        transferStep = 3;
        transferRetryCooldown = 0;
        sandStartNode =
            RyznBootstrap.macroManager()
                .getByClass(SandMinerMacro.class)
                .prepareRandomRoute(player);
        if (sandStartNode == null) {
          hunt.waitReason = "Add at least two points to a Sand route";
          return;
        }
        startTransferRoute(sandStartNode, "Sand route");
      }
      default -> {
        if (sandStartNode == null) {
          sandStartNode =
              RyznBootstrap.macroManager()
                  .getByClass(SandMinerMacro.class)
                  .prepareRandomRoute(player);
        }
        if (sandStartNode == null) {
          hunt.waitReason = "Add at least two points to a Sand route";
          return;
        }
        if (isNearTransferTarget(player, sandStartNode)) {
          PathfindCommand.stopNavQuiet();
          hunt.setState(State.SAND_PHASE);
          return;
        }
        keepTransferRouteRunning(sandStartNode, "Sand route");
      }
    }
  }

  void keepTransferRouteRunning(int[] route, String label) {
    if (PathfindCommand.isNavigationActive()) {
      // Only a route that actually DRIVES clears the counter; a search still in flight has proven
      // nothing.
      if (PathfindCommand.isRouteDriving()) {
        routeAttempts = 0;
      }
      hunt.waitReason = label + "; walking to destination";
      return;
    }
    if (transferRetryCooldown > 0) {
      transferRetryCooldown--;
      hunt.waitReason = label + "; pathfinder stopped, retrying (" + routeAttempts + ")";
      return;
    }
    startTransferRoute(route, label);
  }

  /**
   * Every attempt takes the cooldown, not just a refused one: a search that resolves to "no path"
   * ends the route a tick later, and retrying that every single tick is a search storm, not a
   * retry.
   */
  void startTransferRoute(int[] route, String label) {
    if (++routeAttempts > MAX_ROUTE_ATTEMPTS) {
      hunt.failRoute(
          label
              + " ("
              + route[0]
              + " "
              + route[1]
              + " "
              + route[2]
              + ") is unreachable from here"
              + " after "
              + MAX_ROUTE_ATTEMPTS
              + " tries - pick the resume stage that matches the world"
              + " you are standing in");
      return;
    }
    transferRetryCooldown = TRANSFER_RETRY_COOLDOWN_TICKS;
    if (PathfindCommand.startToQuiet(route[0], route[1], route[2])) {
      hunt.waitReason = label + "; walking to destination";
      return;
    }
    hunt.waitReason = label + "; waiting for pathfinder (" + routeAttempts + ")";
  }

  /**
   * The pad fires the tick it is stepped on, so the flight itself is the real "route A done"
   * signal. Merely standing near the pad is not: stopping the walk there left the player just off
   * it, never launched.
   */
  boolean wasLaunched(LocalPlayer player, int[] route) {
    return player.getDeltaMovement().y > PAD_LAUNCH_VELOCITY
        || player.getY() - route[1] > PAD_LAUNCH_HEIGHT;
  }

  boolean isNearTransferTarget(LocalPlayer player, int[] route) {
    final double dx = (route[0] + 0.5) - player.getX();
    final double dz = (route[2] + 0.5) - player.getZ();
    return dx * dx + dz * dz <= TRANSFER_ARRIVAL_HORIZONTAL_DIST_SQ
        && Math.abs(player.getY() - route[1]) <= TRANSFER_ARRIVAL_VERTICAL_DIST;
  }

  int levelKey(Minecraft mc) {
    return mc.level != null ? System.identityHashCode(mc.level) : 0;
  }
}
