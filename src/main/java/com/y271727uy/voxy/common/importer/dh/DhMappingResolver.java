package com.y271727uy.voxy.common.importer.dh;

@FunctionalInterface
public interface DhMappingResolver {
    long resolve(String biomeId, String blockState);
}
