package cn.wode490390.nukkit.cachesupport.protocol;

import cn.nukkit.network.protocol.DataPacket;
import cn.nukkit.network.protocol.ProtocolInfo;

public class ClientCacheBlobStatusPacket extends DataPacket {

    public static final byte NETWORK_ID = ProtocolInfo.CLIENT_CACHE_BLOB_STATUS_PACKET;

    public long[] missHashes;
    public long[] hitHashes;

    @Override
    public byte pid() {
        return NETWORK_ID;
    }

    @Override
    public void decode() {
        int missCount = (int) this.getUnsignedVarInt();
        int hitCount = (int) this.getUnsignedVarInt();

        if (missCount + hitCount > 0xfff) {
            throw new ArrayIndexOutOfBoundsException("Too many BlobIDs");
        }

        this.missHashes = new long[missCount];
        for (int i = 0; i < missCount; ++i) {
            this.missHashes[i] = this.getLLong();
        }

        this.hitHashes = new long[hitCount];
        for (int i = 0; i < hitCount; ++i) {
            this.hitHashes[i] = this.getLLong();
        }
    }

    @Override
    public void encode() {

    }
}
