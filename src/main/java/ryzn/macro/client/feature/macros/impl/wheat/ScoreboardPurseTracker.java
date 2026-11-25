package ryzn.macro.client.feature.macros.impl.wheat;

import java.math.BigDecimal;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import ryzn.macro.client.feature.hud.SkyBlockHud;

public final class ScoreboardPurseTracker {
  private static final Pattern PURSE =
      Pattern.compile("(?i)(?:purse|piggy)\\s*:\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)([kmb]?)");
  private PurseSnapshot last = PurseSnapshot.unknown();
  private int ticks;

  public PurseSnapshot lastPurse() {
    return last;
  }

  public void tick(Minecraft mc) {
    if (++ticks % 40 == 0) {
      checkNow(mc);
    }
  }

  public void checkNow(Minecraft mc) {
    last = PurseSnapshot.unknown();
    if (mc.player == null || mc.level == null) {
      return;
    }
    for (var row : SkyBlockHud.sidebarRows()) {
      var match = PURSE.matcher(row.getString().replaceAll("§.", ""));
      if (!match.find()) {
        continue;
      }
      long factor =
          switch (match.group(2).toLowerCase(java.util.Locale.ROOT)) {
            case "k" -> 1000;
            case "m" -> 1000000;
            case "b" -> 1000000000;
            default -> 1;
          };
      last =
          PurseSnapshot.of(
              new BigDecimal(match.group(1).replace(",", "")).multiply(BigDecimal.valueOf(factor)));
      return;
    }
  }
}
