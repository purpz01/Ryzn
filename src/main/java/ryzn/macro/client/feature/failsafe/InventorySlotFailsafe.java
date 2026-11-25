package ryzn.macro.client.feature.failsafe;

import net.minecraft.client.Minecraft;
import ryzn.macro.client.config.groups.FailsafeSettings;
import ryzn.macro.client.core.RyznBootstrap;
import ryzn.macro.client.feature.failsafe.api.Failsafe;

public final class InventorySlotFailsafe extends Failsafe {
  /**
   * @description Creates the process-lifetime detector owned by the failsafe manager.
   */
  public InventorySlotFailsafe() {
    super("inventory_slot", "Hotbar Changed", "Detects hotbar changed.");
  }

  private static final int EXPECTED_CHANGE_GRACE_TICKS = 5;

  /**
   * How long after a world change (or a missing player) the held slot is allowed to move on its
   * own. A Skyblock warp hands us a fresh player on slot 1 and only re-sends the real held slot a
   * beat later, so latching the first value seen blamed the macro's own axe for "changing" the
   * hotbar.
   */
  private static final int WORLD_CHANGE_SETTLE_TICKS = 60;

  private final FailsafeDelay delay = new FailsafeDelay();
  private int expectedSlot = -1;
  private long expectedAt;
  private long graceUntil;

  void expect(int slot, long tick) {
    if (slot < 0 || slot > 8) {
      return;
    }
    expectedSlot = slot;
    expectedAt = tick;
    delay.reset();
  }

  /** Re-sync instead of trip for a while: the server, not the user, is moving the slot. */
  void allowGrace(long fromTick) {
    graceUntil = Math.max(graceUntil, fromTick + WORLD_CHANGE_SETTLE_TICKS);
    delay.reset();
  }

  void tick(Minecraft client, long tick, FailsafeSettings settings) {
    if (client == null || client.player == null) {
      expectedSlot = -1;
      expectedAt = 0;
      delay.reset();
      allowGrace(tick);
      return;
    }

    int current = client.player.getInventory().getSelectedSlot();
    if (!settings.isEnabled()
        || !settings.isInventorySlotEnabled()
        || !FailsafeActivity.foregroundActive()) {
      expect(current, tick);
      return;
    }
    if (expectedSlot < 0 || tick < graceUntil) {
      expect(current, tick);
      return;
    }

    boolean mismatched =
        current != expectedSlot && tick - expectedAt >= EXPECTED_CHANGE_GRACE_TICKS;
    if (delay.update(
        mismatched,
        tick,
        settings.getInventorySlotDelayTicks(),
        () -> FailsafeCoordinator.sampleAdditionalDelay(settings))) {
      RyznBootstrap.failsafeManager()
          .trigger(
              FailsafeType.INVENTORY_SLOT,
              "expected slot " + (expectedSlot + 1) + ", got slot " + (current + 1));
    }
  }

  void reset() {
    expectedSlot = -1;
    expectedAt = 0;
    graceUntil = 0;
    delay.reset();
  }
}
