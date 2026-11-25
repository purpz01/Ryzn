package ryzn.macro.client.feature.macros.impl.level7;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import ryzn.macro.client.core.RyznBootstrap;
import ryzn.macro.client.core.events.api.GameMessageEvent;
import ryzn.macro.client.feature.commands.impl.MacroCommands;
import ryzn.macro.client.feature.reconnect.EvictionLines;
import ryzn.macro.client.feature.reconnect.MacroReconnect;
import ryzn.macro.client.util.text.RyznChat;
import ryzn.macro.client.world.WorldChangeDetector;

/**
 * Puts the Level 7 route back to work after the two things that end a run without the user asking:
 * a Hypixel restart / game update, and a dropped connection.
 *
 * <p>The restart path leaves on its own terms rather than waiting to be evacuated mid-swing. It
 * goes to the PRIVATE ISLAND, not the hub: an island is its own server, so it rides out a hub or a
 * Barn restart instead of sitting in the queue for whatever the evacuation hands us. Once the
 * outage is over it warps back to the Hub, which is where every stage of the route except the
 * Desert half lives - and the Desert half is walked out of the Hub anyway (Barn gate, launch pad,
 * settlement), so one warp covers both.
 *
 * <p>The drop path is the global {@link MacroReconnect}; only the route back is ours. Two things it
 * never sees also come back here: a server swap that stays inside SkyBlock (another Hub, the
 * island) and a death, both of which stop the route without leaving SkyBlock.
 *
 * <p>"Was this run interrupted" is measured against the START of the recovery, not against arrival:
 * the world-change failsafe stops the route before the drop is even visible, and the walk back
 * (settle, {@code /lobby}, a laggy {@code /skyblock}) eats most of a minute.
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class Level7Recovery {

  /** How long before the eviction the route must have been running for this to count as its run. */
  private static final long RESUME_WINDOW_MS = 60_000L;

  private enum Stage {
    IDLE,
    SETTLE,
    LEAVE,
    WAIT_OUT,
    RETURN
  }

  /** Human-length pause between reading the warning and acting on it. */
  private static final int REACT_MIN_TICKS = 20 * 8;

  private static final int REACT_MAX_TICKS = 20 * 20;

  /**
   * How long the island sits out the restart. A minute is what the user asked for and what a reboot
   * takes.
   */
  private static final int WAIT_OUT_TICKS = 20 * 60;

  /**
   * A warp can be refused (PLAYER_TRANSFER_COOLDOWN), so "command sent" proves nothing - ask again.
   */
  private static final int WARP_RESEND_TICKS = 20 * 12;

  private static final int MAX_WARPS = 8;

  /**
   * Ryzn's block cache switches over a couple of seconds after the new world appears; the first
   * pathfind out of the Hub searches the island we just left without this.
   */
  private static final int ARRIVAL_SETTLE_TICKS = 20 * 5;

  /**
   * Respawn, or the new server, settling before deciding: the failsafe stop that follows a death
   * lands a few seconds after it, and a real eviction is MacroReconnect's well inside this.
   */
  private static final int SETTLE_MIN_TICKS = 20 * 12;

  private static final int SETTLE_MAX_TICKS = 20 * 20;

  private static final String ISLAND_AREA = "private island";
  private static final String HUB_AREA = "hub";

  /** The route this recovery resumes. */
  private final AutoLvl7Macro macro;

  private Stage stage = Stage.IDLE;
  private int timer;
  private int warps;
  private int settleTicks;
  private long lastRunningMs = 0L;

  /** What the pending settle is for, for the chat line once the route is held. */
  private String settleCause = "";

  /**
   * @description Registers the reconnect listeners and this recovery's own chat callback slot. A
   *     manual stop, import, or loader shutdown disarms it so it cannot restart the old run.
   */
  void registerListeners() {
    MacroReconnect.registerRecoveryListeners(this::onRejoin, this::disarm);
    RyznBootstrap.eventBus().subscribe(GameMessageEvent.class, this::onGameMessageEvent);
  }

  /**
   * @description Starts the restart hop when the server announces a restart during a live route.
   * @param gamemessageevent the received message; overlays are ignored
   */
  public void onGameMessageEvent(GameMessageEvent gamemessageevent) {
    final Component message = gamemessageevent.message();
    if (gamemessageevent.overlay() || message == null || stage != Stage.IDLE) {
      return;
    }
    if (!macro.isActive() || macro.isHoldingForRestart()) {
      return;
    }
    final String raw = message.getString();
    if (EvictionLines.isRestartWarning(raw)) {
      onRestartWarning();
    } else if (EvictionLines.isOwnDeath(raw) && !isRevStage(macro.lastLiveState())) {
      settle("a death");
    }
  }

  /**
   * @description The world-change failsafe stopped the route. Off SkyBlock or disconnected is
   *     {@link MacroReconnect}'s to walk back; a swap that stays in SkyBlock is only seen here.
   */
  void onWorldChangeStop() {
    if (stage == Stage.IDLE) {
      settle("a server swap");
    }
  }

  /**
   * @description A manual stop: drop whatever recovery was pending or under way, and forget the run
   *     so a later rejoin does not bring it back.
   */
  void cancel() {
    disarm();
  }

  private void settle(String cause) {
    settleCause = cause;
    enter(
        Stage.SETTLE, ThreadLocalRandom.current().nextInt(SETTLE_MIN_TICKS, SETTLE_MAX_TICKS + 1));
  }

  private void disarm() {
    enter(Stage.IDLE, 0);
    lastRunningMs = 0L;
  }

  public void onClientTick() {
    if (macro.isActive() && !macro.isHoldingForRestart()) {
      lastRunningMs = System.currentTimeMillis();
    }
    if (stage == Stage.IDLE) {
      return;
    }
    if (stage == Stage.SETTLE && MacroReconnect.isRecovering()) {
      // Knocked off SkyBlock after all: the rejoin event brings the route back.
      enter(Stage.IDLE, 0);
      return;
    }
    final Minecraft mc = Minecraft.getInstance();
    if (mc.player == null) {
      return;
    } // mid-reconnect: the rejoin listener owns us until the world is back
    macro.setRestartWaitReason(status());
    if (timer > 0) {
      timer--;
      return;
    }
    switch (stage) {
      case SETTLE -> resolveSettle();
      case LEAVE -> tickWarp(mc, "is", ISLAND_AREA, Stage.WAIT_OUT, WAIT_OUT_TICKS);
      case WAIT_OUT -> enter(Stage.RETURN, 0);
      case RETURN -> tickWarp(mc, "hub", HUB_AREA, null, 0);
      case IDLE -> {}

      default -> {}
    }
  }

  /** True from the restart warning until the route is running again, for the macro HUD card. */
  public boolean isRecovering() {
    return stage != Stage.IDLE;
  }

  public String status() {
    return switch (stage) {
      case IDLE -> "Idle";
      case SETTLE -> "Interrupted - settling before warping to the Hub";
      case LEAVE -> "Restart announced - warping to the Private Island";
      case WAIT_OUT -> "Sitting out the restart on the island";
      case RETURN -> "Warping back to the Hub";

      default -> throw new MatchException(null, null);
    };
  }

  /**
   * Still in SkyBlock but no longer where the stage was: take the same {@code /hub} leg a rejoin
   * does, whether the failsafe already stopped the route or it is still nominally running.
   */
  private void resolveSettle() {
    enter(Stage.IDLE, 0);
    if (macro.isActive()) {
      if (!macro.isHoldingForRestart() && macro.holdForRestart()) {
        enter(Stage.RETURN, 0);
      }
      return;
    }
    if (System.currentTimeMillis() - lastRunningMs < RESUME_WINDOW_MS
        && macro.holdAfterInterrupt(settleCause)) {
      enter(Stage.RETURN, 0);
    }
  }

  private static boolean isRevStage(AutoLvl7Macro.State state) {
    return state == AutoLvl7Macro.State.REV_HUB_WARP || state == AutoLvl7Macro.State.REV_LEVEL_2;
  }

  /** True when the route was live as the current reconnect began, the same test the rejoin uses. */
  boolean wasLiveAtReconnect() {
    return lastRunningMs != 0L
        && MacroReconnect.recoveryStartedMs() != 0L
        && MacroReconnect.recoveryStartedMs() - lastRunningMs < RESUME_WINDOW_MS;
  }

  private void onRestartWarning() {
    if (!macro.holdForRestart()) {
      return;
    }
    RyznChat.send(
        "§bLevel 7 Test§7: §ethe server announced a restart §7- warping to the Private "
            + "Island and coming back once it is over.");
    enter(Stage.LEAVE, ThreadLocalRandom.current().nextInt(REACT_MIN_TICKS, REACT_MAX_TICKS + 1));
  }

  /**
   * Send a warp and advance only once the TAB area says it landed - the same "observed state, never
   * 'click sent'" rule the menu macros run on. The last leg ({@code next == null}) hands back to
   * the route.
   */
  private void tickWarp(Minecraft mc, String command, String area, Stage next, int nextTimer) {
    if (isInArea(area)) {
      // The new ClientLevel exists a couple of seconds before Ryzn's block cache has switched to
      // it.
      if (++settleTicks < ARRIVAL_SETTLE_TICKS) {
        return;
      }
      if (next != null) {
        enter(next, nextTimer);
        return;
      }
      finish();
      return;
    }
    if (++warps > MAX_WARPS) {
      RyznChat.send(
          "§bLevel 7 Test§7: §estill not in the "
              + area
              + " after "
              + MAX_WARPS
              + " warps §7- carrying on without it.");
      // Never fail open on the way OUT: resuming the grind on a server that is about to go down is
      // the
      // exact failure this whole path exists to avoid. Sit the outage out where we stand instead.
      if (next != null) {
        enter(next, nextTimer);
        return;
      }
      finish();
      return;
    }
    MacroCommands.sendCommand(mc, command);
    timer = WARP_RESEND_TICKS;
  }

  private void enter(Stage next, int wait) {
    stage = next;
    timer = wait;
    warps = 0;
    settleTicks = 0;
  }

  private void finish() {
    enter(Stage.IDLE, 0);
    macro.resumeAfterRestart();
  }

  private boolean isInArea(String needle) {
    final String area = WorldChangeDetector.currentArea();
    return area != null && area.toLowerCase(Locale.ROOT).contains(needle);
  }

  private void onRejoin() {
    // A drop on top of a restart hop: we are back in SkyBlock, so the hop's own tail is what to
    // run. A pending settle never held the route, so it takes the normal rejoin path below.
    if (stage == Stage.SETTLE) {
      enter(Stage.IDLE, 0);
    }
    if (stage != Stage.IDLE) {
      enter(Stage.RETURN, 0);
      return;
    }
    if (macro.isActive()) {
      return;
    }
    if (lastRunningMs == 0L
        || MacroReconnect.recoveryStartedMs() == 0L
        || MacroReconnect.recoveryStartedMs() - lastRunningMs >= RESUME_WINDOW_MS) {
      return;
    }
    if (macro.holdAfterInterrupt("a disconnect")) {
      enter(Stage.RETURN, 0);
    }
  }
}
