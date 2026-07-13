package com.y271727uy.voxy.common.importer.dh;

import java.util.Objects;

public record DhImportAvailability(Capability sqlite, Capability xz, Capability zstd) {
    public enum Status {
        AVAILABLE,
        MISSING_DEPENDENCY,
        ADAPTER_NOT_LINKED
    }

    public record Capability(String className, Status status) {
        public Capability {
            Objects.requireNonNull(className, "className");
            Objects.requireNonNull(status, "status");
        }

        public boolean isAvailable() {
            return this.status == Status.AVAILABLE;
        }
    }

    public boolean isAvailableForCompression(int compressionMode) {
        if (!this.sqlite.isAvailable()) return false;
        return switch (compressionMode) {
            case 3 -> this.xz.isAvailable();
            case 4 -> this.zstd.isAvailable();
            default -> false;
        };
    }

    public Capability compressionCapability(int compressionMode) {
        return switch (compressionMode) {
            case 3 -> this.xz;
            case 4 -> this.zstd;
            default -> throw new IllegalArgumentException("Unsupported Distant Horizons compression mode: "
                    + compressionMode);
        };
    }

    public static DhImportAvailability inspect(DhDependencyProbe probe, boolean sqliteAdapterLinked,
                                                boolean xzAdapterLinked, boolean zstdAdapterLinked) {
        return new DhImportAvailability(
                inspect(probe, "org.sqlite.JDBC", sqliteAdapterLinked),
                inspect(probe, "org.tukaani.xz.XZInputStream", xzAdapterLinked),
                inspect(probe, "org.lwjgl.util.zstd.Zstd", zstdAdapterLinked));
    }

    public static DhImportAvailability runtime() {
        return inspect(DhDependencyProbe.runtime(), true, false, false);
    }

    private static Capability inspect(DhDependencyProbe probe, String className, boolean adapterLinked) {
        if (!probe.isPresent(className)) return new Capability(className, Status.MISSING_DEPENDENCY);
        return new Capability(className, adapterLinked ? Status.AVAILABLE : Status.ADAPTER_NOT_LINKED);
    }
}
