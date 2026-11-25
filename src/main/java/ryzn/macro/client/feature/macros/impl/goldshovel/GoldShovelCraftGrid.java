package ryzn.macro.client.feature.macros.impl.goldshovel;

import static ryzn.macro.client.util.inventory.MacroSlots.clickSlot;
import static ryzn.macro.client.util.inventory.MacroSlots.findCursorReturnSlot;

import java.util.List;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import ryzn.macro.client.input.GuiActionTiming;
import ryzn.macro.client.input.MacroActionTiming;
import ryzn.macro.client.util.inventory.CraftGridFill;

/**
 * Crafts the Golden Shovel. The recipe shape is this class's business; the click mechanics belong
 * to the shared {@link CraftGridFill}, which advances on what the menu shows rather than on a step
 * counter - the old counter here left CRAFT_SHOVEL believing it was done and the next state failed
 * "Golden Shovel missing" because nothing had actually been claimed.
 */
final class GoldShovelCraftGrid {

  enum Result {
    WORKING,
    COMPLETE,
    FAILED
  }

  record Outcome(Result result, String message) {

    static Outcome working() {
      return new Outcome(Result.WORKING, null);
    }

    static Outcome waiting(String status) {
      return new Outcome(Result.WORKING, status);
    }

    static Outcome complete() {
      return new Outcome(Result.COMPLETE, null);
    }

    static Outcome failed(String message) {
      return new Outcome(Result.FAILED, message);
    }
  }

  /** Which tool the open Craft Item menu is making right now. Both come out of the same session. */
  enum Craft {
    AXE,
    SHOVEL
  }

  /** Gold ingot over two sticks: grid squares 1, 4 and 7 of the 3x3. */
  private static final List<CraftGridFill.Placement> shovelRecipe =
      List.of(
          new CraftGridFill.Placement(1, stack -> stack.is(Items.GOLD_INGOT)),
          new CraftGridFill.Placement(4, stack -> stack.is(Items.STICK)),
          new CraftGridFill.Placement(7, stack -> stack.is(Items.STICK)));

  /** Three gold in the L, two sticks down the middle: squares 0, 1, 3 gold and 4, 7 stick. */
  private static final List<CraftGridFill.Placement> axeRecipe =
      List.of(
          new CraftGridFill.Placement(0, stack -> stack.is(Items.GOLD_INGOT)),
          new CraftGridFill.Placement(1, stack -> stack.is(Items.GOLD_INGOT)),
          new CraftGridFill.Placement(3, stack -> stack.is(Items.GOLD_INGOT)),
          new CraftGridFill.Placement(4, stack -> stack.is(Items.STICK)),
          new CraftGridFill.Placement(7, stack -> stack.is(Items.STICK)));

  private final GoldShovelCraftSession session;
  private final MacroActionTiming actionTiming;
  private final CraftGridFill fill = new CraftGridFill();

  GoldShovelCraftGrid(
      GoldShovelCraftSession session,
      GoldShovelMenuSlots menuSlots,
      MacroActionTiming actionTiming) {
    this.session = session;
    this.actionTiming = actionTiming;
  }

  /** Reset before a fresh craft attempt. */
  void begin() {
    fill.begin();
  }

  Outcome tick(Minecraft mc, LocalPlayer player, AbstractContainerMenu menu, Craft craft) {
    final boolean axe = craft == Craft.AXE;
    final Predicate<ItemStack> result =
        axe ? stack -> stack.is(Items.GOLDEN_AXE) : stack -> stack.is(Items.GOLDEN_SHOVEL);
    return switch (fill.tick(mc, player, menu, axe ? axeRecipe : shovelRecipe, result)) {
      case CRAFTED -> {
        // Only the shovel is tracked: it is the one the enchant leg goes looking for.
        if (!axe) {
          session.markCrafted();
        }
        yield Outcome.complete();
      }
      case OUT_OF_MATERIAL ->
          Outcome.failed(
              axe ? "Need 3 gold ingots and 2 sticks" : "Need 1 gold ingot and 2 sticks");
      case BLOCKED -> Outcome.failed("Crafting grid blocked");
      case WORKING -> Outcome.waiting(fill.status());

      default -> throw new MatchException(null, null);
    };
  }

  boolean returnCursorStackToPlayerInventory(
      Minecraft mc, AbstractContainerMenu menu, LocalPlayer player) {
    if (menu == null || menu.getCarried().isEmpty()) {
      return true;
    }
    if (mc == null || mc.gameMode == null || player == null) {
      return false;
    }
    // The callers re-run this every tick until the cursor clears, and a second click while the
    // first
    // is still in flight TAKES THE STACK BACK - that is the put-back stutter. Pace it like any
    // click.
    if (actionTiming.waitForMenuClick()) {
      return true;
    }
    Slot returnSlot =
        findCursorReturnSlot(menu, player, menu.getCarried(), session.returnSlotIndex());
    if (returnSlot == null) {
      return false;
    }
    clickSlot(mc, menu, returnSlot.index, 0, ContainerInput.PICKUP, player);
    actionTiming.armMenuClick(GuiActionTiming.randomDelay());
    return true;
  }
}
