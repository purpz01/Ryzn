package ryzn.macro.client.config.groups;

import static ryzn.macro.client.config.settings.ConfigSection.bool;
import static ryzn.macro.client.config.settings.ConfigSection.integer;

import com.google.gson.JsonObject;
import lombok.Getter;
import lombok.Setter;
import ryzn.macro.client.config.settings.ConfigSection;
import ryzn.macro.client.feature.rotation.impl.abcurves.AbcRotation;

/** Saved settings for the ABCurves camera planner. */
@Getter
public final class RotationSettings implements ConfigSection {
  public static final int MAX_SEED = 99;
  @Setter private boolean abcEnabled = true;
  private int abcSeed = 7;
  public static final int MAX_RATE = 720;
  public static final int MAX_DAMPING_MS = 250;
  public static final int MAX_DEADZONE_TENTHS = 20;
  @Setter private boolean randomSeed = true;
  @Setter private boolean limitOvershoot;
  @Setter private boolean quantizeOutput = true;
  private int yawCap;
  private int pitchCap;
  private int dampingMillis;
  private int deadzoneTenths;
  private JsonObject backup;

  public AbcRotation.Tuning tuning() {
    return new AbcRotation.Tuning(
        randomSeed,
        yawCap,
        pitchCap,
        dampingMillis,
        deadzoneTenths / 10.0,
        limitOvershoot,
        quantizeOutput);
  }

  public void setYawCap(int value) {
    yawCap = Math.clamp(value, 0, MAX_RATE);
  }

  public void setPitchCap(int value) {
    pitchCap = Math.clamp(value, 0, MAX_RATE);
  }

  public void setDampingMillis(int value) {
    dampingMillis = Math.clamp(value, 0, MAX_DAMPING_MS);
  }

  public void setDeadzoneTenths(int value) {
    deadzoneTenths = Math.clamp(value, 0, MAX_DEADZONE_TENTHS);
  }

  @Override
  public String id() {
    return "rotations";
  }

  public void setAbcSeed(int value) {
    abcSeed = Math.clamp(value, 0, MAX_SEED);
  }

  @Override
  public void read(JsonObject object) {
    abcEnabled = bool(object, "abcurves", true);
    abcSeed = integer(object, "abcurves_seed", 7, 0, MAX_SEED);
    randomSeed = bool(object, "random_seed", true);
    limitOvershoot = bool(object, "limit_overshoot", false);
    quantizeOutput = bool(object, "quantize_output", true);
    setYawCap(integer(object, "yaw_cap", 0, 0, MAX_RATE));
    setPitchCap(integer(object, "pitch_cap", 0, 0, MAX_RATE));
    setDampingMillis(integer(object, "damping_ms", 0, 0, MAX_DAMPING_MS));
    setDeadzoneTenths(integer(object, "deadzone_tenths", 0, 0, MAX_DEADZONE_TENTHS));
    backup = object.get("legacy_settings") instanceof JsonObject legacy ? legacy.deepCopy() : null;
    if (backup == null
        && (object.has("humanized") || object.has("general_min") || object.has("presets"))) {
      backup = object.deepCopy();
      backup.remove("abcurves");
      backup.remove("abcurves_seed");
      backup.remove("version");
      backup.remove("legacy_settings");
    }
  }

  @Override
  public JsonObject write() {
    var object = new JsonObject();
    object.addProperty("version", 2);
    object.addProperty("abcurves", abcEnabled);
    object.addProperty("abcurves_seed", abcSeed);
    object.addProperty("random_seed", randomSeed);
    object.addProperty("limit_overshoot", limitOvershoot);
    object.addProperty("quantize_output", quantizeOutput);
    object.addProperty("yaw_cap", yawCap);
    object.addProperty("pitch_cap", pitchCap);
    object.addProperty("damping_ms", dampingMillis);
    object.addProperty("deadzone_tenths", deadzoneTenths);
    if (backup != null) {
      object.add("legacy_settings", backup.deepCopy());
    }
    return object;
  }
}
