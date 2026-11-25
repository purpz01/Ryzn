package ryzn.macro.client.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.util.profiling.metrics.profiling.MetricsRecorder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ryzn.macro.client.MacroInput;
import ryzn.macro.client.core.RyznHooks;

@Mixin(Minecraft.class)
public class MinecraftClientMixin {

  @Inject(method = "createTitle", at = @At("HEAD"), cancellable = true)
  private void ryzn$windowTitle(CallbackInfoReturnable<String> cir) {
    cir.setReturnValue("Ryzn");
  }

  /** Vanilla's own miss cooldown is 10 ticks; only {@code grabMouse()} ever parks it at 10000. */
  private static final int RYZN_GRAB_FREEZE_MIN = 20;

  @Shadow protected int missTime;

  @Shadow private MetricsRecorder metricsRecorder;

  @Inject(
      method = "<init>",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/network/chat/contents/KeybindResolver;setKeyResolver(Ljava/util/"
                      + "function/Function;)V",
              shift = At.Shift.BEFORE))
  private void ryzn$init(CallbackInfo ci) {
    RyznHooks.initialize();
  }

  /**
   * Vanilla gates continuous held attack/use handling on {@code mouseHandler.isMouseGrabbed()}
   * inside {@code handleKeybinds}. That flag can go stale-false while a macro runs: {@code
   * grabMouse()} silently no-ops when the window is not active, so closing any screen while tabbed
   * out (or minimized at 1 fps) leaves the mouse ungrabbed and held macro keys are ignored forever
   * - the bot aims at the target but never uses the key, until the user opens+closes chat while
   * focused. While a macro owns the look, treat the mouse as grabbed for this one check; the key's
   * own down/click state still gates the actual press. Client-side input only, sends nothing.
   */
  @Inject(method = "handleKeybinds", at = @At("HEAD"))
  private void ryzn$clearGrabAttackFreeze(CallbackInfo ci) {
    if (missTime >= RYZN_GRAB_FREEZE_MIN && RyznHooks.ownsLook()) {
      missTime = 0;
    }
  }

  @com.llamalad7.mixinextras.injector.ModifyExpressionValue(
      method = "handleKeybinds",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/client/MouseHandler;isMouseGrabbed()Z"))
  private boolean ryzn$macroInputIgnoresStaleMouseGrab(boolean original) {
    return RyznHooks.acceptsHeldInput(original);
  }

  /**
   * Keeps the game "active" while the window is not focused, so alt-tabbing away from a running
   * macro does not open the pause screen or stall input handling.
   *
   * <p>Frame-rate throttling is handled separately by {@link FramerateLimitTrackerMixin}.
   */
  @com.llamalad7.mixinextras.injector.ModifyExpressionValue(
      method = "pauseIfInactive",
      at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/Window;isFocused()Z"))
  private boolean ryzn$keepFocusForInactivePause(boolean original) {
    return original || ryzn$keepFocus();
  }

  @Inject(method = "isWindowActive", at = @At("HEAD"), cancellable = true)
  private void ryzn$keepWindowActive(CallbackInfoReturnable<Boolean> cir) {
    if (ryzn$keepFocus()) {
      cir.setReturnValue(true);
    }
  }

  /**
   * On while any macro is running, so alt-tabbing mid-grind never pauses it. The config toggle
   * forces it on for normal play too; left off by default because that would otherwise silently
   * stop the game pausing when you tab away.
   */
  @Unique
  private static boolean ryzn$keepFocus() {
    return RyznHooks.keepFocus();
  }

  /**
   * Keeps the crosshair pick (block and entity hit result) honest while the macro camera smoother
   * is easing the visual camera. {@code Minecraft.pick} delegates to {@code
   * LocalPlayer.raycastHitResult}, which reads the same partial-tick view getters we smooth ({@code
   * Entity.getViewVector} -> {@code getViewXRot}/{@code getViewYRot}). Without this, whenever the
   * smoother is active (a running macro, combat aim-assist locked on, or the short fresh-grace
   * window) the interaction / attack raycast would aim at the lagged CAMERA angle instead of the
   * real head angle. We flag the pick window here; {@link
   * ryzn.macro.client.mixin.entity.ClientPlayerMixin} returns the raw angle while the flag is set,
   * so only the visual camera lags - the ray stays on the server-true angle. Read-only, sends
   * nothing.
   */
  @Inject(method = "pick(F)V", at = @At("HEAD"))
  private void ryzn$rawLookForPickStart(float partialTick, CallbackInfo ci) {
    RyznHooks.setPicking(true);
  }

  @Inject(method = "pick(F)V", at = @At("RETURN"))
  private void ryzn$rawLookForPickEnd(float partialTick, CallbackInfo ci) {
    RyznHooks.setPicking(false);
  }

  /**
   * {@link MacroInput#isCrosshairOnRealPlayer} already refuses to press use while the crosshair
   * rests on another account, but it checks at QUEUE time: a macro's {@code KeyMapping.click} is
   * consumed by {@code handleKeybinds} a tick later, against a {@code hitResult} re-picked in
   * between. A player sprinting past a placement slips into that window and the click lands on
   * them, opening their profile menu - which the unexpected-screen failsafe then reads as a reason
   * to stop everything. Re-checking here, at the point vanilla itself reads {@code hitResult},
   * closes the window entirely. Only while a macro drives the head; nothing is sent, the click is
   * simply dropped and retried.
   */
  @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
  private void ryzn$noMacroUseOnRealPlayers(CallbackInfo ci) {
    final Minecraft mc = (Minecraft) (Object) this;
    if (RyznHooks.suppressMacroUse(mc)) {
      ci.cancel();
    }
  }
}
