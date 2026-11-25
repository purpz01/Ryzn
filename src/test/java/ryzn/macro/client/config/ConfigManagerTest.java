package ryzn.macro.client.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigManagerTest {
  @Test
  void saveAndResetApplyToTheWholeConfig(@TempDir Path directory) {
    Path file = directory.resolve("ryzn.json");
    var manager = new ConfigManager(file);
    var settings = manager.settings();
    var defaults = settings.write();
    settings.pathfinder().setSprint(!settings.pathfinder().isSprint());
    settings.wheat().setSweepTrackingPercent(200);
    settings.ryznMenu().setOpenSections(List.of());
    settings.ryznMenu().setLevelExpanded(true);
    settings.ryznMenu().setWheatExpanded(true);
    assertTrue(manager.saveNow());

    var loaded = new ConfigManager(file);
    loaded.load();
    assertEquals(List.of(), loaded.settings().ryznMenu().openSections());
    assertTrue(loaded.settings().ryznMenu().isLevelExpanded());
    assertTrue(loaded.settings().ryznMenu().isWheatExpanded());
    assertEquals(settings.write(), loaded.settings().write());

    assertTrue(manager.resetToDefaults());
    loaded.load();
    assertEquals(defaults, settings.write());
    assertEquals(defaults, loaded.settings().write());
  }
}
