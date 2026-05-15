package net.bitbylogic.packetblocks.util;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.world.chunk.LightData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateLight;
import lombok.NonNull;
import net.bitbylogic.packetblocks.PacketBlocks;
import net.bitbylogic.packetblocks.block.PacketBlock;
import net.bitbylogic.packetblocks.block.PacketBlockHolder;
import net.bitbylogic.packetblocks.block.PacketBlockManager;
import net.bitbylogic.packetblocks.group.PacketBlockGroup;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;

import java.util.BitSet;
import java.util.Collection;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

public class PacketBlockUtil {

    private static final int LIGHT_SECTION_HEIGHT = 16;
    private static final int LIGHT_SECTION_VOLUME = 16 * 16 * 16;
    private static final int LIGHT_ARRAY_SIZE = LIGHT_SECTION_VOLUME / 2;
    private static final int LIGHT_MASK_SECTION_OFFSET = 1;
    private static final int SINGLE_LIGHT_SECTION_COUNT = 1;
    private static final boolean EXCLUDE_MAX_BLOCK_Y = false;
    private static final boolean EXCLUDE_BIOME_DATA = false;
    private static final boolean EXCLUDE_TEMPERATURE_DATA = false;
    private static final boolean INCLUDE_LIGHT_DATA = true;
    private static final boolean TRUST_EDGES = true;

    public static BlockData getBlockData(@Nullable Player player, @NonNull Location location) {
        if(location.getWorld() == null) {
            return Material.AIR.createBlockData();
        }

        Optional<PacketBlockHolder<?, ?>> optionalHolder = PacketBlocks.getInstance().getBlockManager().getBlock(location);

        if(optionalHolder.isEmpty()) {
            return location.getBlock().getBlockData();
        }

        PacketBlockHolder<?, ?> packetBlockHolder = optionalHolder.get();

        if (packetBlockHolder instanceof PacketBlock packetBlock) {
            return packetBlock.getData();
        }

        if(packetBlockHolder instanceof PacketBlockGroup group) {
            Optional<BlockData> optionalData = group.getDataAt(player, location);

            if(optionalData.isEmpty()) {
                return location.getBlock().getBlockData();
            }

            return optionalData.get();
        }

        return location.getBlock().getBlockData();
    }

    /**
     * Retrieves the material type of the block at a specific location for a given player.
     * This method first checks for any custom packet-based block at the provided location
     * and, if present, determines the block type specific to the player. If no custom block exists,
     * it returns the material type of the actual block at the location.
     *
     * @param player   the player for whom the block type is determined; must not be null
     * @param location the location of the block to check; must not be null
     * @return the material representing the block type at the specified location
     */
    public static Material getBlockType(@Nullable Player player, @NonNull Location location) {
        if(location.getWorld() == null) {
            return Material.AIR;
        }

        Optional<PacketBlockHolder<?, ?>> optionalHolder = PacketBlocks.getInstance().getBlockManager().getBlock(location);

        if(optionalHolder.isEmpty()) {
            return location.getBlock().getType();
        }

        PacketBlockHolder<?, ?> packetBlockHolder = optionalHolder.get();

        if (packetBlockHolder instanceof PacketBlock packetBlock) {
            return packetBlock.getData().getMaterial();
        }

        if(packetBlockHolder instanceof PacketBlockGroup group) {
            Optional<BlockData> optionalData = group.getDataAt(player, location);

            if(optionalData.isEmpty()) {
                return location.getBlock().getType();
            }

            return optionalData.get().getMaterial();
        }

        return location.getBlock().getType();
    }

    /**
     * Performs a ray trace from the player's eye location along their current direction up to the specified range.
     * The ray trace detects blocks in the player's world considering custom bounding boxes when relevant.
     *
     * @param player the player from whose perspective the ray trace is performed
     * @param range the maximum distance the ray trace will travel
     * @return a RayTraceResult containing information about the hit block and location, or null if no block was hit
     */
    public static RayTraceResult rayTrace(Player player, double range) {
        PacketBlockManager blockManager = PacketBlocks.getInstance().getBlockManager();

        Location eye = player.isSneaking() ? player.getEyeLocation().clone().add(0, 0.25, 0) : player.getEyeLocation();
        Vector direction = eye.getDirection().normalize();

        World world = player.getWorld();

        RayTraceResult vanillaResult = world.rayTraceBlocks(eye, direction, range, FluidCollisionMode.NEVER, false);

        Vector current = eye.toVector();
        double step = 0.02;

        for (double traveled = 0; traveled <= range; traveled += step) {
            current.add(direction.clone().multiply(step));
            Block block = world.getBlockAt(current.toLocation(world));
            PacketBlockHolder<?, ?> packetBlock = blockManager.getBlock(block.getLocation()).orElse(null);

            if(packetBlock == null) {
                if(!block.getType().isAir()) {
                    RayTraceResult boxResult = BoundingBoxes.rayTraceAt(block, block.getBlockData(), eye.toVector(), direction, range);

                    if(boxResult != null) {
                        return new RayTraceResult(boxResult.getHitPosition(), block, boxResult.getHitBlockFace());
                    }
                }

                continue;
            }

            if (packetBlock instanceof PacketBlock singleBlock) {
                BlockData blockData = singleBlock.getData(player);

                RayTraceResult boxResult = BoundingBoxes.rayTraceAt(block, blockData, eye.toVector(), direction, range);

                if(boxResult == null) {
                    continue;
                }

                return new RayTraceResult(boxResult.getHitPosition(), block, boxResult.getHitBlockFace());
            }

            if (!(packetBlock instanceof PacketBlockGroup group)) {
                continue;
            }

            Optional<BlockData> optionalBlockData = group.getDataAt(player, block.getLocation());

            if (optionalBlockData.isEmpty()) {
                continue;
            }

            BlockData blockData = optionalBlockData.get();

            RayTraceResult boxResult = BoundingBoxes.rayTraceAt(block, blockData, eye.toVector(), direction, range);

            if(boxResult == null) {
                continue;
            }

            return new RayTraceResult(boxResult.getHitPosition(), block, boxResult.getHitBlockFace());
        }

        return vanillaResult;
    }

    public static void sendLightUpdate(@NonNull Player player, @NonNull Location location) {
        World world = location.getWorld();

        if (world == null || !player.getWorld().equals(world)) {
            return;
        }

        int chunkX = location.getBlockX() >> 4;
        int chunkZ = location.getBlockZ() >> 4;

        if (!world.isChunkLoaded(chunkX, chunkZ)) {
            return;
        }

        Chunk chunk = world.getChunkAt(chunkX, chunkZ);
        int sectionY = location.getBlockY() >> 4;
        ChunkSnapshot chunkSnapshot = chunk.getChunkSnapshot(
                EXCLUDE_MAX_BLOCK_Y,
                EXCLUDE_BIOME_DATA,
                EXCLUDE_TEMPERATURE_DATA,
                INCLUDE_LIGHT_DATA
        );
        LightData lightData = createLightData(world, chunkSnapshot, sectionY);

        PacketEvents.getAPI().getPlayerManager().sendPacket(player,
                new WrapperPlayServerUpdateLight(chunkX, chunkZ, lightData));
    }

    public static void sendLightUpdates(@NonNull Player player, @NonNull Collection<Location> locations) {
        Set<ChunkSectionPosition> updatedSections = new HashSet<>();

        for (Location location : locations) {
            if (location.getWorld() == null || !location.getWorld().equals(player.getWorld())) {
                continue;
            }

            if (!updatedSections.add(ChunkSectionPosition.of(location))) {
                continue;
            }

            sendLightUpdate(player, location);
        }
    }

    private static LightData createLightData(@NonNull World world, @NonNull ChunkSnapshot chunkSnapshot, int sectionY) {
        BitSet skyLightMask = new BitSet();
        BitSet blockLightMask = new BitSet();
        int lightSectionIndex = sectionY - (world.getMinHeight() >> 4) + LIGHT_MASK_SECTION_OFFSET;

        skyLightMask.set(lightSectionIndex);
        blockLightMask.set(lightSectionIndex);

        return new LightData(
                TRUST_EDGES,
                blockLightMask,
                skyLightMask,
                new BitSet(),
                new BitSet(),
                SINGLE_LIGHT_SECTION_COUNT,
                SINGLE_LIGHT_SECTION_COUNT,
                new byte[][]{createLightArray(chunkSnapshot, sectionY, true)},
                new byte[][]{createLightArray(chunkSnapshot, sectionY, false)}
        );
    }

    private static byte[] createLightArray(@NonNull ChunkSnapshot chunkSnapshot, int sectionY, boolean skyLight) {
        byte[] lightArray = new byte[LIGHT_ARRAY_SIZE];
        int baseY = sectionY * LIGHT_SECTION_HEIGHT;
        int blockIndex = 0;

        for (int y = 0; y < LIGHT_SECTION_HEIGHT; y++) {
            for (int z = 0; z < LIGHT_SECTION_HEIGHT; z++) {
                for (int x = 0; x < LIGHT_SECTION_HEIGHT; x++) {
                    int lightLevel = skyLight
                            ? chunkSnapshot.getBlockSkyLight(x, baseY + y, z)
                            : chunkSnapshot.getBlockEmittedLight(x, baseY + y, z);

                    int arrayIndex = blockIndex >> 1;

                    if ((blockIndex & 1) == 0) {
                        lightArray[arrayIndex] = (byte) (lightLevel & 0xF);
                    } else {
                        lightArray[arrayIndex] |= (byte) ((lightLevel & 0xF) << 4);
                    }

                    blockIndex++;
                }
            }
        }

        return lightArray;
    }

    private record ChunkSectionPosition(int chunkX, int sectionY, int chunkZ) {

        private static ChunkSectionPosition of(Location location) {
            return new ChunkSectionPosition(location.getBlockX() >> 4, location.getBlockY() >> 4, location.getBlockZ() >> 4);
        }
    }

}
