package ryzn.macro.client.mixin.accessor;

import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LocalPlayer.class)
public interface LocalPlayerServerStateAccessor {

  @Accessor("xLast")
  double ryzn$getServerX();

  @Accessor("yLast")
  double ryzn$getServerY();

  @Accessor("zLast")
  double ryzn$getServerZ();

  @Accessor("crouching")
  boolean ryzn$isServerCrouching();
}
