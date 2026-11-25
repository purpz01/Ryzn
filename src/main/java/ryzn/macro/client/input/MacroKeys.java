package ryzn.macro.client.input;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.world.entity.player.Input;

/**
 * Ryzn's movement intent, held apart from the vanilla keybind flags.
 *
 * <p>Driving the body by writing {@link KeyMapping#setDown} has four defects on a real client:
 *
 * <ul>
 *   <li>{@code keyShift} and {@code keySprint} are {@code ToggleKeyMapping}. With Toggle Sneak or
 *       Toggle Sprint on, {@code setDown(true)} FLIPS the state instead of setting it and {@code
 *       setDown(false)} does nothing at all - so holding sneak every tick strobes it and it can
 *       never be released.
 *   <li>{@code Minecraft.setScreen} calls {@code KeyMapping.releaseAll()}, so any menu opening
 *       drops whatever a macro was holding, and it stays dropped after the menu closes.
 *   <li>{@code isDown} is one global flag the player, this mod, and every other mod share, so a
 *       real keypress and a macro's hold clobber each other.
 *   <li>{@code MouseHandler.grabMouse} calls {@code KeyMapping.setAll()}, re-reading the physical
 *       keyboard over the top of anything a macro had set.
 * </ul>
 *
 * Macros write here instead, and {@code KeyboardInput#tick} stamps this intent onto the {@link
 * Input} record it just built. That record is what {@code travel()} and {@code
 * ServerboundPlayerInputPacket} both read, and it is byte-identical to the one a real keypress
 * produces - so this stays inside the anti-detection rule: no packet is sent, blocked, delayed, or
 * altered.
 *
 * <p>Keys with no slot here (attack, use) are not part of the {@link Input} record and fall
 * straight through to the vanilla mapping.
 */
public final class MacroKeys {

  private static final int FORWARD = 0;
  private static final int BACKWARD = 1;
  private static final int LEFT = 2;
  private static final int RIGHT = 3;
  private static final int JUMP = 4;
  private static final int SNEAK = 5;
  private static final int SPRINT = 6;
  private static final int SLOTS = 7;

  private static final boolean[] held = new boolean[SLOTS];
  private static boolean driving;

  private MacroKeys() {}

  /** Sets one key's intent. Non-movement mappings pass through to the vanilla flag unchanged. */
  public static void set(KeyMapping mapping, boolean down) {
    final int slot = slotOf(mapping);
    if (slot < 0) {
      if (mapping != null) {
        mapping.setDown(down);
      }
      return;
    }
    held[slot] = down;
    if (down) {
      driving = true;
    } else if (!anyHeld()) {
      driving = false;
    }
  }

  /** Reads one key's intent, falling back to the vanilla flag for anything without a slot. */
  public static boolean isDown(KeyMapping mapping) {
    final int slot = slotOf(mapping);
    if (slot < 0) {
      return mapping != null && mapping.isDown();
    }
    return held[slot];
  }

  /**
   * Replaces the input vanilla just read off the keyboard, or returns it untouched when no macro is
   * driving. A menu keeps the body still exactly as it did before, by deferring to vanilla - which
   * has already released every key - rather than by clearing the intent, so the walk resumes on
   * close.
   */
  public static Input apply(Input vanilla) {
    if (!driving) {
      return vanilla;
    }
    final Minecraft mc = Minecraft.getInstance();
    if (mc == null || mc.gui.screen() != null) {
      return vanilla;
    }
    return new Input(
        held[FORWARD],
        held[BACKWARD],
        held[LEFT],
        held[RIGHT],
        held[JUMP],
        held[SNEAK],
        held[SPRINT]);
  }

  private static boolean anyHeld() {
    for (int i = 0; i < SLOTS; i++) {
      if (held[i]) {
        return true;
      }
    }
    return false;
  }

  private static int slotOf(KeyMapping mapping) {
    if (mapping == null) {
      return -1;
    }
    final Minecraft mc = Minecraft.getInstance();
    if (mc == null || mc.options == null) {
      return -1;
    }
    final Options o = mc.options;
    if (mapping == o.keyUp) {
      return FORWARD;
    }
    if (mapping == o.keyDown) {
      return BACKWARD;
    }
    if (mapping == o.keyLeft) {
      return LEFT;
    }
    if (mapping == o.keyRight) {
      return RIGHT;
    }
    if (mapping == o.keyJump) {
      return JUMP;
    }
    if (mapping == o.keyShift) {
      return SNEAK;
    }
    if (mapping == o.keySprint) {
      return SPRINT;
    }
    return -1;
  }
}
