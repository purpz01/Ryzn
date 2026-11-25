package ryzn.macro.client.feature.failsafe;

import java.util.function.Consumer;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class FailsafeDispatcher {
  @FunctionalInterface
  interface Notifier {
    void notify(FailsafeType type, FailsafeAction action, String details);
  }

  private final Consumer<String> stopper;

  /**
   * Returns false when nothing running knows how to hop, so the trigger falls back to a plain stop.
   */
  private final java.util.function.Predicate<String> hopper;

  private final Notifier notifier;

  void dispatch(FailsafeType type, FailsafeAction action, String details) {
    final String reason = type.label() + ": " + details;
    if (action == FailsafeAction.HOP && hopper.test(reason)) {
      notifier.notify(type, action, details);
      return;
    }
    notifier.notify(type, action == FailsafeAction.HOP ? FailsafeAction.STOP : action, details);
    if (action != FailsafeAction.IGNORE) {
      stopper.accept(reason);
    }
  }
}
