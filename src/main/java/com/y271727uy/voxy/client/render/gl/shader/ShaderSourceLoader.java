package com.y271727uy.voxy.client.render.gl.shader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Expands Voxy shader imports without depending on a render thread or GL context. */
public final class ShaderSourceLoader {
    public static final String GLSL_VERSION = "#version 460 core";
    private static final Pattern IMPORT = Pattern.compile("#import <([^:>]+):([^>]+)>");

    private final ShaderSourceProvider provider;

    public ShaderSourceLoader(ShaderSourceProvider provider) {
        this.provider = Objects.requireNonNull(provider, "provider");
    }

    public static ShaderSourceLoader classpath() {
        return new ShaderSourceLoader(new ClasspathShaderSourceProvider());
    }

    /** Loads a complete compilation unit with the authoritative Voxy GLSL version header. */
    public String load(String rootId) {
        return load(ShaderResourceId.parse(rootId));
    }

    public String load(ShaderResourceId rootId) {
        Objects.requireNonNull(rootId, "rootId");
        List<String> output = new ArrayList<>();
        expand(rootId, new ArrayDeque<>(), output);
        return GLSL_VERSION + '\n' + String.join("\n", output);
    }

    private void expand(ShaderResourceId id, Deque<ShaderResourceId> stack, List<String> output) {
        if (stack.contains(id)) {
            List<ShaderResourceId> cycle = new ArrayList<>(stack);
            cycle.add(id);
            throw new ShaderSourceException("Shader import cycle: " + cycle.stream()
                    .map(ShaderResourceId::toString)
                    .collect(Collectors.joining(" -> ")));
        }

        stack.addLast(id);
        try {
            for (String line : lines(loadSource(id, stack))) {
                if (line.startsWith("#version")) {
                    continue;
                }
                if (line.startsWith("#import")) {
                    Matcher matcher = IMPORT.matcher(line);
                    if (!matcher.matches()) {
                        throw new ShaderSourceException("Invalid shader import in " + id + ": " + line);
                    }
                    final ShaderResourceId imported;
                    try {
                        imported = new ShaderResourceId(matcher.group(1), matcher.group(2));
                    } catch (IllegalArgumentException invalidId) {
                        throw new ShaderSourceException("Invalid shader import in " + id + ": " + line,
                                invalidId);
                    }
                    expand(imported, stack, output);
                } else {
                    output.add(line);
                }
            }
        } finally {
            stack.removeLast();
        }
    }

    private String loadSource(ShaderResourceId id, Deque<ShaderResourceId> stack) {
        try {
            String source = provider.load(id);
            if (source == null) {
                throw new IOException("Provider returned null");
            }
            return source;
        } catch (IOException failure) {
            throw new ShaderSourceException("Failed to load shader " + id + " (import chain: "
                    + stack.stream().map(ShaderResourceId::toString).collect(Collectors.joining(" -> ")) + ')',
                    failure);
        }
    }

    private static List<String> lines(String source) {
        return new BufferedReader(new StringReader(source)).lines().toList();
    }
}
