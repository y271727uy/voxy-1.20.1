package com.y271727uy.voxy.client.render.gl.shader;

import java.io.IOException;

@FunctionalInterface
public interface ShaderSourceProvider {
    String load(ShaderResourceId id) throws IOException;
}
