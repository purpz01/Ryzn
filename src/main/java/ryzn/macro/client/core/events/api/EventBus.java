package ryzn.macro.client.core.events.api;

import java.util.function.Consumer;

/** The one synchronous event dispatcher. Listeners run in the order they were added. */
public interface EventBus {
  /**
   * @description Discovers valid annotated handlers on exactly this owner and adds them once.
   * @param owner the element or infrastructure instance that owns the listeners
   * @throws IllegalArgumentException if an annotated handler has an invalid signature
   */
  void register(Object owner);

  /**
   * @description Removes all handlers registered by the owner, if present.
   * @param owner the same instance supplied to register
   */
  void unregister(Object owner);

  /**
   * @description Adds one process-lifetime handler at the current end of the listener order.
   * @param type the event class, or a supertype, to receive
   * @param handler the listener to call
   * @param <E> the event type
   */
  <E extends Event> void subscribe(Class<E> type, Consumer<? super E> handler);

  /**
   * @description Delivers an event synchronously in registration order.
   * @param event the immutable payload or mutable request to publish
   * @param <E> the event type
   * @return the same event, including any response or cancellation written by listeners
   */
  <E extends Event> E post(E event);
}
