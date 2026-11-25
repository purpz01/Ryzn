package ryzn.macro.client.feature.hud;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import ryzn.macro.client.feature.hud.SkyBlockHudData.Field;
import ryzn.macro.client.feature.hud.SkyBlockHudParser.Line;
import ryzn.macro.client.util.text.MacroText;

/**
 * Minecraft-facing scoreboard facade. Minecraft renders the live rows; Ryzn reads the same
 * objective only for macro helpers such as area, objective, and Garden pest detection.
 */
public final class SkyBlockHud {

  private static final SkyBlockHudParser parser = new SkyBlockHudParser();
  private static final SkyBlockHudSession session = new SkyBlockHudSession();

  private SkyBlockHud() {}

  /** Returns the sidebar area name independently of whether the HUD is enabled. */
  public static String currentArea() {
    Minecraft minecraft = Minecraft.getInstance();
    if (minecraft == null || minecraft.level == null) {
      return null;
    }
    int worldId = System.identityHashCode(minecraft.level);
    int tick = minecraft.player != null ? minecraft.player.tickCount : -1;
    return session.currentArea(
        worldId, tick, () -> parser.parseSidebar(readSidebar(minecraft)).get(Field.AREA));
  }

  /** Returns the sidebar objective independently of whether the HUD is enabled. */
  public static String currentObjective() {
    Minecraft minecraft = Minecraft.getInstance();
    if (minecraft == null || minecraft.level == null) {
      return null;
    }
    return parser.parseSidebar(readSidebar(minecraft)).get(Field.OBJECTIVE);
  }

  /** Returns whether any clean sidebar line contains the requested text. */
  public static boolean sidebarContainsText(String text) {
    Minecraft minecraft = Minecraft.getInstance();
    if (minecraft == null || minecraft.level == null) {
      return false;
    }
    String search = MacroText.normalizeSearchText(text);
    if (search.isBlank()) {
      return false;
    }
    for (Line line : readSidebar(minecraft)) {
      if (MacroText.normalizeSearchText(line.clean()).contains(search)) {
        return true;
      }
    }
    return false;
  }

  /** The live sidebar rows, styling intact, in the order Minecraft would draw them. */
  public static List<Component> sidebarRows() {
    Minecraft minecraft = Minecraft.getInstance();
    return minecraft == null ? List.of() : readSidebarComponents(minecraft);
  }

  /** Reads the active sidebar objective into clean, top-to-bottom lines. */
  private static List<Line> readSidebar(Minecraft minecraft) {
    List<Line> lines = new ArrayList<>();
    for (Component row : readSidebarComponents(minecraft)) {
      String raw = row.getString();
      String clean = clean(raw);
      // Hypixel's blank spacer rows are group separators for the skin, but noise to the parsers.
      if (!clean.isBlank()) {
        lines.add(new Line(raw, clean));
      }
    }
    return lines;
  }

  /**
   * The one place the sidebar is read. Rows keep their components rather than being flattened here,
   * because the skin draws them in the server's own colours and only the text helpers need strings.
   */
  private static List<Component> readSidebarComponents(Minecraft minecraft) {
    List<Component> rows = new ArrayList<>();
    if (minecraft.level == null) {
      return rows;
    }
    Scoreboard scoreboard = minecraft.level.getScoreboard();
    if (scoreboard == null) {
      return rows;
    }
    Objective objective = activeSidebarObjective(minecraft, scoreboard);
    if (objective == null) {
      return rows;
    }

    List<PlayerScoreEntry> entries = new ArrayList<>(scoreboard.listPlayerScores(objective));
    entries.removeIf(PlayerScoreEntry::isHidden);
    entries.sort(Comparator.comparingInt(PlayerScoreEntry::value).reversed());
    // Blank rows are kept: Hypixel uses them to group the sidebar, and the skin renders them as the
    // gaps they are. Callers that parse text drop them.
    for (PlayerScoreEntry entry : entries) {
      rows.add(sidebarRow(scoreboard, entry));
    }
    return rows;
  }

  /**
   * Hypixel stores visible sidebar text in a team prefix and suffix around a throwaway score-holder
   * key. Vanilla scoreboards without a team fall back to the holder name.
   */
  private static Component sidebarRow(Scoreboard scoreboard, PlayerScoreEntry entry) {
    PlayerTeam team = scoreboard.getPlayersTeam(entry.owner());
    if (team != null) {
      Component combined =
          Component.empty().append(team.getPlayerPrefix()).append(team.getPlayerSuffix());
      if (!clean(combined.getString()).isBlank()) {
        return combined;
      }
    }
    return entry.ownerName();
  }

  private static Objective activeSidebarObjective(Minecraft minecraft, Scoreboard scoreboard) {
    Objective objective = null;
    if (minecraft.player != null) {
      PlayerTeam team = scoreboard.getPlayersTeam(minecraft.player.getScoreboardName());
      if (team != null) {
        DisplaySlot teamSlot = team.getColor().map(color -> color.displaySlot()).orElse(null);
        if (teamSlot != null) {
          objective = scoreboard.getDisplayObjective(teamSlot);
        }
      }
    }
    return objective != null ? objective : scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
  }

  private static String clean(String text) {
    return MacroText.cleanDecoratedText(text);
  }
}
