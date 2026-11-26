package ryzn.macro.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

final class RyznMenuStyle {
  static final int TEXT = 0xfff6f7f9;
  static final int MUTED = 0xffa9adb5;
  static final int PINK = 0xffff69ad;
  static final int BORDER = 0x385f4d68;
  static final int PANEL = 0xd71d1626;
  static final int SIDEBAR = 0x54433751;

  private static final Identifier CROWN =
      Identifier.fromNamespaceAndPath("ryzn", "textures/gui/pink_crown.png");

  private RyznMenuStyle() {}

  private static final class SkijaRuntime {
    private static final boolean READY = SkijaNativeLoader.ensureLoaded();
  }

  static boolean skijaAvailable() {
    return SkijaRuntime.READY && RyznSkija.available();
  }

  static void text(
      GuiGraphicsExtractor g,
      Font font,
      String label,
      int x,
      int y,
      int width,
      int color,
      boolean bold,
      boolean centered) {
    if (skijaAvailable() && RyznSkija.text(g, label, x, y, width, color, bold, centered)) return;
    String fitted = font.plainSubstrByWidth(label, width);
    var text = Component.literal(fitted);
    if (bold) text.withStyle(net.minecraft.ChatFormatting.BOLD);
    int left = centered ? x + (width - font.width(text)) / 2 : x;
    g.text(font, text, left, y, color, false);
  }

  static void controlText(
      GuiGraphicsExtractor g,
      Font font,
      String label,
      int x,
      int y,
      int width,
      int height,
      int color,
      boolean bold,
      boolean centered) {
    if (width <= 0 || height <= 0) return;
    if (skijaAvailable()
        && RyznSkija.controlText(g, label, x, y, width, height, color, bold, centered)) return;
    var lines = controlLines(label, width, height, value -> font.width(controlLabel(value, bold)));
    int lineY = y + (height - lines.size() * 9) / 2;
    for (String value : lines) {
      var line = controlLabel(value, bold);
      int measured = font.width(line);
      float scale = measured > width ? (float) width / measured : 1f;
      float left = centered ? x + (width - measured * scale) / 2 : x;
      var pose = g.pose();
      pose.pushMatrix();
      try {
        pose.translate(left, lineY);
        pose.scale(scale, scale);
        g.text(font, line, 0, 0, color, false);
      } finally {
        pose.popMatrix();
      }
      lineY += 9;
    }
  }

  private static Component controlLabel(String label, boolean bold) {
    var component = Component.literal(label);
    return bold ? component.withStyle(net.minecraft.ChatFormatting.BOLD) : component;
  }

  static java.util.List<String> controlLines(
      String label, int width, int height, java.util.function.ToDoubleFunction<String> measure) {
    if (height < 18 || measure.applyAsDouble(label) <= width) return java.util.List.of(label);
    int split = -1;
    double best = Double.POSITIVE_INFINITY;
    for (int i = 1; i < label.length() - 1; i++) {
      if (label.charAt(i) != ' ') continue;
      double widest =
          Math.max(
              measure.applyAsDouble(label.substring(0, i).stripTrailing()),
              measure.applyAsDouble(label.substring(i + 1).stripLeading()));
      if (widest < best) {
        best = widest;
        split = i;
      }
    }
    return split < 0
        ? java.util.List.of(label)
        : java.util.List.of(
            label.substring(0, split).stripTrailing(), label.substring(split + 1).stripLeading());
  }

  static void rounded(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
    if (skijaAvailable() && RyznSkija.rounded(g, x, y, w, h, color)) return;
    if (w < 6 || h < 6) {
      g.fill(x, y, x + w, y + h, color);
      return;
    }
    g.fill(x + 2, y, x + w - 2, y + 1, color);
    g.fill(x + 1, y + 1, x + w - 1, y + 2, color);
    g.fill(x, y + 2, x + w, y + h - 2, color);
    g.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, color);
    g.fill(x + 2, y + h - 1, x + w - 2, y + h, color);
  }

  static void glass(
      GuiGraphicsExtractor g, int x, int y, int w, int h, int top, int bottom, float radius) {
    if (skijaAvailable() && RyznSkija.surface(g, x, y, w, h, top, bottom, radius)) return;
    rounded(g, x, y, w, h, bottom);
  }

  static void darkGlass(GuiGraphicsExtractor g, int x, int y, int width, int height) {
    rounded(g, x, y, width, height, RyznTheme.EDGE);
    glass(g, x + 1, y + 1, width - 2, height - 2, RyznTheme.TOP, RyznTheme.BOTTOM, 8);
  }

  static boolean window(
      GuiGraphicsExtractor g, int x, int y, int w, int h, int header, float scale) {
    return skijaAvailable() && RyznSkija.window(g, x, y, w, h, header, scale);
  }

  static void crown(GuiGraphicsExtractor g, int x, int y, int w, int h) {
    crown(g, x, y, w, h, -1);
  }

  static void crown(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
    if (skijaAvailable() && RyznSkija.crown(g, x, y, w, h, color)) return;
    g.blit(RenderPipelines.GUI_TEXTURED, CROWN, x, y, 0, 0, w, h, 1401, 1123, 1401, 1123, color);
  }

  enum Symbol {
    CROWN,
    ROUTE,
    PLAY,
    STOP,
    EXPAND,
    COLLAPSE,
    SEARCH
  }

  static void icon(
      GuiGraphicsExtractor g, Font font, Symbol symbol, int x, int y, int size, int color) {
    if (skijaAvailable() && RyznSkija.icon(g, symbol, x, y, size, color)) return;
    String glyph =
        switch (symbol) {
          case ROUTE -> "#";
          case PLAY -> ">";
          case STOP -> "■";
          case EXPAND -> "+";
          case COLLAPSE -> "-";
          case SEARCH -> "o";
          case CROWN -> "";
        };
    text(g, font, glyph, x, y + (size - 8) / 2, size, color, false, true);
  }

  enum ButtonKind {
    ACTION,
    LIST_ROW,
    SWITCH,
    TAB,
    SECTION,
    CARD,
    PLAY,
    PICKER,
    ROUTE
  }

  static final class MenuButton extends AbstractButton {
    private final Font font;
    private final Runnable action;
    private final boolean primary;
    private final ButtonKind kind;

    MenuButton(
        Font font, String label, int x, int y, int w, int h, boolean primary, Runnable action) {
      this(font, label, x, y, w, h, primary, ButtonKind.ACTION, action);
    }

    MenuButton(
        Font font,
        String label,
        int x,
        int y,
        int w,
        int h,
        boolean primary,
        ButtonKind kind,
        Runnable action) {
      super(x, y, w, h, Component.literal(label));
      this.font = font;
      this.action = action;
      this.primary = primary;
      this.kind = kind;
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float tick) {
      boolean focused = active && (isHovered() || isFocused());
      if (kind == ButtonKind.SWITCH) {
        drawSwitch(g, focused);
        return;
      }
      if (kind == ButtonKind.SECTION) {
        drawSection(g, focused);
        return;
      }
      if (kind == ButtonKind.PLAY) {
        int x = getX();
        int y = getY();
        int size = 19;
        rounded(
            g, x, y, getWidth(), getHeight(), focused ? RyznTheme.SELECTION : RyznTheme.ICON_TILE);
        Symbol glyph = getMessage().getString().equals("Stop") ? Symbol.STOP : Symbol.PLAY;
        icon(
            g,
            font,
            glyph,
            x + (getWidth() - size) / 2,
            y + (getHeight() - size) / 2,
            size,
            active ? RyznTheme.ACCENT : RyznTheme.MUTED);
        return;
      }
      if (kind == ButtonKind.CARD) {
        drawMacroCard(g, focused);
        return;
      }
      if (kind == ButtonKind.PICKER) {
        int x = getX();
        int y = getY();
        int w = getWidth();
        rounded(g, x, y, w, getHeight(), focused ? RyznTheme.SELECTION : RyznTheme.ICON_TILE);
        controlText(
            g,
            font,
            getMessage().getString(),
            x + 9,
            y,
            w - 35,
            getHeight(),
            RyznTheme.INK,
            false,
            false);
        icon(g, font, Symbol.EXPAND, x + w - 23, y + 4, 12, RyznTheme.MUTED);
        return;
      }
      if (kind != ButtonKind.ACTION && kind != ButtonKind.LIST_ROW) {
        drawSelection(g, focused);
        return;
      }
      int fill =
          primary && active ? RyznTheme.ACCENT : focused ? RyznTheme.SELECTION : RyznTheme.CARD;
      rounded(g, getX(), getY(), getWidth(), getHeight(), fill);
      controlText(
          g,
          font,
          getMessage().getString(),
          getX() + 4,
          getY(),
          getWidth() - 8,
          getHeight(),
          active ? (primary ? RyznTheme.ON_ACCENT : RyznTheme.INK) : RyznTheme.MUTED,
          primary && active,
          kind == ButtonKind.ACTION);
    }

    private void drawSwitch(GuiGraphicsExtractor g, boolean focused) {
      int x = getX();
      int y = getY();
      int w = getWidth();
      int h = getHeight();
      if (focused) rounded(g, x, y, w, h, RyznTheme.CARD);
      String message = getMessage().getString();
      String label = message.substring(0, message.lastIndexOf("  ·  "));
      int trackX = x + w - 31;
      int trackY = y + (h - 12) / 2;
      rounded(g, trackX, trackY, 25, 12, primary ? RyznTheme.SWITCH_ON : RyznTheme.ICON_TILE);
      rounded(g, trackX + (primary ? 13 : 2), trackY + 1, 10, 10, RyznTheme.INK);
      controlText(g, font, label, x + 7, y, w - 42, h, RyznTheme.INK, false, false);
    }

    private void drawMacroCard(GuiGraphicsExtractor g, boolean focused) {
      int x = getX();
      int y = getY();
      int h = getHeight();
      if (focused || primary) rounded(g, x, y, getWidth(), h, RyznTheme.ICON_TILE);
      rounded(g, x + 7, y + (h - 21) / 2, 21, 21, RyznTheme.ICON_TILE);
      icon(g, font, Symbol.ROUTE, x + 10, y + (h - 15) / 2, 15, RyznTheme.ACCENT);
      icon(
          g,
          font,
          primary ? Symbol.COLLAPSE : Symbol.EXPAND,
          x + getWidth() - 20,
          y + (h - 13) / 2,
          13,
          RyznTheme.MUTED);
      controlText(
          g,
          font,
          getMessage().getString(),
          x + 36,
          y,
          getWidth() - 58,
          h,
          RyznTheme.INK,
          true,
          false);
    }

    private void drawSection(GuiGraphicsExtractor g, boolean focused) {
      int x = getX();
      int y = getY();
      int w = getWidth();
      int h = getHeight();
      rounded(g, x, y, w, h, focused ? RyznTheme.ICON_TILE : RyznTheme.TOP);
      g.fill(x + 8, y + h - 1, x + w - 8, y + h, RyznTheme.EDGE);
      int grip = primary ? RyznTheme.ACCENT : RyznTheme.MUTED;
      for (int i = 0; i < 3; i++) {
        g.fill(x + 8, y + 9 + i * 4, x + 13, y + 10 + i * 4, grip);
      }
      controlText(
          g, font, getMessage().getString(), x + 20, y, w - 28, h, RyznTheme.INK, true, false);
    }

    private void drawSelection(GuiGraphicsExtractor g, boolean focused) {
      int x = getX();
      int y = getY();
      int w = getWidth();
      int h = getHeight();
      boolean route = kind == ButtonKind.ROUTE;
      boolean leftAligned = route || kind == ButtonKind.CARD;
      if (primary || focused) {
        rounded(g, x, y, w, h, primary ? RyznTheme.SELECTION : RyznTheme.CARD);
      }
      if (primary && route) {
        rounded(g, x + 3, y + 4, 2, h - 8, RyznTheme.ACCENT);
      } else if (primary && !leftAligned) {
        rounded(g, x + w / 2 - 8, y + h - 3, 16, 2, RyznTheme.ACCENT);
      }
      int pad = leftAligned ? 8 : 3;
      controlText(
          g,
          font,
          getMessage().getString(),
          x + pad,
          y,
          w - pad - 3,
          h,
          primary ? RyznTheme.ACCENT : focused ? RyznTheme.INK : RyznTheme.MUTED,
          primary,
          !leftAligned);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
      defaultButtonNarrationText(output);
    }

    @Override
    public void onPress(InputWithModifiers input) {
      action.run();
    }
  }
}
