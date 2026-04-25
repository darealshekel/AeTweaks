package com.miningtrackeraddon.tracker;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.miningtrackeraddon.config.Configs;
import com.miningtrackeraddon.storage.WorldSessionContext;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;

public final class MiningValidationTracker
{
    private static final int PLACE_BREAK_COUNT_THRESHOLD = 8;
    private static final ArrayDeque<BrokenPosition> RECENT_BROKEN_POSITIONS = new ArrayDeque<>();
    private static final Map<Long, Long> RECENT_PLACED_POSITIONS = new HashMap<>();
    private static final Set<Long> SESSION_BROKEN_POSITIONS = new HashSet<>();

    private static long sessionStartMs = System.currentTimeMillis();
    private static long physicalBlocksMined;
    private static long cameraSamples;
    private static double yawSum;
    private static double yawSquareSum;
    private static double pitchSum;
    private static double pitchSquareSum;
    private static float lastRawYaw;
    private static double unwrappedYaw;
    private static boolean hasYawSample;
    private static long positionSamples;
    private static double xSum;
    private static double xSquareSum;
    private static double ySum;
    private static double ySquareSum;
    private static double zSum;
    private static double zSquareSum;
    private static int currentContinuousMiningTicks;
    private static int maxContinuousMiningTicks;
    private static long placedThenBrokenCount;
    private static long lastActionPauseMs;

    private MiningValidationTracker()
    {
    }

    public static void resetSession(long now)
    {
        sessionStartMs = now;
        physicalBlocksMined = 0L;
        cameraSamples = 0L;
        yawSum = 0D;
        yawSquareSum = 0D;
        pitchSum = 0D;
        pitchSquareSum = 0D;
        lastRawYaw = 0F;
        unwrappedYaw = 0D;
        hasYawSample = false;
        positionSamples = 0L;
        xSum = 0D;
        xSquareSum = 0D;
        ySum = 0D;
        ySquareSum = 0D;
        zSum = 0D;
        zSquareSum = 0D;
        currentContinuousMiningTicks = 0;
        maxContinuousMiningTicks = 0;
        placedThenBrokenCount = 0L;
        lastActionPauseMs = now;
        RECENT_BROKEN_POSITIONS.clear();
        SESSION_BROKEN_POSITIONS.clear();
        prunePlacedPositions(now);
    }

    public static void onClientTick(long now)
    {
        MinecraftClient client = MinecraftClient.getInstance();
        boolean miningHeld = client != null
                && client.options != null
                && client.options.attackKey != null
                && client.options.attackKey.isPressed();

        if (miningHeld)
        {
            currentContinuousMiningTicks++;
            maxContinuousMiningTicks = Math.max(maxContinuousMiningTicks, currentContinuousMiningTicks);
            sampleCameraAndPosition(client);
        }
        else
        {
            currentContinuousMiningTicks = 0;
            lastActionPauseMs = now;
        }

        prunePlacedPositions(now);
    }

    public static void onBlockMined(BlockPos pos, BlockState previousState, long now)
    {
        physicalBlocksMined++;
        MinecraftClient client = MinecraftClient.getInstance();
        sampleCameraAndPosition(client);

        if (pos != null)
        {
            BlockPos immutable = pos.toImmutable();
            long key = immutable.asLong();
            SESSION_BROKEN_POSITIONS.add(key);
            RECENT_BROKEN_POSITIONS.addLast(new BrokenPosition(immutable, now));
            while (RECENT_BROKEN_POSITIONS.size() > getClusterBufferSize())
            {
                RECENT_BROKEN_POSITIONS.removeFirst();
            }

            Long placedAtMs = RECENT_PLACED_POSITIONS.remove(key);
            if (placedAtMs != null && now - placedAtMs <= getPlaceBreakWindowMs())
            {
                placedThenBrokenCount++;
            }
        }

        prunePlacedPositions(now);
    }

    public static void onBlockPlaced(BlockPos pos, long now)
    {
        if (pos == null)
        {
            return;
        }

        RECENT_PLACED_POSITIONS.put(pos.asLong(), now);
        prunePlacedPositions(now);
    }

    public static JsonObject buildPayload(String username,
                                          String minecraftUuid,
                                          WorldSessionContext.WorldInfo worldInfo,
                                          long blocksMinedDelta,
                                          long sessionStart,
                                          long sessionEnd)
    {
        double cameraVariance = getCameraVariance();
        double positionVariance = getPositionVariance();
        ClusterStats clusterStats = getRecentClusterStats();
        int minimumBlocks = Configs.Generic.VALIDATION_MIN_BLOCKS.getIntegerValue();
        boolean enoughBlocks = physicalBlocksMined >= minimumBlocks;

        boolean lowCamera = enoughBlocks
                && cameraSamples >= Math.min(physicalBlocksMined, 30L)
                && cameraVariance <= Configs.Generic.VALIDATION_CAMERA_VARIANCE_THRESHOLD.getDoubleValue();
        boolean lowPosition = enoughBlocks
                && positionSamples >= Math.min(physicalBlocksMined, 30L)
                && positionVariance <= Configs.Generic.VALIDATION_POSITION_VARIANCE_THRESHOLD.getDoubleValue();
        boolean noPauses = enoughBlocks
                && maxContinuousMiningTicks >= Configs.Generic.VALIDATION_CONTINUOUS_MINING_TICKS.getIntegerValue();
        boolean placeBreak = enoughBlocks && placedThenBrokenCount >= PLACE_BREAK_COUNT_THRESHOLD;
        boolean repeatedClusterCandidate = enoughBlocks
                && clusterStats.sampleSize() >= Math.min(getClusterBufferSize(), Math.max(20, minimumBlocks / 5))
                && (clusterStats.uniquePositions() <= 3 || clusterStats.isTinyCluster());
        boolean repeatedCluster = repeatedClusterCandidate && (lowCamera || lowPosition || noPauses);

        JsonArray flags = new JsonArray();
        JsonObject details = new JsonObject();
        int score = 0;

        if (lowCamera)
        {
            flags.add("LOW_CAMERA_VARIANCE");
            score += 20;
            details.addProperty("LOW_CAMERA_VARIANCE", "Pitch/yaw variance stayed below the configured threshold during a large mining session.");
        }
        if (lowPosition)
        {
            flags.add("LOW_POSITION_VARIANCE");
            score += 20;
            details.addProperty("LOW_POSITION_VARIANCE", "Player movement stayed inside the configured movement radius during a large mining session.");
        }
        if (noPauses)
        {
            flags.add("NO_ACTION_PAUSES");
            score += 15;
            details.addProperty("NO_ACTION_PAUSES", "Mining input was held continuously longer than the configured tick threshold.");
        }
        if (repeatedCluster)
        {
            flags.add("REPEATED_BLOCK_CLUSTER");
            score += 25;
            details.addProperty("REPEATED_BLOCK_CLUSTER", "Recent broken blocks repeated in a tiny coordinate cluster alongside low-human-movement signals.");
        }
        if (placeBreak)
        {
            flags.add("PLACE_AND_BREAK_PATTERN");
            score += 20;
            details.addProperty("PLACE_AND_BREAK_PATTERN", "Repeated recently placed blocks were broken inside the configured time window.");
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("uuid", minecraftUuid == null ? "" : minecraftUuid);
        payload.addProperty("username", username == null ? "" : username);
        payload.addProperty("sourceName", worldInfo == null ? "" : worldInfo.displayName());
        payload.addProperty("worldName", worldInfo == null ? "" : worldInfo.displayName());
        payload.addProperty("serverIp", worldInfo == null ? "" : worldInfo.host());
        payload.addProperty("blocksMinedDelta", Math.max(0L, blocksMinedDelta));
        payload.addProperty("sessionStart", toIso(sessionStart > 0L ? sessionStart : sessionStartMs));
        payload.addProperty("sessionEnd", toIso(sessionEnd > 0L ? sessionEnd : System.currentTimeMillis()));
        payload.addProperty("pitchYawVariance", cameraVariance);
        payload.addProperty("positionVariance", positionVariance);
        payload.addProperty("maxContinuousMiningTicks", maxContinuousMiningTicks);
        payload.addProperty("uniqueBrokenBlockPositions", clusterStats.totalUniquePositions());
        payload.addProperty("recentClusterSize", clusterStats.uniquePositions());
        payload.addProperty("placedThenBrokenCount", placedThenBrokenCount);
        payload.addProperty("suspicionScore", Math.min(100, score));
        payload.add("flags", flags);
        payload.add("flagDetails", details);
        payload.add("sessionStats", buildSessionStats(enoughBlocks, clusterStats));
        return payload;
    }

    private static JsonObject buildSessionStats(boolean checksApplied, ClusterStats clusterStats)
    {
        JsonObject stats = new JsonObject();
        stats.addProperty("checksApplied", checksApplied);
        stats.addProperty("physicalBlocksObserved", physicalBlocksMined);
        stats.addProperty("cameraSamples", cameraSamples);
        stats.addProperty("positionSamples", positionSamples);
        stats.addProperty("lastActionPauseAt", toIso(lastActionPauseMs));
        stats.addProperty("recentBrokenBufferSize", clusterStats.sampleSize());
        stats.addProperty("recentClusterBounds", clusterStats.boundsDescription());
        stats.addProperty("minimumBlocksBeforeChecks", Configs.Generic.VALIDATION_MIN_BLOCKS.getIntegerValue());
        stats.addProperty("cameraVarianceThreshold", Configs.Generic.VALIDATION_CAMERA_VARIANCE_THRESHOLD.getDoubleValue());
        stats.addProperty("positionVarianceThreshold", Configs.Generic.VALIDATION_POSITION_VARIANCE_THRESHOLD.getDoubleValue());
        stats.addProperty("continuousMiningTickThreshold", Configs.Generic.VALIDATION_CONTINUOUS_MINING_TICKS.getIntegerValue());
        stats.addProperty("clusterBufferSize", getClusterBufferSize());
        stats.addProperty("placeBreakWindowSeconds", Configs.Generic.VALIDATION_PLACE_BREAK_WINDOW_SECONDS.getIntegerValue());
        return stats;
    }

    private static void sampleCameraAndPosition(MinecraftClient client)
    {
        if (client == null || client.player == null)
        {
            return;
        }

        float rawYaw = client.player.getYaw();
        if (!hasYawSample)
        {
            unwrappedYaw = rawYaw;
            lastRawYaw = rawYaw;
            hasYawSample = true;
        }
        else
        {
            float delta = rawYaw - lastRawYaw;
            while (delta > 180F) delta -= 360F;
            while (delta < -180F) delta += 360F;
            unwrappedYaw += delta;
            lastRawYaw = rawYaw;
        }

        double pitch = client.player.getPitch();
        cameraSamples++;
        yawSum += unwrappedYaw;
        yawSquareSum += unwrappedYaw * unwrappedYaw;
        pitchSum += pitch;
        pitchSquareSum += pitch * pitch;

        double x = client.player.getX();
        double y = client.player.getY();
        double z = client.player.getZ();
        positionSamples++;
        xSum += x;
        xSquareSum += x * x;
        ySum += y;
        ySquareSum += y * y;
        zSum += z;
        zSquareSum += z * z;
    }

    private static double getCameraVariance()
    {
        if (cameraSamples <= 1L)
        {
            return 0D;
        }

        double yawVariance = variance(yawSum, yawSquareSum, cameraSamples);
        double pitchVariance = variance(pitchSum, pitchSquareSum, cameraSamples);
        return round3(Math.sqrt(yawVariance + pitchVariance));
    }

    private static double getPositionVariance()
    {
        if (positionSamples <= 1L)
        {
            return 0D;
        }

        double variance = variance(xSum, xSquareSum, positionSamples)
                + variance(ySum, ySquareSum, positionSamples)
                + variance(zSum, zSquareSum, positionSamples);
        return round3(Math.sqrt(variance));
    }

    private static double variance(double sum, double squareSum, long samples)
    {
        if (samples <= 1L)
        {
            return 0D;
        }
        double mean = sum / samples;
        return Math.max(0D, (squareSum / samples) - (mean * mean));
    }

    private static ClusterStats getRecentClusterStats()
    {
        if (RECENT_BROKEN_POSITIONS.isEmpty())
        {
            return new ClusterStats(0, 0, 0, 0, 0, 0, 0);
        }

        Set<Long> unique = new HashSet<>();
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;

        for (BrokenPosition broken : RECENT_BROKEN_POSITIONS)
        {
            BlockPos pos = broken.pos();
            unique.add(pos.asLong());
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
        }

        return new ClusterStats(RECENT_BROKEN_POSITIONS.size(), unique.size(), SESSION_BROKEN_POSITIONS.size(), maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1, physicalBlocksMined);
    }

    private static int getClusterBufferSize()
    {
        return Math.max(20, Math.min(200, Configs.Generic.VALIDATION_CLUSTER_BUFFER_SIZE.getIntegerValue()));
    }

    private static long getPlaceBreakWindowMs()
    {
        return Math.max(1L, Configs.Generic.VALIDATION_PLACE_BREAK_WINDOW_SECONDS.getIntegerValue()) * 1000L;
    }

    private static void prunePlacedPositions(long now)
    {
        long cutoff = now - getPlaceBreakWindowMs();
        RECENT_PLACED_POSITIONS.entrySet().removeIf(entry -> entry.getValue() < cutoff);
    }

    private static String toIso(long timeMs)
    {
        return Instant.ofEpochMilli(Math.max(0L, timeMs)).toString();
    }

    private static double round3(double value)
    {
        return Math.round(value * 1000D) / 1000D;
    }

    private record BrokenPosition(BlockPos pos, long minedAtMs)
    {
    }

    private record ClusterStats(int sampleSize,
                                int uniquePositions,
                                int totalUniquePositions,
                                int widthX,
                                int heightY,
                                int depthZ,
                                long physicalBlocks)
    {
        private boolean isTinyCluster()
        {
            int[] dims = { Math.max(0, widthX), Math.max(0, heightY), Math.max(0, depthZ) };
            java.util.Arrays.sort(dims);
            return dims[0] <= 1 && dims[1] <= 2 && dims[2] <= 3;
        }

        private String boundsDescription()
        {
            return String.format(Locale.ROOT, "%dx%dx%d", Math.max(0, widthX), Math.max(0, heightY), Math.max(0, depthZ));
        }
    }
}
