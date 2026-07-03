package ru.axis.bot.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;
import ru.axis.bot.model.KnowledgeEntry;

public final class KnowledgeRepository {
    private final Database database;

    public KnowledgeRepository(Database database) {
        this.database = database;
    }

    public long add(KnowledgeEntry entry) throws SQLException {
        String sql = """
                INSERT INTO knowledge_entries (
                    category, title, keywords, content, created_by, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """;

        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, entry.getCategory());
            statement.setString(2, entry.getTitle());
            statement.setString(3, entry.getKeywords());
            statement.setString(4, entry.getContent());
            statement.setLong(5, entry.getCreatedBy());
            statement.setString(6, OffsetDateTime.now().toString());
            statement.executeUpdate();

            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
                throw new SQLException("Failed to retrieve generated knowledge entry ID");
            }
        }
    }

    public boolean delete(long id) throws SQLException {
        String sql = "DELETE FROM knowledge_entries WHERE id = ?";
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            return statement.executeUpdate() > 0;
        }
    }

    public List<KnowledgeEntry> findAll() throws SQLException {
        String sql = "SELECT * FROM knowledge_entries ORDER BY id DESC";
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            List<KnowledgeEntry> entries = new ArrayList<>();
            while (resultSet.next()) {
                entries.add(map(resultSet));
            }
            return entries;
        }
    }

    public List<KnowledgeEntry> findByCategories(Set<String> categories) throws SQLException {
        if (categories.isEmpty()) {
            return List.of();
        }

        StringJoiner placeholders = new StringJoiner(",", "(", ")");
        categories.forEach(category -> placeholders.add("?"));
        String sql = "SELECT * FROM knowledge_entries WHERE LOWER(category) IN " + placeholders + " ORDER BY id DESC";

        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            for (String category : categories) {
                statement.setString(index++, category.toLowerCase());
            }

            try (ResultSet resultSet = statement.executeQuery()) {
                List<KnowledgeEntry> entries = new ArrayList<>();
                while (resultSet.next()) {
                    entries.add(map(resultSet));
                }
                return entries;
            }
        }
    }

    private KnowledgeEntry map(ResultSet resultSet) throws SQLException {
        KnowledgeEntry entry = new KnowledgeEntry();
        entry.setId(resultSet.getLong("id"));
        entry.setCategory(resultSet.getString("category"));
        entry.setTitle(resultSet.getString("title"));
        entry.setKeywords(resultSet.getString("keywords"));
        entry.setContent(resultSet.getString("content"));
        entry.setCreatedBy(resultSet.getLong("created_by"));
        return entry;
    }
}
