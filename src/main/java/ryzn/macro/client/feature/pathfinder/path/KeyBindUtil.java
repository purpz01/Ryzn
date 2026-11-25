package ryzn.macro.client.feature.pathfinder.path;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;
import ryzn.macro.client.config.RyznSettings;
import ryzn.macro.client.feature.rotation.impl.AngleUtil;
import ryzn.macro.client.input.MacroKeys;

/**
 * Keybind / movement-input helpers. Rewritten to Mojang-mapped vanilla 1.21.11 types (KeyMapping,
 * Vec3, InteractionHand, hit results).
 */
public class KeyBindUtil {
  private static final Minecraft mc = Minecraft.getInstance();

  /**
   * Movement keys are chosen by decomposing the body-relative bearing into a forward and a strafe
   * component that sum to 1, then holding each axis whose component clears this threshold. Both can
   * clear at once, which is what produces a diagonal.
   *
   * <p>0.25 is algebraically the 67.5-degree acceptance cone this replaced - identical keys at
   * every bearing, verified in KeyBindUtilTest. Turning it DOWN widens the band where both axes are
   * held, so the body blends a diagonal instead of snapping between key combos as wobble carries
   * the bearing across a cone edge. That chatter is the difference in walk feel; the knob is the
   * fix.
   */
  private static final double AXIS_THRESHOLD_DEFAULT = 0.25;

  private static KeyMapping[] allKeys = new KeyMapping[0];
  private static KeyMapping[] directionKeys = new KeyMapping[0];

  public static void rightClick() {
    if (mc.gameMode != null && mc.player != null) {
      mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
    }
  }

  public static void onTick(KeyMapping key) {
    if (key != null && mc.gui.screen() == null) {
      MacroKeys.set(key, true);
    }
  }

  private static void realSetKeyBindState(KeyMapping key, boolean pressed) {
    if (key == null) {
      return;
    }
    MacroKeys.set(key, pressed);
  }

  public static void stopMovement() {
    stopMovement(false);
  }

  public static void stopMovement(boolean ignoreAttack) {
    if (mc == null || mc.options == null) {
      return;
    }
    realSetKeyBindState(mc.options.keyUp, false);
    realSetKeyBindState(mc.options.keyDown, false);
    realSetKeyBindState(mc.options.keyRight, false);
    realSetKeyBindState(mc.options.keyLeft, false);
    if (!ignoreAttack) {
      realSetKeyBindState(mc.options.keyAttack, false);
      realSetKeyBindState(mc.options.keyUse, false);
    }
    realSetKeyBindState(mc.options.keyShift, false);
    realSetKeyBindState(mc.options.keyJump, false);
    realSetKeyBindState(mc.options.keySprint, false);
  }

  public static void holdThese(boolean withAttack, KeyMapping... keyBinding) {
    releaseAllExcept(keyBinding);
    for (KeyMapping key : keyBinding) {
      if (key != null) {
        realSetKeyBindState(key, true);
      }
    }
    if (withAttack) {
      if (mc == null || mc.options == null) {
        return;
      }
      realSetKeyBindState(mc.options.keyAttack, true);
    }
  }

  public static void holdThese(KeyMapping... keyBinding) {
    releaseAllExcept(keyBinding);
    for (KeyMapping key : keyBinding) {
      if (key != null) {
        realSetKeyBindState(key, true);
      }
    }
  }

  public static void holdThese(List<KeyMapping> keyBindings) {
    releaseAllExcept(keyBindings);
    for (KeyMapping key : keyBindings) {
      if (key != null) {
        realSetKeyBindState(key, true);
      }
    }
  }

  public static void releaseAllExcept(KeyMapping... keyBinding) {
    ensureKeys();
    for (KeyMapping key : allKeys) {
      if (key != null && !contains(keyBinding, key) && MacroKeys.isDown(key)) {
        realSetKeyBindState(key, false);
      }
    }
  }

  private static void releaseAllExcept(List<KeyMapping> keyBindings) {
    ensureKeys();
    for (KeyMapping key : allKeys) {
      if (key != null && !keyBindings.contains(key) && MacroKeys.isDown(key)) {
        realSetKeyBindState(key, false);
      }
    }
  }

  public static boolean contains(KeyMapping[] keyBinding, KeyMapping key) {
    for (KeyMapping keyBind : keyBinding) {
      if (keyBind == key) {
        return true;
      }
    }
    return false;
  }

  public static List<KeyMapping> getNeededKeyPresses(Vec3 orig, Vec3 dest) {
    return getNeededKeyPresses(orig.x, orig.z, dest.x, dest.z);
  }

  public static List<KeyMapping> getNeededKeyPresses(
      double origX, double origZ, double destX, double destZ) {
    List<KeyMapping> keys = new ArrayList<>(4);
    ensureKeys();
    if (mc == null || mc.player == null || directionKeys.length == 0) {
      return keys;
    }

    final double deltaX = origX - destX;
    final double deltaZ = origZ - destZ;
    final float requiredAngle = (float) (Math.atan2(deltaX, -deltaZ) * (180.0 / Math.PI));

    final float angleDifference =
        AngleUtil.normalizeAngle(requiredAngle - mc.player.getYRot()) * -1;
    selectAxisKeys(keys, angleDifference, axisThreshold());
    return keys;
  }

  public static List<KeyMapping> getNeededKeyPresses(float neededYaw) {
    List<KeyMapping> keys = new ArrayList<>(4);
    ensureKeys();
    if (mc == null || mc.player == null || directionKeys.length == 0) {
      return keys;
    }
    neededYaw = AngleUtil.normalizeAngle(neededYaw - mc.player.getYRot()) * -1;
    selectAxisKeys(keys, neededYaw, axisThreshold());
    return keys;
  }

  private static double axisThreshold() {
    return RyznSettings.get() == null
        ? AXIS_THRESHOLD_DEFAULT
        : RyznSettings.get().pathfinder().strafeBlendThreshold();
  }

  /**
   * Fills {@code keys} with the movement keys for a body-relative bearing. Package-private and free
   * of client state so the cone-equivalence check can drive it directly.
   *
   * @param bearing body-relative bearing in (-180, 180]; 0 is straight ahead, positive is to the
   *     left
   */
  static void selectAxisKeys(List<KeyMapping> keys, float bearing, double threshold) {
    final int mask = axisMask(bearing, threshold);
    if ((mask & AXIS_FORWARD) != 0) {
      addOnce(keys, mc.options.keyUp);
    }
    if ((mask & AXIS_BACK) != 0) {
      addOnce(keys, mc.options.keyDown);
    }
    if ((mask & AXIS_LEFT) != 0) {
      addOnce(keys, mc.options.keyLeft);
    }
    if ((mask & AXIS_RIGHT) != 0) {
      addOnce(keys, mc.options.keyRight);
    }
  }

  static final int AXIS_FORWARD = 1;
  static final int AXIS_LEFT = 2;
  static final int AXIS_BACK = 4;
  static final int AXIS_RIGHT = 8;

  /**
   * The whole key decision as pure math, so it can be checked without a client. Decomposes a
   * body-relative bearing into a forward and a strafe component that sum to 1 and returns the axes
   * whose share clears {@code threshold} - both, when the bearing is diagonal enough.
   *
   * @param bearing body-relative bearing in (-180, 180]; 0 is straight ahead, positive is to the
   *     left
   */
  static int axisMask(float bearing, double threshold) {
    // Fold to 0..180 so a bearing and its mirror share one forward/strafe split; the signs below
    // put
    // the result back on the right axis. At 0 or 180 the motion is pure forward/back, at +/-90 it
    // is
    // pure strafe, and in between both axes hold a share of 1.
    final double folded = bearing < 0 ? bearing + 180.0 : bearing;
    final double forward = Math.abs(folded - 90.0) / 90.0;
    final double strafe = 1.0 - forward;

    boolean holdForward = forward > threshold;
    boolean holdStrafe = strafe > threshold;
    // The two components always sum to 1, so below a threshold of 0.5 at least one of them clears
    // and
    // this cannot fire. At 0.5 a perfect diagonal ties both at exactly 0.5 and neither would -
    // which
    // would stand the body still mid-turn - so the larger share takes the tie.
    if (!holdForward && !holdStrafe) {
      if (strafe > forward) {
        holdStrafe = true;
      } else {
        holdForward = true;
      }
    }

    int mask = 0;
    if (holdForward) {
      mask |= Math.abs(bearing) <= 90.0 ? AXIS_FORWARD : AXIS_BACK;
    }
    if (holdStrafe) {
      mask |= bearing < 0 ? AXIS_RIGHT : AXIS_LEFT;
    }
    return mask;
  }

  private static void addOnce(List<KeyMapping> keys, KeyMapping key) {
    if (key != null && !keys.contains(key)) {
      keys.add(key);
    }
  }

  private static void ensureKeys() {
    if (allKeys.length != 0 || mc == null || mc.options == null) {
      return;
    }
    allKeys =
        new KeyMapping[] {
          mc.options.keyAttack, mc.options.keyUse, mc.options.keyDown,
          mc.options.keyUp, mc.options.keyLeft, mc.options.keyRight,
          mc.options.keyJump, mc.options.keyShift, mc.options.keySprint
        };
    directionKeys =
        new KeyMapping[] {
          mc.options.keyUp, mc.options.keyLeft, mc.options.keyDown, mc.options.keyRight
        };
  }
}
