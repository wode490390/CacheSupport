package cn.wode490390.nukkit.cachesupport.scheduler;

import cn.nukkit.Player;
import cn.nukkit.Server;
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
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteOrder;
import java.util.List;

public class AnvilCacheableChunkRequest extends AsyncTask {

    private static final Field F;

    static {
        try {
            F = Player.class.getDeclaredField("chunkLoadCount");
            F.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }

    private final CacheSupport plugin;
    private final Level level;
    private final int chunkX;
    private final int chunkZ;
    private final Player player;

    private int subChunkCount = 0;
    private long[] blobIds;
    private byte[] payload;

    private final Long2ObjectMap<byte[]> track = new Long2ObjectOpenHashMap<>(16 + 1); // 16 subChunk + 1 biome

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
            List<CompoundTag> tagList = new ObjectArrayList<>();
            chunk.getBlockEntities().values().stream()
                    .filter(blockEntity -> blockEntity instanceof BlockEntitySpawnable)
                    .forEach(blockEntity -> tagList.add(((BlockEntitySpawnable) blockEntity).getSpawnCompound()));
            try {
                tiles = NBTIO.write(tagList, ByteOrder.LITTLE_ENDIAN, true);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        ChunkSection[] sections = chunk.getSections();
        for (int i = sections.length - 1; i >= 0; i--) {
            if (!sections[i].isEmpty()) {
                this.subChunkCount = i + 1;
                break;
            }
        }

        LongList blobIds = new LongArrayList();
        for (int i = 0; i < this.subChunkCount; i++) {
            byte[] subChunk = new byte[6145]; // 1 subChunkVersion (always 0) + 4096 blockIds + 2048 blockMeta
            System.arraycopy(sections[i].getBytes(), 0, subChunk, 1, 6144); // skip subChunkVersion
            long hash = XXHash64.getHash(subChunk);
            blobIds.add(hash);
            this.track.put(hash, subChunk);
        }

        byte[] biome = chunk.getBiomeIdArray();
        long hash = XXHash64.getHash(biome);
        blobIds.add(hash);
        this.track.put(hash, biome);

        this.payload = new byte[1 + tiles.length]; // borderBlocks + blockEntities
        System.arraycopy(tiles, 0, this.payload, 1, tiles.length); // borderBlocks array size is always 0, skip it

        this.blobIds = blobIds.toLongArray();
    }

    @Override
    public void onCompletion(Server server) {
        this.plugin.trackTransaction(this.player, this.track);

        this.player.usedChunks.put(Level.chunkHash(this.chunkX, this.chunkZ), true);
        try {
            F.set(this.player, (int) F.get(this.player) + 1);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }

        LevelChunkPacket pk = new LevelChunkPacket();
        pk.chunkX = this.chunkX;
        pk.chunkZ = this.chunkZ;
        pk.subChunkCount = this.subChunkCount;
        pk.cacheEnabled = true;
        pk.blobIds = this.blobIds;
        pk.data = this.payload;
        this.player.batchDataPacket(pk);

        if (this.player.spawned) {
            this.player.level.getChunkEntities(this.chunkX, this.chunkZ).values().stream()
                    .filter(entity -> this.player != entity && !entity.isClosed() && entity.isAlive())
                    .forEach(entity -> entity.spawnTo(this.player));
        }
    }
}
