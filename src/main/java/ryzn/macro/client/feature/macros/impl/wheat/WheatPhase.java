package ryzn.macro.client.feature.macros.impl.wheat;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
enum WheatPhase {
  IDLE("Idle"),
  PATHING_TO_ZONE("Walking to zone"),
  HARVESTING("Continuous sweep"),
  AUTO_BUY_PATHING("Walking to Farm Merchant"),
  AUTO_BUY_INTERACT("Opening Farm Merchant"),
  AUTO_BUY_PURCHASE("Buying Rookie Hoe"),
  AUTO_SELL_PATHING("Walking to Farm Merchant"),
  AUTO_SELL_INTERACT("Opening Farm Merchant"),
  AUTO_SELLING("Selling wheat/seeds"),
  LOBBY_HOP_SEND_IS("Lobby hop: /is"),
  LOBBY_HOP_WAIT_IS("Lobby hop: waiting"),
  LOBBY_HOP_SEND_HUB("Lobby hop: /hub"),
  LOBBY_HOP_SETTLE("Lobby hop: settling"),
  LOBBY_HOP_RETURN_TO_ZONE("Returning to Zone 1"),
  REPATHING("Recovering"),
  PAUSED("Paused");

  private final String label;

  String label() {
    return label;
  }
}
