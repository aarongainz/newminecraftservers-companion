# Paper plugin

## Purpose

**NewMinecraftServers Companion** is a Paper plugin that lets an operator prove they own a server
for their NewMinecraftServers account (`/nms claim`), connect an installation for 90-day stats
(`/nms link`), and read NewMinecraftServers' externally observed status and history without
leaving Minecraft. Every command works from the console and in game.

It sends no player names, UUIDs, chat, player IPs, or command telemetry. It has no analytics,
advertising, account login, remote code, or voting integration.

## Claiming flow (owner accounts)

1. On the website, the owner opens their listing (or adds a new one) and presses **Claim**. The
   website shows a code such as `NMS-7K3QPX9A`, tied to their signed-in, email-verified account.
2. The owner runs `/nms claim NMS-7K3QPX9A` in the console or in game as an operator. Spacing and
   case do not matter (`/nms claim nms 7k3q px9a` works).
3. The plugin adds the code as an extra line under the MOTD **in memory only** (the server-list
   ping event), never in `server.properties`, and calls `POST /api/v1/paper/claim/check`.
4. The website pings the listing's addresses like its normal status checks. The plugin retries
   every 10 seconds for up to two minutes, then removes the code whether or not it was seen.
   `/nms claim cancel` removes it immediately; a restart also clears it.
5. On success the listing belongs to that account, and edits happen on the website dashboard.
   If the server already has a verified owner, the proof goes to an admin for review. Listings
   never change hands automatically.

Only one verification (claim or link) shows a code at a time. Behind BungeeCord or Velocity, the
proxy answers server-list pings, so owners should put the code in the proxy MOTD instead (the
website explains this and offers DNS verification too).

## Linking flow (installation stats)

1. An operator runs `/nms link play.example.net[:port]`.
2. The API creates a one-use, two-minute challenge. The plugin temporarily appends the challenge
   to the server-list MOTD.
3. The API resolves and pings that exact public Java endpoint through the existing protected
   Minecraft probe.
4. Verification succeeds only when the public ping contains the exact challenge.
5. An existing matching listing is linked, or a minimal published listing is created. Monitoring
   is enabled and the successful verification ping becomes its first observation.
6. The API returns a server-scoped credential. The plugin saves it in `plugins/NewMinecraftServersCompanion/config.yml`;
   the backend stores only its SHA-256 hash.

The backend rejects literal private/reserved addresses, rechecks resolved addresses before
connecting, rate-limits starts per process, limits verification attempts, deduplicates concurrent
links, and never turns `/nms lookup` or the ordinary lookup endpoint into a network scanner.

New listings use the verified Java address, ping status, MOTD, version, population, and safe
literal genre terms found in the MOTD. These are starting values, not claims inferred by an LLM.
Linking does not grant editing: the listing is edited by its verified owner on the website, and a
link never republishes a listing that its owner or an admin hid.

Bedrock discovery, port scanning, proxy-specific plugins, votes, two-way synchronization, and
telemetry are deliberately deferred. A BungeeCord or Velocity setup
that owns the public status response may hide Paper's temporary MOTD challenge; v1 linking should
target an endpoint whose public ping reaches the Paper server.

## Where it lives

- Download: [SpigotMC resource 138899](https://www.spigotmc.org/resources/newminecraftservers-companion.138899/)
- Download: [BuiltByBit resource 126876](https://builtbybit.com/resources/newminecraftservers-companion.126876/)
- Public landing page: `/plugin` on the website
- Source (MIT): this repo
- On each release, bump `version` in the website's `app/imports/public/companion-plugin.ts` (private Meteor repo; used by `/plugin` and the claim page's plugin tab)

## Installation

Requirements:

- Paper 1.20.4 or newer.
- The Java runtime required by that Paper release.
- A publicly reachable Java address. Localhost and private network addresses cannot be verified.
- The `/api/v1/paper/*` backend routes deployed at `https://newminecraftservers.net`.

Copy `new-minecraft-servers-companion-0.2.0.jar` into the server's `plugins/` directory and restart
Paper. The production origin is hardcoded to the apex hostname; there is no `www` fallback and no
config option for the API URL. For local testing only, the JVM flag
`-Dnewminecraftservers.apiBase=http://127.0.0.1:<port>/api/v1/` points it at a loopback server;
any non-loopback value is refused at startup.

## Commands

| Command | Purpose | Default permission |
| --- | --- | --- |
| `/nms help` | Show available commands | Everyone |
| `/nms status` | Compare the latest external observation with local player capacity | Everyone |
| `/nms stats [24h\|7d\|30d\|90d]` | Show peak, average, uptime, busiest time, and observation coverage | Everyone |
| `/nms lookup <address-or-slug> [24h\|7d\|30d]` | Read another published listing; never auto-add it | Everyone |
| `/nms listing` | Print the configured listing URL | Everyone |
| `/nms claim <code>` | Show a website claim code in the server list until it is verified | Operators |
| `/nms claim cancel` | Stop a running claim check and restore the MOTD | Operators |
| `/nms link <public-address>` | Verify and link or auto-add this server for 90-day stats | Operators |
| `/nms unlink` | Revoke this installation's credential; retain the public listing/history | Operators |
| `/nms reload` | Reload validated local configuration | Operators |

`/newminecraftservers` is an alias for `/nms`. `/nms set …` from 0.1.x now replies that listing
edits moved to the website. In game, help lines are clickable (they type the command for you),
links open in the browser, and the claim code can be clicked to copy.

Permissions are `newminecraftservers.status`, `.lookup`, `.listing`, `.claim`, `.link`, and
`.reload`. The first three default to everyone; the rest default to operators, and help and tab
completion only show what the sender may run.

## Configuration

```yaml
public-address: ""
listing-slug: ""
link-token: ""
request-timeout-seconds: 5
cache-seconds: 60
```

`public-address`, `listing-slug`, and `link-token` are written after a successful link. Do not
publish or share the token. Requests time out between 1 and 30 seconds; cached reads are bounded to
0–300 seconds. Every HTTP request is asynchronous and every Bukkit response is scheduled back on
the server thread.

## Build, package, and smoke test

```sh
./gradlew build
npm ci
JAVA21_HOME=/path/to/jdk-21 JAVA25_HOME=/path/to/jdk-25 npm run smoke
```

`./gradlew build` compiles Java 17 bytecode, runs the JUnit suite, and writes the JAR to
`build/libs/`. `npm run smoke` uses the dev-only player client (`mineflayer`), then starts isolated Paper 1.20.4 and Paper 26.2
servers pointed at a local stand-in API that pings Paper exactly like production. It runs every
command from the console on both versions and, on 1.20.4, as a real logged-in player: first as a
non-operator (owner commands refused and hidden from help and tab completion), then as an operator
(claim verified from the live MOTD, a second claim cancelled, the MOTD restored each time, `link`
blocked while a claim runs, `set` redirected to the website). Logs, including the player's chat
and the API calls, are written under `build/smoke/runs/`.

## Deploy and production validation

The plugin is locally complete, but linking cannot work in production until the matching Meteor
API changes are deployed.

1. Deploy the Meteor app, including any new collection indexes and `/api/v1` routes.
2. Confirm `GET https://newminecraftservers.net/api/v1/servers/<known-slug>/history?range=24h`
   returns a `{ "data": ... }` envelope.
3. Install the verified JAR on a disposable or pilot public Paper server.
4. Run `/nms link <public-address>` as an operator while its public status endpoint is reachable.
5. Confirm the listing exists, monitoring is enabled, and `/nms status`, `/nms stats 24h`, and
   `/nms listing` work.
6. Start a claim on the website for that listing, run `/nms claim <code>`, and confirm the
   dashboard shows it as yours and the MOTD is back to normal.
7. Run `/nms unlink`; confirm the credential is revoked while the listing and history remain.

Do not test linking with localhost or a private address: rejection is intentional SSRF protection.
No marketplace upload or production mutation is part of this goal.
