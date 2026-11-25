package ryzn.macro.client.feature.macros.impl.tree;

import lombok.Getter;
import lombok.experimental.Accessors;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

@Accessors(fluent = true)
public enum TreeLogType {
  OAK(
      "oak",
      "Oak",
      "oak",
      Blocks.OAK_LOG,
      Blocks.OAK_WOOD,
      Blocks.STRIPPED_OAK_LOG,
      Blocks.STRIPPED_OAK_WOOD),
  BIRCH(
      "birch",
      "Birch",
      "birch",
      Blocks.BIRCH_LOG,
      Blocks.BIRCH_WOOD,
      Blocks.STRIPPED_BIRCH_LOG,
      Blocks.STRIPPED_BIRCH_WOOD),
  SPRUCE(
      "spruce",
      "Spruce",
      "spruce",
      Blocks.SPRUCE_LOG,
      Blocks.SPRUCE_WOOD,
      Blocks.STRIPPED_SPRUCE_LOG,
      Blocks.STRIPPED_SPRUCE_WOOD),
  ACACIA(
      "acacia",
      "Acacia",
      "acacia",
      Blocks.ACACIA_LOG,
      Blocks.ACACIA_WOOD,
      Blocks.STRIPPED_ACACIA_LOG,
      Blocks.STRIPPED_ACACIA_WOOD),
  DARK_OAK(
      "dark_oak",
      "Dark Oak",
      "dark oak",
      Blocks.DARK_OAK_LOG,
      Blocks.DARK_OAK_WOOD,
      Blocks.STRIPPED_DARK_OAK_LOG,
      Blocks.STRIPPED_DARK_OAK_WOOD),
  JUNGLE(
      "jungle",
      "Jungle",
      "jungle",
      Blocks.JUNGLE_LOG,
      Blocks.JUNGLE_WOOD,
      Blocks.STRIPPED_JUNGLE_LOG,
      Blocks.STRIPPED_JUNGLE_WOOD);

  private static final String[] labels = {"Oak", "Birch", "Spruce", "Acacia", "Dark Oak", "Jungle"};
  private static final TreeLogType[] types = {OAK, BIRCH, SPRUCE, ACACIA, DARK_OAK, JUNGLE};

  @Getter private final String persistedId;
  @Getter private final String displayName;
  @Getter private final String statusName;
  private final Block[] blocks;

  TreeLogType(String persistedId, String displayName, String statusName, Block... blocks) {
    this.persistedId = persistedId;
    this.displayName = displayName;
    this.statusName = statusName;
    this.blocks = blocks;
  }

  public boolean isOak() {
    return this == OAK;
  }

  public int uiIndex() {
    return ordinal() + 1;
  }

  public boolean matches(BlockState state) {
    if (state == null) {
      return false;
    }
    final Block block = state.getBlock();
    for (Block allowed : blocks) {
      if (block == allowed) {
        return true;
      }
    }
    return false;
  }

  public boolean matchesItem(ItemStack stack) {
    if (stack == null || stack.isEmpty()) {
      return false;
    }
    for (Block allowed : blocks) {
      if (stack.is(allowed.asItem())) {
        return true;
      }
    }
    return false;
  }

  public static TreeLogType fromUiIndex(int index) {
    final int clamped = Math.max(1, Math.min(types.length, index));
    return types[clamped - 1];
  }

  public static TreeLogType fromPersistedId(String id) {
    if (id == null) {
      return OAK;
    }
    return switch (id.trim().toLowerCase().replace(' ', '_').replace('-', '_')) {
      case "birch" -> BIRCH;
      case "spruce" -> SPRUCE;
      case "acacia" -> ACACIA;
      case "dark_oak" -> DARK_OAK;
      case "jungle" -> JUNGLE;
      default -> OAK;
    };
  }

  public static String[] labels() {
    return labels.clone();
  }
}
