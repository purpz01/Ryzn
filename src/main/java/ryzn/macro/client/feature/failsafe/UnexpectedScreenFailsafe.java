package ryzn.macro.client.feature.failsafe;

import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import ryzn.macro.client.MacroInput;
import ryzn.macro.client.config.groups.FailsafeSettings;
import ryzn.macro.client.core.RyznBootstrap;
import ryzn.macro.client.feature.failsafe.api.Failsafe;
import ryzn.macro.client.util.gui.MacroScreens;
import ryzn.macro.client.util.gui.ScreenOwner;

public final class UnexpectedScreenFailsafe extends Failsafe {
  /**
   * @description Creates the process-lifetime detector owned by the failsafe manager.
   */
  public UnexpectedScreenFailsafe() {
    super("unexpected_screen", "Unexpected GUI", "Detects unexpected gui.");
  }

  /** Hypixel titles a right-clicked account's menu "<name>'s Profile" - narrow on purpose. */
  private static final String PROFILE_TITLE_SUFFIX = "'s profile";

  /**
   * ~10 s, jittered per occurrence: long enough to read as a human noticing, not an instant bot
   * reflex.
   */
  private static final int PROFILE_CLOSE_TICKS = 200;

  private final FailsafeDelay delay = new FailsafeDelay();
  private int profileTicks;
  private int profileCloseAt;

  void tick(Minecraft client, long tick, FailsafeSettings settings) {
    // A stranger's profile menu is a misclick, not a reason to abandon the grind: back out of it
    // and
    // keep going. Stopping is still the answer for every OTHER unexpected container.
    if (isPlayerProfile(client) && FailsafeActivity.foregroundActive()) {
      delay.reset();
      if (profileTicks == 0) {
        profileCloseAt = MacroInput.jitter(PROFILE_CLOSE_TICKS);
      }
      if (++profileTicks >= profileCloseAt) {
        MacroScreens.closeCurrentScreen(client);
        profileTicks = 0;
      }
      return;
    }
    profileTicks = 0;

    // Every macro is covered, not just the ones that expect no screen at all. A macro that drives
    // menus is exempted by having opened the menu, not by being on a list - see ScreenOwner. That
    // is
    // what makes this safe to apply while the harp or a shop is mid-flow.
    boolean unexpected =
        client != null
            && client.player != null
            && settings.isEnabled()
            && settings.isUnexpectedScreenEnabled()
            && FailsafeActivity.foregroundActive()
            && client.gui.screen() instanceof AbstractContainerScreen<?>
            && !ScreenOwner.isMacroOwned(client.gui.screen());
    // The delay is also what stops a menu that merely blinks - Hypixel opens and closes one within
    // a
    // tick often enough - from reading as the player stepping in.
    if (delay.update(
        unexpected,
        tick,
        settings.getUnexpectedScreenDelayTicks(),
        () -> FailsafeCoordinator.sampleAdditionalDelay(settings))) {
      String title =
          client.gui.screen() == null ? "unknown" : client.gui.screen().getTitle().getString();
      RyznBootstrap.failsafeManager()
          .trigger(
              FailsafeType.UNEXPECTED_SCREEN,
              ScreenOwner.isPlayerInventory(client)
                  ? "you opened your inventory"
                  : "opened " + title);
    }
  }

  private static boolean isPlayerProfile(Minecraft client) {
    return client != null
        && client.player != null
        && client.gui.screen() instanceof AbstractContainerScreen<?>
        && MacroScreens.cleanTitle(client).toLowerCase(Locale.ROOT).endsWith(PROFILE_TITLE_SUFFIX);
  }

  void reset() {
    delay.reset();
    profileTicks = 0;
  }
}
