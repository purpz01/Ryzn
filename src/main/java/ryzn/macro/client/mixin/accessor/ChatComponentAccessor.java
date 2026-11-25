package ryzn.macro.client.mixin.accessor;

import java.util.List;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Read-only window into the vanilla chat log, so Ryzn's Skija chat repaints the same lines vanilla
 * would have drawn rather than keeping a second copy of the message state.
 *
 * <p>Vanilla's own {@code forEachLine} is not reused: its {@code LineConsumer} / {@code
 * AlphaCalculator} parameters are package-private types, and widening the whole chat package to
 * borrow a fifteen-line loop costs more than restating it.
 */
@Mixin(ChatComponent.class)
public interface ChatComponentAccessor {

  @Accessor("trimmedMessages")
  List<GuiMessage.Line> ryzn$trimmedMessages();

  @Accessor("chatScrollbarPos")
  int ryzn$chatScrollbarPos();

  @Invoker("getLineHeight")
  int ryzn$getLineHeight();

  @Invoker("getWidth")
  int ryzn$getWidth();

  @Invoker("getScale")
  double ryzn$getScale();
}
