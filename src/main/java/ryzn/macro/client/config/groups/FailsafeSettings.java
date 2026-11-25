package ryzn.macro.client.config.groups;

import static ryzn.macro.client.config.settings.ConfigSection.bool;
import static ryzn.macro.client.config.settings.ConfigSection.integer;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import lombok.Getter;
import lombok.Setter;
import ryzn.macro.client.config.settings.ConfigSection;
import ryzn.macro.client.feature.failsafe.FailsafeAction;
import ryzn.macro.client.feature.failsafe.FailsafeType;

@Getter
public final class FailsafeSettings implements ConfigSection {
  @Setter private boolean enabled;
  private int additionalRandomDelayTicks = 40;

  private boolean inventorySlotEnabled = true;
  private FailsafeAction inventorySlotAction = FailsafeAction.STOP;
  private int inventorySlotDelayTicks = 40;

  private boolean unexpectedScreenEnabled = true;
  private FailsafeAction unexpectedScreenAction = FailsafeAction.STOP;
  private int unexpectedScreenDelayTicks = 40;

  private boolean ghostBlockEnabled = true;
  private FailsafeAction ghostBlockAction = FailsafeAction.STOP;
  private int ghostBlockWindowTicks = 100;
  private int ghostBlockDelayTicks = 40;

  private boolean dirtEnabled = true;
  private FailsafeAction dirtAction = FailsafeAction.STOP;
  private int dirtDelayTicks = 40;

  private boolean rotationEnabled = true;
  private FailsafeAction rotationAction = FailsafeAction.STOP;
  private int rotationYawThreshold = 10;
  private int rotationPitchThreshold = 10;
  private int rotationDelayTicks = 40;
  private int rotationWarpGraceTicks = 40;

  private boolean worldChangeEnabled = true;
  private FailsafeAction worldChangeAction = FailsafeAction.STOP;

  /**
   * Off by default: hopping costs a warp, so the user opts in rather than being surprised by it.
   */
  private boolean proximityEnabled;

  private FailsafeAction proximityAction = FailsafeAction.HOP;
  private int proximityRangeBlocks = 12;
  private int proximityDwellSeconds = 20;

  @Override
  public String id() {
    return "failsafe";
  }

  @Override
  public void read(JsonObject object) {
    enabled = bool(object, "enabled", false);
    additionalRandomDelayTicks = integer(object, "additional_random_delay_ticks", 40, 0, 100);

    inventorySlotEnabled = bool(object, "inventory_slot_enabled", true);
    inventorySlotAction = action(object, "inventory_slot_action");
    inventorySlotDelayTicks = integer(object, "inventory_slot_delay_ticks", 40, 0, 100);

    unexpectedScreenEnabled = bool(object, "unexpected_screen_enabled", true);
    unexpectedScreenAction = action(object, "unexpected_screen_action");
    unexpectedScreenDelayTicks = integer(object, "unexpected_screen_delay_ticks", 40, 0, 100);

    ghostBlockEnabled = bool(object, "ghost_block_enabled", true);
    ghostBlockAction = action(object, "ghost_block_action");
    ghostBlockWindowTicks = integer(object, "ghost_block_window_ticks", 100, 20, 600);
    ghostBlockDelayTicks = integer(object, "ghost_block_delay_ticks", 40, 0, 100);

    dirtEnabled = bool(object, "dirt_enabled", true);
    dirtAction = action(object, "dirt_action");
    dirtDelayTicks = integer(object, "dirt_delay_ticks", 40, 0, 200);

    rotationEnabled = bool(object, "rotation_enabled", true);
    rotationAction = action(object, "rotation_action");
    rotationYawThreshold = integer(object, "rotation_yaw_threshold", 10, 1, 180);
    rotationPitchThreshold = integer(object, "rotation_pitch_threshold", 10, 1, 90);
    rotationDelayTicks = integer(object, "rotation_delay_ticks", 40, 0, 100);
    rotationWarpGraceTicks = integer(object, "rotation_warp_grace_ticks", 40, 0, 200);

    worldChangeEnabled = bool(object, "world_change_enabled", true);
    worldChangeAction = action(object, "world_change_action");

    proximityEnabled = bool(object, "proximity_enabled", false);
    proximityAction = action(object, "proximity_action", FailsafeAction.HOP);
    proximityRangeBlocks = integer(object, "proximity_range_blocks", 12, 1, 64);
    proximityDwellSeconds = integer(object, "proximity_dwell_seconds", 20, 1, 100);
  }

  @Override
  public JsonObject write() {
    JsonObject object = new JsonObject();
    object.addProperty("enabled", enabled);
    object.addProperty("additional_random_delay_ticks", additionalRandomDelayTicks);

    writeDetector(object, "inventory_slot", inventorySlotEnabled, inventorySlotAction);
    object.addProperty("inventory_slot_delay_ticks", inventorySlotDelayTicks);
    writeDetector(object, "unexpected_screen", unexpectedScreenEnabled, unexpectedScreenAction);
    object.addProperty("unexpected_screen_delay_ticks", unexpectedScreenDelayTicks);

    writeDetector(object, "ghost_block", ghostBlockEnabled, ghostBlockAction);
    object.addProperty("ghost_block_window_ticks", ghostBlockWindowTicks);
    object.addProperty("ghost_block_delay_ticks", ghostBlockDelayTicks);

    writeDetector(object, "dirt", dirtEnabled, dirtAction);
    object.addProperty("dirt_delay_ticks", dirtDelayTicks);

    writeDetector(object, "rotation", rotationEnabled, rotationAction);
    object.addProperty("rotation_yaw_threshold", rotationYawThreshold);
    object.addProperty("rotation_pitch_threshold", rotationPitchThreshold);
    object.addProperty("rotation_delay_ticks", rotationDelayTicks);
    object.addProperty("rotation_warp_grace_ticks", rotationWarpGraceTicks);

    writeDetector(object, "world_change", worldChangeEnabled, worldChangeAction);

    writeDetector(object, "proximity", proximityEnabled, proximityAction);
    object.addProperty("proximity_range_blocks", proximityRangeBlocks);
    object.addProperty("proximity_dwell_seconds", proximityDwellSeconds);
    return object;
  }

  public boolean isDetectorEnabled(FailsafeType type) {
    return switch (type) {
      case INVENTORY_SLOT -> inventorySlotEnabled;
      case UNEXPECTED_SCREEN -> unexpectedScreenEnabled;
      case GHOST_BLOCK -> ghostBlockEnabled;
      case DIRT -> dirtEnabled;
      case ROTATION -> rotationEnabled;
      case WORLD_CHANGE -> worldChangeEnabled;
      case PROXIMITY -> proximityEnabled;

      default -> throw new MatchException(null, null);
    };
  }

  public FailsafeAction action(FailsafeType type) {
    return switch (type) {
      case INVENTORY_SLOT -> inventorySlotAction;
      case UNEXPECTED_SCREEN -> unexpectedScreenAction;
      case GHOST_BLOCK -> ghostBlockAction;
      case DIRT -> dirtAction;
      case ROTATION -> rotationAction;
      case WORLD_CHANGE -> worldChangeAction;
      case PROXIMITY -> proximityAction;

      default -> throw new MatchException(null, null);
    };
  }

  private static FailsafeAction action(JsonObject object, String key) {
    return action(object, key, FailsafeAction.STOP);
  }

  private static FailsafeAction action(JsonObject object, String key, FailsafeAction fallback) {
    if (!(object.get(key) instanceof JsonPrimitive primitive) || !primitive.isString()) {
      return fallback;
    }
    return FailsafeAction.fromConfig(primitive.getAsString());
  }

  private static void writeDetector(
      JsonObject object, String key, boolean enabled, FailsafeAction action) {
    object.addProperty(key + "_enabled", enabled);
    object.addProperty(key + "_action", actionKey(action));
  }

  /**
   * Written out by hand rather than {@code name()}: config keys are a stable format, not enum
   * internals.
   */
  private static String actionKey(FailsafeAction action) {
    if (action == null) {
      return "STOP";
    }
    return switch (action) {
      case IGNORE -> "IGNORE";
      case HOP -> "HOP";
      case STOP -> "STOP";

      default -> throw new MatchException(null, null);
    };
  }
}
