package ryzn.macro.client.core.events.api;

import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * An item tooltip was built; listeners may edit {@code lines} in place.
 *
 * @param stack the item being described
 * @param context the vanilla tooltip context
 * @param flag the vanilla tooltip flag
 * @param lines the mutable tooltip lines
 */
public record ItemTooltipEvent(
    ItemStack stack, Item.TooltipContext context, TooltipFlag flag, List<Component> lines)
    implements RuntimeEvent {}
