package ryzn.macro.client.feature.macros.impl.foraging;

/** A held-white pad can contain consecutive notes when another wool block is directly above it. */
final class HarpPadState {
  private boolean fired;
  private boolean nextNoteWaiting;
  private long firedBeat;

  boolean ready(String pad, long beat) {
    if (AutoHarpMacro.shouldRearm(pad)) {
      fired = false;
    }
    return AutoHarpMacro.isWhitePad(pad) && (!fired || nextNoteWaiting && beat > firedBeat);
  }

  void fired(long beat, boolean noteDirectlyAbove) {
    fired = true;
    firedBeat = beat;
    nextNoteWaiting = noteDirectlyAbove;
  }
}
