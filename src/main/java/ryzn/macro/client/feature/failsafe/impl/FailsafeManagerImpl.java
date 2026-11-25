package ryzn.macro.client.feature.failsafe.impl;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;
import ryzn.macro.client.feature.failsafe.DirtFailsafe;
import ryzn.macro.client.feature.failsafe.FailsafeCoordinator;
import ryzn.macro.client.feature.failsafe.FailsafeType;
import ryzn.macro.client.feature.failsafe.GhostBlockFailsafe;
import ryzn.macro.client.feature.failsafe.InventorySlotFailsafe;
import ryzn.macro.client.feature.failsafe.ProximityFailsafe;
import ryzn.macro.client.feature.failsafe.RotationFailsafe;
import ryzn.macro.client.feature.failsafe.UnexpectedScreenFailsafe;
import ryzn.macro.client.feature.failsafe.WorldChangeFailsafe;
import ryzn.macro.client.feature.failsafe.api.FailsafeManager;

/** Owns the existing detector instances in their original declaration order. */
public final class FailsafeManagerImpl extends FailsafeManager {
  private FailsafeCoordinator coordinator;

  /**
   * Creates the process-lifetime registry before detector registration.
   *
   * @description Defers detector construction until the manager is initialized.
   */
  public FailsafeManagerImpl() {}

  /**
   * Creates the coordinator after detector registration so every exact-class lookup resolves.
   *
   * @description Lazily wires the one coordinator to this initialized manager.
   * @return the process-lifetime failsafe coordinator
   */
  private FailsafeCoordinator coordinator() {
    init();
    if (coordinator == null) {
      coordinator = new FailsafeCoordinator(this);
    }
    return coordinator;
  }

  /**
   * Registers the eight known failsafe types.
   *
   * @description Registers each detector once without changing the coordinator's tick order.
   */
  @Override
  protected void registerElements() {
    register(new InventorySlotFailsafe());
    register(new UnexpectedScreenFailsafe());
    register(new GhostBlockFailsafe());
    register(new DirtFailsafe());
    register(new RotationFailsafe());
    register(new WorldChangeFailsafe());
    register(new ProximityFailsafe());
  }

  /**
   * Installs the original Fabric and world-change adapters once.
   *
   * @description Installs the original Fabric and world-change adapters once.
   */
  @Override
  public void registerCallbacks() {
    coordinator().registerCallbacks();
  }

  /**
   * Stops automation and persists the stopped state through the shared panic path.
   *
   * @description Stops automation and persists the stopped state through the shared panic path.
   * @param reason the message explaining why automation stopped
   */
  @Override
  public void stopAllMacros(String reason) {
    coordinator().stopAllMacros(reason);
  }

  /**
   * Reports whether foreground automation currently needs failsafe monitoring.
   *
   * @description Reports whether foreground automation currently needs failsafe monitoring.
   * @return true while a supported macro or module is active
   */
  @Override
  public boolean automationActive() {
    return coordinator().automationActive();
  }

  /**
   * Records an intentional hotbar selection for inventory-slot monitoring.
   *
   * @description Records an intentional hotbar selection for inventory-slot monitoring.
   * @param slot the zero-based selected hotbar slot
   */
  @Override
  public void expectSelectedSlot(int slot) {
    coordinator().expectSelectedSlot(slot);
  }

  /**
   * Records a requested client turn for forced-rotation monitoring.
   *
   * @description Records a requested client turn for forced-rotation monitoring.
   * @param yaw the expected player yaw
   * @param pitch the expected player pitch
   */
  @Override
  public void expectRotation(float yaw, float pitch) {
    coordinator().expectRotation(yaw, pitch);
  }

  /**
   * Records a block break for throughput and dirt detectors.
   *
   * @description Records a block break for throughput and dirt detectors.
   * @param pos the broken block position
   */
  @Override
  public void onBlockBreak(BlockPos pos) {
    coordinator().onBlockBreak(pos);
  }

  /**
   * Feeds a world block change to the dirt detector.
   *
   * @description Feeds a world block change to the dirt detector.
   * @param client the active Minecraft client
   * @param pos the changed position
   * @param oldState the previous block state
   * @param newState the replacement block state
   */
  @Override
  public void onBlockChanged(
      Minecraft client, BlockPos pos, BlockState oldState, BlockState newState) {
    coordinator().onBlockChanged(client, pos, oldState, newState);
  }

  /**
   * Feeds server overlay text to the ghost-block detector.
   *
   * @description Feeds server overlay text to the ghost-block detector.
   * @param component the overlay component, possibly null
   */
  @Override
  public void observeOverlay(Component component) {
    coordinator().observeOverlay(component);
  }

  /**
   * Grants world-change grace for recognized warp commands.
   *
   * @description Grants world-change grace for recognized warp commands.
   * @param command the command text before dispatch
   */
  @Override
  public void onCommand(String command) {
    coordinator().onCommand(command);
  }

  /**
   * Grants grace for an intentional world transition outside a command.
   *
   * @description Grants grace for an intentional world transition outside a command.
   */
  @Override
  public void expectWorldChange() {
    coordinator().expectWorldChange();
  }

  /**
   * Grants the configured grace interval for an expected server turn.
   *
   * @description Grants the configured grace interval for an expected server turn.
   */
  @Override
  public void expectServerRotation() {
    coordinator().expectServerRotation();
  }

  /**
   * Dispatches an enabled detector event through the configured failsafe action.
   *
   * @description Dispatches an enabled detector event through the configured failsafe action.
   * @param type the detector that reported the event
   * @param details the human-readable cause
   */
  @Override
  public void trigger(FailsafeType type, String details) {
    coordinator().trigger(type, details);
  }
}
