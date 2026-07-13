package com.y271727uy.voxy.common.importer.dh;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public final class DhSqliteFullDataSource implements DhIndexedRecordSource {
    private static final String INDEX_QUERY = "SELECT PosX, PosZ, DetailLevel, DataFormatVersion, "
            + "CompressionMode FROM FullData WHERE DetailLevel = 0";
    private static final String FETCH_QUERY = "SELECT DataFormatVersion, CompressionMode, Data, Mapping FROM FullData "
            + "WHERE DetailLevel = ? AND PosX = ? AND PosZ = ?";
    private final Connection connection;

    private DhSqliteFullDataSource(Connection connection) {
        this.connection = connection;
    }

    public static DhSqliteFullDataSource open(DhImportSource source) throws IOException {
        if (!DhDependencyProbe.runtime().isPresent("org.sqlite.JDBC")) {
            throw new IOException("Distant Horizons import is unavailable: missing org.sqlite.JDBC");
        }
        try {
            Connection connection = DriverManager.getConnection("jdbc:sqlite:" + source.database());
            try (Statement statement = connection.createStatement()) {
                statement.executeQuery(INDEX_QUERY + " LIMIT 0").close();
            } catch (SQLException exception) {
                connection.close();
                throw exception;
            }
            return new DhSqliteFullDataSource(connection);
        } catch (SQLException exception) {
            throw new IOException("Unable to open Distant Horizons FullData database", exception);
        }
    }

    @Override
    public List<DhFullDataIndex> scan(DhImportCancellation cancellation) throws IOException {
        List<DhFullDataIndex> records = new ArrayList<>();
        cancellation.throwIfCancelled();
        try (Statement statement = this.connection.createStatement();
             ResultSet rows = statement.executeQuery(INDEX_QUERY)) {
            while (rows.next()) {
                cancellation.throwIfCancelled();
                records.add(new DhFullDataIndex(rows.getInt(1), rows.getInt(2), rows.getInt(3),
                        rows.getInt(4), rows.getInt(5)));
            }
        } catch (SQLException exception) {
            throw new IOException("Unable to scan Distant Horizons FullData rows", exception);
        }
        return records;
    }

    @Override
    public DhFullDataRecord fetch(DhFullDataIndex index, DhImportCancellation cancellation) throws IOException {
        cancellation.throwIfCancelled();
        try (var statement = this.connection.prepareStatement(FETCH_QUERY)) {
            statement.setInt(1, index.detailLevel());
            statement.setInt(2, index.x());
            statement.setInt(3, index.z());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new IOException("Distant Horizons FullData row disappeared at "
                            + index.x() + "," + index.z());
                }
                cancellation.throwIfCancelled();
                int actualFormat = rows.getInt(1);
                int actualCompression = rows.getInt(2);
                if (actualFormat != index.dataFormatVersion() || actualCompression != index.compressionMode()) {
                    throw new IOException("Distant Horizons FullData row changed after scan at "
                            + index.x() + "," + index.z());
                }
                byte[] data = rows.getBytes(3);
                cancellation.throwIfCancelled();
                byte[] mapping = rows.getBytes(4);
                cancellation.throwIfCancelled();
                if (data == null || mapping == null) {
                    throw new IOException("Distant Horizons FullData row contains a null data blob at "
                            + index.x() + "," + index.z());
                }
                if (rows.next()) {
                    throw new IOException("Duplicate Distant Horizons FullData rows at "
                            + index.x() + "," + index.z());
                }
                return new DhFullDataRecord(index.x(), index.z(), index.detailLevel(),
                        index.dataFormatVersion(), index.compressionMode(), data, mapping);
            }
        } catch (SQLException exception) {
            throw new IOException("Unable to fetch Distant Horizons FullData row at "
                    + index.x() + "," + index.z(), exception);
        }
    }

    @Override
    public void close() throws IOException {
        try {
            this.connection.close();
        } catch (SQLException exception) {
            throw new IOException("Unable to close Distant Horizons database", exception);
        }
    }
}
