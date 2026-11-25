package ryzn.macro.client.feature.macros.impl.foragingquest;

import static ryzn.macro.client.MacroInput.releaseAllKeys;
import static ryzn.macro.client.MacroInput.setUseHeldOn;
import static ryzn.macro.client.feature.macros.impl.foragingquest.ForagingQuestUnlockerMacro.CHAT_TIMEOUT_TICKS;
import static ryzn.macro.client.feature.macros.impl.foragingquest.ForagingQuestUnlockerMacro.LOGGER;
import static ryzn.macro.client.feature.macros.impl.foragingquest.ForagingQuestUnlockerMacro.NPC_CHAT_GRACE_TICKS;
import static ryzn.macro.client.feature.macros.impl.foragingquest.ForagingQuestUnlockerMacro.PATH_TIMEOUT_TICKS;
import static ryzn.macro.client.feature.rotation.impl.MacroAim.entityLookPoint;
import static ryzn.macro.client.feature.rotation.impl.MacroAim.isWithinInteractRange;
import static ryzn.macro.client.feature.rotation.impl.MacroAim.isWithinRange;
import static ryzn.macro.client.util.gui.MacroScreens.closeCurrentScreen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import ryzn.macro.client.MacroInput;
import ryzn.macro.client.feature.commands.impl.MacroCommands;
import ryzn.macro.client.feature.commands.impl.pathfinder.PathfindCommand;
import ryzn.macro.client.feature.macros.impl.foragingquest.ForagingRoute.Npc;
import ryzn.macro.client.feature.macros.impl.foragingquest.ForagingRoute.Target;
import ryzn.macro.client.input.GuiActionTiming;
import ryzn.macro.client.input.MacroKeys;
import ryzn.macro.client.util.world.MacroObstruction;

/**
 * @description Advances quest NPC conversations and preserves the shared interaction timing and
 *     obstruction gates.
 */
final class ForagingQuestNpcInteractions {

  /**
   * A scripted NPC intro runs far longer than a normal interact wait - Molbert's is ~40 s to the
   * prompt.
   */
  private static final int NPC_DIALOGUE_TIMEOUT_TICKS = 20 * 75;

  private static final int INTERACT_TIMEOUT_TICKS = 20 * 25;

  private final ForagingQuestUnlockerMacro quest;

  ForagingQuestNpcInteractions(ForagingQuestUnlockerMacro quest) {
    this.quest = quest;
  }

  void tickNpcChatInteract(
      Minecraft mc,
      LocalPlayer player,
      ClientLevel level,
      Npc npc,
      ForagingQuestState waitState,
      boolean sneak) {
    quest.stateTicks++;
    releaseAllKeys(mc.options);
    if (sneak && mc.options != null) {
      MacroKeys.set(mc.options.keyShift, true);
    }
    if (mc.gui.screen() != null) {
      closeCurrentScreen(mc);
      return;
    }
    if (npcInteractTimedOut(npc)) {
      quest.fail(npc.label() + " interaction timed out");
      return;
    }
    if (npcDialogueRunning(npc)) {
      quest.status = "Listening to " + npc.label();
      return; // re-clicking restarts the speech and wipes the option prompt
    }
    final Entity entity = quest.route.npc(level, npc);
    if (entity == null) {
      quest.status = "Searching for " + npc.label();
      return;
    }
    if (!withinUseReach(player, entity)) {
      pathToNpcInteractRange(entity, npc);
      return;
    }
    if (relocating()) {
      return;
    }
    PathfindCommand.stopNavQuiet();
    quest.actionTiming.clearPathRequest();
    if (!quest.look().aimAtEntity(player, entity)) {
      return;
    }
    if (blockedFromClicking(mc, player, level, entity)) {
      return;
    }
    if (quest.actionTiming.waitForInteraction()) {
      return;
    }
    if (mc.options != null) {
      setUseHeldOn(mc.options, true, entity);
    }
    MacroInput.useClickOn(mc, entity);
    quest.status = "Clicked " + npc.label();
    quest.setState(waitState);
  }

  /**
   * First Lumber Jack talk: walk right up to him, click him, then accept the "Select an option:
   * [Sure] [Nah]" chat dialogue by sending [Sure]'s own click command (identical packet to a manual
   * click, same as the Molbert help step - no crafted packets). Advances once the Oak objective
   * lands.
   */
  void tickLumberJackStart(Minecraft mc, LocalPlayer player, ClientLevel level) {
    quest.stateTicks++;
    releaseAllKeys(mc.options);
    if (quest.chatWaits.hasObjectiveSinceState("Collect Oak Logs")) {
      quest.setState(ForagingQuestState.START_OAK_CHOP);
      return;
    }
    ClickEvent sure = quest.chatWaits.selectOptionClick("sure");
    if (sure == null && npcDialogueRunning(ForagingRoute.LUMBER_JACK)) {
      sure = quest.chatWaits.visibleOptionClick(mc, "sure");
    }
    if (sure != null) {
      MacroCommands.runChatClick(mc, sure);
      quest.status = "Accepted Lumber Jack task";
      quest.setState(ForagingQuestState.WAIT_OAK_OBJECTIVE);
      return;
    }
    if (mc.gui.screen() != null) {
      closeCurrentScreen(mc);
      return;
    }
    if (npcInteractTimedOut(ForagingRoute.LUMBER_JACK)) {
      quest.fail("Lumber Jack interaction timed out");
      return;
    }
    if (npcDialogueRunning(ForagingRoute.LUMBER_JACK)) {
      quest.status = "Listening to Lumber Jack";
      if (quest.chatHistory.containsSinceState("select an option")) {
        quest.status = "Waiting for clickable Sure";
        if (!quest.unansweredPromptLogged) {
          quest.unansweredPromptLogged = true;
          LOGGER.warn(
              "No answerable [Sure] in prompt: {}", quest.chatWaits.describeVisiblePrompt(mc));
        }
      }
      return; // re-clicking restarts the intro and wipes the [Sure] prompt
    }
    final Entity entity = quest.route.npc(level, ForagingRoute.LUMBER_JACK);
    if (entity == null) {
      quest.status = "Searching for Lumber Jack";
      return;
    }
    // Stay just inside actual reach before clicking - a use only reaches entityInteractionRange, so
    // the
    // wider isWithinInteractRange gate let earlier attempts "click" from out of reach and miss.
    if (!withinUseReach(player, entity)) {
      pathToNpcInteractRange(entity, ForagingRoute.LUMBER_JACK);
      return;
    }
    if (relocating()) {
      return;
    }
    PathfindCommand.stopNavQuiet();
    quest.actionTiming.clearPathRequest();
    if (!quest.look().aimAtEntity(player, entity)) {
      return;
    }
    if (blockedFromClicking(mc, player, level, entity)) {
      return;
    }
    if (quest.actionTiming.waitForInteraction()) {
      return;
    }
    if (mc.options != null) {
      setUseHeldOn(mc.options, true, entity);
    }
    MacroInput.useClickOn(mc, entity);
    quest.actionTiming.armInteraction(GuiActionTiming.randomDelayAtLeast(3));
    quest.status = "Talking to Lumber Jack";
  }

  /**
   * Kelly's first talk: click her, then accept the "Did Charlie send you? [Yeah] [Never heard of
   * him]" prompt by sending [Yeah]'s own click command. [Yeah] is the first bracket, so this reuses
   * the same first-run-command trick as {@link #tickLumberJackStart} (identical packet to a manual
   * click, no crafted packets). Advances once the Spruce objective lands or the option is sent.
   */
  void tickKellyStart(Minecraft mc, LocalPlayer player, ClientLevel level) {
    quest.stateTicks++;
    releaseAllKeys(mc.options);
    if (quest.chatWaits.hasObjectiveSinceState("Collect Spruce Logs")) {
      quest.setState(ForagingQuestState.WAIT_SPRUCE_OBJECTIVE);
      return;
    }
    final ClickEvent yeah = quest.chatWaits.selectOptionClickOrFirst("yeah");
    if (yeah != null) {
      MacroCommands.runChatClick(mc, yeah);
      quest.status = "Told Kelly that Charlie sent us";
      quest.setState(ForagingQuestState.WAIT_SPRUCE_OBJECTIVE);
      return;
    }
    if (mc.gui.screen() != null) {
      closeCurrentScreen(mc);
      return;
    }
    if (npcInteractTimedOut(ForagingRoute.KELLY)) {
      quest.fail("Kelly interaction timed out");
      return;
    }
    if (npcDialogueRunning(ForagingRoute.KELLY)) {
      quest.status = "Listening to Kelly";
      return; // re-clicking restarts the speech and wipes the [Yeah] prompt
    }
    final Entity entity = quest.route.npc(level, ForagingRoute.KELLY);
    if (entity == null) {
      quest.status = "Searching for Kelly";
      return;
    }
    if (!withinUseReach(player, entity)) {
      pathToNpcInteractRange(entity, ForagingRoute.KELLY);
      return;
    }
    if (relocating()) {
      return;
    }
    PathfindCommand.stopNavQuiet();
    quest.actionTiming.clearPathRequest();
    if (!quest.look().aimAtEntity(player, entity)) {
      return;
    }
    if (blockedFromClicking(mc, player, level, entity)) {
      return;
    }
    if (quest.actionTiming.waitForInteraction()) {
      return;
    }
    if (mc.options != null) {
      setUseHeldOn(mc.options, true, entity);
    }
    MacroInput.useClickOn(mc, entity);
    quest.actionTiming.armInteraction(GuiActionTiming.randomDelayAtLeast(3));
    quest.status = "Talking to Kelly";
  }

  /**
   * Ryan's first talk. Click him from behind, then accept the first prompt "What do you say to
   * that? [Sure, I guess?]" by sending [Sure, I guess?]'s own click command (same first-run-command
   * trick as the Lumber Jack / Kelly steps). Ryan then asks a SECOND question with a [WHAT?]
   * option, handled by {@link #tickRyanTrialWhat} - a separate state so its stateStartSequence
   * advances past this prompt and finds the next one.
   */
  void tickRyanStart(Minecraft mc, LocalPlayer player, ClientLevel level) {
    quest.stateTicks++;
    releaseAllKeys(mc.options);
    final ClickEvent sure = quest.chatWaits.selectOptionClickOrFirst("sure");
    if (sure != null) {
      MacroCommands.runChatClick(mc, sure);
      quest.status = "Told Ryan sure";
      quest.setState(ForagingQuestState.INTERACT_RYAN_TRIAL_WHAT);
      return;
    }
    if (mc.gui.screen() != null) {
      closeCurrentScreen(mc);
      return;
    }
    if (npcInteractTimedOut(ForagingRoute.RYAN)) {
      quest.fail("Ryan interaction timed out");
      return;
    }
    if (npcDialogueRunning(ForagingRoute.RYAN)) {
      quest.status = "Listening to Ryan";
      return; // re-clicking restarts the speech and wipes the [Sure] prompt
    }
    final Entity entity = quest.route.npc(level, ForagingRoute.RYAN);
    if (entity == null) {
      quest.status = "Searching for Ryan";
      return;
    }
    if (!withinUseReach(player, entity)) {
      pathToNpcInteractRange(entity, ForagingRoute.RYAN);
      return;
    }
    if (relocating()) {
      return;
    }
    PathfindCommand.stopNavQuiet();
    quest.actionTiming.clearPathRequest();
    if (!quest.look().aimAtEntity(player, entity)) {
      return;
    }
    if (blockedFromClicking(mc, player, level, entity)) {
      return;
    }
    if (quest.actionTiming.waitForInteraction()) {
      return;
    }
    if (mc.options != null) {
      setUseHeldOn(mc.options, true, entity);
    }
    MacroInput.useClickOn(mc, entity);
    quest.actionTiming.armInteraction(GuiActionTiming.randomDelayAtLeast(3));
    quest.status = "Talking to Ryan";
  }

  /**
   * Ryan's second prompt: after [Sure, I guess?] he says "Stand in the CAMPFIRE! [WHAT?]". Send
   * [WHAT?]'s own click command (the first option on this new prompt), then wait for the trial
   * objective. No re-click - the conversation is already open; we just answer the follow-up option.
   */
  void tickRyanTrialWhat(Minecraft mc) {
    quest.stateTicks++;
    releaseAllKeys(mc.options);
    final ClickEvent what = quest.chatWaits.selectOptionClickOrFirst("what");
    if (what != null) {
      MacroCommands.runChatClick(mc, what);
      quest.status = "Asked Ryan what";
      quest.setState(ForagingQuestState.WAIT_RYAN_TRIAL_OBJECTIVE);
      return;
    }
    if (mc.gui.screen() != null) {
      closeCurrentScreen(mc);
      return;
    }
    if (npcTimedOut(INTERACT_TIMEOUT_TICKS)) {
      quest.fail("Ryan trial prompt timed out");
      return;
    }
    quest.status = "Waiting for Ryan trial prompt";
  }

  /**
   * Melody's first talk: click her, then accept the "Hello! [Are you okay?]" prompt by sending [Are
   * you okay?]'s own click command (first option - same trick as the Kelly step). Advances once the
   * Acacia objective lands or the option is sent. The harp lessons later are driven by the Auto
   * Harp module.
   */
  void tickMelodyStart(Minecraft mc, LocalPlayer player, ClientLevel level) {
    quest.stateTicks++;
    releaseAllKeys(mc.options);
    if (quest.chatWaits.hasObjectiveSinceState("Collect Acacia Logs")) {
      quest.setState(ForagingQuestState.WAIT_ACACIA_OBJECTIVE);
      return;
    }
    final ClickEvent okay = quest.chatWaits.selectOptionClickOrFirst("are you okay");
    if (okay != null) {
      MacroCommands.runChatClick(mc, okay);
      quest.status = "Asked Melody if she's okay";
      quest.setState(ForagingQuestState.WAIT_ACACIA_OBJECTIVE);
      return;
    }
    if (mc.gui.screen() != null) {
      closeCurrentScreen(mc);
      return;
    }
    if (npcInteractTimedOut(ForagingRoute.MELODY)) {
      quest.fail("Melody interaction timed out");
      return;
    }
    if (npcDialogueRunning(ForagingRoute.MELODY)) {
      quest.status = "Listening to Melody";
      return; // re-clicking reopens the song menu and loses the selection
    }
    final Entity entity = quest.route.npc(level, ForagingRoute.MELODY);
    if (entity == null) {
      quest.status = "Searching for Melody";
      return;
    }
    if (!withinUseReach(player, entity)) {
      pathToNpcInteractRange(entity, ForagingRoute.MELODY);
      return;
    }
    if (relocating()) {
      return;
    }
    PathfindCommand.stopNavQuiet();
    quest.actionTiming.clearPathRequest();
    if (!quest.look().aimAtEntity(player, entity)) {
      return;
    }
    if (blockedFromClicking(mc, player, level, entity)) {
      return;
    }
    if (quest.actionTiming.waitForInteraction()) {
      return;
    }
    if (mc.options != null) {
      setUseHeldOn(mc.options, true, entity);
    }
    MacroInput.useClickOn(mc, entity);
    quest.actionTiming.armInteraction(GuiActionTiming.randomDelayAtLeast(3));
    quest.status = "Talking to Melody";
  }

  /**
   * Molbert's first talk: click him, sit through the long intro, then accept the "The moles must be
   * dealt with... [Sure...] [You look like a mole yourself]" prompt by sending [Sure...]'s own
   * click command, matched by its "Sure" text ([You look like a mole yourself] is the wrong one and
   * just loops). Falls back to the first bracket if the text match misses. Advances once the Jungle
   * objective lands or the option is sent.
   */
  void tickMolbertStart(Minecraft mc, LocalPlayer player, ClientLevel level) {
    quest.stateTicks++;
    releaseAllKeys(mc.options);
    if (quest.chatWaits.hasObjectiveSinceState("Collect Jungle Logs")) {
      quest.setState(ForagingQuestState.WAIT_JUNGLE_OBJECTIVE);
      return;
    }
    final ClickEvent sure = quest.chatWaits.selectOptionClick("sure");
    if (sure != null) {
      MacroCommands.runChatClick(mc, sure);
      quest.status = "Agreed to help Molbert";
      quest.setState(ForagingQuestState.WAIT_JUNGLE_OBJECTIVE);
      return;
    }
    if (mc.gui.screen() != null) {
      closeCurrentScreen(mc);
      return;
    }
    if (npcInteractTimedOut(ForagingRoute.MOLBERT)) {
      quest.fail("Molbert interaction timed out");
      return;
    }
    if (npcDialogueRunning(ForagingRoute.MOLBERT)) {
      quest.status = "Listening to Molbert";
      return; // re-clicking restarts the intro and wipes the [Sure...] prompt
    }
    final Entity entity = quest.route.npc(level, ForagingRoute.MOLBERT);
    if (entity == null) {
      quest.status = "Searching for Molbert";
      return;
    }
    if (!withinUseReach(player, entity)) {
      pathToNpcInteractRange(entity, ForagingRoute.MOLBERT);
      return;
    }
    if (relocating()) {
      return;
    }
    PathfindCommand.stopNavQuiet();
    quest.actionTiming.clearPathRequest();
    if (!quest.look().aimAtEntity(player, entity)) {
      return;
    }
    if (blockedFromClicking(mc, player, level, entity)) {
      return;
    }
    if (quest.actionTiming.waitForInteraction()) {
      return;
    }
    if (mc.options != null) {
      setUseHeldOn(mc.options, true, entity);
    }
    MacroInput.useClickOn(mc, entity);
    quest.actionTiming.armInteraction(GuiActionTiming.randomDelayAtLeast(3));
    quest.status = "Talking to Molbert";
  }

  /**
   * After [HELP MOLBERT] frees him, a "Talk to Molbert" objective lands and he must be clicked
   * AGAIN - the thank-you dialogue and the [I know that you are a mole] prompt never arrive on
   * their own. So this state clicks him once, listens out the whole speech, then sends the option's
   * own command. Match "i know" exactly: [HELP MOLBERT] is itself a "Select an option" line, so an
   * OrFirst fallback can grab it and mis-route.
   */
  void tickMolbertMole(Minecraft mc, LocalPlayer player, ClientLevel level) {
    quest.stateTicks++;
    releaseAllKeys(mc.options);
    final ClickEvent mole = quest.chatWaits.selectOptionClick("i know");
    if (mole != null) {
      MacroCommands.runChatClick(mc, mole);
      quest.status = "Told Molbert he's a mole";
      quest.setState(ForagingQuestState.WAIT_FINAL_TALK_OBJECTIVE);
      return;
    }
    if (mc.gui.screen() != null) {
      closeCurrentScreen(mc);
      return;
    }
    if (npcInteractTimedOut(ForagingRoute.FINAL_MOLBERT)) {
      quest.fail("Molbert mole prompt timed out");
      return;
    }
    if (npcDialogueRunning(ForagingRoute.FINAL_MOLBERT)) {
      quest.status = "Listening to Molbert";
      return; // a queued re-click lands the tick the speech ends, restarting it and wiping the
      // prompt
    }
    final Entity entity = quest.route.npc(level, ForagingRoute.FINAL_MOLBERT);
    if (entity == null) {
      quest.status = "Searching for Molbert";
      return;
    }
    if (!withinUseReach(player, entity)) {
      pathToNpcInteractRange(entity, ForagingRoute.FINAL_MOLBERT);
      return;
    }
    if (relocating()) {
      return;
    }
    PathfindCommand.stopNavQuiet();
    quest.actionTiming.clearPathRequest();
    if (!quest.look().aimAtEntity(player, entity)) {
      return;
    }
    if (blockedFromClicking(mc, player, level, entity)) {
      return;
    }
    if (quest.actionTiming.waitForInteraction()) {
      return;
    }
    if (mc.options != null) {
      setUseHeldOn(mc.options, true, entity);
    }
    MacroInput.useClickOn(mc, entity);
    quest.actionTiming.armInteraction(GuiActionTiming.randomDelayAtLeast(3));
    quest.status = "Talking to Molbert";
  }

  /**
   * Return to the Lumber Jack, walk right up to him (point-blank, like the first talk), and do the
   * last conversation to open the reward popup. Same close-approach reason as {@link
   * #tickLumberJackStart}: a use only reaches entityInteractionRange, so the wider
   * isWithinInteractRange gate fires clicks from out of reach.
   */
  void tickLumberJackReturn(Minecraft mc, LocalPlayer player, ClientLevel level) {
    quest.stateTicks++;
    releaseAllKeys(mc.options);
    if (mc.gui.screen() instanceof AbstractContainerScreen<?>) {
      quest.macroScreenOpened = true;
      quest.setState(ForagingQuestState.WAIT_LUMBER_JACK_GUI);
      return;
    }
    if (mc.gui.screen() != null) {
      closeCurrentScreen(mc);
      return;
    }
    if (npcTimedOut(INTERACT_TIMEOUT_TICKS)) {
      quest.fail("Lumber Jack turn-in timed out");
      return;
    }
    final Entity entity = quest.route.npc(level, ForagingRoute.LUMBER_JACK);
    if (entity == null) {
      quest.status = "Searching for Lumber Jack";
      return;
    }
    if (!withinUseReach(player, entity)) {
      pathToNpcInteractRange(entity, ForagingRoute.LUMBER_JACK);
      return;
    }
    if (relocating()) {
      return;
    }
    PathfindCommand.stopNavQuiet();
    quest.actionTiming.clearPathRequest();
    if (!quest.look().aimAtEntity(player, entity)) {
      return;
    }
    if (blockedFromClicking(mc, player, level, entity)) {
      return;
    }
    if (quest.actionTiming.waitForInteraction()) {
      return;
    }
    if (mc.options != null) {
      setUseHeldOn(mc.options, true, entity);
    }
    MacroInput.useClickOn(mc, entity);
    quest.actionTiming.armInteraction(GuiActionTiming.randomDelayAtLeast(2));
    quest.status = "Turning in oak to Lumber Jack";
  }

  void tickNpcScreenInteract(
      Minecraft mc,
      LocalPlayer player,
      ClientLevel level,
      Npc npc,
      ForagingQuestState screenState,
      boolean sneak) {
    quest.stateTicks++;
    releaseAllKeys(mc.options);
    if (sneak && mc.options != null) {
      MacroKeys.set(mc.options.keyShift, true);
    }
    if (mc.gui.screen() instanceof AbstractContainerScreen<?>) {
      quest.macroScreenOpened = true;
      quest.setState(screenState);
      return;
    }
    if (mc.gui.screen() != null) {
      closeCurrentScreen(mc);
      return;
    }
    if (npcTimedOut(INTERACT_TIMEOUT_TICKS)) {
      quest.fail(npc.label() + " GUI timed out");
      return;
    }
    final Entity entity = quest.route.npc(level, npc);
    if (entity == null) {
      quest.status = "Searching for " + npc.label();
      return;
    }
    if (!withinUseReach(player, entity)) {
      pathToNpcInteractRange(entity, npc);
      return;
    }
    if (relocating()) {
      return;
    }
    PathfindCommand.stopNavQuiet();
    quest.actionTiming.clearPathRequest();
    if (!quest.look().aimAtEntity(player, entity)) {
      return;
    }
    if (blockedFromClicking(mc, player, level, entity)) {
      return;
    }
    if (quest.actionTiming.waitForInteraction()) {
      return;
    }
    if (mc.options != null) {
      setUseHeldOn(mc.options, true, entity);
    }
    MacroInput.useClickOn(mc, entity);
    quest.actionTiming.armInteraction(GuiActionTiming.randomDelayAtLeast(2));
    quest.status = "Opening " + npc.label();
  }

  void tickPathToFinalMolbert(Minecraft mc, LocalPlayer player, ClientLevel level) {
    quest.stateTicks++;
    releaseAllKeys(mc.options);
    quest.look().clear();
    if (mc.gui.screen() != null) {
      closeCurrentScreen(mc);
      return;
    }
    final Entity molbert = quest.route.npc(level, ForagingRoute.FINAL_MOLBERT);
    if (molbert != null && isWithinInteractRange(player, molbert)) {
      PathfindCommand.stopNavQuiet();
      quest.setState(ForagingQuestState.INTERACT_FINAL_MOLBERT_HELP);
      return;
    }
    final Target target;
    if (molbert != null) {
      target =
          new Target(
              Mth.floor(molbert.getX()),
              Mth.floor(molbert.getY()),
              Mth.floor(molbert.getZ()),
              "final Molbert");
    } else {
      target = ForagingRoute.FINAL_MOLBERT.target();
    }
    if (!quest.actionTiming.isPathRequested()) {
      if (!PathfindCommand.startToQuiet(target.x(), target.y(), target.z())) {
        quest.fail("Could not start path to final Molbert");
        return;
      }
      quest.actionTiming.markPathRequested();
      quest.status = "Walking to final Molbert";
      return;
    }
    if (!PathfindCommand.isNavigationActive() && quest.stateTicks > 20) {
      quest.actionTiming.clearPathRequest();
      if (quest.timedOut(PATH_TIMEOUT_TICKS)) {
        quest.fail("Final Molbert not found");
      }
    }
  }

  void tickHelpMolbertClickable(Minecraft mc) {
    quest.stateTicks++;
    releaseAllKeys(mc.options);
    quest.look().clear();
    // Already-helped Molbert (a resumed run) skips [HELP MOLBERT] and goes straight to the mole
    // prompt when clicked - answer it here instead of waiting forever for a help option that
    // can never reappear. Match the mole option by its own text ONLY: [HELP MOLBERT] is itself a
    // "Select an option" line, so an OrFirst fallback would grab it and mis-route past the real
    // [I know that you are a mole] prompt (the bot would click HELP but never accuse him).
    final ClickEvent mole = quest.chatWaits.selectOptionClick("i know");
    if (mole != null) {
      MacroCommands.runChatClick(mc, mole);
      quest.status = "Told Molbert he's a mole";
      quest.setState(ForagingQuestState.WAIT_FINAL_TALK_OBJECTIVE);
      return;
    }
    final ClickEvent command = quest.chatWaits.helpMolbertClick();
    if (command != null) {
      MacroCommands.runChatClick(mc, command);
      quest.status = "Selected Help Molbert";
      quest.setState(ForagingQuestState.INTERACT_MOLBERT_MOLE);
      return;
    }
    quest.status = "Waiting for Help Molbert option";
    if (npcTimedOut(CHAT_TIMEOUT_TICKS)) {
      quest.fail("Help Molbert option timed out");
    }
  }

  /**
   * True when {@code entity} is within actual USE (right-click) reach. A use only reaches {@code
   * entityInteractionRange}, which is shorter than the +1-block-padded {@link
   * #isWithinInteractRange} gate; clicking from that wider range lands just out of reach and
   * silently misses, so the interaction handlers stop short and stand there. Matches the tight
   * reach the Lumber Jack steps already use.
   */
  boolean withinUseReach(LocalPlayer player, Entity entity) {
    // Interaction norm: never click from farther than 3 blocks - close range is hard to body-block.
    final double clickReach =
        Math.min(player.entityInteractionRange() - 0.4, MacroObstruction.MAX_INTERACT_DISTANCE);
    return isWithinRange(player.getEyePosition(), entityLookPoint(entity), clickReach);
  }

  /**
   * True while a blocked-view sidestep walk is still running - hold all clicking until it lands.
   */
  boolean relocating() {
    if (!quest.sidestepActive) {
      return false;
    }
    if (PathfindCommand.isNavigationActive()) {
      quest.status = "Relocating for a clear view";
      return true;
    }
    quest.sidestepActive = false;
    return false;
  }

  /**
   * Interaction norm gate, checked once aim has settled: the crosshair pick must be the NPC itself
   * and nobody stacked on the bot. A blocker persisting past the norm window triggers a sidestep to
   * a standable tile with a player- and block-clear line to the NPC. True = do not click this tick.
   */
  boolean blockedFromClicking(Minecraft mc, LocalPlayer player, ClientLevel level, Entity entity) {
    if (quest.inventoryActions.moveHandOffArmor(player)) {
      quest.status = "Putting the armour reward away";
      return true;
    }
    if (MacroInput.isCrosshairOn(mc, entity)
        && !MacroObstruction.isPlayerStackedOn(level, player)) {
      quest.blockedTicks = 0;
      return false;
    }
    if (++quest.blockedTicks < MacroObstruction.BLOCKED_RELOCATE_TICKS) {
      quest.status = "Waiting for a clear view";
      return true;
    }
    quest.blockedTicks = 0;
    final BlockPos side =
        MacroObstruction.findSidestep(
            level, player, entity.blockPosition(), entityLookPoint(entity), entity);
    if (side != null && PathfindCommand.startToQuiet(side.getX(), side.getY(), side.getZ())) {
      quest.sidestepActive = true;
      quest.status = "Relocating for a clear view";
    }
    return true;
  }

  void pathToNpcInteractRange(Entity entity, Npc npc) {
    quest.look().clear();
    if (PathfindCommand.isNavigationActive()) {
      quest.status = "Walking to " + npc.label();
      return;
    }
    if (quest.actionTiming.isPathRequested() && quest.stateTicks <= 20) {
      quest.status = "Waiting for path to " + npc.label();
      return;
    }
    if (npcTimedOut(INTERACT_TIMEOUT_TICKS)) {
      quest.fail(npc.label() + " path timed out");
      return;
    }
    final Target target = interactTarget(entity, npc);
    if (!PathfindCommand.startToQuiet(target.x(), target.y(), target.z())) {
      quest.fail("Could not start path to " + npc.label());
      return;
    }
    quest.actionTiming.markPathRequested();
    quest.status = "Walking to " + npc.label();
  }

  /**
   * Where to stand to click an NPC: a standable tile within the interact norm of its aim point,
   * never the NPC's own feet block - that cell is often un-pathable (Ryan stands on a solid
   * decoration), so pathing to it made the native return "no path" every tick while the bot
   * crouch-stared at him.
   */
  Target interactTarget(Entity entity, Npc npc) {
    if (entity == null) {
      return npc.target();
    }
    final Minecraft mc = Minecraft.getInstance();
    if (mc.level != null && mc.player != null) {
      final BlockPos side =
          MacroObstruction.findSidestep(
              mc.level, mc.player, entity.blockPosition(), entityLookPoint(entity), entity);
      if (side != null) {
        return new Target(side.getX(), side.getY(), side.getZ(), npc.label());
      }
    }
    return new Target(
        Mth.floor(entity.getX()), Mth.floor(entity.getY()), Mth.floor(entity.getZ()), npc.label());
  }

  /**
   * Timeout for steps whose progress depends on NPC dialogue: while "[NPC]" chat lines are still
   * streaming (Molbert's intro alone is 7+ slow lines) the deadline is held open, up to a 3x hard
   * ceiling so ambient NPC chatter can never keep a genuinely stuck state alive forever.
   */
  /**
   * True while this NPC's scripted dialogue is already running (an {@code [NPC] Name:} line has
   * landed since the state started). Hypixel restarts a speech from line 1 on every re-click and
   * only shows the "Select an option" prompt after the last line, so the old few-tick re-click
   * cadence wiped the prompt in the same tick it arrived and looped forever. Match on the {@code
   * [NPC]} prefix, not the bare name, so an objective line like "Talk to Molbert" is not mistaken
   * for the NPC speaking.
   */
  boolean npcDialogueRunning(ForagingRoute.Npc npc) {
    return quest.chatHistory.containsSinceState("[npc] " + npc.needle());
  }

  /** Timeout for an interact tick: a longer budget once the NPC has actually started talking. */
  boolean npcInteractTimedOut(ForagingRoute.Npc npc) {
    return npcTimedOut(
        npcDialogueRunning(npc) ? NPC_DIALOGUE_TIMEOUT_TICKS : INTERACT_TIMEOUT_TICKS);
  }

  boolean npcTimedOut(int timeoutTicks) {
    if (quest.stateTicks >= timeoutTicks * 3) {
      return true;
    }
    return quest.timedOut(timeoutTicks) && quest.ticksSinceNpcChat >= NPC_CHAT_GRACE_TICKS;
  }
}
