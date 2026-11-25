package ryzn.macro.client.feature.macros.impl.foraging;

import com.google.gson.JsonObject;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import ryzn.macro.client.config.RyznSettings;
import ryzn.macro.client.config.settings.ConfigSection;
import ryzn.macro.client.config.settings.Setting;
import ryzn.macro.client.core.events.api.AutoHarpActionEvent;
import ryzn.macro.client.core.events.api.Subscribe;
import ryzn.macro.client.debug.DebugLog;
import ryzn.macro.client.debug.MacroStateTrace;
import ryzn.macro.client.feature.macros.api.Macro;
import ryzn.macro.client.util.gui.ScreenOwner;
import ryzn.macro.client.util.inventory.MacroSlots;
import ryzn.macro.client.util.text.RyznChat;

/** Plays each harp pad once when it turns white, with a THROW click on that slot. */
public final class AutoHarpMacro extends Macro implements ConfigSection {

  private static final int[] noteSlots = {37, 38, 39, 40, 41, 42, 43};

  /** Every row of the grid, top first. The last one is the pad row. */
  private static final int[] gridRowStarts = {1, 10, 19, 28, 37};

  /** Index of the pad row, which is also how many beats a note in the top row is from landing. */
  private static final int PAD_ROW = gridRowStarts.length - 1;

  private static final String GUI_NEEDLE = "Harp - ";

  /** Saved run toggle shared by config and the live macro. */
  private final Setting<Boolean> enabled =
      addSetting(Setting.bool("auto_harp_enabled", "Enabled", false));

  private String status = "Disabled";

  private final HarpPadState[] pads = new HarpPadState[noteSlots.length];
  private AbstractContainerMenu activeMenu;
  private final MacroStateTrace trace = new MacroStateTrace("harp");
  private final HarpTiming timing = new HarpTiming();

  /**
   * Every distinct grid item seen this session, so the debug log carries a legend of the real ids.
   */
  private final Set<String> seenItems = new HashSet<>();

  /**
   * @description Creates the process-lifetime harp macro and its original run-toggle setting.
   */
  public AutoHarpMacro() {
    super("auto_harp", "Auto Harp", "Plays each white harp pad as it lands.");
    resetNotes();
  }

  /**
   * @description Loads the saved run toggle under its original key and resets transient note state.
   * @param object the harp section, with a missing toggle treated as disabled
   */
  @Override
  public void read(JsonObject object) {
    enabled.read(object);
    resetNotes();
    status = isEnabled() ? "Waiting for Harp" : "Disabled";
  }

  /**
   * @description Writes the original run-toggle key.
   * @return a new harp config section
   */
  @Override
  public JsonObject write() {
    JsonObject object = new JsonObject();
    enabled.write(object);
    return object;
  }

  /**
   * @description Reports whether the harp macro is running.
   * @return the saved run toggle
   */
  public boolean isEnabled() {
    return enabled.get();
  }

  /**
   * @description Changes the run toggle, announces transitions, and clears pending notes.
   * @param on whether to drive the harp
   */
  public void setEnabled(boolean on) {
    if (isEnabled() != on) {
      RyznChat.announce("Auto Harp", on);
    }
    enabled.set(on);
    resetNotes();
    status = on ? "Waiting for Harp" : "Disabled";
    persist();
  }

  /**
   * @description Applies a run-state request published by another feature or menu action.
   * @param autoharpactionevent the requested enabled state
   */
  @Subscribe
  public void onAutoHarpActionEvent(AutoHarpActionEvent autoharpactionevent) {
    setEnabled(autoharpactionevent.enabled());
  }

  /**
   * @description Returns the current player-facing harp status.
   * @return the live status or Disabled when the macro is off
   */
  public String statusText() {
    return isEnabled() ? status : "Disabled";
  }

  /**
   * @description Decides whether the harp status belongs on the current HUD.
   * @param mc the current client with its player and screen
   * @return true while enabled in world or viewing the harp screen
   */
  public boolean shouldRenderHud(Minecraft mc) {
    if (!isEnabled() || mc == null || mc.player == null) {
      return false;
    }
    if (mc.gui.screen() == null) {
      return true;
    }
    return mc.gui.screen() instanceof AbstractContainerScreen<?> screen && isHarpScreen(screen);
  }

  /**
   * @description Tracks the harp screen and processes notes each client tick while enabled.
   * @param mc the current client state
   */
  public void onClientTick(Minecraft mc) {
    trace.logStatus(status);
    if (!isEnabled()) {
      return;
    }

    final LocalPlayer player = mc.player;
    if (player == null || mc.gameMode == null) {
      resetNotes();
      return;
    }
    if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> screen) || !isHarpScreen(screen)) {
      resetNotes();
      status = "Waiting for Harp";
      return;
    }

    final AbstractContainerMenu menu = screen.getMenu();
    if (menu == null) {
      return;
    }
    status = "Playing";
    // A song is minutes of one screen with only note clicks in it, so the claim from the click that
    // opened it runs out mid-song and the failsafe reads the harp as the player's. We are driving
    // it.
    ScreenOwner.macroActed();

    onNotesUpdated(mc);
  }

  /**
   * @description Scans harp notes on a render frame to catch updates between ticks.
   */
  public void onRenderFrame() {
    onNotesUpdated(Minecraft.getInstance());
  }

  /**
   * @description Detects ready white pads after menu updates and presses each once per beat.
   * @param mc the client containing the active harp menu
   */
  public void onNotesUpdated(Minecraft mc) {
    if (!isEnabled() || mc == null || mc.player == null || mc.gameMode == null) {
      return;
    }
    if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> screen) || !isHarpScreen(screen)) {
      return;
    }
    AbstractContainerMenu menu = screen.getMenu();
    if (menu != mc.player.containerMenu || !menu.getCarried().isEmpty()) {
      return;
    }
    if (activeMenu != menu) {
      resetNotes();
      activeMenu = menu;
    }
    if (timing.observe(notePattern(menu))) {
      logItemLegend(menu);
    }
    long beat = timing.beatIndex();
    for (int column = 0; column < noteSlots.length; column++) {
      String item = registryPath(itemAt(menu, noteSlots[column]));
      if (!pads[column].ready(item, beat)) {
        continue;
      }
      boolean nextNote = isNote(itemAt(menu, gridRowStarts[PAD_ROW - 1] + column));
      if (dropOnPad(mc, screen, noteSlots[column])) {
        pads[column].fired(beat, nextNote);
      }
    }
  }

  /**
   * @description Identifies the white block used by a playable harp pad.
   * @param item the item registry path
   * @return true for the white pad item
   */
  static boolean isWhitePad(String item) {
    return item.equals("quartz_block");
  }

  /**
   * @description Identifies a resting pad color that permits the next note to fire.
   * @param item the item registry path
   * @return true for a terracotta pad
   */
  static boolean shouldRearm(String item) {
    // Drop locally empties the pad. Only its resting colour rearms the next note.
    return item.endsWith("_terracotta");
  }

  /**
   * @description Sends the same THROW click the Drop key makes over a pad, without the repeat guard
   *     since a pad is re-clicked while its contents look unchanged.
   * @param mc the current client
   * @param screen the active harp container screen
   * @param slotIndex the target pad slot
   * @return true when the click was sent
   */
  private boolean dropOnPad(Minecraft mc, AbstractContainerScreen<?> screen, int slotIndex) {
    AbstractContainerMenu menu = screen.getMenu();
    if (menu.getSlot(slotIndex).container == mc.player.getInventory()) {
      return false;
    }
    MacroSlots.clickSlotUnguarded(mc, menu, slotIndex, 0, ContainerInput.THROW, mc.player);
    status = "Playing";
    return true;
  }

  // ---- grid reading ----

  /**
   * @description Distinguishes falling wool notes from empty colored panes.
   * @param stack the item shown in one harp grid slot
   * @return true when the item is a wool note
   */
  private static boolean isNote(ItemStack stack) {
    return registryPath(stack).contains("wool");
  }

  /**
   * @description Encodes falling notes row by row while excluding the pad row we change ourselves.
   * @param menu the active harp container menu
   * @return a slash-separated grid pattern of wool notes and empty lanes
   */
  private static String notePattern(AbstractContainerMenu menu) {
    final StringBuilder out = new StringBuilder(PAD_ROW * (noteSlots.length + 1));
    for (int row = 0; row < PAD_ROW; row++) {
      if (row > 0) {
        out.append('/');
      }
      for (int column = 0; column < noteSlots.length; column++) {
        out.append(isNote(itemAt(menu, gridRowStarts[row] + column)) ? 'w' : '.');
      }
    }
    return out.toString();
  }

  /**
   * @description Logs each distinct grid item once so item changes can be diagnosed later.
   * @param menu the active harp menu to inspect
   */
  private void logItemLegend(AbstractContainerMenu menu) {
    if (!DebugLog.isEnabled(DebugLog.Category.MACRO)) {
      return;
    }
    for (int row = 0; row < gridRowStarts.length; row++) {
      for (int column = 0; column < noteSlots.length; column++) {
        final ItemStack stack = itemAt(menu, gridRowStarts[row] + column);
        if (stack == null) {
          continue;
        }
        final String entry = registryPath(stack) + " [" + hoverName(stack) + "]";
        if (seenItems.add(entry)) {
          DebugLog.detail(
              DebugLog.Category.MACRO, "[harp] item row=%d col=%d %s", row, column, entry);
        }
      }
    }
  }

  // ---- timing readouts (HUD) ----

  /**
   * @description Reports the white-pad strategy's lack of predictive lead.
   * @return zero milliseconds
   */
  public int leadMs() {
    return 0;
  }

  /**
   * @description Measures the number of note advances during a click's flight.
   * @return advances in flight, where more than one means the pad moved on
   */
  public float advancesInFlight() {
    return timing.advancesInFlight();
  }

  /**
   * @description Recognizes the server harp screen by its title prefix.
   * @param screen the current container screen
   * @return true for a harp screen
   */
  private static boolean isHarpScreen(AbstractContainerScreen<?> screen) {
    if (screen.getTitle() == null) {
      return false;
    }
    final String title = screen.getTitle().getString();
    return title != null && title.startsWith(GUI_NEEDLE);
  }

  /**
   * @description Reads a nonempty item from a bounded menu slot.
   * @param menu the active container menu
   * @param slot the zero-based menu slot
   * @return the item stack, or null for an empty or invalid slot
   */
  private static ItemStack itemAt(AbstractContainerMenu menu, int slot) {
    if (slot < 0 || slot >= menu.slots.size()) {
      return null;
    }
    final ItemStack stack = menu.getSlot(slot).getItem();
    return stack == null || stack.isEmpty() ? null : stack;
  }

  /**
   * @description Reads an item's registry path for harp pattern checks.
   * @param stack the item to inspect, or null
   * @return its path, or the empty string for null
   */
  private static String registryPath(ItemStack stack) {
    if (stack == null) {
      return "";
    }
    return BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
  }

  /**
   * @description Reads a normalized hover name for one debug legend entry.
   * @param stack the item to inspect, or null
   * @return its lower-case display name, or empty for null
   */
  private static String hoverName(ItemStack stack) {
    if (stack == null) {
      return "";
    }
    return stack.getHoverName().getString().toLowerCase(Locale.ROOT);
  }

  /**
   * @description Clears pad firing history, active menu identity, and timing samples.
   */
  private void resetNotes() {
    for (int column = 0; column < pads.length; column++) {
      pads[column] = new HarpPadState();
    }
    activeMenu = null;
    timing.reset();
  }

  /**
   * @description Saves the macro's owned run toggle through the root config writer.
   */
  private void persist() {
    RyznSettings.get().save();
  }
}
