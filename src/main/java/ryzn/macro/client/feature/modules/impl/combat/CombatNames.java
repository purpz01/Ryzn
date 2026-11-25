package ryzn.macro.client.feature.modules.impl.combat;

import java.util.LinkedHashSet;
import java.util.Set;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import ryzn.macro.client.util.text.MacroText;

/**
 * What the user picked to fight, parsed out of the two config strings that say so: a CSV of
 * SkyBlock mob names and a CSV of entity-type ids.
 *
 * <p>Names are matched against a rendered nametag, which carries level tags, colour codes and a
 * health suffix. {@link #matches} ignores case and formatting and accepts the selected text
 * anywhere in the displayed tag.
 *
 * <p>The parse is memoised on the CSV itself. These strings change only when the user edits them in
 * the GUI, but they are read per entity per frame (the ESP overlay), and the old code re-split and
 * re-normalised the whole CSV on every one of those calls.
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
final class CombatNames {

  /**
   * Last parse, keyed by the CSV it came from. One entry: there is one names CSV in play at a time.
   */
  private static CombatNames cached;

  private final String csv;
  private final Set<String> selected;

  static CombatNames parse(String csv) {
    final String key = csv == null ? "" : csv;
    final CombatNames last = cached; // immutable + all-final: safe to publish unsynchronised
    if (last != null && last.csv.equals(key)) {
      return last;
    }

    final Set<String> names = new LinkedHashSet<>();
    for (String entry : key.split(",")) {
      final String name = MacroText.normalizeSearchText(entry);
      if (!name.isEmpty()) {
        names.add(name);
      }
    }
    final CombatNames parsed = new CombatNames(key, Set.copyOf(names));
    cached = parsed;
    return parsed;
  }

  boolean hasSelections() {
    return !selected.isEmpty();
  }

  boolean matches(String nametagText) {
    if (selected.isEmpty()) {
      return false;
    }
    final String mobName = MacroText.normalizeSearchText(nametagText);
    return !mobName.isEmpty() && selected.stream().anyMatch(mobName::contains);
  }
}
