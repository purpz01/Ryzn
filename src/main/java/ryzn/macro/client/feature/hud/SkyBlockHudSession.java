package ryzn.macro.client.feature.hud;

import java.util.function.Supplier;

/** Avoids reparsing the sidebar more than once per world tick for macro area checks. */
final class SkyBlockHudSession {

  private int worldId;
  private int areaTick = -1;
  private String area;
  private boolean areaLoaded;

  String currentArea(int currentWorldId, int currentTick, Supplier<String> areaReader) {
    enterWorld(currentWorldId);
    if (!areaLoaded || currentTick != areaTick) {
      area = areaReader.get();
      areaTick = currentTick;
      areaLoaded = true;
    }
    return area;
  }

  private void enterWorld(int currentWorldId) {
    if (worldId == currentWorldId) {
      return;
    }
    worldId = currentWorldId;
    areaTick = -1;
    area = null;
    areaLoaded = false;
  }
}
