package ryzn.swift.cache;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import ryzn.swift.nativepath.NativeVoxelFlags;

/**
 * Per-chunk block-code storage ({@link NativeVoxelFlags}) in 16³ sections. Indexed locally (x,y,z
 * within the chunk); y is absolute (clamped to minY..&lt;maxY). A missing section reads as air.
 */
public final class CachedChunk {

  public static final short AIR = NativeVoxelFlags.AIR;

  /**
   * One shared, read-only array per code for sections that are that code throughout (solid
   * underground, water). {@link #setCode} copies before writing into one.
   */
  private static final ConcurrentHashMap<Short, short[]> UNIFORM = new ConcurrentHashMap<>();

  public static short[] uniformSection(short code) {
    return UNIFORM.computeIfAbsent(
        code,
        c -> {
          final short[] section = new short[4096];
          Arrays.fill(section, c);
          return section;
        });
  }

  private static boolean isUniform(short[] section) {
    return UNIFORM.get(section[0]) == section;
  }

  public final int minY;
  public final int maxY;
  private final short[][] sections;
  public volatile boolean ready = false;

  public CachedChunk(int minY, int maxY) {
    this.minY = minY;
    this.maxY = maxY;
    this.sections = new short[(maxY - minY + 15) >> 4][];
  }

  public short getCode(int localX, int y, int localZ) {
    if (y < minY || y >= maxY) {
      return AIR;
    }
    final int sectionIndex = (y - minY) >> 4;
    if (sectionIndex < 0 || sectionIndex >= sections.length) {
      return AIR;
    }
    final short[] section = sections[sectionIndex];
    if (section == null) {
      return AIR;
    }
    return section[((y & 15) << 8) | ((localZ & 15) << 4) | (localX & 15)];
  }

  public void setCode(int localX, int y, int localZ, short code) {
    if (y < minY || y >= maxY) {
      return;
    }
    final int sectionIndex = (y - minY) >> 4;
    if (sectionIndex < 0 || sectionIndex >= sections.length) {
      return;
    }
    short[] section = sections[sectionIndex];
    if (section == null) {
      section = new short[4096];
      Arrays.fill(section, AIR);
      sections[sectionIndex] = section;
    } else if (isUniform(section)) {
      section = section.clone();
      sections[sectionIndex] = section;
    }
    section[((y & 15) << 8) | ((localZ & 15) << 4) | (localX & 15)] = code;
  }

  public boolean hasSection(int index) {
    return index >= 0 && index < sections.length && sections[index] != null;
  }

  public void copySectionCodes(int index, short[] dest, int destOffset) {
    if (index < 0 || index >= sections.length) {
      return;
    }
    final short[] section = sections[index];
    if (section != null) {
      System.arraycopy(section, 0, dest, destOffset, section.length);
    }
  }

  public void setSection(int sectionIndex, short[] data) {
    if (sectionIndex >= 0 && sectionIndex < sections.length) {
      sections[sectionIndex] = data;
    }
  }

  /** Copies section {@code index} from {@code other}; a shared uniform section stays shared. */
  public void copySectionFrom(CachedChunk other, int index) {
    if (index < 0 || index >= sections.length || index >= other.sections.length) {
      return;
    }
    final short[] section = other.sections[index];
    sections[index] = section == null || isUniform(section) ? section : section.clone();
  }

  public int sectionCount() {
    return sections.length;
  }
}
