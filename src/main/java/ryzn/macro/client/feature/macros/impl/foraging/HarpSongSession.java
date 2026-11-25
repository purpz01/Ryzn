package ryzn.macro.client.feature.macros.impl.foraging;

import java.util.HashSet;
import java.util.Set;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.experimental.Accessors;

@Accessors(fluent = true)
final class HarpSongSession {

  private final Set<String> completedNames = new HashSet<>();
  private int selectedSongIndex = -1;

  @Getter(AccessLevel.PACKAGE)
  private int currentSongSlot = -1;

  private String lastSongName;
  private String lastScoredSongName;

  @Getter(AccessLevel.PACKAGE)
  private int retries;

  private volatile boolean songCompleted;
  private volatile boolean scoreSeen;
  private volatile boolean harpBusy;
  private volatile boolean songMissing;

  @Getter(AccessLevel.PACKAGE)
  private int lastScore = -1;

  private int ticksSinceCompletion;

  @Getter(AccessLevel.PACKAGE)
  private String lastChatLine = "";

  void selectSong(int songIndex, int slotIndex, String displayName) {
    selectedSongIndex = songIndex;
    currentSongSlot = slotIndex;
    lastSongName = displayName;
    retries = 0;
  }

  int selectedSongNumber() {
    return selectedSongIndex < 0 ? 0 : selectedSongIndex + 1;
  }

  String displaySongName() {
    if (lastSongName != null && !lastSongName.isBlank()) {
      return lastSongName;
    }
    if (lastScoredSongName != null && !lastScoredSongName.isBlank()) {
      return lastScoredSongName;
    }
    return "-";
  }

  void rememberScoredSongName(String songName) {
    if (songName != null && !songName.isBlank()) {
      lastScoredSongName = songName;
    }
  }

  void markBusy(String chatLine) {
    harpBusy = true;
    lastChatLine = chatLine;
  }

  void markSongCompleted(String chatLine) {
    songCompleted = true;
    lastChatLine = chatLine;
  }

  void markScore(int score, String chatLine) {
    lastScore = score;
    scoreSeen = true;
    songCompleted = true;
    lastChatLine = chatLine;
  }

  /** The harp told us to pick a song, so the menu click never registered server-side. */
  void markSongMissing(String chatLine) {
    songMissing = true;
    lastChatLine = chatLine;
  }

  boolean wasSongMissing() {
    return songMissing;
  }

  void clearSongMissing() {
    songMissing = false;
  }

  boolean wasHarpBusy() {
    return harpBusy;
  }

  void clearBusy() {
    harpBusy = false;
  }

  boolean wasSongCompleted() {
    return songCompleted;
  }

  boolean wasScoreSeen() {
    return scoreSeen;
  }

  boolean shouldEvaluateResult(int scoreWaitTicks) {
    return scoreSeen || ++ticksSinceCompletion > scoreWaitTicks;
  }

  void resetCompletionWait() {
    ticksSinceCompletion = 0;
  }

  boolean isSuccessful(int minimumScore) {
    return songCompleted && scoreSeen && lastScore >= minimumScore;
  }

  void markSelectedSongComplete(String normalizedName) {
    if (lastSongName != null && normalizedName != null) {
      completedNames.add(normalizedName);
    }
  }

  boolean isSongComplete(String normalizedName) {
    return completedNames.contains(normalizedName);
  }

  int completedSongCount() {
    return completedNames.size();
  }

  int recordRetry() {
    return ++retries;
  }

  void resetRetries() {
    retries = 0;
  }

  void resetAttempt() {
    songCompleted = false;
    scoreSeen = false;
    harpBusy = false;
    songMissing = false;
    ticksSinceCompletion = 0;
  }

  void reset() {
    completedNames.clear();
    selectedSongIndex = -1;
    currentSongSlot = -1;
    lastSongName = null;
    lastScoredSongName = null;
    retries = 0;
    lastScore = -1;
    lastChatLine = "";
    resetAttempt();
  }
}
