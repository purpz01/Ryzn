package ryzn.swift.nativepath;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Loads V5PathJNI.{dll,so,dylib} from /assets/ryzn/natives/{os}/{arch}/ on first use, then exposes
 * the native pathfinder ABI: the walking pathfinder.
 */
public final class NativePathfinderJNI {

  private static final String LIB_BASE = "V5PathJNI";

  private static volatile boolean initialized = false;
  private static volatile boolean available = false;
  private static volatile String loadError = null;
  private static final Object lock = new Object();

  private NativePathfinderJNI() {}

  public static boolean initialize() {
    if (initialized) {
      return available;
    }
    synchronized (lock) {
      if (initialized) {
        return available;
      }

      final List<String> candidates = nativeResourceCandidates();
      final List<String> errors = new ArrayList<>();

      for (String resourcePath : candidates) {
        try {
          if (loadNativeFromResource(resourcePath)) {
            available = true;
            loadError = null;
            initialized = true;
            return true;
          }
          errors.add(resourcePath + ": not found in jar");
        } catch (Throwable t) {
          final String msg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
          errors.add(resourcePath + ": " + msg);
        }
      }

      available = false;
      final StringBuilder sb = new StringBuilder();
      sb.append("Failed to load native pathfinder")
          .append(" (OS: ")
          .append(System.getProperty("os.name"))
          .append(", arch: ")
          .append(System.getProperty("os.arch"))
          .append("). Tried:\n");
      for (String e : errors) {
        sb.append("  - ").append(e).append('\n');
      }
      loadError = sb.toString().stripTrailing();
      initialized = true;
      return false;
    }
  }

  private static boolean loadNativeFromResource(String resourcePath) throws Exception {
    final String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
    final String ext = extensionForOs(os);

    final InputStream input = ryzn.macro.client.RyznResources.open(resourcePath);
    if (input == null) {
      return false;
    }

    final File tempFile = Files.createTempFile(LIB_BASE, ext).toFile();
    tempFile.deleteOnExit();
    try (InputStream in = input;
        FileOutputStream out = new FileOutputStream(tempFile)) {
      final byte[] buf = new byte[16 * 1024];
      int n;
      while ((n = in.read(buf)) > 0) {
        out.write(buf, 0, n);
      }
    }

    System.load(tempFile.getAbsolutePath());

    if (!com.chattriggers.ctjs.api.world.pathfinding.NativePathfinderJNI.initNative()) {
      throw new IllegalStateException("initNative() returned false");
    }
    return true;
  }

  public static List<String> nativeResourceCandidates() {
    final String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
    final String arch = normalizeArch(System.getProperty("os.arch"));
    final String ext = extensionForOs(os);
    final String lib = LIB_BASE + ext;

    final List<String> platformPaths = new ArrayList<>();
    if (os.contains("win")) {
      platformPaths.add("windows/" + arch);
    } else if (os.contains("linux")) {
      platformPaths.add("linux/" + arch);
    } else if (os.contains("mac")) {
      if ("arm64".equals(arch)) {
        platformPaths.add("macos/arm64");
        platformPaths.add("macos/universal");
      } else if ("x86_64".equals(arch)) {
        platformPaths.add("macos/x86_64");
        platformPaths.add("macos/universal");
      } else {
        platformPaths.add("macos/" + arch);
      }
    }

    final List<String> out = new ArrayList<>(platformPaths.size());
    for (String p : platformPaths) {
      out.add("/assets/ryzn/natives/" + p + "/" + lib);
    }
    return out;
  }

  private static String extensionForOs(String os) {
    if (os.contains("win")) {
      return ".dll";
    }
    if (os.contains("mac")) {
      return ".dylib";
    }
    if (os.contains("linux")) {
      return ".so";
    }
    throw new IllegalStateException("Unsupported OS for native pathfinder: " + os);
  }

  private static String normalizeArch(String arch) {
    final String a = arch.toLowerCase(Locale.ROOT);
    switch (a) {
      case "amd64":
      case "x86_64":
        return "x86_64";
      case "aarch64":
      case "arm64":
        return "arm64";
      default:
        return a;
    }
  }

  public static boolean isAvailable() {
    return initialize();
  }

  public static String getLoadError() {
    return loadError;
  }
}
