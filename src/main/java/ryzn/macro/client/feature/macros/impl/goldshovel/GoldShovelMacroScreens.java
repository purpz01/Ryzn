package ryzn.macro.client.feature.macros.impl.goldshovel;

import static ryzn.macro.client.util.gui.MacroScreens.closeCurrentScreen;
import static ryzn.macro.client.util.gui.MacroScreens.closeCurrentScreenIfCursorEmpty;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import net.minecraft.client.Minecraft;

/** Owns screen cleanup rules for the Gold Shovel workflow. */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class GoldShovelMacroScreens {
  private final GoldShovelMenuSlots menuSlots;

  boolean closeCurrent(Minecraft client) {
    if (client.gui.screen() == null) {
      return true;
    }
    if (menuSlots.isCraftItemScreen(client) && !closeCurrentScreenIfCursorEmpty(client)) {
      return false;
    }
    closeCurrentScreen(client);
    return true;
  }

  boolean shouldClose(
      Minecraft client,
      GoldShovelSupplySession supply,
      GoldShovelCraftSession craft,
      GoldShovelEnchantSession enchant) {
    if (supply.wasLumberGuiOpened() && menuSlots.isLumberMerchantScreen(client)) {
      return true;
    }
    if (supply.wasLibrarianGuiOpened() && menuSlots.isLibrarianScreen(client)) {
      return true;
    }
    if (supply.wereTradingOptionsOpened() && menuSlots.isTradingOptionsScreen(client)) {
      return true;
    }
    if (craft.wasGuiOpened() && menuSlots.isCraftItemScreen(client)) {
      return true;
    }
    return enchant.wasGuiOpened() && menuSlots.isEnchantItemScreen(client);
  }
}
