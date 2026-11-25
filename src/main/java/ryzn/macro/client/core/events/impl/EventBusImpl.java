package ryzn.macro.client.core.events.impl;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ryzn.macro.client.core.events.api.Event;
import ryzn.macro.client.core.events.api.EventBus;
import ryzn.macro.client.core.events.api.RuntimeEvent;
import ryzn.macro.client.core.events.api.Subscribe;

/** Explicit listener registry with synchronous, deterministic dispatch. */
public final class EventBusImpl implements EventBus {
  private static final Logger logger = LoggerFactory.getLogger("ryzn-events");

  private final Map<Object, List<Listener>> owners = new IdentityHashMap<>();
  private final List<Listener> listeners = new ArrayList<>();
  private final BiConsumer<String, Throwable> failureHandler;
  private final BooleanSupplier runtimeActive;
  // Listeners per concrete event class, in registration order. Replaced whenever a listener is
  // added or removed, so a dispatch in progress keeps the list it started with.
  private volatile Map<Class<?>, List<Listener>> routes = new ConcurrentHashMap<>();

  /**
   * @description Creates the one process-lifetime bus with a platform failure reporter.
   * @param failureHandler receives the listener name and cause after a handler fails
   * @param runtimeActive false once {@link RuntimeEvent}s must no longer be delivered
   */
  public EventBusImpl(BiConsumer<String, Throwable> failureHandler, BooleanSupplier runtimeActive) {
    this.failureHandler = failureHandler;
    this.runtimeActive = runtimeActive;
  }

  /**
   * @description Discovers handlers only on the supplied instance and registers it once.
   * @param owner the instance that owns its handlers and later unregisters them
   * @throws IllegalArgumentException if an annotated handler has the wrong signature or name
   */
  @Override
  public synchronized void register(Object owner) {
    if (owners.containsKey(owner)) {
      return;
    }
    List<Method> methods = new ArrayList<>();
    for (Method method : owner.getClass().getMethods()) {
      if (method.isAnnotationPresent(Subscribe.class)) {
        validate(owner, method);
        methods.add(method);
      }
    }
    methods.sort(Comparator.comparing(Method::getName));
    List<Listener> registered = new ArrayList<>();
    String name = owner.getClass().getName();
    for (Method method : methods) {
      method.setAccessible(true);
      registered.add(
          new Listener(name, method.getParameterTypes()[0], event -> method.invoke(owner, event)));
    }
    owners.put(owner, registered);
    listeners.addAll(registered);
    routes = new ConcurrentHashMap<>();
  }

  /**
   * @description Removes only the handlers belonging to the exact owner instance.
   * @param owner the instance previously passed to register
   */
  @Override
  public synchronized void unregister(Object owner) {
    List<Listener> registered = owners.remove(owner);
    if (registered != null) {
      listeners.removeAll(registered);
      routes = new ConcurrentHashMap<>();
    }
  }

  @Override
  @SuppressWarnings("unchecked")
  public synchronized <E extends Event> void subscribe(Class<E> type, Consumer<? super E> handler) {
    listeners.add(
        new Listener(handler.getClass().getName(), type, event -> handler.accept((E) event)));
    routes = new ConcurrentHashMap<>();
  }

  /**
   * @description Calls compatible handlers on the publisher thread despite individual failures.
   * @param event the event instance delivered to registered listeners
   * @param <E> the event type
   * @return the same event after dispatch
   */
  @Override
  public <E extends Event> E post(E event) {
    boolean gated = event instanceof RuntimeEvent;
    for (Listener listener : route(event.getClass())) {
      if (gated && !runtimeActive.getAsBoolean()) {
        break;
      }
      try {
        listener.handler().handle(event);
      } catch (InvocationTargetException failure) {
        report(listener, event, failure.getCause());
      } catch (Exception | LinkageError failure) {
        report(listener, event, failure);
      }
    }
    return event;
  }

  private void report(Listener listener, Event event, Throwable failure) {
    if (event.fatalOnFailure()) {
      failureHandler.accept(listener.name(), failure);
    } else {
      logger.warn("[ryzn-events] {} listener {} failed", event, listener.name(), failure);
    }
  }

  private List<Listener> route(Class<?> eventClass) {
    Map<Class<?>, List<Listener>> current = routes;
    List<Listener> route = current.get(eventClass);
    if (route != null) {
      return route;
    }
    synchronized (this) {
      List<Listener> matching = new ArrayList<>();
      for (Listener listener : listeners) {
        if (listener.type().isAssignableFrom(eventClass)) {
          matching.add(listener);
        }
      }
      route = List.copyOf(matching);
      routes.put(eventClass, route);
      return route;
    }
  }

  private static void validate(Object owner, Method method) {
    Class<?>[] parameters = method.getParameterTypes();
    if (Modifier.isStatic(method.getModifiers())
        || method.getReturnType() != void.class
        || parameters.length != 1
        || !Event.class.isAssignableFrom(parameters[0])) {
      throw new IllegalArgumentException(
          "Invalid event handler " + owner.getClass().getName() + "." + method.getName());
    }
  }

  @FunctionalInterface
  private interface Handler {
    void handle(Event event) throws Exception;
  }

  private record Listener(String name, Class<?> type, Handler handler) {}
}
