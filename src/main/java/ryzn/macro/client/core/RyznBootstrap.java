package ryzn.macro.client.core;

import com.mojang.logging.LogUtils;
import java.util.concurrent.atomic.AtomicReference;
import lombok.Getter;
import lombok.experimental.Accessors;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import ryzn.macro.client.MacroInput;
import ryzn.macro.client.config.ConfigManager;
import ryzn.macro.client.core.events.api.EventBus;
import ryzn.macro.client.core.events.impl.EventBusImpl;
import ryzn.macro.client.feature.commands.api.CommandManager;
import ryzn.macro.client.feature.commands.impl.CommandManagerImpl;
import ryzn.macro.client.feature.failsafe.api.FailsafeManager;
import ryzn.macro.client.feature.failsafe.impl.FailsafeManagerImpl;
import ryzn.macro.client.feature.macros.api.MacroManager;
import ryzn.macro.client.feature.macros.impl.MacroManagerImpl;
import ryzn.macro.client.feature.modules.api.ModuleManager;
import ryzn.macro.client.feature.modules.impl.ModuleManagerImpl;
import ryzn.macro.client.feature.reconnect.MacroReconnect;
import ryzn.macro.client.feature.rotation.api.RotationManager;
import ryzn.macro.client.feature.rotation.impl.RotationManagerImpl;

@Accessors(fluent = true)
public final class RyznBootstrap {
  private static final Logger logger = LogUtils.getLogger();

  @Getter
  private static final EventBus eventBus =
      new EventBusImpl(RyznBootstrap::fail, RyznBootstrap::isReady);

  @Getter private static final ModuleManager moduleManager = new ModuleManagerImpl();
  @Getter private static final CommandManager commandManager = new CommandManagerImpl();
  @Getter private static final MacroManager macroManager = new MacroManagerImpl();
  @Getter private static final FailsafeManager failsafeManager = new FailsafeManagerImpl();
  @Getter private static final RotationManager rotationManager = new RotationManagerImpl();
  private static final AtomicReference<State> state = new AtomicReference<>(State.NEW);
  private static boolean clientStarted;
  private static boolean modulesStarted;
  private static Boolean originalPauseOnLostFocus;

  public enum State {
    NEW,
    WAITING_FOR_CLIENT,
    STARTING,
    READY,
    FAILED,
    STOPPED
  }

  private RyznBootstrap() {}

  public static State state() {
    return state.get();
  }

  public static boolean isReady() {
    return state() == State.READY;
  }

  public static void start() {
    if (state.compareAndSet(State.NEW, State.WAITING_FOR_CLIENT)) {
      logger.info("Starting Ryzn");
    }
  }

  private static void initializeModules() {
    Minecraft client = Minecraft.getInstance();
    if (client == null || client.options == null || client.gui == null) {
      return;
    }
    if (!state.compareAndSet(State.WAITING_FOR_CLIENT, State.STARTING)) {
      return;
    }
    try {
      RyznModules.prepare();
      if (state() != State.STARTING) {
        deactivate("Ryzn stopped");
        return;
      }
      ConfigManager.initLocal();
      finishInitialization();
    } catch (Exception | LinkageError failure) {
      fail("module preparation", failure);
    }
  }

  private static void finishInitialization() {
    if (state() != State.STARTING) {
      return;
    }
    try {
      RyznModules.register();
      if (!state.compareAndSet(State.STARTING, State.READY)) {
        deactivate("Ryzn stopped");
        return;
      }
      if (clientStarted) {
        stopAutomation("client startup");
      }

    } catch (Exception | LinkageError failure) {
      if (terminal(state())) {
        deactivate("startup cancelled");
      } else {
        fail("module initialization", failure);
      }
    }
  }

  static void onModulesLoaded() {
    modulesStarted = true;
  }

  static void keepBackgroundTicks(Minecraft client) {
    if (originalPauseOnLostFocus == null) {
      originalPauseOnLostFocus = client.options.pauseOnLostFocus;
    }
    client.options.pauseOnLostFocus = false;
  }

  public static void onClientStarted() {
    clientStarted = true;
    if (state() == State.WAITING_FOR_CLIENT) {
      initializeModules();
    } else if (isReady()) {
      stopReadyAutomation("client startup");
    }
  }

  public static void onClientAvailable(Minecraft client) {
    if (state() == State.WAITING_FOR_CLIENT && client.options != null && client.gui != null) {
      initializeModules();
    }
  }

  private static boolean terminal(State value) {
    return value == State.STOPPED || value == State.FAILED;
  }

  public static void fail(String operation, Throwable failure) {
    State previous = state.getAndUpdate(current -> terminal(current) ? current : State.FAILED);
    if (terminal(previous)) {
      return;
    }
    logger.error("Ryzn disabled after failure in {}", operation, failure);
    Minecraft client = Minecraft.getInstance();
    if (client != null) {
      client.execute(() -> deactivate("Ryzn failure"));
    }
  }

  private static void stopReadyAutomation(String reason) {
    try {
      stopAutomation(reason);
    } catch (Exception | LinkageError failure) {
      fail("automation cleanup", failure);
    }
  }

  private static void stopAutomation(String reason) {
    if (!modulesStarted) {
      return;
    }
    try {
      MacroReconnect.disarm();
      RyznBootstrap.failsafeManager().stopAllMacros(reason);
    } finally {
      Minecraft client = Minecraft.getInstance();
      if (client != null) {
        MacroInput.releaseAllKeys(client.options);
      }
    }
  }

  private static void deactivate(String reason) {
    close("automation", () -> stopAutomation(reason));
    if (modulesStarted) {
      Minecraft client = Minecraft.getInstance();
      if (originalPauseOnLostFocus != null && client != null && client.options != null) {
        client.options.pauseOnLostFocus = originalPauseOnLostFocus;
        originalPauseOnLostFocus = null;
        ryzn.macro.client.MacroMouseUnlock.reset(client);
      }
    }
  }

  private static void close(String name, Runnable action) {
    try {
      action.run();
    } catch (Exception | LinkageError failure) {
      logger.warn("Ryzn {} shutdown failed", name, failure);
    }
  }

  public static synchronized void shutdown() {
    if (state() == State.STOPPED) {
      return;
    }
    if (isReady()) {
      try {
        ConfigManager.shutdown();
      } catch (Exception | LinkageError failure) {
        logger.warn("Ryzn final settings save failed", failure);
      }
      try {
        ryzn.swift.cache.CachedWorld.shutdown();
      } catch (Exception | LinkageError failure) {
        logger.warn("Ryzn final pathfinder cache save failed", failure);
      }
    }
    if (state.getAndSet(State.STOPPED) == State.STOPPED) {
      return;
    }
    deactivate("client shutdown");
  }
}
