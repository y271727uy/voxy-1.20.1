package com.y271727uy.voxy.common.storage;

import com.y271727uy.voxy.common.storage.provider.StorageBackendUnavailableException;
import com.y271727uy.voxy.common.voxelization.PackedVoxel;
import com.y271727uy.voxy.common.world.WorldSection;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.Rule;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class StorageRuntimeFactoryTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void fileGraphPreservesExistingLayoutThroughAsyncBarrier() throws Exception {
        var runtime = StorageRuntimeFactory.open(temporary.newFolder("file").toPath(), "file", 8);
        WorldSection source = new WorldSection(0, 1, 2, 3);
        source.set(0, 0, 0, PackedVoxel.compose(0xFF, 42, 0));
        runtime.storage().saveSection(source);
        runtime.storage().flush();

        WorldSection loaded = new WorldSection(0, 1, 2, 3);
        assertEquals(SectionStorage.LOAD_SUCCESS, runtime.storage().loadSection(loaded));
        assertEquals(42, PackedVoxel.blockId(loaded.get(0, 0, 0)));
        assertEquals("file", runtime.graphConfig().root().type());
        assertTrue(runtime.storage().metrics().completedCommands() >= 3);
        runtime.storage().close();
    }

    @Test
    public void unavailableExternalBackendFailsBeforeCreatingRuntime() {
        assertThrows(StorageBackendUnavailableException.class,
                () -> StorageRuntimeFactory.open(temporary.getRoot().toPath(), "rocksdb", 8));
    }
}
