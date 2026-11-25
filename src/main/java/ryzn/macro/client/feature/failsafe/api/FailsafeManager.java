package ryzn.macro.client.feature.failsafe.api;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;
import ryzn.macro.client.feature.api.Manager;
import ryzn.macro.client.feature.failsafe.FailsafeType;

/** Ordered owner of registered failsafe detectors. */
public abstract class FailsafeManager extends Manager<Failsafe> {
  /**
   * Installs the original Fabric and world-change adapters once.
   *
   * @description Installs the original Fabric and world-change adapters once.
   */
  public abstract void registerCallbacks();

  /**
   * Stops automation and persists the stopped state through the shared panic path.
   *
   * @description Stops automation and persists the stopped state through the shared panic path.
   * @param reason the message explaining why automation stopped
   */
  public abstract void stopAllMacros(String reason);

  /**
   * Reports whether foreground automation currently needs failsafe monitoring.
   *
   * @description Reports whether foreground automation currently needs failsafe monitoring.
   * @return true while a supported macro or module is active
   */
  public abstract boolean automationActive();

  /**
   * Records an intentional hotbar selection for inventory-slot monitoring.
   *
   * @description Records an intentional hotbar selection for inventory-slot monitoring.
   * @param slot the zero-based selected hotbar slot
   */
  public abstract void expectSelectedSlot(int slot);

  /**
   * Records a requested client turn for forced-rotation monitoring.
   *
   * @description Records a requested client turn for forced-rotation monitoring.
   * @param yaw the expected player yaw
   * @param pitch the expected player pitch
   */
  public abstract void expectRotation(float yaw, float pitch);

  /**
   * Records a block break for throughput and dirt detectors.
   *
   * @description Records a block break for throughput and dirt detectors.
   * @param pos the broken block position
   */
  public abstract void onBlockBreak(BlockPos pos);

  /**
   * Feeds a world block change to the dirt detector.
   *
   * @description Feeds a world block change to the dirt detector.
   * @param client the active Minecraft client
   * @param pos the changed position
   * @param oldState the previous block state
   * @param newState the replacement block state
   */
  public abstract void onBlockChanged(
      Minecraft client, BlockPos pos, BlockState oldState, BlockState newState);

  /**
   * Feeds server overlay text to the ghost-block detector.
   *
   * @description Feeds server overlay text to the ghost-block detector.
   * @param component the overlay component, possibly null
   */
  public abstract void observeOverlay(Component component);

  /**
   * Grants world-change grace for recognized warp commands.
   *
   * @description Grants world-change grace for recognized warp commands.
   * @param command the command text before dispatch
   */
  public abstract void onCommand(String command);

  /**
   * Grants grace for an intentional world transition outside a command.
   *
   * @description Grants grace for an intentional world transition outside a command.
   */
  public abstract void expectWorldChange();

  /**
   * Grants the configured grace interval for an expected server turn.
   *
   * @description Grants the configured grace interval for an expected server turn.
   */
  public abstract void expectServerRotation();

  /**
   * Dispatches an enabled detector event through the configured failsafe action.
   *
   * @description Dispatches an enabled detector event through the configured failsafe action.
   * @param type the detector that reported the event
   * @param details the human-readable cause
   */
  public abstract void trigger(FailsafeType type, String details);
}
