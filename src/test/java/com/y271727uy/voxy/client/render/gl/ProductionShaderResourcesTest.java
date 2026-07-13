package com.y271727uy.voxy.client.render.gl;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ProductionShaderResourcesTest {
    private static final Path SHADER_ROOT = Path.of(
            "src", "main", "resources", "assets", "voxy", "shaders");
    private static final Set<String> PRODUCTION_CLOSURE = Set.of(
            "hiz/blit.vsh",
            "hiz/blit.fsh",
            "lod/hierarchical/traversal_dev.comp",
            "lod/hierarchical/queue.glsl",
            "lod/hierarchical/node.glsl",
            "lod/hierarchical/screenspace.glsl",
            "lod/frustum.glsl",
            "lod/pos_util.glsl");
    private static final Pattern VOXY_IMPORT = Pattern.compile(
            "(?m)^\\s*#import\\s+<voxy:([^>]+)>\\s*$");

    @Test
    public void productionTraversalResourcesExistAndAreNonEmpty() throws IOException {
        for (String resource : PRODUCTION_CLOSURE) {
            Path path = SHADER_ROOT.resolve(resource);
            assertTrue("Missing production shader " + resource, Files.isRegularFile(path));
            assertTrue("Empty production shader " + resource, Files.size(path) > 0);
        }
    }

    @Test
    public void everyVoxyImportResolvesInsideTheProductionClosure() throws IOException {
        ArrayDeque<String> pending = new ArrayDeque<>(Set.of(
                "hiz/blit.vsh", "hiz/blit.fsh", "lod/hierarchical/traversal_dev.comp"));
        Set<String> visited = new HashSet<>();

        while (!pending.isEmpty()) {
            String resource = pending.removeFirst();
            if (!visited.add(resource)) {
                continue;
            }

            Matcher imports = VOXY_IMPORT.matcher(Files.readString(SHADER_ROOT.resolve(resource)));
            while (imports.find()) {
                String imported = imports.group(1);
                assertTrue("Import escaped production closure: " + resource + " -> " + imported,
                        PRODUCTION_CLOSURE.contains(imported));
                assertTrue("Unresolved shader import: " + resource + " -> " + imported,
                        Files.isRegularFile(SHADER_ROOT.resolve(imported)));
                pending.addLast(imported);
            }
        }

        assertTrue("Not every production shader is reachable from an entry point",
                visited.containsAll(PRODUCTION_CLOSURE));
    }

    @Test
    public void experimentalShaderFamiliesAreNotBundled() {
        assertFalse(Files.exists(SHADER_ROOT.resolve("hiz/hiz.comp")));
        assertFalse(Files.exists(SHADER_ROOT.resolve("lod/hierarchical/cleaner")));
        assertFalse(Files.exists(SHADER_ROOT.resolve("lod/hierarchical/debug")));
        assertFalse(Files.exists(SHADER_ROOT.resolve("lod/gl46")));
    }
}
