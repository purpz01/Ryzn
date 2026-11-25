package ryzn.macro.client.feature.macros.impl.foraging;

import lombok.AccessLevel;
import lombok.Getter;

final class HarpRouteAttempts {

  @Getter(AccessLevel.PACKAGE)
  private boolean pathRequested;

  private int melodyPaths;
  private int harpOpens;

  void enterState() {
    pathRequested = false;
  }

  void markPathRequested() {
    pathRequested = true;
  }

  void enterMelodyPath() {
    melodyPaths++;
  }

  boolean exceededMelodyPaths(int maximumAttempts) {
    return melodyPaths > maximumAttempts;
  }

  void enterHarpOpen() {
    harpOpens++;
  }

  boolean exceededHarpOpens(int maximumAttempts) {
    return harpOpens > maximumAttempts;
  }

  void clearHarpOpens() {
    harpOpens = 0;
  }

  void reset() {
    pathRequested = false;
    melodyPaths = 0;
    harpOpens = 0;
  }
}
