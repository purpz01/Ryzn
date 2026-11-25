package ryzn.swift.cache;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import ryzn.macro.client.core.RyznBootstrap;
import ryzn.macro.client.core.events.api.ClientDisconnectEvent;
import ryzn.macro.client.core.events.api.EndClientTickEvent;
import ryzn.macro.client.debug.DebugLog;
import ryzn.swift.Swift;
import ryzn.swift.nativepath.NativePathfinderBridge;
import ryzn.swift.nativepath.NativeStateEncoder;

/**
 * Persistent, disk-first world memory for the pathfinder. One cache file per world/lobby key (see
 * {@link WorldSerializer}); when a key is identified, the in-memory and native caches are cleared
 * and that key's disk file (if any) is loaded immediately and pushed into the native pathfinder, so
 * planning can use cached geometry BEFORE the server re-streams it. Live geometry then keeps the
 * cache current:
 *
 * <ul>
 *   <li>Inbound S2C packets are observed read-only by {@code ClientConnectionMixin} and forwarded
 *       to {@link #onPacketReceive}: chunk packets enqueue a chunk key, block-update packets update
 *       the cell.
 *   <li>{@code LevelChunkMixin} forwards the client's own block mutations to {@link #onBlockUpdate}
 *       (the same update path) so there is a single cache truth: disk -> in-memory chunks ->
 *       native.
 *   <li>Pending chunk keys are converted from the live client world each {@code END_CLIENT_TICK}
 *       (up to {@link Swift#CHUNKS_PER_TICK}), classified via {@link NativeStateEncoder}, stored,
 *       and upserted into native. Dirty cache is saved to disk periodically and on world/lobby
 *       switch + disconnect.
 * </ul>
 *
 * <p>Nothing is ever sent, blocked, delayed, or altered on the network; this only reads
 * inbound/client state.
 */
public final class CachedWorld {

  private static final char MOJIBAKE_C2 = '\u00C2'; // UTF-8 byte 0xC2 read as Latin-1
  private static final String RUNTIME_WORLD_KEY = "runtime_memory";
  private static final int FLUSH_INTERVAL_TICKS = 3600; // ~3 min between periodic disk saves
  private static final int SWITCH_DEBOUNCE_TICKS =
      20; // ~1s: a key must hold before we re-key on disk
  private static final int WORLD_SESSION_SETTLE_TICKS =
      20; // same-key lobby swaps need ~1s to restream chunks
  private static final int LIVE_REINGEST_CHUNK_RADIUS = 16; // covers any Hypixel view distance

  private static volatile ConcurrentHashMap<Long, CachedChunk> chunks =
      new ConcurrentHashMap<>(512);
  private static final ConcurrentLinkedQueue<Long> pendingChunks = new ConcurrentLinkedQueue<>();
  // Low-priority reconcile queue: client-loaded chunks re-classified live after a swap/load
  // settles. The
  // swap guards drop the warp burst on purpose, so without this the cache serves persisted geometry
  // for the
  // whole visible area - wrong wherever the server's map changed since it was saved.
  private static final ConcurrentLinkedQueue<Long> reingestChunks = new ConcurrentLinkedQueue<>();
  private static volatile boolean pendingLiveReingest = false;
  private static final ConcurrentHashMap<Long, Integer> pendingNativeUpdates =
      new ConcurrentHashMap<>(256);

  private static volatile String worldKey = RUNTIME_WORLD_KEY;
  private static volatile String nativeWorldToken = "";
  private static volatile boolean pendingNativeResync = true;

  // Loading state: set while a disk load is in flight. Non-blocking - processPendingChunks just
  // skips a tick
  // so the loaded chunks land in the map (and then native) before live conversion/resync runs.
  private static volatile boolean isCacheLoading = false;
  // Set whenever live data changes the cache beyond what is already on disk; cleared after a
  // successful save.
  private static volatile boolean cacheDirty = false;
  // Chunks changed since they were last encoded for disk; a save re-encodes only these.
  private static final Set<Long> dirtyChunks = ConcurrentHashMap.newKeySet();
  // The active key's file contents, owned by the Swift.io thread once handed over.
  private static volatile WorldSerializer.DiskCache disk = null;
  private static short[] nativeScratch = new short[0];

  // Single-entry hot-chunk hint; deliberately non-volatile (best-effort).
  private static long cacheKey = Long.MIN_VALUE;
  private static CachedChunk cacheChunk = null;

  // Disk lifecycle: the currently-active world/lobby key (null when detached), a small debounce so
  // a
  // one-tick TAB/scoreboard blip can't thrash save+load, and the periodic-save countdown.
  private static String activeKey = null;
  // The active key split into its physical location (server ip + dimension, no area) and the
  // SkyBlock
  // area. activeLocationKey identifies the physical location for sticky-area handling. The
  // bare-location
  // file is deliberately not a cross-area fallback because it can mix unrelated SkyBlock geometry.
  private static String activeLocationKey = null;
  private static String activeArea = null;
  private static String pendingKey = null;
  private static int pendingKeyTicks = 0;
  private static int flushTimer = 0;
  private static volatile int worldSessionSettleTicks = 0;
  // Blocks live ingestion until TAB names the new world's area; the settle timer alone cannot tell
  // which
  // key the streaming chunks belong to (see AreaConfirmGate).
  private static final AreaConfirmGate areaConfirm = new AreaConfirmGate();
  // True once a login/respawn has landed since the chunk map was last (re)loaded for a key. Chunks
  // held
  // across such a boundary came from the world we were in BEFORE it, so a later refine must not
  // promote
  // them into the area file it finally learns the name of.
  private static volatile boolean boundarySinceLoad = false;
  private static volatile boolean worldSessionLiveAnchorReady = true;

  private static final org.slf4j.Logger logger = com.mojang.logging.LogUtils.getLogger();

  private CachedWorld() {}

  // --- registration --------------------------------------------------------

  /** Call once during client init. Packet feeding is driven by {@code ClientConnectionMixin}. */
  public static void registerEvents() {
    RyznBootstrap.eventBus()
        .subscribe(
            EndClientTickEvent.class,
            event -> {
              maybeRekey(event.client());
              if (event.client().level != null) {
                processPendingChunks();
              }
            });
    RyznBootstrap.eventBus().subscribe(ClientDisconnectEvent.class, event -> onDisconnect());
  }

  // --- coordinate keys -----------------------------------------------------

  private static long chunkKey(int x, int z) {
    return ((long) x << 32) | ((long) z & 0xFFFFFFFFL);
  }

  private static long blockKey(int x, int y, int z) {
    final long packedX = ((long) x + 33_554_432L) & 0x3FFFFFFL;
    final long packedY = ((long) y + 2_048L) & 0xFFFL;
    final long packedZ = ((long) z + 33_554_432L) & 0x3FFFFFFL;
    return (packedX << 38) | (packedY << 26) | packedZ;
  }

  private static int unpackBlockX(long key) {
    return (int) ((key >>> 38) & 0x3FFFFFFL) - 33_554_432;
  }

  private static int unpackBlockY(long key) {
    return (int) ((key >>> 26) & 0xFFFL) - 2_048;
  }

  private static int unpackBlockZ(long key) {
    return (int) (key & 0x3FFFFFFL) - 33_554_432;
  }

  // --- lookups -------------------------------------------------------------

  /**
   * Stored block code for one block ({@code NativeVoxelFlags}), or null if the chunk isn't cached.
   */
  public static Short getBlockCode(int x, int y, int z) {
    final int chunkX = x >> 4;
    final int chunkZ = z >> 4;
    final long key = chunkKey(chunkX, chunkZ);

    final CachedChunk cached = cacheChunk;
    if (cacheKey == key && cached != null && cached.ready) {
      return cached.getCode(x & 15, y, z & 15);
    }

    final CachedChunk chunk = chunks.get(key);
    if (chunk == null || !chunk.ready) {
      return null;
    }

    cacheKey = key;
    cacheChunk = chunk;
    return chunk.getCode(x & 15, y, z & 15);
  }

  public static CachedChunk getChunk(int x, int z) {
    final CachedChunk chunk = chunks.get(chunkKey(x, z));
    return (chunk != null && chunk.ready) ? chunk : null;
  }

  // --- packet ingestion (network thread, read-only) ------------------------

  /** Forwarded from {@code ClientConnectionMixin} for every inbound S2C packet. Unwraps bundles. */
  public static void onPacketReceive(Packet<?> packet) {
    if (packet instanceof ClientboundBundlePacket bundle) {
      for (Packet<?> sub : bundle.subPackets()) {
        onPacketReceive(sub);
      }
    } else if (packet instanceof ClientboundLoginPacket
        || packet instanceof ClientboundRespawnPacket) {
      onWorldSessionBoundary(packet.getClass().getSimpleName());
    } else if (packet instanceof ClientboundLevelChunkWithLightPacket chunkData) {
      pendingChunks.add(chunkKey(chunkData.getX(), chunkData.getZ()));
    } else if (packet instanceof ClientboundBlockUpdatePacket blockUpdate) {
      final BlockPos pos = blockUpdate.getPos();
      applyBlockUpdate(pos.getX(), pos.getY(), pos.getZ(), blockUpdate.getBlockState());
    } else if (packet instanceof ClientboundSectionBlocksUpdatePacket sectionUpdate) {
      sectionUpdate.runUpdates(
          (pos, state) -> applyBlockUpdate(pos.getX(), pos.getY(), pos.getZ(), state));
    }
  }

  /**
   * Called by {@code LevelChunkMixin} when the client applies a block change (same update path as
   * packets).
   */
  public static void onBlockUpdate(int x, int y, int z, BlockState state) {
    applyBlockUpdate(x, y, z, state);
  }

  private static void applyBlockUpdate(int x, int y, int z, BlockState state) {
    final long key = chunkKey(x >> 4, z >> 4);
    final CachedChunk chunk = chunks.get(key);
    if (chunk == null || !chunk.ready) {
      return;
    } // chunk not converted yet; conversion will read fresh state
    final short code = NativeStateEncoder.codeForState(state);
    chunk.setCode(x & 15, y, z & 15, code);
    queueNativeUpdate(x, y, z, code);
    if (cacheKey == key) {
      cacheChunk = chunk;
    }
    markDirty(key);
    // An edge cell also decides whether the neighbour's border blocks count as buried on disk.
    final int localX = x & 15;
    final int localZ = z & 15;
    if (localX == 0 || localX == 15) {
      markDirty(chunkKey((x >> 4) + (localX == 0 ? -1 : 1), z >> 4));
    }
    if (localZ == 0 || localZ == 15) {
      markDirty(chunkKey(x >> 4, (z >> 4) + (localZ == 0 ? -1 : 1)));
    }
  }

  private static void markDirty(long key) {
    dirtyChunks.add(key);
    cacheDirty = true;
  }

  private static Set<Long> drainDirty() {
    final Set<Long> drained = new HashSet<>();
    for (Iterator<Long> it = dirtyChunks.iterator(); it.hasNext(); ) {
      drained.add(it.next());
      it.remove();
    }
    return drained;
  }

  // --- chunk conversion (client tick thread) -------------------------------

  public static void processPendingChunks() {
    if (isCacheLoading) {
      return;
    } // disk load in flight: let it finish so disk chunks reach native first

    final Minecraft mc = Minecraft.getInstance();
    final ClientLevel level = mc.level;
    if (level == null) {
      return;
    }

    final int minY = level.getMinY();
    final int maxY = level.getMaxY() + 1;
    ensureNativeWorld(minY, maxY);

    // Do NOT ingest live chunks while a world change is in flight. After a warp the live world
    // becomes the
    // NEW area immediately (login/respawn packet -> worldSessionSettleTicks), but activeKey only
    // flips
    // later, once the TAB area updates and maybeRekey re-keys (pendingKey). Ingesting anywhere in
    // that gap
    // writes the new area's geometry into the OLD key's cache. Hub and Private Island are both
    // overworld
    // with overlapping coordinates, so that contaminates the old file with phantom geometry (and
    // the
    // merge-save persists it) - exactly what made a path fail after an island swap. Both windows
    // must be
    // covered: the settle (from the world-change packet) and the pending switch (from key
    // detection). A
    // switch clears these queued chunks on its load(); a refine (same location) keeps and ingests
    // them.
    if (pendingKey == null && worldSessionSettleTicks <= 0 && !areaConfirm.isHolding()) {
      if (pendingLiveReingest) {
        pendingLiveReingest = false;
        queueLoadedClientChunksForReingest(level);
      }
      List<Long> requeue = null;
      int budget = Swift.CHUNKS_PER_TICK;
      while (budget > 0) {
        final Long nextBoxed = pendingChunks.poll();
        if (nextBoxed == null) {
          break;
        }
        budget--;
        final long next = nextBoxed;
        if (!processLoadedChunk(level, minY, maxY, (int) (next >> 32), (int) next)) {
          if (requeue == null) {
            requeue = new ArrayList<>(4);
          }
          requeue.add(next); // chunk not ready in the live world yet; retry next tick
        }
      }
      if (requeue != null) {
        pendingChunks.addAll(requeue);
      }
      // Leftover budget reconciles reingest chunks. No requeue on failure: a miss means the client
      // no
      // longer has the chunk, and a re-stream re-enqueues it through the normal packet path anyway.
      while (budget > 0) {
        final Long nextBoxed = reingestChunks.poll();
        if (nextBoxed == null) {
          break;
        }
        budget--;
        final long next = nextBoxed;
        processLoadedChunk(level, minY, maxY, (int) (next >> 32), (int) next);
      }
    }

    if (chunks.size() > Swift.MAXIMUM_CACHED_CHUNKS) {
      evictFarChunks(mc.player);
    }

    if (pendingNativeResync) {
      final int synced = syncAllCachedChunksToNative();
      pendingNativeResync = false;
      logger.info("[ryzn-cache] native resync done ({} chunks) key={}", synced, worldKey);
    }
    flushPendingNativeUpdates();
    tickWorldSessionSettle();

    if (++flushTimer >= FLUSH_INTERVAL_TICKS) {
      flushTimer = 0;
      maybePeriodicSave();
    }
  }

  private static boolean processLoadedChunk(
      ClientLevel level, int minY, int maxY, int chunkX, int chunkZ) {
    final LevelChunk worldChunk =
        level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
    if (worldChunk == null) {
      return false;
    }

    final long key = chunkKey(chunkX, chunkZ);
    final CachedChunk previous = chunks.get(key);
    final CachedChunk cached = new CachedChunk(minY, maxY);
    final LevelChunkSection[] sections = worldChunk.getSections();

    // Which sections the client actually told us about this time. A null section means NO
    // INFORMATION (not loaded yet); hasOnlyAir() means we were told, and the answer is "empty".
    // The old code skipped both identically, which is what lost real geometry - see below.
    final boolean[] observed = new boolean[sections.length];

    for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
      final LevelChunkSection section = sections[sectionIndex];
      if (section == null) {
        continue;
      } // no data from the client - leave it for carry-forward
      observed[sectionIndex] = true;
      if (section.hasOnlyAir()) {
        continue;
      } // genuinely empty: absent already reads as air

      cached.setSection(sectionIndex, convertSection(section));
    }

    // Carry forward every section this pass had NO information about. Without this, re-ingesting a
    // chunk while the client holds only part of it REPLACES the good copy with a hollow one: the
    // missing sections then read as air, so the planner sees open sky where the floor is and
    // refuses to route. Measured on a real Hub cache - two consecutive saves lost 151 sections and
    // gained 24, and a route that had worked stopped working until the player walked it manually.
    // Only unobserved indices are copied, so a section the client reports as genuinely empty still
    // becomes empty; this never invents geometry, it only declines to forget it.
    int carried = 0;
    if (previous != null && previous.minY == minY && previous.maxY == maxY) {
      final int limit = Math.min(cached.sectionCount(), observed.length);
      for (int i = 0; i < limit; i++) {
        if (observed[i] || cached.hasSection(i) || !previous.hasSection(i)) {
          continue;
        }
        cached.copySectionFrom(previous, i);
        carried++;
      }
    }

    // A pass that learned nothing and had nothing to carry would cache an all-air chunk, which the
    // planner reads as "open sky, no floor" - strictly worse than leaving it absent (absent reads
    // as
    // a solid wall, so the search stays out instead of walking into a hole). Report not-ready and
    // let
    // a later tick retry once the client has the data.
    if (carried == 0 && !anyObserved(observed) && previous == null) {
      return false;
    }

    cached.ready = true;
    chunks.put(key, cached);
    if (cacheKey == key) {
      cacheChunk = cached;
    }
    syncChunkToNative(chunkX, chunkZ, cached);
    markDirty(key);
    markDirty(chunkKey(chunkX - 1, chunkZ));
    markDirty(chunkKey(chunkX + 1, chunkZ));
    markDirty(chunkKey(chunkX, chunkZ - 1));
    markDirty(chunkKey(chunkX, chunkZ + 1));
    return true;
  }

  /**
   * One section's codes in a single pass. A section whose palette maps to one code (solid
   * underground, open water) shares {@link CachedChunk#uniformSection} instead of its own array.
   */
  private static short[] convertSection(LevelChunkSection section) {
    final PalettedContainer<BlockState> states = section.getStates();
    final int bits = states.bitsPerEntry();
    if (bits <= 8) {
      final int[] paletteCode = {Integer.MIN_VALUE};
      final boolean[] mixed = {false};
      states.forEachInPalette(
          state -> {
            final short code = NativeStateEncoder.codeForState(state);
            if (paletteCode[0] == Integer.MIN_VALUE) {
              paletteCode[0] = code;
            } else if (paletteCode[0] != code) {
              mixed[0] = true;
            }
          });
      if (!mixed[0] && paletteCode[0] != Integer.MIN_VALUE) {
        return CachedChunk.uniformSection((short) paletteCode[0]);
      }
    }

    final short[] sectionData = new short[4096];
    BlockState last = null;
    short lastCode = 0;
    for (int localY = 0; localY < 16; localY++) {
      final int offsetY = localY << 8;
      for (int localZ = 0; localZ < 16; localZ++) {
        final int offsetZ = localZ << 4;
        for (int localX = 0; localX < 16; localX++) {
          final BlockState state = states.get(localX, localY, localZ);
          // Runs of one state are the norm, so the registry lookup is skipped for repeats.
          if (state != last) {
            last = state;
            lastCode = NativeStateEncoder.codeForState(state);
          }
          sectionData[offsetY | offsetZ | localX] = lastCode;
        }
      }
    }
    return sectionData;
  }

  /**
   * Drop the chunks farthest from the player once the cap is passed. Changes not yet encoded are
   * handed to the disk cache first, so the file keeps everything; only memory forgets them.
   */
  private static void evictFarChunks(LocalPlayer player) {
    if (player == null) {
      return;
    }
    final ConcurrentHashMap<Long, CachedChunk> live = chunks;
    final int playerChunkX = player.getBlockX() >> 4;
    final int playerChunkZ = player.getBlockZ() >> 4;
    final Long[] keys = live.keySet().toArray(new Long[0]);
    if (keys.length <= Swift.EVICT_TO_CHUNKS) {
      return;
    }
    Arrays.sort(
        keys,
        Comparator.comparingLong((Long key) -> chunkDistanceSq(key, playerChunkX, playerChunkZ))
            .reversed());
    final Map<Long, CachedChunk> evicted = new HashMap<>();
    for (int i = 0; i < keys.length - Swift.EVICT_TO_CHUNKS; i++) {
      final CachedChunk removed = live.remove(keys[i]);
      if (removed != null && dirtyChunks.remove(keys[i])) {
        evicted.put(keys[i], removed);
      }
    }
    cacheKey = Long.MIN_VALUE;
    cacheChunk = null;
    final WorldSerializer.DiskCache target = disk;
    if (target != null && !evicted.isEmpty()) {
      Swift.io.submit(() -> WorldSerializer.retain(target, evicted, live));
    }
  }

  private static long chunkDistanceSq(long key, int chunkX, int chunkZ) {
    final long dx = (int) (key >> 32) - chunkX;
    final long dz = (int) key - chunkZ;
    return dx * dx + dz * dz;
  }

  private static boolean anyObserved(boolean[] observed) {
    for (boolean b : observed) {
      if (b) {
        return true;
      }
    }
    return false;
  }

  // --- path-search readiness gate ------------------------------------------

  /**
   * Advance the cache toward a state where a native search will see real geometry: settle the world
   * key, drain pending chunks, push the player's own chunk into native, and let any async disk load
   * + resync complete. Safe to call every tick while waiting (idempotent); pair with {@link
   * #isPathSearchReady()}. Call this instead of submitting a native search the instant a key just
   * changed - that race searched an empty/stale native world and returned a spurious no-path.
   */
  public static void prepareForPathSearch(Minecraft mc) {
    maybeRekey(mc);
    if (mc.level == null) {
      return;
    }
    final long t0 = System.nanoTime();
    processPendingChunks(); // convert queued chunks + flush a pending native resync (skips while
    // loading)
    forceSyncPlayerChunk(
        mc); // guarantee the start chunk exists in native even on a cold/empty cache
    DebugLog.detail(
        DebugLog.Category.NAV, "[cache] prep %.2f ms", (System.nanoTime() - t0) / 1.0e6);
  }

  /**
   * True when a native search may run: the world key has settled (no debounce in flight), no disk
   * load is running, and every cached chunk has been pushed into the native world. While any of
   * these is false a search would see a partial/empty native world, so the caller should keep
   * waiting.
   */
  public static boolean isPathSearchReady() {
    return pendingKey == null
        && !isCacheLoading
        && !pendingNativeResync
        && (worldSessionSettleTicks <= 0 || worldSessionLiveAnchorReady);
  }

  /**
   * Classify + upsert the chunk the player is standing in straight into native. The start node is
   * then never in an empty world even before the disk cache / server re-stream lands. Re-syncs
   * cheaply when the chunk is already cached; only does a full reclassify when it isn't.
   */
  public static void forceSyncPlayerChunk(Minecraft mc) {
    final ClientLevel level = mc.level;
    final LocalPlayer player = mc.player;
    if (level == null || player == null) {
      return;
    }
    final int minY = level.getMinY();
    final int maxY = level.getMaxY() + 1;
    final int chunkX = player.getBlockX() >> 4;
    final int chunkZ = player.getBlockZ() >> 4;

    ensureNativeWorld(minY, maxY);

    final CachedChunk existing = chunks.get(chunkKey(chunkX, chunkZ));

    // Steady-state fast path: the player chunk is already cached, kept current by incremental block
    // updates (packets + LevelChunkMixin), and - because isPathSearchReady also requires no pending
    // native resync - already present in native. Re-classifying the whole chunk every search cost
    // ~100ms
    // for nothing. worldSessionLiveAnchorReady is reset on every login/respawn AND world
    // load/switch and
    // only the live reclassify below sets it, so the first search after any swap always re-anchors
    // from
    // the live world regardless of how long ago the swap was (a stale cached start chunk otherwise
    // no-paths forever - the old false-no-path-after-swap bug and the 2026-07-08 wheat variant).
    if (existing != null && existing.ready && worldSessionLiveAnchorReady) {
      return;
    }

    // Prefer the live client chunk: after a same-area lobby swap the cached chunk may be old-lobby
    // geometry, so one fresh reclassify re-anchors the search, then sets the flag so the fast path
    // above
    // takes over for subsequent searches.
    if (processLoadedChunk(level, minY, maxY, chunkX, chunkZ)) {
      worldSessionLiveAnchorReady = true;
      return;
    }

    if (existing != null && existing.ready) {
      syncChunkToNative(chunkX, chunkZ, existing);
    }
  }

  /**
   * Reconcile a single GOAL chunk straight from the live client world. Fixes the "path to a fixed
   * nearby goal fails forever after a warp" case: the chunk's packet arrived in the initial warp
   * burst that {@link #onWorldSessionBoundary} / {@link #resetState} deliberately clear, and
   * because the player then stands still (a macro waiting for the path) no fresh packet ever
   * re-enqueues it - so the client has the chunk loaded and walkable, yet it is absent from Ryzn's
   * cache and every search to a goal inside it returns no-path. {@link #forceSyncPlayerChunk}
   * already does this for the START chunk; the goal side had no equivalent. Gated to a settled
   * world (same guard as live ingestion) so it never writes the new area's geometry under the old
   * key. No-op if the chunk is already cached or the client hasn't loaded it.
   */
  public static void forceSyncGoalChunk(int chunkX, int chunkZ) {
    if (getChunk(chunkX, chunkZ) != null) {
      return;
    } // already cached
    forceReingestChunk(chunkX, chunkZ);
  }

  /**
   * Re-read one chunk from the live world into the cache and native NOW, replacing any cached copy.
   * The executor calls this when a chunk its route crosses streams in with different terrain from
   * the copy the route was planned on - without it, the replan it asks for could run before the
   * normal per-tick ingest reaches that chunk, search the same stale copy, and hand back the same
   * wrong route.
   */
  public static void forceReingestChunk(int chunkX, int chunkZ) {
    // mid world-change: don't cross-contaminate
    if (pendingKey != null || worldSessionSettleTicks > 0 || areaConfirm.isHolding()) {
      return;
    }
    final ClientLevel level = Minecraft.getInstance().level;
    if (level == null) {
      return;
    }
    final int minY = level.getMinY();
    final int maxY = level.getMaxY() + 1;
    ensureNativeWorld(minY, maxY);
    processLoadedChunk(
        level, minY, maxY, chunkX,
        chunkZ); // classifies + stores + upserts to native if the client has it FULL
  }

  /**
   * Enqueue every chunk the client currently has loaded for a live re-classify (drained by {@link
   * #processPendingChunks} after the packet queue). Runs once per world boundary or disk load: the
   * swap guards drop the warp burst, so the cache would otherwise keep serving persisted geometry
   * for the whole visible area - wrong wherever the server's map changed since it was saved (a
   * Hypixel Hub rebuild left air cached at the new spawn ground, so every wheat-macro search
   * no-pathed at the start cell, 2026-07-08). Reads only the client's own loaded chunks;
   * beyond-view chunks keep their disk value, preserving the long-range pathing memory.
   */
  private static void queueLoadedClientChunksForReingest(ClientLevel level) {
    final LocalPlayer player = Minecraft.getInstance().player;
    if (player == null) {
      pendingLiveReingest = true; // retry next tick
      return;
    }
    reingestChunks.clear();
    final int playerChunkX = player.getBlockX() >> 4;
    final int playerChunkZ = player.getBlockZ() >> 4;
    int queued = 0;
    for (int cx = playerChunkX - LIVE_REINGEST_CHUNK_RADIUS;
        cx <= playerChunkX + LIVE_REINGEST_CHUNK_RADIUS;
        cx++) {
      for (int cz = playerChunkZ - LIVE_REINGEST_CHUNK_RADIUS;
          cz <= playerChunkZ + LIVE_REINGEST_CHUNK_RADIUS;
          cz++) {
        if (level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false) != null) {
          reingestChunks.add(chunkKey(cx, cz));
          queued++;
        }
      }
    }
    logger.info("[ryzn-cache] live reingest queued {} chunks key={}", queued, worldKey);
  }

  private static void onWorldSessionBoundary(String reason) {
    // Same-key swaps keep chunks/native on purpose: old Hub memory is useful for pathing. Only drop
    // stale in-flight deltas and wait until the new lobby's player chunk anchors the search.
    pendingChunks.clear();
    reingestChunks.clear();
    pendingNativeUpdates.clear();
    cacheKey = Long.MIN_VALUE;
    cacheChunk = null;
    worldSessionSettleTicks = Math.max(worldSessionSettleTicks, WORLD_SESSION_SETTLE_TICKS);
    areaConfirm.onWorldSessionBoundary();
    boundarySinceLoad = true;
    worldSessionLiveAnchorReady = false;
    pendingLiveReingest = true;
    logger.info(
        "[ryzn-cache] world session boundary from {} (settling {} ticks) key={}",
        reason,
        WORLD_SESSION_SETTLE_TICKS,
        worldKey);
  }

  private static void tickWorldSessionSettle() {
    // Deliberately does NOT set worldSessionLiveAnchorReady: only the live reclassify in
    // forceSyncPlayerChunk may. Setting it on timer expiry let any search started >1s after a swap
    // trust stale disk geometry at the start cell (the 2026-07-08 wheat no-path).
    if (worldSessionSettleTicks > 0) {
      worldSessionSettleTicks--;
    }
    areaConfirm.tick();
  }

  // --- disk lifecycle (read-only world keying) -----------------------------

  /**
   * Detect a world/lobby key change each tick and re-key the disk cache (refine, switch, or load).
   */
  private static void maybeRekey(Minecraft client) {
    final String loc = deriveLocationKey(client);
    if (loc == null) {
      return;
    } // not in a world; real disconnects are handled by the DISCONNECT event

    String area = detectSkyblockArea(client);
    areaConfirm.onAreaRead(
        area); // the raw read only - a sticky (old) area must never confirm the new world
    // Sticky area: a momentary TAB read failure (area briefly null) at the SAME physical location
    // must
    // not drop us to the bare-location key and discard the area cache - that base <-> area flip is
    // what
    // cleared the freshly-streamed Hub chunks and produced empty-world no-paths after a lobby swap.
    if (area == null && loc.equals(activeLocationKey) && activeArea != null) {
      area = activeArea;
    }
    final String key = (area != null) ? loc + "@" + area : loc;

    if (key.equals(activeKey)) {
      pendingKey = null;
      pendingKeyTicks = 0;
      return;
    }
    if (activeKey == null) { // first attach this session: load immediately
      pendingKey = null;
      pendingKeyTicks = 0;
      activeKey = key;
      activeLocationKey = loc;
      activeArea = area;
      onJoin(key, loc);
      return;
    }
    // Key changed while attached: require it to hold steady so a one-tick TAB blip can't thrash the
    // cache.
    if (!key.equals(pendingKey)) {
      pendingKey = key;
      pendingKeyTicks = 0;
      return;
    }
    if (++pendingKeyTicks < SWITCH_DEBOUNCE_TICKS) {
      return;
    }
    pendingKey = null;
    pendingKeyTicks = 0;

    if (key.startsWith(activeKey + "@")) {
      // base -> base@area: the chunks already streamed under the bare key ARE this area's geometry.
      // Retain them, re-key native to the area, and hydrate the area file on top - NOT a world
      // switch.
      onRefine(activeKey, key, loc);
    } else {
      // Different area at this location, or a different server / dimension: a real switch.
      onSwitch(activeKey, key, loc);
    }
    activeKey = key;
    activeLocationKey = loc;
    activeArea = area;
  }

  /**
   * The physical-location key: server address (or "singleplayer") + dimension, with NO area suffix.
   * The full world key is this plus "@&lt;area&gt;" when a SkyBlock area is detectable (see {@link
   * #maybeRekey}). Splitting them supports sticky-area identity without mixing bare cache data into
   * area caches. Null when there is no level. Read-only - nothing is sent to the server.
   */
  private static String deriveLocationKey(Minecraft client) {
    final ClientLevel level = client.level;
    if (level == null) {
      return null;
    }
    final ServerData server = client.getCurrentServer();
    final String base =
        (server != null && server.ip != null && !server.ip.isBlank()) ? server.ip : "singleplayer";
    // ResourceKey#toString() is unique per dimension; sanitize() in WorldSerializer scrubs the
    // punctuation.
    return base + "@" + level.dimension();
  }

  /**
   * The current SkyBlock island/area read from the player-list (TAB) "Area:" line, or null when
   * absent. Hypixel renders it as a fake player entry's tab name; it is island-level and stable.
   * Read-only.
   */
  private static String detectSkyblockArea(Minecraft client) {
    final ClientPacketListener conn = client.getConnection();
    if (conn == null) {
      return null;
    }
    for (PlayerInfo info : conn.getOnlinePlayers()) {
      final Component name = info.getTabListDisplayName();
      if (name == null) {
        continue;
      }
      final String line = stripTabFormatting(name.getString()).trim();
      final int marker = line.toLowerCase(Locale.ROOT).indexOf("area:");
      if (marker < 0) {
        continue;
      }
      String area = line.substring(marker + 5).trim();
      if (area.length() > 32) {
        area = area.substring(0, 32).trim();
      }
      if (!area.isEmpty()) {
        return area;
      }
    }
    return null;
  }

  private static String stripTabFormatting(String value) {
    if (value == null || value.isEmpty()) {
      return "";
    }
    StringBuilder out = null;
    int i = 0;
    while (i < value.length()) {
      final char c = value.charAt(i);
      final boolean mojibakeSection =
          c == MOJIBAKE_C2 && i + 2 < value.length() && value.charAt(i + 1) == '§';
      final boolean section = c == '§' && i + 1 < value.length();
      if (mojibakeSection || section) {
        if (out == null) {
          out = new StringBuilder(value.length()).append(value, 0, i);
        }
        i += mojibakeSection ? 3 : 2;
        continue;
      }
      if (out != null) {
        out.append(c);
      }
      i++;
    }
    return out == null ? value : out.toString();
  }

  private static void onJoin(String key, String locationKey) {
    setWorldKey(key); // native key + clear native + flag resync
    load(key, locationKey); // reset in-memory state, async-load the disk file(s) into the chunk map
    logger.info("[ryzn-cache] join key={}", key);
  }

  private static void onSwitch(String oldKey, String newKey, String newLocationKey) {
    saveAndClear(oldKey); // persist the old key (async) and reset memory + native
    setWorldKey(newKey);
    load(newKey, newLocationKey);
    logger.info("[ryzn-cache] switch {} -> {}", oldKey, newKey);
  }

  /**
   * Base-to-area refinement (e.g. "ip@overworld" -> "ip@overworld@Hub" once TAB reveals the area).
   * The chunks already streamed under the bare key belong to this area, so we do NOT clear them:
   * keep them in memory, re-key native to the area (clears + flags a resync so they are re-pushed
   * under the new key), mark them dirty so they persist into the area file, then hydrate that area
   * file on top with putIfAbsent so the retained live chunks always win.
   */
  private static void onRefine(String baseKey, String areaKey, String locationKey) {
    final int retained = chunks.size();
    if (boundarySinceLoad) {
      // A world session boundary landed while the key still had no area, so the chunks in memory
      // are
      // the world we were in BEFORE the warp - retaining them files that world's geometry under
      // this
      // area (2026-08-25: the pre-warp island's rock ended up over the Hub's Lumber Merchant floor,
      // and the quest re-routed forever against a goal cell that read solid). Drop them and load
      // the
      // area clean; the boundary's live reingest rebuilds the visible area from the real world.
      logger.info(
          "[ryzn-cache] refine {} -> {} dropped {} unattributed chunks (world changed first)",
          baseKey,
          areaKey,
          retained);
      setWorldKey(areaKey);
      load(areaKey, locationKey);
      return;
    }
    setWorldKey(areaKey); // clears native + flags resync; the in-memory chunks are NOT touched
    // the retained chunks now need persisting under the area key
    chunks.keySet().forEach(CachedWorld::markDirty);
    loadOnTop(areaKey, locationKey);
    logger.info("[ryzn-cache] refine {} -> {} (retained {} chunks)", baseKey, areaKey, retained);
  }

  private static void onDisconnect() {
    final String key = activeKey;
    if (key != null) {
      saveAndClear(key);
    } else {
      clear();
    }
    setWorldKey(null);
    activeKey = null;
    activeLocationKey = null;
    activeArea = null;
    pendingKey = null;
    pendingKeyTicks = 0;
    worldSessionSettleTicks = 0;
    worldSessionLiveAnchorReady = true;
    logger.info("[ryzn-cache] disconnect (saved key={})", key);
  }

  // --- save / load / clear -------------------------------------------------

  /**
   * Persist the current cache for {@code key} (async) and reset memory + native. Saves only when
   * there is non-empty, changed data and no load is in flight, so we never overwrite a good disk
   * cache with empty or partially-loaded data.
   */
  public static void saveAndClear(String key) {
    final Map<Long, CachedChunk> mapToSave = chunks;
    final WorldSerializer.DiskCache target = disk;
    final boolean shouldSave =
        !isCacheLoading
            && cacheDirty
            && target != null
            && target.name().equals(key)
            && hasReadyChunk(mapToSave);
    final Set<Long> dirty = drainDirty();
    resetState();
    setLoadingState(false);
    if (shouldSave) {
      Swift.io.submit(() -> WorldSerializer.save(target, mapToSave, dirty));
    }
  }

  /** Client shutdown: persist the active key and give the write a moment to land. */
  public static void shutdown() {
    final String key = activeKey;
    if (key != null) {
      saveAndClear(key);
      activeKey = null;
    }
    Swift.awaitIo(5_000L);
  }

  /** Reset memory + native, then async-load {@code key}'s disk file(s) into the chunk map. */
  public static void load(String key, String locationKey) {
    resetState();
    submitLoad(key, locationKey);
  }

  /**
   * Like {@link #load} but KEEPS the current chunk map (used by refinement: retained live chunks
   * win).
   */
  private static void loadOnTop(String key, String locationKey) {
    submitLoad(key, locationKey);
  }

  /** Load only the active area's file. Bare keys are ignored when area-specific siblings exist. */
  private static void submitLoad(String key, String locationKey) {
    final ConcurrentHashMap<Long, CachedChunk> sessionMap = chunks;
    final WorldSerializer.DiskCache target = new WorldSerializer.DiskCache(key);
    disk = target;
    setLoadingState(true);
    logger.info("[ryzn-cache] load start key={}", key);

    Swift.io.submit(
        () -> {
          int loaded = 0;
          try {
            final boolean bareKey = locationKey != null && locationKey.equals(key);
            if (!bareKey || !WorldSerializer.hasAreaSiblings(locationKey)) {
              loaded += mergeFromDisk(sessionMap, target);
            }
            if (chunks == sessionMap) {
              pendingNativeResync = true; // next tick upserts the loaded chunks into native
              logger.info("[ryzn-cache] load done key={} chunks loaded={}", key, loaded);
            }
          } catch (Exception e) {
            logger.warn("[ryzn-cache] load failed for {}", key, e);
          } finally {
            if (chunks == sessionMap) {
              setLoadingState(false);
            }
          }
        });
  }

  /**
   * Load one disk file and putIfAbsent its chunks into {@code map} (never clobbers live/area data).
   * Returns added count.
   */
  private static int mergeFromDisk(
      ConcurrentHashMap<Long, CachedChunk> map, WorldSerializer.DiskCache target) {
    final ConcurrentHashMap<Long, CachedChunk> fromDisk = WorldSerializer.load(target);
    if (fromDisk == null) {
      return 0;
    }
    int added = 0;
    for (Map.Entry<Long, CachedChunk> entry : fromDisk.entrySet()) {
      if (map.putIfAbsent(entry.getKey(), entry.getValue()) == null) {
        added++;
      }
    }
    return added;
  }

  public static void clear() {
    resetState();
    setLoadingState(false);
  }

  private static void resetState() {
    chunks = new ConcurrentHashMap<>(512);
    pendingChunks.clear();
    reingestChunks.clear();
    pendingNativeUpdates.clear();
    cacheKey = Long.MIN_VALUE;
    cacheChunk = null;
    pendingNativeResync = true;
    nativeWorldToken = "";
    cacheDirty = false;
    dirtyChunks.clear();
    disk = null;
    worldSessionSettleTicks = 0;
    areaConfirm.clear(); // the key just re-keyed to the live world: ingestion may resume
    boundarySinceLoad = false; // this map is attributed to the key being loaded, nothing older
    worldSessionLiveAnchorReady =
        false; // first search after a load/switch re-anchors the start chunk live
    pendingLiveReingest = true; // and the visible area reconciles against the live world
    NativePathfinderBridge.clearWorld();
  }

  private static void maybePeriodicSave() {
    if (!cacheDirty || isCacheLoading) {
      return;
    }
    final String key = activeKey;
    final WorldSerializer.DiskCache target = disk;
    if (key == null || target == null || !target.name().equals(key)) {
      return;
    }
    final ConcurrentHashMap<Long, CachedChunk> snapshot = chunks;
    if (!hasReadyChunk(snapshot)) {
      return;
    }
    cacheDirty = false;
    final Set<Long> dirty = drainDirty();
    Swift.io.submit(() -> WorldSerializer.save(target, snapshot, dirty));
  }

  private static boolean hasReadyChunk(Map<Long, CachedChunk> map) {
    for (CachedChunk c : map.values()) {
      if (c != null && c.ready) {
        return true;
      }
    }
    return false;
  }

  private static void setLoadingState(boolean loading) {
    isCacheLoading = loading;
  }

  public static void setWorldKey(String newWorldKey) {
    final String normalized =
        (newWorldKey == null || newWorldKey.isBlank()) ? RUNTIME_WORLD_KEY : newWorldKey;
    if (worldKey.equals(normalized)) {
      return;
    }
    worldKey = normalized;
    nativeWorldToken = "";
    pendingNativeResync = true;
    NativePathfinderBridge.clearWorld();
  }

  public static String getCacheStats() {
    final ConcurrentHashMap<Long, CachedChunk> current = chunks;
    int ready = 0;
    for (CachedChunk c : current.values()) {
      if (c != null && c.ready) {
        ready++;
      }
    }
    return "Cached: "
        + ready
        + ", Pending: "
        + pendingChunks.size()
        + (isCacheLoading ? ", Loading" : "")
        + (cacheDirty ? ", Dirty" : "");
  }

  /** Full key + cache state, for the no-path / cache-wait-timeout diagnostics. */
  public static String getDiagnostics() {
    final ConcurrentHashMap<Long, CachedChunk> current = chunks;
    int ready = 0;
    for (CachedChunk c : current.values()) {
      if (c != null && c.ready) {
        ready++;
      }
    }
    return "activeKey="
        + activeKey
        + " worldKey="
        + worldKey
        + " pendingKey="
        + pendingKey
        + " Cached="
        + ready
        + " Pending="
        + pendingChunks.size()
        + " Loading="
        + isCacheLoading
        + " Dirty="
        + cacheDirty
        + " pendingNativeResync="
        + pendingNativeResync
        + " SessionSettle="
        + worldSessionSettleTicks
        + " SessionAnchor="
        + worldSessionLiveAnchorReady
        + " AreaHold="
        + areaConfirm.isHolding();
  }

  /**
   * No-path triage: the cached chunk-coordinate extent plus what is cached at the start and goal
   * cells (the cell, the block below it, and the head cell). "NOT CACHED" means the chunk is absent
   * from the cache entirely; a number is the V5 voxel flags ({@link NativeStateEncoder}); "air" is
   * empty space.
   */
  public static String describeProbe(int sx, int sy, int sz, int gx, int gy, int gz) {
    return chunkBounds()
        + " | "
        + describeCell("start", sx, sy, sz)
        + " | "
        + describeCell("goal", gx, gy, gz)
        + " | "
        + corridorCoverage(sx, sz, gx, gz);
  }

  /**
   * Sample the chunks along the straight start->goal line and report how many are cached. A* does
   * not travel a straight line, but if the direct corridor has holes that is the most likely reason
   * a search between two cached, standable endpoints still finds no path (the connecting geometry
   * was never loaded/saved).
   */
  private static String corridorCoverage(int sx, int sz, int gx, int gz) {
    final int scx = sx >> 4;
    final int scz = sz >> 4;
    final int gcx = gx >> 4;
    final int gcz = gz >> 4;
    final int steps = Math.max(Math.abs(gcx - scx), Math.abs(gcz - scz));
    if (steps == 0) {
      return "corridor=same chunk";
    }
    int cached = 0;
    int missing = 0;
    final StringBuilder miss = new StringBuilder();
    for (int i = 0; i <= steps; i++) {
      final int cx = scx + Math.round((gcx - scx) * (i / (float) steps));
      final int cz = scz + Math.round((gcz - scz) * (i / (float) steps));
      if (getChunk(cx, cz) != null) {
        cached++;
      } else {
        missing++;
        if (miss.length() < 64) {
          miss.append('[').append(cx).append(',').append(cz).append(']');
        }
      }
    }
    return "corridor cached="
        + cached
        + "/"
        + (steps + 1)
        + (missing > 0 ? " MISSING=" + missing + " " + miss : " (full)");
  }

  private static String chunkBounds() {
    final ConcurrentHashMap<Long, CachedChunk> current = chunks;
    int minCx = Integer.MAX_VALUE;
    int maxCx = Integer.MIN_VALUE;
    int minCz = Integer.MAX_VALUE;
    int maxCz = Integer.MIN_VALUE;
    int n = 0;
    for (Map.Entry<Long, CachedChunk> e : current.entrySet()) {
      final CachedChunk c = e.getValue();
      if (c == null || !c.ready) {
        continue;
      }
      final long k = e.getKey();
      final int cx = (int) (k >> 32);
      final int cz = (int) k;
      minCx = Math.min(minCx, cx);
      maxCx = Math.max(maxCx, cx);
      minCz = Math.min(minCz, cz);
      maxCz = Math.max(maxCz, cz);
      n++;
    }
    if (n == 0) {
      return "bounds=empty";
    }
    return "boundsCX[" + minCx + ".." + maxCx + "] CZ[" + minCz + ".." + maxCz + "] ready=" + n;
  }

  private static String describeCell(String label, int x, int y, int z) {
    final int cx = x >> 4;
    final int cz = z >> 4;
    if (getChunk(cx, cz) == null) {
      return label + "(" + x + "," + y + "," + z + ") chunk[" + cx + "," + cz + "] NOT CACHED";
    }
    return String.format(
        "%s(%d,%d,%d) chunk[%d,%d] here=%s below=%s head=%s",
        label, x, y, z, cx, cz, flagStr(x, y, z), flagStr(x, y - 1, z), flagStr(x, y + 1, z));
  }

  private static String flagStr(int x, int y, int z) {
    final Short code = getBlockCode(x, y, z);
    if (code == null) {
      return "?";
    }
    return code == CachedChunk.AIR ? "air" : Integer.toString(code);
  }

  // --- native sync ---------------------------------------------------------

  private static void ensureNativeWorld(int minY, int maxY) {
    if (!NativePathfinderBridge.isAvailable()) {
      return;
    }
    final String token = worldKey + "|" + minY + "|" + maxY;
    if (token.equals(nativeWorldToken)) {
      return;
    }
    NativePathfinderBridge.setWorld(worldKey, minY, maxY);
    if (NativePathfinderBridge.getLastError() == null) {
      nativeWorldToken = token;
      pendingNativeResync = true;
    }
  }

  private static int syncAllCachedChunksToNative() {
    if (!NativePathfinderBridge.isAvailable()) {
      return 0;
    }
    int synced = 0;
    for (Map.Entry<Long, CachedChunk> e : chunks.entrySet()) {
      final CachedChunk chunk = e.getValue();
      if (chunk == null || !chunk.ready) {
        continue;
      }
      final long key = e.getKey();
      syncChunkToNative((int) (key >> 32), (int) (long) key, chunk);
      synced++;
    }
    return synced;
  }

  private static synchronized void syncChunkToNative(int chunkX, int chunkZ, CachedChunk chunk) {
    if (!NativePathfinderBridge.isAvailable() || !chunk.ready) {
      return;
    }

    final int sectionCount = chunk.sectionCount();
    long sectionMask = 0L;
    int totalValues = 0;
    for (int i = 0; i < sectionCount; i++) {
      if (chunk.hasSection(i)) {
        sectionMask |= (1L << i);
        totalValues += 4096;
      }
    }

    if (totalValues == 0) {
      NativePathfinderBridge.upsertChunk(chunkX, chunkZ, chunk.minY, chunk.maxY, 0L, new short[0]);
      return;
    }

    // Reused across calls: the native side reads only the masked sections from the front.
    if (nativeScratch.length < totalValues) {
      nativeScratch = new short[sectionCount * 4096];
    }
    final short[] sectionFlags = nativeScratch;
    int offset = 0;
    for (int i = 0; i < sectionCount; i++) {
      if ((sectionMask & (1L << i)) == 0L) {
        continue;
      }
      chunk.copySectionCodes(i, sectionFlags, offset);
      offset += 4096;
    }
    NativePathfinderBridge.upsertChunk(
        chunkX, chunkZ, chunk.minY, chunk.maxY, sectionMask, sectionFlags);
  }

  private static void flushPendingNativeUpdates() {
    if (!NativePathfinderBridge.isAvailable()) {
      pendingNativeUpdates.clear();
      return;
    }
    if (pendingNativeUpdates.isEmpty()) {
      return;
    }

    int[] updates = new int[Math.max(16, pendingNativeUpdates.size() * 4)];
    int offset = 0;
    for (Iterator<Map.Entry<Long, Integer>> it = pendingNativeUpdates.entrySet().iterator();
        it.hasNext(); ) {
      final Map.Entry<Long, Integer> e = it.next();
      final long blockKey = e.getKey();
      final int flags = e.getValue();
      it.remove();

      if (offset + 4 > updates.length) {
        final int[] grown = new int[Math.max(updates.length << 1, offset + 4)];
        System.arraycopy(updates, 0, grown, 0, offset);
        updates = grown;
      }
      updates[offset] = unpackBlockX(blockKey);
      updates[offset + 1] = unpackBlockY(blockKey);
      updates[offset + 2] = unpackBlockZ(blockKey);
      updates[offset + 3] = flags;
      offset += 4;
    }

    if (offset == 0) {
      return;
    }
    if (offset != updates.length) {
      final int[] trimmed = new int[offset];
      System.arraycopy(updates, 0, trimmed, 0, offset);
      updates = trimmed;
    }
    NativePathfinderBridge.applyBlockUpdates(updates);
  }

  private static void queueNativeUpdate(int x, int y, int z, int flags) {
    if (!NativePathfinderBridge.isAvailable()) {
      return;
    }
    pendingNativeUpdates.put(blockKey(x, y, z), flags);
  }
}
