package ryzn.macro.client.feature.modules.impl.combat;

import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * Picks the mob to fight: the cheapest one that {@link CombatTargeting#accepts} and is on this
 * floor.
 *
 * <p>{@code requireSeen} is a preference, not a validity rule. The caller asks for a mob in sight
 * first and asks again without that filter if nothing qualifies, so the bot walks in to regain
 * sight rather than standing still. It never widens what counts as a target.
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class CombatTargetSelector {

  private final CombatTargeting targeting;

  /**
   * A mob this far above the player is on another floor - reachable only by a route that is not
   * this fight.
   */
  private static final double MAX_UP = 10.0;

  /**
   * ... and this far below. Asymmetric because walking off a ledge is easy and climbing back up is
   * not.
   */
  private static final double MAX_DOWN = 8.0;

  LivingEntity best(LocalPlayer player, ClientLevel level, double maxRangeSq, boolean requireSeen) {
    final double originY = player.getY();
    final Entity candidate =
        best(
            level.entitiesForRendering(),
            entity ->
                targeting.accepts(player, entity, maxRangeSq)
                    && onThisFloor(entity.getY() - originY)
                    && (!requireSeen || targeting.seen(player, (LivingEntity) entity)),
            entity ->
                targeting.usesNearestTarget()
                    ? player.distanceToSqr(entity)
                    : CombatTargeting.cost(player, (LivingEntity) entity)
                        + targeting.behindPenalty(player, (LivingEntity) entity));
    return candidate == null ? null : (LivingEntity) candidate;
  }

  /** Lowest-cost candidate that passes, or null. */
  <T> T best(Iterable<T> candidates, Predicate<T> valid, ToDoubleFunction<T> cost) {
    T best = null;
    double bestCost = Double.MAX_VALUE;
    for (T candidate : candidates) {
      if (!valid.test(candidate)) {
        continue;
      }
      final double candidateCost = cost.applyAsDouble(candidate);
      if (candidateCost < bestCost) {
        bestCost = candidateCost;
        best = candidate;
      }
    }
    return best;
  }

  /**
   * The height window, a permanent selection gate. The HELD target is never put through it -
   * retention asks {@link CombatTargeting#accepts} directly - so a mob that climbs mid-fight is
   * chased, not dropped.
   */
  static boolean onThisFloor(double deltaY) {
    return deltaY <= MAX_UP && deltaY >= -MAX_DOWN;
  }
}
