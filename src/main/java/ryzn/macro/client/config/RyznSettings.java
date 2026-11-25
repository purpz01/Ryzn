package ryzn.macro.client.config;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.experimental.Accessors;
import ryzn.macro.client.config.groups.FailsafeSettings;
import ryzn.macro.client.config.groups.PathfinderSettings;
import ryzn.macro.client.config.groups.RotationSettings;
import ryzn.macro.client.config.groups.RyznMenuSettings;
import ryzn.macro.client.config.settings.ConfigSection;
import ryzn.macro.client.config.settings.ToggleSettings;
import ryzn.macro.client.core.RyznBootstrap;
import ryzn.macro.client.feature.api.Element;
import ryzn.macro.client.feature.macros.impl.foraging.AfkAutoHarpMacro;
import ryzn.macro.client.feature.macros.impl.foraging.AutoHarpMacro;
import ryzn.macro.client.feature.macros.impl.foragingquest.ForagingQuestUnlockerMacro;
import ryzn.macro.client.feature.macros.impl.goldshovel.GoldShovelMacro;
import ryzn.macro.client.feature.macros.impl.level7.AutoLvl7Macro;
import ryzn.macro.client.feature.macros.impl.sand.SandMinerMacro;
import ryzn.macro.client.feature.macros.impl.slayer.RevSlayerMacro;
import ryzn.macro.client.feature.macros.impl.tree.TreeChopMacro;
import ryzn.macro.client.feature.macros.impl.wheat.WheatBreakerMacro;
import ryzn.macro.client.feature.modules.impl.combat.CombatModule;

/**
 * Root settings registry for {@code config/ryzn/settings.json}. Feature sections own their values,
 * validation, accessors, derived behavior, and manual JSON keys. This class only groups the
 * sections, coordinates the root JSON tree, and notifies {@link ConfigManager} about changes.
 */
@Getter
@Accessors(fluent = true)
public final class RyznSettings {
  public static final int SCHEMA_VERSION = 1;
  private static final Map<String, String> RUN_TOGGLES =
      Map.of(
          "gold_shovel", "gold_shovel_macro_enabled",
          "foraging_quest", "foraging_quest_unlocker_enabled",
          "wheat_breaker", "wheat_breaker_enabled",
          "auto_harp", "auto_harp_enabled",
          "afk_auto_harp", "afk_auto_harp_enabled",
          "sand_miner", "sand_miner_enabled",
          "rev_slayer", "enabled");

  private final FailsafeSettings failsafe = new FailsafeSettings();
  private final PathfinderSettings pathfinder = new PathfinderSettings();
  private final RotationSettings rotations = new RotationSettings();
  private final RyznMenuSettings ryznMenu = new RyznMenuSettings();
  private final WheatBreakerMacro wheat = new WheatBreakerMacro();
  private final GoldShovelMacro goldShovel = new GoldShovelMacro();
  private final ForagingQuestUnlockerMacro foragingQuest = new ForagingQuestUnlockerMacro();
  private final AutoLvl7Macro level7 = new AutoLvl7Macro();
  private final SandMinerMacro sand = new SandMinerMacro();
  private final RevSlayerMacro revSlayer = new RevSlayerMacro();
  private final AutoHarpMacro autoHarp = new AutoHarpMacro();
  private final AfkAutoHarpMacro afkAutoHarp = new AfkAutoHarpMacro();
  private final TreeChopMacro treeChop = new TreeChopMacro();
  private final CombatModule combat = new CombatModule();
  private final ToggleSettings keepFocus =
      new ToggleSettings("keep_focus", "keep_focus_enabled", false);
  private final ToggleSettings statusHud = new ToggleSettings("status_hud", "enabled", false);
  private final ToggleSettings statusCoords =
      new ToggleSettings("status_coordinates", "enabled", true);
  private final List<ConfigSection> sections;
  private static final RyznSettings live = new RyznSettings();

  public RyznSettings() {
    sections =
        List.of(
            failsafe,
            pathfinder,
            rotations,
            ryznMenu,
            wheat,
            goldShovel,
            foragingQuest,
            level7,
            sand,
            revSlayer,
            autoHarp,
            afkAutoHarp,
            treeChop,
            combat,
            keepFocus,
            statusHud,
            statusCoords);
  }

  public static RyznSettings get() {
    return live;
  }

  public static void load() {
    ConfigManager.init();
  }

  public void save() {
    ConfigManager.markLiveDirty();
  }

  public static void markDirty() {
    ConfigManager.markLiveDirty();
  }

  public boolean clearRunToggles() {
    boolean changed = false;
    for (ConfigSection section : sectionsForIo()) {
      String key = RUN_TOGGLES.get(section.id());
      if (key == null) {
        continue;
      }
      JsonObject values = section.write();
      if (ConfigSection.bool(values, key, false)) {
        values.addProperty(key, false);
        section.read(values);
        changed = true;
      }
    }
    return changed;
  }

  public void read(JsonObject root) {
    JsonObject features =
        (root.get("features") instanceof JsonObject value ? value : new JsonObject());
    for (ConfigSection section : sectionsForIo()) {
      JsonObject sectionJson =
          features.get(section.id()) instanceof JsonObject value ? value : new JsonObject();
      section.read(sectionJson);
    }
  }

  public JsonObject write() {
    JsonObject features = new JsonObject();
    for (ConfigSection section : sectionsForIo()) {
      features.add(section.id(), section.write());
    }
    JsonObject root = new JsonObject();
    root.addProperty("schema_version", SCHEMA_VERSION);
    root.add("features", features);
    return root;
  }

  /**
   * Resolves live feature sections from their initialized managers while preserving JSON order.
   *
   * @description Uses the root's standalone sections for isolated config instances and manager
   *     elements for the live mod, so file I/O reads the same settings used by runtime features.
   * @return an ordered immutable list of config sections for the current settings root
   */
  public List<ConfigSection> sectionsForIo() {
    if (this != live) {
      return sections;
    }
    Map<String, ConfigSection> managed = new LinkedHashMap<>();
    collectSections(managed, RyznBootstrap.moduleManager().elements());
    collectSections(managed, RyznBootstrap.macroManager().elements());
    collectSections(managed, RyznBootstrap.failsafeManager().elements());

    List<ConfigSection> ordered = new ArrayList<>(sections.size() + managed.size());
    for (ConfigSection section : sections) {
      ConfigSection owner = managed.remove(section.id());
      ordered.add(owner == null ? section : owner);
    }
    ordered.addAll(managed.values());
    return List.copyOf(ordered);
  }

  /**
   * Adds registered element sections to the live configuration lookup.
   *
   * @description Keeps registration explicit and rejects duplicate stable section IDs.
   * @param managed the destination section map keyed by config ID
   * @param elements the initialized feature elements to inspect
   * @throws IllegalStateException when two elements claim the same section ID
   */
  private static void collectSections(
      Map<String, ConfigSection> managed, Iterable<? extends Element> elements) {
    for (Element element : elements) {
      if (element instanceof ConfigSection section
          && managed.putIfAbsent(section.id(), section) != null) {
        throw new IllegalStateException("Duplicate managed config section " + section.id());
      }
    }
  }
}
