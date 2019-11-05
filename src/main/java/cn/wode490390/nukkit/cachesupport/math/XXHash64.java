package cn.wode490390.nukkit.cachesupport.math;

import net.openhft.hashing.LongHashFunction;

public final class XXHash64 {

    private static final LongHashFunction xxHash64 = LongHashFunction.xx();

    public static long getHash(byte[] buffer) {
        return xxHash64.hashBytes(buffer);
    }

    private XXHash64() {
    }
}
