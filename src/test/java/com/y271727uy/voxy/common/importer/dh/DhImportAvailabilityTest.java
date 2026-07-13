package com.y271727uy.voxy.common.importer.dh;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class DhImportAvailabilityTest {
    @Test
    public void missingDependenciesFailClosedWithExactClasses() {
        Set<String> present = Set.of("org.lwjgl.util.zstd.Zstd");
        DhImportAvailability availability = DhImportAvailability.inspect(present::contains,
                true, true, true);

        assertEquals(DhImportAvailability.Status.MISSING_DEPENDENCY, availability.sqlite().status());
        assertEquals(DhImportAvailability.Status.MISSING_DEPENDENCY, availability.xz().status());
        assertEquals(DhImportAvailability.Status.AVAILABLE, availability.zstd().status());
        assertFalse(availability.isAvailableForCompression(3));
        assertFalse(availability.isAvailableForCompression(4));
    }

    @Test
    public void classPresenceDoesNotClaimUnlinkedAdapters() {
        DhImportAvailability availability = DhImportAvailability.inspect(ignored -> true,
                true, false, false);

        assertEquals(DhImportAvailability.Status.AVAILABLE, availability.sqlite().status());
        assertEquals(DhImportAvailability.Status.ADAPTER_NOT_LINKED, availability.xz().status());
        assertEquals(DhImportAvailability.Status.ADAPTER_NOT_LINKED, availability.zstd().status());
        assertFalse(availability.isAvailableForCompression(3));
    }

    @Test
    public void compressionModesHaveIndependentCapabilities() {
        Set<String> present = Set.of("org.sqlite.JDBC", "org.tukaani.xz.XZInputStream");
        DhImportAvailability availability = DhImportAvailability.inspect(present::contains,
                true, true, true);

        assertTrue(availability.isAvailableForCompression(3));
        assertFalse(availability.isAvailableForCompression(4));
    }

    @Test
    public void sourceAcceptsDirectoryAndChecksSqliteMagic() throws IOException {
        Path directory = Files.createTempDirectory("voxy-dh-source");
        Path database = directory.resolve(DhImportSource.DATABASE_NAME);
        Files.write(database, new byte[]{'S', 'Q', 'L', 'i', 't', 'e', ' ', 'f', 'o', 'r', 'm', 'a', 't', ' ', '3', 0});

        assertEquals(database.toAbsolutePath(), DhImportSource.inspect(directory).database());
        Files.write(database, new byte[]{1, 2, 3});
        assertThrows(IOException.class, () -> DhImportSource.inspect(database));
    }

    @Test
    public void sourceDiscoveryHonorsCancellationBeforeFilesystemRead() throws IOException {
        Path missing = Files.createTempDirectory("voxy-dh-cancel").resolve("missing");
        assertThrows(DhImportCancelledException.class,
                () -> DhImportSource.inspect(missing, () -> true));
    }
}
