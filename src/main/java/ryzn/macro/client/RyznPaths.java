package ryzn.macro.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Every path Ryzn writes to, under one roof.
 *
 * <pre>
 *   config/ryzn/
 *     settings.json     the settings ConfigManager reads and writes
 *     data/             things the player owns - builds, routes, recorded samples
 *     cache/            things Ryzn can rebuild - chunk caches, downloaded heads
 *     fonts/            typefaces, including the one the player drops in
 *     natives/          Skija binaries
 * </pre>
 */
public final class RyznPaths {

  private static final Logger logger = LoggerFactory.getLogger("ryzn");

  private RyznPaths() {}

  /**
   * {@code config/ryzn} - the one directory Ryzn owns.
   *
   * <p>Falls back to a build directory when there is no Fabric to ask, which is what the tests run
   * under. Without that, every path in the mod would throw in a unit test, and the callers that
   * used to resolve against the game directory themselves would each need their own fallback.
   */
  public static synchronized Path root() {
    try {
      return FabricLoader.getInstance().getConfigDir().resolve("ryzn");
    } catch (Throwable noFabric) {
      return Path.of("build", "test-config", "ryzn");
    }
  }

  /** The settings file. */
  public static Path settings() {
    return root().resolve("settings.json");
  }

  /** Things the player made and would miss: builds, routes, recorded samples. */
  public static Path data(String name) {
    return dir(root().resolve("data")).resolve(name);
  }

  /** Things Ryzn can rebuild from scratch. Safe to delete. */
  public static Path cache(String name) {
    return dir(root().resolve("cache")).resolve(name);
  }

  public static Path fonts() {
    return dir(root().resolve("fonts"));
  }

  public static Path natives() {
    return dir(root().resolve("natives"));
  }

  private static Path dir(Path path) {
    try {
      Files.createDirectories(path);
    } catch (IOException | RuntimeException ignored) {
      // The caller will fail on its own read or write with a better message than anything here.
    }
    return path;
  }
}
