package ryzn.macro.client.config.settings;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.experimental.Accessors;

/** A typed scalar setting with one validation and JSON persistence pipeline. */
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Accessors(fluent = true)
public final class Setting<T> {

  @Getter private final String key;
  @Getter private final String label;
  @Getter private final T defaultValue;
  private final UnaryOperator<T> validator;
  private final Predicate<JsonPrimitive> accepts;
  private final Function<JsonPrimitive, T> decoder;
  private T value;

  public static Setting<Boolean> bool(String key, String label, boolean defaultValue) {
    return new Setting<>(
        key,
        label,
        defaultValue,
        UnaryOperator.identity(),
        JsonPrimitive::isBoolean,
        JsonPrimitive::getAsBoolean,
        defaultValue);
  }

  public static Setting<Integer> integer(
      String key, String label, int defaultValue, int min, int max) {
    return integer(key, label, defaultValue, min, max, UnaryOperator.identity());
  }

  public static Setting<Integer> integer(
      String key,
      String label,
      int defaultValue,
      int min,
      int max,
      UnaryOperator<Integer> sanitizer) {
    if (min > max) {
      throw new IllegalArgumentException("min > max for " + key);
    }
    UnaryOperator<Integer> clamp = value -> Math.clamp(sanitizer.apply(value), min, max);
    var normalizedDefault = clamp.apply(defaultValue);
    return new Setting<>(
        key,
        label,
        normalizedDefault,
        clamp,
        JsonPrimitive::isNumber,
        JsonPrimitive::getAsInt,
        normalizedDefault);
  }

  /** A free-text setting, trimmed and capped so a hand-edited config cannot carry an essay. */
  public static Setting<String> text(String key, String label, String defaultValue, int maxLength) {
    UnaryOperator<String> trim =
        value -> {
          final String safe = value == null ? "" : value.trim();
          return safe.length() <= maxLength ? safe : safe.substring(0, maxLength);
        };
    return new Setting<>(
        key,
        label,
        trim.apply(defaultValue),
        trim,
        JsonPrimitive::isString,
        JsonPrimitive::getAsString,
        trim.apply(defaultValue));
  }

  /**
   * Creates an untrimmed text setting for existing fields that preserve user whitespace.
   *
   * @description Keeps non-null text byte-for-byte while mapping null to the empty string.
   * @param key the stable JSON property name
   * @param label the menu label for the value
   * @param defaultValue the value used when the property is absent
   * @return a typed string setting with no trimming or length cap
   */
  public static Setting<String> rawText(String key, String label, String defaultValue) {
    UnaryOperator<String> safe = value -> value == null ? "" : value;
    String normalizedDefault = safe.apply(defaultValue);
    return new Setting<>(
        key,
        label,
        normalizedDefault,
        safe,
        JsonPrimitive::isString,
        JsonPrimitive::getAsString,
        normalizedDefault);
  }

  public T get() {
    return value;
  }

  public void set(T value) {
    this.value = validator.apply(value);
  }

  public void reset() {
    value = defaultValue;
  }

  public void read(JsonObject object) {
    set(
        object.get(key) instanceof JsonPrimitive primitive && accepts.test(primitive)
            ? decoder.apply(primitive)
            : defaultValue);
  }

  public void write(JsonObject object) {
    switch (value) {
      case Boolean scalar -> object.addProperty(key, scalar);
      case Number scalar -> object.addProperty(key, scalar);
      case String scalar -> object.addProperty(key, scalar);
      case Character scalar -> object.addProperty(key, scalar);
      default ->
          throw new IllegalStateException(
              "Unsupported setting type: " + value.getClass().getName());
    }
  }
}
