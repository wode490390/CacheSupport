package cn.wode490390.nukkit.cachesupport;

import cn.nukkit.Player;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerChunkRequestEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.event.server.DataPacketReceiveEvent;
import cn.nukkit.level.Level;
import cn.nukkit.level.format.anvil.Anvil;
import cn.nukkit.network.protocol.ClientCacheStatusPacket;
import cn.nukkit.network.protocol.DataPacket;
import cn.nukkit.network.protocol.ProtocolInfo;
import cn.nukkit.plugin.PluginBase;
import cn.wode490390.nukkit.cachesupport.protocol.ClientCacheBlobStatusPacket;
import cn.wode490390.nukkit.cachesupport.protocol.ClientCacheMissResponsePacket;
import cn.wode490390.nukkit.cachesupport.scheduler.AnvilCacheableChunkRequest;
import cn.wode490390.nukkit.cachesupport.util.MetricsLite;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

public class CacheSupport extends PluginBase implements Listener {

    private final LongSet supported = new LongOpenHashSet();
    private final Long2ObjectMap<Long2ObjectMap<byte[]>> track = new Long2ObjectOpenHashMap<>();

    @Override
    public void onEnable() {
        try {
            new MetricsLite(this, 5883);
        } catch (Throwable ignore) {

        }

        boolean support = false;
        for (int version : ProtocolInfo.SUPPORTED_PROTOCOLS) {
            if (version >= 361) { //1.12+
                support = true;
                break;
            }
        };

        if (support) {
            this.getServer().getNetwork().registerPacket(ProtocolInfo.CLIENT_CACHE_BLOB_STATUS_PACKET, ClientCacheBlobStatusPacket.class);
            this.getServer().getNetwork().registerPacket(ProtocolInfo.CLIENT_CACHE_MISS_RESPONSE_PACKET, ClientCacheMissResponsePacket.class);

            this.getServer().getPluginManager().registerEvents(this, this);
        } else {
            this.getLogger().warning("Unsupported protocol version!");
        }
    }

    @EventHandler
    public void onPlayerChunkRequest(PlayerChunkRequestEvent event) {
        Player player = event.getPlayer();
        Level level = player.getLevel();
        if (level.getProvider() instanceof Anvil && this.supported.contains(player.getId()) && player.getLoaderId() > 0) {
            event.setCancelled();
            this.getServer().getScheduler().scheduleAsyncTask(this, new AnvilCacheableChunkRequest(this, level, event.getChunkX(), event.getChunkZ(), player));
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        long id = event.getPlayer().getId();
        this.supported.remove(id);
        this.track.remove(id);
    }

    @EventHandler
    public void onDataPacketReceive(DataPacketReceiveEvent event) {
        DataPacket packet = event.getPacket();
        Player player = event.getPlayer();
        switch (packet.pid()) {
            case ProtocolInfo.CLIENT_CACHE_STATUS_PACKET:
                if (((ClientCacheStatusPacket) packet).supported) {
                    long id = player.getId();
                    this.track.put(id, new Long2ObjectOpenHashMap<>());
                    this.supported.add(id);
                }
                break;
            case ProtocolInfo.CLIENT_CACHE_BLOB_STATUS_PACKET:
                Long2ObjectMap<byte[]> usedBlobs = this.track.get(player.getId());
                if (usedBlobs != null) {
                    ClientCacheBlobStatusPacket pk = (ClientCacheBlobStatusPacket) packet;

                    ClientCacheMissResponsePacket responsePk = new ClientCacheMissResponsePacket();
                    for (long id : pk.missHashes) {
                        byte[] blob = usedBlobs.get(id);
                        if (blob != null) {
                            responsePk.blobs.put(id, blob);
                            usedBlobs.remove(id);
                        }
                    }
                    player.batchDataPacket(responsePk);

                    for (long id : pk.hitHashes) {
                        usedBlobs.remove(id);
                    }
                }
                break;
        }
    }

    public void trackTransaction(Player player, Long2ObjectMap<byte[]> track) {
        Long2ObjectMap<byte[]> usedBlobs = this.track.get(player.getId());
        if (usedBlobs != null) {
            usedBlobs.putAll(track);
        }
    }
}
