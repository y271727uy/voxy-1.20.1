package com.y271727uy.voxy.client.render.gl.shader;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Loads {@code namespace:path} from {@code assets/namespace/shaders/path}. */
public final class ClasspathShaderSourceProvider implements ShaderSourceProvider {
    private final ClassLoader classLoader;

    public ClasspathShaderSourceProvider() {
        this(contextClassLoader());
    }

    public ClasspathShaderSourceProvider(ClassLoader classLoader) {
        this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
    }

    @Override
    public String load(ShaderResourceId id) throws IOException {
        Objects.requireNonNull(id, "id");
        String resourcePath = resourcePath(id);
        try (InputStream stream = classLoader.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new FileNotFoundException("Shader source not found: " + resourcePath);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static String resourcePath(ShaderResourceId id) {
        return "assets/" + id.namespace() + "/shaders/" + id.path();
    }

    private static ClassLoader contextClassLoader() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return loader != null ? loader : ClasspathShaderSourceProvider.class.getClassLoader();
    }
}
