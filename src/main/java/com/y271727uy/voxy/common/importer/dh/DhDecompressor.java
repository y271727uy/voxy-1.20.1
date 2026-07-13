package com.y271727uy.voxy.common.importer.dh;

import java.io.IOException;
import java.io.InputStream;

@FunctionalInterface
public interface DhDecompressor {
    InputStream decompress(int compressionMode, byte[] compressed) throws IOException;
}
