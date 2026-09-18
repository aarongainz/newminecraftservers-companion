package net.newminecraftservers.companion;

import java.util.List;

final class ApiModels {
    private ApiModels() {}

    record LinkStart(
        String linkId,
        String secret,
        String challengeCode,
        String expiresAt,
        String address
    ) {}

    record LinkVerified(String token, boolean created, ServerDetail server) {}

    record ClaimCheck(
        String status,
        ClaimServer server,
        String checkedAddress,
        String observedMotd,
        String message
    ) {}

    record ClaimServer(String name, String slug) {}

    record ServerDetail(
        String id,
        String slug,
        String name,
        Classification classification,
        Connection primaryConnection,
        Snapshot primarySnapshot,
        Links links,
        String firstSeenByUsAt,
        String lastSeenByUsAt
    ) {}

    record Classification(List<String> genreIds, List<String> tags) {}

    record Links(String websiteUrl, String discordUrl) {}

    record Connection(
        String id,
        String edition,
        String host,
        int port,
        Latest latest
    ) {}

    record Latest(
        String outcome,
        String observedAt,
        Integer playersOnline,
        Integer playersMax,
        Double latencyMs,
        Integer protocol,
        String versionName,
        String motdPlain
    ) {}

    record Snapshot(
        String connectionId,
        String edition,
        String outcome,
        String observedAt,
        Integer playersOnline,
        Integer playersMax,
        Integer protocol,
        String versionName
    ) {}

    record History(
        String range,
        String resolution,
        List<HistoryPoint> points,
        HistorySummary summary,
        List<VersionEntry> versions,
        int motdChanges,
        String firstCheckAt
    ) {}

    record HistoryPoint(String t, Double players, Integer peak, Double uptime) {}

    record HistorySummary(
        int checks,
        int daysCovered,
        Peak peak,
        Double averagePlayers,
        Double uptimeRatio,
        Integer busiestHourUtc
    ) {}

    record Peak(int players, String at) {}

    record VersionEntry(String versionName, String since) {}
}
