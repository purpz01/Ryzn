package ryzn.macro.client.feature.macros.impl.slayer;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import ryzn.macro.client.MacroInput;
import ryzn.macro.client.util.inventory.MacroItems;
import ryzn.macro.client.util.inventory.MacroSlots;
import ryzn.macro.client.util.text.MacroText;

final class MaddoxQuestAutomation {

  private static final String BATPHONE = "Maddox Batphone";
  private static final int MENU_CLICK_DELAY_TICKS = 6;
  private static final int PHASE_TIMEOUT_TICKS = 20 * 20;
  private static final Pattern SLAYER_XP_REWARD =
      Pattern.compile("reward:\\s*([\\d,]+)\\s+zombie\\s+slayer\\s+xp", Pattern.CASE_INSENSITIVE);

  private Phase phase = Phase.OFF;
  private int phaseTicks;
  private int clickDelay;
  private int slayerXpPerBoss;

  void setEnabled(boolean enabled) {
    if (!enabled) {
      phase = Phase.OFF;
      phaseTicks = 0;
      clickDelay = 0;
    } else if (phase == Phase.OFF) {
      setPhase(Phase.CALL_PHONE);
    }
  }

  boolean ownsControl() {
    return phase != Phase.OFF && phase != Phase.HUNTING;
  }

  int slayerXpPerBoss() {
    return slayerXpPerBoss;
  }

  String statusText() {
    return switch (phase) {
      case CALL_PHONE, WAIT_START_MENU -> "Calling Maddox";
      case SELECT_REVENANT, SELECT_TIER, CONFIRM, WAIT_QUEST_STARTED ->
          "Starting Revenant Horror IV";
      case CALL_REWARD_PHONE, WAIT_REWARD_MENU, COLLECT_REWARD, WAIT_REWARD_CHAT ->
          "Claiming Slayer reward";
      case OFF, HUNTING -> "Patrolling the Crypts";

      default -> throw new MatchException(null, null);
    };
  }

  void bossDefeated() {
    if (phase == Phase.HUNTING) {
      setPhase(Phase.CALL_REWARD_PHONE);
    }
  }

  void onChat(String message) {
    String plain = MacroText.normalizeSearchText(message);
    if (phase == Phase.WAIT_QUEST_STARTED && isQuestStartedMessage(plain)) {
      setPhase(Phase.HUNTING);
    } else if (phase == Phase.WAIT_REWARD_CHAT && isQuestCompleteMessage(plain)) {
      setPhase(Phase.SELECT_REVENANT);
      clickDelay = MENU_CLICK_DELAY_TICKS;
    }
  }

  String tick(Minecraft mc) {
    if (!ownsControl()) {
      return null;
    }
    if (mc.options != null) {
      MacroInput.releaseAllKeys(mc.options);
    }
    if ((phase == Phase.CALL_PHONE || phase == Phase.CALL_REWARD_PHONE)
        && mc.gui.screen() != null) {
      return null;
    }
    if (++phaseTicks > PHASE_TIMEOUT_TICKS) {
      return "Maddox menu timed out.";
    }
    if (clickDelay > 0) {
      clickDelay--;
      return null;
    }

    return switch (phase) {
      case CALL_PHONE -> tickPhone(mc, Phase.WAIT_START_MENU);
      case WAIT_START_MENU -> tickWaitForSlayerMenu(mc, Phase.SELECT_REVENANT);
      case SELECT_REVENANT ->
          tickMenuChoice(mc, "slayer", "revenant horror", "click to view boss", Phase.SELECT_TIER);
      case SELECT_TIER ->
          tickMenuChoice(
              mc, "revenant horror", "revenant horror iv", "click to slay", Phase.CONFIRM);
      case CONFIRM ->
          tickMenuChoice(
              mc, "confirm", "confirm", "click to start quest", Phase.WAIT_QUEST_STARTED);
      case CALL_REWARD_PHONE -> tickPhone(mc, Phase.WAIT_REWARD_MENU);
      case WAIT_REWARD_MENU -> tickWaitForSlayerMenu(mc, Phase.COLLECT_REWARD);
      case COLLECT_REWARD ->
          tickMenuChoice(
              mc,
              "slayer",
              "slayer quest complete",
              "click to collect reward",
              Phase.WAIT_REWARD_CHAT);
      case WAIT_QUEST_STARTED, WAIT_REWARD_CHAT -> null;
      case OFF, HUNTING -> null;

      default -> throw new MatchException(null, null);
    };
  }

  private String tickPhone(Minecraft mc, Phase waitingPhase) {
    LocalPlayer player = mc.player;
    if (player == null) {
      return "Player is unavailable while calling Maddox.";
    }
    int slot =
        MacroSlots.findHotbarSlot(player, stack -> MacroItems.stackMatchesName(stack, BATPHONE));
    if (slot < 0) {
      return BATPHONE + " must be in your hotbar.";
    }
    MacroInput.selectHotbarSlot(player, slot);
    if (phaseTicks >= 4) {
      MacroInput.useItemClick(mc);
      setPhase(waitingPhase);
    }
    return null;
  }

  private String tickWaitForSlayerMenu(Minecraft mc, Phase next) {
    if (slayerScreen(mc) != null) {
      setPhase(next);
      clickDelay = MENU_CLICK_DELAY_TICKS;
    }
    return null;
  }

  private String tickMenuChoice(
      Minecraft mc, String title, String name, String action, Phase next) {
    if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> screen)) {
      if (phaseTicks > 40) {
        setPhase(phase == Phase.COLLECT_REWARD ? Phase.CALL_REWARD_PHONE : Phase.CALL_PHONE);
      }
      return null;
    }
    String screenTitle = MacroText.normalizeSearchText(screen.getTitle().getString());
    if (!screenTitle.contains(title) || mc.player == null) {
      return null;
    }
    for (Slot slot : screen.getMenu().slots) {
      if (slot == null || MacroSlots.isPlayerInventorySlot(screen.getMenu(), mc.player, slot)) {
        continue;
      }
      String rawText = MacroItems.stackSearchText(slot.getItem());
      String text = MacroText.normalizeSearchText(rawText);
      if (!menuChoiceMatches(text, name, action)) {
        continue;
      }
      if (phase == Phase.SELECT_TIER) {
        int parsedXp = parseSlayerXpReward(rawText);
        if (parsedXp > 0) {
          slayerXpPerBoss = parsedXp;
        }
      }
      MacroSlots.clickSlot(mc, screen.getMenu(), slot.index, 0, ContainerInput.PICKUP, mc.player);
      setPhase(next);
      clickDelay = MENU_CLICK_DELAY_TICKS;
      return null;
    }
    return null;
  }

  private static AbstractContainerScreen<?> slayerScreen(Minecraft mc) {
    if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> screen)) {
      return null;
    }
    String title = MacroText.normalizeSearchText(screen.getTitle().getString());
    return title.contains("slayer") ? screen : null;
  }

  static boolean menuChoiceMatches(String text, String name, String action) {
    return text != null && text.contains(name) && text.contains(action);
  }

  static boolean isQuestStartedMessage(String text) {
    return text != null && text.contains("slayer quest started");
  }

  static boolean isQuestCompleteMessage(String text) {
    return text != null && text.contains("slayer quest complete");
  }

  static int parseSlayerXpReward(String text) {
    if (text == null) {
      return 0;
    }
    Matcher matcher = SLAYER_XP_REWARD.matcher(MacroText.stripFormatting(text));
    if (!matcher.find()) {
      return 0;
    }
    try {
      return Integer.parseInt(matcher.group(1).replace(",", ""));
    } catch (NumberFormatException ignored) {
      return 0;
    }
  }

  private void setPhase(Phase next) {
    phase = next;
    phaseTicks = 0;
  }

  private enum Phase {
    OFF,
    CALL_PHONE,
    WAIT_START_MENU,
    SELECT_REVENANT,
    SELECT_TIER,
    CONFIRM,
    WAIT_QUEST_STARTED,
    HUNTING,
    CALL_REWARD_PHONE,
    WAIT_REWARD_MENU,
    COLLECT_REWARD,
    WAIT_REWARD_CHAT
  }
}
