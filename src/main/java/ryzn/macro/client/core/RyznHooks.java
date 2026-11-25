package ryzn.macro.client.core;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.protocol.Packet;
import net.minecraft.world.entity.player.Input;
import ryzn.macro.client.MacroInput;
import ryzn.macro.client.MacroMouseUnlock;
import ryzn.macro.client.config.RyznSettings;
import ryzn.macro.client.core.events.api.PlayerInputEvent;
import ryzn.macro.client.feature.hud.TpsMeter;
import ryzn.macro.client.feature.macros.impl.sand.SandMinerMacro;
import ryzn.macro.client.feature.pathfinder.path.PathTelemetry;
import ryzn.macro.client.gui.RyznMenuMovement;
import ryzn.macro.client.gui.RyznStatusHud;
import ryzn.macro.client.world.WorldChangeDetector;
import ryzn.swift.cache.CachedWorld;

public final class RyznHooks {
  private static final InputState INPUT_STATE = new InputState();

  private RyznHooks() {}

  public static void renderStatusHud(GuiGraphicsExtractor graphics) {
    if (RyznBootstrap.isReady()) RyznStatusHud.render(graphics);
  }

  public static Input resolveInput(Input original) {
    return INPUT_STATE.apply(original);
  }

  public static final class MacroCameraSmoother {
    private MacroCameraSmoother() {}

    public static boolean isPicking() {
      return RyznBootstrap.rotationManager().isPicking();
    }

    public static void setPicking(boolean value) {
      RyznBootstrap.rotationManager().setPicking(value);
    }

    public static boolean isDriven(net.minecraft.client.player.LocalPlayer player) {
      return RyznBootstrap.isReady() && RyznBootstrap.rotationManager().isCameraDriven(player);
    }

    public static void mark(net.minecraft.client.player.LocalPlayer player) {
      if (RyznBootstrap.isReady()) {
        RyznBootstrap.rotationManager().markCamera(player);
      }
    }

    public static boolean stampFresh(net.minecraft.client.player.LocalPlayer player) {
      return RyznBootstrap.isReady() && RyznBootstrap.rotationManager().isCameraFresh(player);
    }

    public static float yaw(net.minecraft.client.player.LocalPlayer player) {
      return RyznBootstrap.rotationManager().cameraYaw(player);
    }

    public static float pitch(net.minecraft.client.player.LocalPlayer player) {
      return RyznBootstrap.rotationManager().cameraPitch(player);
    }

    public static void idle() {
      RyznBootstrap.rotationManager().idleCamera();
    }
  }

  public static final class MacroLook {
    private MacroLook() {}

    public static boolean anyMacroOwnsLook() {
      return RyznBootstrap.isReady() && RyznBootstrap.rotationManager().anyMacroOwnsLook();
    }

    public static boolean anyMacroRunning() {
      return RyznBootstrap.isReady() && RyznBootstrap.macroManager().anyMacroRunning();
    }
  }

  public static final class MacroMouseUnlock {
    private MacroMouseUnlock() {}

    public static boolean isCursorReleased() {
      return RyznBootstrap.isReady() && ryzn.macro.client.MacroMouseUnlock.isCursorReleased();
    }

    public static boolean shouldSwallowManualInput() {
      return RyznBootstrap.isReady()
          && ryzn.macro.client.MacroMouseUnlock.shouldSwallowManualInput();
    }
  }

  public static final class RyznSandMiner {
    private RyznSandMiner() {}

    public static boolean shouldSwallowManualClicks() {
      return RyznBootstrap.isReady()
          && RyznBootstrap.macroManager()
              .getByClass(SandMinerMacro.class)
              .shouldSwallowManualClicks();
    }
  }

  private static final class InputState {
    private final ryzn.macro.client.input.GroundJumpGuard groundJump =
        new ryzn.macro.client.input.GroundJumpGuard();

    private Input apply(Input original) {
      var resolved = original;
      boolean driving = false;
      if (RyznBootstrap.isReady()) {
        resolved = ryzn.macro.client.input.MacroKeys.apply(original);
        if (resolved == original) {
          resolved = RyznBootstrap.eventBus().post(new PlayerInputEvent(original)).result();
        }
        driving =
            ryzn.macro.client.feature.pathfinder.WalkingNavigation.isRouteDriving();
        if (!driving && resolved == original && !RyznBootstrap.macroManager().anyMacroRunning()) {
          resolved = RyznMenuMovement.resolve(original);
        }
      }
      boolean jump = groundJump.apply(resolved.jump(), driving);
      return jump == resolved.jump()
          ? resolved
          : new net.minecraft.world.entity.player.Input(
              resolved.forward(),
              resolved.backward(),
              resolved.left(),
              resolved.right(),
              jump,
              resolved.shift(),
              resolved.sprint());
    }
  }

  public static void observeOverlay(net.minecraft.network.chat.Component message) {
    if (RyznBootstrap.isReady()) {
      RyznBootstrap.failsafeManager().observeOverlay(message);
    }
  }

  public static void onBlockBreak(net.minecraft.core.BlockPos pos) {
    if (RyznBootstrap.isReady()) {
      RyznBootstrap.failsafeManager().onBlockBreak(pos);
    }
  }

  /** Swaps the vanilla title screen for Ryzn's while the custom main menu is on. */

  /** The themed multiplayer flow; see {@link ryzn.macro.client.gui.skin.MenuSkin}. */
  public static final class AutoHarpHooks {
    private AutoHarpHooks() {}

    public static void onNotesUpdated(Minecraft client) {
      ryzn.macro.client.core.hooks.AutoHarpHooks.onNotesUpdated(client);
    }
  }

  public static final class RevSlayerMacro {
    private RevSlayerMacro() {}

    public static void onParticle(
        net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket packet) {
      if (RyznBootstrap.isReady()) {
        RyznBootstrap.macroManager()
            .getByClass(ryzn.macro.client.feature.macros.impl.slayer.RevSlayerMacro.class)
            .onParticle(packet);
      }
    }

    public static void onSound(net.minecraft.network.protocol.game.ClientboundSoundPacket packet) {
      if (RyznBootstrap.isReady()) {
        RyznBootstrap.macroManager()
            .getByClass(ryzn.macro.client.feature.macros.impl.slayer.RevSlayerMacro.class)
            .onSound(packet);
      }
    }
  }

  public static final class MouseInputHooks {
    private MouseInputHooks() {}

    public static boolean blocksClick(int button, int action) {
      return ryzn.macro.client.core.hooks.MouseInputHooks.blocksClick(button, action);
    }

    public static boolean blocksScroll(double amount) {
      return ryzn.macro.client.core.hooks.MouseInputHooks.blocksScroll(amount);
    }

    public static boolean keepsCursorReleased() {
      return ryzn.macro.client.core.hooks.MouseInputHooks.keepsCursorReleased();
    }

    public static boolean blocksLook() {
      return ryzn.macro.client.core.hooks.MouseInputHooks.blocksLook();
    }
  }

  public static void blockChanged(
      net.minecraft.core.BlockPos pos,
      net.minecraft.world.level.block.state.BlockState previous,
      net.minecraft.world.level.block.state.BlockState current) {
    if (!RyznBootstrap.isReady()) {
      return;
    }
    try {
      RyznBootstrap.failsafeManager()
          .onBlockChanged(Minecraft.getInstance(), pos, previous, current);
      CachedWorld.onBlockUpdate(pos.getX(), pos.getY(), pos.getZ(), current);
    } catch (Exception | LinkageError failure) {
      RyznBootstrap.fail("block observation", failure);
    }
  }

  public static void initialize() {
    RyznBootstrap.start();
  }

  public static boolean ownsLook() {
    return RyznBootstrap.isReady() && RyznBootstrap.rotationManager().anyMacroOwnsLook();
  }

  public static boolean acceptsHeldInput(boolean original) {
    return original
        || (RyznBootstrap.isReady()
            && (MacroMouseUnlock.isCursorReleased()
                || RyznBootstrap.rotationManager().anyMacroOwnsLook()));
  }

  public static boolean keepFocus() {
    return RyznBootstrap.isReady()
        && (RyznSettings.get().keepFocus().isEnabled()
            || RyznBootstrap.macroManager().anyMacroRunning());
  }

  public static int macroFramerateLimit(int vanillaLimit) {
    return RyznBootstrap.isReady() && RyznBootstrap.macroManager().anyMacroRunning()
        ? Math.max(vanillaLimit, 60)
        : vanillaLimit;
  }

  public static void setPicking(boolean picking) {
    MacroCameraSmoother.setPicking(picking);
  }

  public static boolean suppressMacroUse(Minecraft client) {
    return ownsLook() && MacroInput.isCrosshairOnRealPlayer(client);
  }

  public static void inboundPacket(Packet<?> packet) {
    if (!RyznBootstrap.isReady()) {
      return;
    }
    try {
      CachedWorld.onPacketReceive(packet);
      WorldChangeDetector.onInboundPacket(packet);
      TpsMeter.onInboundPacket(packet);
      PathTelemetry.onInboundPacket(packet);
    } catch (Exception | LinkageError failure) {
      RyznBootstrap.fail("inbound observation", failure);
    }
  }
}
