package ryzn.macro.client.mixin.accessor;

import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Slot.class)
public interface SlotAccessor {

  @Mutable
  @Accessor("x")
  void ryzn$setX(int value);

  @Mutable
  @Accessor("y")
  void ryzn$setY(int value);
}
