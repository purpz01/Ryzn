package ryzn.macro.client.feature.macros.impl.bea;

import static ryzn.macro.client.util.gui.MacroScreens.cleanTitle;

import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;

final class BeaMacroScreens {

  boolean isCraftItemOpen(Minecraft minecraft) {
    return minecraft.gui.screen() instanceof AbstractContainerScreen<?>
        && isCraftItemTitle(cleanTitle(minecraft));
  }

  boolean isBeaOpen(Minecraft minecraft) {
    return minecraft.gui.screen() instanceof AbstractContainerScreen<?>
        && isBeaTitle(cleanTitle(minecraft));
  }

  boolean isPetsOpen(Minecraft minecraft) {
    return minecraft.gui.screen() instanceof AbstractContainerScreen<?>
        && isPetsTitle(cleanTitle(minecraft));
  }

  boolean shouldClose(Minecraft minecraft, BeaCraftSession craft, BeaPetSession pet) {
    return shouldClose(
        craft.wasGuiOpened(), isCraftItemOpen(minecraft),
        pet.wasBeaMenuOpened(), isBeaOpen(minecraft),
        pet.wasPetsMenuOpened(), isPetsOpen(minecraft),
        pet.isHotbarMoveRequested(), minecraft.gui.screen() instanceof InventoryScreen);
  }

  boolean shouldClose(
      boolean craftOwned,
      boolean craftOpen,
      boolean beaOwned,
      boolean beaOpen,
      boolean petsOwned,
      boolean petsOpen,
      boolean inventoryOwned,
      boolean inventoryOpen) {
    return craftOwned && craftOpen
        || beaOwned && beaOpen
        || petsOwned && petsOpen
        || inventoryOwned && inventoryOpen;
  }

  boolean isCraftItemTitle(String title) {
    return title.equalsIgnoreCase("Craft Item");
  }

  boolean isBeaTitle(String title) {
    return title.toLowerCase(Locale.ROOT).contains("bea");
  }

  boolean isPetsTitle(String title) {
    final String normalized = title.toLowerCase(Locale.ROOT);
    return normalized.contains("pets") || normalized.contains("pet menu");
  }
}
