<p align="center">
  <img src="artwork/icon-512.png" alt="" width="96" height="96">
</p>

<h1 align="center">NewMinecraftServers Companion</h1>

<p align="center">
  Claim your server's listing on <a href="https://newminecraftservers.net">NewMinecraftServers</a> with one command,<br>
  and see how the server list sees you without leaving the game.
</p>

<p align="center">
  <a href="https://github.com/aarongainz/newminecraftservers-companion/actions/workflows/build.yml"><img alt="Build" src="https://github.com/aarongainz/newminecraftservers-companion/actions/workflows/build.yml/badge.svg"></a>
  <img alt="Paper 1.20.4+" src="https://img.shields.io/badge/Paper-1.20.4%2B-2f7cf6">
  <img alt="Java 17+" src="https://img.shields.io/badge/Java-17%2B-e76f00">
  <a href="LICENSE"><img alt="MIT license" src="https://img.shields.io/badge/license-MIT-3c8527"></a>
</p>

---

## Features

- **Claim your listing in one command.** Start a claim on the website, run `/nms claim <code>`, done. The plugin shows the code in your server list for a moment and puts your MOTD back as soon as it is verified.
- **Never touches your files.** The code is added in memory while the server list is pinged. `server.properties` is never edited, and a restart or `/nms claim cancel` clears it instantly.
- **Live status.** `/nms status` compares what NewMinecraftServers last saw with your server right now.
- **Player and uptime history.** `/nms stats` shows peak and average players, uptime, and your busiest hour and weekday (up to 90 days once linked).
- **Look up any listed server** with `/nms lookup <address>`.
- **Works for players and the console.** Clickable help, links that open in the browser, and a click-to-copy claim code. Help and tab-completion only show what each sender may use.
- **Tiny and quiet.** No dependencies to install, no database, no player data sent anywhere.

## Requirements

| | |
| --- | --- |
| Server | [Paper](https://papermc.io) 1.20.4 or newer. Tested on 1.20.4 and 26.2. |
| Java | Whatever your Paper version needs (17+) |
| Network | The server must be reachable from the internet for claiming and linking |

> Spigot and CraftBukkit are not supported: the plugin uses Paper's Adventure and server-list APIs.

## Installation

1. Download `new-minecraft-servers-companion-<version>.jar` from [Releases](https://github.com/aarongainz/newminecraftservers-companion/releases).
2. Put it in your server's `plugins/` folder.
3. Restart the server.

## Claim your listing

1. Sign in at [newminecraftservers.net](https://newminecraftservers.net) and open **My servers**.
2. Type your server's address. If it is already listed, choose **Claim**. If not, choose **Add**.
3. Copy the code you are given, for example `NMS-7K3QPX9A`.
4. Run it in the console, or in game as an operator:

   ```
   /nms claim NMS-7K3QPX9A
   ```

5. The plugin shows the code under your MOTD while NewMinecraftServers pings your server, checking every 10 seconds for up to two minutes. When it is seen, the listing is yours to edit on the website and your normal MOTD is back.

Spaces and letter case do not matter: `/nms claim nms 7k3q px9a` works too.

<details>
<summary><b>Running BungeeCord or Velocity?</b></summary>

On a proxy network the proxy answers server-list pings, not the Paper server behind it, so the plugin cannot show the code to the outside world. Use one of the website's other methods instead:

- add the code to the **proxy's MOTD** (`config.yml` / `velocity.toml`) for a moment, or
- add a **DNS TXT record** `_nms-verify.<your domain>` with the value `nms-verify=<code>`.
</details>

<details>
<summary><b>Using a MOTD plugin?</b></summary>

MiniMOTD, ServerListPlus, and similar plugins can replace the server-list response after this plugin runs. If `/nms claim` reports that the code was not visible, put the code in that plugin's MOTD for a moment, or verify with DNS on the website.
</details>

## Commands

| Command | Description | Default |
| --- | --- | --- |
| `/nms help` | Show the commands you can use | everyone |
| `/nms status` | Latest public status next to your live player count | everyone |
| `/nms stats [24h\|7d\|30d\|90d]` | Players, uptime, and busiest times (`90d` needs a linked server) | everyone |
| `/nms lookup <address-or-slug> [24h\|7d\|30d]` | Stats for any listed server | everyone |
| `/nms listing` | Link to this server's page | everyone |
| `/nms claim <code>` | Prove ownership for your website account | op |
| `/nms claim cancel` | Stop a running claim and restore the MOTD now | op |
| `/nms link <address>` | Connect this installation for 90-day stats | op |
| `/nms unlink` | Disconnect this installation (the listing stays online) | op |
| `/nms reload` | Reload `config.yml` | op |

`/newminecraftservers` works as an alias for `/nms`.

## Permissions

| Permission | Grants | Default |
| --- | --- | --- |
| `newminecraftservers.status` | `status`, `stats` | everyone |
| `newminecraftservers.lookup` | `lookup` | everyone |
| `newminecraftservers.listing` | `listing` | everyone |
| `newminecraftservers.claim` | `claim` | op |
| `newminecraftservers.link` | `link`, `unlink` | op |
| `newminecraftservers.reload` | `reload` | op |

## Configuration

`plugins/NewMinecraftServersCompanion/config.yml`:

```yaml
public-address: ""           # written by /nms link
listing-slug: ""             # written by /nms link or a successful /nms claim
link-token: ""               # written by /nms link; keep it private
request-timeout-seconds: 5   # 1–30
cache-seconds: 60            # 0–300, for status and stats lookups
```

The website address is built into the plugin and cannot be changed from config, so nothing can quietly redirect its requests.

## Privacy and security

The plugin only talks to `https://newminecraftservers.net/api/v1/`, and only when a command needs it.

| Sent | When |
| --- | --- |
| Your claim code | `/nms claim` |
| The address you type | `/nms link`, `/nms lookup` |
| Your installation's link token | `/nms stats 90d`, `/nms unlink` |

It never sends player names, UUIDs, IP addresses, chat, command history, or analytics. All requests are asynchronous with a timeout and never follow redirects. Replies from the website are shown as plain text, never as formatting codes.

Claiming ties the proof to the account that created the code. If a listing already has a verified owner, a new proof goes to a human admin for review. Listings never change hands automatically.

## Building from source

```bash
./gradlew build
```

The JAR is written to `build/libs/`. Gradle downloads the Java 17 toolchain automatically if you do not have it.

## Testing

**Unit tests** run as part of `./gradlew build`.

**End-to-end smoke test.** This starts real Paper servers (1.20.4 and 26.2) against a local stand-in for the website API. The stand-in pings Paper exactly like production does. The test runs every command from the console and, on 1.20.4, as a real logged-in player: first without op, then with op. It checks that the claim code really appears in the public MOTD and disappears afterwards.

```bash
npm ci
JAVA21_HOME=/path/to/jdk-21 JAVA25_HOME=/path/to/jdk-25 npm run smoke
```

Docker is used automatically when a JDK path is not set. Logs, including the player's chat and every API call, are written to `build/smoke/runs/`.

For local development only, the JVM flag `-Dnewminecraftservers.apiBase=http://127.0.0.1:<port>/api/v1/` points the plugin at a local API. Non-loopback values are refused at startup.

## Contributing

Issues and pull requests are welcome. Please run `./gradlew build` (and the smoke test for anything touching commands or the MOTD) before opening a PR.

## License

[MIT](LICENSE)
