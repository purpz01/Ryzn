package ryzn.macro.client.util.inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import ryzn.macro.client.input.ClickConfirm;
import ryzn.macro.client.input.GuiActionTiming;

/**
 * Fills a 3x3 Craft Item grid to a given recipe shape and claims the result. Shared because every
 * macro that hand-rolled this loop rediscovered the same three failures, and one of them at a time.
 *
 * <p>The shape is a {@link Placement} list - which grid square gets which material - so this covers
 * both a full nine-of-one block recipe ({@link #uniform}) and a sparse one like the Golden Shovel's
 * gold-over-two- sticks column. Only the shape differs between callers; the mechanics below are the
 * whole point.
 *
 * <p>The loop is driven by <b>what the menu actually shows</b> - the cursor and the grid - never by
 * a step counter. A counter desyncs the moment a click is dropped or lands late, and the recovery
 * it forces is what produces the visible stutter: re-clicking a slot while the last click is still
 * in flight UNDOES IT, so the bot picks up, drops, picks up, drops. Four guards prevent that:
 *
 * <ul>
 *   <li>{@code pickupConfirm} - never click the source again while a pickup is unresolved.
 *   <li>{@code returnConfirm} - never click the return slot again while the put-back is unresolved;
 *       a second click takes the stack straight back onto the cursor. A mixed recipe puts the
 *       leftover stack back on every material switch, so this fires on every Golden Shovel / Axe
 *       craft.
 *   <li>{@code gridConfirm} + {@code pendingSlot} - never re-derive "first empty slot" until the
 *       last placement has appeared, or a SECOND item goes into the same square.
 *   <li>{@link #stalled} - when neither cursor nor grid changes for {@value #NO_PROGRESS_TICKS}
 *       ticks the server is rejecting the clicks; report {@link Outcome#BLOCKED} instead of
 *       hammering forever.
 * </ul>
 *
 * <p>Claiming is confirmed twice over: the grid emptying says the server consumed the recipe, the
 * bag count rising says the output was really claimed. Either alone will close a menu on an
 * unclaimed craft.
 *
 * <p>The caller owns opening the menu, closing it, and counting how many crafts it wants; this owns
 * the fill and the take. Each caller keeps its own instance - no shared static state.
 */
public final class CraftGridFill {

  /** What the caller should do next. Anything but {@link #WORKING} ends the current craft. */
  public enum Outcome {
    /** Still filling, taking, or waiting out a click window. Call again next tick. */
    WORKING,
    /** One output was crafted AND claimed into the inventory. */
    CRAFTED,
    /** Not enough material left in the bag to fill the grid, and the grid is empty. */
    OUT_OF_MATERIAL,
    /** The grid holds something else, or the menu stopped responding to clicks. */
    BLOCKED
  }

  /** One square of the recipe: the 0-8 position in the 3x3, and what belongs in it. */
  public record Placement(int gridIndex, Predicate<ItemStack> material) {}

  /** MC container-slot pitch in GUI pixels; used to resolve the grid by layout. */
  private static final int SLOT_PIXELS = 18;

  private static final int GRID_SIZE = 9;

  /** Centre last: a partial ring never forms an accidental recipe on the way. */
  private static final int[] fillOrder = {0, 1, 2, 3, 5, 6, 7, 8, 4};

  private static final int CLICK_CONFIRM_TICKS = 30;
  private static final int NO_PROGRESS_TICKS = 60;

  /** Every square filled with the same material, centre last. */
  public static List<Placement> uniform(Predicate<ItemStack> material) {
    final List<Placement> plan = new ArrayList<>(fillOrder.length);
    for (int index : fillOrder) {
      plan.add(new Placement(index, material));
    }
    return plan;
  }

  private final ClickConfirm pickupConfirm = new ClickConfirm();
  private final ClickConfirm gridConfirm = new ClickConfirm();
  private final ClickConfirm takeConfirm = new ClickConfirm();
  private final ClickConfirm returnConfirm = new ClickConfirm();

  private boolean wholeStacks;
  private int pendingSlot = -1;
  private int outputBaseline = -1;
  private int menuDelay;
  private int idleTicks;
  private String fingerprint = "";
  private String status = "Crafting";

  /** Reset for a fresh craft, one item per square. Call once before the first {@link #tick}. */
  public void begin() {
    begin(false);
  }

  /**
   * Reset for a fresh craft.
   *
   * @param wholeStacks put the entire cursor stack in each square instead of a single item.
   *     Hypixel's enchanted recipes are "N of one material", so they are filled by the stack.
   */
  public void begin(boolean wholeStacks) {
    this.wholeStacks = wholeStacks;
    pickupConfirm.clear();
    gridConfirm.clear();
    takeConfirm.clear();
    returnConfirm.clear();
    pendingSlot = -1;
    outputBaseline = -1;
    idleTicks = 0;
    fingerprint = "";
    menuDelay = GuiActionTiming.firstMenuClickDelay();
    status = "Crafting";
  }

  /** Human-readable progress for the caller's status line. */
  public String status() {
    return status;
  }

  /**
   * Advance one tick against an already-open Craft Item menu.
   *
   * @param plan which grid square takes which material; see {@link #uniform}
   * @param isOutput matches the crafted result
   */
  public Outcome tick(
      Minecraft mc,
      LocalPlayer player,
      AbstractContainerMenu menu,
      List<Placement> plan,
      Predicate<ItemStack> isOutput) {
    if (mc == null || player == null || menu == null || mc.gameMode == null) {
      return Outcome.WORKING;
    }
    if (plan == null || plan.isEmpty()) {
      return Outcome.BLOCKED;
    }
    if (menuDelay > 0) {
      menuDelay--;
      return Outcome.WORKING;
    }

    final List<Slot> grid = grid(menu, player, plan);
    if (grid.size() != GRID_SIZE) {
      status = "Searching crafting grid";
      return Outcome.WORKING; // layout not resolved yet; look again next tick
    }
    if (stalled(menu, grid)) {
      return Outcome.BLOCKED;
    }

    if (outputBaseline < 0) {
      outputBaseline = countPlayer(player, isOutput);
    }

    final Slot output = outputSlot(menu, player, grid, isOutput);
    if (output != null || takeConfirm.isPending()) {
      return take(mc, player, menu, grid, output, isOutput);
    }
    return fill(mc, player, menu, grid, plan);
  }

  // ------------------------------------------------------------------ fill

  private Outcome fill(
      Minecraft mc,
      LocalPlayer player,
      AbstractContainerMenu menu,
      List<Slot> grid,
      List<Placement> plan) {
    if (placementPending(grid)) {
      status = "Placing material";
      return Outcome.WORKING; // a placement is out; do not click over the top of it
    }

    final Placement next = nextUnfilled(grid, plan);
    if (next == null) {
      // Shape complete. The server forms the output a tick or two later, so waiting here is normal;
      // a recipe that never forms is caught by stalled() rather than by giving up on this tick.
      status = "Waiting for output";
      if (!menu.getCarried().isEmpty()) {
        returnCursor(mc, player, menu);
      }
      return Outcome.WORKING;
    }

    final ItemStack cursor = menu.getCarried();
    if (next.material().test(cursor)) {
      pickupConfirm.clear(); // the cursor is loaded, so the pickup plainly landed
      return placeOne(mc, player, menu, grid, next);
    }
    if (!cursor.isEmpty()) { // holding the wrong material; put it back first
      pickupConfirm.clear();
      status = "Returning held stack";
      return returnCursor(mc, player, menu) ? Outcome.WORKING : Outcome.BLOCKED;
    }
    return pickUp(mc, player, menu, grid, next.material());
  }

  /**
   * The first square in plan order whose slot is still empty, or null when the shape is complete.
   */
  private static Placement nextUnfilled(List<Slot> grid, List<Placement> plan) {
    for (Placement placement : plan) {
      final int index = placement.gridIndex();
      if (index < 0 || index >= grid.size()) {
        continue;
      }
      if (grid.get(index).getItem().isEmpty()) {
        return placement;
      }
    }
    return null;
  }

  /** True while the last placement has not shown up in the slot it was aimed at. */
  private boolean placementPending(List<Slot> grid) {
    if (pendingSlot < 0) {
      return false;
    }
    final Slot pending = MacroSlots.findSlotByIndex(grid, pendingSlot);
    if (pending != null && pending.getItem().isEmpty() && gridConfirm.awaiting(0)) {
      return true;
    }
    pendingSlot = -1; // landed, or the window ran out - let it retry
    gridConfirm.clear();
    return false;
  }

  /**
   * Put material from the cursor into the square the plan asks for: right-click one, left-click
   * all.
   */
  private Outcome placeOne(
      Minecraft mc,
      LocalPlayer player,
      AbstractContainerMenu menu,
      List<Slot> grid,
      Placement placement) {
    final Slot slot = grid.get(placement.gridIndex());
    click(mc, player, menu, slot.index, wholeStacks ? 0 : 1, ContainerInput.PICKUP);
    pendingSlot = slot.index;
    gridConfirm.sent(0, CLICK_CONFIRM_TICKS);
    status = "Filling crafting grid";
    return Outcome.WORKING;
  }

  /**
   * Take the whole source stack onto the cursor. Held shut by a confirm: clicking the source twice
   * puts the stack straight back down, which is the stutter this class exists to prevent.
   */
  private Outcome pickUp(
      Minecraft mc,
      LocalPlayer player,
      AbstractContainerMenu menu,
      List<Slot> grid,
      Predicate<ItemStack> isInput) {
    if (pickupConfirm.awaiting(0)) {
      status = "Waiting for cursor stack";
      return Outcome.WORKING;
    }
    final Slot source = playerSlot(menu, player, isInput);
    if (source == null) {
      // Out of material only ends the craft when the grid is EMPTY. Placing the last item empties
      // the
      // bag a tick before the server forms the output; leaving on that tick abandons a finished
      // craft.
      if (!gridEmpty(grid)) {
        status = "Waiting for output";
        return Outcome.WORKING;
      }
      return Outcome.OUT_OF_MATERIAL;
    }
    click(mc, player, menu, source.index, 0, ContainerInput.PICKUP);
    pickupConfirm.sent(0, CLICK_CONFIRM_TICKS);
    status = "Picking up material";
    return Outcome.WORKING;
  }

  // ------------------------------------------------------------------ take

  private Outcome take(
      Minecraft mc,
      LocalPlayer player,
      AbstractContainerMenu menu,
      List<Slot> grid,
      Slot output,
      Predicate<ItemStack> isOutput) {
    final int held = countPlayer(player, isOutput);
    // Both halves matter: the grid emptying says the server consumed the recipe, the bag count
    // rising
    // says the result was actually claimed. Either alone closes the menu on an unclaimed craft.
    if (gridEmpty(grid) && held > outputBaseline) {
      status = "Crafted";
      return Outcome.CRAFTED;
    }
    if (takeConfirm.awaiting(held)) {
      status = "Taking output";
      return Outcome.WORKING; // the shift-click is out; a second one is wasted
    }
    if (output == null) {
      status = "Waiting for output";
      return Outcome.WORKING; // still forming; wait rather than click blind
    }
    click(mc, player, menu, output.index, 0, ContainerInput.QUICK_MOVE);
    takeConfirm.sent(held, CLICK_CONFIRM_TICKS);
    status = "Taking output";
    return Outcome.WORKING;
  }

  // --------------------------------------------------------------- helpers

  /**
   * Any material the recipe uses marks a grid square, so a mixed shape resolves like a uniform one.
   */
  private List<Slot> grid(AbstractContainerMenu menu, LocalPlayer player, List<Placement> plan) {
    final Predicate<ItemStack> anyMaterial =
        stack -> {
          for (Placement placement : plan) {
            if (placement.material().test(stack)) {
              return true;
            }
          }
          return false;
        };
    final Slot probe = playerSlot(menu, player, anyMaterial);
    return MacroSlots.findCraftingGridByLayout(
        menu,
        player,
        probe == null ? ItemStack.EMPTY : probe.getItem(),
        anyMaterial,
        MacroSlots::isCommonMenuControlSlot,
        SLOT_PIXELS);
  }

  private Slot outputSlot(
      AbstractContainerMenu menu,
      LocalPlayer player,
      List<Slot> grid,
      Predicate<ItemStack> isOutput) {
    return MacroSlots.findCraftOutputSlot(
        menu, player, grid, MacroSlots::isCommonMenuControlSlot, isOutput);
  }

  private static boolean gridEmpty(List<Slot> grid) {
    for (Slot slot : grid) {
      if (!slot.getItem().isEmpty()) {
        return false;
      }
    }
    return !grid.isEmpty();
  }

  private static Slot playerSlot(
      AbstractContainerMenu menu, LocalPlayer player, Predicate<ItemStack> match) {
    for (Slot slot : menu.slots) {
      if (slot == null || !MacroSlots.isPlayerInventorySlot(menu, player, slot)) {
        continue;
      }
      if (match.test(slot.getItem())) {
        return slot;
      }
    }
    return null;
  }

  private static int countPlayer(LocalPlayer player, Predicate<ItemStack> match) {
    int total = 0;
    for (int index = 0; index < player.getInventory().getContainerSize(); index++) {
      final ItemStack stack = player.getInventory().getItem(index);
      if (match.test(stack)) {
        total += stack.getCount();
      }
    }
    return total;
  }

  private boolean returnCursor(Minecraft mc, LocalPlayer player, AbstractContainerMenu menu) {
    final int carried = menu.getCarried().getCount();
    if (returnConfirm.awaiting(carried)) {
      return true;
    } // put-back is out; clicking again re-takes it
    final Slot back = MacroSlots.findCursorReturnSlot(menu, player, menu.getCarried(), -1);
    if (back == null) {
      return false;
    }
    click(mc, player, menu, back.index, 0, ContainerInput.PICKUP);
    returnConfirm.sent(carried, CLICK_CONFIRM_TICKS);
    return true;
  }

  private void click(
      Minecraft mc,
      LocalPlayer player,
      AbstractContainerMenu menu,
      int slot,
      int button,
      ContainerInput type) {
    MacroSlots.clickSlot(mc, menu, slot, button, type, player);
    menuDelay = GuiActionTiming.randomDelay();
  }

  /**
   * True when neither the cursor nor the grid has changed for {@value #NO_PROGRESS_TICKS} ticks.
   */
  private boolean stalled(AbstractContainerMenu menu, List<Slot> grid) {
    final StringBuilder print =
        new StringBuilder().append(menu.getCarried().getCount()).append(':');
    for (Slot slot : grid) {
      print.append(slot.getItem().isEmpty() ? '.' : '#');
    }
    final String now = print.toString();
    if (!now.equals(fingerprint)) {
      fingerprint = now;
      idleTicks = 0;
      return false;
    }
    return ++idleTicks > NO_PROGRESS_TICKS;
  }
}
