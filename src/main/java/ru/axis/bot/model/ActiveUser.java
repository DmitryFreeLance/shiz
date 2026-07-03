package ru.axis.bot.model;

public record ActiveUser(
        long userId,
        long lastPeerId,
        int messageCount,
        String lastSeenAt
) {
}
