package ryzn.macro.client.feature.rotation.impl;

import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import ryzn.macro.client.config.RyznSettings;
import ryzn.macro.client.core.RyznBootstrap;
import ryzn.macro.client.feature.macros.impl.bea.BeaMacro;
import ryzn.macro.client.feature.macros.impl.carpentry.CarpentryUnlockMacro;
import ryzn.macro.client.feature.macros.impl.foragingquest.ForagingQuestUnlockerMacro;
import ryzn.macro.client.feature.macros.impl.goldshovel.GoldShovelMacro;
import ryzn.macro.client.feature.macros.impl.miningmerchant.MiningMerchantMacro;
import ryzn.macro.client.feature.macros.impl.sand.SandMinerMacro;
import ryzn.macro.client.feature.macros.impl.tree.TreeChopMacro;
import ryzn.macro.client.feature.macros.impl.wheat.WheatBreakerMacro;
import ryzn.macro.client.feature.modules.impl.combat.CombatModule;
import ryzn.macro.client.feature.pathfinder.path.PathExecutor;
import ryzn.macro.client.feature.rotation.api.LookController;
import ryzn.macro.client.feature.rotation.api.RotationActivity;
import ryzn.macro.client.feature.rotation.api.RotationManager;
import ryzn.macro.client.feature.rotation.impl.abcurves.AbcRotation;

/** Look ownership queries moved out of the root facade while turn loops migrate. */
public final class RotationManagerImpl implements RotationManager {
  private final MacroCameraSmoother camera = new MacroCameraSmoother();
  private final Map<Object, MacroLookArc> arcs = new IdentityHashMap<>();
  private final Map<Object, AngleSmoother> dampedTurns = new IdentityHashMap<>();
  private final Map<Object, MacroLookController> lookControllers = new IdentityHashMap<>();
  private float expectedYaw;
  private float expectedPitch;
  private boolean expectedSet;
  private long rotationGraceUntil;

  private Object activeAimOwner;
  private LocalPlayer activeAimPlayer;
  private final AbcRotation.Session abc = new AbcRotation.Session();

  @Override
  public void aimTarget(Object owner, LocalPlayer player, float yaw, float pitch) {
    aimTarget(owner, player, yaw, pitch, false);
  }

  private void aimTarget(
      Object owner, LocalPlayer player, float yaw, float pitch, boolean holdYaw) {
    aimTarget(owner, player, yaw, pitch, holdYaw, 1.0);
  }

  @Override
  public void aimTarget(
      Object owner, LocalPlayer player, float yaw, float pitch, double plannerSpeed) {
    aimTarget(owner, player, yaw, pitch, false, plannerSpeed);
  }

  private void aimTarget(
      Object owner,
      LocalPlayer player,
      float yaw,
      float pitch,
      boolean holdYaw,
      double plannerSpeed) {
    if (activeAimOwner != owner || activeAimPlayer != player) {
      resetActiveAim();
      activeAimOwner = owner;
      activeAimPlayer = player;
    }
    var settings = RyznSettings.get().rotations();
    var frame =
        abc.step(
            settings.isAbcEnabled(),
            settings.getAbcSeed(),
            System.nanoTime(),
            player.getYRot(),
            player.getXRot(),
            yaw,
            pitch,
            MouseGcd.quantum(),
            holdYaw,
            settings.tuning(),
            plannerSpeed);
    writeLook(player, frame.yaw(), frame.pitch());
  }

  private void resetActiveAim() {
    activeAimOwner = null;
    activeAimPlayer = null;
    abc.reset();
  }

  /**
   * @description Preserves the path executor's existing head-yield predicate.
   * @return true when another feature owns the head while the executor drives movement
   */
  @Override
  public boolean externalOwnsLook() {
    return RyznBootstrap.moduleManager().getByClass(CombatModule.class).ownsLook()
        || RyznBootstrap.macroManager().getByClass(ForagingQuestUnlockerMacro.class).ownsRouteLook()
        || RyznBootstrap.macroManager().getByClass(MiningMerchantMacro.class).ownsRouteLook()
        || RyznBootstrap.macroManager().getByClass(GoldShovelMacro.class).ownsRouteLook()
        || RyznBootstrap.macroManager().getByClass(CarpentryUnlockMacro.class).ownsRouteLook()
        || RyznBootstrap.macroManager().getByClass(BeaMacro.class).ownsRouteLook()
        || RyznBootstrap.macroManager().getByClass(WheatBreakerMacro.class).ownsRouteLook()
        || RyznBootstrap.macroManager().getByClass(TreeChopMacro.class).ownsRouteLook();
  }

  /**
   * @description Preserves the mouse-lock predicate across pathfinders and current macros.
   * @return true when a feature currently controls player look
   */
  @Override
  public boolean anyMacroOwnsLook() {
    return PathExecutor.instance.isActive()
        || RyznBootstrap.moduleManager().getByClass(CombatModule.class).ownsLook()
        || RyznBootstrap.macroManager().getByClass(BeaMacro.class).shouldLockMouse()
        || RyznBootstrap.macroManager().getByClass(GoldShovelMacro.class).shouldLockMouse()
        || RyznBootstrap.macroManager().getByClass(MiningMerchantMacro.class).shouldLockMouse()
        || RyznBootstrap.macroManager().getByClass(CarpentryUnlockMacro.class).shouldLockMouse()
        || RyznBootstrap.macroManager()
            .getByClass(ForagingQuestUnlockerMacro.class)
            .shouldLockMouse()
        || RyznBootstrap.macroManager().getByClass(SandMinerMacro.class).shouldLockMouse()
        || RyznBootstrap.macroManager().getByClass(TreeChopMacro.class).isControllingLook()
        || RyznBootstrap.macroManager().getByClass(WheatBreakerMacro.class).shouldLockMouse();
  }

  /**
   * @description Sets the interaction-ray flag on this manager's camera smoother.
   * @param active true during a crosshair pick
   */
  @Override
  public void setPicking(boolean active) {
    camera.setPicking(active);
  }

  /**
   * @description Reads the current interaction-ray flag.
   * @return true while camera easing must be bypassed
   */
  @Override
  public boolean isPicking() {
    return camera.isPicking();
  }

  /**
   * @description Detects a path turn or recent feature turn and stamps a path turn as needed.
   * @param player the local player whose turn is inspected
   * @return true while the local display should use camera easing
   */
  @Override
  public boolean isCameraDriven(LocalPlayer player) {
    return camera.isDriven(player);
  }

  /**
   * @description Records a feature's functional rotation in the shared camera state.
   * @param player the rotated local player
   */
  @Override
  public void markCamera(LocalPlayer player) {
    camera.mark(player);
  }

  @Override
  public void markCamera(LocalPlayer player, boolean frameDriven) {
    camera.mark(player, frameDriven);
  }

  /**
   * @description Checks the shared recent-turn grace period.
   * @param player the current local player
   * @return true if a stamped turn remains fresh
   */
  @Override
  public boolean isCameraFresh(LocalPlayer player) {
    return camera.stampFresh(player);
  }

  /**
   * @description Advances and returns display yaw without changing functional yaw.
   * @param player the current local player
   * @return the camera-only yaw
   */
  @Override
  public float cameraYaw(LocalPlayer player) {
    return camera.yaw(player);
  }

  /**
   * @description Advances and returns display pitch without changing functional pitch.
   * @param player the current local player
   * @return the camera-only pitch
   */
  @Override
  public float cameraPitch(LocalPlayer player) {
    return camera.pitch(player);
  }

  /**
   * @description Rearms the camera smoother at the end of feature-driven rotation.
   */
  @Override
  public void idleCamera() {
    resetActiveAim();
    camera.idle();
  }

  @Override
  public LookController lookController(Object owner, float downPitch) {
    return lookControllers.computeIfAbsent(owner, ignored -> new MacroLookController(downPitch));
  }

  @Override
  public void smoothTurn(
      Object owner,
      LocalPlayer player,
      float yaw,
      float pitch,
      float deadzone,
      float yawFactor,
      float pitchFactor,
      double yawRate,
      double pitchRate) {
    aimTarget(owner, player, yaw, pitch);
  }

  @Override
  public void releaseTurn(Object owner) {
    resetAim(owner);
  }

  @Override
  public void applyLook(LocalPlayer player, float yaw, float pitch) {
    resetActiveAim();
    writeLook(
        player,
        player.getYRot() + Mth.wrapDegrees(yaw - player.getYRot()),
        Mth.clamp(pitch, -90f, 90f));
  }

  private void writeLook(LocalPlayer player, float steppedYaw, float steppedPitch) {
    markCamera(player);
    player.setYRot(steppedYaw);
    player.setYHeadRot(steppedYaw);
    player.setXRot(steppedPitch);
    player.yRotO = steppedYaw;
    player.yHeadRotO = steppedYaw;
    player.xRotO = steppedPitch;
    RyznBootstrap.failsafeManager().expectRotation(steppedYaw, steppedPitch);
  }

  @Override
  public void aimStandard(Object owner, LocalPlayer player, float yaw, float pitch) {
    aimStandard(owner, player, yaw, pitch, 1.0f, false);
  }

  @Override
  public void aimStandard(
      Object owner, LocalPlayer player, float yaw, float pitch, RotationActivity activity) {
    aimTarget(owner, player, yaw, pitch);
  }

  @Override
  public void aimStandard(
      Object owner, LocalPlayer player, float yaw, float pitch, float speed, boolean holdYaw) {
    aimTarget(owner, player, yaw, pitch, holdYaw);
  }

  @Override
  public void aimCustom(
      Object owner,
      LocalPlayer player,
      float yaw,
      float pitch,
      float deadzone,
      float yawEase,
      float yawMaxStep,
      float pitchEase,
      float pitchMaxStep) {
    aimTarget(owner, player, yaw, pitch);
  }

  @Override
  public void resetAim(Object owner) {
    if (activeAimOwner == owner) {
      resetActiveAim();
    }
  }

  @Override
  public ArcFrame sampleArc(
      Object owner,
      long nowNanos,
      float currentYaw,
      float currentPitch,
      float targetYaw,
      float targetPitch,
      float maximumBendDegrees,
      int durationMs) {
    MacroLookArc.LookFrame frame =
        arcs.computeIfAbsent(owner, ignored -> new MacroLookArc())
            .sample(
                nowNanos,
                currentYaw,
                currentPitch,
                targetYaw,
                targetPitch,
                maximumBendDegrees,
                durationMs);
    return new ArcFrame(frame.yaw(), frame.pitch());
  }

  @Override
  public void resetArc(Object owner) {
    arcs.remove(owner);
  }

  @Override
  public boolean isArcActive(Object owner) {
    MacroLookArc arc = arcs.get(owner);
    return arc != null && arc.isActive();
  }

  @Override
  public DampedFrame dampedTurn(
      Object owner,
      float currentYaw,
      float currentPitch,
      float targetYaw,
      float targetPitch,
      float smoothTime,
      float maxSpeed,
      float deltaSeconds,
      boolean reset) {
    AngleSmoother smoother = dampedTurns.computeIfAbsent(owner, ignored -> new AngleSmoother());
    boolean primedNow = reset || !smoother.isPrimed();
    if (primedNow) {
      smoother.prime(currentYaw, currentPitch);
    }
    smoother.step(targetYaw, targetPitch, smoothTime, maxSpeed, deltaSeconds);
    return new DampedFrame(smoother.yaw(), smoother.pitch(), primedNow);
  }

  @Override
  public void resetDampedTurn(Object owner) {
    dampedTurns.remove(owner);
  }

  @Override
  public void expectRotation(float yaw, float pitch) {
    expectedYaw = yaw;
    expectedPitch = Math.clamp(pitch, -90.0f, 90.0f);
    expectedSet = true;
  }

  @Override
  public void allowRotationGrace(long untilTick) {
    rotationGraceUntil = Math.max(rotationGraceUntil, untilTick);
  }

  @Override
  public void resetRotationExpectation() {
    expectedYaw = 0.0f;
    expectedPitch = 0.0f;
    expectedSet = false;
    rotationGraceUntil = 0;
  }

  @Override
  public Deviation rotationDeviation(LocalPlayer player, long tick, boolean monitoringActive) {
    if (!monitoringActive || tick < rotationGraceUntil || !expectedSet) {
      expectRotation(player.getYRot(), player.getXRot());
      return null;
    }
    return new Deviation(
        angularDifference(player.getYRot(), expectedYaw),
        Math.abs(player.getXRot() - expectedPitch));
  }

  /**
   * Returns the shortest unsigned yaw distance between two turns.
   *
   * @param first one yaw in degrees
   * @param second the other yaw in degrees
   * @return the unsigned difference, 0 to 180 degrees
   */
  public static float angularDifference(float first, float second) {
    float wrapped = (first - second) % 360.0f;
    if (wrapped < -180.0f) {
      wrapped += 360.0f;
    }
    if (wrapped > 180.0f) {
      wrapped -= 360.0f;
    }
    return Math.abs(wrapped);
  }
}
