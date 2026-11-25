package ryzn.macro.client.feature.modules.impl.combat;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.experimental.Accessors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import ryzn.macro.client.debug.DebugLog;
import ryzn.macro.client.input.MacroKeys;

@Getter(AccessLevel.PACKAGE)
@Accessors(fluent = true)
final class CombatTelemetry {
  private static final int TRACE_EVERY_TICKS = 4;

  @Getter(AccessLevel.NONE)
  private final CombatTraceCadence cadence = new CombatTraceCadence();

  private String chaseKeys = "none";
  private boolean chaseSprint;
  private String holdReason = "none";
  private String directReason = "none";
  private double yawError;
  private boolean targetVisible;
  private boolean trackingForced;
  private final StringBuilder motionFrames = new StringBuilder();
  private long lastFrameNanos;
  private int capturedFrames;
  private int droppedFrames;

  void reset() {
    cadence.reset();
    chaseKeys = "none";
    chaseSprint = false;
    holdReason = "none";
    directReason = "none";
    yawError = 0.0;
    targetVisible = false;
    trackingForced = false;
    motionFrames.setLength(0);
    lastFrameNanos = 0L;
    capturedFrames = 0;
    droppedFrames = 0;
  }

  void captureFrame(LocalPlayer player) {
    if (!DebugLog.isEnabled(DebugLog.Category.MACRO)) {
      return;
    }
    long now = System.nanoTime();
    float dt = lastFrameNanos == 0L ? 0f : (now - lastFrameNanos) / 1_000_000f;
    lastFrameNanos = now;
    if (capturedFrames >= 256) {
      droppedFrames++;
      return;
    }
    if (capturedFrames++ > 0) {
      motionFrames.append(',');
    }
    motionFrames
        .append('[')
        .append(dt)
        .append(',')
        .append(player.getYRot())
        .append(',')
        .append(player.getXRot())
        .append(',')
        .append(player.getViewYRot(1f))
        .append(',')
        .append(player.getViewXRot(1f))
        .append(']');
  }

  void traceMotion(Minecraft mc, boolean tracking, boolean navOwned, int targetId) {
    if (!DebugLog.isEnabled(DebugLog.Category.MACRO)) {
      motionFrames.setLength(0);
      capturedFrames = 0;
      droppedFrames = 0;
      lastFrameNanos = 0L;
      return;
    }
    LocalPlayer player = mc.player;
    DebugLog.detail(
        DebugLog.Category.MACRO,
        "[combat-motion] tick=%d target=%d tracking=%b route=%b pos=(%.4f,%.4f,%.4f) "
            + "vel=(%.5f,%.5f,%.5f) look=(%.3f,%.3f) keys=%s dropped=%d frames=[%s]",
        player.tickCount,
        targetId,
        tracking,
        navOwned,
        player.getX(),
        player.getY(),
        player.getZ(),
        player.getDeltaMovement().x,
        player.getDeltaMovement().y,
        player.getDeltaMovement().z,
        player.getYRot(),
        player.getXRot(),
        (MacroKeys.isDown(mc.options.keyUp) ? "W" : "")
            + (MacroKeys.isDown(mc.options.keyLeft) ? "A" : "")
            + (MacroKeys.isDown(mc.options.keyDown) ? "S" : "")
            + (MacroKeys.isDown(mc.options.keyRight) ? "D" : "")
            + (MacroKeys.isDown(mc.options.keyJump) ? "J" : "")
            + (MacroKeys.isDown(mc.options.keyShift) ? "C" : "")
            + (MacroKeys.isDown(mc.options.keySprint) ? "R" : ""),
        droppedFrames,
        motionFrames);
    motionFrames.setLength(0);
    capturedFrames = 0;
    droppedFrames = 0;
  }

  void observeMovement(String keys, boolean sprint) {
    chaseKeys = keys;
    chaseSprint = sprint;
  }

  void traceAim(Minecraft mc, boolean tracking, int targetId, CombatAim.AimSample sample) {
    if (!DebugLog.isEnabled(DebugLog.Category.MACRO)
        || !tracking
        || sample == null
        || sample.targetId() != targetId) {
      return;
    }
    DebugLog.detail(
        DebugLog.Category.MACRO,
        "[combat-aim] tick=%d target=%d pick=%d ageMs=%.3f dtMs=%.3f "
            + "mob=(%.4f,%.4f,%.4f) mobVel=(%.5f,%.5f,%.5f) "
            + "eye=(%.4f,%.4f,%.4f) box=(%.4f,%.4f,%.4f,%.4f,%.4f,%.4f) "
            + "point=(%.4f,%.4f,%.4f) want=(%.3f,%.3f) error=(%.3f,%.3f)",
        mc.player.tickCount,
        targetId,
        mc.crosshairPickEntity == null ? -1 : mc.crosshairPickEntity.getId(),
        (System.nanoTime() - sample.atNs()) / 1_000_000.0,
        sample.dt() * 1000.0,
        sample.targetPos().x,
        sample.targetPos().y,
        sample.targetPos().z,
        sample.targetVelocity().x,
        sample.targetVelocity().y,
        sample.targetVelocity().z,
        sample.eye().x,
        sample.eye().y,
        sample.eye().z,
        sample.hitbox().minX,
        sample.hitbox().minY,
        sample.hitbox().minZ,
        sample.hitbox().maxX,
        sample.hitbox().maxY,
        sample.hitbox().maxZ,
        sample.point().x,
        sample.point().y,
        sample.point().z,
        sample.yaw(),
        sample.pitch(),
        sample.deltaYaw(),
        sample.deltaPitch());
  }

  void observeHold(String reason) {
    holdReason = reason;
  }

  void observeTarget(double yawError, boolean visible, String reason, boolean forced) {
    observeAim(yawError, visible);
    directReason = reason;
    trackingForced = forced;
  }

  void observeAim(double yawError, boolean visible) {
    this.yawError = yawError;
    targetVisible = visible;
  }

  void traceAttack(
      double distance,
      double range,
      boolean tracking,
      boolean rayReady,
      boolean crosshairReady,
      int attackDelay,
      boolean fired,
      String reason) {
    if (!cadence.shouldTraceAttack(
        DebugLog.isEnabled(DebugLog.Category.MACRO), fired, TRACE_EVERY_TICKS)) {
      return;
    }
    DebugLog.detail(
        DebugLog.Category.MACRO,
        "[combat] attack dist=%.2f/%.2f tracking=%b ray=%b pick=%b delay=%d keys=%s sprint=%b "
            + "fired=%b reason=%s",
        distance,
        range,
        tracking,
        rayReady,
        crosshairReady,
        attackDelay,
        chaseKeys,
        chaseSprint,
        fired,
        reason);
  }

  void traceMode(
      double distance,
      double hitboxGap,
      boolean hittable,
      boolean stalled,
      boolean stallBridge,
      boolean directChasing,
      boolean bodyMoving,
      boolean navOwned,
      boolean tracking,
      int noProgressTicks,
      boolean navigationActive) {
    if (!cadence.shouldTraceMode(DebugLog.isEnabled(DebugLog.Category.MACRO), TRACE_EVERY_TICKS)) {
      return;
    }
    final String mode =
        stalled
            ? (stallBridge ? "stall>bridge" : "stall")
            : directChasing && holdReason.startsWith("rotate")
                ? "rotate-track"
                : directChasing ? (bodyMoving ? "direct" : "hold") : navOwned ? "route" : "idle";
    DebugLog.detail(
        DebugLog.Category.MACRO,
        "[combat] mode=%s dist=%.2f gap=%.2f yaw=%.1f los=%b hit=%b track=%b forced=%b reason=%s "
            + "hold=%s noProg=%d nav=%b keys=%s sprint=%b",
        mode,
        distance,
        hitboxGap,
        yawError,
        targetVisible,
        hittable,
        tracking,
        trackingForced,
        directReason,
        holdReason,
        noProgressTicks,
        navigationActive,
        chaseKeys,
        chaseSprint);
  }
}
