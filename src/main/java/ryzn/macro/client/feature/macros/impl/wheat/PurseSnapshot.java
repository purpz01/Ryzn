package ryzn.macro.client.feature.macros.impl.wheat;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

public record PurseSnapshot(BigDecimal coins, String displayText, long checkedAtMillis) {
  private static final DecimalFormat format =
      new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.US));

  public static PurseSnapshot unknown() {
    return new PurseSnapshot(null, "Purse ?", 0L);
  }

  public static PurseSnapshot of(BigDecimal coins) {
    return new PurseSnapshot(coins, format.format(coins), System.currentTimeMillis());
  }

  public boolean known() {
    return coins != null;
  }
}
