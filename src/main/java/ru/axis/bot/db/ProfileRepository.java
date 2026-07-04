package ru.axis.bot.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import ru.axis.bot.model.PlayerProfile;

public final class ProfileRepository {
    private final Database database;

    public ProfileRepository(Database database) {
        this.database = database;
    }

    public void upsert(PlayerProfile profile) throws SQLException {
        String sql = """
                INSERT INTO player_profiles (
                    vk_user_id, vk_profile_url, character_name, character_gender,
                    character_age, spectrum, character_index, reputation, note, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(vk_user_id) DO UPDATE SET
                    vk_profile_url = excluded.vk_profile_url,
                    character_name = excluded.character_name,
                    character_gender = excluded.character_gender,
                    character_age = excluded.character_age,
                    spectrum = excluded.spectrum,
                    character_index = excluded.character_index,
                    reputation = excluded.reputation,
                    note = excluded.note,
                    updated_at = excluded.updated_at
                """;

        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, profile.getVkUserId());
            statement.setString(2, profile.getVkProfileUrl());
            statement.setString(3, profile.getCharacterName());
            statement.setString(4, profile.getCharacterGender());
            statement.setString(5, profile.getCharacterAge());
            statement.setString(6, profile.getSpectrum());
            statement.setString(7, profile.getCharacterIndex());
            statement.setString(8, profile.getReputation());
            statement.setString(9, profile.getNote());
            statement.setString(10, OffsetDateTime.now().toString());
            statement.executeUpdate();
        }
    }

    public Optional<PlayerProfile> findByUserId(long userId) throws SQLException {
        String sql = "SELECT * FROM player_profiles WHERE vk_user_id = ?";
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
            }
        }
    }

    public Optional<PlayerProfile> findByCharacterName(String characterName) throws SQLException {
        String sql = """
                SELECT * FROM player_profiles
                WHERE LOWER(character_name) = LOWER(?)
                LIMIT 1
                """;
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, characterName.trim());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
            }
        }
    }

    public List<PlayerProfile> searchByCharacterName(String query, int limit) throws SQLException {
        String sql = """
                SELECT * FROM player_profiles
                WHERE character_name IS NOT NULL
                  AND LOWER(character_name) LIKE LOWER(?)
                ORDER BY character_name
                LIMIT ?
                """;
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, "%" + query.trim() + "%");
            statement.setInt(2, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<PlayerProfile> profiles = new ArrayList<>();
                while (resultSet.next()) {
                    profiles.add(map(resultSet));
                }
                return profiles;
            }
        }
    }

    public boolean deleteByUserId(long userId) throws SQLException {
        String sql = "DELETE FROM player_profiles WHERE vk_user_id = ?";
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            return statement.executeUpdate() > 0;
        }
    }

    public List<PlayerProfile> findAll(int limit) throws SQLException {
        String sql = """
                SELECT * FROM player_profiles
                ORDER BY character_name IS NULL, character_name, vk_user_id
                LIMIT ?
                """;
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<PlayerProfile> profiles = new ArrayList<>();
                while (resultSet.next()) {
                    profiles.add(map(resultSet));
                }
                return profiles;
            }
        }
    }

    private PlayerProfile map(ResultSet resultSet) throws SQLException {
        PlayerProfile profile = new PlayerProfile();
        profile.setVkUserId(resultSet.getLong("vk_user_id"));
        profile.setVkProfileUrl(resultSet.getString("vk_profile_url"));
        profile.setCharacterName(resultSet.getString("character_name"));
        profile.setCharacterGender(resultSet.getString("character_gender"));
        profile.setCharacterAge(resultSet.getString("character_age"));
        profile.setSpectrum(resultSet.getString("spectrum"));
        profile.setCharacterIndex(resultSet.getString("character_index"));
        profile.setReputation(resultSet.getString("reputation"));
        profile.setNote(resultSet.getString("note"));
        return profile;
    }
}
