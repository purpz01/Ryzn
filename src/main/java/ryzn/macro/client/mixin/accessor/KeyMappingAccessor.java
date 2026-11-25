package ryzn.macro.client.mixin.accessor;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(KeyMapping.class)
public interface KeyMappingAccessor {
  @Accessor("clickCount")
  int ryzn$getClickCount();

  @Accessor("clickCount")
  void ryzn$setClickCount(int value);

  @Accessor("key")
  InputConstants.Key ryzn$key();
}
