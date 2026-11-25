package ryzn.macro.client.mixin.accessor;

import net.minecraft.client.gui.components.EditBox;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(EditBox.class)
public interface RyznEditBoxAccessor {
  @Accessor("displayPos")
  int ryzn$displayPos();

  @Accessor("highlightPos")
  int ryzn$highlightPos();
}
