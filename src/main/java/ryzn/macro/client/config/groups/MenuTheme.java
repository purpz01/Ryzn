package ryzn.macro.client.config.groups;

import lombok.RequiredArgsConstructor;

/**
 * The client's colour themes. Each one is a window tone, a slightly lifted header tone, one tint
 * that every surface, pill and hairline uses at low alpha, four text tones, and the accent it ships
 * with. The settings menu reads these directly; the title screen, HUD and skinned vanilla menus get
 * them through the palette derived from the active theme. Order is the persisted index, so new
 * themes go at the end.
 */
@RequiredArgsConstructor
public enum MenuTheme {
  MIDNIGHT(
      "Midnight", 0x0E1830, 0x15233F, 0x7FA6FF, 0xEEF3FF, 0x9CB0D8, 0x6880AC, 0xCDDBF7, 0x217DFD),
  OBSIDIAN(
      "Obsidian", 0x0F1012, 0x17191D, 0xB9C0CC, 0xF2F3F5, 0xA3A9B4, 0x6E7480, 0xD5D9E0, 0x9B87F5),
  EVERGREEN(
      "Evergreen", 0x0B1A15, 0x112720, 0x7FE0BC, 0xEDFBF5, 0x9CCDBB, 0x62907F, 0xC8EFE0, 0x2FD28A),
  EMBER("Ember", 0x1A110D, 0x271913, 0xFFB088, 0xFFF3EC, 0xD8B3A0, 0xA07A68, 0xF7D9CA, 0xFF7A45),
  ORCHID("Orchid", 0x170F22, 0x21162F, 0xD2A4FF, 0xF8F0FF, 0xC2ACD8, 0x8870A4, 0xE7D5F7, 0xF062A8);

  public final String label;
  public final int windowRgb;
  public final int headerRgb;
  public final int tintRgb;
  public final int textHiRgb;
  public final int textMidRgb;
  public final int textLoRgb;
  public final int textIconRgb;
  public final int accentRgb;

  public int window() {
    return 0xFF000000 | windowRgb;
  }

  public int header() {
    return 0xFF000000 | headerRgb;
  }

  /** The theme's surface tint at {@code alpha}: cards, pills, hairlines and switch tracks. */
  public int tint(int alpha) {
    return (alpha << 24) | tintRgb;
  }
}
