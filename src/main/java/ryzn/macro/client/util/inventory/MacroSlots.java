package ryzn.macro.client.util.inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import ryzn.macro.client.debug.DebugLog;
import ryzn.macro.client.util.gui.MacroScreens;
import ryzn.macro.client.util.gui.MenuClickDebounce;
import ryzn.macro.client.util.gui.ScreenOwner;
import ryzn.macro.client.util.text.MacroText;

/** Shared container-slot interaction for Skyblock macros. */
public final class MacroSlots {

  private MacroSlots() {}

  private static final int INVENTORY_SIZE = 36;
  private static final int HOTBAR_SIZE = 9;

  /**
   * The 9th hotbar slot (index 8) is OFF LIMITS to every macro in the mod - never selected, never
   * swapped into, never clicked. On SkyBlock it is where the SkyBlock Menu lives, and a macro that
   * parks a pulled item there is displacing it. Found 2026-08-27: the travel-scroll step's "park it
   * in a spare hotbar slot" scanned the hotbar from the top down and so landed on 8 first, every
   * time the hotbar was full.
   *
   * <p>Enforced in two layers, deliberately. The FINDERS below skip it so a caller gets an honest
   * "not found" and takes its own not-found path; {@link #swapInventoryToHotbar} and {@code
   * MacroInput.selectHotbarSlot} then refuse it outright as the net under anything that computes a
   * slot itself. A silent refusal alone would leave a macro acting with the wrong item in hand.
   */
  public static final int RESERVED_HOTBAR_SLOT = 8;

  /** Every hotbar slot a macro is allowed to touch. See {@link #RESERVED_HOTBAR_SLOT}. */
  public static boolean isUsableHotbarSlot(int slot) {
    return slot >= 0 && slot < HOTBAR_SIZE && slot != RESERVED_HOTBAR_SLOT;
  }

  private static final int CRAFTING_GRID_WIDTH = 3;
  private static final int CRAFTING_GRID_SIZE = CRAFTING_GRID_WIDTH * CRAFTING_GRID_WIDTH;

  /**
   * One shared guard: every macro click in the mod comes through here, so this catches all of them.
   */
  private static final MenuClickDebounce debounce = new MenuClickDebounce();

  public static void clickSlot(
      Minecraft mc,
      AbstractContainerMenu menu,
      int slot,
      int button,
      ContainerInput clickType,
      LocalPlayer player) {
    click(mc, menu, slot, button, clickType, player, true);
  }

  /**
   * {@link #clickSlot} without the repeat-click guard, for callers that deliberately re-click a
   * slot whose contents never change between clicks (harp pads, a fixed trade offer).
   */
  public static void clickSlotUnguarded(
      Minecraft mc,
      AbstractContainerMenu menu,
      int slot,
      int button,
      ContainerInput clickType,
      LocalPlayer player) {
    click(mc, menu, slot, button, clickType, player, false);
  }

  private static void click(
      Minecraft mc,
      AbstractContainerMenu menu,
      int slot,
      int button,
      ContainerInput clickType,
      LocalPlayer player,
      boolean guarded) {
    if (mc.gameMode == null
        || !(mc.gui.screen() instanceof AbstractContainerScreen<?> screen)
        || screen.getMenu() != menu
        || player.containerMenu != menu) {
      return;
    }
    // Re-clicking a slot before the last click on it lands just undoes it; see MenuClickDebounce.
    // THROW
    // is the exception: dropping is not its own inverse, and Hypixel asks for a SECOND tap to
    // confirm -
    // which the guard swallowed, because a refused drop leaves the slot and cursor exactly as they
    // were.
    final boolean tracing = DebugLog.isEnabled(DebugLog.Category.MACRO);
    if (guarded
        && clickType != ContainerInput.THROW
        && !debounce.allow(menu.containerId, slot, clickState(menu, slot), player.tickCount)) {
      if (tracing) {
        DebugLog.action(
            DebugLog.Category.MACRO,
            "gui: SKIPPED a repeat %s on slot %d %s in \"%s\" - the previous click has not landed "
                + "yet",
            clickType,
            slot,
            describeSlot(menu, slot),
            MacroScreens.cleanTitle(mc));
      }
      return;
    }
    if (tracing) {
      DebugLog.action(
          DebugLog.Category.MACRO,
          "gui: %s%s slot %d %s in \"%s\"%s",
          clickType,
          button == 0 ? "" : " button " + button,
          slot,
          describeSlot(menu, slot),
          MacroScreens.cleanTitle(mc),
          describeCursor(menu));
    }
    // A slot click is how a macro walks into a sub-menu, so it claims whatever opens next.
    ScreenOwner.macroActed();
    mc.gameMode.handleContainerInput(menu.containerId, slot, button, clickType, player);
  }

  /**
   * The clicked slot in the words a player would use: the item's display name and how many of it.
   */
  private static String describeSlot(AbstractContainerMenu menu, int slot) {
    if (slot < 0 || slot >= menu.slots.size()) {
      return "(no slot)";
    }
    return describeStack(menu.slots.get(slot).getItem());
  }

  /**
   * What the click is carrying, when it is carrying anything - half of why a menu click does what
   * it does.
   */
  private static String describeCursor(AbstractContainerMenu menu) {
    final ItemStack carried = menu.getCarried();
    return carried.isEmpty() ? "" : ", holding " + describeStack(carried);
  }

  private static String describeStack(ItemStack stack) {
    if (stack.isEmpty()) {
      return "(empty)";
    }
    final String name = MacroText.stripFormatting(stack.getHoverName().getString());
    return stack.getCount() > 1 ? "\"" + name + "\" x" + stack.getCount() : "\"" + name + "\"";
  }

  /** Everything a repeat click would undo: the cursor, and the slot being clicked. */
  private static String clickState(AbstractContainerMenu menu, int slot) {
    final StringBuilder print = new StringBuilder(48);
    appendStack(print, menu.getCarried());
    appendStack(
        print,
        slot >= 0 && slot < menu.slots.size() ? menu.slots.get(slot).getItem() : ItemStack.EMPTY);
    return print.toString();
  }

  private static void appendStack(StringBuilder print, ItemStack stack) {
    if (stack.isEmpty()) {
      print.append("-;");
      return;
    }
    print
        .append(stack.getItem())
        .append('x')
        .append(stack.getCount())
        .append('|')
        .append(stack.getHoverName().getString())
        .append(';');
  }

  public static boolean isPlayerInventorySlot(
      AbstractContainerMenu menu, LocalPlayer player, Slot slot) {
    final int playerStart = Math.max(0, menu.slots.size() - INVENTORY_SIZE);
    return slot.container == player.getInventory() || slot.index >= playerStart;
  }

  public static boolean isCommonMenuControlSlot(Slot slot) {
    if (slot == null) {
      return false;
    }
    final ItemStack stack = slot.getItem();
    if (stack.isEmpty()) {
      return false;
    }
    if (MacroItems.isMenuDecorationStack(stack)) {
      return true;
    }
    final String text = MacroText.normalizeSearchText(MacroItems.stackSearchText(stack));
    return text.contains("go back")
        || text.contains("skyblock menu")
        || text.contains("close")
        || text.contains("previous")
        || text.contains("next page")
        || text.contains("previous page")
        || text.contains("click to go")
        || text.contains("click to close")
        || text.contains("back to")
        || text.contains("navigation");
  }

  public static Slot findPlayerInventoryMenuSlot(
      AbstractContainerMenu menu, LocalPlayer player, int inventoryIndex) {
    for (Slot slot : menu.slots) {
      if (slot != null
          && slot.container == player.getInventory()
          && slot.getContainerSlot() == inventoryIndex) {
        return slot;
      }
    }
    return null;
  }

  public static int findHotbarSlot(LocalPlayer player, Predicate<ItemStack> match) {
    return findInventorySlot(player, 0, RESERVED_HOTBAR_SLOT, match);
  }

  public static int findMainInventorySlot(LocalPlayer player, Predicate<ItemStack> match) {
    return findInventorySlot(player, HOTBAR_SIZE, INVENTORY_SIZE, match);
  }

  public static int findAnyInventorySlot(LocalPlayer player, Predicate<ItemStack> match) {
    return findInventorySlot(player, 0, INVENTORY_SIZE, match);
  }

  public static int findFirstEmptyHotbarSlot(LocalPlayer player) {
    return findHotbarSlot(player, ItemStack::isEmpty);
  }

  public static boolean swapInventoryToHotbar(
      Minecraft mc, LocalPlayer player, int inventoryIndex, int hotbarIndex) {
    return InventoryHotbarTransfer.start(mc, player, inventoryIndex, hotbarIndex);
  }

  public static boolean isCursorEmpty(AbstractContainerMenu menu) {
    return menu == null || menu.getCarried().isEmpty();
  }

  public static Slot findCursorReturnSlot(
      AbstractContainerMenu menu, LocalPlayer player, ItemStack cursor, int preferredSlotIndex) {
    Slot empty = null;
    for (Slot slot : menu.slots) {
      if (slot == null || !isPlayerInventorySlot(menu, player, slot)) {
        continue;
      }
      if (!slot.mayPlace(cursor)) {
        continue;
      }
      final ItemStack stack = slot.getItem();
      if (slot.index == preferredSlotIndex && (stack.isEmpty() || stack.is(cursor.getItem()))) {
        return slot;
      }
      if (stack.is(cursor.getItem()) && stack.getCount() < stack.getMaxStackSize()) {
        return slot;
      }
      if (stack.isEmpty() && empty == null) {
        empty = slot;
      }
    }
    return empty;
  }

  public static Slot findSlotAt(List<Slot> slots, int x, int y) {
    for (Slot slot : slots) {
      if (Math.abs(slot.x - x) <= 2 && Math.abs(slot.y - y) <= 2) {
        return slot;
      }
    }
    return null;
  }

  public static Slot findSlotByIndex(List<Slot> slots, int slotIndex) {
    for (Slot slot : slots) {
      if (slot != null && slot.index == slotIndex) {
        return slot;
      }
    }
    return null;
  }

  public static Slot findCraftOutputCandidateByLayout(
      AbstractContainerMenu menu,
      LocalPlayer player,
      List<Slot> grid,
      Predicate<Slot> isControlSlot,
      int slotPitch) {
    final List<Slot> nonPlayer = new ArrayList<>(menu.slots.size());
    for (Slot slot : menu.slots) {
      if (slot == null || isPlayerInventorySlot(menu, player, slot)) {
        continue;
      }
      if (isControlSlot.test(slot)) {
        continue;
      }
      nonPlayer.add(slot);
    }
    return findCraftOutputCandidateByLayout(nonPlayer, grid, isControlSlot, slotPitch);
  }

  public static Slot findCraftOutputCandidateByLayout(
      List<Slot> nonPlayer, List<Slot> grid, Predicate<Slot> isControlSlot, int slotPitch) {
    if (grid.isEmpty()) {
      return null;
    }
    int maxX = Integer.MIN_VALUE;
    int minY = Integer.MAX_VALUE;
    for (Slot slot : grid) {
      if (slot.x > maxX) {
        maxX = slot.x;
      }
      if (slot.y < minY) {
        minY = slot.y;
      }
    }

    final int midY = minY + slotPitch;
    Slot best = null;
    int bestDistance = Integer.MAX_VALUE;
    for (Slot slot : nonPlayer) {
      if (isGridSlot(grid, slot)) {
        continue;
      }
      if (isControlSlot.test(slot)) {
        continue;
      }
      if (slot.x <= maxX + slotPitch) {
        continue;
      }
      if (Math.abs(slot.y - midY) > slotPitch) {
        continue;
      }
      final int distance = Math.abs(slot.y - midY) + Math.abs(slot.x - maxX);
      if (distance < bestDistance) {
        bestDistance = distance;
        best = slot;
      }
    }
    return best;
  }

  private static Slot findCraftOutputCandidateByLayout(
      List<Slot> nonPlayer, Slot[] grid, Predicate<Slot> isControlSlot, int slotPitch) {
    int maxX = Integer.MIN_VALUE;
    int minY = Integer.MAX_VALUE;
    for (Slot slot : grid) {
      if (slot.x > maxX) {
        maxX = slot.x;
      }
      if (slot.y < minY) {
        minY = slot.y;
      }
    }

    final int midY = minY + slotPitch;
    Slot best = null;
    int bestDistance = Integer.MAX_VALUE;
    for (Slot slot : nonPlayer) {
      if (isGridSlot(grid, slot)) {
        continue;
      }
      if (isControlSlot.test(slot)) {
        continue;
      }
      if (slot.x <= maxX + slotPitch) {
        continue;
      }
      if (Math.abs(slot.y - midY) > slotPitch) {
        continue;
      }
      final int distance = Math.abs(slot.y - midY) + Math.abs(slot.x - maxX);
      if (distance < bestDistance) {
        bestDistance = distance;
        best = slot;
      }
    }
    return best;
  }

  public static List<Slot> findCraftingGridByLayout(
      AbstractContainerMenu menu,
      LocalPlayer player,
      ItemStack probe,
      Predicate<ItemStack> isRecipeItem,
      Predicate<Slot> isControlSlot,
      int slotPitch) {
    final List<Slot> nonPlayer = new ArrayList<>(menu.slots.size());
    for (Slot slot : menu.slots) {
      if (slot == null || isPlayerInventorySlot(menu, player, slot)) {
        continue;
      }
      if (isControlSlot.test(slot)) {
        continue;
      }
      if (!slot.isActive()) {
        continue;
      }
      nonPlayer.add(slot);
    }

    final Slot[] candidate = new Slot[CRAFTING_GRID_SIZE];
    List<Slot> best = null;
    int bestScore = Integer.MIN_VALUE;
    for (Slot topLeft : nonPlayer) {
      int candidateSize = 0;
      int score = 0;
      boolean valid = true;
      for (int row = 0; row < CRAFTING_GRID_WIDTH; row++) {
        for (int col = 0; col < CRAFTING_GRID_WIDTH; col++) {
          final Slot found =
              findSlotAt(nonPlayer, topLeft.x + col * slotPitch, topLeft.y + row * slotPitch);
          if (found == null) {
            valid = false;
            break;
          }
          final ItemStack stack = found.getItem();
          if (stack.isEmpty()) {
            score += 4;
          } else if (isRecipeItem.test(stack)) {
            score += 2;
          } else {
            score -= 12;
          }
          if (found.mayPlace(probe)) {
            score += 1;
          }
          candidate[candidateSize++] = found;
        }
        if (!valid) {
          break;
        }
      }
      if (valid && candidateSize == CRAFTING_GRID_SIZE) {
        score +=
            findCraftOutputCandidateByLayout(nonPlayer, candidate, isControlSlot, slotPitch) != null
                ? 20
                : -20;
      }
      if (valid && candidateSize == CRAFTING_GRID_SIZE && score > bestScore) {
        bestScore = score;
        if (best == null) {
          best = new ArrayList<>(CRAFTING_GRID_SIZE);
        } else {
          best.clear();
        }
        for (Slot slot : candidate) {
          best.add(slot);
        }
      }
    }
    return best == null ? List.of() : best;
  }

  public static Slot findCraftOutputSlot(
      AbstractContainerMenu menu,
      LocalPlayer player,
      List<Slot> grid,
      Predicate<Slot> isControlSlot,
      Predicate<ItemStack> isOutputItem) {
    for (Slot slot : menu.slots) {
      if (slot == null || isGridSlot(grid, slot) || isPlayerInventorySlot(menu, player, slot)) {
        continue;
      }
      if (isControlSlot.test(slot)) {
        continue;
      }
      if (isOutputItem.test(slot.getItem())) {
        return slot;
      }
    }
    return null;
  }

  private static boolean isGridSlot(List<Slot> grid, Slot candidate) {
    for (Slot slot : grid) {
      if (slot.index == candidate.index) {
        return true;
      }
    }
    return false;
  }

  private static boolean isGridSlot(Slot[] grid, Slot candidate) {
    for (Slot slot : grid) {
      if (slot.index == candidate.index) {
        return true;
      }
    }
    return false;
  }

  private static int findInventorySlot(
      LocalPlayer player, int startInclusive, int endExclusive, Predicate<ItemStack> match) {
    if (player == null) {
      return -1;
    }
    for (int i = startInclusive; i < endExclusive; i++) {
      if (match.test(player.getInventory().getItem(i))) {
        return i;
      }
    }
    return -1;
  }
}
