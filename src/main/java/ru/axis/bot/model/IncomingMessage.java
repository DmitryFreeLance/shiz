package ru.axis.bot.model;

public record IncomingMessage(
        long peerId,
        long fromId,
        String text,
        String replyText,
        String forwardedText,
        boolean chat
) {
}
