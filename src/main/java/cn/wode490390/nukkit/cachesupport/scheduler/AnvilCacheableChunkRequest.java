package cn.wode490390.nukkit.cachesupport.scheduler;

import cn.nukkit.Player;
import cn.nukkit.Server;
import cn.nukkit.blockentity.BlockEntity;
import cn.nukkit.blockentity.BlockEntitySpawnable;
import cn.nukkit.level.Level;
import cn.nukkit.level.format.ChunkSection;
import cn.nukkit.level.format.anvil.Chunk;
import cn.nukkit.nbt.NBTIO;
import cn.nukkit.nbt.tag.CompoundTag;
import cn.nukkit.network.protocol.LevelChunkPacket;
import cn.nukkit.scheduler.AsyncTask;
import cn.nukkit.utils.BinaryStream;
import cn.nukkit.utils.ChunkException;
import cn.wode490390.nukkit.cachesupport.CacheSupport;
import cn.wode490390.nukkit.cachesupport.math.XXHash64;
import com.google.common.collect.Lists;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;

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

    private final Long2ObjectMap<byte[]> track = new Long2ObjectOpenHashMap<>(16 + 1); // 16 subChunks + 1 biome

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

        ChunkSection[] sections = chunk.getSections();
        for (int i = sections.length - 1; i >= 0; i--) {
            if (!sections[i].isEmpty()) {
                this.subChunkCount = i + 1;
                break;
            }
        }

        LongList blobIds = new LongArrayList();
        BinaryStream temp = new BinaryStream();
        for (int i = 0; i < this.subChunkCount; i++) {
            sections[i].writeTo(temp.reset());
            byte[] subChunk = temp.getBuffer();

            long hash = XXHash64.getHash(subChunk);
            blobIds.add(hash);
            this.track.put(hash, subChunk);
        }

        byte[] biome = chunk.getBiomeIdArray();
        long hash = XXHash64.getHash(biome);
        blobIds.add(hash);
        this.track.put(hash, biome);

        BinaryStream stream = new BinaryStream(new byte[1 + 1]).reset(); // borderBlocks + extraData (+ blockEntities)

        stream.putByte((byte) 0); // Border blocks size - Education Edition only

        Map<Integer, Integer> extraData = chunk.getBlockExtraDataArray(); // Replaced by second block layer
        stream.putUnsignedVarInt(extraData.size());
        if (!extraData.isEmpty()) {
            for (Map.Entry<Integer, Integer> entry : extraData.entrySet()) {
                stream.putVarInt(entry.getKey());
                stream.putLShort(entry.getValue());
            }
        }

        Map<Long, BlockEntity> blockEntities = chunk.getBlockEntities();
        if (!blockEntities.isEmpty()) {
            List<CompoundTag> tagList = Lists.newArrayList();
            blockEntities.values().stream()
                    .filter(blockEntity -> blockEntity instanceof BlockEntitySpawnable)
                    .forEach(blockEntity -> tagList.add(((BlockEntitySpawnable) blockEntity).getSpawnCompound()));
            if (!tagList.isEmpty()) {
                byte[] tiles;
                try {
                    tiles = NBTIO.write(tagList, ByteOrder.LITTLE_ENDIAN, true);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                stream.put(tiles);
            }
        }

        this.payload = stream.getBuffer();

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
