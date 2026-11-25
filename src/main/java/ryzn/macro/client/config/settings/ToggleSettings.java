package ryzn.macro.client.config.settings;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import lombok.Getter;
import lombok.Setter;

/** A configuration section containing one persisted on/off value. */
public final class ToggleSettings implements ConfigSection {

  private final String sectionId;
  private final String key;
  private final boolean defaultValue;
  @Getter @Setter private boolean enabled;

  public ToggleSettings(String sectionId, String key, boolean defaultValue) {
    this.sectionId = sectionId;
    this.key = key;
    this.defaultValue = defaultValue;
    enabled = defaultValue;
  }

  @Override
  public String id() {
    return sectionId;
  }

  @Override
  public void read(JsonObject object) {
    enabled =
        object.get(key) instanceof JsonPrimitive value && value.isBoolean()
            ? value.getAsBoolean()
            : defaultValue;
  }

  @Override
  public JsonObject write() {
    JsonObject object = new JsonObject();
    object.addProperty(key, enabled);
    return object;
  }
}
