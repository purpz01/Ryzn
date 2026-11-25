package ryzn.macro.client.config.groups;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import ryzn.macro.client.config.groups.WalkingSettings.Control;

class WalkingSettingsTest {
  @Test
  void oldConfigsGetDefaultsAndNewValuesRoundTripWithinLimits() {
    var settings = new PathfinderSettings();
    settings.read(new JsonObject());
    assertEquals(4, settings.walking().get(Control.PREVIEW));
    settings.walking().set(Control.PREVIEW, 900);
    settings.walking().set(Control.PITCH_BIAS, -99);
    settings.walking().set(Control.SWIPES, 1);
    var loaded = new PathfinderSettings();
    loaded.read(settings.write());
    assertEquals(8, loaded.walking().get(Control.PREVIEW));
    assertEquals(-15, loaded.walking().get(Control.PITCH_BIAS));
    assertEquals(1, loaded.walking().get(Control.SWIPES));
    loaded.walking().reset();
    assertEquals(4, loaded.walking().get(Control.PREVIEW));
    assertEquals(0, loaded.walking().get(Control.SWIPES));
  }
}
