package com.slyph.cloverreports.commands;

import com.slyph.cloverreports.managers.ReportManager;
import com.slyph.cloverreports.models.ReportedPlayerIndex;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public final class ReportSuggestionCache {

    private static final int MAX_SUGGESTIONS = 30;
    private final AtomicReference<State> state = new AtomicReference<>(State.empty());

    public void replace(ReportedPlayerIndex pending, ReportedPlayerIndex resolved) {
        state.set(new State(Section.from(pending), Section.from(resolved)));
    }

    public void clear() {
        state.set(State.empty());
    }

    public List<String> suggest(String status, String input) {
        Section section = state.get().section(status);
        String prefix = normalize(input);
        List<String> result = new ArrayList<>(Math.min(MAX_SUGGESTIONS, section.names.size()));
        for (String name : section.names) {
            if (normalize(name).startsWith(prefix)) {
                result.add(name);
                if (result.size() == MAX_SUGGESTIONS) {
                    break;
                }
            }
        }
        return result;
    }

    public int caseCount(String status, String player) {
        Section section = state.get().section(status);
        return player == null || player.isBlank()
                ? section.totalCases
                : section.caseCounts.getOrDefault(normalize(player), 0);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private record State(Section pending, Section resolved) {

        private static State empty() {
            return new State(Section.empty(), Section.empty());
        }

        private Section section(String status) {
            return ReportManager.STATUS_RESOLVED.equals(status) ? resolved : pending;
        }
    }

    private record Section(List<String> names, Map<String, Integer> caseCounts, int totalCases) {

        private static Section empty() {
            return new Section(List.of(), Map.of(), 0);
        }

        private static Section from(ReportedPlayerIndex index) {
            Map<String, String> uniqueNames = new LinkedHashMap<>();
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (ReportedPlayerIndex.Entry entry : index.getEntries()) {
                String key = normalize(entry.displayName());
                uniqueNames.putIfAbsent(key, entry.displayName());
                counts.merge(key, entry.caseCount(), Integer::sum);
            }
            return new Section(List.copyOf(uniqueNames.values()), Map.copyOf(counts), index.getTotalCases());
        }
    }
}
