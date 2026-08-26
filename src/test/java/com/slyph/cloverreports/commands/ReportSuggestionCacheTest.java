package com.slyph.cloverreports.commands;

import com.slyph.cloverreports.managers.ReportManager;
import com.slyph.cloverreports.models.ReportedPlayerIndex;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ReportSuggestionCacheTest {

    @Test
    void publishesBothStatusesAndFiltersWithoutDatabaseAccess() {
        ReportSuggestionCache cache = new ReportSuggestionCache();
        cache.replace(
                new ReportedPlayerIndex(List.of(
                        new ReportedPlayerIndex.Entry("Alice", 3),
                        new ReportedPlayerIndex.Entry("Alex", 2),
                        new ReportedPlayerIndex.Entry("Bob", 1)
                ), 6),
                new ReportedPlayerIndex(List.of(new ReportedPlayerIndex.Entry("Alice", 7)), 7)
        );

        assertEquals(List.of("Alice", "Alex"), cache.suggest(ReportManager.STATUS_PENDING, "al"));
        assertEquals(6, cache.caseCount(ReportManager.STATUS_PENDING, null));
        assertEquals(3, cache.caseCount(ReportManager.STATUS_PENDING, "ALICE"));
        assertEquals(7, cache.caseCount(ReportManager.STATUS_RESOLVED, "alice"));
    }
}
