package ryzn.macro.client.feature.commands.impl.pathfinder;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import ryzn.macro.client.config.RyznSettings;
import ryzn.macro.client.debug.DebugControl;
import ryzn.macro.client.debug.DebugLog;
import ryzn.macro.client.feature.commands.api.RyznCommandSource;
import ryzn.macro.client.feature.commands.api.RyznCommands;
import ryzn.macro.client.feature.pathfinder.PathRenderer;
import ryzn.macro.client.feature.pathfinder.WalkingNavigation;
import ryzn.macro.client.gui.RyznModulesScreen;
import ryzn.macro.client.util.text.RyznChat;
import ryzn.pathfinding.PathManager;
import ryzn.swift.nativepath.NativePathfinderJNI;

/** Registers walking commands and translates command input into navigation requests. */
public final class PathfindCommand {
  private static boolean previewMode;

  private PathfindCommand() {}

  /** The user-facing part: {@code /ryzn pathfind <x> <y> <z>} and {@code stop}. */
  public static void register(CommandDispatcher<RyznCommandSource> dispatcher) {
    dispatcher.register(
        RyznCommands.literal("ryzn")
            .then(
                RyznCommands.literal("pathfind")
                    .then(RyznCommands.literal("stop").executes(PathfindCommand::stopCmd))
                    .then(
                        RyznCommands.literal("settings")
                            .executes(
                                context -> {
                                  RyznModulesScreen.openWalkingOnNextTick();
                                  return 1;
                                }))
                    .then(
                        RyznCommands.argument("x", DoubleArgumentType.doubleArg())
                            .then(
                                RyznCommands.argument("y", DoubleArgumentType.doubleArg())
                                    .then(
                                        RyznCommands.argument("z", DoubleArgumentType.doubleArg())
                                            .executes(PathfindCommand::run))))));
  }

  /** Diagnostics, registered under {@code /dev}: debug output, probes and calibration. */
  public static void registerDev(CommandDispatcher<RyznCommandSource> dispatcher) {
    dispatcher.register(
        RyznCommands.literal("debug")
            .then(RyznCommands.literal("console").executes(PathfindCommand::debugConsoleCmd))
            .executes(PathfindCommand::debugCmd));
    dispatcher.register(
        RyznCommands.literal("pathfind")
            .then(RyznCommands.literal("preview").executes(PathfindCommand::previewCmd)));
  }

  private static int stopCmd(CommandContext<RyznCommandSource> ctx) {
    WalkingNavigation.stopNavQuiet();
    ctx.getSource().sendFeedback(RyznChat.line("§bPathfinder§7: stopped walking."));
    return 1;
  }

  private static int debugCmd(CommandContext<RyznCommandSource> ctx) {
    final boolean on = DebugControl.toggle();
    final String sink = DebugLog.isChatEcho() ? "chat + log" : "log only";
    ctx.getSource()
        .sendFeedback(
            RyznChat.line(
                "§eRyzn Trace §7is now "
                    + (on ? "§aon" : "§coff")
                    + "§7 - route overlay plus messages to "
                    + sink
                    + "."));
    return 1;
  }

  /**
   * Toggle whether debug output also echoes to chat. Off is log-file only: every line still lands
   * in {@code logs/latest.log}, so nothing is lost for after-the-fact reading, but per-tick trace
   * output stops burying real chat.
   */
  private static int debugConsoleCmd(CommandContext<RyznCommandSource> ctx) {
    final RyznSettings cfg = RyznSettings.get();
    final boolean logOnly = !cfg.pathfinder().isPathDebugLogOnly(); // flipping to this
    cfg.pathfinder().setPathDebugLogOnly(logOnly);
    cfg.save();
    ctx.getSource()
        .sendFeedback(
            RyznChat.line(
                logOnly
                    ? "§eTrace messages§7 now go to logs/latest.log only. "
                        + "Run §f/dev debug console§7 to see them in chat again."
                    : "§eTrace messages§7 now go to chat and logs/latest.log."));
    return 1;
  }

  private static int run(CommandContext<RyznCommandSource> ctx) {
    final RyznCommandSource src = ctx.getSource();
    final LocalPlayer player = src.getPlayer();
    final ClientLevel level = src.getLevel();
    if (player == null || level == null) {
      src.sendError(RyznChat.line("§bPathfinder§7: §cjoin a world first."));
      return 0;
    }
    if (!ensureNative(src)) {
      return 0;
    }

    // Accept float coords (F3 / copied positions) as well as ints - doubleArg() parses both. Floor
    // each to the block that contains it, since the engine plans over the voxel grid.
    final int goalX = Mth.floor(DoubleArgumentType.getDouble(ctx, "x"));
    final int goalY = Mth.floor(DoubleArgumentType.getDouble(ctx, "y"));
    final int goalZ = Mth.floor(DoubleArgumentType.getDouble(ctx, "z"));

    if (!WalkingNavigation.startFromCommand(goalX, goalY, goalZ, previewMode)) {
      final String err = PathManager.getLastError();
      src.sendError(
          RyznChat.line(
              "§bPathfinder§7: §ccould not start the search. §7"
                  + (err != null ? err : "Reason unknown.")));
      return 0;
    }
    return 1;
  }

  private static int previewCmd(CommandContext<RyznCommandSource> ctx) {
    previewMode = !previewMode;
    if (previewMode) {
      PathRenderer.setEnabled(true); // no point planning a route you cannot see
    }
    ctx.getSource()
        .sendFeedback(
            RyznChat.line(
                "§bPathfinder preview §7is now "
                    + (previewMode ? "§aon" : "§coff")
                    + "§7 - "
                    + (previewMode
                        ? "§7/ryzn pathfind will draw the route and NOT walk it."
                        : "§7routes will be walked again.")));
    return 1;
  }

  /** Lazily loads + verifies the native library, reporting any failure to the player. */
  private static boolean ensureNative(RyznCommandSource src) {
    if (!NativePathfinderJNI.isAvailable()) {
      final String err = NativePathfinderJNI.getLoadError();
      src.sendError(
          RyznChat.line(
              "§bPathfinder§7: §cthe engine did not load, so walking routes are off. "
                  + "§7Reason: "
                  + err));
      return false;
    }
    return true;
  }
}
