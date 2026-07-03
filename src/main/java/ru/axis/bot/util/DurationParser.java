package ru.axis.bot.util;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DurationParser {
    private static final Pattern DURATION = Pattern.compile("(?iu)^(\\d+)\\s*([a-zа-я]+)?$");

    private DurationParser() {
    }

    public static long parseSeconds(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Duration is empty");
        }

        Matcher matcher = DURATION.matcher(raw.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Unsupported duration: " + raw);
        }

        long value = Long.parseLong(matcher.group(1));
        String unit = matcher.group(2) == null ? "s" : matcher.group(2).toLowerCase(Locale.ROOT);

        return switch (unit) {
            case "s", "sec", "secs", "сек", "секунда", "секунд", "секунды" -> value;
            case "m", "min", "mins", "мин", "м", "минута", "минут", "минуты" -> value * 60;
            case "h", "hr", "hrs", "ч", "час", "часа", "часов" -> value * 3600;
            case "d", "day", "days", "д", "день", "дня", "дней" -> value * 86400;
            default -> throw new IllegalArgumentException("Unsupported duration unit: " + unit);
        };
    }
}
