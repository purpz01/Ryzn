# Ryzn

A Minecraft 26.2 Fabric client mod for Auto Level 7 and walking pathfinding. Install Fabric Loader 0.19.3 or newer and Fabric API for Minecraft 26.2, then put `ryzn.jar` in the instance’s `mods` folder. The Hypixel Mod API bridge is bundled. Build from source with Java 25 and `gradlew.bat build`.

- Press Right Shift in-game to open Modules.
- `/ryzn level7 start` starts Auto Level 7.
- `/ryzn level7 status` displays the current stage.
- `/ryzn level7 stop` or Escape stops automation.
- `/ryzn pathfind <x> <y> <z>` and `/ryzn pathfind stop` control the walking pathfinder.
- **Rotations** selects ABCurves or Direct camera movement. ABCurves uses Minecraft's mouse
  sensitivity and a repeatable planner seed. Existing rotation preferences are backed up in
  `rotations.legacy_settings` on the next settings save.

Ryzn stores its settings under `config/ryzn`. It bundles the V5 navigation engine for Windows,
Linux, and macOS on x86-64 and ARM64. V5PathJNI is licensed under GNU GPL v3.
See [the V5PathJNI notice](NativeSrc/NOTICE.md) for the pinned upstream revision,
source, license, and build instructions. The distribution JAR includes the
engine source and GPL text under `META-INF`.
To rebuild a native, configure with `cmake -S NativeSrc -B build/v5-native` and build with
`cmake --build build/v5-native --config Release`. Stage the resulting `V5PathJNI` library under
`src/main/resources/assets/ryzn/natives/<os>/<arch>/` before building the jar.

For Skija menus, copy the contents of `build/skija/0.143.16/` into
`.minecraft/skija/0.143.16/` in the same instance as `mods/ryzn.jar`. The runtime includes
`skija-shared`, `types`, and the native jar for your platform. Ryzn uses vanilla menu rendering
when Skija is unavailable.

The ABCurves implementation uses the B2-23 Continuous Planner models by Optima Manent. The
bundled models and license are under `assets/ryzn/abcurves`. The reference fixtures use
synthetic target schedules and can be regenerated with `tools/generate_abcurves_reference.py`.
The bundled ONNX Runtime supports Windows x86-64, Linux x86-64 and ARM64, and macOS ARM64.
Ryzn uses Direct rotation if the planner cannot load on the current platform.
