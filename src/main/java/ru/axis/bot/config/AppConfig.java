package ru.axis.bot.config;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

public record AppConfig(
        long vkGroupId,
        String vkGroupToken,
        String vkApiVersion,
        String vkBotName,
        Set<Long> adminIds,
        Path dataDir,
        String kieApiKey,
        String kieBaseUrl,
        String kieModel,
        boolean aiEnabled
) {
    public static AppConfig load() {
        long vkGroupId = Long.parseLong(required("VK_GROUP_ID"));
        String vkGroupToken = required("VK_GROUP_TOKEN");
        String vkApiVersion = env("VK_API_VERSION", "5.199");
        String vkBotName = env("VK_BOT_NAME", "Аксис");
        Set<Long> adminIds = parseAdminIds(env("ADMIN_IDS", ""));
        Path dataDir = Path.of(env("DATA_DIR", "/app/data")).toAbsolutePath().normalize();
        String kieApiKey = env("KIE_API_KEY", "");
        String kieBaseUrl = env("KIE_BASE_URL", "https://api.kie.ai");
        String kieModel = env("KIE_MODEL", "gemini-3-flash");
        boolean aiEnabled = Boolean.parseBoolean(env("AI_ENABLED", "true"));

        return new AppConfig(
                vkGroupId,
                vkGroupToken,
                vkApiVersion,
                vkBotName,
                adminIds,
                dataDir,
                kieApiKey,
                kieBaseUrl,
                kieModel,
                aiEnabled
        );
    }

    public boolean isAdmin(long userId) {
        return adminIds.contains(userId);
    }

    public boolean isBootstrapAdmin(long userId) {
        return adminIds.contains(userId);
    }

    public boolean aiConfigured() {
        return aiEnabled && !kieApiKey.isBlank();
    }

    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Environment variable " + key + " is required");
        }
        return value.trim();
    }

    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static Set<Long> parseAdminIds(String raw) {
        if (raw == null || raw.isBlank()) {
            return Collections.emptySet();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .map(Long::parseLong)
                .collect(Collectors.toUnmodifiableSet());
    }
}
