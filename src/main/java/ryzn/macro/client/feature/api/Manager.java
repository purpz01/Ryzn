package ryzn.macro.client.feature.api;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Explicit, ordered ownership of one category of feature elements.
 *
 * @param <T> the element type in this manager's category
 */
public abstract class Manager<T extends Element> {
  private final Map<Class<? extends T>, T> elements = new LinkedHashMap<>();
  private boolean initialized;

  /**
   * @description Registers category elements once without replacing their settings or runtime
   *     state.
   * @throws IllegalStateException if registration fails or a class is registered twice
   */
  public final void init() {
    if (initialized) {
      return;
    }
    registerElements();
    initialized = true;
  }

  /**
   * @description Lets the concrete manager register its known implementations in menu order.
   */
  protected abstract void registerElements();

  /**
   * @description Adds one initialized element under its exact concrete class.
   * @param element the process-lifetime element to own
   * @throws IllegalArgumentException if the concrete class is already registered
   */
  protected final void register(T element) {
    Class<? extends T> type = elementClass(element);
    if (elements.putIfAbsent(type, element) != null) {
      throw new IllegalArgumentException("Duplicate feature class " + type.getName());
    }
  }

  /**
   * @description Returns the instance registered under exactly the requested class.
   * @param type the concrete implementation class
   * @param <E> the expected element type
   * @return the registered element
   * @throws IllegalArgumentException if the class was not registered
   */
  public final <E extends T> E getByClass(Class<E> type) {
    T element = elements.get(type);
    if (element == null) {
      throw new IllegalArgumentException("Missing feature class " + type.getName());
    }
    return type.cast(element);
  }

  /**
   * @description Exposes elements in registration order for config and menu presentation.
   * @return an unmodifiable view of registered elements
   */
  public final Collection<T> elements() {
    return Collections.unmodifiableCollection(elements.values());
  }

  /**
   * @description Obtains the exact runtime class used as the registration key.
   * @param element the element whose runtime class is needed
   * @return its concrete class
   */
  @SuppressWarnings("unchecked")
  private Class<? extends T> elementClass(T element) {
    return (Class<? extends T>) element.getClass();
  }
}
