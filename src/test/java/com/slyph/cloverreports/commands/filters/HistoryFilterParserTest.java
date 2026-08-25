package com.slyph.cloverreports.commands.filters;

import com.slyph.cloverreports.managers.ReportManager;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class HistoryFilterParserTest {

    @Test
    void parsesOrderIndependentFiltersAndInclusiveEndDate() {
        String[] arguments = {"history", "to=2026-07-12", "action=ban", "player=Target", "from=2026-07-10", "page=3", "moderator=Mod"};
        HistoryFilterParser.Result result = HistoryFilterParser.parse(arguments, 1, ZoneId.of("UTC"));

        assertTrue(result.isValid());
        assertEquals(2, result.getPage());
        assertEquals("Target", result.getFilter().getPlayer());
        assertEquals("Mod", result.getFilter().getModerator());
        assertEquals(ReportManager.ACTION_PUNISHED, result.getFilter().getAction());
        assertEquals(Instant.parse("2026-07-10T00:00:00Z").toEpochMilli(), result.getFilter().getFromTimestamp());
        assertEquals(Instant.parse("2026-07-13T00:00:00Z").toEpochMilli(), result.getFilter().getToTimestamp());
    }

    @Test
    void rejectsInvalidAndInvertedDates() {
        HistoryFilterParser.Result invalid = HistoryFilterParser.parse(new String[]{"history", "from=12.07.2026"}, 1, ZoneId.of("UTC"));
        HistoryFilterParser.Result inverted = HistoryFilterParser.parse(new String[]{"history", "from=2026-07-13", "to=2026-07-12"}, 1, ZoneId.of("UTC"));

        assertFalse(invalid.isValid());
        assertFalse(inverted.isValid());
    }
}
