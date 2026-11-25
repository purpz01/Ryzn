package ryzn.macro.client.feature.macros.impl.bea;

import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

@RequiredArgsConstructor
enum BeaCraftMaterial {
  COAL("coal", () -> Items.COAL, () -> Items.COAL_BLOCK),
  GOLD("gold", () -> Items.GOLD_INGOT, () -> Items.GOLD_BLOCK);

  private final String label;
  private final Supplier<Item> input;
  private final Supplier<Item> output;

  String label() {
    return label;
  }

  Item input() {
    return input.get();
  }

  Item output() {
    return output.get();
  }

  int craftedCount(BeaCraftSession session) {
    return this == COAL ? session.coalBlocksCrafted() : session.goldBlocksCrafted();
  }

  void recordCrafted(BeaCraftSession session) {
    if (this == COAL) {
      session.recordCoalBlockCrafted();
    } else {
      session.recordGoldBlockCrafted();
    }
  }
}
