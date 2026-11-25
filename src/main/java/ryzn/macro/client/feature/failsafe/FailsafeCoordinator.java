package ryzn.macro.client.feature.failsafe;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;
import ryzn.macro.client.config.RyznSettings;
import ryzn.macro.client.config.groups.FailsafeSettings;
import ryzn.macro.client.core.RyznBootstrap;
import ryzn.macro.client.core.events.api.ChillshotRecoveryEvent;
import ryzn.macro.client.core.events.api.ClientDisconnectEvent;
import ryzn.macro.client.core.events.api.EndClientTickEvent;
import ryzn.macro.client.core.events.api.StartClientTickEvent;
import ryzn.macro.client.core.events.api.WorldChangeEvent;
import ryzn.macro.client.debug.DebugLog;
import ryzn.macro.client.feature.failsafe.api.FailsafeManager;
import ryzn.macro.client.util.text.RyznChat;

public final class FailsafeCoordinator {
  private static final Set<String> WORLD_COMMANDS =
      Set.of("hub", "is", "island", "warp", "l", "lobby", "play", "skyblock");

  private final InventorySlotFailsafe inventorySlot;
  private final UnexpectedScreenFailsafe unexpectedScreen;
  private final GhostBlockFailsafe ghostBlock;
  private final DirtFailsafe dirt;
  private final RotationFailsafe rotation;
  private final WorldChangeFailsafe worldChange;
  private final ProximityFailsafe proximity;
  private final FailsafeDispatcher dispatcher =
      new FailsafeDispatcher(
          FailsafeStopper::stopAll,
          FailsafeCoordinator::offerChillshotRecovery,
          FailsafeCoordinator::notifyPlayer);

  /**
   * @description Offers a failsafe incident to the Chillshot recovery through a typed request.
   * @param reason the incident that fired
   * @return true when the recovery took the incident and will hop instead of stopping
   */
  private static boolean offerChillshotRecovery(String reason) {
    final ChillshotRecoveryEvent request = new ChillshotRecoveryEvent(reason);
    RyznBootstrap.eventBus().post(request);
    return request.accepted();
  }

  private long currentTick;

  /**
   * Wires the registered detector instances into one coordinator.
   *
   * @description Wires the registered detector instances into one coordinator.
   * @param manager the initialized owner of all detector objects
   * @throws IllegalArgumentException if a required detector is missing
   */
  public FailsafeCoordinator(FailsafeManager manager) {
    inventorySlot = manager.getByClass(InventorySlotFailsafe.class);
    unexpectedScreen = manager.getByClass(UnexpectedScreenFailsafe.class);
    ghostBlock = manager.getByClass(GhostBlockFailsafe.class);
    dirt = manager.getByClass(DirtFailsafe.class);
    rotation = manager.getByClass(RotationFailsafe.class);
    worldChange = manager.getByClass(WorldChangeFailsafe.class);
    proximity = manager.getByClass(ProximityFailsafe.class);
  }

  /**
   * Installs the original tick, disconnect, and world-change callbacks.
   *
   * @description Installs the original tick, disconnect, and world-change callbacks.
   */
  public void registerCallbacks() {
    RyznBootstrap.eventBus()
        .subscribe(StartClientTickEvent.class, event -> onStartTick(event.client()));
    RyznBootstrap.eventBus()
        .subscribe(EndClientTickEvent.class, event -> onEndTick(event.client()));
    RyznBootstrap.eventBus().subscribe(ClientDisconnectEvent.class, event -> reset());
    RyznBootstrap.eventBus()
        .subscribe(WorldChangeEvent.class, event -> onWorldChange(event.newInstance()));
  }

  /**
   * Turn every macro / module off and persist the off state. Shared panic path: the failsafes, the
   * Ryzn keybind, and the startup sweep that clears toggles a crash left persisted as on.
   *
   * @description Delegates the shared stop path and preserves its reason in notifications.
   * @param reason the message explaining the stop
   */
  public void stopAllMacros(String reason) {
    FailsafeStopper.stopAll(reason);
  }

  /**
   * Reports whether foreground automation is currently active.
   *
   * @description Reports whether foreground automation is currently active.
   * @return true while a supported automation is running
   */
  public boolean automationActive() {
    return FailsafeActivity.foregroundActive();
  }

  /**
   * Records an intentional selected-slot change at the current tick.
   *
   * @description Records an intentional selected-slot change at the current tick.
   * @param slot the zero-based hotbar slot
   */
  public void expectSelectedSlot(int slot) {
    inventorySlot.expect(slot, currentTick);
  }

  /**
   * Records the requested yaw and pitch for forced-turn detection.
   *
   * @description Records the requested yaw and pitch for forced-turn detection.
   * @param yaw the expected yaw
   * @param pitch the expected pitch
   */
  public void expectRotation(float yaw, float pitch) {
    rotation.expect(yaw, pitch);
  }

  /**
   * Feeds a break to throughput and dirt detectors.
   *
   * @description Feeds a break to throughput and dirt detectors.
   * @param pos the broken block position
   */
  public void onBlockBreak(BlockPos pos) {
    dirt.onBlockBreak(pos, currentTick);
  }

  /**
   * Feeds a world block change to the dirt detector.
   *
   * @description Feeds a world block change to the dirt detector.
   * @param client the active Minecraft client
   * @param pos the changed block position
   * @param oldState the previous block state
   * @param newState the replacement block state
   */
  public void onBlockChanged(
      Minecraft client, BlockPos pos, BlockState oldState, BlockState newState) {
    dirt.onBlockChanged(client, pos, oldState, newState, currentTick, settings());
  }

  /**
   * Feeds action-bar text to the ghost-block detector.
   *
   * @description Feeds action-bar text to the ghost-block detector.
   * @param component the observed text, possibly null
   */
  public void observeOverlay(Component component) {
    ghostBlock.observe(component, currentTick);
  }

  /**
   * Arms warp grace for commands that change worlds.
   *
   * @description Arms warp grace for commands that change worlds.
   * @param command the command text before dispatch
   */
  public void onCommand(String command) {
    if (!isWorldChangingCommand(command)) {
      return;
    }
    armWarpGrace();
  }

  /**
   * A macro is about to change worlds by other means than a command (a portal walk, a menu warp).
   *
   * @description Arms the same warp and rotation grace used for recognized commands.
   */
  public void expectWorldChange() {
    armWarpGrace();
  }

  /**
   * The server is about to turn the player and the macro knows why (an NPC booting us back to a
   * spawn point, a scripted yank). Only the rotation watch stands down - the world-change watch
   * stays armed.
   *
   * @description Extends the rotation detector's grace using the current configured duration.
   */
  public void expectServerRotation() {
    rotation.allowGrace(currentTick + settings().getRotationWarpGraceTicks());
  }

  /**
   * Starts wall-clock warp grace and expected rotation grace together.
   *
   * @description Starts wall-clock warp grace and expected rotation grace together.
   */
  private void armWarpGrace() {
    worldChange.expect();
    expectServerRotation();
  }

  /**
   * Classifies command roots that intentionally change server worlds.
   *
   * @description Classifies command roots that intentionally change server worlds.
   * @param command the raw command text, possibly null
   * @return true for a recognized warp command
   */
  static boolean isWorldChangingCommand(String command) {
    if (command == null) {
      return false;
    }
    String clean = command.trim().toLowerCase(Locale.ROOT);
    while (clean.startsWith("/")) {
      clean = clean.substring(1).trim();
    }
    int separator = clean.indexOf(' ');
    String root = separator < 0 ? clean : clean.substring(0, separator);
    return WORLD_COMMANDS.contains(root);
  }

  /**
   * Samples the configured random delay once for a detector condition.
   *
   * @description Samples the configured random delay once for a detector condition.
   * @param settings the current failsafe settings, possibly null
   * @return an extra delay in ticks
   */
  static int sampleAdditionalDelay(FailsafeSettings settings) {
    int maximum = settings == null ? 0 : settings.getAdditionalRandomDelayTicks();
    return maximum <= 0 ? 0 : ThreadLocalRandom.current().nextInt(maximum + 1);
  }

  /**
   * Dispatches a detector event only when that detector is enabled.
   *
   * @description Dispatches a detector event only when that detector is enabled.
   * @param type the detector that fired
   * @param details the human-readable cause
   */
  public void trigger(FailsafeType type, String details) {
    FailsafeSettings settings = settings();
    if (!settings.isEnabled() || !settings.isDetectorEnabled(type)) {
      return;
    }
    dispatcher.dispatch(type, settings.action(type), details);
  }

  /**
   * Advances the tick counter and runs start-tick detectors in original order.
   *
   * @description Advances the tick counter and runs start-tick detectors in original order.
   * @param client the active Minecraft client
   */
  private void onStartTick(Minecraft client) {
    currentTick++;
    FailsafeSettings settings = settings();
    inventorySlot.tick(client, currentTick, settings);
    rotation.tick(client, currentTick, settings);
  }

  /**
   * Runs end-tick detectors in their original callback order.
   *
   * @description Runs end-tick detectors in their original callback order.
   * @param client the active Minecraft client
   */
  private void onEndTick(Minecraft client) {
    FailsafeSettings settings = settings();
    unexpectedScreen.tick(client, currentTick, settings);
    proximity.tick(client, currentTick, settings);
    ghostBlock.tick(currentTick, settings);
    dirt.tick(client, currentTick, settings);
  }

  /**
   * Applies world-change grace or dispatches an unexpected-change event.
   *
   * @description Applies world-change grace or dispatches an unexpected-change event.
   * @param newInstance whether the transition entered a new server instance
   */
  private void onWorldChange(boolean newInstance) {
    if (!newInstance) {
      return;
    }
    // The new island re-sends the held slot a beat after the world swaps in - not the user touching
    // it.
    inventorySlot.allowGrace(currentTick);
    if (worldChange.isExpected()) {
      // One /is or /hub lands as several instance edges (limbo, then "Sending to server ..."), so
      // re-arm
      // instead of consuming the window - otherwise the second edge looks unexpected and stops the
      // macro.
      armWarpGrace();
      return;
    }
    FailsafeSettings settings = settings();
    if (settings.isEnabled()
        && settings.isWorldChangeEnabled()
        && FailsafeActivity.foregroundActive()) {
      trigger(FailsafeType.WORLD_CHANGE, "server instance changed unexpectedly");
    }
    rotation.allowGrace(currentTick + settings.getRotationWarpGraceTicks());
  }

  /**
   * Reports the chosen failsafe action to chat and the debug log.
   *
   * @description Reports the chosen failsafe action to chat and the debug log.
   * @param type the detector that fired
   * @param action the selected response
   * @param details the human-readable cause
   */
  private static void notifyPlayer(FailsafeType type, FailsafeAction action, String details) {
    String prefix =
        switch (action) {
          case STOP -> "§bFailsafe§7: §cstopped everything §7- ";
          case HOP -> "§bFailsafe§7: §ehopping lobbies §7- ";
          case IGNORE -> "§bFailsafe§7: §espotted and ignored §7- ";

          default -> throw new MatchException(null, null);
        };
    RyznChat.send(prefix + type.label() + " (" + details + ").");
    DebugLog.trace(
        DebugLog.Category.MACRO,
        "Failsafe: %s tripped, %s - %s",
        type.name(),
        action.name(),
        details);
  }

  /**
   * Reads the current shared failsafe settings section.
   *
   * @description Reads the current shared failsafe settings section.
   * @return the live failsafe settings
   */
  private static FailsafeSettings settings() {
    return RyznSettings.get().failsafe();
  }

  /**
   * Clears detector timing on disconnect while preserving expected warp grace.
   *
   * @description Clears detector timing on disconnect while preserving expected warp grace.
   */
  private void reset() {
    currentTick = 0;
    // The world-change element keeps its grace window: the disconnect IS the expected warp.
    inventorySlot.reset();
    unexpectedScreen.reset();
    proximity.reset();
    ghostBlock.reset();
    dirt.reset();
    rotation.reset();
  }
}
