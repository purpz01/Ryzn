package ryzn.macro.client.core.events.api;

import java.util.Objects;
import lombok.Getter;
import lombok.experimental.Accessors;
import net.minecraft.world.entity.player.Input;

/** Synchronous request to resolve a player's vanilla input before movement consumes it. */
@Getter
@Accessors(fluent = true)
public final class PlayerInputEvent implements Event {
  /** The unmodified vanilla input that began this request. */
  private final Input original;

  /** The response after registered modules have run, initially identical to the original. */
  private Input result;

  /**
   * @description Starts an input request with vanilla input as its default response.
   * @param original the raw input supplied by Minecraft
   */
  public PlayerInputEvent(Input original) {
    this.original = Objects.requireNonNull(original);
    result = original;
  }
}
