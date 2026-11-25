package ryzn.macro.client.feature.hud;

import java.util.EnumMap;

/** Optional typed values parsed from the sidebar for macro-facing helpers and parser tests. */
final class SkyBlockHudData {

  enum Field {
    AREA,
    MODE,
    DATE,
    TIME,
    PURSE,
    BANK,
    BITS,
    OBJECTIVE,
    PROFILE,
    PET_NAME,
    PET_STATUS,
    EVENT_NAME,
    EVENT_TIMER
  }

  private final EnumMap<Field, String> values = new EnumMap<>(Field.class);

  String get(Field field) {
    return values.get(field);
  }

  boolean has(Field field) {
    return hasText(values.get(field));
  }

  void set(Field field, String value) {
    if (!has(field) && hasText(value)) {
      values.put(field, value);
    }
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
