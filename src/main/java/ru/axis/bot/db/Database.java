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
        }
    }

    public Connection openConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }
}
