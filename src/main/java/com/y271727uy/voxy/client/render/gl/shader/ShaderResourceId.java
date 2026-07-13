package com.y271727uy.voxy.client.render.gl.shader;

import java.util.Objects;
import java.util.regex.Pattern;

/** A validated shader resource identifier, independent of Minecraft classes. */
public record ShaderResourceId(String namespace, String path) {
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern PATH = Pattern.compile("[a-z0-9/._-]+");

    public ShaderResourceId {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(path, "path");
        if (!NAMESPACE.matcher(namespace).matches() || namespace.equals(".") || namespace.equals("..")) {
            throw new IllegalArgumentException("Invalid shader namespace: " + namespace);
        }
        if (!PATH.matcher(path).matches() || path.startsWith("/") || path.endsWith("/")) {
            throw new IllegalArgumentException("Invalid shader path: " + path);
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("Invalid shader path segment in: " + path);
            }
        }
    }

    public static ShaderResourceId parse(String value) {
        Objects.requireNonNull(value, "value");
        int separator = value.indexOf(':');
        if (separator <= 0 || separator != value.lastIndexOf(':') || separator == value.length() - 1) {
            throw new IllegalArgumentException("Shader resource must be namespace:path: " + value);
        }
        return new ShaderResourceId(value.substring(0, separator), value.substring(separator + 1));
    }

    @Override
    public String toString() {
        return namespace + ':' + path;
    }
}
