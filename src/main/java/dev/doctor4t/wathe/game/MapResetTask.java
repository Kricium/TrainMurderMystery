package dev.doctor4t.wathe.game;

import dev.doctor4t.wathe.Wathe;
import dev.doctor4t.wathe.cca.MapVariablesWorldComponent;
import dev.doctor4t.wathe.entity.FirecrackerEntity;
import dev.doctor4t.wathe.entity.NoteEntity;
import dev.doctor4t.wathe.entity.PlayerBodyEntity;
import dev.doctor4t.wathe.index.WatheEntities;
import dev.doctor4t.wathe.index.WatheProperties;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.component.ComponentMap;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.Clearable;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 渐进式地图重置任务
 * <p>
 * 参考 AutoTrainResetTask 实现，将地图模板区域分块（chunk）后
 * 每 tick 只复制一个分块，避免一次性复制所有方块导致的卡顿。
 * 重置过程中会在 ActionBar 上向所有玩家显示重置进度百分比。
 * </p>
 */
public class MapResetTask {

    // ── 常量 ──────────────────────────────────────────────────────────────
    /** 每个分块的目标方块数量（约 5000 块），与 AutoTrainResetTask 保持一致 */
    private static final int TARGET_BLOCKS_PER_CHUNK = 5000;

    /** 每 tick 处理的分块数，每块已约 5000 方块，通常 1 块即可 */
    private static final int CHUNKS_PER_TICK = 1;

    /** 每隔多少 tick 更新一次进度显示（10 tick ≈ 0.5 秒） */
    private static final int PROGRESS_UPDATE_INTERVAL = 10;

    /** 分块全部处理完毕后，额外执行掉落物清理的 tick 数，确保延迟产生的掉落物也被清除 */
    private static final int POST_CLEANUP_TICKS = 5;

    // ── 状态字段 ──────────────────────────────────────────────────────────
    private final ServerWorld serverWorld;
    private final List<BlockBox> resetChunks;       // 预计算的分块列表
    private final BlockPos offsetBlockPos;           // 模板区域到游玩区域的偏移
    private final BlockBox backupTrainBox;            // 模板区域的 BlockBox
    private final int totalChunks;                   // 分块总数
    private int currentChunkIndex = 0;               // 当前处理到的分块索引
    private int tickCount = 0;                       // 已运行的 tick 数
    private boolean finished = false;                // 是否已完成
    private int postCleanupTicksRemaining = -1;      // 后续清理倒计时（-1 = 尚未进入清理阶段）

    /** 重置完成后的回调，用于触发游戏初始化的第二阶段（传送玩家、分配角色等） */
    private final Runnable onComplete;

    // ── 构造器 ─────────────────────────────────────────────────────────────

    /**
     * 创建渐进式地图重置任务
     *
     * @param serverWorld 服务端世界
     * @param onComplete  重置完成后执行的回调（在主线程 serverTick 中调用）
     */
    public MapResetTask(ServerWorld serverWorld, Runnable onComplete) {
        this.serverWorld = serverWorld;
        this.onComplete = onComplete;

        MapVariablesWorldComponent areas = MapVariablesWorldComponent.KEY.get(serverWorld);
        BlockPos backupMinPos = BlockPos.ofFloored(areas.getResetTemplateArea().getMinPos());
        BlockPos backupMaxPos = BlockPos.ofFloored(areas.getResetTemplateArea().getMaxPos());
        this.backupTrainBox = BlockBox.create(backupMinPos, backupMaxPos);

        BlockPos trainMinPos = BlockPos.ofFloored(
                areas.getResetTemplateArea().offset(Vec3d.of(areas.getResetPasteOffset())).getMinPos()
        );
        BlockPos trainMaxPos = trainMinPos.add(backupTrainBox.getDimensions());
        BlockBox trainBox = BlockBox.create(trainMinPos, trainMaxPos);

        this.offsetBlockPos = new BlockPos(
                trainBox.getMinX() - backupTrainBox.getMinX(),
                trainBox.getMinY() - backupTrainBox.getMinY(),
                trainBox.getMinZ() - backupTrainBox.getMinZ()
        );

        // 预计算三维分块
        this.resetChunks = buildChunks(backupTrainBox, TARGET_BLOCKS_PER_CHUNK);
        this.totalChunks = resetChunks.size();

        // 强制加载模板区域和目标区域的所有区块，确保渐进重置不会因区块未加载而卡死。
        // 在 STARTING 之前执行重置时，玩家可能不在这些区域附近，区块可能未加载。
        forceLoadRegion(serverWorld, backupMinPos, backupMaxPos);
        forceLoadRegion(serverWorld, trainMinPos, trainMaxPos);

        Wathe.LOGGER.info("渐进式地图重置已启动：共 {} 个分块待处理。维度: {}",
                totalChunks, serverWorld.getRegistryKey().getValue());
    }

    /**
     * 强制加载指定范围内的所有区块，防止因区块未加载导致方块操作失败。
     */
    private static void forceLoadRegion(ServerWorld world, BlockPos minPos, BlockPos maxPos) {
        int minChunkX = minPos.getX() >> 4;
        int minChunkZ = minPos.getZ() >> 4;
        int maxChunkX = maxPos.getX() >> 4;
        int maxChunkZ = maxPos.getZ() >> 4;
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                world.getChunk(cx, cz);
            }
        }
    }

    // ── 公共方法 ──────────────────────────────────────────────────────────

    /**
     * 每 tick 调用一次，处理一个分块的方块复制并更新进度
     *
     * @return true 表示重置已完成，false 表示仍在进行中
     */
    public boolean tick() {
        if (finished) {
            return true;
        }

        tickCount++;

        // ── 后续清理阶段：分块已全部处理完，持续清理延迟产生的掉落物 ──
        if (postCleanupTicksRemaining >= 0) {
            clearDroppedItems();
            postCleanupTicksRemaining--;
            if (postCleanupTicksRemaining < 0) {
                // 后续清理完成，真正结束任务
                onFinished();
                return true;
            }
            return false;
        }

        // ── 分块处理阶段 ──────────────────────────────────────────────────
        for (int i = 0; i < CHUNKS_PER_TICK && currentChunkIndex < totalChunks; i++, currentChunkIndex++) {
            BlockBox chunk = resetChunks.get(currentChunkIndex);
            copyChunk(serverWorld, chunk, offsetBlockPos);
        }

        // 每次处理完分块后立即清理掉落物，防止装饰方块（如软垫）掉落后堆积
        clearDroppedItems();

        // 定时广播进度
        if (tickCount % PROGRESS_UPDATE_INTERVAL == 1 || currentChunkIndex >= totalChunks) {
            broadcastProgress();
        }

        // 分块全部处理完毕 → 进入后续清理阶段（再持续清理几 tick）
        if (currentChunkIndex >= totalChunks) {
            postCleanupTicksRemaining = POST_CLEANUP_TICKS;
        }

        return false;
    }

    /**
     * 清理世界中所有掉落物实体。
     * 在每次分块处理后调用，确保因方块替换而产生的掉落物（如装饰方块被弹出）被及时清除。
     */
    private void clearDroppedItems() {
        for (ItemEntity item : serverWorld.getEntitiesByType(EntityType.ITEM, e -> true)) {
            item.discard();
        }
    }

    /**
     * @return 当前重置进度百分比（0-100）
     */
    public int getProgressPercent() {
        if (totalChunks == 0) return 100;
        return (int) ((currentChunkIndex / (float) totalChunks) * 100);
    }

    /**
     * @return 任务是否已完成
     */
    public boolean isFinished() {
        return finished;
    }

    // ── 内部方法 ──────────────────────────────────────────────────────────

    /**
     * 向所有在线玩家的 ActionBar 发送重置进度消息
     */
    private void broadcastProgress() {
        int percent = getProgressPercent();
        for (ServerPlayerEntity player : serverWorld.getPlayers()) {
            player.sendMessage(
                    Text.translatable("message.wathe.resetting", String.valueOf(percent))
                            .formatted(Formatting.YELLOW),
                    true  // actionBar = true
            );
        }
        Wathe.LOGGER.info("渐进式地图重置进度：{}/{}（{}%）", currentChunkIndex, totalChunks, percent);
    }

    /**
     * 重置完成时的回调：清理实体、发送完成消息
     */
    private void onFinished() {
        finished = true;

        // 调度 tick 更新
        serverWorld.getBlockTickScheduler().scheduleTicks(
                serverWorld.getBlockTickScheduler(), backupTrainBox, offsetBlockPos
        );
        refreshLitLights();

        // 清理实体（与原 tryResetTrain 逻辑一致）
        for (PlayerBodyEntity body : serverWorld.getEntitiesByType(WatheEntities.PLAYER_BODY, e -> true)) {
            body.discard();
        }
        for (ItemEntity item : serverWorld.getEntitiesByType(EntityType.ITEM, e -> true)) {
            item.discard();
        }
        for (FirecrackerEntity entity : serverWorld.getEntitiesByType(WatheEntities.FIRECRACKER, e -> true)) {
            entity.discard();
        }
        for (NoteEntity entity : serverWorld.getEntitiesByType(WatheEntities.NOTE, e -> true)) {
            entity.discard();
        }

        // 发送完成消息
        for (ServerPlayerEntity player : serverWorld.getPlayers()) {
            player.sendMessage(
                    Text.translatable("message.wathe.resetting", "100")
                            .formatted(Formatting.GREEN),
                    true
            );
        }

        Wathe.LOGGER.info("渐进式地图重置完成。维度: {}", serverWorld.getRegistryKey().getValue());

        // 执行完成回调（触发游戏初始化第二阶段：传送玩家、分配角色等）
        if (onComplete != null) {
            onComplete.run();
        }
    }

    // ── 分块构建算法 ──────────────────────────────────────────────────────

    /**
     * 将一个大的 BlockBox 区域分割成若干个小分块，每个分块包含约 target 个方块。
     * 遍历顺序为从 maxY 到 minY（从顶向下），避免重力方块问题。
     *
     * @param box    要分割的区域
     * @param target 每个分块的目标方块数
     * @return 分块列表
     */
    private void refreshLitLights() {
        for (int y = backupTrainBox.getMinY(); y <= backupTrainBox.getMaxY(); y++) {
            for (int x = backupTrainBox.getMinX(); x <= backupTrainBox.getMaxX(); x++) {
                for (int z = backupTrainBox.getMinZ(); z <= backupTrainBox.getMaxZ(); z++) {
                    BlockPos dstPos = new BlockPos(x, y, z).add(offsetBlockPos);
                    BlockState state = serverWorld.getBlockState(dstPos);
                    if (!state.contains(Properties.LIT) || !state.contains(WatheProperties.ACTIVE)) {
                        continue;
                    }
                    if (!state.get(Properties.LIT) || !state.get(WatheProperties.ACTIVE)) {
                        continue;
                    }

                    serverWorld.setBlockState(dstPos, state.with(Properties.LIT, false), Block.NOTIFY_ALL);
                    serverWorld.setBlockState(dstPos, state, Block.NOTIFY_ALL);
                }
            }
        }
    }

    private static List<BlockBox> buildChunks(BlockBox box, int target) {
        List<BlockBox> chunks = new ArrayList<>();

        int xLen = box.getMaxX() - box.getMinX() + 1;
        int yLen = box.getMaxY() - box.getMinY() + 1;
        int zLen = box.getMaxZ() - box.getMinZ() + 1;

        // 按体积比例计算各轴的分块尺寸，使每块体积 ≈ target
        double scale = Math.cbrt((double) target / ((double) xLen * yLen * zLen));
        int cx = Math.max(1, Math.min(xLen, (int) Math.ceil(xLen * scale)));
        int cy = Math.max(1, Math.min(yLen, (int) Math.ceil(yLen * scale)));
        int cz = Math.max(1, Math.min(zLen, (int) Math.ceil(zLen * scale)));

        // 从 maxY 到 minY（从顶向下），与 AutoTrainResetTask 保持一致
        for (int y = box.getMaxY(); y >= box.getMinY(); y -= cy) {
            int yMin = Math.max(box.getMinY(), y - cy + 1);
            for (int x = box.getMinX(); x <= box.getMaxX(); x += cx) {
                int xMax = Math.min(box.getMaxX(), x + cx - 1);
                for (int z = box.getMinZ(); z <= box.getMaxZ(); z += cz) {
                    int zMax = Math.min(box.getMaxZ(), z + cz - 1);
                    chunks.add(BlockBox.create(
                            new BlockPos(x, yMin, z),
                            new BlockPos(xMax, y, zMax)
                    ));
                }
            }
        }

        return chunks;
    }

    // ── 方块复制逻辑 ──────────────────────────────────────────────────────

    /**
     * 复制一个分块内的所有方块（包括 BlockEntity 数据）从模板区域到游玩区域。
     * <p>
     * 采用三步策略：
     * 1. 先收集所有 BlockEntity 的 NBT 数据
     * 2. 复制所有方块状态（setBlockState）
     * 3. 恢复 BlockEntity 的 NBT 数据
     * </p>
     *
     * @param world  服务端世界
     * @param chunk  当前要复制的分块区域（在模板坐标系中）
     * @param offset 模板到目标的偏移
     */
    private static void copyChunk(ServerWorld world, BlockBox chunk, BlockPos offset) {
        // 第一步：收集 BlockEntity 数据
        List<Map.Entry<BlockPos, BlockEntitySnapshot>> pendingBlockEntities = new ArrayList<>();

        for (int y = chunk.getMinY(); y <= chunk.getMaxY(); y++) {
            for (int x = chunk.getMinX(); x <= chunk.getMaxX(); x++) {
                for (int z = chunk.getMinZ(); z <= chunk.getMaxZ(); z++) {
                    BlockPos srcPos = new BlockPos(x, y, z);
                    BlockEntity srcBE = world.getBlockEntity(srcPos);
                    if (srcBE != null) {
                        NbtCompound nbt = srcBE.createComponentlessNbt(world.getRegistryManager());
                        ComponentMap components = srcBE.getComponents();
                        BlockPos dstPos = srcPos.add(offset);
                        pendingBlockEntities.add(new AbstractMap.SimpleEntry<>(
                                dstPos, new BlockEntitySnapshot(nbt, components)
                        ));
                    }
                }
            }
        }

        // 第二步：先用 BARRIER 清除目标区域，再设置方块状态
        // 使用 FORCE_STATE 跳过 onStateReplaced，但 onBlockAdded 仍会被
        // Chunk 内部（及 Carpet mod 的 mixin）调用。床/门等多部分方块在
        // 分块处理时可能因相邻部分尚未复制而 NPE，因此用 try-catch 兜底。
        for (int y = chunk.getMaxY(); y >= chunk.getMinY(); y--) {
            for (int x = chunk.getMaxX(); x >= chunk.getMinX(); x--) {
                for (int z = chunk.getMaxZ(); z >= chunk.getMinZ(); z--) {
                    BlockPos dstPos = new BlockPos(x, y, z).add(offset);
                    BlockEntity be = world.getBlockEntity(dstPos);
                    Clearable.clear(be);
                    try {
                        world.setBlockState(dstPos, Blocks.BARRIER.getDefaultState(), Block.FORCE_STATE);
                    } catch (Exception ignored) {
                        // 忽略多部分方块清除时的 NPE（相邻部分可能在另一个分块中）
                    }
                }
            }
        }

        // 正序设置方块状态
        for (int y = chunk.getMinY(); y <= chunk.getMaxY(); y++) {
            for (int x = chunk.getMinX(); x <= chunk.getMaxX(); x++) {
                for (int z = chunk.getMinZ(); z <= chunk.getMaxZ(); z++) {
                    BlockPos srcPos = new BlockPos(x, y, z);
                    BlockPos dstPos = srcPos.add(offset);
                    BlockState state = world.getBlockState(srcPos);
                    try {
                        world.setBlockState(dstPos, state, Block.FORCE_STATE);
                    } catch (Exception ignored) {
                        // 忽略多部分方块设置时的 NPE（相邻部分可能尚未复制）
                    }
                }
            }
        }

        // 第三步：恢复 BlockEntity 数据
        for (Map.Entry<BlockPos, BlockEntitySnapshot> entry : pendingBlockEntities) {
            BlockPos dstPos = entry.getKey();
            BlockEntitySnapshot snapshot = entry.getValue();
            BlockEntity dstBE = world.getBlockEntity(dstPos);
            if (dstBE != null) {
                dstBE.readComponentlessNbt(snapshot.nbt(), world.getRegistryManager());
                dstBE.setComponents(snapshot.components());
                dstBE.markDirty();
            }
        }

        // 更新邻居状态
        for (int y = chunk.getMinY(); y <= chunk.getMaxY(); y++) {
            for (int x = chunk.getMinX(); x <= chunk.getMaxX(); x++) {
                for (int z = chunk.getMinZ(); z <= chunk.getMaxZ(); z++) {
                    BlockPos dstPos = new BlockPos(x, y, z).add(offset);
                    BlockState state = world.getBlockState(dstPos);
                    world.updateNeighbors(dstPos, state.getBlock());
                    world.updateListeners(dstPos, state, state, Block.NOTIFY_LISTENERS);
                    if (state.contains(Properties.LIT) && state.contains(WatheProperties.ACTIVE)) {
                        world.getChunkManager().getLightingProvider().checkBlock(dstPos);
                    }
                }
            }
        }
    }

    /**
     * BlockEntity 快照，用于保存和恢复方块实体数据
     */
    private record BlockEntitySnapshot(NbtCompound nbt, ComponentMap components) {
    }
}
