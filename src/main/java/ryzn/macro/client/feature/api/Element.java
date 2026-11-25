package ryzn.macro.client.feature.api;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import ryzn.macro.client.config.settings.Setting;

/** Identity and settings owned by one process-lifetime feature. */
public abstract class Element {
  private final String id;
  private final String name;
  private final String description;
  private final List<Setting<?>> settings = new ArrayList<>();
  private final Set<String> settingKeys = new HashSet<>();

  /**
   * @description Creates a feature with stable configuration identity and display metadata.
   * @param id the existing configuration section ID
   * @param name the display name shown in the menu
   * @param description the display description shown in the menu
   * @throws IllegalArgumentException if identity or display name is blank
   */
  protected Element(String id, String name, String description) {
    if (id == null || id.isBlank() || name == null || name.isBlank()) {
      throw new IllegalArgumentException("Feature ID and name must be nonblank");
    }
    this.id = id;
    this.name = name;
    this.description = description == null ? "" : description;
  }

  /**
   * @description Returns the stable configuration section ID.
   * @return the feature's section ID
   */
  public final String id() {
    return id;
  }

  /**
   * @description Returns the menu display name.
   * @return the feature's display name
   */
  public final String name() {
    return name;
  }

  /**
   * @description Returns the menu display description.
   * @return the feature's display description
   */
  public final String description() {
    return description;
  }

  /**
   * @description Attaches one typed setting while preserving its menu and JSON order.
   * @param setting the setting owned by this element
   * @param <T> the setting value type
   * @return the same setting for assignment to an element field
   * @throws IllegalArgumentException if this element already owns the setting key
   */
  protected final <T> Setting<T> addSetting(Setting<T> setting) {
    if (!settingKeys.add(setting.key())) {
      throw new IllegalArgumentException("Duplicate setting " + id + "." + setting.key());
    }
    settings.add(setting);
    return setting;
  }

  protected final void readSettings(JsonObject object) {
    for (Setting<?> setting : settings) {
      setting.read(object);
    }
  }

  protected final JsonObject writeSettings() {
    JsonObject object = new JsonObject();
    for (Setting<?> setting : settings) {
      setting.write(object);
    }
    return object;
  }
}
