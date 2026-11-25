package ryzn.macro.client.mixin.accessor;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read-only view of the client's own block-breaking state, for macros that need to know whether a
 * break is actually PROGRESSING rather than just whether they are holding the key.
 *
 * <p>{@code isDestroying()} and {@code getDestroyStage()} are already public, and neither answers
 * the question. The first only says the client believes it is mining something - which stays true
 * while progress sits frozen - and the second quantises to ten steps, so on a block that takes
 * seconds to break it reports the same number for dozens of ticks in a row and a watchdog built on
 * it cannot tell a slow break from a stopped one. The raw accumulator moves every tick a break is
 * working, so it is the only honest signal.
 *
 * <p>Reads client state, writes nothing, touches no packet.
 */
@Mixin(MultiPlayerGameMode.class)
public interface MultiPlayerGameModeAccessor {

  /**
   * The client's accumulated break progress, 0..1. Moves every tick a break is actually running.
   */
  @Accessor("destroyProgress")
  float ryzn$destroyProgress();

  /** The block that progress belongs to, so a reading is never credited to the wrong one. */
  @Accessor("destroyBlockPos")
  BlockPos ryzn$destroyBlockPos();
}
