package ryzn.macro.client.mixin.accessor;

import java.io.File;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Options.class)
public interface OptionsKeysAccessor {
  @Mutable
  @Accessor("keyMappings")
  void ryzn$keyMappings(KeyMapping[] mappings);

  @Accessor("optionsFile")
  File ryzn$optionsFile();
}
