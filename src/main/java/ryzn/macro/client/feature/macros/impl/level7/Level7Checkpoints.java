package ryzn.macro.client.feature.macros.impl.level7;

import net.minecraft.util.Mth;
import ryzn.macro.client.feature.macros.impl.foragingquest.ForagingQuestUnlockerMacro;
import ryzn.macro.client.feature.macros.impl.level7.AutoLvl7Macro.State;

/**
 * @description Defines the Level 7 resume checkpoints and child resume stages.
 */
final class Level7Checkpoints {

  private Level7Checkpoints() {}

  enum Checkpoint {
    FARMING_6(
        "Wheat to Farming 6",
        new Stage("Farm wheat to Farming 6", State.START),
        new Stage("Sell wheat at Farming 6", State.FARMING_6_REACHED)),
    MINING_AND_BEA(
        "Mining Materials and Bea",
        new Stage("Buy 32 gold ingots", State.AFTER_LEVEL6_SELL),
        new Stage("Buy the Bee pet", State.MINING_MERCHANT_DONE)),
    FARMING_12(
        "Wheat to Farming 12",
        new Stage("Farm wheat to Farming 12", State.BEA_DONE),
        new Stage("Sell wheat at Farming 12", State.FARMING_12_REACHED)),
    CARPENTRY_AND_SHOVEL(
        "Carpentry and Gold Shovel",
        new Stage("Unlock Carpentry", State.AFTER_LEVEL12_SELL),
        new Stage("Make Gold Shovel", State.CARPENTRY_DONE),
        shovel("Buy XP bottles", "RIGHT_CLICK_EXP_BOTTLE", false),
        shovel("Throw XP bottles", "EQUIP_EXP_BOTTLES", false),
        shovel("Craft the axe and shovel", "OPEN_CRAFT", false),
        shovel("Enchant the shovel", "ROTATE_TO_ENCHANT_TABLE", false),
        shovel("Buy the axe's XP bottles", "PATHING_TO_LIBRARIAN", true),
        shovel("Throw the axe's XP bottles", "EQUIP_EXP_BOTTLES", true),
        shovel("Enchant the axe", "ROTATE_TO_ENCHANT_TABLE", true)),
    DESERT_TRAVEL(
        "Travel to the Desert",
        new Stage("Walk to the warp", State.GOLD_SHOVEL_DONE),
        new Stage("Continue to the Desert", State.SERVER_TRANSFER_DETECTED)),
    MINING_12("Sand to Mining 12", new Stage("Mine sand to Mining 12", State.SAND_PHASE)),
    FORAGING("Foraging quest unlock", foragingStages()),
    REVENANT_SLAYER(
        "Zombie Slayer",
        new Stage("Warp to the Hub for Zombie Slayer", State.REV_HUB_WARP),
        new Stage("Run Rev Slayer to Zombie Slayer LVL 2", State.REV_LEVEL_2)),
    FORAGING_COLLECTIONS("All wood collections III", collectionStages());

    private final String label;
    final Stage[] stages;

    Checkpoint(String label, Stage... stages) {
      this.label = label;
      this.stages = stages;
    }
  }

  record Stage(
      String label,
      State firstState,
      String shovelStep,
      boolean shovelAxeLeg,
      int foragingCheckpoint,
      int collectionLeg,
      boolean collectionFinalSale) {
    Stage(String label, State firstState) {
      this(label, firstState, null, false, 0, -1, false);
    }
  }

  /** A stage that resumes inside the Gold Shovel rather than at the top of it. */
  static Stage shovel(String label, String shovelStep, boolean axeLeg) {
    return new Stage(
        "Gold Shovel: " + label, State.CARPENTRY_DONE, shovelStep, axeLeg, 0, -1, false);
  }

  /**
   * The parent selector mirrors the unlocker's checkpoints so each quest handoff can be tested
   * alone.
   */
  static Stage[] foragingStages() {
    final String[] checkpoints = ForagingQuestUnlockerMacro.resumeCheckpointLabels();
    final Stage[] stages = new Stage[checkpoints.length + 1];
    stages[0] = new Stage("Warp to the Hub", State.HUB_WARP);
    for (int i = 0; i < checkpoints.length; i++) {
      stages[i + 1] =
          new Stage(checkpoints[i], State.FORAGING_UNLOCK, null, false, i + 1, -1, false);
    }
    return stages;
  }

  static Stage[] collectionStages() {
    final String[] labels = ForagingQuestUnlockerMacro.collectionGrindStageLabels();
    final Stage[] stages = new Stage[labels.length + 1];
    for (int i = 0; i < labels.length; i++) {
      stages[i] = new Stage(labels[i], State.FORAGING_COLLECTIONS, null, false, 0, i, false);
    }
    stages[labels.length] =
        new Stage("Sell collected logs", State.FORAGING_COLLECTIONS, null, false, 0, -1, true);
    return stages;
  }

  static String[] resumeCheckpointLabels() {
    Checkpoint[] checkpoints = Checkpoint.values();
    String[] labels = new String[checkpoints.length];
    for (int i = 0; i < checkpoints.length; i++) {
      labels[i] = checkpoints[i].label;
    }
    return labels;
  }

  static int[] routeCheckpointOrder() {
    return new int[] {
      Checkpoint.FARMING_6.ordinal() + 1,
      Checkpoint.MINING_AND_BEA.ordinal() + 1,
      Checkpoint.FARMING_12.ordinal() + 1,
      Checkpoint.CARPENTRY_AND_SHOVEL.ordinal() + 1,
      Checkpoint.DESERT_TRAVEL.ordinal() + 1,
      Checkpoint.MINING_12.ordinal() + 1,
      Checkpoint.REVENANT_SLAYER.ordinal() + 1,
      Checkpoint.FORAGING.ordinal() + 1,
      Checkpoint.FORAGING_COLLECTIONS.ordinal() + 1
    };
  }

  static String[] resumeStageLabels(int checkpointIndex) {
    Checkpoint checkpoint = checkpoint(checkpointIndex);
    String[] labels = new String[checkpoint.stages.length];
    for (int i = 0; i < checkpoint.stages.length; i++) {
      labels[i] = checkpoint.stages[i].label;
    }
    return labels;
  }

  static State resumeState(int checkpointIndex, int stageIndex) {
    return resumeStage(checkpointIndex, stageIndex).firstState;
  }

  static int resumeForagingCheckpoint(int checkpointIndex, int stageIndex) {
    return resumeStage(checkpointIndex, stageIndex).foragingCheckpoint;
  }

  static int resumeCollectionLeg(int checkpointIndex, int stageIndex) {
    return resumeStage(checkpointIndex, stageIndex).collectionLeg;
  }

  static boolean resumeCollectionFinalSale(int checkpointIndex, int stageIndex) {
    return resumeStage(checkpointIndex, stageIndex).collectionFinalSale;
  }

  static Stage resumeStage(int checkpointIndex, int stageIndex) {
    Checkpoint checkpoint = checkpoint(checkpointIndex);
    int index = Mth.clamp(stageIndex - 1, 0, checkpoint.stages.length - 1);
    return checkpoint.stages[index];
  }

  static Checkpoint checkpoint(int checkpointIndex) {
    Checkpoint[] checkpoints = Checkpoint.values();
    return checkpoints[Mth.clamp(checkpointIndex - 1, 0, checkpoints.length - 1)];
  }
}
