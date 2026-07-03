package ru.axis.bot.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;

public final class AdminRepository {
    private final Database database;

    public AdminRepository(Database database) {
        this.database = database;
    }

    public boolean exists(long userId) throws SQLException {
        String sql = "SELECT 1 FROM bot_admins WHERE vk_user_id = ?";
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    public void addAdmin(long userId, long addedBy) throws SQLException {
        String sql = """
                INSERT INTO bot_admins (vk_user_id, added_by, added_at)
                VALUES (?, ?, ?)
                ON CONFLICT(vk_user_id) DO UPDATE SET
                    added_by = excluded.added_by,
                    added_at = excluded.added_at
                """;
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            statement.setLong(2, addedBy);
            statement.setString(3, OffsetDateTime.now().toString());
            statement.executeUpdate();
        }
    }

    public boolean removeAdmin(long userId) throws SQLException {
        String sql = "DELETE FROM bot_admins WHERE vk_user_id = ?";
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            return statement.executeUpdate() > 0;
        }
    }

    public Set<Long> findAllIds() throws SQLException {
        String sql = "SELECT vk_user_id FROM bot_admins";
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            Set<Long> ids = new HashSet<>();
            while (resultSet.next()) {
                ids.add(resultSet.getLong("vk_user_id"));
            }
            return ids;
        }
    }
}
