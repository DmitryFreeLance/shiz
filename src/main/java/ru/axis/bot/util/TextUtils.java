package ru.axis.bot.util;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TextUtils {
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[\\p{L}\\p{N}]{3,}");
    private static final Pattern ID_PATTERN = Pattern.compile("(?iu)^(?:https?://)?(?:www\\.)?vk\\.com/(id\\d+|[a-z0-9_.]+)$");
    private static final Pattern NUMERIC_ID_PATTERN = Pattern.compile("(?iu)^id?(\\d+)$");
    private static final Pattern MENTION_PATTERN = Pattern.compile("(?iu)^\\[(?:id|club)(\\d+)\\|[^\\]]+]$");

    private TextUtils() {
    }

    public static String normalize(String input) {
        return input == null ? "" : input.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    public static List<String> tokenize(String input) {
        if (input == null || input.isBlank()) {
            return List.of();
        }

        Matcher matcher = TOKEN_PATTERN.matcher(input.toLowerCase(Locale.ROOT));
        List<String> tokens = new java.util.ArrayList<>();
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return tokens;
    }

    public static String truncate(String input, int maxLength) {
        if (input == null || input.length() <= maxLength) {
            return input;
        }
        return input.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    public static Long tryParseVkUserId(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }

        String trimmed = input.trim();

        Matcher mentionMatcher = MENTION_PATTERN.matcher(trimmed);
        if (mentionMatcher.matches()) {
            return Long.parseLong(mentionMatcher.group(1));
        }

        Matcher numericMatcher = NUMERIC_ID_PATTERN.matcher(trimmed);
        if (numericMatcher.matches()) {
            return Long.parseLong(numericMatcher.group(1));
        }

        Matcher directMatcher = ID_PATTERN.matcher(trimmed);
        if (directMatcher.matches()) {
            String part = directMatcher.group(1);
            Matcher nestedNumeric = NUMERIC_ID_PATTERN.matcher(part);
            if (nestedNumeric.matches()) {
                return Long.parseLong(nestedNumeric.group(1));
            }
        }

        try {
            URI uri = URI.create(trimmed);
            String path = uri.getPath();
            if (path != null) {
                String normalized = Arrays.stream(path.split("/"))
                        .filter(chunk -> !chunk.isBlank())
                        .findFirst()
                        .orElse("");
                Matcher nestedNumeric = NUMERIC_ID_PATTERN.matcher(normalized);
                if (nestedNumeric.matches()) {
                    return Long.parseLong(nestedNumeric.group(1));
                }
            }
        } catch (IllegalArgumentException ignored) {
            return null;
        }

        return null;
    }
}
