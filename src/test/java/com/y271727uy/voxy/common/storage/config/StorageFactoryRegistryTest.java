package com.y271727uy.voxy.common.storage.config;

import com.google.gson.JsonObject;
import com.y271727uy.voxy.common.storage.SectionStorage;
import com.y271727uy.voxy.common.world.WorldSection;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.LongConsumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class StorageFactoryRegistryTest {
    @Test
    public void nestedNodesCloseFromParentToLeaf() {
        ArrayList<String> events = new ArrayList<>();
        StorageFactoryRegistry registry = new StorageFactoryRegistry()
                .register(factory("Memory", 0, 0, List.of(),
                        (context, options, children) -> new TrackingStorage("leaf", events, false)))
                .register(factory("CompressionAdaptor", 1, 1, List.of(),
                        (context, options, children) -> new TrackingStorage("parent", events, false)));
        StorageNodeConfig root = node("CompressionAdaptor", node("Memory"));

        StorageGraph graph = registry.build(new StorageGraphConfig(root), context(Map.of()));
        graph.close();
        graph.close();

        assertEquals(List.of("parent", "leaf"), events);
        assertThrows(IllegalStateException.class, graph::flush);
    }

    @Test
    public void completePreflightRejectsUnknownTypesBeforeCreatingResources() {
        ArrayList<String> events = new ArrayList<>();
        StorageFactoryRegistry registry = new StorageFactoryRegistry()
                .register(factory("Parent", 1, 1, List.of(),
                        (context, options, children) -> new TrackingStorage("parent", events, false)));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> registry.build(new StorageGraphConfig(node("Parent", node("Missing"))), context(Map.of())));

        assertTrue(failure.getMessage().contains("Unknown storage node type 'Missing'"));
        assertEquals(List.of(), events);
    }

    @Test
    public void missingOptionalBackendDependencyFailsBeforeAllocation() {
        ArrayList<String> events = new ArrayList<>();
        StorageNodeFactory.Dependency rocks = new StorageNodeFactory.Dependency("rocksdb", Runnable.class);
        StorageFactoryRegistry registry = new StorageFactoryRegistry()
                .register(factory("RocksDB", 0, 0, List.of(rocks),
                        (context, options, children) -> new TrackingStorage("rocks", events, false)));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> registry.build(new StorageGraphConfig(node("RocksDB")), context(Map.of())));

        assertTrue(failure.getMessage().contains("unavailable dependency 'rocksdb'"));
        assertEquals(List.of(), events);
    }

    @Test
    public void partialBuildFailureClosesCreatedChildrenAndPreservesCloseFailure() {
        ArrayList<String> events = new ArrayList<>();
        StorageFactoryRegistry registry = new StorageFactoryRegistry()
                .register(factory("Leaf", 0, 0, List.of(),
                        (context, options, children) -> new TrackingStorage("leaf", events, true)))
                .register(factory("BrokenParent", 1, 1, List.of(), (context, options, children) -> {
                    throw new IllegalArgumentException("cannot connect");
                }));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> registry.build(new StorageGraphConfig(node("BrokenParent", node("Leaf"))), context(Map.of())));

        assertEquals(List.of("leaf"), events);
        assertEquals(1, failure.getSuppressed().length);
        assertTrue(failure.getCause().getMessage().contains("cannot connect"));
    }

    @Test
    public void validatesChildArityAndOptions() {
        StorageNodeFactory validating = new StorageNodeFactory() {
            @Override
            public String type() {
                return "Adaptor";
            }

            @Override
            public int minimumChildren() {
                return 1;
            }

            @Override
            public int maximumChildren() {
                return 1;
            }

            @Override
            public void validateOptions(JsonObject options) {
                if (!options.has("codec")) {
                    throw new IllegalArgumentException("codec is required");
                }
            }

            @Override
            public SectionStorage create(StorageBuildContext context, JsonObject options,
                                         List<SectionStorage> children) {
                return children.get(0);
            }
        };
        StorageFactoryRegistry registry = new StorageFactoryRegistry().register(validating);

        IllegalArgumentException arity = assertThrows(IllegalArgumentException.class,
                () -> registry.build(new StorageGraphConfig(node("Adaptor")), context(Map.of())));
        assertTrue(arity.getMessage().contains("requires 1 children"));

        IllegalArgumentException options = assertThrows(IllegalArgumentException.class,
                () -> registry.build(new StorageGraphConfig(node("Adaptor", node("Adaptor"))), context(Map.of())));
        assertTrue(options.getMessage().contains("codec is required"));
    }

    @Test
    public void contextRejectsPathsOutsideStorageRootAndWrongDependencyTypes() {
        StorageBuildContext context = context(Map.of("redis", "not-a-client"));
        assertThrows(IllegalArgumentException.class, () -> context.resolvePath("../outside"));
        assertThrows(IllegalArgumentException.class, () -> context.resolvePath(Path.of("C:/absolute").toString()));
        assertThrows(IllegalStateException.class, () -> context.requireDependency("redis", Runnable.class));
    }

    private static StorageBuildContext context(Map<String, ?> dependencies) {
        return new StorageBuildContext(Path.of("build", "storage-graph-test"), dependencies);
    }

    private static StorageNodeConfig node(String type, StorageNodeConfig... children) {
        return new StorageNodeConfig(type, new JsonObject(), List.of(children));
    }

    private static StorageNodeFactory factory(String type, int minimumChildren, int maximumChildren,
                                              List<StorageNodeFactory.Dependency> dependencies, Creator creator) {
        return new StorageNodeFactory() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public int minimumChildren() {
                return minimumChildren;
            }

            @Override
            public int maximumChildren() {
                return maximumChildren;
            }

            @Override
            public List<Dependency> requiredDependencies() {
                return dependencies;
            }

            @Override
            public SectionStorage create(StorageBuildContext context, JsonObject options,
                                         List<SectionStorage> children) {
                return creator.create(context, options, children);
            }
        };
    }

    private interface Creator {
        SectionStorage create(StorageBuildContext context, JsonObject options, List<SectionStorage> children);
    }

    private static final class TrackingStorage implements SectionStorage {
        private final String name;
        private final List<String> events;
        private final boolean failClose;

        private TrackingStorage(String name, List<String> events, boolean failClose) {
            this.name = name;
            this.events = events;
            this.failClose = failClose;
        }

        @Override
        public int loadSection(WorldSection section) {
            return LOAD_ABSENT;
        }

        @Override
        public void saveSection(WorldSection section) {
        }

        @Override
        public void iteratePositions(int level, LongConsumer consumer) {
        }

        @Override
        public void putIdMapping(int key, ByteBuffer data) {
        }

        @Override
        public Int2ObjectOpenHashMap<byte[]> getIdMappingsData() {
            return new Int2ObjectOpenHashMap<>();
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
            this.events.add(this.name);
            if (this.failClose) {
                throw new IllegalStateException("close failed: " + this.name);
            }
        }
    }
}
