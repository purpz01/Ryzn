package ryzn.macro.client.feature.macros.impl.sand;

import static ryzn.macro.client.feature.commands.impl.MacroCommands.sendCommand;
import static ryzn.macro.client.util.gui.MacroScreens.closeCurrentScreen;
import static ryzn.macro.client.util.gui.MacroScreens.currentContainerMenu;
import static ryzn.macro.client.util.gui.MacroScreens.titleContains;
import static ryzn.macro.client.util.inventory.MacroItems.stackName;
import static ryzn.macro.client.util.text.MacroText.normalizeSearchText;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import ryzn.macro.client.util.inventory.CraftGridFill;

/**
 * Turns mined sand into Enchanted Sand between route laps.
 *
 * <p>The recipe costs 160 sand for one, and the Craft Item menu credits everything the grid can
 * make in a single shift-click. Sand stacks to 64, so five FULL stacks - 320 - is the smallest fill
 * that both divides evenly into the recipe (2 crafts) and leaves nothing behind in the grid.
 * Leftovers are the thing to avoid: a grid that does not empty never reads as "crafted", so the
 * fill would stall and the menu would close on top of the sand. Hence full stacks only, five of
 * them, and no cycle that cannot come out clean.
 */
final class SandEnchantedCraft {

  private static final int STACKS_PER_CYCLE = 5;
  private static final int FULL_STACK = 64;

  /**
   * Each trip waits for a random number of full stacks in this range, so the /craft rhythm is not
   * the same every time.
   */
  private static final int MIN_TARGET_STACKS = STACKS_PER_CYCLE;

  private static final int MAX_TARGET_STACKS = 12;

  /** Below this many empty slots the trip starts at once, whatever the random target says. */
  private static final int FREE_SLOT_RESERVE = 3;

  /** Main inventory only (hotbar + the 27 above it); armour and offhand never take sand. */
  private static final int MAIN_INVENTORY_SLOTS = 36;

  private static final int STEP_TIMEOUT_TICKS = 20 * 20;
  private static final int RETRY_COOLDOWN_TICKS = 20 * 30;

  /** Five squares of the 3x3, centre last so a partial fill never sits on the centre alone. */
  private static final List<CraftGridFill.Placement> recipe =
      List.of(
          new CraftGridFill.Placement(0, SandEnchantedCraft::isFullSandStack),
          new CraftGridFill.Placement(1, SandEnchantedCraft::isFullSandStack),
          new CraftGridFill.Placement(2, SandEnchantedCraft::isFullSandStack),
          new CraftGridFill.Placement(3, SandEnchantedCraft::isFullSandStack),
          new CraftGridFill.Placement(4, SandEnchantedCraft::isFullSandStack));

  private enum Step {
    OPEN,
    WAIT_MENU,
    FILL,
    CLOSE
  }

  private final CraftGridFill fill = new CraftGridFill();
  private Step step;
  private int stepTicks;
  private int cooldownTicks;
  private int targetStacks = pickTargetStacks();
  private String status = "";

  String status() {
    return status;
  }

  /**
   * True while the craft is driving a menu of its own, so the failsafes know that screen is
   * expected.
   */
  boolean isRunning() {
    return step != null;
  }

  void reset() {
    step = null;
    stepTicks = 0;
    cooldownTicks = 0;
    targetStacks = pickTargetStacks();
    status = "";
  }

  /**
   * Advance the craft.
   *
   * @return true while the craft owns the player, so the miner keeps its hands off the keys
   */
  boolean tick(Minecraft mc, LocalPlayer player) {
    if (cooldownTicks > 0) {
      cooldownTicks--;
    }
    if (step == null) {
      if (!shouldStartTrip(
          fullSandStacks(player), freeSlots(player), targetStacks, cooldownTicks)) {
        return false;
      }
      enter(Step.OPEN);
    }
    if (++stepTicks > STEP_TIMEOUT_TICKS) {
      return abort(mc, "Enchanted Sand craft timed out");
    }
    return switch (step) {
      case OPEN -> tickOpen(mc);
      case WAIT_MENU -> tickWaitMenu(mc);
      case FILL -> tickFill(mc, player);
      case CLOSE -> tickClose(mc);

      default -> throw new MatchException(null, null);
    };
  }

  private boolean tickOpen(Minecraft mc) {
    status = "Opening /craft";
    if (mc.gui.screen() != null) {
      closeCurrentScreen(mc); // some other menu is up; clear it before /craft
      return true;
    }
    sendCommand(mc, "craft");
    enter(Step.WAIT_MENU);
    return true;
  }

  private boolean tickWaitMenu(Minecraft mc) {
    status = "Waiting for Craft Item";
    if (!titleContains(mc, "craft item")) {
      return true;
    }
    fill.begin(true);
    enter(Step.FILL);
    return true;
  }

  private boolean tickFill(Minecraft mc, LocalPlayer player) {
    final AbstractContainerMenu menu = currentContainerMenu(mc);
    if (menu == null || !titleContains(mc, "craft item")) {
      return abort(mc, "Craft Item closed");
    }
    switch (fill.tick(mc, player, menu, recipe, SandEnchantedCraft::isEnchantedSand)) {
      case CRAFTED -> {
        status = "Crafted Enchanted Sand";
        // Another clean cycle is only possible with five more full stacks; anything less would
        // leave sand in the grid.
        if (fullSandStacks(player) >= STACKS_PER_CYCLE) {
          fill.begin(true);
          stepTicks = 0;
        } else {
          enter(Step.CLOSE);
        }
      }
      case OUT_OF_MATERIAL -> enter(Step.CLOSE);
      case BLOCKED -> {
        return abort(mc, "Crafting grid blocked");
      }
      case WORKING -> status = fill.status();

      default -> {}
    }
    return true;
  }

  private boolean tickClose(Minecraft mc) {
    status = "Closing /craft";
    if (mc.gui.screen() != null) {
      closeCurrentScreen(mc);
      return true;
    }
    step = null;
    targetStacks = pickTargetStacks();
    return false; // hand the player back to the route
  }

  private boolean abort(Minecraft mc, String reason) {
    status = reason;
    closeCurrentScreen(mc);
    step = null;
    targetStacks = pickTargetStacks();
    cooldownTicks = RETRY_COOLDOWN_TICKS; // do not hammer /craft when something is off
    return false;
  }

  /**
   * A trip needs five full stacks for a clean craft. It then waits for the randomly picked target,
   * unless the inventory is nearly full, which always wins so mined sand is never lost.
   */
  static boolean shouldStartTrip(int fullStacks, int freeSlots, int target, int cooldownTicks) {
    // A failed trip still backs off, or a broken menu would be reopened every few seconds.
    if (cooldownTicks > 0 || fullStacks < STACKS_PER_CYCLE) {
      return false;
    }
    return freeSlots <= FREE_SLOT_RESERVE || fullStacks >= target;
  }

  private static int pickTargetStacks() {
    return ThreadLocalRandom.current().nextInt(MIN_TARGET_STACKS, MAX_TARGET_STACKS + 1);
  }

  private static int freeSlots(LocalPlayer player) {
    int free = 0;
    for (int index = 0; index < MAIN_INVENTORY_SLOTS; index++) {
      if (player.getInventory().getItem(index).isEmpty()) {
        free++;
      }
    }
    return free;
  }

  private void enter(Step next) {
    step = next;
    stepTicks = 0;
  }

  /** Only full stacks are ever placed, so only full stacks count toward starting a cycle. */
  private static int fullSandStacks(LocalPlayer player) {
    int stacks = 0;
    for (int index = 0; index < player.getInventory().getContainerSize(); index++) {
      if (isFullSandStack(player.getInventory().getItem(index))) {
        stacks++;
      }
    }
    return stacks;
  }

  private static boolean isFullSandStack(ItemStack stack) {
    return stack != null
        && !stack.isEmpty()
        && stack.is(Items.SAND)
        && stack.getCount() >= FULL_STACK
        && !normalizeSearchText(stackName(stack)).contains("enchanted");
  }

  private static boolean isEnchantedSand(ItemStack stack) {
    return stack != null
        && !stack.isEmpty()
        && normalizeSearchText(stackName(stack)).contains("enchanted sand");
  }

  // The whole design rests on one cycle dividing evenly into the recipe; these expose that for the
  // test.

}
