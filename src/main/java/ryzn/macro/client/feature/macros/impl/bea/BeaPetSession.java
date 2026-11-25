package ryzn.macro.client.feature.macros.impl.bea;

import lombok.AccessLevel;
import lombok.Getter;

final class BeaPetSession {

  private boolean beaMenuOpened;

  @Getter(AccessLevel.PACKAGE)
  private boolean petPurchaseRequested;

  @Getter(AccessLevel.PACKAGE)
  private boolean hotbarMoveRequested;

  private int equipDelayTicks;
  private boolean petAddedToMenu;
  private boolean petsCommandSent;
  private boolean petsMenuOpened;
  private boolean petMenuClicked;
  private boolean petSummoned;

  boolean wasBeaMenuOpened() {
    return beaMenuOpened;
  }

  void markBeaMenuOpened() {
    beaMenuOpened = true;
  }

  void clearBeaMenuOpened() {
    beaMenuOpened = false;
  }

  void markPetPurchaseRequested() {
    petPurchaseRequested = true;
  }

  void resetPetPurchaseRequest() {
    petPurchaseRequested = false;
  }

  void markHotbarMoveRequested() {
    hotbarMoveRequested = true;
  }

  boolean shouldWaitForEquipDelay(int requiredTicks) {
    return equipDelayTicks++ < requiredTicks;
  }

  void resetForEquip() {
    hotbarMoveRequested = false;
    equipDelayTicks = 0;
  }

  boolean wasPetAddedToMenu() {
    return petAddedToMenu;
  }

  void markPetAddedToMenu() {
    petAddedToMenu = true;
  }

  boolean wasPetsCommandSent() {
    return petsCommandSent;
  }

  void markPetsCommandSent() {
    petsCommandSent = true;
  }

  boolean wasPetsMenuOpened() {
    return petsMenuOpened;
  }

  void markPetsMenuOpened() {
    petsMenuOpened = true;
  }

  void clearPetsMenuOpened() {
    petsMenuOpened = false;
  }

  void resetForOpenPetsMenu() {
    petsCommandSent = false;
    petsMenuOpened = false;
  }

  boolean wasPetMenuClicked() {
    return petMenuClicked;
  }

  void markPetMenuClicked() {
    petMenuClicked = true;
  }

  void resetPetMenuClick() {
    petMenuClicked = false;
  }

  boolean wasPetSummoned() {
    return petSummoned;
  }

  void markPetSummoned() {
    petSummoned = true;
  }

  void reset() {
    beaMenuOpened = false;
    petPurchaseRequested = false;
    hotbarMoveRequested = false;
    equipDelayTicks = 0;
    petAddedToMenu = false;
    petsCommandSent = false;
    petsMenuOpened = false;
    petMenuClicked = false;
    petSummoned = false;
  }
}
