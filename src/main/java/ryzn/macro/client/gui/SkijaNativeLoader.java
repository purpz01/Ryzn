package ryzn.macro.client.gui;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import lombok.Getter;
import lombok.experimental.Accessors;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ryzn.macro.client.RyznPaths;

/**
 * Prepares the pinned Skija native for the current OS/arch before any Skija class initializes.
 *
 * <p>Windows ships two files (skija.dll plus a separate icudtl.dat); Linux and macOS ship one
 * self-contained library with ICU statically linked. Each platform's Maven artifact, jar hash, and
 * per-file hashes are pinned below. The bundled artifact is verified before installation.
 */
@Accessors(fluent = true)
public final class SkijaNativeLoader {

  private static final Logger logger = LoggerFactory.getLogger("ryzn-skija");

  private static final String SKIJA_VERSION = "0.143.16";
  private static final String SHARED_SHA256 =
      "9edf6265136e3d2a8ceb8cabe9b2788c32d7768782b7b2c98b88aafb78c0ec5d";
  private static final String TYPES_SHA256 =
      "38d94d00770c4f261ffb50ee68d5da853c416c8fe7c57842f0e28049fc26cca8";

  /**
   * A pinned per-platform native bundle: the Maven artifact, its jar hash, and the file(s) to
   * stage.
   */
  private record Platform(String id, String artifact, String jarSha256, List<NativeFile> files) {}

  private static final List<Platform> PLATFORMS =
      List.of(
          new Platform(
              "windows-x64",
              "skija-windows-x64",
              "945b74301040eb4c33e200daf7a2bfa2e3da2a9350545dc9668310dbf1690fd4",
              List.of(
                  new NativeFile(
                      "io/github/humbleui/skija/windows/x64/skija.dll",
                      "skija.dll",
                      "7dc794e7d47050ab3b30d658f4b41f7d341f91ac79dd92d7be9b4389076ffe51"),
                  new NativeFile(
                      "io/github/humbleui/skija/windows/x64/icudtl.dat",
                      "icudtl.dat",
                      "9ae98c06cbb0ea43c5cd6b5725310c008c65e46072421a1118cb88e1de9a8b92"))),
          new Platform(
              "linux-x64",
              "skija-linux-x64",
              "500da37afd7db34b7785c69bb3cc56475423fb0bfc227835b67c879e825e78d0",
              List.of(
                  new NativeFile(
                      "io/github/humbleui/skija/linux/x64/libskija.so",
                      "libskija.so",
                      "c8a32ccbbbfe3c0736c896879f708be5a2b54846862f9261bd3f1f4d1bcf5da8"))),
          new Platform(
              "linux-arm64",
              "skija-linux-arm64",
              "cc55443072a1a13734ae5dd7016dd0b344238aa67844a7c82998a832da5cf152",
              List.of(
                  new NativeFile(
                      "io/github/humbleui/skija/linux/arm64/libskija.so",
                      "libskija.so",
                      "c0b3e5cb91a969c4f98ed73bbb1e2ff3d6731bb770eed222cd3a4910466f4759"))),
          new Platform(
              "macos-x64",
              "skija-macos-x64",
              "8fbd508696a72ac9e3f1b4e0f89eba78ba18e051175393427bd10ea62727769d",
              List.of(
                  new NativeFile(
                      "io/github/humbleui/skija/macos/x64/libskija.dylib",
                      "libskija.dylib",
                      "0153ffeb22637f51d8e27b6a1ea78b6ccbad0f0ced3ef7879fad009889dbb91b"))),
          new Platform(
              "macos-arm64",
              "skija-macos-arm64",
              "2cf06ed2c629ef65e543f96ce1a8586945ae6613bfc1953030a8b6ce3a47eda1",
              List.of(
                  new NativeFile(
                      "io/github/humbleui/skija/macos/arm64/libskija.dylib",
                      "libskija.dylib",
                      "8333667e3712651e76e872d744164f879c99249f6dc5ebce4d0b378239c3e72b"))));

  private static volatile boolean prepared = false;
  @Getter private static volatile boolean ok = false;
  @Getter private static volatile String status = "uninitialized";

  private SkijaNativeLoader() {}

  public static synchronized boolean ensureLoaded() {
    final Platform platform = currentPlatform();
    if (prepared) {
      if (ok && platform != null) {
        final Path dir = nativeDir(platform.id());
        if (verifyFiles(dir, platform.files())) {
          System.setProperty("skija.library.path", dir.toAbsolutePath().toString());
          return true;
        }
        ok = false;
        prepared = false;
        logger.warn("[ryzn] Skija native cache changed after preparation; reinstalling");
      } else {
        return false;
      }
    }
    prepared = true;
    try {
      if (platform == null) {
        status =
            "unsupported platform: "
                + System.getProperty("os.name")
                + " "
                + System.getProperty("os.arch");
        logger.warn("[ryzn] Skija native offload skipped: {}", status);
        return false;
      }

      final Path runtime =
          FabricLoader.getInstance().getGameDir().resolve("skija").resolve(SKIJA_VERSION);
      final Path shared =
          requireRuntimeJar(runtime, "skija-shared-" + SKIJA_VERSION + ".jar", SHARED_SHA256);
      final Path types = requireRuntimeJar(runtime, "types-0.2.0.jar", TYPES_SHA256);
      final Path dir = nativeDir(platform.id());
      if (!verifyFiles(dir, platform.files())) {
        final Path jar =
            requireRuntimeJar(
                runtime, platform.artifact() + "-" + SKIJA_VERSION + ".jar", platform.jarSha256());
        installExternal(platform, jar, dir);
      }
      if (!verifyFiles(dir, platform.files())) {
        throw new IllegalStateException("verified Skija native cache was not produced");
      }

      System.setProperty("skija.library.path", dir.toAbsolutePath().toString());
      final var launcher = FabricLauncherBase.getLauncher();
      launcher.addToClassPath(types);
      launcher.addToClassPath(shared);
      status = "ready: " + dir.toAbsolutePath();
      ok = true;
      logger.info("[ryzn] Skija native path prepared ({})", dir.toAbsolutePath());
      return true;
    } catch (Throwable t) {
      ok = false;
      status =
          "FAILED: "
              + t.getClass().getSimpleName()
              + (t.getMessage() != null ? " - " + t.getMessage() : "");
      logger.error("[ryzn] Skija native setup failed", t);
      return false;
    }
  }

  static boolean verifyFiles(Path dir, List<NativeFile> files) {
    if (!Files.isDirectory(dir)) {
      return false;
    }
    final Set<String> expected = new HashSet<>();
    for (NativeFile file : files) {
      expected.add(file.fileName());
    }
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
      for (Path child : stream) {
        final String name = child.getFileName().toString();
        if (!expected.contains(name) || !Files.isRegularFile(child)) {
          return false;
        }
      }
      for (NativeFile file : files) {
        if (!verifyFile(dir.resolve(file.fileName()), file.sha256())) {
          return false;
        }
      }
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  static void extractNativeFiles(Path sourceJar, Path destinationDir, List<NativeFile> files)
      throws IOException {
    Files.createDirectories(destinationDir);
    final Map<String, NativeFile> byEntry = new HashMap<>();
    final Set<String> seen = new HashSet<>();
    for (NativeFile file : files) {
      byEntry.put(file.jarEntry(), file);
    }

    try (InputStream input = Files.newInputStream(sourceJar);
        ZipInputStream zip = new ZipInputStream(input)) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        final NativeFile file = byEntry.get(entry.getName());
        if (file == null || entry.isDirectory()) {
          zip.closeEntry();
          continue;
        }
        Files.copy(
            zip,
            resolveInside(destinationDir, file.fileName()),
            StandardCopyOption.REPLACE_EXISTING);
        seen.add(file.jarEntry());
        zip.closeEntry();
      }
    }

    for (NativeFile file : files) {
      if (!seen.contains(file.jarEntry())) {
        throw new IllegalStateException("Skija jar missing native entry: " + file.jarEntry());
      }
    }
    if (!verifyFiles(destinationDir, files)) {
      throw new IllegalStateException("Skija native extraction failed hash verification");
    }
  }

  private static Path nativeDir(String platformId) {
    return RyznPaths.natives().resolve("skija").resolve(SKIJA_VERSION).resolve(platformId);
  }

  private static Path requireRuntimeJar(Path runtime, String name, String sha256)
      throws IOException {
    final Path jar = runtime.resolve(name);
    if (!verifyFile(jar, sha256)) {
      throw new IOException("Missing or invalid Skija runtime jar: " + jar);
    }
    return jar;
  }

  private static void installExternal(Platform platform, Path jar, Path targetDir)
      throws IOException {
    final Path parent = targetDir.getParent();
    if (parent == null) {
      throw new IOException("Skija native directory has no parent: " + targetDir);
    }
    Files.createDirectories(parent);
    final Path tempRoot =
        Files.createTempDirectory(
            parent, ".skija-" + SKIJA_VERSION + "-" + UUID.randomUUID() + "-");
    try {
      final Path stage = tempRoot.resolve("stage");
      extractNativeFiles(jar, stage, platform.files());
      installVerifiedFiles(stage, targetDir, platform.files());
    } finally {
      deleteRecursively(tempRoot);
    }
  }

  private static void installVerifiedFiles(Path stage, Path targetDir, List<NativeFile> files)
      throws IOException {
    if (!verifyFiles(stage, files)) {
      throw new IllegalStateException("staged Skija natives failed hash verification");
    }
    final Path parent = targetDir.getParent();
    if (parent == null) {
      throw new IOException("Skija native directory has no parent: " + targetDir);
    }
    Files.createDirectories(parent);

    Path backup = null;
    if (Files.exists(targetDir)) {
      backup = parent.resolve(targetDir.getFileName() + ".old-" + UUID.randomUUID());
      moveAtomic(targetDir, backup);
    }

    boolean installed = false;
    boolean success = false;
    try {
      moveAtomic(stage, targetDir);
      installed = true;
      if (!verifyFiles(targetDir, files)) {
        throw new IllegalStateException("installed Skija natives failed hash verification");
      }
      success = true;
    } catch (IOException | RuntimeException e) {
      if (!installed && backup != null && !Files.exists(targetDir)) {
        try {
          moveAtomic(backup, targetDir);
          backup = null;
        } catch (IOException restoreFailure) {
          e.addSuppressed(restoreFailure);
        }
      }
      throw e;
    } finally {
      if (success && backup != null) {
        deleteRecursively(backup);
      }
    }
  }

  private static void deleteRecursively(Path path) throws IOException {
    if (!Files.exists(path)) {
      return;
    }
    if (Files.isDirectory(path)) {
      try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
        for (Path child : stream) {
          deleteRecursively(child);
        }
      }
    }
    Files.deleteIfExists(path);
  }

  private static void moveAtomic(Path source, Path target) throws IOException {
    Files.createDirectories(target.getParent());
    Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }

  private static Path resolveInside(Path dir, String fileName) {
    final Path out = dir.resolve(fileName).normalize();
    if (!out.startsWith(dir.normalize())) {
      throw new IllegalArgumentException("invalid native output path: " + fileName);
    }
    return out;
  }

  private static boolean verifyFile(Path file, String expectedSha256) throws IOException {
    return Files.isRegularFile(file) && sha256Hex(file).equalsIgnoreCase(expectedSha256);
  }

  private static String sha256Hex(Path file) throws IOException {
    try {
      final MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (InputStream input = Files.newInputStream(file)) {
        final byte[] buffer = new byte[16 * 1024];
        int read;
        while ((read = input.read(buffer)) > 0) {
          digest.update(buffer, 0, read);
        }
      }
      final byte[] bytes = digest.digest();
      final StringBuilder out = new StringBuilder(bytes.length * 2);
      for (byte b : bytes) {
        out.append(String.format("%02x", b & 0xff));
      }
      return out.toString();
    } catch (Exception e) {
      if (e instanceof IOException io) {
        throw io;
      }
      throw new IOException("failed to hash " + file, e);
    }
  }

  /**
   * Resolve the pinned native bundle for this JVM's OS/arch, or null if Skija has no build for it.
   */
  private static Platform currentPlatform() {
    final String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    final String arch = shortArch(normalizeArch(System.getProperty("os.arch", "")));
    final String id;
    if (os.contains("win")) {
      id = "windows-" + arch;
    } else if (os.contains("linux")) {
      id = "linux-" + arch;
    } else if (os.contains("mac") || os.contains("darwin")) {
      id = "macos-" + arch;
    } else {
      return null;
    }
    for (Platform platform : PLATFORMS) {
      if (platform.id().equals(id)) {
        return platform;
      }
    }
    return null;
  }

  /** Maven artifact / staging id arch names: normalized x86_64 -> x64, arm64 stays arm64. */
  private static String shortArch(String normalizedArch) {
    return switch (normalizedArch) {
      case "x86_64" -> "x64";
      case "arm64" -> "arm64";
      default -> normalizedArch;
    };
  }

  private static String normalizeArch(String arch) {
    final String a = arch.toLowerCase(Locale.ROOT);
    return switch (a) {
      case "amd64", "x86_64" -> "x86_64";
      case "aarch64", "arm64" -> "arm64";
      default -> a;
    };
  }

  record NativeFile(String jarEntry, String fileName, String sha256) {}
}
