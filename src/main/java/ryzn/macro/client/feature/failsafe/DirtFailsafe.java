package ryzn.macro.client.feature.failsafe;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.AttachedStemBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import ryzn.macro.client.config.groups.FailsafeSettings;
import ryzn.macro.client.core.RyznBootstrap;
import ryzn.macro.client.feature.failsafe.api.Failsafe;

public final class DirtFailsafe extends Failsafe {
  /**
   * @description Creates the process-lifetime detector owned by the failsafe manager.
   */
  public DirtFailsafe() {
    super("dirt", "Dirt Check", "Detects dirt check.");
  }

  private static final long TRACKED_BLOCK_TTL_TICKS = 2_400;
  private static final long PLAYER_BREAK_TTL_TICKS = 200;
  private static final double TOUCH_DISTANCE = 1.5;

  private final Map<BlockPos, Long> trackedBlocks = new HashMap<>();
  private final Map<BlockPos, Long> playerBrokenBlocks = new HashMap<>();
  private final FailsafeDelay delay = new FailsafeDelay();

  void onBlockBreak(BlockPos pos, long tick) {
    if (pos == null || !FailsafeActivity.farmingActive()) {
      return;
    }
    playerBrokenBlocks.put(pos.immutable(), tick + PLAYER_BREAK_TTL_TICKS);
  }

  void onBlockChanged(
      Minecraft client,
      BlockPos pos,
      BlockState oldState,
      BlockState newState,
      long tick,
      FailsafeSettings settings) {
    if (client == null
        || client.level == null
        || pos == null
        || oldState == null
        || newState == null) {
      return;
    }
    if (!settings.isEnabled() || !settings.isDirtEnabled() || !FailsafeActivity.farmingActive()) {
      return;
    }

    cleanExpired(playerBrokenBlocks, tick);
    if (!isReplaceableFarmState(oldState)
        || !isSuspiciousPlacedBlock(client, pos, newState)
        || playerBrokenBlocks.containsKey(pos)) {
      return;
    }
    trackedBlocks.putIfAbsent(pos.immutable(), tick + TRACKED_BLOCK_TTL_TICKS);
  }

  void tick(Minecraft client, long tick, FailsafeSettings settings) {
    if (client == null
        || client.player == null
        || client.level == null
        || !settings.isEnabled()
        || !settings.isDirtEnabled()
        || !FailsafeActivity.farmingActive()) {
      reset();
      return;
    }

    cleanTrackedBlocks(client, tick);
    Vec3 eye = client.player.getEyePosition(1.0f);
    BlockPos close =
        trackedBlocks.keySet().stream()
            .filter(pos -> eye.distanceTo(Vec3.atCenterOf(pos)) <= TOUCH_DISTANCE)
            .findFirst()
            .orElse(null);
    if (delay.update(
        close != null,
        tick,
        settings.getDirtDelayTicks(),
        () -> FailsafeCoordinator.sampleAdditionalDelay(settings))) {
      RyznBootstrap.failsafeManager()
          .trigger(
              FailsafeType.DIRT,
              "suspicious solid block stayed close to the player at " + close.toShortString());
    }
  }

  void reset() {
    trackedBlocks.clear();
    playerBrokenBlocks.clear();
    delay.reset();
  }

  private void cleanTrackedBlocks(Minecraft client, long tick) {
    cleanExpired(playerBrokenBlocks, tick);
    Iterator<Map.Entry<BlockPos, Long>> iterator = trackedBlocks.entrySet().iterator();
    while (iterator.hasNext()) {
      Map.Entry<BlockPos, Long> entry = iterator.next();
      BlockState state = client.level.getBlockState(entry.getKey());
      if (entry.getValue() < tick || isRemovedOrPassable(client, entry.getKey(), state)) {
        iterator.remove();
      }
    }
  }

  private static void cleanExpired(Map<BlockPos, Long> blocks, long tick) {
    blocks.values().removeIf(expiry -> expiry < tick);
  }

  private static boolean isReplaceableFarmState(BlockState state) {
    return state.isAir() || isCrop(state) || isWater(state);
  }

  private static boolean isSuspiciousPlacedBlock(Minecraft client, BlockPos pos, BlockState state) {
    if (state.isAir() || isCrop(state) || isWater(state)) {
      return false;
    }
    if (state.is(Blocks.LADDER) || state.getBlock() instanceof TrapDoorBlock) {
      return false;
    }
    return !state.getCollisionShape(client.level, pos).isEmpty()
        && Block.isShapeFullBlock(state.getCollisionShape(client.level, pos));
  }

  private static boolean isRemovedOrPassable(Minecraft client, BlockPos pos, BlockState state) {
    return state.isAir()
        || isCrop(state)
        || isWater(state)
        || state.getCollisionShape(client.level, pos).isEmpty();
  }

  private static boolean isCrop(BlockState state) {
    Block block = state.getBlock();
    return block instanceof CropBlock
        || block instanceof StemBlock
        || block instanceof AttachedStemBlock
        || block instanceof NetherWartBlock
        || block instanceof CocoaBlock;
  }

  private static boolean isWater(BlockState state) {
    return state.getFluidState().is(Fluids.WATER);
  }
}
