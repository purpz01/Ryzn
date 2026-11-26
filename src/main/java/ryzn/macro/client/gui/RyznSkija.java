package ryzn.macro.client.gui;

import com.mojang.blaze3d.platform.NativeImage;
import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ColorAlphaType;
import io.github.humbleui.skija.ColorType;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.FontEdging;
import io.github.humbleui.skija.FontMgr;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.skija.Typeface;
import io.github.humbleui.skija.svg.SVGDOM;
import io.github.humbleui.types.RRect;
import java.io.IOException;
import java.util.LinkedHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ryzn.macro.client.RyznResources;
import ryzn.macro.client.gui.cozy.SettingsUiRenderer;

public final class RyznSkija {
  private static final Logger LOGGER = LoggerFactory.getLogger("ryzn-menu");
  private static final int CACHE_LIMIT = 128;
  private static final LinkedHashMap<Tile, CachedTexture> textures =
      new LinkedHashMap<>(32, 0.75f, true);
  private static boolean checked;
  private static boolean available;
  private static Typeface face;
  private static SVGDOM crown;
  private static int sequence;
  private static long frame;

  private RyznSkija() {}

  private record CachedTexture(Identifier id, long lastUsedFrame) {}

  public static void beginFrame(Minecraft client) {
    frame++;
    // GUI extraction queues draws. Releasing an entry during extraction can recycle its GPU
    // texture before the queued draw executes. Also retain the previous frame's full working set.
    while (textures.size() > CACHE_LIMIT) {
      var oldest = textures.firstEntry();
      if (oldest.getValue().lastUsedFrame() >= frame - 1) return;
      client.getTextureManager().release(textures.pollFirstEntry().getValue().id());
    }
  }

  private record Tile(
      int width,
      int height,
      int scale,
      int color,
      int endColor,
      float radius,
      String label,
      boolean bold,
      boolean centered,
      RyznMenuStyle.Symbol symbol,
      int headerHeight) {}

  static boolean available() {
    if (!checked) {
      checked = true;
      try (var stream = RyznResources.open("assets/ryzn/fonts/Roboto.ttf");
          Data data = Data.makeFromBytes(stream.readAllBytes())) {
        face = FontMgr.getDefault().makeFromData(data);
        available = face != null;
      } catch (IOException | RuntimeException | LinkageError problem) {
        LOGGER.warn("Ryzn could not initialize Skija menus", problem);
      }
    }
    return available;
  }

  static boolean text(
      GuiGraphicsExtractor g,
      String label,
      int x,
      int y,
      int width,
      int color,
      boolean bold,
      boolean centered) {
    return draw(g, x, y - 1, width, 12, color, color, 0, label, bold, centered, null);
  }

  private static final int CONTROL_TEXT = -2;

  static boolean controlText(
      GuiGraphicsExtractor g,
      String label,
      int x,
      int y,
      int width,
      int height,
      int color,
      boolean bold,
      boolean centered) {
    return draw(g, x, y, width, height, color, color, 0, label, bold, centered, null, CONTROL_TEXT);
  }

  static boolean rounded(GuiGraphicsExtractor g, int x, int y, int width, int height, int color) {
    return surface(g, x, y, width, height, color, color, 8);
  }

  static boolean surface(
      GuiGraphicsExtractor g,
      int x,
      int y,
      int width,
      int height,
      int start,
      int end,
      float radius) {
    return draw(g, x, y, width, height, start, end, radius, null, false, false, null);
  }

  static boolean crown(GuiGraphicsExtractor g, int x, int y, int width, int height, int color) {
    return draw(
        g, x, y, width, height, color, color, 0, null, false, false, RyznMenuStyle.Symbol.CROWN);
  }

  static boolean icon(
      GuiGraphicsExtractor g, RyznMenuStyle.Symbol symbol, int x, int y, int size, int color) {
    return draw(g, x, y, size, size, color, color, 0, null, false, false, symbol);
  }

  static boolean window(
      GuiGraphicsExtractor g, int x, int y, int width, int height, int header, float scale) {
    return draw(
        g, x - 1, y - 1, width + 2, height + 2, 0, 0, scale, null, false, false, null, header);
  }

  private static boolean draw(
      GuiGraphicsExtractor g,
      int x,
      int y,
      int width,
      int height,
      int color,
      int endColor,
      float radius,
      String label,
      boolean bold,
      boolean centered,
      RyznMenuStyle.Symbol symbol) {
    return draw(g, x, y, width, height, color, endColor, radius, label, bold, centered, symbol, -1);
  }

  private static boolean draw(
      GuiGraphicsExtractor g,
      int x,
      int y,
      int width,
      int height,
      int color,
      int endColor,
      float radius,
      String label,
      boolean bold,
      boolean centered,
      RyznMenuStyle.Symbol symbol,
      int headerHeight) {
    if (width <= 0 || height <= 0) return true;
    if (!available()) return false;
    Minecraft client = Minecraft.getInstance();
    int scale = Math.max(1, client.getWindow().getGuiScale());
    Tile tile =
        new Tile(
            width,
            height,
            scale,
            color,
            endColor,
            radius,
            label,
            bold,
            centered,
            symbol,
            headerHeight);
    try {
      CachedTexture texture = textures.get(tile);
      Identifier id = texture == null ? createTexture(client, tile) : texture.id();
      if (texture == null || texture.lastUsedFrame() != frame) {
        textures.put(tile, new CachedTexture(id, frame));
      }
      int pixelsWide = width * scale;
      int pixelsHigh = height * scale;
      g.blit(
          RenderPipelines.GUI_TEXTURED,
          id,
          x,
          y,
          0,
          0,
          width,
          height,
          pixelsWide,
          pixelsHigh,
          pixelsWide,
          pixelsHigh);
      return true;
    } catch (RuntimeException | LinkageError problem) {
      available = false;
      LOGGER.warn("Ryzn Skija menu rendering failed", problem);
      return false;
    }
  }

  private static Identifier createTexture(Minecraft client, Tile tile) {
    int width = tile.width() * tile.scale();
    int height = tile.height() * tile.scale();
    ImageInfo pixels = new ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL);
    byte[] rgba;
    try (Bitmap bitmap = new Bitmap();
        Paint paint = new Paint()) {
      if (!bitmap.allocPixels(ImageInfo.makeN32Premul(width, height))) {
        throw new IllegalStateException("Could not allocate menu texture");
      }
      try (Canvas canvas = new Canvas(bitmap)) {
        canvas.clear(0);
        canvas.scale(tile.scale(), tile.scale());
        paint.setColor(tile.color()).setAntiAlias(true);
        if (tile.headerHeight() >= 0) {
          SettingsUiRenderer.drawWindow(
              canvas, tile.width(), tile.height(), tile.headerHeight(), tile.radius());
        } else if (tile.symbol() == RyznMenuStyle.Symbol.CROWN) {
          if (crown == null) {
            try (var stream = RyznResources.open("assets/ryzn/textures/gui/pink_crown.svg");
                Data svg = Data.makeFromBytes(stream.readAllBytes())) {
              crown = new SVGDOM(svg);
            } catch (IOException problem) {
              throw new IllegalStateException("Could not load the Ryzn crown", problem);
            }
          }
          crown.setContainerSize(tile.width(), tile.height()).render(canvas);
        } else if (tile.symbol() != null) {
          float center = tile.width() / 2f;
          switch (tile.symbol()) {
            case ROUTE -> Icons.route(canvas, center, center, tile.width(), tile.color(), 1f);
            case PLAY -> Icons.play(canvas, center, center, tile.width(), tile.color(), 1f);
            case STOP ->
                canvas.drawRRect(
                    RRect.makeXYWH(center / 2f, center / 2f, center, center, 1), paint);
            case EXPAND ->
                Icons.chevronDown(canvas, center, center, tile.width(), tile.color(), 1f);
            case COLLAPSE -> {
              canvas.translate(center, center);
              canvas.rotate(180);
              canvas.translate(-center, -center);
              Icons.chevronDown(canvas, center, center, tile.width(), tile.color(), 1f);
            }
            case SEARCH -> Icons.magnifier(canvas, center, center, tile.width(), tile.color(), 1f);
            case CROWN -> throw new AssertionError();
          }
        } else if (tile.label() == null) {
          float radius = Math.min(tile.radius(), Math.min(tile.width(), tile.height()) / 2f);
          var bounds = RRect.makeXYWH(0, 0, tile.width(), tile.height(), radius);
          if (tile.color() == tile.endColor()) {
            canvas.drawRRect(bounds, paint);
          } else {
            try (Shader gradient =
                Shader.makeLinearGradient(
                    0, 0, 0, tile.height(), new int[] {tile.color(), tile.endColor()})) {
              paint.setColor(0xffffffff).setShader(gradient);
              canvas.drawRRect(bounds, paint);
            }
          }
        } else {
          try (Font font = new Font(face, 10f)) {
            font.setEdging(FontEdging.ANTI_ALIAS).setEmboldened(tile.bold());
            String label = tile.label();
            if (tile.headerHeight() == CONTROL_TEXT) {
              var lines =
                  RyznMenuStyle.controlLines(
                      label, tile.width(), tile.height(), font::measureTextWidth);
              float baseline = (tile.height() - lines.size() * 9) / 2f + 8;
              for (String line : lines) {
                float measured = font.measureTextWidth(line);
                float scale = measured > tile.width() ? tile.width() / measured : 1f;
                float x = tile.centered() ? (tile.width() - measured * scale) / 2 : 0;
                canvas.save();
                canvas.translate(x, baseline);
                canvas.scale(scale, scale);
                canvas.drawString(line, 0, 0, font, paint);
                canvas.restore();
                baseline += 9;
              }
            } else {
              if (font.measureTextWidth(label) > tile.width()) {
                int end = label.length();
                do {
                  end = label.offsetByCodePoints(end, -1);
                } while (end > 0
                    && font.measureTextWidth(label.substring(0, end) + "…") > tile.width());
                label = label.substring(0, end) + "…";
              }
              float x =
                  tile.centered()
                      ? Math.max(0, (tile.width() - font.measureTextWidth(label)) / 2)
                      : 0;
              canvas.drawString(label, x, 9, font, paint);
            }
          }
        }
      }
      rgba = bitmap.readPixels(pixels, width * 4L, 0, 0);
    }
    if (rgba == null) throw new IllegalStateException("Could not read menu texture");
    NativeImage image = new NativeImage(width, height, false);
    DynamicTexture texture;
    try {
      MemoryUtil.memByteBuffer(image.getPointer(), rgba.length).put(rgba);
      texture = new DynamicTexture(() -> "Ryzn Skija menu", image);
    } catch (RuntimeException | LinkageError problem) {
      image.close();
      throw problem;
    }
    Identifier id = Identifier.fromNamespaceAndPath("ryzn", "menu/tile_" + sequence++);
    client.getTextureManager().register(id, texture);
    return id;
  }
}
