package ryzn.swift.cache;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongFunction;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import ryzn.macro.client.RyznPaths;
import ryzn.swift.nativepath.NativeVoxelFlags;

/**
 * Per-world V5 voxel cache storing an int {@link #FORMAT_MAGIC}, int chunk count; per chunk int
 * record count, int chunk x, int chunk z; per record one code short and one int packing {@code y <<
 * 8 | localZ << 4 | localX}. Unlisted cells are air. Only the shell is written: every non-air block
 * except full solids enclosed on all six sides by full solids, which nothing can reach. One file
 * per world/lobby key in the pathfinder-v5 cache, written atomically (temp file + rename). A
 * corrupt file is ignored (load returns null), never fatal.
 */
public final class WorldSerializer {

  private static final String EXTENSION = ".dat";

  /** Negative, so it can never be mistaken for the legacy layout's leading chunk count. */
  private static final int FORMAT_MAGIC = 0xE7E00004;

  private static final int DEFAULT_MIN_Y = -64;
  private static final int DEFAULT_MAX_Y = 320;

  private static final Path CACHE_DIR = RyznPaths.cache("pathfinder-v5");

  /** Temp files older than this are abandoned save leftovers (a healthy save renames within ms). */
  private static final long STALE_TMP_AGE_MS = 30_000L;

  private static final org.slf4j.Logger logger = com.mojang.logging.LogUtils.getLogger();

  static {
    cleanStaleTempFiles(); // init: drop leftover *.tmp from a previous run that was killed mid-save
  }

  private WorldSerializer() {}

  /**
   * Delete abandoned {@code *.tmp} files left in the cache dir by a save that was killed before its
   * atomic rename. Only removes temp files older than {@link #STALE_TMP_AGE_MS}, so a concurrent
   * in-flight save (which renames within milliseconds) is never touched. Exception-isolated - never
   * fatal.
   */
  private static void cleanStaleTempFiles() {
    try {
      if (!Files.isDirectory(CACHE_DIR)) {
        return;
      }
      final long cutoff = System.currentTimeMillis() - STALE_TMP_AGE_MS;
      try (java.nio.file.DirectoryStream<Path> stream =
          Files.newDirectoryStream(CACHE_DIR, "*.tmp")) {
        for (Path p : stream) {
          try {
            if (Files.getLastModifiedTime(p).toMillis() < cutoff) {
              Files.deleteIfExists(p);
            }
          } catch (Exception ignore) {
            // a save may rename/remove its own temp concurrently; ignore and move on
          }
        }
      }
    } catch (Exception e) {
      logger.warn("[ryzn-cache] temp-file cleanup failed", e);
    }
  }

  /**
   * The encoded shell of every chunk in one key's file, kept in memory between saves so a save
   * re-encodes only the chunks that changed and never re-reads the file. Chunks dropped from the
   * live cache keep their record here, so the file still holds everything explored before. Only the
   * cache IO thread reads or writes it.
   */
  @RequiredArgsConstructor
  @Accessors(fluent = true)
  public static final class DiskCache {
    @Getter private final String name;
    private final Map<Long, Encoded> records = new HashMap<>();
    private final Set<Long> pendingDirty = new HashSet<>();

    // Read by the client tick to schedule a retry after a failed write.
    private volatile boolean writeFailed;

    boolean writeFailed() {
      return writeFailed;
    }

    /** True once {@link #records} reflects the file on disk. */
    private boolean primed;
  }

  private record Encoded(int count, byte[] data) {}

  /**
   * Re-encode {@code dirty} live chunks into {@code disk} and atomically write the whole key to
   * disk. Every chunk the file already held stays unless a live copy replaces it. Exception-
   * isolated: failure is logged, never thrown.
   */
  public static void save(DiskCache disk, Map<Long, CachedChunk> live, Collection<Long> dirty) {
    disk.pendingDirty.addAll(dirty);
    try {
      Files.createDirectories(CACHE_DIR);
      cleanStaleTempFiles();
      prime(disk);
      for (Long key : disk.pendingDirty) {
        encodeInto(disk, live::get, key, live.get(key));
      }
      write(disk);
      disk.pendingDirty.clear();
      disk.writeFailed = false;
    } catch (Exception e) {
      disk.writeFailed = true;
      logger.warn("[ryzn-cache] failed to save cache '{}'", disk.name, e);
    }
  }

  /** Keep chunks leaving the live cache in {@code disk}, so the next save still writes them. */
  public static void retain(
      DiskCache disk, Map<Long, CachedChunk> evicted, Map<Long, CachedChunk> live) {
    try {
      prime(disk);
      final LongFunction<CachedChunk> lookup =
          key -> {
            final CachedChunk chunk = live.get(key);
            return chunk != null ? chunk : evicted.get(key);
          };
      for (Map.Entry<Long, CachedChunk> entry : evicted.entrySet()) {
        encodeInto(disk, lookup, entry.getKey(), entry.getValue());
      }
    } catch (Exception e) {
      logger.warn("[ryzn-cache] failed to retain evicted chunks for '{}'", disk.name, e);
    }
  }

  private static void prime(DiskCache disk) {
    if (!disk.primed) {
      read(disk, null, DEFAULT_MIN_Y, DEFAULT_MAX_Y);
      disk.primed = true;
    }
  }

  private static void encodeInto(
      DiskCache disk, LongFunction<CachedChunk> lookup, long key, CachedChunk chunk)
      throws IOException {
    if (chunk == null || !chunk.ready) {
      return;
    }
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream(1 << 12);
    final DataOutputStream out = new DataOutputStream(bytes);
    final int count = writeShell(lookup, (int) (key >> 32), (int) key, chunk, out);
    out.flush();
    disk.records.put(key, new Encoded(count, bytes.toByteArray()));
  }

  private static void write(DiskCache disk) throws IOException {
    final Path target = CACHE_DIR.resolve(sanitize(disk.name) + EXTENSION);
    Path tmp = Files.createTempFile(CACHE_DIR, sanitize(disk.name) + ".", ".tmp");
    try {
      try (DataOutputStream dataOut =
          new DataOutputStream(
              new BufferedOutputStream(
                  Files.newOutputStream(
                      tmp, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE),
                  1 << 16))) {
        dataOut.writeInt(FORMAT_MAGIC);
        dataOut.writeInt(disk.records.size());
        for (Map.Entry<Long, Encoded> entry : disk.records.entrySet()) {
          final long key = entry.getKey();
          dataOut.writeInt(entry.getValue().count());
          dataOut.writeInt((int) (key >> 32));
          dataOut.writeInt((int) key);
          dataOut.write(entry.getValue().data());
        }
      }
      // Swap the complete temp file over the target so the live cache file is never half-written.
      try {
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
      } catch (Exception atomicUnsupported) {
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
      }
      tmp = null;
    } finally {
      if (tmp != null) {
        Files.deleteIfExists(tmp);
      }
    }
  }

  private static int writeShell(
      LongFunction<CachedChunk> all,
      int chunkX,
      int chunkZ,
      CachedChunk chunk,
      DataOutputStream out)
      throws IOException {
    int count = 0;
    for (int section = 0; section < chunk.sectionCount(); section++) {
      if (!chunk.hasSection(section)) {
        continue;
      }
      final int baseY = chunk.minY + (section << 4);
      for (int y = baseY; y < baseY + 16 && y < chunk.maxY; y++) {
        for (int localZ = 0; localZ < 16; localZ++) {
          for (int localX = 0; localX < 16; localX++) {
            final short code = chunk.getCode(localX, y, localZ);
            if (code == NativeVoxelFlags.AIR
                || (code == (NativeVoxelFlags.SOLID | NativeVoxelFlags.BLOCKING_WALL)
                    && buried(all, chunkX, chunkZ, chunk, localX, y, localZ))) {
              continue;
            }
            out.writeShort(code);
            out.writeInt((y << 8) | (localZ << 4) | localX);
            count++;
          }
        }
      }
    }
    return count;
  }

  /** A full solid whose six neighbours are all full solids (an uncached neighbour is not). */
  private static boolean buried(
      LongFunction<CachedChunk> all,
      int chunkX,
      int chunkZ,
      CachedChunk chunk,
      int x,
      int y,
      int z) {
    if (y <= chunk.minY || y >= chunk.maxY - 1) {
      return false;
    }
    return solidCell(chunk, x, y - 1, z)
        && solidCell(chunk, x, y + 1, z)
        && solidAt(all, chunkX, chunkZ, chunk, x - 1, y, z)
        && solidAt(all, chunkX, chunkZ, chunk, x + 1, y, z)
        && solidAt(all, chunkX, chunkZ, chunk, x, y, z - 1)
        && solidAt(all, chunkX, chunkZ, chunk, x, y, z + 1);
  }

  private static boolean solidAt(
      LongFunction<CachedChunk> all,
      int chunkX,
      int chunkZ,
      CachedChunk chunk,
      int x,
      int y,
      int z) {
    if (x >= 0 && x < 16 && z >= 0 && z < 16) {
      return solidCell(chunk, x, y, z);
    }
    final CachedChunk neighbour = all.apply(chunkKey(chunkX + (x >> 4), chunkZ + (z >> 4)));
    return neighbour != null && solidCell(neighbour, x & 15, y, z & 15);
  }

  private static boolean solidCell(CachedChunk chunk, int x, int y, int z) {
    return chunk.getCode(x, y, z) == (NativeVoxelFlags.SOLID | NativeVoxelFlags.BLOCKING_WALL);
  }

  /**
   * Load the full cache for {@code disk}'s key, or {@code null} if there is no file or it is
   * corrupt, and remember its records for later saves. The file carries no height range, so chunks
   * take the current level's.
   */
  public static ConcurrentHashMap<Long, CachedChunk> load(DiskCache disk) {
    cleanStaleTempFiles();
    final ClientLevel level = Minecraft.getInstance().level;
    final int minY = level != null ? level.getMinY() : DEFAULT_MIN_Y;
    final int maxY = level != null ? level.getMaxY() + 1 : DEFAULT_MAX_Y;
    final ConcurrentHashMap<Long, CachedChunk> map = new ConcurrentHashMap<>();
    final boolean found = read(disk, map, minY, maxY);
    disk.primed = true;
    return found ? map : null;
  }

  /**
   * Parse the key's file into {@code disk}'s records (always in the current record layout) and,
   * when {@code chunks} is given, into live chunks. False when there is no readable file.
   */
  private static boolean read(DiskCache disk, Map<Long, CachedChunk> chunks, int minY, int maxY) {
    final Path file = CACHE_DIR.resolve(sanitize(disk.name) + EXTENSION);
    if (!Files.exists(file)) {
      return false;
    }
    final Map<Long, Encoded> records = new HashMap<>();
    try (DataInputStream in =
        new DataInputStream(new BufferedInputStream(Files.newInputStream(file), 1 << 16))) {
      final int first = in.readInt();
      if (first != FORMAT_MAGIC) {
        return false;
      }
      final int chunkCount = in.readInt();
      final ByteArrayOutputStream bytes = new ByteArrayOutputStream(1 << 12);
      final DataOutputStream recordOut = new DataOutputStream(bytes);
      for (int k = 0; k < chunkCount; k++) {
        final int count = in.readInt();
        final int chunkX = in.readInt();
        final int chunkZ = in.readInt();
        final CachedChunk chunk = chunks == null ? null : new CachedChunk(minY, maxY);
        bytes.reset();
        for (int r = 0; r < count; r++) {
          final short code = in.readShort();
          final int packed = in.readInt();
          recordOut.writeShort(code);
          recordOut.writeInt(packed);
          if (chunk != null) {
            chunk.setCode(packed & 15, packed >> 8, (packed >> 4) & 15, code);
          }
        }
        recordOut.flush();
        final long key = chunkKey(chunkX, chunkZ);
        records.put(key, new Encoded(count, bytes.toByteArray()));
        if (chunk != null) {
          chunk.ready = true;
          chunks.put(key, chunk);
        }
      }
    } catch (Exception e) {
      // Truncated file from a kill mid-save, or any decode error: ignore it, start empty.
      logger.warn("[ryzn-cache] cache '{}' is unreadable, ignoring it", disk.name, e);
      if (chunks != null) {
        chunks.clear();
      }
      return false;
    }
    records.forEach(disk.records::putIfAbsent);
    return true;
  }

  private static long chunkKey(int x, int z) {
    return ((long) x << 32) | ((long) z & 0xFFFFFFFFL);
  }

  public static boolean hasAreaSiblings(String locationKey) {
    if (locationKey == null) {
      return false;
    }
    try {
      if (!Files.isDirectory(CACHE_DIR)) {
        return false;
      }
      try (java.nio.file.DirectoryStream<Path> stream =
          Files.newDirectoryStream(CACHE_DIR, sanitize(locationKey) + "_*" + EXTENSION)) {
        return stream.iterator().hasNext();
      }
    } catch (Exception e) {
      logger.warn("[ryzn-cache] area-sibling scan failed for '{}'", locationKey, e);
      return false;
    }
  }

  private static String sanitize(String key) {
    final StringBuilder sb = new StringBuilder(key.length());
    for (int i = 0; i < key.length(); i++) {
      final char ch = key.charAt(i);
      sb.append((Character.isLetterOrDigit(ch) || ch == '-' || ch == '_' || ch == '.') ? ch : '_');
    }
    final String s = sb.toString();
    return s.length() > 96 ? s.substring(0, 96) : s;
  }
}
