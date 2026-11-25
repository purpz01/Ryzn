package ryzn.macro.client.feature.modules.impl.combat;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The one answer to "can I see it, or did I see it a moment ago".
 *
 * <p>This was three copies of the same idea. The height up a mob's body that the bot looks at was
 * written out separately in the aim, in the can-I-see-it test, and twice more in the mob-to-mob
 * test - four literals that had to agree or "I can see it" and "I can hit it" would disagree about
 * the same mob. It comes from {@link CombatTuning#AIM_HEIGHT_FRACTION} now, once.
 *
 * <p>Two different questions live here, and they are deliberately not the same test:
 *
 * <ul>
 *   <li><b>Strict</b> ({@link #canSee}): one ray at the upper body, the exact point the aim uses.
 *       This is what decides whether the bot can walk straight at something or has to go around it.
 *   <li><b>Lenient</b> ({@link #isVisibleOrRecent}): feet, middle and head, visible if any of the
 *       three gets through, plus a short memory of where it was last seen. Matches vanilla's own
 *       leniency and stops a mob flickering in and out behind a gravestone.
 * </ul>
 *
 * <p>Reads block collision only, so nothing is sent to the server.
 */
public final class CombatSight {

  /** Keep a mob "seen" this long after sight breaks. */
  private static final long MEMORY_MS = 2500L;

  /** ... but only while it stays within this distance of where it was last seen. */
  private static final double MEMORY_DIST = 6.0;

  private static final double MEMORY_DIST_SQ = MEMORY_DIST * MEMORY_DIST;

  private record Seen(double x, double y, double z, long time) {}

  private final Map<UUID, Seen> recent = new ConcurrentHashMap<>();

  /**
   * The point on a mob the bot looks at, and tests sight against. Upper body, so aim and sight
   * agree.
   */
  static Vec3 lookPoint(Entity target) {
    final AABB box = target.getBoundingBox();
    return new Vec3(
        (box.minX + box.maxX) * 0.5,
        box.minY + (box.maxY - box.minY) * CombatTuning.AIM_HEIGHT_FRACTION,
        (box.minZ + box.maxZ) * 0.5);
  }

  /**
   * Strict: a clear straight shot from the player's eyes to the mob's upper body. Fluids are
   * ignored, but anything with a solid collision box counts as blocking - including leaves, which
   * have a full cube - so a mob behind them reads as not reachable in a straight line.
   *
   * @param self the local player whose eyes the ray starts at
   * @param target the mob to look for
   * @return true when nothing solid blocks the shot
   */
  public static boolean canSee(LocalPlayer self, Entity target) {
    if (self.level() == null || target == null) {
      return false;
    }
    return clear(self, self.getEyePosition(1f), lookPoint(target));
  }

  /** Strict, between two mobs. Used to decide whether an area attack would connect with both. */
  static boolean canSee(Entity from, Entity to) {
    if (from.level() == null || from.level() != to.level()) {
      return false;
    }
    return clear(from, lookPoint(from), lookPoint(to));
  }

  /**
   * Lenient, plus memory: true if the player can see the mob now at any of feet / middle / head, or
   * saw it within {@link #MEMORY_MS} and it has not strayed far since.
   *
   * @param self the local player
   * @param target the mob to look for
   * @return true when the mob is visible now or was seen recently nearby
   */
  public boolean isVisibleOrRecent(LocalPlayer self, LivingEntity target) {
    final long now = System.currentTimeMillis();
    if (anyPointVisible(self, target)) {
      recent.put(target.getUUID(), new Seen(target.getX(), target.getY(), target.getZ(), now));
      return true;
    }
    final Seen seen = recent.get(target.getUUID());
    if (seen == null || now - seen.time() > MEMORY_MS) {
      return false;
    }
    final double dx = target.getX() - seen.x();
    final double dy = target.getY() - seen.y();
    final double dz = target.getZ() - seen.z();
    return dx * dx + dy * dy + dz * dz <= MEMORY_DIST_SQ;
  }

  /** Drop stale sight records; call once per tick. */
  public void prune() {
    if (recent.isEmpty()) {
      return;
    }
    final long now = System.currentTimeMillis();
    recent.entrySet().removeIf(e -> now - e.getValue().time() > MEMORY_MS);
  }

  public void clear() {
    recent.clear();
  }

  private static boolean clear(Entity viewer, Vec3 from, Vec3 to) {
    final HitResult hit =
        viewer
            .level()
            .clip(
                new ClipContext(
                    from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, viewer));
    return hit.getType() == HitResult.Type.MISS;
  }

  private static boolean anyPointVisible(LocalPlayer self, LivingEntity target) {
    if (self.level() == null) {
      return false;
    }
    final Vec3 eye = self.getEyePosition(1f);
    final AABB box = target.getBoundingBox();
    final double x = (box.minX + box.maxX) * 0.5;
    final double z = (box.minZ + box.maxZ) * 0.5;
    return clear(self, eye, new Vec3(x, box.minY + 0.1, z)) // feet
        || clear(self, eye, new Vec3(x, (box.minY + box.maxY) * 0.5, z)) // middle
        || clear(self, eye, new Vec3(x, box.maxY - 0.1, z)); // head
  }

  /**
   * Holds a head-lock briefly after sight breaks, so a mob stepping behind a gravestone for a
   * moment does not snap the aim away and back. Separate from the detection memory above: this one
   * is about not twitching the camera, that one is about not losing the target.
   */
  static final class Grace {
    private long lastVisibleNanos;

    void markVisible(long nowNanos) {
      lastVisibleNanos = nowNanos;
    }

    boolean isActive(double distance, double maximumDistance, long nowNanos, long durationNanos) {
      return lastVisibleNanos != 0L
          && distance <= maximumDistance
          && nowNanos - lastVisibleNanos <= durationNanos;
    }

    boolean hasSeenTarget() {
      return lastVisibleNanos != 0L;
    }

    void reset() {
      lastVisibleNanos = 0L;
    }
  }
}
