package ryzn.macro.client.feature.macros.impl.level7;

import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.Vec3;
import ryzn.macro.client.MacroInput;
import ryzn.macro.client.core.RyznBootstrap;
import ryzn.macro.client.core.events.api.CombatSessionEvent;
import ryzn.macro.client.feature.macros.impl.slayer.RevSlayerMacro;
import ryzn.macro.client.feature.modules.impl.combat.CombatModule;
import ryzn.macro.client.feature.modules.impl.combat.CombatTargeting;
import ryzn.macro.client.feature.pathfinder.path.PathExecutor;
import ryzn.macro.client.util.inventory.MacroItems;
import ryzn.macro.client.util.inventory.MacroSlots;
import ryzn.macro.client.util.text.MacroText;

/** Borrows Combat Bot for the one Revenant Horror I quest started by {@link RevLevel2Macro}. */
final class RevLevel2SlayerCombat {

  enum Result {
    RUNNING,
    COMPLETE,
    FAILED
  }

  private enum Phase {
    ZOMBIES,
    MINI_BOSS,
    BOSS,
    MADDOX_RETURN
  }

  private static final String UNDEAD_SWORD = "Undead Sword";
  private static final String BOSS_NAME = "Revenant Horror I";

  /**
   * The nametag lost its tier ("[Lv7] Revenant Horror 500/500"); the old tiered tag still works.
   */
  private static final List<String> BOSS_NAMETAGS = List.of("Revenant Horror", BOSS_NAME);

  private static final List<String> ZOMBIE_TARGETS = List.of("Graveyard Zombie", "Zombie Villager");
  private static final int SCAN_RANGE = 64;
  private static final int MISSING_TARGET_TICKS = 12;
  private static final double ATTACKER_RANGE_SQUARED = (double) SCAN_RANGE * SCAN_RANGE;
  private static final double RETURN_PATH_HALF_WIDTH_SQUARED = 2.5 * 2.5;
  private static final double RETURN_PATH_MAX_HEIGHT = 4.0;
  private static final double FIGHT_SPOT_RANGE_SQUARED = 6.0 * 6.0;
  private static final double RESUME_ZOMBIE_RANGE_SQUARED = 12.0 * 12.0;

  @Getter(AccessLevel.PACKAGE)
  private boolean active;

  private boolean restarting;
  private boolean combatInterrupted;
  private boolean bossSlain;
  private Phase phase;
  private String pendingMiniBoss;
  private UUID targetId;
  private UUID defenseTargetId;
  private UUID returnTargetId;
  private BlockPos returnPoint;
  private String returnLabel = "Maddox";
  private int missingTargetTicks;
  private int returnTargetMissingTicks;
  private String status = "Idle";

  boolean start(LocalPlayer player) {
    return start(player, Phase.ZOMBIES, null);
  }

  private boolean start(LocalPlayer player, Phase initialPhase, BlockPos destination) {
    if (player == null) {
      status = "Player is unavailable";
      return false;
    }
    if (RyznBootstrap.moduleManager().getByClass(CombatModule.class).isEnabled()) {
      status = "Combat Bot is already running";
      return false;
    }
    if (!selectUndeadSword(player)) {
      status = "Undead Sword must be in your hotbar";
      return false;
    }
    active = true;
    restarting = false;
    combatInterrupted = false;
    bossSlain = false;
    phase = initialPhase;
    pendingMiniBoss = "";
    targetId = null;
    defenseTargetId = null;
    returnTargetId = null;
    returnPoint = destination;
    missingTargetTicks = 0;
    returnTargetMissingTicks = 0;
    startCombat();
    return true;
  }

  boolean startMaddoxReturn(LocalPlayer player, BlockPos destination) {
    if (destination == null) {
      status = "Maddox position is unavailable";
      return false;
    }
    returnLabel = "Maddox";
    return start(player, Phase.MADDOX_RETURN, destination);
  }

  /** Walks back to where the zombies were being fought, killing only what gets in the way. */
  boolean startZombieReturn(LocalPlayer player, BlockPos destination) {
    returnLabel = "the zombies";
    return start(player, Phase.MADDOX_RETURN, destination);
  }

  /** Where the player stands while in melee with a quest zombie, or null when not fighting one. */
  BlockPos fightSpot(LocalPlayer player) {
    if (!active || phase != Phase.ZOMBIES || !player.onGround()) {
      return null;
    }
    final LivingEntity target =
        RyznBootstrap.moduleManager().getByClass(CombatModule.class).getTarget();
    return target != null
            && target.isAlive()
            && player.distanceToSqr(target) <= FIGHT_SPOT_RANGE_SQUARED
        ? player.blockPosition()
        : null;
  }

  boolean zombieNearby(Minecraft mc, LocalPlayer player) {
    for (Entity entity : mc.level.entitiesForRendering()) {
      if (entity instanceof LivingEntity living
          && player.distanceToSqr(living) <= RESUME_ZOMBIE_RANGE_SQUARED
          && isZombieTarget(living)) {
        return true;
      }
    }
    return false;
  }

  static LivingEntity findBoss(Minecraft mc) {
    for (String name : BOSS_NAMETAGS) {
      final LivingEntity boss = RevSlayerMacro.findOwnedBoss(mc, name);
      if (boss != null) {
        return boss;
      }
    }
    return null;
  }

  void stop() {
    active = false;
    bossSlain = false;
    pendingMiniBoss = "";
    targetId = null;
    defenseTargetId = null;
    returnTargetId = null;
    returnPoint = null;
    missingTargetTicks = 0;
    returnTargetMissingTicks = 0;
    restarting = true;
    RyznBootstrap.eventBus().post(CombatSessionEvent.stop());
    restarting = false;
  }

  Result tick(Minecraft mc) {
    if (!active) {
      return Result.FAILED;
    }
    if (bossSlain) {
      stop();
      status = "Revenant Horror I defeated";
      return Result.COMPLETE;
    }
    if (combatInterrupted) {
      status = "Combat Bot was interrupted";
      active = false;
      return Result.FAILED;
    }
    final LocalPlayer player = mc.player;
    if (player == null || mc.level == null || !selectUndeadSword(player)) {
      status = "Undead Sword must stay in your hotbar";
      stop();
      return Result.FAILED;
    }

    if (phase == Phase.MADDOX_RETURN) {
      tickMaddoxReturn(mc, player);
      status =
          returnTargetId == null
              ? "Returning to " + returnLabel
              : "Returning to " + returnLabel + " while killing a zombie";
      return Result.RUNNING;
    }

    final LivingEntity boss = findBoss(mc);
    if (boss != null && phase != Phase.BOSS) {
      phase = Phase.BOSS;
      targetId = boss.getUUID();
      defenseTargetId = null;
      missingTargetTicks = 0;
      startCombat();
    } else if (phase == Phase.ZOMBIES && !pendingMiniBoss.isBlank()) {
      final LivingEntity miniBoss = findNamedLiving(mc, pendingMiniBoss);
      if (miniBoss != null) {
        phase = Phase.MINI_BOSS;
        targetId = miniBoss.getUUID();
        missingTargetTicks = 0;
        startCombat();
      }
    }

    if (phase == Phase.BOSS) {
      tickBossDefense(mc, player);
    }

    if (phase == Phase.MINI_BOSS && findLiving(mc, targetId) == null) {
      if (++missingTargetTicks > MISSING_TARGET_TICKS) {
        phase = Phase.ZOMBIES;
        targetId = null;
        pendingMiniBoss = "";
        missingTargetTicks = 0;
        startCombat();
      }
    } else if (phase == Phase.BOSS && findLiving(mc, targetId) == null) {
      if (++missingTargetTicks > MISSING_TARGET_TICKS) {
        stop();
        status = "Revenant Horror I defeated";
        return Result.COMPLETE;
      }
    } else {
      missingTargetTicks = 0;
    }

    if (!RyznBootstrap.moduleManager().getByClass(CombatModule.class).isManaged()) {
      startCombat();
    }
    status =
        switch (phase) {
          case ZOMBIES -> "Combat Bot killing Graveyard Zombies";
          case MINI_BOSS -> "Combat Bot killing " + pendingMiniBoss;
          case BOSS ->
              defenseTargetId == null
                  ? "Combat Bot killing Revenant Horror I"
                  : "Combat Bot killing the zombie attacking you";
          case MADDOX_RETURN -> "Returning to " + returnLabel + " while killing zombies";

          default -> throw new MatchException(null, null);
        };
    return Result.RUNNING;
  }

  void onChat(String message) {
    if (!active) {
      return;
    }
    if (isBossSlainMessage(message)) {
      bossSlain = true;
      return;
    }
    final String miniBoss = miniBossNameFromChat(message);
    if (!miniBoss.isBlank() && phase == Phase.ZOMBIES) {
      pendingMiniBoss = miniBoss;
    }
  }

  String statusText() {
    return status;
  }

  static boolean isQuestStartedMessage(String message) {
    return MacroText.normalizeSearchText(message).contains("slayer quest started");
  }

  static boolean isBossSlainMessage(String message) {
    return MacroText.normalizeSearchText(message).equals("nice! slayer boss slain!");
  }

  static boolean isQuestFailedMessage(String message) {
    return MacroText.normalizeSearchText(message).equals("slayer quest failed!");
  }

  static boolean isZombieSlayerLevelTwoMessage(String message) {
    final String text = MacroText.normalizeSearchText(message);
    return text.equals("zombie slayer lvl 2")
        || text.startsWith("lvl up!") && text.endsWith("zombie slayer lvl 2!");
  }

  static boolean isPlayerDeathMessage(String message) {
    final String text = MacroText.normalizeSearchText(message);
    return text.startsWith("you died")
        || text.startsWith("you fell into the void")
        || text.startsWith("you were killed by");
  }

  static String miniBossNameFromChat(String message) {
    final String text = MacroText.collapseWhitespace(MacroText.stripFormatting(message));
    final String prefix = "SLAYER MINI-BOSS ";
    final String suffix = " has spawned!";
    if (!text.startsWith(prefix) || !text.endsWith(suffix)) {
      return "";
    }
    return text.substring(prefix.length(), text.length() - suffix.length()).trim();
  }

  private void startCombat() {
    if (!active) {
      return;
    }
    restarting = true;
    RyznBootstrap.eventBus().post(CombatSessionEvent.stop());
    RyznBootstrap.eventBus()
        .post(
            CombatSessionEvent.start(
                settingsForPhase(),
                ignored -> {
                  if (active && !restarting) {
                    combatInterrupted = true;
                  }
                }));
    restarting = false;
  }

  private CombatModule.ManagedSettings settingsForPhase() {
    if (phase == Phase.ZOMBIES) {
      return zombieSettings();
    }
    if (phase == Phase.BOSS) {
      return defenseTargetId == null ? bossSettings(targetId) : defenseSettings(defenseTargetId);
    }
    if (phase == Phase.MADDOX_RETURN) {
      return returnTargetId == null
          ? returnSettings(returnPoint)
          : returnTargetSettings(returnTargetId);
    }
    return new CombatModule.ManagedSettings(
        List.of(pendingMiniBoss),
        true,
        0,
        false,
        3.0,
        SCAN_RANGE,
        CombatModule.ManagedAttackMode.HOLD_IN_RANGE_MELEE,
        4,
        "",
        targetId);
  }

  static CombatModule.ManagedSettings zombieSettings() {
    return new CombatModule.ManagedSettings(
        ZOMBIE_TARGETS,
        true,
        0,
        true,
        3.0,
        SCAN_RANGE,
        CombatModule.ManagedAttackMode.MELEE,
        4,
        "",
        null);
  }

  static CombatModule.ManagedSettings bossSettings(UUID bossId) {
    return new CombatModule.ManagedSettings(
        BOSS_NAMETAGS,
        false,
        0,
        false,
        5.0,
        SCAN_RANGE,
        CombatModule.ManagedAttackMode.STATIONARY_MELEE,
        1,
        "",
        bossId);
  }

  static CombatModule.ManagedSettings defenseSettings(UUID attackerId) {
    return new CombatModule.ManagedSettings(
        ZOMBIE_TARGETS,
        false,
        0,
        false,
        3.0,
        SCAN_RANGE,
        CombatModule.ManagedAttackMode.HOLD_IN_RANGE_MELEE,
        1,
        "",
        attackerId);
  }

  static CombatModule.ManagedSettings returnSettings(BlockPos destination) {
    return new CombatModule.ManagedSettings(
        List.of(),
        true,
        0,
        true,
        3.0,
        SCAN_RANGE,
        CombatModule.ManagedAttackMode.PREPARE_SPAWN,
        4,
        "",
        null,
        "",
        "",
        List.of(destination));
  }

  static CombatModule.ManagedSettings returnTargetSettings(UUID zombieId) {
    return new CombatModule.ManagedSettings(
        ZOMBIE_TARGETS,
        true,
        0,
        false,
        3.0,
        SCAN_RANGE,
        CombatModule.ManagedAttackMode.HOLD_IN_RANGE_MELEE,
        4,
        "",
        zombieId);
  }

  private void tickBossDefense(Minecraft mc, LocalPlayer player) {
    if (defenseTargetId != null && findLiving(mc, defenseTargetId) == null) {
      defenseTargetId = null;
      startCombat();
    }
    final LivingEntity attacker = recentZombieAttacker(player);
    if (attacker == null || attacker.getUUID().equals(defenseTargetId)) {
      return;
    }
    defenseTargetId = attacker.getUUID();
    startCombat();
  }

  private void tickMaddoxReturn(Minecraft mc, LocalPlayer player) {
    final LivingEntity attacker = recentZombieAttacker(player);
    if (attacker != null && !attacker.getUUID().equals(returnTargetId)) {
      returnTargetId = attacker.getUUID();
      returnTargetMissingTicks = 0;
      retargetCombat();
      return;
    }
    if (returnTargetId != null) {
      if (findLiving(mc, returnTargetId) != null) {
        if (!RyznBootstrap.moduleManager().getByClass(CombatModule.class).isManaged()) {
          returnTargetMissingTicks = 0;
          startCombat();
          return;
        }
        final LivingEntity selected =
            RyznBootstrap.moduleManager().getByClass(CombatModule.class).getTarget();
        if (selected != null && returnTargetId.equals(selected.getUUID())) {
          returnTargetMissingTicks = 0;
          return;
        }
        if (!returnTargetSelectionTimedOut(++returnTargetMissingTicks)) {
          return;
        }
      }
      returnTargetId = null;
      returnTargetMissingTicks = 0;
      if (!RyznBootstrap.eventBus()
          .post(CombatSessionEvent.resumePatrolAt(returnSettings(returnPoint), returnPoint))
          .accepted()) {
        startCombat();
      }
      return;
    }
    final LivingEntity pathZombie = findReturnPathZombie(mc, player);
    if (pathZombie != null) {
      returnTargetId = pathZombie.getUUID();
      returnTargetMissingTicks = 0;
      retargetCombat();
    } else if (!RyznBootstrap.moduleManager().getByClass(CombatModule.class).isManaged()) {
      startCombat();
    }
  }

  static boolean returnTargetSelectionTimedOut(int ticks) {
    return ticks > MISSING_TARGET_TICKS;
  }

  private void retargetCombat() {
    if (!active) {
      return;
    }
    if (!RyznBootstrap.eventBus()
        .post(CombatSessionEvent.reconfigure(settingsForPhase(), true))
        .accepted()) {
      startCombat();
    }
  }

  private LivingEntity recentZombieAttacker(LocalPlayer player) {
    if (player.hurtTime <= 0) {
      return null;
    }
    final LivingEntity lastMob = player.getLastHurtByMob();
    if (isZombieAttacker(player, lastMob)) {
      return lastMob;
    }
    final DamageSource source = player.getLastDamageSource();
    final Entity sourceEntity = source == null ? null : source.getEntity();
    return sourceEntity instanceof LivingEntity living && isZombieAttacker(player, living)
        ? living
        : null;
  }

  private boolean isZombieAttacker(LocalPlayer player, LivingEntity attacker) {
    return isZombieTarget(attacker) && player.distanceToSqr(attacker) <= ATTACKER_RANGE_SQUARED;
  }

  private LivingEntity findReturnPathZombie(Minecraft mc, LocalPlayer player) {
    final Vec3 pathTarget = PathExecutor.instance.getCurrentMovementTarget();
    if (pathTarget == null) {
      return null;
    }
    LivingEntity closest = null;
    double closestDistance = Double.POSITIVE_INFINITY;
    for (Entity entity : mc.level.entitiesForRendering()) {
      if (!(entity instanceof LivingEntity living)
          || !isZombieTarget(living)
          || player.distanceToSqr(living) > ATTACKER_RANGE_SQUARED
          || Math.abs(living.getY() - player.getY()) > RETURN_PATH_MAX_HEIGHT
          || !isOnReturnPath(
              player.getX(),
              player.getZ(),
              pathTarget.x,
              pathTarget.z,
              living.getX(),
              living.getZ())) {
        continue;
      }
      final double distance = player.distanceToSqr(living);
      if (distance < closestDistance) {
        closest = living;
        closestDistance = distance;
      }
    }
    return closest;
  }

  static boolean isOnReturnPath(
      double startX, double startZ, double endX, double endZ, double targetX, double targetZ) {
    final double pathX = endX - startX;
    final double pathZ = endZ - startZ;
    final double pathLengthSquared = pathX * pathX + pathZ * pathZ;
    if (pathLengthSquared < 1.0e-6) {
      return false;
    }
    final double targetOffsetX = targetX - startX;
    final double targetOffsetZ = targetZ - startZ;
    final double progress = (targetOffsetX * pathX + targetOffsetZ * pathZ) / pathLengthSquared;
    if (progress < 0.0 || progress > 1.0) {
      return false;
    }
    final double lateralX = targetOffsetX - progress * pathX;
    final double lateralZ = targetOffsetZ - progress * pathZ;
    return lateralX * lateralX + lateralZ * lateralZ <= RETURN_PATH_HALF_WIDTH_SQUARED;
  }

  private boolean isZombieTarget(LivingEntity attacker) {
    if (attacker == null || !attacker.isAlive() || attacker instanceof ArmorStand) {
      return false;
    }
    for (String name : ZOMBIE_TARGETS) {
      if (CombatTargeting.matchesName(attacker, name)) {
        return true;
      }
    }
    return false;
  }

  private boolean selectUndeadSword(LocalPlayer player) {
    final int slot =
        MacroSlots.findHotbarSlot(
            player, stack -> MacroItems.stackMatchesName(stack, UNDEAD_SWORD));
    if (slot < 0) {
      return false;
    }
    MacroInput.selectHotbarSlot(player, slot);
    return true;
  }

  private LivingEntity findNamedLiving(Minecraft mc, String name) {
    for (Entity entity : mc.level.entitiesForRendering()) {
      if (entity instanceof LivingEntity living
          && !(living instanceof ArmorStand)
          && living.isAlive()
          && CombatTargeting.matchesName(living, name)) {
        return living;
      }
    }
    return null;
  }

  private LivingEntity findLiving(Minecraft mc, UUID id) {
    if (id == null) {
      return null;
    }
    for (Entity entity : mc.level.entitiesForRendering()) {
      if (entity instanceof LivingEntity living
          && id.equals(living.getUUID())
          && living.isAlive()) {
        return living;
      }
    }
    return null;
  }
}
