package ru.axis.bot.db;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public final class Database {
    private final String jdbcUrl;

    public Database(Path filePath) {
        this.jdbcUrl = "jdbc:sqlite:" + filePath.toAbsolutePath();
    }

    public void init() throws SQLException {
        try (Connection connection = openConnection()) {
            connection.createStatement().execute("""
                    CREATE TABLE IF NOT EXISTS player_profiles (
                        vk_user_id INTEGER PRIMARY KEY,
                        vk_profile_url TEXT NOT NULL,
                        character_name TEXT,
                        character_gender TEXT,
                        character_age TEXT,
                        spectrum TEXT,
                        character_index TEXT,
                        note TEXT,
                        updated_at TEXT NOT NULL
                    )
                    """);
            connection.createStatement().execute("""
                    CREATE TABLE IF NOT EXISTS knowledge_entries (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        category TEXT NOT NULL,
                        title TEXT NOT NULL,
                        keywords TEXT,
                        content TEXT NOT NULL,
                        created_by INTEGER NOT NULL,
                        updated_at TEXT NOT NULL
                    )
                    """);
            connection.createStatement().execute("""
                    CREATE INDEX IF NOT EXISTS idx_knowledge_category ON knowledge_entries(category)
                    """);
            connection.createStatement().execute("""
                    CREATE TABLE IF NOT EXISTS bot_admins (
                        vk_user_id INTEGER PRIMARY KEY,
                        added_by INTEGER NOT NULL,
                        added_at TEXT NOT NULL
                    )
                    """);
            connection.createStatement().execute("""
                    CREATE TABLE IF NOT EXISTS active_users (
                        vk_user_id INTEGER PRIMARY KEY,
                        last_peer_id INTEGER NOT NULL,
                        message_count INTEGER NOT NULL DEFAULT 1,
                        last_seen_at TEXT NOT NULL
                    )
                    """);
        }
    }

    public Connection openConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }
}
