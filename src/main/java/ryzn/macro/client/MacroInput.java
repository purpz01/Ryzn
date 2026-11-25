package ryzn.macro.client;

import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import ryzn.macro.client.core.RyznBootstrap;
import ryzn.macro.client.debug.DebugLog;
import ryzn.macro.client.input.MacroKeys;
import ryzn.macro.client.mixin.accessor.KeyMappingAccessor;
import ryzn.macro.client.util.gui.ScreenOwner;
import ryzn.macro.client.util.inventory.MacroSlots;
import ryzn.macro.client.util.text.MacroText;

/** Shared macro input helper for vanilla keybind clicks and jittered tick delays. */
public final class MacroInput {

  private MacroInput() {}

  public static void attackClick(Minecraft mc) {
    if (tracing()) {
      clicked("left click (attack) at %s", crosshairTarget(mc));
    }
    if (offFocusAttack(mc)) {
      return;
    }
    click(mc, mc != null && mc.options != null ? mc.options.keyAttack : null);
  }

  /**
   * Use-click for a TARGETLESS held-item action (rod cast/reel, ability items). With a grabbed
   * cursor it is the normal vanilla use-key click; otherwise it sends the direct held-item use
   * instead of the generic off-focus path, because that path routes through {@code mc.hitResult} -
   * which freezes when render frames stop - and a stale entity pick would turn the click into an
   * entity interact instead of using the item.
   */
  public static void useItemClick(Minecraft mc) {
    ScreenOwner.macroActed();
    if (tracing()) {
      clicked("right click, using %s", heldItem(mc));
    }
    if (needsDirectHeldItemUse(mc)) {
      useItemDirect(mc, InteractionHand.MAIN_HAND);
      return;
    }
    if (isCrosshairOnRealPlayer(mc)) {
      return;
    }
    click(mc, mc != null && mc.options != null ? mc.options.keyUse : null);
  }

  /**
   * Queue the normal use-key path when focused; fall back to a direct targetless use when it cannot
   * run.
   */
  public static boolean useHeldItemNow(Minecraft mc) {
    ScreenOwner.macroActed();
    if (mc == null || mc.gameMode == null || mc.player == null) {
      return false;
    }
    if (tracing()) {
      clicked("right click, using %s", heldItem(mc));
    }
    if (needsDirectHeldItemUse(mc)) {
      useItemDirect(mc, InteractionHand.MAIN_HAND);
    } else {
      click(mc, mc.options.keyUse);
    }
    return true;
  }

  /** Exact-intent click: fires only when the crosshair pick is {@code intended} itself. */
  public static void useClickOn(Minecraft mc, Entity intended) {
    ScreenOwner.macroActed();
    if (!isCrosshairOn(mc, intended)) {
      if (tracing()) {
        clicked(
            "SKIPPED a right click on %s - the crosshair is on %s instead",
            name(intended), crosshairTarget(mc));
      }
      return;
    }
    if (tracing()) {
      clicked("right click %s", name(intended));
    }
    if (offFocusUse(mc, intended)) {
      return;
    }
    click(mc, mc != null && mc.options != null ? mc.options.keyUse : null);
  }

  /**
   * Exact-intent hold: presses use only while the crosshair pick is {@code intended} itself, so an
   * NPC click can never land on whoever happens to stand in front of it - not by skin, name, or
   * heuristic, but by identity of the entity the click would actually hit. Passing {@code
   * held=false} always releases.
   */
  public static void setUseHeldOn(Options opts, boolean held, Entity intended) {
    if (opts == null) {
      return;
    }
    final boolean press = held && isCrosshairOn(Minecraft.getInstance(), intended);
    if (press) {
      ScreenOwner.macroActed();
    }
    if (tracing()) {
      holding(
          USE_KEY, press ? "holding right click on " + name(intended) : "not holding right click");
    }
    MacroKeys.set(opts.keyUse, press);
  }

  /** How far off the intended entity's column the pick may sit and still be its own body. */
  private static final double NPC_BODY_SLACK_XZ = 1.0;

  /** The hologram floats above the body, so vertical slack has to be the generous one. */
  private static final double NPC_BODY_SLACK_Y = 3.0;

  /**
   * True when the crosshair pick is {@code intended} - or its own body. A Hypixel NPC is TWO
   * entities: an invisible hologram armor stand carrying the searchable name (what the macros find
   * and aim at, since the body's entity name is scrambled) and the skinned player body underneath
   * (what the crosshair actually picks). A pick glued to the intended entity passes as long as it
   * is not a REAL player standing in front.
   *
   * <p>Glued means sharing the NPC's column, not merely being near it. Measuring the plain distance
   * let anything parked a couple of blocks away pass as the NPC: Melody stands about two blocks
   * from the harp, so aiming at her picked up the harp's armor stand, and the click opened the harp
   * instead of her song menu.
   */
  public static boolean isCrosshairOn(Minecraft mc, Entity intended) {
    if (intended == null || mc == null || !(mc.hitResult instanceof EntityHitResult hit)) {
      return false;
    }
    final Entity picked = hit.getEntity();
    if (picked == intended) {
      return true;
    }
    if (picked instanceof Player other && isRealPlayer(mc, other)) {
      return false;
    }
    final double dx = picked.getX() - intended.getX();
    final double dz = picked.getZ() - intended.getZ();
    return dx * dx + dz * dz <= NPC_BODY_SLACK_XZ * NPC_BODY_SLACK_XZ
        && Math.abs(picked.getY() - intended.getY()) <= NPC_BODY_SLACK_Y;
  }

  /**
   * Global anti-misclick rule: a use press is suppressed while the crosshair rests on another REAL
   * player, so no macro ever opens a stranger's profile menu by accident. Hypixel NPCs (Melody,
   * merchants, quest givers) are player-type entities too and the macros deliberately click them,
   * so the guard must never catch those - see {@link #isRealPlayer}.
   */
  public static boolean isCrosshairOnRealPlayer(Minecraft mc) {
    return mc != null
        && mc.hitResult instanceof EntityHitResult hit
        && hit.getEntity() instanceof Player other
        && isRealPlayer(mc, other);
  }

  /**
   * True only for another actual account: BOTH signals must agree - a live tab-list entry AND a
   * version-4 (random) account UUID. Hypixel player-NPCs are removed from the tab list right after
   * spawning, so they fail this and are treated as scenery. Failing either check means "not a real
   * player", i.e. the guards fail open and never break an intentional NPC interaction.
   */
  public static boolean isRealPlayer(Minecraft mc, Player other) {
    if (mc == null || other == null || other == mc.player) {
      return false;
    }
    final ClientPacketListener connection = mc.getConnection();
    return connection != null
        && connection.getPlayerInfo(other.getUUID()) != null
        && other.getUUID().version() == 4;
  }

  public static void setAttackHeld(Options opts, boolean held) {
    if (opts == null) {
      return;
    }
    holding(ATTACK_KEY, held ? "holding left click" : "not holding left click");
    MacroKeys.set(opts.keyAttack, held);
  }

  public static void setUseHeld(Options opts, boolean held) {
    if (opts == null) {
      return;
    }
    if (held && isCrosshairOnRealPlayer(Minecraft.getInstance())) {
      held = false;
      holding(USE_KEY, "not holding right click - the crosshair is on another player");
    } else {
      holding(USE_KEY, held ? "holding right click" : "not holding right click");
    }
    if (held) {
      ScreenOwner.macroActed();
    }
    MacroKeys.set(opts.keyUse, held);
  }

  /**
   * Stops the body outright. Drops the macro intent AND the vanilla flag, because input handling
   * drives the player through the real mappings while a screen is open and calls this to halt them
   * - clearing only the intent would leave those still held.
   */
  public static void releaseMovementKeys(Options opts) {
    if (opts == null) {
      return;
    }
    for (KeyMapping mapping :
        new KeyMapping[] {
          opts.keyUp,
          opts.keyDown,
          opts.keyLeft,
          opts.keyRight,
          opts.keyJump,
          opts.keyShift,
          opts.keySprint
        }) {
      MacroKeys.set(mapping, false);
      mapping.setDown(false);
    }
  }

  public static void releaseAllKeys(Options opts) {
    releaseMovementKeys(opts);
    releaseAttackAndUse(opts);
  }

  public static void releaseAttackAndUse(Options opts) {
    if (opts == null) {
      return;
    }
    releaseAttack(opts);
    releaseUse(opts);
  }

  public static void releaseAttack(Options opts) {
    if (opts == null) {
      return;
    }
    holding(ATTACK_KEY, "not holding left click");
    MacroKeys.set(opts.keyAttack, false);
  }

  public static void releaseUse(Options opts) {
    if (opts == null) {
      return;
    }
    holding(USE_KEY, "not holding right click");
    MacroKeys.set(opts.keyUse, false);
  }

  public static void releaseMovementAndAttack(Options opts) {
    releaseMovementKeys(opts);
    releaseAttack(opts);
  }

  public static void selectHotbarSlot(LocalPlayer player, int slot) {
    // The 9th slot is reserved from every macro in the mod - see MacroSlots.RESERVED_HOTBAR_SLOT.
    if (player == null || !MacroSlots.isUsableHotbarSlot(slot)) {
      return;
    }
    RyznBootstrap.failsafeManager().expectSelectedSlot(slot);
    if (tracing()) {
      holding(
          HOTBAR_KEY,
          "hotbar slot "
              + (slot + 1)
              + " is selected, holding "
              + stackName(player.getInventory().getItem(slot)));
    }
    if (player.getInventory().getSelectedSlot() != slot) {
      player.getInventory().setSelectedSlot(slot);
    }
  }

  /** Queue the vanilla number-key mapping for a hotbar slot. */
  public static boolean hotbarKeyClick(Minecraft mc, int slot) {
    if (mc == null || mc.options == null || !MacroSlots.isUsableHotbarSlot(slot)) {
      return false;
    }
    RyznBootstrap.failsafeManager().expectSelectedSlot(slot);
    return click(mc, mc.options.keyHotbarSlots[slot]);
  }

  /** Queue the player's normal bound drop-item key. */
  public static boolean dropItemKeyClick(Minecraft mc) {
    return click(mc, mc != null && mc.options != null ? mc.options.keyDrop : null);
  }

  /**
   * Queues one click on this mapping alone. Going through {@code KeyMapping.click(boundKey)}
   * instead would fire every action sharing that physical key - and for an unbound mapping, every
   * unbound keybind in the game, this mod's and every other mod's.
   */
  private static boolean click(Minecraft mc, KeyMapping mapping) {
    if (mc == null || mapping == null) {
      return false;
    }
    final KeyMappingAccessor counter = (KeyMappingAccessor) mapping;
    counter.ryzn$setClickCount(counter.ryzn$getClickCount() + 1);
    return true;
  }

  private static boolean offFocusAttack(Minecraft mc) {
    if (!needsDirectGameClick(mc)) {
      return false;
    }
    final HitResult hit = mc.hitResult;
    if (hit instanceof EntityHitResult entityHit) {
      final Entity target = entityHit.getEntity();
      if (target instanceof Player other && isRealPlayer(mc, other)) {
        return true;
      }
      mc.gameMode.attack(mc.player, target);
      mc.player.swing(InteractionHand.MAIN_HAND);
      return true;
    }
    if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
      mc.gameMode.startDestroyBlock(blockHit.getBlockPos(), blockHit.getDirection());
      mc.player.swing(InteractionHand.MAIN_HAND);
      return true;
    }
    return true;
  }

  private static boolean offFocusUse(Minecraft mc, Entity intended) {
    if (!needsDirectGameClick(mc)) {
      return false;
    }
    final InteractionHand hand = InteractionHand.MAIN_HAND;
    final HitResult hit = mc.hitResult;
    if (hit instanceof EntityHitResult entityHit) {
      final Entity target = intended != null ? intended : entityHit.getEntity();
      if (swingsClient(mc.gameMode.interact(mc.player, target, entityHit, hand))) {
        mc.player.swing(hand);
      }
    } else if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
      final ItemStack held = mc.player.getItemInHand(hand);
      final int count = held.getCount();
      if (swingsClient(mc.gameMode.useItemOn(mc.player, hand, blockHit))) {
        mc.player.swing(hand);
        if (!held.isEmpty() && (held.getCount() != count || mc.player.hasInfiniteMaterials())) {
          mc.gameRenderer.itemInHandRenderer.itemUsed(hand);
        }
      }
    } else {
      useItemDirect(mc, hand);
    }
    return true;
  }

  /**
   * A targetless item use sent without the use key, finished the way vanilla's own click handler
   * finishes it: the arm swing (which is also the swing packet a real click sends) and the hand
   * animation. {@code gameMode.useItem} alone does neither.
   */
  private static void useItemDirect(Minecraft mc, InteractionHand hand) {
    final InteractionResult result = mc.gameMode.useItem(mc.player, hand);
    if (swingsClient(result)) {
      mc.player.swing(hand);
    }
    if (result instanceof InteractionResult.Success) {
      mc.gameRenderer.itemInHandRenderer.itemUsed(hand);
    }
  }

  private static boolean swingsClient(InteractionResult result) {
    return result instanceof InteractionResult.Success success
        && success.swingSource() == InteractionResult.SwingSource.CLIENT;
  }

  private static boolean needsDirectGameClick(Minecraft mc) {
    return mc != null
        && !mc.isWindowActive()
        && mc.gui.screen() == null
        && mc.gameMode != null
        && mc.player != null;
  }

  private static boolean needsDirectHeldItemUse(Minecraft mc) {
    return needsDirectGameClick(mc)
        || mc != null
            && !mc.mouseHandler.isMouseGrabbed()
            && mc.gui.screen() == null
            && mc.gameMode != null
            && mc.player != null;
  }

  public static int jitter(int baseTicks) {
    if (baseTicks <= 0) {
      return baseTicks;
    }
    final int spread = Math.max(1, Math.round(baseTicks * 0.4f));
    final int delta = ThreadLocalRandom.current().nextInt(-spread, spread + 1);
    final int jittered = Math.max(1, baseTicks + delta);
    DebugLog.trace(
        DebugLog.Category.MACRO, "timing: humanised a %dt wait into %dt", baseTicks, jittered);
    return jittered;
  }

  /**
   * One key per thing a macro holds down, so {@link DebugLog#event} prints only when that thing
   * changes.
   */
  private static final String ATTACK_KEY = "macro-attack-key";

  private static final String USE_KEY = "macro-use-key";
  private static final String HOTBAR_KEY = "macro-hotbar-slot";

  /**
   * Whether the debug lines below are wanted at all. Every one of them builds a string from an
   * entity or item name, and that work happens before the call, so the check has to be here rather
   * than inside {@link DebugLog}.
   */
  private static boolean tracing() {
    return DebugLog.isEnabled(DebugLog.Category.MACRO);
  }

  /** A discrete click the macro just made - every one of them is worth a line. */
  private static void clicked(String what, Object... args) {
    DebugLog.action(DebugLog.Category.MACRO, "input: " + what, args);
  }

  /** Something a macro re-asserts every tick; only a change is worth a line. */
  private static void holding(String key, String what) {
    DebugLog.event(DebugLog.Category.MACRO, key, "input: " + what);
  }

  private static String name(Entity entity) {
    return entity == null ? "nothing" : MacroText.stripFormatting(entity.getName().getString());
  }

  private static String stackName(ItemStack stack) {
    return stack.isEmpty()
        ? "nothing"
        : MacroText.stripFormatting(stack.getHoverName().getString());
  }

  private static String heldItem(Minecraft mc) {
    return mc == null || mc.player == null ? "nothing" : stackName(mc.player.getMainHandItem());
  }

  /** What a click would land on right now, so a missed click says what it hit instead. */
  private static String crosshairTarget(Minecraft mc) {
    if (mc == null) {
      return "nothing";
    }
    if (mc.hitResult instanceof EntityHitResult entityHit) {
      return name(entityHit.getEntity());
    }
    if (mc.hitResult instanceof BlockHitResult blockHit
        && mc.hitResult.getType() == HitResult.Type.BLOCK) {
      return "the block at " + blockHit.getBlockPos().toShortString();
    }
    return "nothing";
  }
}
