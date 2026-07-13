package com.y271727uy.voxy.client.render.mesh;

final class VertexColorUtil {
    private VertexColorUtil() {
    }

    static int multiplyAlpha(int sourceAbgr, int tintArgb) {
        int sourceAlpha = (sourceAbgr >>> 24) & 0xFF;
        int tintAlpha = (tintArgb >>> 24) & 0xFF;
        return (sourceAlpha * tintAlpha + 127) / 255;
    }
}
