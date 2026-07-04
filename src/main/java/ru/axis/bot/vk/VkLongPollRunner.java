package ru.axis.bot.vk;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.axis.bot.config.AppConfig;
import ru.axis.bot.model.IncomingMessage;
import ru.axis.bot.service.MessageHandler;

public final class VkLongPollRunner {
    private static final Logger log = LoggerFactory.getLogger(VkLongPollRunner.class);

    private final VkApiClient vkApiClient;
    private final AppConfig config;
    private final MessageHandler messageHandler;

    public VkLongPollRunner(VkApiClient vkApiClient, AppConfig config, MessageHandler messageHandler) {
        this.vkApiClient = vkApiClient;
        this.config = config;
        this.messageHandler = messageHandler;
    }

    public void runForever() throws Exception {
        VkApiClient.LongPollServer server = vkApiClient.getLongPollServer();

        while (true) {
            try {
                JsonNode payload = vkApiClient.poll(server);
                int failed = payload.path("failed").asInt(0);
                if (failed == 1) {
                    server = new VkApiClient.LongPollServer(server.server(), server.key(), payload.path("ts").asText());
                    continue;
                }
                if (failed == 2 || failed == 3) {
                    server = vkApiClient.getLongPollServer();
                    continue;
                }

                server = new VkApiClient.LongPollServer(server.server(), server.key(), payload.path("ts").asText(server.ts()));
                for (JsonNode update : payload.path("updates")) {
                    if (!"message_new".equals(update.path("type").asText())) {
                        continue;
                    }
                    IncomingMessage message = mapMessage(update.path("object").path("message"));
                    messageHandler.handle(message);
                }
            } catch (Exception exception) {
                log.error("Long poll error for group {}", config.vkGroupId(), exception);
                Thread.sleep(1_500L);
                server = vkApiClient.getLongPollServer();
            }
        }
    }

    private IncomingMessage mapMessage(JsonNode messageNode) {
        long peerId = messageNode.path("peer_id").asLong();
        long fromId = messageNode.path("from_id").asLong();
        boolean chat = peerId >= 2_000_000_000L;
        String text = messageNode.path("text").asText("");
        String replyText = messageNode.path("reply_message").path("text").asText("");
        String forwardedText = flattenForwardedMessages(messageNode.path("fwd_messages")).trim();
        return new IncomingMessage(peerId, fromId, text, replyText, forwardedText, chat);
    }

    private String flattenForwardedMessages(JsonNode forwardedMessages) {
        if (forwardedMessages == null || !forwardedMessages.isArray() || forwardedMessages.isEmpty()) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        for (JsonNode forwardedMessage : forwardedMessages) {
            String text = forwardedMessage.path("text").asText("").trim();
            if (!text.isBlank()) {
                if (!builder.isEmpty()) {
                    builder.append("\n\n");
                }
                builder.append(text);
            }

            String nestedForwardedText = flattenForwardedMessages(forwardedMessage.path("fwd_messages"));
            if (!nestedForwardedText.isBlank()) {
                if (!builder.isEmpty()) {
                    builder.append("\n\n");
                }
                builder.append(nestedForwardedText);
            }
        }
        return builder.toString();
    }
}
