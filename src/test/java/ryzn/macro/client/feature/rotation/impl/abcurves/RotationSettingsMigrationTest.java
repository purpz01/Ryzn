package ryzn.macro.client.feature.rotation.impl.abcurves;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import ryzn.macro.client.config.groups.RotationSettings;

final class RotationSettingsMigrationTest {
  @Test
  void backsUpLegacyControlsAndRoundTripsTheNewControls() {
    var legacy =
        JsonParser.parseString(
                """
        {"humanized":false,"general_min":144,"presets":[{"name":"walk","values":{"human_swipes":false}}]}
        """)
            .getAsJsonObject();
    var settings = new RotationSettings();
    settings.read(legacy);
    assertTrue(settings.isAbcEnabled());
    assertEquals(7, settings.getAbcSeed());
    assertEquals(legacy, settings.write().getAsJsonObject("legacy_settings"));
    assertFalse(settings.write().has("humanized"));
    settings.setAbcEnabled(false);
    settings.setAbcSeed(42);
    settings.setRandomSeed(false);
    settings.setYawCap(180);
    settings.setPitchCap(90);
    settings.setDampingMillis(48);
    settings.setDeadzoneTenths(3);
    settings.setLimitOvershoot(true);
    settings.setQuantizeOutput(false);
    var reread = new RotationSettings();
    reread.read(settings.write());
    assertFalse(reread.isAbcEnabled());
    assertEquals(42, reread.getAbcSeed());
    assertEquals(settings.tuning(), reread.tuning());
    assertEquals(settings.write(), reread.write());
  }
}
