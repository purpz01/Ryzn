package ryzn.macro.client;

import java.io.IOException;
import java.io.InputStream;

public final class RyznResources {
  private RyznResources() {}

  public static InputStream open(String resource) throws IOException {
    String key = key(resource);
    InputStream stream = RyznResources.class.getResourceAsStream("/" + key);
    if (stream == null) {
      throw new IOException("Missing Ryzn resource: " + key);
    }
    return stream;
  }

  public static boolean available(String resource) {
    return RyznResources.class.getResource("/" + key(resource)) != null;
  }

  private static String key(String resource) {
    String key = resource.startsWith("/") ? resource.substring(1) : resource;
    if (key.isBlank() || key.contains("..") || key.contains("\\") || key.contains(":")) {
      throw new IllegalArgumentException("Invalid Ryzn resource path");
    }
    return key;
  }
}
