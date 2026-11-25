package ryzn.macro.client.feature.modules.impl.combat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import ryzn.macro.client.MacroInput;
import ryzn.macro.client.feature.hud.SkyBlockHud;

/**
 * Target validity + cost classification for the {@link CombatModule}.
 *
 * <p>The hostile/attackable {@link TargetFilter} is a pluggable seam: the default is vanilla {@link
 * Enemy}, but config swaps it (to widen the filter for server-custom mobs) without touching the
 * module's selection loop. The {@link #cost} metric is fixed (flow-first distance scoring).
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public final class CombatTargeting {

  private final CombatHazards hazards;
  private final CombatSight sight;

  private static final double NAMETAG_SCAN_XZ = 2.0;
  private static final double NAMETAG_SCAN_XZ_SQ = NAMETAG_SCAN_XZ * NAMETAG_SCAN_XZ;
  private static final double NAMETAG_SCAN_DOWN = 0.75;
  private static final double NAMETAG_SCAN_UP = 4.25;

  /** Pluggable hostile/attackable filter. */
  @FunctionalInterface
  public interface TargetFilter {
    boolean isHostile(LivingEntity target);
  }

  /** An entity-type rule that only applies while the player is in a specific SkyBlock area. */
  private record AreaRule(String area, EntityType<?> type) {}

  /**
   * Area-gated targeting: while the sidebar area (via {@link SkyBlockHud#currentArea()}) contains
   * {@code area} (case-insensitive), every entity of {@code type} counts as attackable on top of
   * the configured filter. Matching by vanilla entity type is robust to the floating nametag living
   * on a separate display entity: the Graveyard Zombie is a vanilla {@code minecraft:zombie}, so
   * gating zombies to the Graveyard tracks exactly them. Add a row to cover another area's mob.
   *
   * <p>This table used to live in {@code CombatEspModule}, which meant the fight logic asked a
   * render class whether a mob was attackable. It lives here now so the ESP can be a pure visual
   * module; the ESP reads {@link #activeAreaTypes()} for its own colouring.
   */
  private static final class AreaRules {
    /** Loaded on first use, so building the module never touches the entity registry. */
    static final List<AreaRule> ALL = List.of(new AreaRule("graveyard", EntityTypes.ZOMBIE));
  }

  private String cachedArea = "";
  private List<EntityType<?>> cachedAreaTypes = List.of();

  /**
   * True when an area rule currently in force covers this entity's type.
   *
   * @param entity the entity to test
   * @return true when an active area rule covers its type
   */
  public boolean matchesAreaRule(Entity entity) {
    return entity != null && activeAreaTypes().contains(entity.getType());
  }

  /**
   * Entity types whose {@link AreaRule} matches the current sidebar area; empty if none are active.
   *
   * @return the entity types the current area adds, possibly empty
   */
  public List<EntityType<?>> activeAreaTypes() {
    if (AreaRules.ALL.isEmpty()) {
      return List.of();
    }
    final String area = SkyBlockHud.currentArea();
    final String current = area == null ? "" : area;
    if (current.equals(cachedArea)) {
      return cachedAreaTypes;
    }
    if (current.isBlank()) {
      cachedArea = current;
      cachedAreaTypes = List.of();
      return cachedAreaTypes;
    }

    final String lower = current.toLowerCase(Locale.ROOT);
    final List<EntityType<?>> out = new ArrayList<>();
    for (AreaRule rule : AreaRules.ALL) {
      if (lower.contains(rule.area())) {
        out.add(rule.type());
      }
    }
    cachedArea = current;
    cachedAreaTypes = out.isEmpty() ? List.of() : List.copyOf(out);
    return cachedAreaTypes;
  }

  /**
   * Default: any vanilla hostile mob (skeletons, zombies, spiders, ...). Narrowed by config via
   * {@link #setTargetFilter}.
   */
  public volatile TargetFilter filter = target -> target instanceof Enemy;

  /**
   * Per-UUID target blacklist (value = expiry epoch ms). A blacklisted mob is skipped by selection
   * until it expires.
   */
  private final Map<UUID, Long> blacklist = new ConcurrentHashMap<>();

  /**
   * Hard suppression for mobs an area attack is expected to have killed. Unlike path blacklists,
   * close range cannot bypass it.
   */
  private final Map<UUID, Long> suppressed = new ConcurrentHashMap<>();

  /**
   * When true, a target whose position is inside a {@link CombatHazards} hazard zone is rejected.
   */
  @Setter private volatile boolean avoidDanger = false;

  /** Optional region the target must sit inside (null = no spatial gate). */
  private volatile CombatBounds bounds = null;

  /**
   * When true (V5 detection gate), only mobs in real - or very recent - line of sight are eligible.
   */
  @Setter private volatile boolean requireSight = true;

  /** Rev Slayer flow mode ignores mobs behind the player's current travel direction. */
  @Setter private volatile boolean forwardOnly;

  /** A flowing hunt picks mobs ahead over ones behind, so it never turns back for a near one. */
  @Setter private volatile boolean preferAhead;

  @Setter private boolean recordedMovement;

  boolean usesNearestTarget() {
    return recordedMovement;
  }

  /**
   * A managed session's exact mob pin, which must not be bypassed by the Graveyard zombie area
   * rule.
   */
  private volatile UUID requiredTargetId;

  /**
   * Extra selection cost for a mob behind the flow direction while {@link #preferAhead} is on. A
   * mob straight behind costs as much as one about {@code 36} blocks further away ahead.
   */
  double behindPenalty(LocalPlayer self, LivingEntity target) {
    if (!preferAhead) {
      return 0.0;
    }
    final double angle = flowAngle(self, target);
    return angle > 90.0 ? (angle - 90.0) * BEHIND_PENALTY : 0.0;
  }

  /**
   * Set the allowed target region from a {@code "minX,minY,minZ,maxX,maxY,maxZ"} string;
   * blank/invalid clears it.
   *
   * @param csv six comma-separated coordinates, or a blank string to clear the region
   */
  public void setBounds(String csv) {
    bounds = CombatBounds.parse(csv);
  }

  /**
   * Skip this mob in selection until {@code now + durationMs} - used after it repeatedly fails to
   * path.
   *
   * @param id the mob to skip
   * @param durationMs how long to skip it for, in milliseconds
   */
  public void blacklist(UUID id, long durationMs) {
    if (id != null) {
      blacklist.put(id, System.currentTimeMillis() + durationMs);
    }
  }

  public void suppress(UUID id, long durationMs) {
    if (id != null) {
      suppressed.put(id, System.currentTimeMillis() + durationMs);
    }
  }

  boolean isSuppressed(UUID id) {
    return id != null && suppressed.containsKey(id);
  }

  /** Drop expired blacklist entries; call once per tick before selection. */
  public void expireBlacklist() {
    final long now = System.currentTimeMillis();
    blacklist.entrySet().removeIf(e -> now > e.getValue());
    suppressed.entrySet().removeIf(e -> now > e.getValue());
  }

  public void clearBlacklist() {
    blacklist.clear();
    suppressed.clear();
  }

  /**
   * Restrict targeting by visible SkyBlock nametag names first, then by entity type ids when no
   * names are selected. Empty/null values restore the default "any vanilla hostile" filter.
   *
   * @param types the allowed entity types, used when no names are given
   * @param namesCsv comma-separated nametag names, or blank for none
   */
  public void setTargetFilter(Set<EntityType<?>> types, String namesCsv) {
    setTargetFilter(types, namesCsv, null);
  }

  public void setTargetFilter(Set<EntityType<?>> types, String namesCsv, UUID targetId) {
    final CombatNames targetNames = CombatNames.parse(namesCsv);
    final TargetFilter selected;
    if (targetNames.hasSelections()) {
      selected =
          target -> !(target instanceof ArmorStand) && targetNameMatches(targetNames, target);
    } else if (types == null || types.isEmpty()) {
      selected = target -> target instanceof Enemy;
    } else {
      final Set<EntityType<?>> allowed = Set.copyOf(types);
      selected = target -> allowed.contains(target.getType());
    }
    requiredTargetId = targetId;
    filter = targetId == null ? selected : target -> targetId.equals(target.getUUID());
  }

  /**
   * Every mob of {@code type}, but only while it is too far for its nametag to have loaded; within
   * {@code verifyRange} its nametag must match {@code namesCsv}. A nearby mob nametag that names
   * something else rejects it at any distance.
   *
   * @param type the entity type tracked beyond nametag range
   * @param namesCsv comma-separated nametag names that confirm a mob up close
   * @param verifyRange blocks within which the nametag must confirm the mob
   */
  public void setVerifiedTypeFilter(EntityType<?> type, String namesCsv, double verifyRange) {
    final CombatNames names = CombatNames.parse(namesCsv);
    final double verifyRangeSq = verifyRange * verifyRange;
    requiredTargetId = null;
    filter =
        target -> {
          if (target.getType() != type) {
            return false;
          }
          final NametagVerdict verdict = nametagVerdict(names, target);
          if (verdict != NametagVerdict.NONE) {
            return verdict == NametagVerdict.MATCH;
          }
          final LocalPlayer player = net.minecraft.client.Minecraft.getInstance().player;
          return player == null || player.distanceToSqr(target) > verifyRangeSq;
        };
  }

  private enum NametagVerdict {
    MATCH,
    OTHER,
    NONE
  }

  /** Whether a nametag over the mob names it, names something else, or has not loaded. */
  private static NametagVerdict nametagVerdict(CombatNames names, LivingEntity target) {
    if (names.matches(visibleNameText(target))) {
      return NametagVerdict.MATCH;
    }
    if (!(target.level() instanceof ClientLevel level)) {
      return NametagVerdict.NONE;
    }
    final AABB box = target.getBoundingBox();
    final double centerX = (box.minX + box.maxX) * 0.5;
    final double centerZ = (box.minZ + box.maxZ) * 0.5;
    final AABB search =
        new AABB(
            centerX - NAMETAG_SCAN_XZ,
            box.minY - NAMETAG_SCAN_DOWN,
            centerZ - NAMETAG_SCAN_XZ,
            centerX + NAMETAG_SCAN_XZ,
            box.maxY + NAMETAG_SCAN_UP,
            centerZ + NAMETAG_SCAN_XZ);
    NametagVerdict verdict = NametagVerdict.NONE;
    for (Entity entity : level.getEntities(target, search, e -> e instanceof ArmorStand)) {
      final double dx = entity.getX() - centerX;
      final double dz = entity.getZ() - centerZ;
      final Component customName = entity.getCustomName();
      if (dx * dx + dz * dz > NAMETAG_SCAN_XZ_SQ || customName == null) {
        continue;
      }
      // A mob tag carries its health; anything else (a hologram, a damage number) says nothing.
      final String text = customName.getString();
      if (names.matches(text)) {
        return NametagVerdict.MATCH;
      }
      if (text.contains("\u2764")) {
        verdict = NametagVerdict.OTHER;
      }
    }
    return verdict;
  }

  public static boolean matchesName(LivingEntity target, String name) {
    return target != null && targetNameMatches(CombatNames.parse(name), target);
  }

  public static boolean nametagMatches(String nametag, String name) {
    return CombatNames.parse(name).matches(nametag);
  }

  private static boolean targetNameMatches(CombatNames targetNames, LivingEntity target) {
    if (targetNames.matches(visibleNameText(target))) {
      return true;
    }
    return nearbyNametagMatches(targetNames, target);
  }

  /**
   * A SkyBlock mob's visible name often lives on a separate display entity floating above it, so
   * when the mob's own name does not match we look for a matching nametag hovering over it.
   *
   * <p>This asks the level for entities inside a small box rather than walking the whole render
   * list. The old full walk ran once per candidate that failed the direct name test, which made
   * selection O(entities squared) every tick as soon as any target name was configured, and it ran
   * BEFORE the line-of-sight clip that the code itself documents as the expensive check.
   */
  private static boolean nearbyNametagMatches(CombatNames targetNames, LivingEntity target) {
    if (!(target.level() instanceof ClientLevel level)) {
      return false;
    }
    final AABB box = target.getBoundingBox();
    final double centerX = (box.minX + box.maxX) * 0.5;
    final double centerZ = (box.minZ + box.maxZ) * 0.5;
    final AABB search =
        new AABB(
            centerX - NAMETAG_SCAN_XZ,
            box.minY - NAMETAG_SCAN_DOWN,
            centerZ - NAMETAG_SCAN_XZ,
            centerX + NAMETAG_SCAN_XZ,
            box.maxY + NAMETAG_SCAN_UP,
            centerZ + NAMETAG_SCAN_XZ);

    for (Entity entity :
        level.getEntities(
            target, search, e -> e.hasCustomName() || e instanceof Display.TextDisplay)) {
      // The query box is square; keep the original round XZ test so the match radius is unchanged.
      final double dx = entity.getX() - centerX;
      final double dz = entity.getZ() - centerZ;
      if (dx * dx + dz * dz > NAMETAG_SCAN_XZ_SQ) {
        continue;
      }
      if (targetNames.matches(visibleNameText(entity))) {
        return true;
      }
    }
    return false;
  }

  private static String visibleNameText(Entity target) {
    if (target instanceof Display.TextDisplay display) {
      final var state = display.textRenderState();
      return state == null ? "" : state.text().getString();
    }
    final Component customName = target.getCustomName();
    return customName != null ? customName.getString() : target.getDisplayName().getString();
  }

  /**
   * Is this thing something the bot may fight? Seven gates, and deliberately NO sight test: what to
   * target is decided by mob id and nametag, so a zombie behind a pillar is still found and walked
   * to. Sight only ever decides HOW to approach it, and is applied separately by {@link #seen}.
   *
   * <p>The gates: it is a living, alive, non-invulnerable non-player; it matches a managed UUID pin
   * when one exists; it is not suppressed (an area attack is expected to have killed it); it is
   * hostile by the configured filter or by an area rule; it is inside the forward-flow cone when a
   * session asks for one; it is within scan range; it is not path-blacklisted; and it sits inside
   * the optional bounds region and outside every hazard zone.
   *
   * @param self the local player
   * @param e the candidate entity
   * @param maxRangeSq the squared search radius
   * @return true when the entity passes every targeting gate
   */
  public boolean accepts(LocalPlayer self, Entity e, double maxRangeSq) {
    if (e == null || e == self) {
      return false;
    }
    if (!(e instanceof LivingEntity le) || e instanceof ArmorStand) {
      return false;
    }
    // Use the same account/NPC distinction as vanilla-input guards, not UUID version alone.
    if (e instanceof Player other
        && (MacroInput.isRealPlayer(net.minecraft.client.Minecraft.getInstance(), other)
            || !filter.isHostile(le))) {
      return false;
    }
    if (!le.isAlive() || le.isInvulnerable()) {
      return false;
    }
    if (!matchesRequiredTarget(requiredTargetId, e.getUUID())) {
      return false;
    }
    if (isSuppressed(e.getUUID())) {
      return false;
    }
    if (!filter.isHostile(le) && !matchesAreaRule(le)) {
      return false;
    }
    if (forwardOnly && !recordedMovement && !isForwardFlowTarget(self, le)) {
      return false;
    }
    if (self.distanceToSqr(e) > maxRangeSq) {
      return false;
    }
    // Recorded approaches also check terrain, so proximity alone cannot bypass a failed route.
    if (!blacklist.isEmpty()
        && blacklist.containsKey(e.getUUID())
        && (recordedMovement || !CombatModule.directlyChaseable(self, e))) {
      return false;
    }
    if (!CombatBounds.allows(bounds, e.getX(), e.getY(), e.getZ())) {
      return false;
    }
    return !avoidDanger || hazards.isSafe(e.getX(), e.getY(), e.getZ());
  }

  static boolean matchesRequiredTarget(UUID requiredId, UUID candidateId) {
    return requiredId == null || requiredId.equals(candidateId);
  }

  /**
   * The sight preference, as a post-filter on an already-accepted mob: can the bot see it now, or
   * did it a moment ago? Off when the user turns {@code requireSight} off (a managed session always
   * turns it on).
   *
   * <p>This is never a validity gate. Selection asks for seen mobs first and falls back to unseen
   * ones, so the bot walks in to regain sight instead of standing still; the head still only tracks
   * and swings once real sight returns ({@link CombatModule#shouldTrack}). It is the expensive
   * test, so it is asked last.
   *
   * @param self the local player
   * @param e an already accepted mob
   * @return true when the mob is in sight now or was a moment ago, or sight is not required
   */
  public boolean seen(LocalPlayer self, LivingEntity e) {
    return !requireSight || sight.isVisibleOrRecent(self, e);
  }

  /**
   * Past this angle (deg) off the flow direction a target is "to the side", and the side penalty
   * ramps in.
   */
  private static final double FLOW_CONE_DEG = 55.0;

  /**
   * Extra cost per degree a target sits beyond {@link #FLOW_CONE_DEG} off the flow direction (keeps
   * the bot moving forward: a side / behind mob must be clearly closer to beat one already in the
   * travel direction).
   */
  private static final double FLOW_SIDE_PENALTY = 0.7;

  private static final double BEHIND_PENALTY = 4.0;

  /**
   * Above this horizontal speed (blocks/tick, squared) the flow direction is the body's travel
   * heading; below it (standing) the flow direction falls back to where the head faces.
   */
  private static final double FLOW_SPEED_SQ = 0.02 * 0.02;

  /**
   * Target selection cost (flow-first): distance (x10) plus a facing/travel-angle term that ramps
   * past the flow cone. The angle is measured off the direction the body is actually MOVING (its
   * travel heading) when the bot is moving, falling back to the head yaw when standing, so
   * selection prefers the zombie that keeps the bot flowing forward over a slightly closer one off
   * to the side. Distance still dominates, so a clearly closer mob still wins; the close-range
   * melee takeover handles a point-blank threat regardless of angle.
   */
  static double cost(LocalPlayer self, LivingEntity target) {
    final double dist = self.distanceTo(target);
    final double angle = flowAngle(self, target);
    double cost = dist * 10.0 + angle * 0.5;
    if (angle > FLOW_CONE_DEG) {
      cost += (angle - FLOW_CONE_DEG) * FLOW_SIDE_PENALTY;
    }
    return cost;
  }

  static boolean forwardFlowAngleAllowed(double angle) {
    return angle <= 90.0;
  }

  private static boolean isForwardFlowTarget(LocalPlayer self, LivingEntity target) {
    return forwardFlowAngleAllowed(flowAngle(self, target));
  }

  /**
   * Angle (deg, 0..180) between the target's bearing and the flow direction (travel heading when
   * moving, else head yaw).
   */
  private static double flowAngle(LocalPlayer self, LivingEntity target) {
    final double dx = target.getX() - self.getX();
    final double dz = target.getZ() - self.getZ();
    if (dx * dx + dz * dz < 1.0e-6) {
      return 0.0;
    }
    final double targetYaw = Math.toDegrees(Math.atan2(dz, dx)) - 90.0;
    final double vx = self.getDeltaMovement().x;
    final double vz = self.getDeltaMovement().z;
    final double refYaw =
        (vx * vx + vz * vz > FLOW_SPEED_SQ)
            ? Math.toDegrees(Math.atan2(vz, vx)) - 90.0
            : self.getYRot();
    return Math.abs(Mth.wrapDegrees((float) (targetYaw - refYaw)));
  }
}
