package ryzn.macro.client.feature.macros.impl.slayer;

import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.Vec3;
import ryzn.macro.client.MacroInput;
import ryzn.macro.client.config.RyznSettings;
import ryzn.macro.client.config.settings.ConfigSection;
import ryzn.macro.client.config.settings.Setting;
import ryzn.macro.client.core.RyznBootstrap;
import ryzn.macro.client.core.events.api.CombatSessionEvent;
import ryzn.macro.client.core.events.api.GameMessageEvent;
import ryzn.macro.client.core.events.api.RevSlayerActionEvent;
import ryzn.macro.client.core.events.api.Subscribe;
import ryzn.macro.client.feature.api.MenuToggle;
import ryzn.macro.client.feature.hud.SkyBlockHud;
import ryzn.macro.client.feature.macros.api.Macro;
import ryzn.macro.client.feature.modules.impl.combat.CombatModule;
import ryzn.macro.client.feature.modules.impl.combat.CombatTargeting;
import ryzn.macro.client.feature.rotation.impl.MacroAim;
import ryzn.macro.client.util.inventory.MacroItems;
import ryzn.macro.client.util.inventory.MacroSlots;
import ryzn.macro.client.util.text.MacroText;
import ryzn.macro.client.util.text.RyznChat;

public final class RevSlayerMacro extends Macro implements ConfigSection, MenuToggle {

  private static final String BOUQUET_NAME = "Bouquet of Lies";
  private static final String REAPER_FALCHION_NAME = "Reaper Falchion";
  private static final String KILL_WEAPON_NAME = "Giant's Sword";
  private static final String BOSS_NAME = "Revenant Horror IV";
  private static final String MINI_BOSS_PREFIX = "SLAYER MINI-BOSS ";
  private static final String MINI_BOSS_SUFFIX = " has spawned!";
  private static final List<String> GHOUL_TARGETS = List.of("Crypt Ghoul");
  private static final List<String> MINI_BOSS_NAMES =
      List.of(
          "Revenant Sycophant",
          "Revenant Champion",
          "Deformed Revenant",
          "Atoned Champion",
          "Atoned Revenant");
  private static final int MINI_BOSS_SPAWN_WAIT_TICKS = 12;
  private static final int MINI_BOSS_SIGNAL_WAIT_TICKS = 5;
  private static final int MINI_BOSS_CLEAR_WAIT_TICKS = 20;
  private static final int MINI_BOSS_SCAN_INTERVAL_TICKS = 4;
  private static final int BOSS_CLEAR_WAIT_TICKS = 10;
  private static final int SPAWN_SIGNAL_TICKS = 30;
  private static final long BOSS_LOOK_HANDOFF_MILLIS = 250L;
  private static final int NEW_ENTITY_TICKS = 20;
  private static final double SPAWN_ENTITY_RADIUS_SQ = 6.0 * 6.0;
  private static final double SPAWN_AIM_HEIGHT = 0.9;
  private static final double OWNER_TAG_XZ_SQ = 2.0 * 2.0;
  private static final double BOSS_BODY_XZ_SQ = 1.5 * 1.5;

  private final Deque<String> miniBossQueue = new ArrayDeque<>();
  private final Set<UUID> knownHostileIds = new LinkedHashSet<>();
  private final Set<UUID> discoveredMiniBossIds = new LinkedHashSet<>();
  private final MaddoxQuestAutomation maddox = new MaddoxQuestAutomation();
  private final float[] spawnAim = new float[2];

  private static final String DEFAULT_PATROL_ZONES =
      "-101,39,-86; -148,57,-101; -102,49,-117; -130,42,-139; -115,46,-96";

  /** Saved run toggle; startup, import, and export clear it through the run-toggle list. */
  private final Setting<Boolean> enabled = addSetting(Setting.bool("enabled", "Enabled", false));

  /** Reach of the ghoul weapon ability, in blocks. */
  private final Setting<Integer> abilityRange =
      addSetting(Setting.integer("ability_range", "Ability range", 18, 4, 24));

  /** Ticks between ability uses. */
  private final Setting<Integer> abilityCooldownTicks =
      addSetting(Setting.integer("ability_cooldown_ticks", "Ability cooldown", 20, 20, 60));

  /** Radius searched for targets, in blocks. */
  private final Setting<Integer> scanRange =
      addSetting(Setting.integer("scan_range", "Scan range", 48, 16, 96));

  /** Whether the combat walk sprints. */
  private final Setting<Boolean> sprint = addSetting(Setting.bool("sprint", "Sprint", true));

  /** Whether ghouls are fought with the Reaper Falchion instead of the Bouquet of Lies. */
  private final Setting<Boolean> swapToReaperFalchion =
      addSetting(Setting.bool("swap_to_reaper_falchion", "Reaper Falchion", true));

  /** Whether quests are started and claimed through Maddox automatically. */
  private final Setting<Boolean> automaticMaddox =
      addSetting(Setting.bool("automatic_maddox", "Automatic Maddox", false));

  /**
   * Semicolon-separated patrol points. Kept exactly as typed while editing and trimmed when the
   * section is loaded or saved, as it always was.
   */
  private final Setting<String> patrolZones =
      addSetting(Setting.rawText("patrol_zones", "Patrol zones", DEFAULT_PATROL_ZONES));

  private boolean managedStarted;
  // "Started" waits for the first tick's start checks, so a refused start prints only its reason.
  private boolean startAnnouncePending;
  private boolean stopping;
  private boolean restartRequested;
  private boolean miniBossSeen;
  private boolean knownHostilesReady;
  private boolean spawnLookActive;
  private boolean bossLookHandoffPending;
  private boolean maddoxLookHeld;
  private int missingTicks;
  private int bossKills;
  private long bossSpawnNanos;
  private long bossMissingSinceNanos;
  private long lastBossFightNanos;
  private long totalBossFightNanos;
  private float maddoxHoldYaw;
  private float maddoxHoldPitch;
  private Phase phase = Phase.GHOULS;
  private Phase announcedPhase;
  private String activeMiniBossName;
  private UUID miniBossId;
  private UUID bossId;
  private SpawnSignal spawnSignal;

  /**
   * @description Creates the process-lifetime Revenant slayer with its saved options.
   */
  public RevSlayerMacro() {
    super(
        "rev_slayer",
        "Slayer Macro",
        "Automates supported Slayer targets with dedicated weapon, targeting, and pathfinding"
            + " settings.");
  }

  /**
   * @description Loads every saved option under its original key, clamping the numbers and trimming
   *     the patrol zones.
   * @param object the section JSON; a missing or mistyped value falls back to its default
   */
  @Override
  public void read(JsonObject object) {
    ConfigSection.readSettings(
        object,
        enabled,
        abilityRange,
        abilityCooldownTicks,
        scanRange,
        sprint,
        swapToReaperFalchion,
        automaticMaddox,
        patrolZones);
    normalizePatrolZones();
  }

  /**
   * @description Writes every saved option in its original key order, trimming the patrol zones
   *     first exactly as a load would.
   * @return a new section object for the settings file
   */
  @Override
  public JsonObject write() {
    normalizePatrolZones();
    return ConfigSection.writeSettings(
        enabled,
        abilityRange,
        abilityCooldownTicks,
        scanRange,
        sprint,
        swapToReaperFalchion,
        automaticMaddox,
        patrolZones);
  }

  /**
   * @description Trims the stored patrol zones.
   */
  private void normalizePatrolZones() {
    patrolZones.set(patrolZones.get().trim());
  }

  /**
   * @description Re-arms the run after settings load: resets the phase and lets Maddox take over
   *     only when both the run and automatic Maddox are on.
   */
  public void applyConfig() {
    managedStarted = false;
    resetPhase();
    maddox.setEnabled(false);
    maddox.setEnabled(isEnabled() && isAutomaticMaddox());
  }

  /**
   * @description Feeds chat to Maddox and queues each announced mini-boss while running.
   * @param gamemessageevent the received message; overlays are ignored
   */
  public void onGameMessageEvent(GameMessageEvent gamemessageevent) {
    final Component message = gamemessageevent.message();
    if (gamemessageevent.overlay() || !isEnabled() || message == null) {
      return;
    }
    String plain = MacroText.collapseWhitespace(MacroText.stripFormatting(message.getString()));
    maddox.onChat(plain);
    String name = miniBossNameFromChat(plain);
    if (name.isBlank() || phase == Phase.BOSS) {
      return;
    }

    miniBossQueue.addLast(name);
    if (activeMiniBossName == null) {
      beginNextMiniBoss(false);
    }
  }

  /**
   * @description Applies a run-state request from the menu or failsafe.
   * @param revslayeractionevent the requested run state
   */
  @Subscribe
  public void onRevSlayerActionEvent(RevSlayerActionEvent revslayeractionevent) {
    setEnabled(revslayeractionevent.enabled());
  }

  public boolean isEnabled() {
    return enabled.get();
  }

  public String statusText() {
    if (maddox.ownsControl()) {
      return maddox.statusText();
    }
    if (spawnSignal != null) {
      return "Preparing for Slayer spawn";
    }
    return switch (phase) {
      case GHOULS ->
          RyznBootstrap.moduleManager().getByClass(CombatModule.class).getTarget() == null
              ? "Patrolling the Crypts"
              : "Fighting Crypt Ghoul";
      case MINI_BOSS ->
          miniBossId == null ? "Waiting for Slayer mini-boss" : "Killing Slayer mini-boss";
      case BOSS -> "Killing Revenant Horror IV";

      default -> throw new MatchException(null, null);
    };
  }

  public int slayerXpPerBoss() {
    return maddox.slayerXpPerBoss();
  }

  public long slayerXpPerHour() {
    return slayerXpPerHour(slayerXpPerBoss(), bossKills, totalBossFightNanos);
  }

  static long slayerXpPerHour(int xpPerBoss, int kills, long totalFightNanos) {
    if (xpPerBoss <= 0 || kills <= 0 || totalFightNanos <= 0L) {
      return 0L;
    }
    return Math.round(xpPerBoss * (double) kills * 3_600_000_000_000.0 / totalFightNanos);
  }

  public void setEnabled(boolean value) {
    if (isEnabled() == value) {
      return;
    }
    enabled.set(value);
    RyznSettings.get().save();
    if (!value) {
      announceStartIfPending();
      RyznChat.announce("Rev Slayer", false);
      stopManaged();
      resetPhase();
      maddox.setEnabled(false);
    } else {
      startAnnouncePending = true;
      maddox.setEnabled(isAutomaticMaddox());
    }
  }

  public void onClientTick(Minecraft mc) {
    if (!isEnabled() || mc == null || mc.player == null || mc.level == null) {
      return;
    }

    maddox.setEnabled(isAutomaticMaddox());
    if (maddoxLookHeld && !maddox.ownsControl()) {
      maddoxLookHeld = false;
    }
    if (handleMaddox(mc)) {
      return;
    }
    if (mc.gui.screen() != null) {
      return;
    }

    updatePhase(mc);
    if (handleMaddox(mc)) {
      return;
    }
    rememberHostiles(mc);
    boolean preparingSpawn = spawnSignal != null;
    boolean huntingGhouls = isHuntingGhouls();
    boolean falchionSwap = !preparingSpawn && huntingGhouls && isSwapToReaperFalchion();
    String weaponName =
        preparingSpawn
            ? KILL_WEAPON_NAME
            : falchionSwap ? REAPER_FALCHION_NAME : huntingGhouls ? BOUQUET_NAME : KILL_WEAPON_NAME;
    LocalPlayer player = mc.player;
    int weaponSlot =
        MacroSlots.findHotbarSlot(player, stack -> MacroItems.stackMatchesName(stack, weaponName));
    if (weaponSlot < 0) {
      stopWithMessage(weaponName + " must be in your hotbar.");
      return;
    }
    if (falchionSwap
        && MacroSlots.findHotbarSlot(
                player, stack -> MacroItems.stackMatchesName(stack, BOUQUET_NAME))
            < 0) {
      stopWithMessage(BOUQUET_NAME + " must be in your hotbar.");
      return;
    }

    if (restartRequested && managedStarted) {
      stopManagedForRestart();
    }
    if (!RyznBootstrap.moduleManager().getByClass(CombatModule.class).isManagingAbilitySwap()) {
      MacroInput.selectHotbarSlot(player, weaponSlot);
    }
    if (managedStarted
        && RyznBootstrap.moduleManager().getByClass(CombatModule.class).isManaged()) {
      return;
    }
    if (!managedStarted
        && RyznBootstrap.moduleManager().getByClass(CombatModule.class).isManaged()) {
      stopWithMessage("another macro already has Combat Bot. Stop that one first.");
      return;
    }

    List<BlockPos> patrolZones = huntingGhouls ? parsePatrolZones(getPatrolZones()) : List.of();
    if (!preparingSpawn && huntingGhouls && patrolZones.isEmpty()) {
      stopWithMessage(
          "add at least one patrol zone in the Ryzn menu, written as §fx,y,z; x,y,z§7.");
      return;
    }
    announceStartIfPending();
    CombatModule.ManagedSettings managed = settingsForPhase(patrolZones);
    if (managed == null) {
      return;
    }

    restartRequested = false;
    managedStarted = true;
    RyznBootstrap.eventBus().post(CombatSessionEvent.start(managed, this::onCombatStopped));
    if (bossLookHandoffPending) {
      bossLookHandoffPending = false;
      RyznBootstrap.eventBus().post(CombatSessionEvent.holdLook(BOSS_LOOK_HANDOFF_MILLIS));
    }
    if (announcedPhase != phase) {
      announcedPhase = phase;
      final String status =
          switch (phase) {
            case GHOULS -> "working the " + patrolZones.size() + " Crypt zones for Ghouls.";
            case MINI_BOSS -> "going after " + activeMiniBossName + ".";
            case BOSS -> "your Revenant Horror IV is up - locking onto it.";

            default -> throw new MatchException(null, null);
          };
      RyznChat.send("§bRev Slayer§7: §f" + status);
    }
  }

  static String miniBossNameFromChat(String message) {
    String plain = MacroText.collapseWhitespace(MacroText.stripFormatting(message));
    if (!plain.startsWith(MINI_BOSS_PREFIX) || !plain.endsWith(MINI_BOSS_SUFFIX)) {
      return "";
    }
    return plain
        .substring(MINI_BOSS_PREFIX.length(), plain.length() - MINI_BOSS_SUFFIX.length())
        .trim();
  }

  static boolean ownerTagMatches(String tag, Set<String> identityNames) {
    String normalized = MacroText.normalizeSearchText(tag);
    int marker = normalized.indexOf("spawned by:");
    if (marker < 0) {
      return false;
    }
    String owner = normalized.substring(marker + "spawned by:".length()).trim();
    for (String identity : identityNames) {
      String name = MacroText.normalizeSearchText(identity);
      if (!name.isBlank() && (owner.equals(name) || owner.endsWith(" " + name))) {
        return true;
      }
    }
    return false;
  }

  static List<BlockPos> parsePatrolZones(String value) {
    if (value == null || value.isBlank()) {
      return List.of();
    }
    List<BlockPos> points = new ArrayList<>();
    for (String entry : value.split(";")) {
      String[] coordinates = entry.trim().split("[,\\s]+");
      if (coordinates.length != 3) {
        continue;
      }
      try {
        BlockPos point =
            new BlockPos(
                Integer.parseInt(coordinates[0]),
                Integer.parseInt(coordinates[1]),
                Integer.parseInt(coordinates[2]));
        if (!points.contains(point)) {
          points.add(point);
        }
      } catch (NumberFormatException ignored) {
        // Invalid entries are skipped; startup reports an error only when no valid zone remains.
      }
    }
    return List.copyOf(points);
  }

  public void onParticle(ClientboundLevelParticlesPacket packet) {
    if (!isEnabled() || packet == null || !isSlayerSpawnParticle(packet.getParticle().getType())) {
      return;
    }
    Minecraft mc = Minecraft.getInstance();
    recordSpawnSignal(
        mc,
        new Vec3(packet.getX(), packet.getY(), packet.getZ()),
        isMiniBossSpawnParticle(packet.getParticle().getType()));
  }

  public void onSound(ClientboundSoundPacket packet) {
    if (!isEnabled()
        || packet == null
        || !isSlayerSpawnSound(packet.getSound().value().location().toString())) {
      return;
    }
    Minecraft mc = Minecraft.getInstance();
    recordSpawnSignal(mc, new Vec3(packet.getX(), packet.getY(), packet.getZ()), false);
  }

  static boolean isSlayerSpawnSound(String soundId) {
    if (soundId == null) {
      return false;
    }
    return switch (soundId) {
      case "minecraft:entity.wither.spawn",
          "minecraft:entity.ender_dragon.growl",
          "minecraft:entity.zombie_villager.cure",
          "minecraft:entity.evoker.prepare_summon",
          "minecraft:block.end_portal.spawn" ->
          true;
      default -> false;
    };
  }

  static boolean isSlayerSpawnParticle(ParticleType<?> type) {
    return ParticleTypes.ENCHANT.equals(type)
        || ParticleTypes.WITCH.equals(type)
        || ParticleTypes.ENTITY_EFFECT.equals(type)
        || isMiniBossSpawnParticle(type);
  }

  static boolean isMiniBossSpawnParticle(ParticleType<?> type) {
    return ParticleTypes.EXPLOSION.equals(type) || ParticleTypes.EXPLOSION_EMITTER.equals(type);
  }

  private void recordSpawnSignal(Minecraft mc, Vec3 position, boolean miniBossParticle) {
    if (mc == null || mc.player == null || mc.level == null || !knownHostilesReady) {
      return;
    }
    boolean bossExpected = SkyBlockHud.sidebarContainsText("Slay the boss");
    boolean miniBossExpected =
        miniBossParticle
            && phase == Phase.MINI_BOSS
            && miniBossId == null
            && activeMiniBossName != null;
    if (phase == Phase.BOSS || (!bossExpected && !miniBossExpected)) {
      return;
    }
    int tick = mc.player.tickCount;
    if (spawnSignal != null && tick <= spawnSignal.expiresAtTick()) {
      return;
    }
    spawnSignal =
        new SpawnSignal(
            position, tick + SPAWN_SIGNAL_TICKS, Set.copyOf(knownHostileIds), bossExpected);
    if (bossExpected && bossSpawnNanos == 0L) {
      bossSpawnNanos = System.nanoTime();
    }
    int weaponSlot =
        MacroSlots.findHotbarSlot(
            mc.player, stack -> MacroItems.stackMatchesName(stack, KILL_WEAPON_NAME));
    if (weaponSlot >= 0) {
      MacroInput.selectHotbarSlot(mc.player, weaponSlot);
    }
    restartRequested = true;
  }

  private void updatePhase(Minecraft mc) {
    acquireSignaledSpawn(mc);
    if (phase == Phase.BOSS) {
      LivingEntity boss = findLivingEntity(mc, bossId);
      if (boss != null && boss.isAlive()) {
        missingTicks = 0;
        bossMissingSinceNanos = 0L;
        return;
      }
      if (bossMissingSinceNanos == 0L) {
        bossMissingSinceNanos = System.nanoTime();
      }
      if (boss != null || ++missingTicks >= BOSS_CLEAR_WAIT_TICKS) {
        finishBossFight(mc);
      }
      return;
    }

    LivingEntity ownedBoss = findOwnedBoss(mc, BOSS_NAME);
    if (ownedBoss != null) {
      spawnSignal = null;
      miniBossQueue.clear();
      discoveredMiniBossIds.clear();
      activeMiniBossName = null;
      miniBossId = null;
      missingTicks = 0;
      bossId = ownedBoss.getUUID();
      if (bossSpawnNanos == 0L) {
        bossSpawnNanos = System.nanoTime();
      }
      bossMissingSinceNanos = 0L;
      phase = Phase.BOSS;
      restartRequested = true;
      return;
    }

    if (phase != Phase.MINI_BOSS) {
      return;
    }
    boolean trackedMiniBoss = miniBossId != null;
    LivingEntity signaledMiniBoss = findLivingEntity(mc, miniBossId);
    if (signaledMiniBoss != null && signaledMiniBoss.isAlive()) {
      miniBossSeen = true;
      missingTicks = 0;
      return;
    }
    miniBossId = null;
    if (trackedMiniBoss) {
      spawnSignal = null;
      finishActiveMiniBoss(true);
      return;
    }
    LivingEntity visibleMiniBoss = findVisibleMiniBoss(mc);
    if (visibleMiniBoss != null) {
      spawnSignal = null;
      miniBossId = visibleMiniBoss.getUUID();
      miniBossSeen = true;
      missingTicks = 0;
      announcedPhase = null;
      restartRequested = true;
      return;
    }

    if (spawnSignal != null && !spawnSignal.boss() && missingTicks >= MINI_BOSS_SIGNAL_WAIT_TICKS) {
      spawnSignal = null;
      restartRequested = true;
    }

    int wait = miniBossSeen ? MINI_BOSS_CLEAR_WAIT_TICKS : MINI_BOSS_SPAWN_WAIT_TICKS;
    if (++missingTicks >= wait) {
      finishActiveMiniBoss(miniBossSeen);
    }
  }

  private void acquireSignaledSpawn(Minecraft mc) {
    if (spawnSignal == null) {
      return;
    }
    if (mc.player.tickCount > spawnSignal.expiresAtTick()) {
      spawnSignal = null;
      restartRequested = true;
      return;
    }

    if (spawnSignal.boss()) {
      return;
    }
    if (phase != Phase.MINI_BOSS || activeMiniBossName == null) {
      return;
    }

    LivingEntity candidate = findSignaledMiniBoss(mc, spawnSignal);
    if (candidate == null) {
      return;
    }
    spawnSignal = null;
    missingTicks = 0;
    miniBossId = candidate.getUUID();
    miniBossSeen = true;
    announcedPhase = null;
    restartRequested = true;
  }

  private LivingEntity findSignaledMiniBoss(Minecraft mc, SpawnSignal signal) {
    LivingEntity closest = null;
    double closestDistance = Double.POSITIVE_INFINITY;
    for (Entity entity : mc.level.entitiesForRendering()) {
      if (!(entity instanceof LivingEntity living)
          || living instanceof ArmorStand
          || !(living instanceof Enemy)
          || !living.isAlive()) {
        continue;
      }
      if (!matchesMiniBossName(living)) {
        continue;
      }
      boolean newlyObserved = !signal.baseline().contains(living.getUUID());
      if (!newlyObserved && living.tickCount > NEW_ENTITY_TICKS) {
        continue;
      }
      double distance = living.position().distanceToSqr(signal.position());
      if (distance > SPAWN_ENTITY_RADIUS_SQ || distance >= closestDistance) {
        continue;
      }
      closest = living;
      closestDistance = distance;
    }
    return closest;
  }

  private void rememberHostiles(Minecraft mc) {
    knownHostileIds.clear();
    boolean scanMiniBosses =
        phase != Phase.BOSS
            && Math.floorMod(mc.player.tickCount, MINI_BOSS_SCAN_INTERVAL_TICKS) == 0;
    Set<UUID> loadedMiniBossIds = new LinkedHashSet<>();
    List<DetectedMiniBoss> detected = new ArrayList<>();
    double scanRangeSq = Math.pow(getScanRange(), 2.0);
    for (Entity entity : mc.level.entitiesForRendering()) {
      if (entity instanceof LivingEntity living
          && !(living instanceof ArmorStand)
          && living instanceof Enemy
          && living.isAlive()) {
        knownHostileIds.add(living.getUUID());
        if (!scanMiniBosses) {
          continue;
        }
        String name = miniBossName(living);
        if (name.isBlank()) {
          continue;
        }
        loadedMiniBossIds.add(living.getUUID());
        if (mc.player.distanceToSqr(living) <= scanRangeSq
            && !discoveredMiniBossIds.contains(living.getUUID())) {
          detected.add(new DetectedMiniBoss(name, living.getUUID(), living.tickCount));
        }
      }
    }
    if (scanMiniBosses) {
      discoveredMiniBossIds.retainAll(loadedMiniBossIds);
      detected.sort((left, right) -> Integer.compare(right.ageTicks(), left.ageTicks()));
      for (DetectedMiniBoss miniBoss : detected) {
        discoverMiniBoss(miniBoss);
      }
    }
    knownHostilesReady = true;
  }

  private void discoverMiniBoss(DetectedMiniBoss miniBoss) {
    if (!discoveredMiniBossIds.add(miniBoss.id())) {
      return;
    }
    if (activeMiniBossName == null) {
      activeMiniBossName = miniBoss.name();
      miniBossId = miniBoss.id();
      miniBossSeen = true;
      missingTicks = 0;
      phase = Phase.MINI_BOSS;
      announcedPhase = null;
      restartRequested = true;
      return;
    }
    if (miniBossId == null && activeMiniBossName.equals(miniBoss.name())) {
      miniBossId = miniBoss.id();
      miniBossSeen = true;
      missingTicks = 0;
      announcedPhase = null;
      restartRequested = true;
      return;
    }
    miniBossQueue.addLast(miniBoss.name());
  }

  private CombatModule.ManagedSettings settingsForPhase(List<BlockPos> patrolZones) {
    if (spawnSignal != null) {
      return new CombatModule.ManagedSettings(
          List.of(),
          isSprint(),
          0,
          true,
          3.0,
          getScanRange(),
          CombatModule.ManagedAttackMode.PREPARE_SPAWN,
          1,
          "",
          null,
          "",
          "",
          List.of(spawnPathPoint(spawnSignal.position())));
    }
    return switch (phase) {
      case GHOULS -> ghoulSettings(patrolZones);
      case MINI_BOSS ->
          miniBossId == null
              ? ghoulSettings(patrolZones)
              : new CombatModule.ManagedSettings(
                  List.of(activeMiniBossName),
                  isSprint(),
                  0,
                  false,
                  3.0,
                  getScanRange(),
                  CombatModule.ManagedAttackMode.HOLD_IN_RANGE_MELEE,
                  4,
                  "",
                  miniBossId);
      case BOSS ->
          bossId == null
              ? null
              : new CombatModule.ManagedSettings(
                  List.of(BOSS_NAME),
                  false,
                  0,
                  false,
                  5.0,
                  getScanRange(),
                  CombatModule.ManagedAttackMode.STATIONARY_MELEE,
                  1,
                  "",
                  bossId);

      default -> throw new MatchException(null, null);
    };
  }

  private CombatModule.ManagedSettings ghoulSettings(List<BlockPos> patrolZones) {
    return new CombatModule.ManagedSettings(
        GHOUL_TARGETS,
        isSprint(),
        0,
        true,
        isSwapToReaperFalchion() ? getScanRange() : getAbilityRange(),
        getScanRange(),
        CombatModule.ManagedAttackMode.CLUSTERED_USE_HELD_ITEM,
        isSwapToReaperFalchion() ? 20 : getAbilityCooldownTicks(),
        "",
        null,
        isSwapToReaperFalchion() ? BOUQUET_NAME : "",
        isSwapToReaperFalchion() ? REAPER_FALCHION_NAME : "",
        patrolZones);
  }

  public static LivingEntity findOwnedBoss(Minecraft mc, String bossName) {
    Set<String> identityNames = playerIdentityNames(mc);
    if (identityNames.isEmpty()) {
      return null;
    }
    for (Entity entity : mc.level.entitiesForRendering()) {
      if (!entity.hasCustomName()) {
        continue;
      }
      Component name = entity.getCustomName();
      if (name == null || !CombatTargeting.nametagMatches(name.getString(), bossName)) {
        continue;
      }
      if (!hasOwnerTag(mc, entity, identityNames)) {
        continue;
      }
      LivingEntity body = findBossBody(mc, entity);
      if (body != null) {
        return body;
      }
    }
    return null;
  }

  private static LivingEntity findBossBody(Minecraft mc, Entity nameTag) {
    if (nameTag instanceof LivingEntity living
        && !(living instanceof ArmorStand)
        && living instanceof Enemy
        && living.isAlive()) {
      return living;
    }

    LivingEntity closest = null;
    double bestScore = Double.POSITIVE_INFINITY;
    for (Entity entity : mc.level.entitiesForRendering()) {
      if (!(entity instanceof LivingEntity living)
          || living instanceof ArmorStand
          || !(living instanceof Enemy)
          || !living.isAlive()) {
        continue;
      }
      double dx = living.getX() - nameTag.getX();
      double dz = living.getZ() - nameTag.getZ();
      double horizontalSq = dx * dx + dz * dz;
      if (horizontalSq > BOSS_BODY_XZ_SQ) {
        continue;
      }
      if (nameTag.getY() < living.getBoundingBox().minY - 0.75
          || nameTag.getY() > living.getBoundingBox().maxY + 4.25) {
        continue;
      }
      double score = horizontalSq + Math.abs(nameTag.getY() - living.getBoundingBox().maxY) * 0.01;
      if (score < bestScore) {
        closest = living;
        bestScore = score;
      }
    }
    return closest;
  }

  private static boolean hasOwnerTag(Minecraft mc, Entity bossTag, Set<String> identityNames) {
    for (Entity entity : mc.level.entitiesForRendering()) {
      if (entity == bossTag || !entity.hasCustomName()) {
        continue;
      }
      if (Math.abs(entity.getY() - bossTag.getY()) > 6.0) {
        continue;
      }
      double dx = entity.getX() - bossTag.getX();
      double dz = entity.getZ() - bossTag.getZ();
      if (dx * dx + dz * dz > OWNER_TAG_XZ_SQ) {
        continue;
      }
      Component name = entity.getCustomName();
      if (name != null && ownerTagMatches(name.getString(), identityNames)) {
        return true;
      }
    }
    return false;
  }

  private static Set<String> playerIdentityNames(Minecraft mc) {
    Set<String> names = new LinkedHashSet<>();
    if (mc.player != null && mc.player.getGameProfile() != null) {
      names.add(mc.player.getGameProfile().name());
    }
    names.removeIf(name -> name == null || MacroText.normalizeSearchText(name).isBlank());
    return names;
  }

  private LivingEntity findVisibleMiniBoss(Minecraft mc) {
    LivingEntity oldest = null;
    for (Entity entity : mc.level.entitiesForRendering()) {
      if (!(entity instanceof LivingEntity living)
          || living instanceof ArmorStand
          || !living.isAlive()) {
        continue;
      }
      if (matchesMiniBossName(living) && (oldest == null || living.tickCount > oldest.tickCount)) {
        oldest = living;
      }
    }
    return oldest;
  }

  private boolean matchesMiniBossName(LivingEntity living) {
    return activeMiniBossName != null && CombatTargeting.matchesName(living, activeMiniBossName);
  }

  private static String miniBossName(LivingEntity living) {
    for (String name : MINI_BOSS_NAMES) {
      if (CombatTargeting.matchesName(living, name)) {
        return name;
      }
    }
    return "";
  }

  private static LivingEntity findLivingEntity(Minecraft mc, UUID id) {
    if (id == null) {
      return null;
    }
    for (Entity entity : mc.level.entitiesForRendering()) {
      if (entity instanceof LivingEntity living && id.equals(living.getUUID())) {
        return living;
      }
    }
    return null;
  }

  private void changePhase(Phase next) {
    phase = next;
    if (next != Phase.MINI_BOSS) {
      miniBossId = null;
    }
    miniBossSeen = false;
    missingTicks = 0;
    restartRequested = true;
  }

  private void beginNextMiniBoss(boolean restart) {
    activeMiniBossName = pollNextMiniBoss(miniBossQueue);
    miniBossId = null;
    miniBossSeen = false;
    missingTicks = 0;
    spawnSignal = null;
    if (activeMiniBossName == null) {
      phase = Phase.GHOULS;
    } else {
      phase = Phase.MINI_BOSS;
    }
    if (restart) {
      restartRequested = true;
    }
  }

  private void finishActiveMiniBoss(boolean targeted) {
    activeMiniBossName = null;
    miniBossId = null;
    beginNextMiniBoss(targeted);
    if (activeMiniBossName == null && targeted) {
      changePhase(Phase.GHOULS);
    }
  }

  private boolean isHuntingGhouls() {
    return phase == Phase.GHOULS || phase == Phase.MINI_BOSS && miniBossId == null;
  }

  static String pollNextMiniBoss(Deque<String> queue) {
    return queue == null ? null : queue.pollFirst();
  }

  private boolean handleMaddox(Minecraft mc) {
    if (!maddox.ownsControl()) {
      return false;
    }
    if (managedStarted) {
      stopManagedForRestart();
    }
    String error = maddox.tick(mc);
    if (error != null) {
      stopWithMessage(error);
    } else {
      announceStartIfPending();
    }
    return true;
  }

  private void finishBossFight(Minecraft mc) {
    long endNanos = bossMissingSinceNanos > 0L ? bossMissingSinceNanos : System.nanoTime();
    if (bossSpawnNanos > 0L && endNanos >= bossSpawnNanos) {
      lastBossFightNanos = endNanos - bossSpawnNanos;
      totalBossFightNanos += lastBossFightNanos;
      bossKills++;
    }
    bossSpawnNanos = 0L;
    bossMissingSinceNanos = 0L;
    bossId = null;
    boolean automaticMaddox = isAutomaticMaddox();
    bossLookHandoffPending = !automaticMaddox;
    changePhase(Phase.GHOULS);
    if (automaticMaddox) {
      maddox.bossDefeated();
      if (maddox.ownsControl() && mc.player != null) {
        maddoxHoldYaw = mc.player.getYRot();
        maddoxHoldPitch = mc.player.getXRot();
        maddoxLookHeld = true;
      }
    }
  }

  private void onCombatStopped(CombatModule.ManagedResult result) {
    managedStarted = false;
    if (!isEnabled() || stopping) {
      return;
    }
    disableWithoutStopping();
    RyznChat.send(
        "§bRev Slayer§7: §estopped §7- Combat Bot was interrupted. Start it again once "
            + "you are back in position.");
  }

  private void stopWithMessage(String message) {
    if (!isEnabled()) {
      return;
    }
    startAnnouncePending = false;
    disableWithoutStopping();
    stopManaged();
    resetPhase();
    RyznChat.send("§bRev Slayer§7: §cstopped §7- " + message);
  }

  private void announceStartIfPending() {
    if (startAnnouncePending) {
      startAnnouncePending = false;
      RyznChat.announce("Rev Slayer", true);
    }
  }

  private void disableWithoutStopping() {
    enabled.set(false);
    RyznSettings.get().save();
  }

  private void stopManagedForRestart() {
    stopping = true;
    RyznBootstrap.eventBus().post(CombatSessionEvent.stop());
    stopping = false;
    managedStarted = false;
  }

  private void stopManaged() {
    if (!managedStarted) {
      return;
    }
    stopManagedForRestart();
  }

  private void resetPhase() {
    maddox.setEnabled(false);
    phase = Phase.GHOULS;
    announcedPhase = null;
    miniBossQueue.clear();
    activeMiniBossName = null;
    miniBossId = null;
    bossId = null;
    spawnSignal = null;
    knownHostileIds.clear();
    discoveredMiniBossIds.clear();
    knownHostilesReady = false;
    spawnLookActive = false;
    bossLookHandoffPending = false;
    maddoxLookHeld = false;
    RyznBootstrap.rotationManager().resetAim(this);
    miniBossSeen = false;
    missingTicks = 0;
    restartRequested = false;
    bossKills = 0;
    bossSpawnNanos = 0L;
    bossMissingSinceNanos = 0L;
    lastBossFightNanos = 0L;
    totalBossFightNanos = 0L;
  }

  public void tickLook() {
    Minecraft mc = Minecraft.getInstance();
    if (isEnabled() && maddoxLookHeld && maddox.ownsControl() && mc.player != null) {
      if (spawnLookActive) {
        RyznBootstrap.rotationManager().resetAim(this);
      }
      spawnLookActive = false;
      RyznBootstrap.rotationManager().applyLook(mc.player, maddoxHoldYaw, maddoxHoldPitch);
      return;
    }
    if (!isEnabled()
        || spawnSignal == null
        || mc.player == null
        || mc.level == null
        || mc.gui.screen() != null) {
      if (spawnLookActive) {
        RyznBootstrap.rotationManager().resetAim(this);
      }
      spawnLookActive = false;
      return;
    }
    spawnLookActive = true;
    Vec3 point = spawnSignal.position().add(0.0, SPAWN_AIM_HEIGHT, 0.0);
    MacroAim.desiredYawPitch(mc.player.getEyePosition(), point, spawnAim);
    RyznBootstrap.rotationManager()
        .aimCustom(this, mc.player, spawnAim[0], spawnAim[1], 0.2f, 0.55f, 24.0f, 0.45f, 12.0f);
  }

  public void onRenderFrame() {
    tickLook();
  }

  static BlockPos spawnPathPoint(Vec3 position) {
    return new BlockPos(
        (int) Math.floor(position.x), (int) Math.floor(position.y), (int) Math.floor(position.z));
  }

  private static String seconds(long nanos) {
    return String.format(Locale.ROOT, "%.1fs", Math.max(0L, nanos) / 1_000_000_000.0);
  }

  private record SpawnSignal(Vec3 position, int expiresAtTick, Set<UUID> baseline, boolean boss) {}

  private record DetectedMiniBoss(String name, UUID id, int ageTicks) {}

  private enum Phase {
    GHOULS,
    MINI_BOSS,
    BOSS
  }

  /**
   * @description Returns whether the combat walk sprints.
   * @return the saved value
   */
  public boolean isSprint() {
    return sprint.get();
  }

  /**
   * @description Changes whether the combat walk sprints; the caller saves the settings file.
   * @param value the new value
   */
  public void setSprint(boolean value) {
    sprint.set(value);
  }

  /**
   * @description Returns whether ghouls are fought with the Reaper Falchion.
   * @return the saved value
   */
  public boolean isSwapToReaperFalchion() {
    return swapToReaperFalchion.get();
  }

  /**
   * @description Returns whether Maddox quests are automated.
   * @return the saved value
   */
  public boolean isAutomaticMaddox() {
    return automaticMaddox.get();
  }

  /**
   * @description Returns the patrol zones text.
   * @return the saved value
   */
  public String getPatrolZones() {
    return patrolZones.get();
  }

  /**
   * @description Returns the ability range, clamped to 4-24 blocks.
   * @return the saved value
   */
  public int getAbilityRange() {
    return abilityRange.get();
  }

  /**
   * @description Returns the ability cooldown, clamped to 20-60 ticks.
   * @return the saved value
   */
  public int getAbilityCooldownTicks() {
    return abilityCooldownTicks.get();
  }

  /**
   * @description Returns the target scan range, clamped to 16-96 blocks.
   * @return the saved value
   */
  public int getScanRange() {
    return scanRange.get();
  }

  @Override
  public boolean menuEnabled() {
    return isEnabled();
  }

  @Override
  public void menuToggle() {
    RyznBootstrap.eventBus().post(new RevSlayerActionEvent(!isEnabled()));
  }
}
