package cn.wode490390.nukkit.cachesupport.scheduler;

import cn.nukkit.Player;
import cn.nukkit.blockentity.BlockEntitySpawnable;
import cn.nukkit.level.Level;
import cn.nukkit.level.format.ChunkSection;
import cn.nukkit.level.format.anvil.Chunk;
import cn.nukkit.nbt.NBTIO;
import cn.nukkit.nbt.tag.CompoundTag;
import cn.nukkit.network.protocol.LevelChunkPacket;
import cn.nukkit.scheduler.AsyncTask;
import cn.nukkit.utils.ChunkException;
import cn.wode490390.nukkit.cachesupport.CacheSupport;
import cn.wode490390.nukkit.cachesupport.math.XXHash64;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

public class AnvilCacheableChunkRequest extends AsyncTask {

    private final CacheSupport plugin;
    private final Level level;
    private final int chunkX;
    private final int chunkZ;
    private final Player player;

    public AnvilCacheableChunkRequest(CacheSupport plugin, Level level, int chunkX, int chunkZ, Player player) {
        this.plugin = plugin;
        this.level = level;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.player = player;
    }

    @Override
    public void onRun() {
        Chunk chunk = (Chunk) this.level.getProvider().getChunk(this.chunkX, this.chunkZ, false);
        if (chunk == null) {
            throw new ChunkException("Invalid Chunk Set");
        }

        byte[] tiles = new byte[0];
        if (!chunk.getBlockEntities().isEmpty()) {
            List<CompoundTag> tagList = new ArrayList<>();
            chunk.getBlockEntities().values().stream()
                    .filter(blockEntity -> blockEntity instanceof BlockEntitySpawnable)
                    .forEach(blockEntity -> tagList.add(((BlockEntitySpawnable) blockEntity).getSpawnCompound()));
            try {
                tiles = NBTIO.write(tagList, ByteOrder.LITTLE_ENDIAN, true);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        int count = 0;
        ChunkSection[] sections = chunk.getSections();
        for (int i = sections.length - 1; i >= 0; i--) {
            if (!sections[i].isEmpty()) {
                count = i + 1;
                break;
            }
        }

        List<Long> blobIds = new LongArrayList();
        for (int i = 0; i < count; i++) {
            byte[] subChunk = new byte[6145];
            System.arraycopy(sections[i].getBytes(), 0, subChunk, 1, 6144);
            long hash = XXHash64.getHash(subChunk);
            blobIds.add(hash);
            this.plugin.trackTransaction(this.player, hash, subChunk);
        }

        byte[] biome = chunk.getBiomeIdArray();
        long hash = XXHash64.getHash(biome);
        blobIds.add(hash);
        this.plugin.trackTransaction(this.player, hash, biome);

        byte[] payload = new byte[1 + tiles.length];
        System.arraycopy(tiles, 0, payload, 1, tiles.length);

        this.player.usedChunks.put(Level.chunkHash(this.chunkX, this.chunkZ), true);
        try {
            Field f = Player.class.getDeclaredField("chunkLoadCount");
            f.setAccessible(true);
            f.set(this.player, (int) f.get(this.player) + 1);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }

        LevelChunkPacket pk = new LevelChunkPacket();
        pk.chunkX = this.chunkX;
        pk.chunkZ = this.chunkZ;
        pk.subChunkCount = count;
        pk.cacheEnabled = true;
        pk.blobIds = blobIds.stream().mapToLong(Long::valueOf).toArray();
        pk.data = payload;
        this.player.dataPacket(pk);

        if (this.player.spawned) {
            this.player.level.getChunkEntities(this.chunkX, this.chunkZ).values().stream()
                    .filter(entity -> this.player != entity && !entity.isClosed() && entity.isAlive())
                    .forEach(entity -> entity.spawnTo(this.player));
        }
    }
}
