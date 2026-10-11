package com.mrgregles.bsp_region_guard.storage;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * MySQL through plain JDBC. No driver is bundled: BSP Core's full jar provides one on Forge servers and Mohist ships
 * its own (two copies refuse to load together, see RG-C4). One connection, reopened when it fails.
 */
public final class JdbcBackend implements MirrorBackend {
    private final String url, user, password, prefix;
    private Connection connection;

    public JdbcBackend(String host, int port, String database, String user, String password, String prefix) {
        this.url = "jdbc:mysql://" + host + ":" + port + "/" + database + "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&connectTimeout=5000&socketTimeout=15000";
        this.user = user;
        this.password = password;
        this.prefix = prefix;
    }

    private Connection conn() throws SQLException {
        if (connection == null || connection.isClosed() || !connection.isValid(2)) {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException ignored) {
                    // reopening anyway
                }
            }
            connection = DriverManager.getConnection(url, user, password);
        }
        return connection;
    }

    @Override
    public void init() throws SQLException {
        try (Statement st = conn().createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "regions ("
                    + "server_id VARCHAR(64) NOT NULL, dimension VARCHAR(128) NOT NULL, region_id VARCHAR(64) NOT NULL, "
                    + "version INT NOT NULL, updated_at BIGINT NOT NULL, data MEDIUMBLOB, deleted TINYINT NOT NULL DEFAULT 0, "
                    + "PRIMARY KEY (server_id, dimension, region_id))");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "messages ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, created_at BIGINT NOT NULL, source VARCHAR(64) NOT NULL, "
                    + "kind VARCHAR(32) NOT NULL, dimension VARCHAR(128) NOT NULL, region_id VARCHAR(64) NOT NULL, payload TEXT)");
        }
    }

    @Override
    public void upsertRegion(RegionRow row) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement("INSERT INTO " + prefix + "regions (server_id, dimension, region_id, version, updated_at, data, deleted) "
                + "VALUES (?, ?, ?, ?, ?, ?, 0) ON DUPLICATE KEY UPDATE version = VALUES(version), updated_at = VALUES(updated_at), data = VALUES(data), deleted = 0")) {
            ps.setString(1, row.server());
            ps.setString(2, row.dimension());
            ps.setString(3, row.id());
            ps.setInt(4, row.version());
            ps.setLong(5, row.updatedAt());
            ps.setBytes(6, row.data());
            ps.executeUpdate();
        }
    }

    @Override
    public void markDeleted(String server, String dimension, String id, long when) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement("INSERT INTO " + prefix + "regions (server_id, dimension, region_id, version, updated_at, data, deleted) "
                + "VALUES (?, ?, ?, 0, ?, NULL, 1) ON DUPLICATE KEY UPDATE version = version + 1, updated_at = VALUES(updated_at), data = NULL, deleted = 1")) {
            ps.setString(1, server);
            ps.setString(2, dimension);
            ps.setString(3, id);
            ps.setLong(4, when);
            ps.executeUpdate();
        }
    }

    @Override
    public List<RegionRow> fetchRegions(String server, String dimension) throws SQLException {
        List<RegionRow> out = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement("SELECT region_id, version, updated_at, data, deleted FROM " + prefix + "regions WHERE server_id = ? AND dimension = ?")) {
            ps.setString(1, server);
            ps.setString(2, dimension);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    byte[] data = rs.getBytes(4);
                    out.add(new RegionRow(server, dimension, rs.getString(1), rs.getInt(2), rs.getLong(3), data == null ? new byte[0] : data, rs.getInt(5) != 0));
                }
            }
        }
        return out;
    }

    @Override
    public Map<String, Integer> countByServer() throws SQLException {
        Map<String, Integer> out = new TreeMap<>();
        try (Statement st = conn().createStatement(); ResultSet rs = st.executeQuery("SELECT server_id, COUNT(*) FROM " + prefix + "regions WHERE deleted = 0 GROUP BY server_id")) {
            while (rs.next()) {
                out.put(rs.getString(1), rs.getInt(2));
            }
        }
        return out;
    }

    @Override
    public long publish(Message m) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement("INSERT INTO " + prefix + "messages (created_at, source, kind, dimension, region_id, payload) VALUES (?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, m.createdAt());
            ps.setString(2, m.source());
            ps.setString(3, m.kind());
            ps.setString(4, m.dimension());
            ps.setString(5, m.regionId());
            ps.setString(6, m.payload());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : 0;
            }
        }
    }

    @Override
    public List<Message> fetchMessagesAfter(long id, int limit) throws SQLException {
        List<Message> out = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement("SELECT id, created_at, source, kind, dimension, region_id, payload FROM " + prefix + "messages WHERE id > ? ORDER BY id LIMIT ?")) {
            ps.setLong(1, id);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Message(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7)));
                }
            }
        }
        return out;
    }

    @Override
    public long latestMessageId() throws SQLException {
        try (Statement st = conn().createStatement(); ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(id), 0) FROM " + prefix + "messages")) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    @Override
    public String describe() {
        return "mysql " + url.substring(0, url.indexOf('?')) + " as " + user + ", prefix " + prefix;
    }

    @Override
    public void close() throws SQLException {
        if (connection != null) {
            connection.close();
            connection = null;
        }
    }
}
