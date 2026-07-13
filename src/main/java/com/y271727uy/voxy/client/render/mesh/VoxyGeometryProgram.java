package com.y271727uy.voxy.client.render.mesh;

import org.joml.Matrix4f;

interface VoxyGeometryProgram {
    void bind(Matrix4f modelView, Matrix4f projection);
    void unbind();
}
