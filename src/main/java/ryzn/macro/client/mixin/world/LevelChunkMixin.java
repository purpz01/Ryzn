package ryzn.macro.client.mixin.world;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ryzn.swift.cache.CachedWorld;

/**
 * Live world updates for the pathfinder cache, done WITHOUT touching the network. This observes the
 * client's own world mutation - {@link LevelChunk#setBlockState} - which is exactly what a vanilla
 * client does when it applies a block change it already knows about (mining, placing, doors,
 * pistons). We only <em>read</em> the resulting state and copy it into {@link CachedWorld}; nothing
 * is sent, intercepted, blocked, or altered, so this is invisible to the server (it's reading our
 * client's own memory).
 *
 * <p>Guarded to the {@link ClientLevel} so the integrated server's chunks aren't double-processed,
 * and skipped when {@code setBlockState} reports no change (null return). Chunk LOAD fills sections
 * directly via {@code replaceWithPacketData}, not {@code setBlockState}, so this fires only on
 * genuine edits; and {@link CachedWorld#onBlockUpdate} cheaply ignores blocks in chunks that aren't
 * cached yet.
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {

  @Shadow @Final Level level;

  @Inject(method = "setBlockState", at = @At("RETURN"))
  private void ryzn$onSetBlockState(
      BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<BlockState> cir) {
    if (!(this.level instanceof ClientLevel)) {
      return;
    }
    if (cir.getReturnValue() == null) {
      return;
    } // no actual change → nothing to propagate
    ryzn.macro.client.core.RyznHooks.blockChanged(pos, cir.getReturnValue(), state);
  }
}
