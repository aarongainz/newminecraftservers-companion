package net.newminecraftservers.companion;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;

final class StatsFormatter {
    private StatsFormatter() {}

    static List<String> status(ApiModels.ServerDetail server, int localPlayers, int localMaximum, Instant now) {
        List<String> lines = new ArrayList<>();
        ApiModels.Snapshot snapshot = server.primarySnapshot();
        lines.add("NewMinecraftServers · " + safe(server.name()));
        String players = snapshot.playersOnline() == null
            ? "players unknown"
            : snapshot.playersOnline() + "/" + (snapshot.playersMax() == null ? "?" : snapshot.playersMax()) + " players";
        lines.add("Website last saw: " + title(snapshot.outcome()) + " · " + players + " · " + age(snapshot.observedAt(), now));
        lines.add("Paper now: " + localPlayers + "/" + localMaximum + " players");
        if (snapshot.versionName() != null && !snapshot.versionName().isBlank()) {
            lines.add("Version observed: " + safe(snapshot.versionName()));
        }
        if (isOlderThan(snapshot.observedAt(), now, Duration.ofMinutes(20))) {
            lines.add("Notice: the public observation is stale.");
        }
        return lines;
    }

    static List<String> history(ApiModels.ServerDetail server, ApiModels.History history) {
        List<String> lines = new ArrayList<>();
        ApiModels.HistorySummary summary = history.summary();
        lines.add("NewMinecraftServers · " + safe(server.name()) + " · " + history.range());
        if (summary == null || summary.checks() == 0) {
            lines.add("Collecting history. The first observations will appear shortly.");
            return lines;
        }
        if (summary.peak() != null) {
            lines.add("Peak observed: " + summary.peak().players() + " players · " + date(summary.peak().at()));
        }
        if (summary.averagePlayers() != null) {
            lines.add(String.format(Locale.ROOT, "Average observed: %.1f players", summary.averagePlayers()));
        }
        if (summary.uptimeRatio() != null) {
            lines.add(String.format(Locale.ROOT, "Observed uptime: %.1f%% across %,d checks", summary.uptimeRatio() * 100, summary.checks()));
            if (summary.uptimeRatio() < 0.95) lines.add("Notice: observed uptime is below 95%.");
        } else {
            lines.add(String.format(Locale.ROOT, "Checks: %,d", summary.checks()));
        }
        lines.add("Observation coverage: " + summary.daysCovered() + (summary.daysCovered() == 1 ? " day" : " days"));
        if (summary.busiestHourUtc() != null) {
            lines.add(String.format(Locale.ROOT, "Busiest hour: %02d:00 UTC", summary.busiestHourUtc()));
        }
        String weekday = summary.daysCovered() >= 3 ? busiestWeekday(history.points()) : null;
        if (weekday != null) lines.add("Busiest weekday: " + weekday + " (average observed players)");
        return lines;
    }

    static String busiestWeekday(List<ApiModels.HistoryPoint> points) {
        if (points == null) return null;
        record Total(double players, int count) {}
        EnumMap<DayOfWeek, Total> totals = new EnumMap<>(DayOfWeek.class);
        for (ApiModels.HistoryPoint point : points) {
            if (point.players() == null || point.t() == null) continue;
            try {
                DayOfWeek day = Instant.parse(point.t()).atZone(ZoneOffset.UTC).getDayOfWeek();
                Total current = totals.getOrDefault(day, new Total(0, 0));
                totals.put(day, new Total(current.players + point.players(), current.count + 1));
            } catch (RuntimeException ignored) {
                // Ignore a malformed optional point instead of losing the entire command output.
            }
        }
        DayOfWeek best = null;
        double bestAverage = -1;
        for (var entry : totals.entrySet()) {
            double average = entry.getValue().players / entry.getValue().count;
            if (average > bestAverage) {
                best = entry.getKey();
                bestAverage = average;
            }
        }
        return best == null ? null : best.getDisplayName(TextStyle.FULL, Locale.ENGLISH);
    }

    private static String safe(String value) {
        if (value == null) return "Unknown";
        return value.replace('§', '?').replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "").trim();
    }

    private static String title(String value) {
        if (value == null || value.isBlank()) return "Unknown";
        return Character.toUpperCase(value.charAt(0)) + value.substring(1).toLowerCase(Locale.ROOT);
    }

    private static String age(String value, Instant now) {
        try {
            long seconds = Math.max(0, Duration.between(Instant.parse(value), now).getSeconds());
            if (seconds < 60) return seconds + "s ago";
            if (seconds < 3_600) return seconds / 60 + "m ago";
            if (seconds < 86_400) return seconds / 3_600 + "h ago";
            return seconds / 86_400 + "d ago";
        } catch (RuntimeException error) {
            return "time unknown";
        }
    }

    private static boolean isOlderThan(String value, Instant now, Duration duration) {
        try {
            return Instant.parse(value).isBefore(now.minus(duration));
        } catch (RuntimeException error) {
            return false;
        }
    }

    private static String date(String value) {
        try {
            return Instant.parse(value).atZone(ZoneOffset.UTC).toLocalDateTime().toString().replace('T', ' ') + " UTC";
        } catch (RuntimeException error) {
            return "time unknown";
        }
    }
}
