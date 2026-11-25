package ryzn.macro.client.feature.macros.impl.foraging;

import static ryzn.macro.client.util.inventory.MacroItems.stackName;
import static ryzn.macro.client.util.inventory.MacroItems.stackSearchText;
import static ryzn.macro.client.util.inventory.MacroSlots.isPlayerInventorySlot;
import static ryzn.macro.client.util.text.MacroText.normalizeSearchText;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import ryzn.macro.client.debug.DebugLog;

final class HarpSongMenu {
  /**
   * Hypixel writes "Best: 92.6%" (and "Best: 100%!"); "best score:" is kept as an older/alternate
   * wording.
   */
  private static final Pattern BEST_SCORE_PATTERN =
      Pattern.compile(
          "best(?: *score)?:? *([0-9]{1,3}(?:[.][0-9]+)?) *%", Pattern.CASE_INSENSITIVE);

  /**
   * The "Best:" line itself, present on every real song - including unplayed ones, as "Best: N/A".
   */
  private static final Pattern BEST_LINE_PATTERN =
      Pattern.compile("best(?: *score)?:", Pattern.CASE_INSENSITIVE);

  record Song(
      int slotIndex, int order, String displayName, boolean looksCompleted, boolean locked) {}

  /**
   * Song entries already written to the debug log, so the dump is one line each, not one per scan.
   */
  private final Set<String> seen = new HashSet<>();

  List<Song> scan(AbstractContainerMenu menu, LocalPlayer player, int minimumScore) {
    final List<Slot> candidates = new ArrayList<>();
    boolean anySongLike = false;
    for (Slot slot : menu.slots) {
      if (slot == null || isPlayerInventorySlot(menu, player, slot)) {
        continue;
      }
      final ItemStack stack = slot.getItem();
      if (!isSongCandidate(stack)) {
        continue;
      }
      candidates.add(slot);
      if (looksLikeSong(normalizeSearchText(stackSearchText(stack)))) {
        anySongLike = true;
      }
    }
    candidates.sort((left, right) -> Integer.compare(left.index, right.index));

    final List<Song> songs = new ArrayList<>();
    int order = 0;
    for (Slot slot : candidates) {
      final ItemStack stack = slot.getItem();
      final String search = normalizeSearchText(stackSearchText(stack));
      if (anySongLike && !looksLikeSong(search)) {
        continue;
      }
      String name = stackName(stack);
      if (name.isBlank()) {
        name = "song@" + slot.index;
      }
      final boolean completed = looksCompleted(search, minimumScore);
      final boolean locked = looksLocked(search);
      logSong(slot.index, name, completed, locked, search);
      songs.add(new Song(slot.index, order++, name, completed, locked));
    }
    return songs;
  }

  /**
   * Log each song entry once per session: slot, name, whether we read it as already cleared or
   * locked, and the lore that decision came from. The lore patterns here are read off Hypixel's
   * live wording, and when one stops matching a cleared song reads as unplayed and gets played
   * again - so the text it failed on has to be recoverable from the log rather than guessed at a
   * second time.
   */
  private void logSong(
      int slotIndex, String name, boolean completed, boolean locked, String search) {
    if (!DebugLog.isEnabled(DebugLog.Category.MACRO)) {
      return;
    }
    final String entry =
        slotIndex
            + " "
            + name
            + " completed="
            + completed
            + " locked="
            + locked
            + " lore="
            + search;
    if (seen.add(entry)) {
      DebugLog.detail(DebugLog.Category.MACRO, "[harp] song slot=%s", entry);
    }
  }

  Song firstSelectable(List<Song> songs, HarpSongSession session) {
    for (Song song : songs) {
      if (song.looksCompleted() || song.locked()) {
        continue;
      }
      if (session.isSongComplete(normalizeSearchText(song.displayName()))) {
        continue;
      }
      return song;
    }
    return null;
  }

  /**
   * A real song entry, as opposed to the other things Melody's menu holds (the Personal Harp shop
   * slot, the unrevealed "Mystery Song" placeholders). Every playable song carries a "Best:" line -
   * even an unplayed one, as "Best: N/A" - so that line is the identity. The click-prompt wordings
   * are a fallback in case Hypixel restyles the score line.
   */
  boolean looksLikeSong(String search) {
    return BEST_LINE_PATTERN.matcher(search).find()
        || search.contains("click to select song")
        || search.contains("song is selected")
        || search.contains("click for your first attempt");
  }

  /** Not yet unlocked: the server ignores the click, so selecting it would spin forever. */
  boolean looksLocked(String search) {
    return search.contains("on previous song");
  }

  boolean looksCompleted(String search, int minimumScore) {
    final Matcher matcher = BEST_SCORE_PATTERN.matcher(search);
    return matcher.find() && parseScoreSafe(matcher.group(1)) >= minimumScore;
  }

  private static boolean isSongCandidate(ItemStack stack) {
    if (stack == null || stack.isEmpty() || stack.is(Items.BARRIER)) {
      return false;
    }
    final String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
    if (path.contains("pane")) {
      return false;
    }
    final String name = normalizeSearchText(stackName(stack));
    if (name.isBlank()) {
      return false;
    }
    if (isShopEntry(name, normalizeSearchText(stackSearchText(stack)))) {
      return false;
    }
    return !name.contains("close")
        && !name.contains("go back")
        && !name.equals("back")
        && !name.contains("previous page")
        && !name.contains("next page")
        && !name.contains("return");
  }

  /**
   * The Personal Harp furniture slot. Its lore talks about completing songs, so the old loose song
   * test accepted it; once every song was cleared it became the only "unplayed song" left and the
   * macro clicked it forever instead of finishing the run.
   */
  private static boolean isShopEntry(String name, String search) {
    return name.contains("personal harp")
        || search.contains("cost:")
        || search.contains("can't afford")
        || search.contains("click to purchase");
  }

  private static double parseScoreSafe(String text) {
    try {
      return Double.parseDouble(text);
    } catch (NumberFormatException ignored) {
      return 0;
    }
  }
}
