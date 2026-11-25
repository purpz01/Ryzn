package ryzn.macro.client.feature.modules.impl.combat;

/** Movement fit from the Dwarven Mines, Hub and End recorder sessions. */
public final class RecordedCombatMovement {
  public static final double ENGAGE_RANGE = 8.0;
  public static final double ENGAGE_EXIT_RANGE = 8.75;
  public static final double STOP_RANGE = 3.0;
  public static final double RESUME_RANGE = 3.25;
  public static final double AIR_LANDING_RANGE = 1.5;
  public static final double DIRECT_DROP_MAX = 1.5;
  public static final double STRAFE_DEADZONE_DEG = 22.5;
  public static final double PATH_STRAFE_DEADZONE_DEG = 8.0;
  public static final double SPRINT_RANGE = 6.0;
  public static final float AIM_SPEED_CAP = 950f;
  public static final float AIM_PITCH_SPEED_CAP = 200f;
  public static final float AIM_SMOOTH_SECONDS = 0.07f;
  public static final float PATH_YAW_RATE_FLOOR = 6f;
  public static final float PATH_YAW_RATE_CEIL = 50f;
  public static final float PATH_PITCH_RATE_CEIL = 15f;

  private RecordedCombatMovement() {}
}
