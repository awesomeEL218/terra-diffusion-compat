package com.github.xandergos.terraindiffusionmc.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.event.level.ChunkEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RiverFlowFixer {
    private static final Logger LOG = LoggerFactory.getLogger(RiverFlowFixer.class);

    private static final Direction[] CHECK_DIRS = {
            Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST
    };

    private RiverFlowFixer() {
    }

    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!(event.getChunk() instanceof LevelChunk chunk)) return;

        ChunkPos cp = chunk.getPos();
        int minX = cp.getMinBlockX();
        int minZ = cp.getMinBlockZ();
        int maxX = minX + 15;
        int maxZ = minZ + 15;
        int minY = chunk.getMinBuildHeight();
        int maxY = chunk.getMaxBuildHeight();

        int sourcesFound = 0;
        int scheduled = 0;
        int skippedUnloadedNeighbor = 0;

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos neighborPos = new BlockPos.MutableBlockPos();

        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int x = minX + dx;
                int z = minZ + dz;
                boolean onBorder = dx == 0 || dx == 15 || dz == 0 || dz == 15;

                // Scan the full column for this chunk directly off the chunk's
                // storage (cheap, no cross-chunk lookups) rather than trusting
                // a single cached "water level" per column.
                for (int y = minY; y < maxY; y++) {
                    FluidState fluid = chunk.getFluidState(x, y, z);
                    if (fluid.isEmpty() || !fluid.isSource()) continue;

                    sourcesFound++;
                    pos.set(x, y, z);

                    boolean hasAirNeighbor = false;
                    boolean unloadedNeighborSeen = false;

                    for (Direction dir : CHECK_DIRS) {
                        neighborPos.setWithOffset(pos, dir);

                        boolean crossesChunkBoundary = onBorder
                                && (neighborPos.getX() < minX || neighborPos.getX() > maxX
                                || neighborPos.getZ() < minZ || neighborPos.getZ() > maxZ);

                        if (crossesChunkBoundary) {
                            // NEVER force-load/generate a neighbor chunk just to
                            // peek at it here -- that's what was causing the
                            // runaway generation cascade and crash. Only read
                            // across the boundary if that chunk is already loaded;
                            // otherwise skip this neighbor and let that chunk's
                            // own load pass handle it later.
                            int ncx = neighborPos.getX() >> 4;
                            int ncz = neighborPos.getZ() >> 4;
                            if (!level.hasChunk(ncx, ncz)) {
                                unloadedNeighborSeen = true;
                                continue;
                            }
                        }

                        if (level.getBlockState(neighborPos).isAir()) {
                            hasAirNeighbor = true;
                            break;
                        }
                    }

                    if (unloadedNeighborSeen && !hasAirNeighbor) {
                        skippedUnloadedNeighbor++;
                    }

                    if (!hasAirNeighbor) continue;

                    level.scheduleTick(pos.immutable(), fluid.getType(), 1);
                    scheduled++;
                }
            }
        }

        LOG.info("RiverFlowFixer: chunk {} -> {} source blocks scanned, {} scheduled to flow "
                + "(had an air neighbor), {} border columns skipped pending an unloaded neighbor chunk",
                cp, sourcesFound, scheduled, skippedUnloadedNeighbor);
    }
}