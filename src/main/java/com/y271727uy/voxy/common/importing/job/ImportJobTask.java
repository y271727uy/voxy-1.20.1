package com.y271727uy.voxy.common.importing.job;

@FunctionalInterface
public interface ImportJobTask extends AutoCloseable {
    void execute(ImportJobContext context) throws Exception;

    @Override
    default void close() throws Exception {
    }
}
