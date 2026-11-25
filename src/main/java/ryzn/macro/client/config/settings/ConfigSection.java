package ryzn.macro.client.config.settings;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * One feature's settings group. Each implementation manually lists every setting it reads and
 * writes - no reflection, no field scanning, no automatic Gson object mapping. The parent ({@code
 * RyznSettings}) owns the JSON key this section lives under; {@link #id()} is the same name, used
 * for logging and GUI.
 */
public interface ConfigSection {
  String id();

  /** Read every owned setting from {@code object} (an empty object means "use defaults"). */
  void read(JsonObject object);

  /** Write every owned setting into a fresh {@link JsonObject}. */
  JsonObject write();

  static void readSettings(JsonObject object, Setting<?>... settings) {
    for (var setting : settings) {
      setting.read(object);
    }
  }

  static JsonObject writeSettings(Setting<?>... settings) {
    var object = new JsonObject();
    for (var setting : settings) {
      setting.write(object);
    }
    return object;
  }

  /** {@code key}'s boolean, or {@code fallback} when absent or the wrong type. */
  static boolean bool(JsonObject object, String key, boolean fallback) {
    return object.get(key) instanceof JsonPrimitive value && value.isBoolean()
        ? value.getAsBoolean()
        : fallback;
  }

  /** {@code key}'s int, or {@code fallback} when absent, the wrong type, or unparseable. */
  static int integer(JsonObject object, String key, int fallback) {
    try {
      return object.get(key) instanceof JsonPrimitive value && value.isNumber()
          ? value.getAsInt()
          : fallback;
    } catch (RuntimeException unparseable) {
      return fallback; // a malformed number must not abort the whole config load
    }
  }

  /** {@link #integer(JsonObject, String, int)} clamped into {@code [min, max]}. */
  static int integer(JsonObject object, String key, int fallback, int min, int max) {
    return Math.clamp(integer(object, key, fallback), min, max);
  }

  /** {@code key}'s string, or {@code fallback} when absent or the wrong type. */
  static String string(JsonObject object, String key, String fallback) {
    return object.get(key) instanceof JsonPrimitive value && value.isString()
        ? value.getAsString()
        : fallback;
  }
}
