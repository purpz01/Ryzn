package ryzn.macro.client.feature.macros.impl.tree;

import java.util.List;
import net.minecraft.core.BlockPos;

/**
 * A connected clump of selected log blocks found inside the chop scan area (see {@link
 * LogScanner}). {@code base} is the lowest log in the clump (the trunk foot the bot mines from the
 * ground); {@code centroid*} is the average position; {@code size} is the block count used for the
 * density gate. With a Treecapitator a single break on any log fells the whole connected clump, so
 * the macro only needs to reach and hit one log.
 *
 * @param blocks every log in the clump
 * @param base the lowest log, the trunk foot mined from the ground
 * @param centroidX the average X of the clump's logs
 * @param centroidY the average Y of the clump's logs
 * @param centroidZ the average Z of the clump's logs
 * @param size the number of logs, used by the density gate
 */
public record LogCluster(
    List<BlockPos> blocks,
    BlockPos base,
    double centroidX,
    double centroidY,
    double centroidZ,
    int size) {}
