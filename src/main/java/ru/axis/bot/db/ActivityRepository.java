package ru.axis.bot.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import ru.axis.bot.model.ActiveUser;

public final class ActivityRepository {
    private final Database database;

    public ActivityRepository(Database database) {
        this.database = database;
    }

    public void touch(long userId, long peerId) throws SQLException {
        String sql = """
                INSERT INTO active_users (vk_user_id, last_peer_id, message_count, last_seen_at)
                VALUES (?, ?, 1, ?)
                ON CONFLICT(vk_user_id) DO UPDATE SET
                    last_peer_id = excluded.last_peer_id,
                    message_count = active_users.message_count + 1,
                    last_seen_at = excluded.last_seen_at
                """;
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            statement.setLong(2, peerId);
            statement.setString(3, OffsetDateTime.now().toString());
            statement.executeUpdate();
        }
    }

    public boolean hasSeenUser(long userId) throws SQLException {
        String sql = "SELECT 1 FROM active_users WHERE vk_user_id = ?";
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    public List<ActiveUser> findRecent(int limit) throws SQLException {
        String sql = """
                SELECT vk_user_id, last_peer_id, message_count, last_seen_at
                FROM active_users
                ORDER BY last_seen_at DESC
                LIMIT ?
                """;
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<ActiveUser> users = new ArrayList<>();
                while (resultSet.next()) {
                    users.add(new ActiveUser(
                            resultSet.getLong("vk_user_id"),
                            resultSet.getLong("last_peer_id"),
                            resultSet.getInt("message_count"),
                            resultSet.getString("last_seen_at")
                    ));
                }
                return users;
            }
        }
    }

    public List<ActiveUser> findRecentByPeer(long peerId, int limit) throws SQLException {
        String sql = """
                SELECT vk_user_id, last_peer_id, message_count, last_seen_at
                FROM active_users
                WHERE last_peer_id = ?
                ORDER BY last_seen_at DESC
                LIMIT ?
                """;
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, peerId);
            statement.setInt(2, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<ActiveUser> users = new ArrayList<>();
                while (resultSet.next()) {
                    users.add(new ActiveUser(
                            resultSet.getLong("vk_user_id"),
                            resultSet.getLong("last_peer_id"),
                            resultSet.getInt("message_count"),
                            resultSet.getString("last_seen_at")
                    ));
                }
                return users;
            }
        }
    }
}
