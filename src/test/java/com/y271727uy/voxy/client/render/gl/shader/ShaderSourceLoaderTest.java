package com.y271727uy.voxy.client.render.gl.shader;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ShaderSourceLoaderTest {
    @Test
    public void recursivelyExpandsImportsInSourceOrder() {
        MapProvider sources = new MapProvider()
                .put("voxy:root.vert", "#version 330\r\nroot-a\r\n#import <voxy:common/a.glsl>\r\nroot-b")
                .put("voxy:common/a.glsl", "a\n#import <voxy:common/b.glsl>\na-end")
                .put("voxy:common/b.glsl", "#version 460 core\nb");

        String loaded = new ShaderSourceLoader(sources).load("voxy:root.vert");

        assertEquals("#version 460 core\nroot-a\na\nb\na-end\nroot-b", loaded);
    }

    @Test
    public void repeatedImportsAreExpandedAtEachDeterministicPosition() {
        MapProvider sources = new MapProvider()
                .put("voxy:root.comp", "#import <voxy:value.glsl>\nmiddle\n#import <voxy:value.glsl>")
                .put("voxy:value.glsl", "value");

        assertEquals("#version 460 core\nvalue\nmiddle\nvalue",
                new ShaderSourceLoader(sources).load("voxy:root.comp"));
    }

    @Test
    public void rejectsMalformedImportsInsteadOfPassingThemToGlsl() {
        MapProvider sources = new MapProvider().put("voxy:root.vert", "#import voxy:common.glsl");

        ShaderSourceException failure = assertThrows(ShaderSourceException.class,
                () -> new ShaderSourceLoader(sources).load("voxy:root.vert"));
        assertTrue(failure.getMessage().contains("Invalid shader import"));
    }

    @Test
    public void resourceIdsRejectInvalidNamespacesAndTraversal() {
        assertThrows(IllegalArgumentException.class, () -> ShaderResourceId.parse("Voxy:root.vert"));
        assertThrows(IllegalArgumentException.class, () -> ShaderResourceId.parse("..:root.vert"));
        assertThrows(IllegalArgumentException.class, () -> ShaderResourceId.parse("voxy:../secret"));
        assertThrows(IllegalArgumentException.class, () -> ShaderResourceId.parse("voxy:a/./b"));
        assertThrows(IllegalArgumentException.class, () -> ShaderResourceId.parse("voxy:a//b"));
        assertThrows(IllegalArgumentException.class, () -> ShaderResourceId.parse("voxy/root.vert"));
        assertThrows(IllegalArgumentException.class, () -> ShaderResourceId.parse("voxy:a:b"));
    }

    @Test
    public void importedResourceIdsUseTheSameStrictValidation() {
        MapProvider sources = new MapProvider().put("voxy:root.vert", "#import <voxy:../secret>");

        ShaderSourceException failure = assertThrows(ShaderSourceException.class,
                () -> new ShaderSourceLoader(sources).load("voxy:root.vert"));
        assertTrue(failure.getMessage().contains("voxy:../secret"));
    }

    @Test
    public void missingSourcesReportTheFullImportChain() {
        MapProvider sources = new MapProvider()
                .put("voxy:root.vert", "#import <voxy:a.glsl>")
                .put("voxy:a.glsl", "#import <voxy:missing.glsl>");

        ShaderSourceException failure = assertThrows(ShaderSourceException.class,
                () -> new ShaderSourceLoader(sources).load("voxy:root.vert"));
        assertTrue(failure.getMessage().contains(
                "voxy:root.vert -> voxy:a.glsl -> voxy:missing.glsl"));
    }

    @Test
    public void cyclesReportTheDeterministicCycleChain() {
        MapProvider sources = new MapProvider()
                .put("voxy:a.glsl", "#import <voxy:b.glsl>")
                .put("voxy:b.glsl", "#import <voxy:a.glsl>");

        ShaderSourceException failure = assertThrows(ShaderSourceException.class,
                () -> new ShaderSourceLoader(sources).load("voxy:a.glsl"));
        assertEquals("Shader import cycle: voxy:a.glsl -> voxy:b.glsl -> voxy:a.glsl",
                failure.getMessage());
    }

    @Test
    public void classpathProviderUsesAssetShaderPathAndUtf8() throws IOException {
        String expectedPath = "assets/voxy/shaders/util/depth.glsl";
        ClassLoader loader = new ClassLoader(null) {
            @Override
            public InputStream getResourceAsStream(String name) {
                if (!expectedPath.equals(name)) return null;
                return new ByteArrayInputStream("const int marker = 1; // \u6df1\u5ea6"
                        .getBytes(StandardCharsets.UTF_8));
            }
        };

        ClasspathShaderSourceProvider provider = new ClasspathShaderSourceProvider(loader);
        assertEquals("const int marker = 1; // \u6df1\u5ea6",
                provider.load(ShaderResourceId.parse("voxy:util/depth.glsl")));
        assertThrows(FileNotFoundException.class,
                () -> provider.load(ShaderResourceId.parse("voxy:missing.glsl")));
    }

    @Test
    public void classpathLoaderExpandsProductionTraversalClosure() {
        String source = ShaderSourceLoader.classpath().load(
                "voxy:lod/hierarchical/traversal_dev.comp");

        assertTrue(source.startsWith("#version 460 core\n"));
        assertTrue(source.contains("struct UnpackedNode"));
        assertTrue(source.contains("bool isCulledByHiz()"));
        assertTrue(source.contains("void main()"));
        assertTrue(!source.contains("#import"));
    }

    private static final class MapProvider implements ShaderSourceProvider {
        private final Map<ShaderResourceId, String> sources = new HashMap<>();

        private MapProvider put(String id, String source) {
            sources.put(ShaderResourceId.parse(id), source);
            return this;
        }

        @Override
        public String load(ShaderResourceId id) throws IOException {
            String source = sources.get(id);
            if (source == null) throw new FileNotFoundException(id.toString());
            return source;
        }
    }
}
