package ryzn.macro.client.util.inventory;

import java.util.Optional;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.Tool;
import net.minecraft.world.level.block.Block;

/**
 * Detects vanilla-style digger tools. 26.3 data-drives tools: a shovel or hoe is a plain Item whose
 * Tool component carries a rule over the matching mineable tag - there is no item class left to
 * test. SkyBlock custom items usually match by display name instead, which stays the caller's
 * fallback.
 */
public final class VanillaTools {

  private VanillaTools() {}

  public static boolean isShovel(ItemStack stack) {
    return hasToolRule(stack, BlockTags.MINEABLE_WITH_SHOVEL);
  }

  public static boolean isHoe(ItemStack stack) {
    return hasToolRule(stack, BlockTags.MINEABLE_WITH_HOE);
  }

  private static boolean hasToolRule(ItemStack stack, TagKey<Block> tag) {
    if (stack == null || stack.isEmpty()) {
      return false;
    }
    final Tool tool = stack.get(DataComponents.TOOL);
    if (tool == null) {
      return false;
    }
    for (Tool.Rule rule : tool.rules()) {
      final Optional<TagKey<Block>> key = rule.blocks().unwrapKey();
      if (key.isPresent() && key.get() == tag) {
        return true;
      }
    }
    return false;
  }
}
