package ryzn.macro.client.feature.macros.impl.level7;

import static ryzn.macro.client.feature.macros.impl.level7.RevLevel2Gear.MAX_GEAR_RECOVERIES;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import ryzn.macro.client.MacroInput;
import ryzn.macro.client.feature.commands.impl.pathfinder.PathfindCommand;
import ryzn.macro.client.feature.macros.impl.level7.RevLevel2Macro.State;
import ryzn.macro.client.input.GuiActionTiming;
import ryzn.macro.client.util.entity.MacroEntities;
import ryzn.macro.client.util.world.MacroObstruction;

/**
 * @description Owns merchant approach, line-of-sight checks and menu interaction retries.
 */
final class RevLevel2Merchants {

  private final RevLevel2Macro hunt;

  RevLevel2Merchants(RevLevel2Macro hunt) {
    this.hunt = hunt;
  }

  static final BlockPos WEAPONSMITH_POSITION = new BlockPos(-52, 69, -88);

  static final double ARRIVAL_DISTANCE_SQUARED = 2.75 * 2.75;

  static final double NPC_SEARCH_RADIUS = 12.0;

  static final int PATH_RETRY_TICKS = 20;

  static final int MAX_PATH_ATTEMPTS = 15;

  static final int NPC_MENU_TIMEOUT_TICKS = 20 * 15;

  /** How long a merchant menu gets to arrive before the macro asks for it again (~2 s). */
  static final int NPC_MENU_RECLICK_TICKS = 40;

  /** How long the merchant may stay out of reach or out of sight before the body moves closer. */
  static final int NO_VIEW_REPATH_TICKS = 50;

  int noViewTicks;

  /** Walk right up to the merchant: the usual standing spot had no reach or no view of it. */
  boolean approachMerchant;

  /**
   * Walks to a merchant and arrives only once it is both in reach and in clear view, so the click
   * that follows cannot land on a wall or counter. {@code usualSpot} is the standing spot tried
   * first (null walks straight to the merchant); if that spot has no reach or no view, the body
   * closes in on the merchant itself.
   */
  void tickPathToMerchant(
      LocalPlayer player, ClientLevel level, String name, BlockPos usualSpot, State next) {
    hunt.look().clear();
    final Entity npc = findMerchant(level, name, WEAPONSMITH_POSITION);
    if (npc != null && canInteract(level, player, npc)) {
      PathfindCommand.stopNavQuiet();
      hunt.setState(next, "At " + name);
      return;
    }
    final boolean atUsualSpot = usualSpot != null && nearPosition(player, usualSpot);
    if (npc == null && (usualSpot == null || atUsualSpot)) {
      if (hunt.stateTicks > NPC_MENU_TIMEOUT_TICKS) {
        hunt.fail("could not find " + name);
      } else {
        hunt.status = "Waiting for " + name;
      }
      return;
    }
    if (atUsualSpot && !approachMerchant) {
      approachMerchant = true;
      hunt.pathRetryTicks = 0;
      PathfindCommand.stopNavQuiet();
    }
    if (PathfindCommand.isNavigationActive()) {
      hunt.status = "Walking to " + name;
      return;
    }
    if (hunt.pathRetryTicks > 0) {
      hunt.status = "Waiting for pathfinder (" + hunt.pathAttempts + "/" + MAX_PATH_ATTEMPTS + ")";
      return;
    }
    if (++hunt.pathAttempts > MAX_PATH_ATTEMPTS) {
      hunt.fail("could not pathfind to " + name);
      return;
    }
    hunt.pathRetryTicks = PATH_RETRY_TICKS;
    final boolean toMerchant = npc != null && (usualSpot == null || approachMerchant);
    final int goalX = toMerchant ? npc.getBlockX() : usualSpot.getX();
    final int goalZ = toMerchant ? npc.getBlockZ() : usualSpot.getZ();
    if (PathfindCommand.startToQuiet(goalX, WEAPONSMITH_POSITION.getY(), goalZ)) {
      hunt.status = "Pathfinding to " + name;
    } else {
      hunt.status = "Waiting for pathfinder (" + hunt.pathAttempts + "/" + MAX_PATH_ATTEMPTS + ")";
    }
  }

  static boolean canInteract(ClientLevel level, LocalPlayer player, Entity npc) {
    return MacroObstruction.isWithinInteract(player, npc)
        && MacroObstruction.hasClearView(level, player, npc);
  }

  void tickOpenMerchant(
      Minecraft mc,
      LocalPlayer player,
      ClientLevel level,
      BlockPos anchor,
      String npcName,
      State next,
      State repath) {
    MacroInput.releaseMovementKeys(mc.options);
    if (hunt.isServerMenuOpen(mc)) {
      hunt.look().clear();
      hunt.setState(next, "Opened " + npcName);
      hunt.nextActionTicks = GuiActionTiming.firstMenuClickDelay();
      return;
    }
    if (hunt.stateTicks > NPC_MENU_TIMEOUT_TICKS) {
      hunt.gear.gearFailure("timed out waiting for " + npcName + "'s menu");
      return;
    }
    final Entity npc = findMerchant(level, npcName, anchor);
    if (npc == null) {
      hunt.status = "Waiting for " + npcName;
      return;
    }
    if (repath != null) {
      // Out of reach or behind a block: a click from here cannot land, so go and stand closer.
      noViewTicks = canInteract(level, player, npc) ? 0 : noViewTicks + 1;
      if (noViewTicks > NO_VIEW_REPATH_TICKS) {
        if (++hunt.gear.gearRecoveries > MAX_GEAR_RECOVERIES) {
          hunt.fail("could not get a clear view of " + npcName);
          return;
        }
        hunt.setState(repath, "No clear view of " + npcName + "; moving closer");
        hunt.pathAttempts = 0;
        approachMerchant = true;
        return;
      }
    }
    if (!hunt.look().aimAtEntity(player, npc)) {
      hunt.status = "Aiming at " + npcName;
      hunt.look().tick(player);
      return;
    }
    hunt.look().tick(player);
    if (hunt.nextActionTicks > 0 || !MacroInput.isCrosshairOn(mc, npc)) {
      hunt.status = "Aiming at " + npcName;
      return;
    }
    MacroInput.useClickOn(mc, npc);
    // Hypixel talks first and opens the menu a second or more later. Re-asking after half a second
    // queued a SECOND menu that arrived mid-equip, swapped the inventory screen out and dropped the
    // item off the cursor (log 2026-09-02 11:24:52-57). One request, then actually wait for it.
    hunt.nextActionTicks = GuiActionTiming.randomDelayAtLeast(NPC_MENU_RECLICK_TICKS);
    hunt.status = "Waiting for " + npcName + "'s menu";
  }

  boolean nearPosition(LocalPlayer player, BlockPos position) {
    final double x = player.getX() - (position.getX() + 0.5);
    final double z = player.getZ() - (position.getZ() + 0.5);
    return x * x + z * z <= ARRIVAL_DISTANCE_SQUARED
        && Math.abs(player.getY() - position.getY()) <= 3.0;
  }

  Entity findMerchant(ClientLevel level, String name, BlockPos anchor) {
    return MacroEntities.findNearestNamedEntity(
        level, name.toLowerCase(), anchor.getX(), anchor.getY(), anchor.getZ(), NPC_SEARCH_RADIUS);
  }
}
