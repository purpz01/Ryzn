package ryzn.macro.client.config.groups;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import lombok.Getter;
import lombok.Setter;
import ryzn.macro.client.config.settings.ConfigSection;

@Getter
@Setter
public final class PathfinderSettings implements ConfigSection {

  public static final int SPEED_CUSTOM = 0;
  public static final int SPEED_AUTO = 1;
  public static final int SPEED_NORMAL = 2;
  public static final int SPEED_FAST = 3;
  public static final int SPEED_EXTREME = 4;
  public static final int ROTATION_DPT_MIN = 2;
  public static final int ROTATION_DPT_MAX = 90;
  private static final int MS_PER_TICK = 50;

  /**
   * Floor for the built-in (confirmed) click pacing - fast enough to feel snappy, slow enough to
   * look human.
   */
  public static final int DYNAMIC_DELAY_MIN_MS = 250;

  public static final int DYNAMIC_DELAY_MAX_MS = 400;
  public static final int GUI_DELAY_MAX_MS = 2000;
  public static final int ROTATION_AMOUNT_MIN = 0;
  public static final int ROTATION_AMOUNT_MAX = 180;
  public static final int STRING_PULL_MIN = 10;
  public static final int STRING_PULL_MAX = 100;

  /**
   * THE rotation dial, in tenths: 0 is the slowest, 10 = 1.0x (the tuned default), 50 = 5.0x. Every
   * macro head-turn speed in the mod derives from this one number via {@link #rotationGain()} - the
   * shared macro aim, the mining curve and the treechop ease. There used to be a separate slider
   * for each, which is why turn speed felt different from one macro to the next.
   */
  public static final int MACRO_ROT_SPEED_MIN = 0;

  public static final int MACRO_ROT_SPEED_MAX = 50;
  public static final int MACRO_ROT_SPEED_DEFAULT = 10;
  public static final int TRANSMISSION_AIM_DELAY_MIN_TICKS = 0;
  public static final int TRANSMISSION_AIM_DELAY_MAX_TICKS = 20;
  public static final int TRANSMISSION_WAIT_MIN_TENTHS = 0;
  public static final int TRANSMISSION_WAIT_MAX_TENTHS = 100;
  public static final int TRANSMISSION_END_BUFFER_MIN = 0;
  public static final int TRANSMISSION_END_BUFFER_MAX = 20;
  public static final int TRANSMISSION_SKIP_CHANCE_MAX = 75;

  // Locked tuning: laziness, strictness, speed mode, custom speed, string pull, strafe blend and
  // fly
  // smoothness are neither shown nor saved. These defaults are the tuned values; only the sim
  // harness and tests change them, in-process.
  private int laziness = 3;
  private int nodeStrictness = 4;
  private boolean sprint = true;
  private boolean constantJumping = true;
  private int pathSpeedMode = SPEED_AUTO;
  private int pathCustomSpeed = 30;
  private int rotationMinDegPerTick = 12;
  private int rotationMaxDegPerTick = 90;
  private int rotationAmountMin = 0;
  private int rotationAmountMax = 180;
  private int stringPull = 49;
  private int macroRotationSpeed = MACRO_ROT_SPEED_DEFAULT;
  public static final int STRAFE_BLEND_MIN = 1;
  public static final int STRAFE_BLEND_MAX = 50;
  public static final int STRAFE_BLEND_DEFAULT = 25;

  private boolean humanizeRotation = false;
  private int humanizeStrength = 50;

  /**
   * Percent of the forward/strafe split a movement axis must claim before its key is held. 25
   * reproduces the 67.5-degree cone this replaced exactly; lower widens the diagonal band so the
   * body blends a diagonal rather than chattering keys as the bearing crosses a cone edge. See
   * KeyBindUtil.
   */
  private int strafeBlend = STRAFE_BLEND_DEFAULT;

  private boolean pathfindQuantizeRotation = false;
  private boolean pathfindSmoothLook = true;
  private boolean pathDebugKeepAfterFinish = false;

  /**
   * Draw the whole route the moment it resolves, instead of laying it down ahead of the player as
   * they walk.
   */
  private boolean pathDrawInstant = true;

  /**
   * Old style: a glassy box per turn point linked by straight chords, instead of one smooth curve.
   */
  private boolean pathDrawBoxes = false;

  /** Debug output goes to logs/latest.log only, not chat. Per-tick trace lines bury real chat. */
  private boolean pathDebugLogOnly = false;

  private boolean guiActionDelayCustom = false;
  private int guiActionDelayMinMs = 100;
  private int guiActionDelayMaxMs = 300;
  private int lookahead = 3;
  private int gazeDivergence = 45;
  private int flyNodeSmoothness = 3;
  private boolean transmissionEnabled = false;
  private int transmissionAimDelayTicks = 2;
  private int transmissionWaitMinTenths = 20;
  private int transmissionWaitMaxTenths = 40;
  private int transmissionEndBufferBlocks = 8;
  private int transmissionSkipChancePercent = 25;

  @Override
  public String id() {
    return "pathfinder";
  }

  private final WalkingSettings walking = new WalkingSettings();

  public WalkingSettings walking() {
    return walking;
  }

  @Override
  public void read(JsonObject object) {
    walking.read(object.get("walking") instanceof JsonObject group ? group : new JsonObject());
    sprint =
        (object.get("sprint") instanceof JsonPrimitive value && value.isBoolean()
            ? value.getAsBoolean()
            : true);
    constantJumping =
        object.get("constant_jumping") instanceof JsonPrimitive value && value.isBoolean()
            ? value.getAsBoolean()
            : true;
    rotationMinDegPerTick =
        (object.get("rotation_min_deg_per_tick") instanceof JsonPrimitive value && value.isNumber()
            ? value.getAsInt()
            : 12);
    rotationMaxDegPerTick =
        (object.get("rotation_max_deg_per_tick") instanceof JsonPrimitive value && value.isNumber()
            ? value.getAsInt()
            : 90);
    rotationAmountMin =
        (object.get("rotation_amount_min") instanceof JsonPrimitive value && value.isNumber()
            ? value.getAsInt()
            : 0);
    rotationAmountMax =
        (object.get("rotation_amount_max") instanceof JsonPrimitive value && value.isNumber()
            ? value.getAsInt()
            : 180);
    macroRotationSpeed =
        (object.get("macro_rotation_speed") instanceof JsonPrimitive value && value.isNumber()
            ? value.getAsInt()
            : MACRO_ROT_SPEED_DEFAULT);
    humanizeRotation =
        (object.get("humanize_rotation") instanceof JsonPrimitive value && value.isBoolean()
            ? value.getAsBoolean()
            : false);
    humanizeStrength =
        (object.get("humanize_strength") instanceof JsonPrimitive value && value.isNumber()
            ? value.getAsInt()
            : 50);
    pathfindQuantizeRotation =
        (object.get("pathfind_quantize_rotation") instanceof JsonPrimitive value
                && value.isBoolean()
            ? value.getAsBoolean()
            : false);
    pathfindSmoothLook =
        (object.get("pathfind_smooth_look") instanceof JsonPrimitive value && value.isBoolean()
            ? value.getAsBoolean()
            : true);
    pathDebugKeepAfterFinish =
        (object.get("path_debug_keep_after_finish") instanceof JsonPrimitive value
                && value.isBoolean()
            ? value.getAsBoolean()
            : false);
    pathDrawInstant =
        (object.get("path_draw_instant") instanceof JsonPrimitive value && value.isBoolean()
            ? value.getAsBoolean()
            : true);
    pathDrawBoxes =
        (object.get("path_draw_boxes") instanceof JsonPrimitive value
            && value.isBoolean()
            && value.getAsBoolean());
    pathDebugLogOnly =
        (object.get("path_debug_log_only") instanceof JsonPrimitive value && value.isBoolean()
            ? value.getAsBoolean()
            : false);
    guiActionDelayCustom =
        (object.get("gui_action_delay_custom") instanceof JsonPrimitive value
            && value.isBoolean()
            && value.getAsBoolean());
    // The range used to be stored in ticks; carry an older config over so the slider does not
    // reset.
    guiActionDelayMinMs =
        readDelayMs(object, "gui_action_delay_min_ms", "gui_action_delay_min_ticks", 100);
    guiActionDelayMaxMs =
        readDelayMs(object, "gui_action_delay_max_ms", "gui_action_delay_max_ticks", 300);
    lookahead =
        (object.get("lookahead") instanceof JsonPrimitive value && value.isNumber()
            ? value.getAsInt()
            : 3);
    gazeDivergence =
        (object.get("gaze_divergence") instanceof JsonPrimitive value && value.isNumber()
            ? value.getAsInt()
            : 45);
    transmissionEnabled =
        object.get("transmission_enabled") instanceof JsonPrimitive value
            && value.isBoolean()
            && value.getAsBoolean();
    transmissionAimDelayTicks =
        object.get("transmission_aim_delay_ticks") instanceof JsonPrimitive value
                && value.isNumber()
            ? value.getAsInt()
            : 2;
    transmissionWaitMinTenths =
        object.get("transmission_wait_min_tenths") instanceof JsonPrimitive value
                && value.isNumber()
            ? value.getAsInt()
            : 20;
    transmissionWaitMaxTenths =
        object.get("transmission_wait_max_tenths") instanceof JsonPrimitive value
                && value.isNumber()
            ? value.getAsInt()
            : 40;
    transmissionEndBufferBlocks =
        object.get("transmission_end_buffer_blocks") instanceof JsonPrimitive value
                && value.isNumber()
            ? value.getAsInt()
            : 8;
    transmissionSkipChancePercent =
        object.get("transmission_skip_chance_percent") instanceof JsonPrimitive value
                && value.isNumber()
            ? value.getAsInt()
            : 25;
    normalize();
  }

  @Override
  public JsonObject write() {
    normalize();
    JsonObject object = new JsonObject();
    object.add("walking", walking.write());
    object.addProperty("sprint", sprint);
    object.addProperty("constant_jumping", constantJumping);
    object.addProperty("rotation_min_deg_per_tick", rotationMinDegPerTick);
    object.addProperty("rotation_max_deg_per_tick", rotationMaxDegPerTick);
    object.addProperty("rotation_amount_min", rotationAmountMin);
    object.addProperty("rotation_amount_max", rotationAmountMax);
    object.addProperty("macro_rotation_speed", macroRotationSpeed);
    object.addProperty("humanize_rotation", humanizeRotation);
    object.addProperty("humanize_strength", humanizeStrength);
    object.addProperty("pathfind_quantize_rotation", pathfindQuantizeRotation);
    object.addProperty("pathfind_smooth_look", pathfindSmoothLook);
    object.addProperty("path_debug_keep_after_finish", pathDebugKeepAfterFinish);
    object.addProperty("path_draw_instant", pathDrawInstant);
    object.addProperty("path_draw_boxes", pathDrawBoxes);
    object.addProperty("path_debug_log_only", pathDebugLogOnly);
    object.addProperty("gui_action_delay_custom", guiActionDelayCustom);
    object.addProperty("gui_action_delay_min_ms", guiActionDelayMinMs);
    object.addProperty("gui_action_delay_max_ms", guiActionDelayMaxMs);
    object.addProperty("lookahead", lookahead);
    object.addProperty("gaze_divergence", gazeDivergence);
    object.addProperty("transmission_enabled", transmissionEnabled);
    object.addProperty("transmission_aim_delay_ticks", transmissionAimDelayTicks);
    object.addProperty("transmission_wait_min_tenths", transmissionWaitMinTenths);
    object.addProperty("transmission_wait_max_tenths", transmissionWaitMaxTenths);
    object.addProperty("transmission_end_buffer_blocks", transmissionEndBufferBlocks);
    object.addProperty("transmission_skip_chance_percent", transmissionSkipChancePercent);
    return object;
  }

  /**
   * The dial as a speed multiplier. Deliberately not linear to zero: a true 0 would freeze the head
   * rather than turn it slowly, so 0.0 maps to a third speed, and 1.0 is the tuned baseline. Read
   * live everywhere - macros hold their motion in static fields, so a value captured once at
   * construction would need a game restart to take effect.
   */
  public float rotationGain() {
    final float speed = macroRotationSpeed / 10f;
    return speed <= 1f ? 0.35f + 0.65f * speed : speed;
  }

  /**
   * 0..1 multiplier on the path steering (yaw) rate. Lower = looser head pull onto the line, so the
   * strafe net does more of the work holding the body on the path. 100% (1.0) is the original tight
   * steering.
   */
  public float stringPullFactor() {
    return stringPull / 100f;
  }

  /** 0.01..0.50 threshold on each movement axis; see {@link #strafeBlend}. */
  public double strafeBlendThreshold() {
    return strafeBlend / 100.0;
  }

  public int strafeBlend() {
    return strafeBlend;
  }

  public void setGuiActionDelayMinMs(int v) {
    guiActionDelayMinMs = Math.clamp(v, 0, GUI_DELAY_MAX_MS);
    if (guiActionDelayMaxMs < guiActionDelayMinMs) {
      guiActionDelayMaxMs = guiActionDelayMinMs;
    }
  }

  public void setGuiActionDelayMaxMs(int v) {
    guiActionDelayMaxMs = Math.clamp(v, 0, GUI_DELAY_MAX_MS);
    if (guiActionDelayMinMs > guiActionDelayMaxMs) {
      guiActionDelayMinMs = guiActionDelayMaxMs;
    }
  }

  /**
   * The wait actually used between menu clicks, in ticks. Macros confirm every click against what
   * the server sends back, so this is only a human-like floor, not the thing correctness rides on -
   * hence the {@link #DYNAMIC_DELAY_MIN_MS} default. The custom range replaces it only when the
   * user opts in.
   */
  public int getGuiActionDelayMinTicks() {
    return msToTicks(guiActionDelayCustom ? guiActionDelayMinMs : DYNAMIC_DELAY_MIN_MS);
  }

  public int getGuiActionDelayMaxTicks() {
    return msToTicks(guiActionDelayCustom ? guiActionDelayMaxMs : DYNAMIC_DELAY_MAX_MS);
  }

  private static int msToTicks(int ms) {
    return Math.max(0, Math.round(ms / (float) MS_PER_TICK));
  }

  public double heuristicWeight() {
    return switch (laziness) {
      case 1 -> 1.0;
      case 2 -> 1.4;
      case 3 -> 1.8;
      case 4 -> 2.3;
      default -> 3.0;
    };
  }

  public int waypointGapBlocks() {
    switch (pathSpeedMode) {
      case SPEED_NORMAL:
        return 3; // vanilla speed
      case SPEED_FAST:
        return 2; // potion speed
      case SPEED_EXTREME:
        return 1; // plugin-boosted: every block, no corner-cutting
      case SPEED_AUTO:
        return 2; // lean dense; the executor adapts the rest from velocity
      default:
        return Math.max(1, 6 - nodeStrictness); // SPEED_CUSTOM: level 1 -> 5, level 5 -> 1
    }
  }

  private void normalize() {
    laziness = Math.clamp(laziness, 1, 5);
    nodeStrictness = Math.clamp(nodeStrictness, 1, 5);
    pathSpeedMode = Math.clamp(pathSpeedMode, SPEED_CUSTOM, SPEED_EXTREME);
    pathCustomSpeed = Math.clamp(pathCustomSpeed, 1, 100);
    rotationMinDegPerTick =
        Math.clamp(rotationMinDegPerTick, ROTATION_DPT_MIN, ROTATION_DPT_MAX - 1);
    rotationMaxDegPerTick =
        Math.clamp(rotationMaxDegPerTick, rotationMinDegPerTick + 1, ROTATION_DPT_MAX);
    rotationAmountMin = Math.clamp(rotationAmountMin, ROTATION_AMOUNT_MIN, ROTATION_AMOUNT_MAX - 1);
    rotationAmountMax = Math.clamp(rotationAmountMax, rotationAmountMin + 1, ROTATION_AMOUNT_MAX);
    stringPull = Math.clamp(stringPull, STRING_PULL_MIN, STRING_PULL_MAX);
    macroRotationSpeed = Math.clamp(macroRotationSpeed, MACRO_ROT_SPEED_MIN, MACRO_ROT_SPEED_MAX);
    strafeBlend = Math.clamp(strafeBlend, STRAFE_BLEND_MIN, STRAFE_BLEND_MAX);
    humanizeStrength = Math.clamp(humanizeStrength, 0, 200);
    lookahead = Math.clamp(lookahead, 1, 5);
    gazeDivergence = Math.clamp(gazeDivergence, 0, 90);
    flyNodeSmoothness = Math.clamp(flyNodeSmoothness, 1, 5);
    transmissionAimDelayTicks =
        Math.clamp(
            transmissionAimDelayTicks,
            TRANSMISSION_AIM_DELAY_MIN_TICKS,
            TRANSMISSION_AIM_DELAY_MAX_TICKS);
    transmissionWaitMinTenths =
        Math.clamp(
            transmissionWaitMinTenths, TRANSMISSION_WAIT_MIN_TENTHS, TRANSMISSION_WAIT_MAX_TENTHS);
    transmissionWaitMaxTenths =
        Math.clamp(
            transmissionWaitMaxTenths, transmissionWaitMinTenths, TRANSMISSION_WAIT_MAX_TENTHS);
    transmissionEndBufferBlocks =
        Math.clamp(
            transmissionEndBufferBlocks, TRANSMISSION_END_BUFFER_MIN, TRANSMISSION_END_BUFFER_MAX);
    transmissionSkipChancePercent =
        Math.clamp(transmissionSkipChancePercent, 0, TRANSMISSION_SKIP_CHANCE_MAX);
    guiActionDelayMinMs = Math.clamp(guiActionDelayMinMs, 0, GUI_DELAY_MAX_MS);
    guiActionDelayMaxMs = Math.clamp(guiActionDelayMaxMs, guiActionDelayMinMs, GUI_DELAY_MAX_MS);
  }

  /**
   * Reads the ms key, falling back to the retired ticks key (x50) so an existing config carries
   * over.
   */
  private static int readDelayMs(JsonObject object, String msKey, String ticksKey, int fallbackMs) {
    if (object.get(msKey) instanceof JsonPrimitive ms && ms.isNumber()) {
      return ms.getAsInt();
    }
    if (object.get(ticksKey) instanceof JsonPrimitive ticks && ticks.isNumber()) {
      return ticks.getAsInt() * MS_PER_TICK;
    }
    return fallbackMs;
  }
}
