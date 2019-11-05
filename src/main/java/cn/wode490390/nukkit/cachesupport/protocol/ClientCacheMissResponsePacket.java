package cn.wode490390.nukkit.cachesupport.protocol;

import cn.nukkit.network.protocol.DataPacket;
import cn.nukkit.network.protocol.ProtocolInfo;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.Map;

public class ClientCacheMissResponsePacket extends DataPacket {

    public static final byte NETWORK_ID = ProtocolInfo.CLIENT_CACHE_MISS_RESPONSE_PACKET;

    public Map<Long, byte[]> blobs = new Long2ObjectOpenHashMap<>();

    @Override
    public byte pid() {
        return NETWORK_ID;
    }

    @Override
    public void decode() {

    }

    @Override
    public void encode() {
        this.reset();
        this.putUnsignedVarInt(this.blobs.size());
        this.blobs.forEach((id, blob) -> {
            this.putLLong(id);
            this.putByteArray(blob);
        });
    }
}
