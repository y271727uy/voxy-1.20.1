package com.y271727uy.voxy.common.importer.dh;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public record DhImportSource(Path database) {
    public static final String DATABASE_NAME = "DistantHorizons.sqlite";
    private static final byte[] SQLITE_HEADER = new byte[]{
            'S', 'Q', 'L', 'i', 't', 'e', ' ', 'f', 'o', 'r', 'm', 'a', 't', ' ', '3', 0};

    public DhImportSource {
        database = database.toAbsolutePath().normalize();
    }

    public static DhImportSource inspect(Path path) throws IOException {
        return inspect(path, DhImportCancellation.never());
    }

    public static DhImportSource inspect(Path path, DhImportCancellation cancellation) throws IOException {
        cancellation.throwIfCancelled();
        Path candidate = path.toAbsolutePath().normalize();
        if (Files.isDirectory(candidate)) candidate = candidate.resolve(DATABASE_NAME);
        if (!Files.isRegularFile(candidate) || !Files.isReadable(candidate)) {
            throw new IOException("Distant Horizons database is not a readable file: " + candidate);
        }
        byte[] header = new byte[SQLITE_HEADER.length];
        try (InputStream input = Files.newInputStream(candidate)) {
            int offset = 0;
            while (offset < header.length) {
                cancellation.throwIfCancelled();
                int read = input.read(header, offset, header.length - offset);
                if (read < 0) break;
                offset += read;
            }
        }
        if (!Arrays.equals(SQLITE_HEADER, header)) {
            throw new IOException("File is not a SQLite 3 database: " + candidate);
        }
        cancellation.throwIfCancelled();
        return new DhImportSource(candidate);
    }
}
