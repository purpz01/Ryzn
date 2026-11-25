package ryzn.macro.client.config.groups;

import static ryzn.macro.client.config.settings.ConfigSection.integer;

import com.google.gson.JsonObject;
import java.util.EnumMap;
import java.util.Locale;

/** User preferences for route following. Terrain and release checks remain unconditional. */
public final class WalkingSettings {
  public enum Control {
    PREVIEW("Movement preview", 1, 8, 4, " blocks"),
    SPEED_PREVIEW("Scale preview with speed", 0, 1, 1, ""),
    STRAFE_ENGAGE("Strafe engage angle", 0, 45, 0, "°"),
    STRAFE_RELEASE("Strafe release", 10, 95, 65, "% of engage"),
    GAZE_LEAD("Gaze look-ahead", 50, 150, 100, "%"),
    PITCH_BIAS("Route pitch bias", -15, 15, 0, "°"),
    SWIPES("Hold gaze during a turn", 0, 1, 0, ""),
    SWIPE_ANGLE("Gaze hold trigger", 5, 90, 25, "°"),
    SWIPE_TOLERANCE("Gaze hold settle angle", 1, 15, 4, "°"),
    SWIPE_DURATION("Maximum gaze hold", 2, 16, 8, " ticks"),
    YAW_CAP("Route yaw cap (0 = inherit)", 0, 720, 0, "°/s"),
    PITCH_CAP("Route pitch cap (0 = inherit)", 0, 720, 0, "°/s"),
    ARRIVAL_COAST("Coast to final point", 0, 1, 1, ""),
    DROP_LEAD("Drop coast lead", 50, 150, 100, "%"),
    RECOVERY_HOP("Allow verified obstacle hops", 0, 1, 1, ""),
    STALL_TIME("Ground stall timeout", 500, 5000, 1500, " ms"),
    REPLAN_LIMIT("Recovery attempts at one spot", 1, 8, 6, ""),
    TRACE("Record local walking trace", 0, 1, 0, "");

    public final String label;
    public final int min;
    public final int max;
    public final int initial;
    public final String unit;

    Control(String label, int min, int max, int initial, String unit) {
      this.label = label;
      this.min = min;
      this.max = max;
      this.initial = initial;
      this.unit = unit;
    }

    String key() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  private final EnumMap<Control, Integer> values = new EnumMap<>(Control.class);

  public int get(Control control) {
    return values.getOrDefault(control, control.initial);
  }

  public boolean enabled(Control control) {
    return get(control) != 0;
  }

  public void set(Control control, int value) {
    values.put(control, Math.clamp(value, control.min, control.max));
  }

  public void reset() {
    values.clear();
  }

  void read(JsonObject object) {
    reset();
    for (Control control : Control.values()) {
      set(control, integer(object, control.key(), control.initial, control.min, control.max));
    }
  }

  JsonObject write() {
    var object = new JsonObject();
    for (Control control : Control.values()) {
      object.addProperty(control.key(), get(control));
    }
    return object;
  }
}
