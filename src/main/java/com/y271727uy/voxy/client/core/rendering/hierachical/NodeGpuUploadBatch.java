package com.y271727uy.voxy.client.core.rendering.hierachical;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable, coalesced updates for absolute writes into the traversal node SSBO.
 * Dirty-id draining and capture must be coordinated by the owning {@link NodeManager}; this class
 * only keeps the encoded {@link NodeStore} words coherent while holding the store monitor.
 */
public final class NodeGpuUploadBatch {
    private final List<Span> spans;

    private NodeGpuUploadBatch(List<Span> spans) {
        this.spans = List.copyOf(spans);
    }

    public static NodeGpuUploadBatch capture(NodeStore store, int... dirtyNodeIds) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(dirtyNodeIds, "dirtyNodeIds");
        int[] ids = Arrays.stream(dirtyNodeIds).sorted().distinct().toArray();
        synchronized (store) {
            for (int id : ids) {
                if (id < 0 || id >= store.getLimit()) {
                    throw new IllegalArgumentException("Dirty node outside store: " + id);
                }
            }
            List<Span> spans = new ArrayList<>();
            int cursor = 0;
            while (cursor < ids.length) {
                int first = ids[cursor];
                int end = first + 1;
                cursor++;
                while (cursor < ids.length && ids[cursor] == end) {
                    end++;
                    cursor++;
                }
                spans.add(new Span(first, end - first,
                        NodeGpuTraversalSnapshot.encodeRange(store, first, end - first)));
            }
            return new NodeGpuUploadBatch(spans);
        }
    }

    /**
     * Coalesces already-encoded worker publications without consulting the live node store.
     * Data from {@code newer} replaces older data at the same absolute node id.
     */
    public NodeGpuUploadBatch merge(NodeGpuUploadBatch newer) {
        Objects.requireNonNull(newer, "newer");
        Map<Integer, byte[]> nodes = new LinkedHashMap<>();
        collectNodes(this.spans, nodes);
        collectNodes(newer.spans, nodes);
        if (nodes.isEmpty()) return new NodeGpuUploadBatch(List.of());

        int[] ids = nodes.keySet().stream().mapToInt(Integer::intValue).sorted().toArray();
        List<Span> merged = new ArrayList<>();
        int cursor = 0;
        while (cursor < ids.length) {
            int first = ids[cursor];
            int endCursor = cursor + 1;
            while (endCursor < ids.length && ids[endCursor] == ids[endCursor - 1] + 1) endCursor++;
            int count = endCursor - cursor;
            byte[] data = new byte[Math.multiplyExact(count, NodeGpuAbi.BYTES_PER_NODE)];
            for (int index = 0; index < count; index++) {
                System.arraycopy(nodes.get(ids[cursor + index]), 0, data,
                        index * NodeGpuAbi.BYTES_PER_NODE, NodeGpuAbi.BYTES_PER_NODE);
            }
            merged.add(new Span(first, count, data));
            cursor = endCursor;
        }
        return new NodeGpuUploadBatch(merged);
    }

    private static void collectNodes(List<Span> spans, Map<Integer, byte[]> output) {
        for (Span span : spans) {
            byte[] data = span.data;
            for (int index = 0; index < span.nodeCount; index++) {
                output.put(span.firstNodeId + index, Arrays.copyOfRange(data,
                        index * NodeGpuAbi.BYTES_PER_NODE,
                        (index + 1) * NodeGpuAbi.BYTES_PER_NODE));
            }
        }
    }

    public List<Span> spans() {
        return this.spans;
    }

    public int nodeCount() {
        return this.spans.stream().mapToInt(Span::nodeCount).sum();
    }

    public record Span(int firstNodeId, int nodeCount, byte[] data) {
        public Span {
            if (firstNodeId < 0 || nodeCount <= 0
                    || data.length != nodeCount * NodeGpuAbi.BYTES_PER_NODE) {
                throw new IllegalArgumentException("Invalid node upload span");
            }
            data = data.clone();
        }

        @Override
        public byte[] data() {
            return this.data.clone();
        }

        public long byteOffset() {
            return (long) this.firstNodeId * NodeGpuAbi.BYTES_PER_NODE;
        }

        public NodeGpuAbi.CompactedNode node(int relativeNodeId) {
            if (relativeNodeId < 0 || relativeNodeId >= this.nodeCount) {
                throw new IndexOutOfBoundsException("Relative node outside span: " + relativeNodeId);
            }
            return NodeGpuAbi.decode(this.data, relativeNodeId * NodeGpuAbi.BYTES_PER_NODE);
        }
    }
}
