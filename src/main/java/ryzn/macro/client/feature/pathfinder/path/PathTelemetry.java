package ryzn.macro.client.feature.pathfinder.path;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import ryzn.macro.client.config.JsonTrees;
import ryzn.macro.client.config.RyznSettings;
import ryzn.macro.client.config.groups.WalkingSettings.Control;
import ryzn.macro.client.debug.DebugLog;
import ryzn.macro.client.feature.hud.TpsMeter;
import ryzn.swift.cache.CachedWorld;
import ryzn.swift.nativepath.NativeStateEncoder;

/**
 * Flight recorder for the walk executor, on whenever {@code /dev debug} is. Writes one JSON line
 * per event to {@code <gameDir>/ryzn/telemetry/path-<time>.jsonl}: everything the executor reads or
 * decides each tick, the real collision shapes it walks past, what the server did to the body, and
 * per-frame head rotation, so a run can be replayed in the sim and diffed tick by tick. The format
 * is documented in {@code docs/agent-memory/pathfinder-sim-harness.md}. Read-only: it never touches
 * input, rotation or packets.
 */
public final class PathTelemetry {

  private static final int KEEP_FILES = 40;
  private static final double BLOCK_UPDATE_RANGE_SQ = 32.0 * 32.0;
  private static final DateTimeFormatter FILE_TIME =
      DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

  private static BufferedWriter out;
  private static volatile boolean recording;
  private static volatile boolean arrived;
  private static long startNanos;
  private static long lastFrameNanos;
  private static boolean priorGround;
  private static boolean priorSprint;
  private static boolean hasPriorTick;
  private static Double landingOffset;
  private static int arrivalTicks;
  private static Vec3 finalPoint;
  private static Vec3 finalDirection;
  private static double peakOvershoot;
  private static int tickIndex;
  private static int routeIndex;
  private static BlockPos lastScanned;
  private static List<Vec3> routeTrail = List.of();
  private static final int TRAIL_RESCAN_AHEAD = 12;

  /** A quit or crash mid-walk loses at most this much of the file. */
  private static final int FLUSH_EVERY_TICKS = 20;

  private static Decision decision;
  private static final Set<Long> recordedCells = new HashSet<>();
  private static final StringBuilder frames = new StringBuilder();
  private static int frameCount;
  private static final Queue<String> notes = new ConcurrentLinkedQueue<>();
  private static final Queue<String> inbound = new ConcurrentLinkedQueue<>();
  private static volatile int playerId = Integer.MIN_VALUE;
  private static volatile double playerX;
  private static volatile double playerZ;

  private PathTelemetry() {}

  /**
   * What the executor decided this tick. Logged next to the physics that followed it.
   *
   * @param keys movement keys held for the next tick, as letters: W A S D, J jump, C crouch
   * @param aimIndex trail index the body steers at (cursor, or cursor + 1 when peeking)
   * @param aimX steer point after ledge/deadzone/rejoin adjustments
   * @param faceYaw bearing from the body to the steer point
   * @param faceGap |faceYaw - player yaw|, what the hop facing gate reads
   * @param aimGap |head target yaw - player yaw|
   * @param flatGap flat distance to the steer point
   * @param gazeNode trail index the head looks at
   * @param dropGap distance to a planned drop, NaN when none
   */
  record Decision(
      String phase,
      int cursor,
      int aimIndex,
      double aimX,
      double aimZ,
      float faceYaw,
      float faceGap,
      float aimGap,
      double flatGap,
      int gazeNode,
      float aimYaw,
      float aimPitch,
      String keys,
      boolean sprint,
      boolean hop,
      boolean constantJump,
      boolean forcedHop,
      int wallGrindTicks,
      int wallStallTicks,
      int hopCooldown,
      double dropGap,
      boolean sneak,
      String water,
      boolean ledge,
      boolean deadzone,
      int airTicks,
      boolean ownsLook,
      boolean airDiagonal,
      String combatAirSteer) {}

  static boolean recording() {
    return recording;
  }

  static String keyLetters(Minecraft client, List<KeyMapping> keys) {
    final StringBuilder letters = new StringBuilder();
    if (keys.contains(client.options.keyUp)) {
      letters.append('W');
    }
    if (keys.contains(client.options.keyLeft)) {
      letters.append('A');
    }
    if (keys.contains(client.options.keyDown)) {
      letters.append('S');
    }
    if (keys.contains(client.options.keyRight)) {
      letters.append('D');
    }
    if (keys.contains(client.options.keyJump)) {
      letters.append('J');
    }
    if (keys.contains(client.options.keyShift)) {
      letters.append('C');
    }
    return letters.toString();
  }

  static void decision(Decision value) {
    decision = value;
  }

  /** A route was installed: a fresh walk opens a new file, a hot swap appends to the open one. */
  static void route(
      Minecraft client,
      List<PathNode> nodes,
      List<Vec3> trail,
      List<Integer> marks,
      BlockPos goal,
      boolean hotSwap) {
    try {
      recordRoute(client, nodes, trail, marks, goal, hotSwap);
    } catch (RuntimeException failure) {
      abandon(failure);
    }
  }

  /** End of every client tick the executor runs: physics after this tick's move, plus events. */
  static void tick(Minecraft client, boolean running, boolean won, boolean bailed) {
    try {
      recordTick(client, running, won, bailed);
    } catch (RuntimeException failure) {
      abandon(failure);
    }
  }

  /** Every rendered frame: the head turns per frame in game, so the sim needs these. */
  public static void onFrame() {
    if (!recording) {
      return;
    }
    try {
      recordFrame();
    } catch (RuntimeException failure) {
      abandon(failure);
    }
  }

  /** Network thread. Only records; the packet itself is never touched. */
  public static void onInboundPacket(Packet<?> packet) {
    if (!recording || !DebugLog.isEnabled(DebugLog.Category.NAV)) {
      return;
    }
    try {
      recordPacket(packet);
    } catch (RuntimeException failure) {
      recording = false;
      DebugLog.error(DebugLog.Category.NAV, "telemetry: packet record failed (%s)", failure);
    }
  }

  /** A recorder bug must never take the walk down with it: log it and stop recording. */
  private static void abandon(RuntimeException failure) {
    DebugLog.error(DebugLog.Category.NAV, "telemetry: recorder failed, stopping (%s)", failure);
    recording = false;
    DebugLog.setNavSink(null);
    if (out != null) {
      try {
        out.close();
      } catch (IOException ignored) {
        // Diagnostics only.
      }
      out = null;
    }
  }

  private static void recordRoute(
      Minecraft client,
      List<PathNode> nodes,
      List<Vec3> trail,
      List<Integer> marks,
      BlockPos goal,
      boolean hotSwap) {
    if ((!DebugLog.isEnabled(DebugLog.Category.NAV)
            && !RyznSettings.get().pathfinder().walking().enabled(Control.TRACE))
        || client.player == null) {
      return;
    }
    if (!hotSwap || out == null) {
      close("restarted");
      if (!open(client)) {
        return;
      }
    }
    if (trail.size() >= 2) {
      finalPoint = trail.getLast();
      finalDirection =
          finalPoint.subtract(trail.get(trail.size() - 2)).multiply(1, 0, 1).normalize();
    } else {
      finalPoint = null;
      finalDirection = null;
    }
    final Line line = new Line("route").num("route", ++routeIndex).bool("hotSwap", hotSwap);
    if (goal != null) {
      line.raw("goal", "[" + goal.getX() + "," + goal.getY() + "," + goal.getZ() + "]");
    }
    final StringBuilder nodeJson = new StringBuilder("[");
    for (PathNode node : nodes) {
      if (nodeJson.length() > 1) {
        nodeJson.append(',');
      }
      nodeJson
          .append('[')
          .append(node.cell().getX())
          .append(',')
          .append(node.cell().getY())
          .append(',')
          .append(node.cell().getZ())
          .append(',')
          .append(fmt(node.center().x, 3))
          .append(',')
          .append(fmt(node.center().y, 3))
          .append(',')
          .append(fmt(node.center().z, 3))
          .append(',')
          .append(node.jumpUp() ? 1 : 0)
          .append(",\"")
          .append(node.medium().name())
          .append("\",")
          .append(node.gapFiller() ? 1 : 0)
          .append(']');
    }
    line.raw("nodes", nodeJson.append(']').toString());
    line.raw("trail", points(trail));
    line.raw("marks", marks.toString().replace(" ", ""));
    write(line);
    routeTrail = List.copyOf(trail);
    scanTrail(client, 0, routeTrail.size());
  }

  private static void scanTrail(Minecraft client, int from, int to) {
    for (int i = Math.max(0, from); i < Math.min(to, routeTrail.size()); i++) {
      final Vec3 point = routeTrail.get(i);
      scanCells(
          client,
          (int) Math.floor(point.x),
          (int) Math.floor(point.y),
          (int) Math.floor(point.z),
          1,
          -1,
          3);
    }
  }

  private static void recordTick(Minecraft client, boolean running, boolean won, boolean bailed) {
    if (out == null) {
      return;
    }
    final LocalPlayer player = client.player;
    if (player == null || client.level == null) {
      close("no player");
      return;
    }
    try {
      playerId = player.getId();
    } catch (IllegalStateException unassigned) {
      // The sim's player never gets an entity id; it only filters knockback packets.
    }
    playerX = player.getX();
    playerZ = player.getZ();
    final Vec3 velocity = player.getDeltaMovement();
    final Line line =
        new Line("tick")
            .num("i", tickIndex++)
            .num("gt", client.level.getLevelData() == null ? -1 : client.level.getGameTime())
            .num("ms", (System.nanoTime() - startNanos) / 1_000_000L)
            .raw(
                "pos",
                "["
                    + fmt(player.getX(), 4)
                    + ","
                    + fmt(player.getY(), 4)
                    + ","
                    + fmt(player.getZ(), 4)
                    + "]")
            .raw(
                "vel",
                "["
                    + fmt(velocity.x, 5)
                    + ","
                    + fmt(velocity.y, 5)
                    + ","
                    + fmt(velocity.z, 5)
                    + "]")
            .num("yaw", player.getYRot(), 3)
            .num("pitch", player.getXRot(), 3)
            .bool("ground", player.onGround())
            .bool("hc", player.horizontalCollision)
            .bool("mhc", player.minorHorizontalCollision)
            .bool("vc", player.verticalCollision)
            .bool("vcb", player.verticalCollisionBelow)
            .bool("sprinting", player.isSprinting())
            .bool("crouching", player.isCrouching())
            .bool("water", player.isInWater())
            .bool("swimming", player.isSwimming())
            .bool("climb", player.onClimbable())
            .num("fall", player.fallDistance, 3)
            .raw("input", safe(() -> quote(inputLetters(player))))
            .raw("moveX", safe(() -> fmt(player.input.getMoveVector().x, 3)))
            .raw("moveY", safe(() -> fmt(player.input.getMoveVector().y, 3)))
            .raw(
                "speedAttr",
                safe(() -> fmt(player.getAttributeValue(Attributes.MOVEMENT_SPEED), 5)))
            .raw("stepAttr", safe(() -> fmt(player.getAttributeValue(Attributes.STEP_HEIGHT), 3)))
            .raw("jumpAttr", safe(() -> fmt(player.getAttributeValue(Attributes.JUMP_STRENGTH), 4)))
            .raw("jumpHeight", safe(() -> fmt(JumpCapability.height(), 4)))
            .raw("gravAttr", safe(() -> fmt(player.getAttributeValue(Attributes.GRAVITY), 4)))
            .raw("effects", safe(() -> quote(effects(player))));
    line.bool(
            "physicalTakeoff", hasPriorTick && priorGround && !player.onGround() && velocity.y > 0)
        .bool("groundContact", hasPriorTick && !priorGround && player.onGround())
        .bool("sprintChanged", hasPriorTick && priorSprint != player.isSprinting());
    priorGround = player.onGround();
    priorSprint = player.isSprinting();
    hasPriorTick = true;
    if (landingOffset != null) {
      line.num("landingOffset", landingOffset, 4);
      landingOffset = null;
    }
    if (arrived && finalPoint != null && finalDirection != null) {
      peakOvershoot =
          Math.max(peakOvershoot, player.position().subtract(finalPoint).dot(finalDirection));
      line.num("peakArrivalOvershoot", peakOvershoot, 4)
          .num(
              "arrivalMiss",
              Math.hypot(player.getX() - finalPoint.x, player.getZ() - finalPoint.z),
              4);
    }
    final BlockPos below = player.getBlockPosBelowThatAffectsMyMovement();
    line.raw("supportBlock", "[" + below.getX() + "," + below.getY() + "," + below.getZ() + "]");
    final Block under = client.level.getBlockState(below).getBlock();
    line.num("friction", under.getFriction(), 3)
        .num("speedFactor", under.getSpeedFactor(), 3)
        .num("jumpFactor", under.getJumpFactor(), 3)
        .raw("ping", safe(() -> Integer.toString(ping(client, player))))
        .num("tps", TpsMeter.tps(), 2)
        .raw("fps", safe(() -> Integer.toString(client.getFps())))
        .num("frames", frameCount)
        .raw("frameLog", "[" + frames + "]")
        .bool("running", running);
    frames.setLength(0);
    frameCount = 0;
    final Decision now = decision;
    decision = null;
    if (now != null) {
      line.raw("dec", decisionJson(now));
      // Trail cells whose chunk was not loaded at route time get picked up as the body nears them.
      scanTrail(client, now.cursor(), now.cursor() + TRAIL_RESCAN_AHEAD);
    }
    final String pushers = movingEntities(client, player);
    if (!pushers.isEmpty()) {
      line.raw("entities", pushers);
    }
    line.raw("notes", drain(notes, true));
    line.raw("server", drain(inbound, false));
    write(line);
    if (tickIndex % FLUSH_EVERY_TICKS == 0 && out != null) {
      try {
        out.flush();
      } catch (IOException ignored) {
        // The next write reports a broken file.
      }
    }

    final BlockPos feet = player.blockPosition();
    if (!feet.equals(lastScanned)) {
      lastScanned = feet;
      scanCells(client, feet.getX(), feet.getY() - 1, feet.getZ(), 2, 0, 3);
    }
    if (!running) {
      // won is not cleared on a new start, so a manual stop after an earlier arrival still reads
      // true; the executor's own arrival line is the reliable signal.
      if (arrived && won && arrivalTicks++ < 8) {
        return;
      }
      close(arrived && won ? "arrived" : bailed ? "failed" : "stopped");
    }
  }

  static void arrival() {
    if (recording) {
      arrived = true;
    }
  }

  static void landingOffset(double distance) {
    if (recording) {
      landingOffset = distance;
    }
  }

  private static void note(String message) {
    notes.add(message);
    if (message.startsWith("Arrived")) {
      arrived = true;
    }
  }

  private static void recordFrame() {
    final LocalPlayer player = Minecraft.getInstance().player;
    final long now = System.nanoTime();
    final double dt = lastFrameNanos == 0L ? 0.0 : (now - lastFrameNanos) / 1.0e6;
    lastFrameNanos = now;
    if (player == null) {
      return;
    }
    if (frameCount++ > 0) {
      frames.append(',');
    }
    var camera = Minecraft.getInstance().gameRenderer.mainCamera();
    frames
        .append('[')
        .append(fmt(dt, 2))
        .append(',')
        .append(fmt(player.getYRot(), 3))
        .append(',')
        .append(fmt(player.getXRot(), 3))
        .append(',')
        .append(fmt(camera.yRot(), 3))
        .append(',')
        .append(fmt(camera.xRot(), 3))
        .append(',')
        .append(fmt((now - startNanos) / 1e6, 2))
        .append(']');
  }

  private static void recordPacket(Packet<?> packet) {
    switch (packet) {
      case ClientboundPlayerPositionPacket teleport -> {
        final var change = teleport.change();
        inbound.add(
            "{\"type\":\"teleport\",\"pos\":"
                + vec(change.position(), 4)
                + ",\"delta\":"
                + vec(change.deltaMovement(), 5)
                + ",\"yaw\":"
                + fmt(change.yRot(), 3)
                + ",\"pitch\":"
                + fmt(change.xRot(), 3)
                + ",\"relative\":\""
                + teleport.relatives()
                + "\"}");
      }
      case ClientboundSetEntityMotionPacket motion when motion.id() == playerId ->
          inbound.add("{\"type\":\"motion\",\"delta\":" + vec(motion.movement(), 5) + "}");
      case ClientboundBlockUpdatePacket update ->
          blockUpdate(update.getPos(), update.getBlockState());
      case ClientboundSectionBlocksUpdatePacket section ->
          section.runUpdates(PathTelemetry::blockUpdate);
      case ClientboundLevelChunkWithLightPacket chunk ->
          inbound.add(
              "{\"type\":\"chunkLoad\",\"x\":" + chunk.getX() + ",\"z\":" + chunk.getZ() + "}");
      case ClientboundForgetLevelChunkPacket forget ->
          inbound.add(
              "{\"type\":\"chunkUnload\",\"x\":"
                  + forget.pos().x()
                  + ",\"z\":"
                  + forget.pos().z()
                  + "}");
      default -> {}
    }
  }

  private static void blockUpdate(BlockPos pos, BlockState state) {
    final double dx = pos.getX() + 0.5 - playerX;
    final double dz = pos.getZ() + 0.5 - playerZ;
    if (dx * dx + dz * dz > BLOCK_UPDATE_RANGE_SQ) {
      return;
    }
    inbound.add(
        "{\"type\":\"block\",\"at\":["
            + pos.getX()
            + ","
            + pos.getY()
            + ","
            + pos.getZ()
            + "],\"state\":"
            + quote(state.toString())
            + "}");
  }

  private static boolean open(Minecraft client) {
    try {
      final Path root = client.gameDirectory == null ? Path.of(".") : client.gameDirectory.toPath();
      final Path dir = root.resolve("ryzn").resolve("telemetry");
      Files.createDirectories(dir);
      prune(dir);
      final Path file = dir.resolve("path-" + LocalDateTime.now().format(FILE_TIME) + ".jsonl");
      out = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
    } catch (IOException failure) {
      DebugLog.error(DebugLog.Category.NAV, "telemetry: cannot open file (%s)", failure);
      out = null;
      return false;
    }
    startNanos = System.nanoTime();
    lastFrameNanos = 0L;
    tickIndex = 0;
    routeIndex = 0;
    lastScanned = null;
    decision = null;
    recordedCells.clear();
    frames.setLength(0);
    frameCount = 0;
    notes.clear();
    inbound.clear();
    arrived = false;
    hasPriorTick = false;
    landingOffset = null;
    arrivalTicks = 0;
    peakOvershoot = 0;
    recording = true;
    DebugLog.setNavSink(PathTelemetry::note);
    write(session(client));
    return true;
  }

  private static void close(String outcome) {
    if (out == null) {
      return;
    }
    write(new Line("end").str("outcome", outcome).num("ticks", tickIndex));
    recording = false;
    DebugLog.setNavSink(null);
    try {
      out.close();
    } catch (IOException ignored) {
      // The file is diagnostics only; a failed close loses at most the buffered tail.
    }
    out = null;
  }

  private static void prune(Path dir) throws IOException {
    try (Stream<Path> files = Files.list(dir)) {
      final List<Path> old =
          files.filter(p -> p.getFileName().toString().startsWith("path-")).sorted().toList();
      for (int i = 0; i <= old.size() - KEEP_FILES; i++) {
        Files.deleteIfExists(old.get(i));
      }
    }
  }

  private static Line session(Minecraft client) {
    final LocalPlayer player = client.player;
    final ServerData server = client.getCurrentServer();
    String version = "?";
    try {
      version =
          FabricLoader.getInstance()
              .getModContainer("ryzn")
              .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
              .orElse("?");
    } catch (IllegalStateException headless) {
      // The sim runs without a loader; the version is only a label.
    }
    final RyznSettings settings = RyznSettings.get();
    return new Line("session")
        .num("format", 1)
        .str("ryzn", version)
        .str("time", LocalDateTime.now().toString())
        .str("server", server == null ? "" : server.ip)
        .str("cache", CachedWorld.getDiagnostics())
        .raw("start", vec(player.position(), 4))
        .num("startYaw", player.getYRot(), 3)
        .num("startPitch", player.getXRot(), 3)
        .raw("sensitivity", option(client.options.sensitivity()))
        .raw("maxFps", option(client.options.framerateLimit()))
        .raw("vsync", option(client.options.enableVsync()))
        .raw("renderDistance", option(client.options.renderDistance()))
        .raw("simulationDistance", option(client.options.simulationDistance()))
        .raw("autoJump", option(client.options.autoJump()))
        .raw("toggleSprint", option(client.options.toggleSprint()))
        .raw("toggleCrouch", option(client.options.toggleCrouch()))
        .raw("pathfinder", settings == null ? "null" : oneLine(settings.pathfinder().write()))
        .raw("rotations", settings == null ? "null" : oneLine(settings.rotations().write()));
  }

  /**
   * A field the sim's stand-in player cannot supply reads as null instead of ending the recording.
   * In game every one of these is always present.
   */
  private static String safe(Supplier<String> value) {
    try {
      return value.get();
    } catch (RuntimeException missing) {
      return "null";
    }
  }

  /** The printer is pretty; a JSONL record has to stay on one line. */
  private static String oneLine(com.google.gson.JsonObject tree) {
    return JsonTrees.print(tree).replaceAll("\\R\\s*", "");
  }

  /** A number or boolean option as JSON; the sim's stand-in options leave most of them unset. */
  private static String option(OptionInstance<?> option) {
    return option == null || option.get() == null ? "null" : option.get().toString();
  }

  /**
   * Records each cell once per file: real block state, its collision boxes for this player, the
   * code Java would classify it to now, and the code the cache holds. Air whose cache code agrees
   * is skipped, which is nearly all of it.
   */
  private static void scanCells(
      Minecraft client, int cx, int cy, int cz, int r, int dyLo, int dyHi) {
    final CollisionContext context = CollisionContext.of(client.player);
    final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    for (int dx = -r; dx <= r; dx++) {
      for (int dz = -r; dz <= r; dz++) {
        for (int dy = dyLo; dy <= dyHi; dy++) {
          final int x = cx + dx;
          final int y = cy + dy;
          final int z = cz + dz;
          pos.set(x, y, z);
          // An unloaded chunk reads as void_air; leave the cell for a later scan once it streams
          // in.
          if (!client.level.hasChunkAt(pos) || !recordedCells.add(BlockPos.asLong(x, y, z))) {
            continue;
          }
          final BlockState state = client.level.getBlockState(pos);
          final short live = NativeStateEncoder.codeForState(state);
          final Short cached = CachedWorld.getBlockCode(x, y, z);
          final VoxelShape shape = state.getCollisionShape(client.level, pos, context);
          final FluidState fluid = state.getFluidState();
          final boolean cacheAgrees = cached != null && cached == live;
          if (shape.isEmpty() && fluid.isEmpty() && cacheAgrees) {
            continue;
          }
          final Line line =
              new Line("block")
                  .raw("at", "[" + x + "," + y + "," + z + "]")
                  .str("state", state.toString())
                  .num("live", live)
                  .raw("cache", cached == null ? "null" : Short.toString(cached));
          if (shape == Shapes.block()) {
            line.str("boxes", "full");
          } else if (!shape.isEmpty()) {
            final StringBuilder boxes = new StringBuilder("[");
            for (AABB box : shape.toAabbs()) {
              if (boxes.length() > 1) {
                boxes.append(',');
              }
              boxes
                  .append('[')
                  .append(fmt(box.minX, 4))
                  .append(',')
                  .append(fmt(box.minY, 4))
                  .append(',')
                  .append(fmt(box.minZ, 4))
                  .append(',')
                  .append(fmt(box.maxX, 4))
                  .append(',')
                  .append(fmt(box.maxY, 4))
                  .append(',')
                  .append(fmt(box.maxZ, 4))
                  .append(']');
            }
            line.raw("boxes", boxes.append(']').toString());
          }
          if (!fluid.isEmpty()) {
            line.str("fluid", fluid.getType().toString())
                .num("fluidHeight", fluid.getOwnHeight(), 3);
          }
          write(line);
        }
      }
    }
  }

  /**
   * Only entities that can move the body: hard colliders (boats, shulkers) touching it, and
   * pushable ones overlapping it. Everything else cannot change a walk, so it is left out.
   */
  private static String movingEntities(Minecraft client, LocalPlayer player) {
    final AABB reach = player.getBoundingBox().inflate(0.3);
    final StringBuilder json = new StringBuilder();
    for (Entity entity : client.level.getEntities(player, reach)) {
      final boolean collides = entity.canBeCollidedWith(player);
      final boolean pushes =
          entity.isPushable() && entity.getBoundingBox().intersects(player.getBoundingBox());
      if (!collides && !pushes) {
        continue;
      }
      json.append(json.isEmpty() ? "[" : ",")
          .append("{\"type\":")
          .append(quote(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString()))
          .append(",\"pos\":")
          .append(vec(entity.position(), 3))
          .append(",\"w\":")
          .append(fmt(entity.getBbWidth(), 3))
          .append(",\"h\":")
          .append(fmt(entity.getBbHeight(), 3))
          .append(",\"collides\":")
          .append(collides)
          .append('}');
    }
    return json.isEmpty() ? "" : json.append(']').toString();
  }

  private static String decisionJson(Decision d) {
    return new Line(null)
        .str("phase", d.phase())
        .num("cursor", d.cursor())
        .num("aimIndex", d.aimIndex())
        .raw("aim", "[" + fmt(d.aimX(), 3) + "," + fmt(d.aimZ(), 3) + "]")
        .num("faceYaw", d.faceYaw(), 2)
        .num("faceGap", d.faceGap(), 2)
        .num("aimGap", d.aimGap(), 2)
        .num("flatGap", d.flatGap(), 3)
        .num("gazeNode", d.gazeNode())
        .num("aimYaw", d.aimYaw(), 3)
        .num("aimPitch", d.aimPitch(), 3)
        .str("keys", d.keys())
        .bool("sprint", d.sprint())
        .bool("hop", d.hop())
        .bool("constantJump", d.constantJump())
        .bool("forcedHop", d.forcedHop())
        .num("wallGrind", d.wallGrindTicks())
        .num("wallStall", d.wallStallTicks())
        .num("hopCooldown", d.hopCooldown())
        .raw("dropGap", Double.isNaN(d.dropGap()) ? "null" : fmt(d.dropGap(), 3))
        .bool("sneak", d.sneak())
        .str("water", d.water())
        .bool("ledge", d.ledge())
        .bool("deadzone", d.deadzone())
        .num("airTicks", d.airTicks())
        .bool("ownsLook", d.ownsLook())
        .bool("airDiagonal", d.airDiagonal())
        .str("combatAirSteer", d.combatAirSteer())
        .body();
  }

  private static String inputLetters(LocalPlayer player) {
    final var keys = player.input.keyPresses;
    final StringBuilder letters = new StringBuilder();
    if (keys.forward()) {
      letters.append('W');
    }
    if (keys.left()) {
      letters.append('A');
    }
    if (keys.backward()) {
      letters.append('S');
    }
    if (keys.right()) {
      letters.append('D');
    }
    if (keys.jump()) {
      letters.append('J');
    }
    if (keys.shift()) {
      letters.append('C');
    }
    if (keys.sprint()) {
      letters.append('R');
    }
    return letters.toString();
  }

  private static String effects(LocalPlayer player) {
    final StringBuilder text = new StringBuilder();
    effect(text, player, MobEffects.SPEED, "speed");
    effect(text, player, MobEffects.SLOWNESS, "slowness");
    effect(text, player, MobEffects.JUMP_BOOST, "jump_boost");
    effect(text, player, MobEffects.SLOW_FALLING, "slow_falling");
    effect(text, player, MobEffects.LEVITATION, "levitation");
    return text.toString();
  }

  private static void effect(
      StringBuilder text, LocalPlayer player, Holder<MobEffect> effect, String name) {
    final MobEffectInstance instance = player.getEffect(effect);
    if (instance != null) {
      text.append(text.isEmpty() ? "" : ",")
          .append(name)
          .append(':')
          .append(instance.getAmplifier());
    }
  }

  private static int ping(Minecraft client, LocalPlayer player) {
    if (client.getConnection() == null) {
      return -1;
    }
    final PlayerInfo info = client.getConnection().getPlayerInfo(player.getUUID());
    return info == null ? -1 : info.getLatency();
  }

  private static String drain(Queue<String> queue, boolean quoteEach) {
    final StringBuilder json = new StringBuilder("[");
    String item;
    while ((item = queue.poll()) != null) {
      if (json.length() > 1) {
        json.append(',');
      }
      json.append(quoteEach ? quote(item) : item);
    }
    return json.append(']').toString();
  }

  private static String points(List<Vec3> points) {
    final StringBuilder json = new StringBuilder("[");
    for (Vec3 point : points) {
      if (json.length() > 1) {
        json.append(',');
      }
      json.append(vec(point, 3));
    }
    return json.append(']').toString();
  }

  private static String vec(Vec3 v, int decimals) {
    return "[" + fmt(v.x, decimals) + "," + fmt(v.y, decimals) + "," + fmt(v.z, decimals) + "]";
  }

  private static String fmt(double value, int decimals) {
    if (!Double.isFinite(value)) {
      return "null";
    }
    return String.format(Locale.ROOT, "%." + decimals + "f", value);
  }

  private static String quote(String text) {
    final StringBuilder json = new StringBuilder(text.length() + 2).append('"');
    for (int i = 0; i < text.length(); i++) {
      final char c = text.charAt(i);
      switch (c) {
        case '"' -> json.append("\\\"");
        case '\\' -> json.append("\\\\");
        case '\n' -> json.append("\\n");
        case '\r' -> json.append("\\r");
        case '\t' -> json.append("\\t");
        default -> {
          if (c < 0x20) {
            json.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
          } else {
            json.append(c);
          }
        }
      }
    }
    return json.append('"').toString();
  }

  private static void write(Line line) {
    if (out == null) {
      return;
    }
    try {
      out.write(line.body());
      out.newLine();
    } catch (IOException failure) {
      DebugLog.error(DebugLog.Category.NAV, "telemetry: write failed, stopping (%s)", failure);
      recording = false;
      DebugLog.setNavSink(null);
      out = null;
    }
  }

  /** One JSON object, built by hand: the file is append-only text and never parsed in game. */
  private static final class Line {
    private final StringBuilder json = new StringBuilder("{");

    Line(String type) {
      if (type != null) {
        str("t", type);
      }
    }

    private Line key(String key) {
      if (json.length() > 1) {
        json.append(',');
      }
      json.append('"').append(key).append("\":");
      return this;
    }

    Line str(String key, String value) {
      key(key).json.append(quote(value));
      return this;
    }

    Line num(String key, long value) {
      key(key).json.append(value);
      return this;
    }

    Line num(String key, double value, int decimals) {
      key(key).json.append(fmt(value, decimals));
      return this;
    }

    Line bool(String key, boolean value) {
      key(key).json.append(value);
      return this;
    }

    Line raw(String key, String value) {
      key(key).json.append(value);
      return this;
    }

    String body() {
      return json + "}";
    }
  }
}
