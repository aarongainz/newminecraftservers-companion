package net.newminecraftservers.companion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class StatsFormatterTest {
    @Test
    void formatsObservedStatusWithoutInterpretingLegacyFormatting() {
        ApiModels.ServerDetail server = server("§kUnsafe");
        List<String> lines = StatsFormatter.status(server, 14, 100, Instant.parse("2026-09-18T12:05:00Z"));
        assertEquals("NewMinecraftServers · ?kUnsafe", lines.get(0));
        assertTrue(lines.get(1).contains("10/100 players · 5m ago"));
        assertEquals("Paper now: 14/100 players", lines.get(2));
    }

    @Test
    void derivesBusiestWeekdayOnlyFromSufficientDailyHistory() {
        List<ApiModels.HistoryPoint> points = new ArrayList<>();
        Instant start = Instant.parse("2026-09-01T00:00:00Z");
        for (int index = 0; index < 21; index++) {
            Instant day = start.plusSeconds(index * 86_400L);
            double players = day.atZone(java.time.ZoneOffset.UTC).getDayOfWeek() == java.time.DayOfWeek.SATURDAY ? 90 : 10;
            points.add(new ApiModels.HistoryPoint(day.toString(), players, (int) players, 1.0));
        }
        assertEquals("Saturday", StatsFormatter.busiestWeekday(points));

        ApiModels.History history = new ApiModels.History(
            "30d", "day", points,
            new ApiModels.HistorySummary(3_000, 21, new ApiModels.Peak(90, points.get(4).t()), 20.0, 0.99, 20),
            List.of(), 0, points.get(0).t()
        );
        List<String> lines = StatsFormatter.history(server("Alpha"), history);
        assertTrue(lines.stream().anyMatch(line -> line.contains("99.0% across 3,000 checks")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("Observation coverage: 21 days")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("Busiest weekday: Saturday")));

        ApiModels.History tooYoung = new ApiModels.History(
            "24h", "sample", points,
            new ApiModels.HistorySummary(100, 1, null, 20.0, 1.0, null),
            List.of(), 0, points.get(0).t()
        );
        assertTrue(StatsFormatter.history(server("Alpha"), tooYoung).stream()
            .noneMatch(line -> line.contains("Busiest weekday")));
    }

    @Test
    void challengeDecorationIsTemporary() {
        LinkChallenge challenge = new LinkChallenge();
        assertEquals("Original", challenge.decorateText("Original"));
        challenge.activate("NMS-ABC234");
        assertEquals("Original\nNMS-ABC234", challenge.decorateText("Original"));
        assertTrue(!challenge.activateIfIdle("NMS-OTHER1"), "a second verification must wait");
        challenge.clear();
        assertEquals("Original", challenge.decorateText("Original"));
        assertTrue(challenge.activateIfIdle("NMS-OTHER1"));
    }

    private static ApiModels.ServerDetail server(String name) {
        ApiModels.Connection connection = new ApiModels.Connection(
            "conn", "java", "play.example.net", 25565,
            new ApiModels.Latest("online", "2026-09-18T12:00:00Z", 10, 100, 12.0, 772, "Paper", "MOTD")
        );
        ApiModels.Snapshot snapshot = new ApiModels.Snapshot(
            "conn", "java", "online", "2026-09-18T12:00:00Z", 10, 100, 772, "Paper"
        );
        return new ApiModels.ServerDetail(
            "server", "alpha", name,
            new ApiModels.Classification(List.of("survival"), List.of("survival")),
            connection, snapshot, new ApiModels.Links(null, null),
            "2026-09-01T00:00:00Z", "2026-09-18T12:00:00Z"
        );
    }
}
