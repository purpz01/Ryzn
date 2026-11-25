package ryzn.macro.client.feature.pathfinder.path;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import net.minecraft.client.Minecraft;
import ryzn.macro.client.debug.DebugLog;

/** Emits state-transition, block-step, and notable-turn traces for the executor. */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class PathExecutorTrace {
  private static final float BIG_TURN_DEGREES = 30.0f;

  private final Minecraft client;
  private int lastBlockX = Integer.MIN_VALUE;
  private int lastBlockY = Integer.MIN_VALUE;
  private int lastBlockZ = Integer.MIN_VALUE;
  private boolean bigTurnLogged;
  private PathExecutor.State lastState;

  void logBlockStep(int cursor, int trailSize) {
    if (client.player == null || !DebugLog.isEnabled(DebugLog.Category.NAV)) {
      return;
    }
    int blockX = (int) Math.floor(client.player.getX());
    int blockY = (int) Math.floor(client.player.getY());
    int blockZ = (int) Math.floor(client.player.getZ());
    if (blockX == lastBlockX && blockY == lastBlockY && blockZ == lastBlockZ) {
      return;
    }
    lastBlockX = blockX;
    lastBlockY = blockY;
    lastBlockZ = blockZ;
    DebugLog.detail(
        DebugLog.Category.NAV,
        "[move] now at %d %d %d (step %d/%d)",
        blockX,
        blockY,
        blockZ,
        cursor,
        trailSize);
  }

  void logState(PathExecutor.State state) {
    if (!DebugLog.isEnabled(DebugLog.Category.NAV) || state == lastState) {
      return;
    }
    log("Route state: %s -> %s", lastState == null ? "start" : lastState, state);
    lastState = state;
  }

  void logBigTurn(float aimGap, float targetYaw) {
    if (client.player == null || !DebugLog.isEnabled(DebugLog.Category.NAV)) {
      return;
    }
    if (aimGap >= BIG_TURN_DEGREES) {
      if (!bigTurnLogged) {
        bigTurnLogged = true;
        DebugLog.detail(
            DebugLog.Category.NAV,
            "[rot] turn %.1f deg (yaw %.1f -> %.1f)",
            aimGap,
            client.player.getYRot(),
            targetYaw);
      }
    } else if (aimGap < BIG_TURN_DEGREES * 0.5f) {
      bigTurnLogged = false;
    }
  }

  private static void log(String message, Object... parts) {
    DebugLog.trace(DebugLog.Category.NAV, message, parts);
  }
}
