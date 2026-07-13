package com.y271727uy.voxy.common.importer.chunky;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Discovers standard Anvil region files produced by Minecraft and Chunky. */
public final class RegionSource {
    private static final Pattern REGION_NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

    private RegionSource() {
    }

    public static List<RegionFile> discover(Path source) throws IOException {
        Path root = source.toAbsolutePath().normalize();
        requireDirectory(root, "import source");
        Path regionDirectory = Files.isDirectory(root.resolve("region"), LinkOption.NOFOLLOW_LINKS)
                ? root.resolve("region") : root;
        requireDirectory(regionDirectory, "region directory");
        if (!regionDirectory.normalize().startsWith(root)) {
            throw new IOException("Region directory escapes import source: " + regionDirectory);
        }

        List<RegionFile> result = new ArrayList<>();
        try (var files = Files.list(regionDirectory)) {
            files.forEach(path -> {
                Matcher matcher = REGION_NAME.matcher(path.getFileName().toString());
                if (!matcher.matches() || Files.isSymbolicLink(path)
                        || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    return;
                }
                try {
                    int x = Integer.parseInt(matcher.group(1));
                    int z = Integer.parseInt(matcher.group(2));
                    Path normalized = path.toAbsolutePath().normalize();
                    if (normalized.getParent().equals(regionDirectory)) {
                        result.add(new RegionFile(normalized, x, z));
                    }
                } catch (NumberFormatException ignored) {
                    // Coordinates outside the integer world range are not importable.
                }
            });
        }
        result.sort(Comparator.comparingInt(RegionFile::regionX)
                .thenComparingInt(RegionFile::regionZ)
                .thenComparing(file -> file.path().getFileName().toString()));
        return List.copyOf(result);
    }

    private static void requireDirectory(Path path, String description) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Invalid " + description + ": " + path);
        }
    }

    public record RegionFile(Path path, int regionX, int regionZ) {
    }
}
