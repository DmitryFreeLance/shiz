package ru.axis.bot.vk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.axis.bot.config.AppConfig;

public final class VkApiClient {
    private static final Logger log = LoggerFactory.getLogger(VkApiClient.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final AppConfig config;

    public VkApiClient(HttpClient httpClient, AppConfig config) {
        this.httpClient = httpClient;
        this.config = config;
        this.objectMapper = new ObjectMapper();
    }

    public LongPollServer getLongPollServer() throws Exception {
        JsonNode response = callMethod("groups.getLongPollServer", Map.of(
                "group_id", String.valueOf(config.vkGroupId())
        ));
        JsonNode root = response.path("response");
        return new LongPollServer(
                root.path("server").asText(),
                root.path("key").asText(),
                root.path("ts").asText()
        );
    }

    public JsonNode poll(LongPollServer server) throws Exception {
        String query = "act=a_check&key=" + urlEncode(server.key())
                + "&ts=" + urlEncode(server.ts())
                + "&wait=25&mode=2&version=3";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(server.server() + "?" + query))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() >= 400) {
            throw new IOException("VK long poll failed: " + response.statusCode() + " " + response.body());
        }
        return objectMapper.readTree(response.body());
    }

    public void sendMessage(long peerId, String message) throws Exception {
        callMethod("messages.send", Map.of(
                "peer_id", String.valueOf(peerId),
                "message", message,
                "random_id", String.valueOf(ThreadLocalRandom.current().nextInt())
        ));
    }

    public void changeConversationMemberRestrictions(long peerId, long memberId, String action, Long seconds) throws Exception {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("peer_id", String.valueOf(peerId));
        params.put("member_ids", String.valueOf(memberId));
        params.put("action", action);
        if (seconds != null) {
            params.put("for", String.valueOf(seconds));
        }
        callMethod("messages.changeConversationMemberRestrictions", params);
    }

    public JsonNode resolveScreenName(String reference) throws Exception {
        String screenName = normalizeScreenName(reference);
        if (screenName == null || screenName.isBlank()) {
            return null;
        }
        JsonNode response = callMethod("utils.resolveScreenName", Map.of("screen_name", screenName));
        JsonNode result = response.path("response");
        return result.isMissingNode() || result.isNull() ? null : result;
    }

    private JsonNode callMethod(String method, Map<String, String> params) throws Exception {
        Map<String, String> payload = new LinkedHashMap<>(params);
        payload.put("access_token", config.vkGroupToken());
        payload.put("v", config.vkApiVersion());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.vk.com/method/" + method))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formEncode(payload), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode json = objectMapper.readTree(response.body());
        if (json.has("error")) {
            String errorMessage = json.path("error").path("error_msg").asText("Unknown VK error");
            int errorCode = json.path("error").path("error_code").asInt(-1);
            throw new IOException("VK API error " + errorCode + ": " + errorMessage);
        }
        return json;
    }

    private String formEncode(Map<String, String> params) {
        StringJoiner joiner = new StringJoiner("&");
        params.forEach((key, value) -> joiner.add(urlEncode(key) + "=" + urlEncode(value)));
        return joiner.toString();
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String normalizeScreenName(String reference) {
        String trimmed = reference.trim();
        trimmed = trimmed.replaceAll("(?iu)^https?://(?:www\\.)?vk\\.com/", "");
        trimmed = trimmed.replaceAll("(?iu)^\\[(?:id|club)(\\d+)\\|[^\\]]+]$", "id$1");
        if (trimmed.startsWith("id") && trimmed.substring(2).chars().allMatch(Character::isDigit)) {
            return trimmed;
        }
        if (trimmed.chars().allMatch(Character::isDigit)) {
            return "id" + trimmed;
        }
        if (trimmed.matches("(?iu)^[a-z0-9_.]+$")) {
            return trimmed;
        }
        log.debug("Cannot normalize VK screen name from {}", reference);
        return null;
    }

    public record LongPollServer(String server, String key, String ts) {
    }
}
