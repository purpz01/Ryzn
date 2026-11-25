package ryzn.macro.client.util.entity;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ResolvableProfile;
import ryzn.macro.client.util.text.MacroText;

public final class MacroEntities {

  private static final Pattern TEXTURE_HASH = Pattern.compile("texture/([a-fA-F0-9]{16,64})");

  private MacroEntities() {}

  /**
   * The canonical {@code textures.minecraft.net/texture/<hash>} skin id of a player entity, decoded
   * from its GameProfile "textures" property (lowercase), or null for non-players / default skins.
   * This is the stable per-NPC id that survives the scrambled entity name a Hypixel player-NPC
   * carries. Capture with {@code /skinid}.
   */
  public static String playerSkinHash(Entity entity) {
    if (!(entity instanceof Player player)) {
      return null;
    }
    final GameProfile profile = player.getGameProfile();
    if (profile == null) {
      return null;
    }
    for (Property property : profile.properties().get("textures")) {
      final String hash = decodeTextureHash(property.value());
      if (hash != null) {
        return hash;
      }
    }
    return null;
  }

  /**
   * The {@code textures.minecraft.net/texture/<hash>} id of the player-head this entity WEARS (head
   * slot first, then main hand), lowercased, or null if it wears no skull. This is the stable
   * per-skull id SkyBlock mobs are built from, and it is what {@code /skullid} prints.
   *
   * <p>Sibling of {@link #playerSkinHash}: that one reads a player's own profile, this one reads an
   * item a mob is holding or wearing. Both decode the same base64 textures property.
   */
  public static String skullTextureId(Entity entity) {
    if (!(entity instanceof LivingEntity living)) {
      return null;
    }
    final String worn = hashFromItem(living.getItemBySlot(EquipmentSlot.HEAD));
    return worn != null ? worn : hashFromItem(living.getItemBySlot(EquipmentSlot.MAINHAND));
  }

  private static String hashFromItem(ItemStack stack) {
    if (stack == null || stack.isEmpty()) {
      return null;
    }
    final ResolvableProfile resolvable = stack.get(DataComponents.PROFILE);
    if (resolvable == null) {
      return null;
    }
    final GameProfile profile = resolvable.partialProfile();
    if (profile == null) {
      return null;
    }
    for (Property property : profile.properties().get("textures")) {
      final String hash = decodeTextureHash(property.value());
      if (hash != null) {
        return hash;
      }
    }
    return null;
  }

  /**
   * Nearest player entity to the block whose skin hash equals {@code skinHash} (within radius), or
   * null.
   */
  public static Entity findNearestPlayerBySkin(
      ClientLevel level, String skinHash, int x, int y, int z, double radius) {
    if (level == null || skinHash == null || skinHash.isEmpty()) {
      return null;
    }
    final double radiusSq = radius * radius;
    Entity best = null;
    double bestSq = Double.MAX_VALUE;
    for (Entity entity : level.entitiesForRendering()) {
      if (entity == null) {
        continue;
      }
      final double distanceSq = distanceToBlockCenterSq(entity, x, y, z);
      if (distanceSq > radiusSq || distanceSq >= bestSq) {
        continue;
      }
      if (skinHash.equals(playerSkinHash(entity))) {
        best = entity;
        bestSq = distanceSq;
      }
    }
    return best;
  }

  private static String decodeTextureHash(String base64) {
    if (base64 == null || base64.isEmpty()) {
      return null;
    }
    try {
      // UTF-8 explicitly: the decoded payload is JSON, and the platform default charset would
      // decode it differently on a non-UTF-8 machine.
      final String json = new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
      final Matcher matcher = TEXTURE_HASH.matcher(json);
      return matcher.find() ? matcher.group(1).toLowerCase(Locale.ROOT) : null;
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  public static String entityNameLower(Entity entity) {
    if (entity == null || entity.getName() == null) {
      return "";
    }
    return MacroText.normalizeSearchText(entity.getName().getString());
  }

  public static Entity findNearestNamedEntity(
      ClientLevel level, String needle, int x, int y, int z, double radius) {
    return findNearestNamedEntity(level, needle, null, x, y, z, radius);
  }

  public static Entity findNearestNamedEntity(
      ClientLevel level, String needle, String fallbackNeedle, int x, int y, int z, double radius) {
    if (level == null) {
      return null;
    }
    final double radiusSq = radius * radius;
    Entity primary = null;
    Entity fallback = null;
    double primaryDistanceSq = Double.MAX_VALUE;
    double fallbackDistanceSq = Double.MAX_VALUE;
    for (Entity entity : level.entitiesForRendering()) {
      if (entity == null) {
        continue;
      }
      final double distanceSq = distanceToBlockCenterSq(entity, x, y, z);
      if (distanceSq > radiusSq) {
        continue;
      }
      final String name = entityNameLower(entity);
      if (name.isEmpty()) {
        continue;
      }
      if (name.contains(needle)) {
        if (distanceSq < primaryDistanceSq) {
          primary = entity;
          primaryDistanceSq = distanceSq;
        }
      } else if (fallbackNeedle != null && name.contains(fallbackNeedle)) {
        if (distanceSq < fallbackDistanceSq) {
          fallback = entity;
          fallbackDistanceSq = distanceSq;
        }
      }
    }
    return primary != null ? primary : fallback;
  }

  public static Entity findNearestNamedEntity(
      ClientLevel level, String needle, String fallbackNeedle, BlockPos pos, double radius) {
    return findNearestNamedEntity(
        level, needle, fallbackNeedle, pos.getX(), pos.getY(), pos.getZ(), radius);
  }

  public static boolean isNearBlockCenter(Entity entity, int x, int y, int z, double radius) {
    return entity != null && distanceToBlockCenterSq(entity, x, y, z) <= radius * radius;
  }

  public static boolean isNearBlockCenter(Entity entity, BlockPos pos, double radius) {
    return isNearBlockCenter(entity, pos.getX(), pos.getY(), pos.getZ(), radius);
  }

  private static double distanceToBlockCenterSq(Entity entity, int x, int y, int z) {
    final double dx = entity.getX() - (x + 0.5);
    final double dy = entity.getY() - (y + 0.5);
    final double dz = entity.getZ() - (z + 0.5);
    return dx * dx + dy * dy + dz * dz;
  }
}
