package net.newminecraftservers.companion;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerListPingEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class NewMinecraftServersCompanion extends JavaPlugin
    implements Listener, CommandExecutor, TabCompleter {

    private static final String SITE = "https://newminecraftservers.net";
    private static final String DASHBOARD = SITE + "/dashboard/servers";
    private static final List<String> PUBLIC_RANGES = List.of("24h", "7d", "30d");
    private static final List<String> RANGES = List.of("24h", "7d", "30d", "90d");
    private static final Pattern CLAIM_CODE = Pattern.compile("^(?:NMS)?([2-9A-HJ-NP-Z]{8})$");
    /** The code stays in the server list for at most this many checks, 10 seconds apart. */
    private static final int CLAIM_CHECKS = 12;

    private final LinkChallenge challenge = new LinkChallenge();
    private final AtomicInteger claimGeneration = new AtomicInteger();
    private ApiClient api;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        rebuildClient();
        PluginCommand command = Objects.requireNonNull(getCommand("nms"), "nms command is missing");
        command.setExecutor(this);
        command.setTabCompleter(this);
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("NewMinecraftServers Companion enabled; API: " + ApiClient.configuredBaseUri());
    }

    @Override
    public void onDisable() {
        claimGeneration.incrementAndGet();
        challenge.clear();
    }

    /** Adds the verification code under the MOTD while a check runs. Never touches server.properties. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onServerListPing(ServerListPingEvent event) {
        String code = challenge.current();
        if (code != null) {
            event.motd(event.motd().append(Component.newline()).append(Component.text(code)));
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String subcommand = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (subcommand) {
            case "help", "?" -> help(sender, label);
            case "status" -> status(sender);
            case "stats" -> stats(sender, args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "7d");
            case "lookup" -> lookup(sender, args);
            case "listing" -> listing(sender);
            case "claim" -> claim(sender, args);
            case "link" -> link(sender, args);
            case "unlink" -> unlink(sender);
            case "reload" -> reload(sender);
            case "set" -> {
                Messages.warn(sender, "Listing edits moved to the website.");
                Messages.linkLine(sender, "Edit your listing:", DASHBOARD);
            }
            default -> Messages.error(sender, "Unknown command. Try /" + label + " help.");
        }
        return true;
    }

    private void help(CommandSender sender, String label) {
        String base = "/" + label;
        Messages.header(sender, "NewMinecraftServers Companion " + getPluginMeta().getVersion());
        sender.sendMessage(Messages.command(base + " status", "how the website sees this server"));
        sender.sendMessage(Messages.command(base + " stats [24h|7d|30d|90d]", "player and uptime history"));
        sender.sendMessage(Messages.command(base + " lookup <address>", "check any listed server"));
        sender.sendMessage(Messages.command(base + " listing", "open this server's page"));
        boolean owner = sender.hasPermission("newminecraftservers.claim") || sender.hasPermission("newminecraftservers.link");
        if (owner) sender.sendMessage(Component.text("  Server owners", Messages.BRAND));
        if (sender.hasPermission("newminecraftservers.claim")) {
            sender.sendMessage(Messages.command(base + " claim <code>", "prove you own this server"));
        }
        if (sender.hasPermission("newminecraftservers.link")) {
            sender.sendMessage(Messages.command(base + " link <address>", "connect for 90-day stats"));
            sender.sendMessage(Messages.command(base + " unlink", "disconnect this installation"));
        }
        if (sender.hasPermission("newminecraftservers.reload")) {
            sender.sendMessage(Messages.command(base + " reload", "reload config.yml"));
        }
    }

    private void status(CommandSender sender) {
        if (!allowed(sender, "newminecraftservers.status")) return;
        complete(sender, configuredServer(), server -> {
            Messages.lines(sender, StatsFormatter.status(
                server,
                getServer().getOnlinePlayers().size(),
                getServer().getMaxPlayers(),
                Instant.now()
            ));
            Messages.linkLine(sender, "Listing:", SITE + "/server/" + server.slug());
        });
    }

    private void stats(CommandSender sender, String range) {
        if (!allowed(sender, "newminecraftservers.status")) return;
        if (!RANGES.contains(range)) {
            Messages.error(sender, "Range must be 24h, 7d, 30d, or 90d.");
            return;
        }
        if (range.equals("90d") && token().isBlank()) {
            Messages.warn(sender, "90-day history needs a linked server. An operator can run /nms link <address>.");
            return;
        }
        complete(sender, configuredServer().thenCompose(server ->
            api.history(server.slug(), range, token()).thenApply(history -> new StatsResult(server, history))
        ), result -> {
            Messages.lines(sender, StatsFormatter.history(result.server, result.history));
            Messages.linkLine(sender, "Full charts:", SITE + "/server/" + result.server.slug() + "/stats");
        });
    }

    private void lookup(CommandSender sender, String[] args) {
        if (!allowed(sender, "newminecraftservers.lookup")) return;
        if (args.length < 2) {
            Messages.error(sender, "Usage: /nms lookup <address-or-slug> [24h|7d|30d]");
            return;
        }
        String reference = args[1].trim().toLowerCase(Locale.ROOT);
        String range = args.length >= 3 ? args[2].toLowerCase(Locale.ROOT) : "7d";
        if (!PUBLIC_RANGES.contains(range)) {
            Messages.error(sender, "Lookup range must be 24h, 7d, or 30d.");
            return;
        }
        Messages.info(sender, "Looking up " + reference + "…");
        complete(sender, api.lookup(reference).thenCompose(server ->
            api.history(server.slug(), range, null).thenApply(history -> new StatsResult(server, history))
        ), result -> {
            Messages.lines(sender, StatsFormatter.history(result.server, result.history));
            Messages.linkLine(sender, "Listing:", SITE + "/server/" + result.server.slug());
        });
    }

    private void listing(CommandSender sender) {
        if (!allowed(sender, "newminecraftservers.listing")) return;
        complete(sender, configuredServer(), server ->
            Messages.linkLine(sender, safeName(server.name()) + ":", SITE + "/server/" + server.slug())
        );
    }

    static String normalizeClaimCode(String value) {
        String compact = value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        var match = CLAIM_CODE.matcher(compact);
        return match.matches() ? "NMS-" + match.group(1) : null;
    }

    /**
     * Shows the website's claim code in the server list for up to two minutes and asks the
     * website to check. The code ties the proof to the account that started the claim.
     */
    private void claim(CommandSender sender, String[] args) {
        if (!allowed(sender, "newminecraftservers.claim")) return;
        if (args.length == 2 && args[1].equalsIgnoreCase("cancel")) {
            claimGeneration.incrementAndGet();
            if (challenge.current() != null) {
                challenge.clear();
                Messages.success(sender, "Stopped. Your server list shows the normal MOTD again.");
            } else {
                Messages.info(sender, "No claim check is running.");
            }
            return;
        }
        if (args.length < 2) {
            Messages.info(sender, "Start a claim on the website, then run /nms claim <code> here.");
            Messages.linkLine(sender, "Get your code:", DASHBOARD);
            return;
        }
        // Players often type the code with spaces ("nms 7k3q px9a"); join everything after "claim".
        String code = normalizeClaimCode(String.join("", Arrays.copyOfRange(args, 1, args.length)));
        if (code == null) {
            Messages.error(sender, "That is not a claim code. Codes look like NMS-7K3QPX9A.");
            Messages.linkLine(sender, "Copy yours from", DASHBOARD);
            return;
        }
        if (!challenge.activateIfIdle(code)) {
            Messages.error(sender, "Another verification is already running. Wait for it, or run /nms claim cancel.");
            return;
        }
        int generation = claimGeneration.incrementAndGet();
        Messages.send(sender, Component.text()
            .append(Component.text("Showing ", NamedTextColor.GRAY))
            .append(Messages.code(code))
            .append(Component.text(" in your server list while NewMinecraftServers checks it…", NamedTextColor.GRAY))
            .build());
        Messages.info(sender, "Your normal MOTD comes back as soon as the check finishes (2 minutes at most).");
        pollClaim(sender, code, 1, generation);
    }

    private void pollClaim(CommandSender sender, String code, int attempt, int generation) {
        long delaySeconds = attempt == 1 ? 2 : 10;
        CompletableFuture<ApiModels.ClaimCheck> check = CompletableFuture
            .runAsync(() -> {}, CompletableFuture.delayedExecutor(delaySeconds, TimeUnit.SECONDS))
            .thenCompose(ignored -> generation == claimGeneration.get()
                ? api.claimCheck(code)
                : CompletableFuture.failedFuture(new CancellationMarker()));
        check.whenComplete((result, failure) -> runMain(() -> {
            if (generation != claimGeneration.get() || !code.equals(challenge.current())) return;
            if (failure != null) {
                challenge.clear();
                reportFailure(sender, failure);
                return;
            }
            switch (result.status()) {
                case "pending" -> {
                    if (attempt == 1) {
                        String where = result.checkedAddress() == null ? "your listed address" : result.checkedAddress();
                        Messages.info(sender, "Not visible at " + where + " yet. Checking again every 10 seconds…");
                    }
                    if (attempt < CLAIM_CHECKS) {
                        pollClaim(sender, code, attempt + 1, generation);
                        return;
                    }
                    challenge.clear();
                    Messages.warn(sender, "We could not see the code in your server list, so the MOTD is back to normal.");
                    String observed = firstLine(result.observedMotd());
                    if (!observed.isBlank()) Messages.info(sender, "Players at " + result.checkedAddress() + " see: " + observed);
                    Messages.info(sender, "Behind BungeeCord or Velocity, or using a MOTD plugin? Put the code in that MOTD instead.");
                    Messages.linkLine(sender, "Other ways to verify:", DASHBOARD);
                }
                case "verified", "approved" -> {
                    challenge.clear();
                    Messages.success(sender, result.message());
                    if (result.server() != null && result.server().slug() != null && !result.server().slug().isBlank()) {
                        if (getConfig().getString("listing-slug", "").isBlank()) {
                            getConfig().set("listing-slug", result.server().slug());
                            saveConfig();
                        }
                        Messages.linkLine(sender, "Manage it:", DASHBOARD + "/" + result.server().slug());
                    }
                }
                case "disputed" -> {
                    challenge.clear();
                    Messages.warn(sender, result.message());
                }
                default -> {
                    challenge.clear();
                    Messages.error(sender, result.message());
                    Messages.linkLine(sender, "Start again:", DASHBOARD);
                }
            }
        }));
    }

    private void link(CommandSender sender, String[] args) {
        if (!allowed(sender, "newminecraftservers.link")) return;
        if (args.length != 2) {
            Messages.error(sender, "Usage: /nms link <public-address>");
            return;
        }
        String address = args[1].trim().toLowerCase(Locale.ROOT);
        if (challenge.current() != null) {
            Messages.error(sender, "Another verification is already running. Wait for it, or run /nms claim cancel.");
            return;
        }
        Messages.info(sender, "Asking NewMinecraftServers to verify " + address + "…");
        int generation = claimGeneration.incrementAndGet();
        CompletableFuture<ApiModels.LinkVerified> request = api.startLink(address).thenCompose(started -> {
            if (!challenge.activateIfIdle(started.challengeCode())) {
                return CompletableFuture.failedFuture(new IllegalStateException("Another verification is already running."));
            }
            runMain(() -> Messages.info(sender, "Showing " + started.challengeCode() + " in your server list for a moment…"));
            return api.verifyLink(started.linkId(), started.secret());
        });
        request.whenComplete((ignored, error) -> {
            if (generation == claimGeneration.get()) challenge.clear();
        });
        complete(sender, request, verified -> {
            getConfig().set("public-address", address);
            getConfig().set("listing-slug", verified.server().slug());
            getConfig().set("link-token", verified.token());
            saveConfig();
            Messages.success(sender, verified.created()
                ? "Linked and added " + safeName(verified.server().name()) + ". Monitoring has started."
                : "Linked to " + safeName(verified.server().name()) + ". 90-day stats are now available here.");
            Messages.linkLine(sender, "Listing:", SITE + "/server/" + verified.server().slug());
            Messages.info(sender, "To edit the listing, claim it on the website: " + DASHBOARD.replace("https://", ""));
        });
    }

    private void unlink(CommandSender sender) {
        if (!allowed(sender, "newminecraftservers.link")) return;
        String token = token();
        if (token.isBlank()) {
            clearLink();
            Messages.info(sender, "This server was not linked.");
            return;
        }
        complete(sender, api.unlink(token), ignored -> {
            clearLink();
            Messages.success(sender, "Unlinked. The public listing and its history stay online.");
        });
    }

    private void reload(CommandSender sender) {
        if (!allowed(sender, "newminecraftservers.reload")) return;
        reloadConfig();
        rebuildClient();
        Messages.success(sender, "NewMinecraftServers Companion configuration reloaded.");
    }

    private CompletableFuture<ApiModels.ServerDetail> configuredServer() {
        String slug = getConfig().getString("listing-slug", "").trim();
        String address = getConfig().getString("public-address", "").trim();
        if (slug.isBlank() && address.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                "Server is not linked. An operator can run /nms link <public-address>."
            ));
        }
        return api.configuredServer(slug, address);
    }

    private void rebuildClient() {
        int timeout = Math.max(1, Math.min(getConfig().getInt("request-timeout-seconds", 5), 30));
        int cache = Math.max(0, Math.min(getConfig().getInt("cache-seconds", 60), 300));
        api = ApiClient.production(timeout, cache);
    }

    private String token() {
        return getConfig().getString("link-token", "").trim();
    }

    private void clearLink() {
        getConfig().set("public-address", "");
        getConfig().set("listing-slug", "");
        getConfig().set("link-token", "");
        saveConfig();
    }

    private boolean allowed(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) return true;
        Messages.error(sender, "You do not have permission to use this command.");
        return false;
    }

    private <T> void complete(CommandSender sender, CompletableFuture<T> future, Consumer<T> success) {
        future.whenComplete((value, failure) -> runMain(() -> {
            if (failure == null) {
                success.accept(value);
                return;
            }
            reportFailure(sender, failure);
        }));
    }

    private void reportFailure(CommandSender sender, Throwable failure) {
        Throwable error = unwrap(failure);
        if (error instanceof CancellationMarker) return;
        if (error instanceof ApiException apiError) {
            if (apiError.status() == 404 && "not_found".equals(apiError.code())) {
                Messages.error(sender, "That server is not listed on NewMinecraftServers.");
            } else {
                Messages.error(sender, apiError.getMessage());
            }
        } else if (error instanceof IllegalArgumentException || error instanceof IllegalStateException) {
            Messages.error(sender, error.getMessage());
        } else {
            getLogger().log(Level.WARNING, "NewMinecraftServers request failed: " + error.getClass().getSimpleName());
            Messages.error(sender, "NewMinecraftServers is unreachable right now. Try again shortly.");
        }
    }

    private void runMain(Runnable task) {
        if (!isEnabled()) return;
        getServer().getScheduler().runTask(this, task);
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
            && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String safeName(String value) {
        String cleaned = Messages.clean(value).trim();
        return cleaned.isEmpty() ? "this server" : cleaned;
    }

    private static String firstLine(String value) {
        if (value == null) return "";
        String cleaned = Messages.clean(value).trim();
        int newline = cleaned.indexOf('\n');
        return newline >= 0 ? cleaned.substring(0, newline).trim() : cleaned;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> values = new ArrayList<>(List.of("help", "status", "stats", "lookup", "listing"));
            if (sender.hasPermission("newminecraftservers.claim")) values.add("claim");
            if (sender.hasPermission("newminecraftservers.link")) values.addAll(List.of("link", "unlink"));
            if (sender.hasPermission("newminecraftservers.reload")) values.add("reload");
            return prefix(values, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("stats")) return prefix(RANGES, args[1]);
        if (args.length == 2 && args[0].equalsIgnoreCase("claim") && sender.hasPermission("newminecraftservers.claim")) {
            return prefix(List.of("cancel"), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("lookup")) return prefix(PUBLIC_RANGES, args[2]);
        return List.of();
    }

    private static List<String> prefix(List<String> values, String input) {
        String normalized = input.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.startsWith(normalized)).toList();
    }

    private record StatsResult(ApiModels.ServerDetail server, ApiModels.History history) {}

    /** A claim that was cancelled or replaced before its next check; nothing to report. */
    private static final class CancellationMarker extends RuntimeException {
        CancellationMarker() {
            super(null, null, false, false);
        }
    }
}
