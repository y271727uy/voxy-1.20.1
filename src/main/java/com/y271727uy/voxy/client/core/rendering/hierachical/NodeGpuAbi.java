package com.y271727uy.voxy.client.core.rendering.hierachical;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Authoritative CPU/GPU layout for the uvec4 node buffer consumed by Voxy traversal shaders. */
public final class NodeGpuAbi {
    public static final int VERSION = 1;
    public static final int BYTES_PER_NODE = 16;
    public static final int WORDS_PER_NODE = 4;
    public static final int BYTES_PER_ROOT_NODE_ID = 4;
    public static final int REQUEST_QUEUE_HEADER_BYTES = 8;
    public static final int BYTES_PER_REQUEST = 8;
    public static final int RENDER_QUEUE_HEADER_BYTES = 4;
    public static final int BYTES_PER_RENDER_ENTRY = 4;

    private NodeGpuAbi() {
    }

    public static CompactedNode decode(byte[] data, int byteOffset) {
        if (byteOffset < 0 || byteOffset + BYTES_PER_NODE > data.length) {
            throw new IndexOutOfBoundsException("Node byte offset outside snapshot: " + byteOffset);
        }
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.nativeOrder());
        int positionHigh = buffer.getInt(byteOffset);
        int positionLow = buffer.getInt(byteOffset + 4);
        int geometryAndFlags = buffer.getInt(byteOffset + 8);
        int childAndFlags = buffer.getInt(byteOffset + 12);
        long position = ((long) positionHigh << 32) | Integer.toUnsignedLong(positionLow);
        int geometry = geometryAndFlags & NodeStore.GEOMETRY_ID_MSK;
        int childPointer = childAndFlags & NodeStore.NODE_ID_MSK;
        int flags = (geometryAndFlags >>> 24) | ((childAndFlags >>> 24) << 8);
        return new CompactedNode(position, geometry, childPointer, flags,
                positionHigh == -1 && positionLow == -1
                        && geometryAndFlags == -1 && childAndFlags == -1);
    }

    public record CompactedNode(long position, int geometry, int childPointer, int flags,
                                boolean tombstone) {
        public boolean requestInFlight() {
            return (this.flags & 1) != 0;
        }

        public int childPointerCount() {
            return ((this.flags >>> 2) & 7) + 1;
        }

        public boolean eligibleForCleaning() {
            return (this.flags & (1 << 5)) != 0;
        }
    }
}
