package ru.axis.bot.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.axis.bot.config.AppConfig;

public final class KieAiClient {
    private static final Logger log = LoggerFactory.getLogger(KieAiClient.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final AppConfig config;

    public KieAiClient(HttpClient httpClient, AppConfig config) {
        this.httpClient = httpClient;
        this.config = config;
        this.objectMapper = new ObjectMapper();
    }

    public String chat(String systemPrompt, String userPrompt) {
        if (!config.aiConfigured()) {
            return "";
        }

        try {
            ObjectNode request = objectMapper.createObjectNode();
            ArrayNode messages = request.putArray("messages");
            messages.add(message("system", systemPrompt));
            messages.add(message("user", userPrompt));
            request.put("stream", false);
            request.put("reasoning_effort", "low");

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(buildChatCompletionsUrl()))
                    .header("Authorization", "Bearer " + config.kieApiKey())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 400) {
                log.warn("KIE.AI request failed with status {}: {}", response.statusCode(), response.body());
                return "";
            }

            JsonNode json = objectMapper.readTree(response.body());
            JsonNode contentNode = json.path("choices").path(0).path("message").path("content");
            if (contentNode.isTextual()) {
                return contentNode.asText().trim();
            }
            if (contentNode.isArray()) {
                StringBuilder builder = new StringBuilder();
                for (JsonNode node : contentNode) {
                    if (node.has("text")) {
                        builder.append(node.get("text").asText()).append('\n');
                    }
                }
                return builder.toString().trim();
            }
            log.warn("KIE.AI response did not contain assistant content: {}", response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.error("Failed to call KIE.AI", exception);
        } catch (IOException exception) {
            log.error("Failed to call KIE.AI", exception);
        } catch (Exception exception) {
            log.error("Unexpected KIE.AI error", exception);
        }
        return "";
    }

    private String buildChatCompletionsUrl() {
        String baseUrl = config.kieBaseUrl().replaceAll("/+$", "");
        String model = config.kieModel().replaceAll("^/+", "").replaceAll("/+$", "");
        return baseUrl + "/" + model + "/v1/chat/completions";
    }

    private ObjectNode message(String role, String text) {
        ObjectNode message = objectMapper.createObjectNode();
        message.put("role", role);
        ArrayNode content = message.putArray("content");
        ObjectNode textNode = content.addObject();
        textNode.put("type", "text");
        textNode.put("text", text);
        return message;
    }
}
