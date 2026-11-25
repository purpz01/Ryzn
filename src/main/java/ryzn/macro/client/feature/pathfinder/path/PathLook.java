package ryzn.macro.client.feature.pathfinder.path;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import net.minecraft.client.Minecraft;
import ryzn.macro.client.core.RyznBootstrap;
import ryzn.macro.client.util.time.Clock;

/**
 * The head {@link PathRotationController} drives: the six things it needs from the player, and
 * nothing else.
 *
 * <p>The controller used to hold a {@link Minecraft} and reach through it, which made the rotation
 * pipeline - the GCD carry, the ease-in, the P-gain ramp, the dither and the tremor - impossible to
 * run without a live game, so every rotation change had to be judged by eye in-game. Behind this
 * interface the same code runs in the headless sandbox against a fake head, so a wobble is a number
 * instead of a feeling.
 *
 * <p>{@link #nowMillis()} exists for the same reason: the ease-in is wall-clock, and a sim that
 * runs a thousand ticks in five milliseconds would skip it entirely. The sandbox returns simulated
 * time.
 */
public interface PathLook {

  /** False when there is no player to drive; every entry point returns early on it. */
  boolean present();

  float yaw();

  float pitch();

  /** Vanilla mouse sensitivity, 0..1. Feeds {@link RotationSmoothing#mouseGcd(double)}. */
  double sensitivity();

  /** Milliseconds on whatever clock the ease-in should run against. */
  long nowMillis();

  default long nowNanos() {
    return nowMillis() * 1_000_000L;
  }

  /** Writes the tick's functional rotation. This is what the server sees. */
  void apply(float yaw, float pitch);

  static PathLook of(Minecraft client) {
    return new MinecraftLook(client);
  }

  /** The live head. The only place the rotation pipeline touches the game. */
  @RequiredArgsConstructor(access = AccessLevel.PACKAGE)
  final class MinecraftLook implements PathLook {

    private final Minecraft client;

    @Override
    public boolean present() {
      return client.player != null;
    }

    @Override
    public float yaw() {
      return client.player.getYRot();
    }

    @Override
    public float pitch() {
      return client.player.getXRot();
    }

    @Override
    public double sensitivity() {
      return client.options.sensitivity().get();
    }

    @Override
    public long nowMillis() {
      return Clock.now();
    }

    @Override
    public long nowNanos() {
      return System.nanoTime();
    }

    @Override
    public void apply(float yaw, float pitch) {
      client.player.setYRot(yaw);
      client.player.setYHeadRot(yaw);
      client.player.setXRot(pitch);
      client.player.yRotO = yaw;
      client.player.yHeadRotO = yaw;
      client.player.xRotO = pitch;
      RyznBootstrap.failsafeManager().expectRotation(yaw, pitch);
    }
  }
}
