package com.slyph.cloverreports.commands.filters;

import com.slyph.cloverreports.managers.ReportManager;
import com.slyph.cloverreports.models.HistoryFilter;
import com.slyph.cloverreports.reasons.ReportReason;
import com.slyph.cloverreports.reasons.ReportReasons;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

public final class HistoryFilterParser {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    private HistoryFilterParser() {
    }

    public static Result parse(String[] args, int startIndex, ZoneId zoneId) {
        String player = null;
        String moderator = null;
        String reason = null;
        String reasonKey = null;
        String action = null;
        LocalDate from = null;
        LocalDate to = null;
        int page = 0;

        for (int index = startIndex; index < args.length; index++) {
            String token = args[index].trim();
            if (token.isEmpty()) {
                continue;
            }
            int separator = token.indexOf('=');
            if (separator < 1) {
                Integer legacyPage = parsePage(token);
                if (legacyPage != null) {
                    page = legacyPage;
                    continue;
                }
                if (player == null) {
                    player = token;
                    continue;
                }
                return Result.invalid(token);
            }

            String key = token.substring(0, separator).toLowerCase(Locale.ROOT);
            String value = token.substring(separator + 1).trim();
            if (value.isEmpty()) {
                return Result.invalid(token);
            }
            switch (key) {
                case "page":
                    Integer parsedPage = parsePage(value);
                    if (parsedPage == null) {
                        return Result.invalid(token);
                    }
                    page = parsedPage;
                    break;
                case "player":
                    player = value;
                    break;
                case "moderator":
                case "mod":
                    moderator = value;
                    break;
                case "reason":
                    ReportReason configuredReason = ReportReasons.findByKey(value);
                    if (configuredReason == null) {
                        configuredReason = ReportReasons.findByName(value);
                    }
                    reason = configuredReason == null ? value : configuredReason.getName();
                    reasonKey = configuredReason == null ? value : configuredReason.getKey();
                    break;
                case "action":
                    action = normalizeAction(value);
                    if (action == null) {
                        return Result.invalid(token);
                    }
                    break;
                case "from":
                    from = parseDate(value);
                    if (from == null) {
                        return Result.invalid(token);
                    }
                    break;
                case "to":
                    to = parseDate(value);
                    if (to == null) {
                        return Result.invalid(token);
                    }
                    break;
                default:
                    return Result.invalid(token);
            }
        }

        if (from != null && to != null && from.isAfter(to)) {
            return Result.invalid("from/to");
        }
        try {
            long fromTimestamp = from == null ? 0L : from.atStartOfDay(zoneId).toInstant().toEpochMilli();
            long toTimestamp = to == null ? 0L : to.plusDays(1L).atStartOfDay(zoneId).toInstant().toEpochMilli();
            return Result.valid(new HistoryFilter(player, moderator, reason, reasonKey, action, fromTimestamp, toTimestamp), page);
        } catch (DateTimeException exception) {
            return Result.invalid("date");
        }
    }

    private static Integer parsePage(String value) {
        try {
            int page = Integer.parseInt(value);
            return page > 0 ? page - 1 : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value, DATE_FORMATTER);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private static String normalizeAction(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        if (normalized.equals("close") || normalized.equals("closed")) {
            return ReportManager.ACTION_CLOSED;
        }
        if (normalized.equals("punish") || normalized.equals("punished") || normalized.equals("ban")) {
            return ReportManager.ACTION_PUNISHED;
        }
        if (normalized.equals("note") || normalized.equals("note-clear") || normalized.equals("teleport") || normalized.equals("open-actions") || normalized.equals("select-ban-reason")) {
            return normalized;
        }
        return null;
    }

    public static final class Result {

        private final HistoryFilter filter;
        private final int page;
        private final String invalidToken;

        private Result(HistoryFilter filter, int page, String invalidToken) {
            this.filter = filter;
            this.page = page;
            this.invalidToken = invalidToken;
        }

        public static Result valid(HistoryFilter filter, int page) {
            return new Result(filter, page, null);
        }

        public static Result invalid(String token) {
            return new Result(HistoryFilter.empty(), 0, token);
        }

        public boolean isValid() {
            return invalidToken == null;
        }

        public HistoryFilter getFilter() {
            return filter;
        }

        public int getPage() {
            return page;
        }

        public String getInvalidToken() {
            return invalidToken;
        }
    }
}
