package ru.axis.bot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import ru.axis.bot.util.DurationParser;

class DurationParserTest {
    @Test
    void parsesMinutes() {
        assertEquals(1800, DurationParser.parseSeconds("30м"));
    }

    @Test
    void parsesHours() {
        assertEquals(7200, DurationParser.parseSeconds("2h"));
    }
}
