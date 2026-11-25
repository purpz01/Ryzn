package ryzn.macro.client.config;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ryzn.macro.client.RyznPaths;

/** Persists Ryzn settings in the instance configuration directory. */
public final class ConfigManager {

  private static final Logger logger = LoggerFactory.getLogger("ryzn-config");
  private static final String FILE_NAME = "ryzn.json";
  private static final String LEGACY_NAME = "ryzn.legacy.json";
  private static final long DEFAULT_DEBOUNCE_MILLIS = 750L;
  private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

  private final Path file;
  private final RyznSettings settings;
  private final long debounceMillis;
  @Getter private boolean dirty;
  private boolean live;

  private long lastChangeMillis;

  public ConfigManager(Path file) {
    this(file, DEFAULT_DEBOUNCE_MILLIS, new RyznSettings());
  }

  public ConfigManager(Path file, long debounceMillis) {
    this(file, debounceMillis, new RyznSettings());
  }

  private ConfigManager(Path file, long debounceMillis, RyznSettings settings) {
    this.file = file;
    this.debounceMillis = debounceMillis;
    this.settings = settings;
  }

  public RyznSettings settings() {
    return settings;
  }

  private void saveDefaults() {
    if (live) {
      markDirty();
    } else {
      saveNow();
    }
  }

  /** Read the existing config, or rotate/replace it and write fresh defaults. */
  public synchronized void load() {
    if (!Files.exists(file)) {
      settings.read(new JsonObject());
      saveDefaults();
      return;
    }

    JsonObject root;
    try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      root = JsonTrees.parseObject(reader);
    } catch (Exception malformed) {
      logger.warn("[ryzn] {} is malformed, backing up and regenerating", file, malformed);
      if (backupBroken()) {
        settings.read(new JsonObject());
        saveDefaults();
      }
      return;
    }
    if (isLegacyFlat(root)) {
      if (rotateLegacy()) {
        settings.read(new JsonObject());
        saveDefaults();
      }
      return;
    }
    settings.read(root);
  }

  /**
   * Mark the in-memory settings changed; the next {@link #tickSave()} past the debounce window
   * flushes.
   */
  public synchronized void markDirty() {
    dirty = true;
    lastChangeMillis = System.currentTimeMillis();
  }

  /**
   * Flush a pending change once it has settled for the debounce interval. Call from the client
   * tick.
   */
  public synchronized void tickSave() {
    if (dirty && System.currentTimeMillis() - lastChangeMillis >= debounceMillis) {
      saveNow();
    }
  }

  /** Force a flush if anything is pending (client shutdown). */
  public synchronized void shutdownSave() {
    if (dirty) {
      saveNow();
    }
  }

  /**
   * Write the complete tree now through a temp file + atomic replace, then clear the dirty flag.
   */
  public synchronized boolean saveNow() {
    Path temp = null;
    try {
      Files.createDirectories(file.getParent());
      temp = Files.createTempFile(file.getParent(), "ryzn", ".json.tmp");
      JsonObject tree = settings.write();
      try (Writer w = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
        JsonTrees.write(tree, w);
      }
      replace(temp, file);
      temp = null;
      dirty = false;
      return true;
    } catch (IOException e) {
      logger.warn("[ryzn] failed to save {}", file, e);
      return false;
    } finally {
      deleteQuietly(temp);
    }
  }

  /** Restores defaults across all sections and writes them to the global config file. */
  public synchronized boolean resetToDefaults() {
    JsonObject previous = settings.write();
    settings.read(new JsonObject());
    if (saveNow()) {
      return true;
    }
    settings.read(previous);
    return false;
  }

  /**
   * The new format always carries a schema version and a {@code features} object; anything else is
   * legacy.
   */
  private static boolean isLegacyFlat(JsonObject root) {
    return !root.has("schema_version") || !(root.get("features") instanceof JsonObject);
  }

  private boolean rotateLegacy() {
    Path target = file.resolveSibling(LEGACY_NAME);
    if (Files.exists(target)) {
      target = file.resolveSibling("ryzn.legacy." + LocalDateTime.now().format(STAMP) + ".json");
    }
    return moveAside(target);
  }

  private boolean backupBroken() {
    return moveAside(
        file.resolveSibling("ryzn." + LocalDateTime.now().format(STAMP) + ".broken.json"));
  }

  private boolean moveAside(Path target) {
    try {
      Files.move(file, target);
      return true;
    } catch (IOException e) {
      logger.warn("[ryzn] failed to move {} to {}", file, target, e);
      return false;
    }
  }

  static void replace(Path temp, Path file) throws IOException {
    try {
      Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException atomicUnsupported) {
      Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private static void deleteQuietly(Path temp) {
    if (temp == null) {
      return;
    }
    try {
      Files.deleteIfExists(temp);
    } catch (IOException e) {
      logger.warn("[ryzn] failed to delete temp config {}", temp, e);
    }
  }

  // ---- live singleton for the running mod ----

  private static ConfigManager instance;

  public static synchronized ConfigManager global() {
    if (instance == null) {
      instance = new ConfigManager(liveFile(), DEFAULT_DEBOUNCE_MILLIS, RyznSettings.get());
      instance.live = true;
    }
    return instance;
  }

  /** Mark the live config dirty if (and only if) the manager has been wired; otherwise a no-op. */
  static void markLiveDirty() {
    if (instance != null) {
      instance.markDirty();
    }
  }

  /** Eagerly load on mod init. */
  public static void init() {
    global();
  }

  public static void initLocal() {
    ConfigManager manager = global();
    manager.load();
    if (manager.settings.clearRunToggles()) {
      manager.markDirty();
    }
  }

  /** Per-tick debounced flush hook for the client. */
  public static void tick() {
    global().tickSave();
  }

  /** Final flush on client shutdown. */
  public static void shutdown() {
    global().shutdownSave();
  }

  private static Path liveFile() {
    try {
      return RyznPaths.settings();
    } catch (Throwable noFabric) {
      return Path.of("build", "test-config", FILE_NAME);
    }
  }
}
