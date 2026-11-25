package ryzn.macro.client.mixin.accessor;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AbstractContainerScreen.class)
public interface HarpScreenAccessor {
  @Accessor("leftPos")
  int ryzn$getLeftPos();

  @Accessor("topPos")
  int ryzn$getTopPos();

  @Accessor("hoveredSlot")
  void ryzn$setHoveredSlot(Slot slot);
}
