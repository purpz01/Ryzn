# Ryzn source and API master index

Ryzn is a Minecraft Fabric client mod. Start at the subsystem that owns the state you need, then
use its existing entry point from commands, macros, screens, or mixins.

## Where changes belong

| Work | Owner and entry point |
| --- | --- |
| Minecraft hooks, lifecycle, and event wiring | [Mixins](../src/main/java/ryzn/macro/client/mixin), [RyznRuntime](../src/main/java/ryzn/macro/client/core/RyznRuntime.java), [RyznBootstrap](../src/main/java/ryzn/macro/client/core/RyznBootstrap.java), [RyznModules](../src/main/java/ryzn/macro/client/core/RyznModules.java) |
| Events between features | [Event API](../src/main/java/ryzn/macro/client/core/events/api) and [EventBusImpl](../src/main/java/ryzn/macro/client/core/events/impl/EventBusImpl.java) |
| User and developer commands | [Command API](../src/main/java/ryzn/macro/client/feature/commands/api) and [CommandManagerImpl](../src/main/java/ryzn/macro/client/feature/commands/impl/CommandManagerImpl.java) |
| Macro behavior and registration | [Macro API](../src/main/java/ryzn/macro/client/feature/macros/api), [MacroManagerImpl](../src/main/java/ryzn/macro/client/feature/macros/impl/MacroManagerImpl.java), and each macro's [implementation package](../src/main/java/ryzn/macro/client/feature/macros/impl) |
| Shared combat sessions | [ModuleManager](../src/main/java/ryzn/macro/client/feature/modules/api/ModuleManager.java), [CombatModule](../src/main/java/ryzn/macro/client/feature/modules/impl/combat/CombatModule.java), and [CombatSessionEvent](../src/main/java/ryzn/macro/client/core/events/api/CombatSessionEvent.java) |
| Walking, replans, and route status | [WalkingNavigation](../src/main/java/ryzn/macro/client/feature/pathfinder/WalkingNavigation.java) |
| Movement policy, route execution, and overlays | [PathExecutor and movement policies](../src/main/java/ryzn/macro/client/feature/pathfinder/path), [WalkingRouteBuilder](../src/main/java/ryzn/macro/client/feature/pathfinder/WalkingRouteBuilder.java), [PathRenderer](../src/main/java/ryzn/macro/client/feature/pathfinder/PathRenderer.java) |
| Native search and cached geometry | [PathManager](../src/main/java/ryzn/pathfinding/PathManager.java), [Swift cache](../src/main/java/ryzn/swift/cache), [JNI adapter](../src/main/java/ryzn/swift/nativepath), and [V5 build and licensing](../NativeSrc/NOTICE.md) |
| Look arbitration and camera turns | [RotationManager and LookController](../src/main/java/ryzn/macro/client/feature/rotation/api), [rotation implementation](../src/main/java/ryzn/macro/client/feature/rotation/impl) |
| Settings and persistence | [RyznSettings](../src/main/java/ryzn/macro/client/config/RyznSettings.java), [ConfigManager](../src/main/java/ryzn/macro/client/config/ConfigManager.java), and [ConfigSection](../src/main/java/ryzn/macro/client/config/settings/ConfigSection.java) |
| World changes, failsafes, and reconnect | [WorldChangeDetector](../src/main/java/ryzn/macro/client/world/WorldChangeDetector.java), [failsafe coordinator](../src/main/java/ryzn/macro/client/feature/failsafe), [reconnect and location](../src/main/java/ryzn/macro/client/feature/reconnect) |
| Menu, HUD, world rendering, and input | [GUI](../src/main/java/ryzn/macro/client/gui), [HUD](../src/main/java/ryzn/macro/client/feature/hud), [render utilities](../src/main/java/ryzn/macro/client/render), [input](../src/main/java/ryzn/macro/client/input), [inventory and screen helpers](../src/main/java/ryzn/macro/client/util) |
| Runtime diagnostics and tests | [Debug utilities](../src/main/java/ryzn/macro/client/debug), [Java tests](../src/test/java), [fixtures](../src/test/resources) |

## Using shared APIs

- **Navigation.** On the client thread, call `WalkingNavigation.startToQuiet(x, y, z)` with a
  block goal. A true return means the request was accepted. Use `isSearchPending()` while planning,
  `isRouteDriving()` while the executor holds the route, and `isNavigationActive()` for overall
  activity. Use `stopNavQuiet()` on your feature's stop path if it owns the shared route. The failure
  sequence is a change signal for failed searches. Arrival remains a separate domain condition.
  Native paths return feet Y. `PathManager.RouteData` converts that Y to the floor block and pairs
  the dense points, key nodes, and movement flags from one search.
- **Events.** Use `RyznBootstrap.eventBus()`. Use `@Subscribe` for public instance handlers on a
  registered owner, or `subscribe(EventType.class, handler)` for process-lifetime wiring. Dispatch is
  synchronous on the publishing thread. A listener that touches game state needs a publisher on the
  client thread or an explicit handoff. Keep registration order when a listener relies on an earlier
  phase or on the inventory transfer tick gate.
- **Rotation and input.** Use the `RotationManager` exposed through bootstrap for shared look
  ownership and per-owner turns. Use existing input and inventory helpers for actions that hold
  keys or open screens. Match each acquisition with cleanup on completion, stop, and world change.
- **Features and settings.** Put a feature's phase machine and supporting code in its implementation
  package. Register its live instance through the matching manager. Existing persistent features
  share their instance with `RyznSettings`, so config loads and menu changes alter that same object.
  Keep JSON keys and validation in the owning section and use `RyznSettings.save()` on the live
  instance to queue the debounced write.
- **Commands and integration.** Register commands through the command manager. Parse arguments and
  send feedback in the command adapter, then call the owning service. Put mixin registrations and
  access wideners in [resources](../src/main/resources). Use one owner for shared feature state.
  Add an interface when multiple implementations or a real substitution boundary call for it.

## Data and model assets

Use records for values crossing API boundaries and keep mutable Minecraft state with its owning
service. State coordinate units, ownership of arrays or collections, and client-thread requirements
at the boundary.

ABCurves assets are under [assets/ryzn/abcurves](../src/main/resources/assets/ryzn/abcurves).
The [ABCurves adapter](../src/main/java/ryzn/macro/client/feature/rotation/impl/abcurves) owns
tensor construction, model sessions, seeded sampling, and resource cleanup. Feature callers use the
rotation API. Preserve Direct rotation as the fallback when the model backend cannot load.

For a model change, record its version, source, license, required runtime, and input and output
contract. Keep the resource update with its adapter and parity tests. Use the repository's
[synthetic-fixture generator](../tools/generate_abcurves_reference.py) when reference outputs need
regeneration. Keep model initialization and disk reads outside tick and render callbacks.

## Verification

Run `bash gradlew test` and `bash gradlew check` with Java 25, or use `gradlew.bat` on Windows.
Use the wrapper’s `runClient` task for lifecycle, mixin, and input checks that need Minecraft. Exercise path start, stop, replan, and world change when
changing navigation. Exercise both rotation modes when changing model or camera code.
