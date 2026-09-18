import { createHash } from 'node:crypto';
import {
  copyFile,
  mkdir,
  mkdtemp,
  readFile,
  stat,
  writeFile,
} from 'node:fs/promises';
import { spawn, spawnSync } from 'node:child_process';
import { createServer as createHttpServer } from 'node:http';
import { createServer as createNetServer } from 'node:net';
import { createRequire } from 'node:module';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, '..');
const output = resolve(root, 'build/smoke');
const pluginJar = resolve(root, 'build/libs/new-minecraft-servers-companion-0.2.0.jar');
const userAgent = 'NewMinecraftServersCompanionTests/0.2.0 (+https://newminecraftservers.net)';
const require = createRequire(resolve(here, '../package.json'));

/*
 * Real Paper servers, a real logged-in player (mineflayer), and a local stand-in for the website
 * API that pings Paper exactly like production does, so the MOTD code path is proven end to end.
 * Player sessions run on the versions mineflayer speaks; every target runs the console pass.
 */
const targets = [
  {
    version: '1.20.4',
    java: process.env.JAVA21_HOME ? resolve(process.env.JAVA21_HOME, 'bin/java') : '',
    image: 'eclipse-temurin:21-jre',
    player: true,
  },
  {
    version: '26.2',
    java: process.env.JAVA25_HOME ? resolve(process.env.JAVA25_HOME, 'bin/java') : '',
    image: 'eclipse-temurin:25-jre',
    player: false,
  },
];
const dockerReady = spawnSync('docker', ['info'], { stdio: 'ignore' }).status === 0;

const VERIFY_CODE = 'NMS-7K3QPX9A';
const SLOW_CODE = 'NMS-33333333';
const exists = async (path) => stat(path).then(() => true).catch(() => false);
if (!await exists(pluginJar)) throw new Error('Build the plugin first: ./gradlew build');

let mineflayer;
let minecraftProtocol;
try {
  mineflayer = require('mineflayer');
  minecraftProtocol = require('minecraft-protocol');
} catch {
  throw new Error('Install the smoke-test player client first: npm ci');
}

const fetchJson = async (url) => {
  const response = await fetch(url, { headers: { 'User-Agent': userAgent, Accept: 'application/json' } });
  if (!response.ok) throw new Error(`Paper downloads API returned ${response.status} for ${url}`);
  return response.json();
};

const paperJar = async (version) => {
  const cache = resolve(output, 'servers', `paper-${version}.jar`);
  const checksumFile = `${cache}.sha256`;
  if (await exists(cache) && await exists(checksumFile)) {
    const expected = (await readFile(checksumFile, 'utf8')).trim();
    const actual = createHash('sha256').update(await readFile(cache)).digest('hex');
    if (expected === actual) return cache;
  }
  const builds = await fetchJson(`https://fill.papermc.io/v3/projects/paper/versions/${version}/builds`);
  if (!Array.isArray(builds)) throw new Error(`Unexpected Paper build response for ${version}`);
  const build = builds.find(({ channel }) => String(channel).toUpperCase() === 'STABLE')
    ?? builds.find(({ channel }) => String(channel).toUpperCase() === 'RECOMMENDED');
  const download = build?.downloads?.['server:default'];
  if (!download?.url) throw new Error(`No stable Paper server download found for ${version}`);
  const response = await fetch(download.url, { headers: { 'User-Agent': userAgent } });
  if (!response.ok) throw new Error(`Paper JAR download returned ${response.status} for ${version}`);
  const bytes = Buffer.from(await response.arrayBuffer());
  const actual = createHash('sha256').update(bytes).digest('hex');
  const expected = download.checksums?.sha256 ?? download.checksum?.sha256;
  if (expected && expected !== actual) throw new Error(`Paper ${version} checksum mismatch`);
  await mkdir(dirname(cache), { recursive: true });
  await writeFile(cache, bytes);
  await writeFile(checksumFile, `${actual}\n`, 'utf8');
  return cache;
};

const delay = (milliseconds) => new Promise((resolveDelay) => setTimeout(resolveDelay, milliseconds));

const freePort = () => new Promise((resolvePort, reject) => {
  const server = createNetServer();
  server.once('error', reject);
  server.listen(0, '127.0.0.1', () => {
    const { port } = server.address();
    server.close(() => resolvePort(port));
  });
});

const flatten = (component) => {
  if (component == null) return '';
  if (typeof component === 'string') return component;
  if (Array.isArray(component)) return component.map(flatten).join('');
  return `${component.text ?? ''}${flatten(component.extra)}`;
};

/** Same question production asks: what does the public server list say right now? */
const pingMotd = async (port, version) => {
  const response = await minecraftProtocol.ping({ host: '127.0.0.1', port, version, closeTimeout: 5_000 });
  return flatten(response.description);
};

const serverDetail = (slug, name) => ({
  id: `server-${slug}`,
  slug,
  name,
  classification: { genreIds: ['survival'], tags: ['survival'] },
  primaryConnection: {
    id: 'conn', edition: 'java', host: 'play.example.net', port: 25565,
    latest: { outcome: 'online', observedAt: new Date().toISOString(), playersOnline: 7, playersMax: 50 },
  },
  primarySnapshot: {
    connectionId: 'conn', edition: 'java', outcome: 'online', observedAt: new Date().toISOString(),
    playersOnline: 7, playersMax: 50, versionName: 'Paper',
  },
  links: {},
  firstSeenByUsAt: '2026-09-01T00:00:00.000Z',
  lastSeenByUsAt: new Date().toISOString(),
});

const history = (range) => ({
  range,
  resolution: 'hour',
  points: [],
  summary: { checks: 144, daysCovered: 1, peak: { players: 12, at: '2026-09-18T20:00:00.000Z' }, averagePlayers: 6.5, uptimeRatio: 0.99, busiestHourUtc: 20 },
  versions: [],
  motdChanges: 0,
});

/** Local stand-in for https://newminecraftservers.net/api/v1 with production-shaped responses. */
const startFakeApi = async (paperPort, pingVersion) => {
  const calls = [];
  let linkChallenge = 'NMS-LINK23';
  const server = createHttpServer(async (request, response) => {
    const url = new URL(request.url, 'http://127.0.0.1');
    const path = url.pathname.replace(/^\/api\/v1\//, '');
    let body = '';
    for await (const chunk of request) body += chunk;
    const json = body ? JSON.parse(body) : {};
    calls.push(`${request.method} ${path}`);
    const send = (status, payload) => {
      response.writeHead(status, { 'content-type': 'application/json' });
      response.end(JSON.stringify(payload));
    };
    try {
      if (request.method === 'POST' && path === 'paper/claim/check') {
        if (json.code !== VERIFY_CODE && json.code !== SLOW_CODE) {
          send(404, { error: { code: 'claim_not_found', message: 'No claim uses that code. Start one at newminecraftservers.net/dashboard/servers and copy the code shown there.' } });
          return;
        }
        const motd = await pingMotd(paperPort, pingVersion);
        const visible = motd.includes(json.code);
        calls.push(`motd-visible:${json.code}:${visible}`);
        if (json.code === VERIFY_CODE && visible) {
          send(200, { data: { status: 'verified', server: { name: 'Smoke Test', slug: 'smoke-test' }, checkedAddress: 'play.example.net', message: 'Verified. You now manage Smoke Test on NewMinecraftServers.' } });
        } else {
          send(200, { data: { status: 'pending', server: { name: 'Smoke Test', slug: 'smoke-test' }, checkedAddress: 'play.example.net', observedMotd: 'NMS smoke test', message: 'Not visible yet' } });
        }
        return;
      }
      if (request.method === 'POST' && path === 'paper/link/start') {
        linkChallenge = 'NMS-LINK23';
        send(200, { data: { linkId: '11111111-1111-4111-8111-111111111111', secret: 'x'.repeat(40), challengeCode: linkChallenge, expiresAt: new Date(Date.now() + 120_000).toISOString(), address: json.address } });
        return;
      }
      if (request.method === 'POST' && path === 'paper/link/verify') {
        await delay(500);
        const motd = await pingMotd(paperPort, pingVersion);
        calls.push(`link-visible:${motd.includes(linkChallenge)}`);
        if (!motd.includes(linkChallenge)) {
          send(409, { error: { code: 'verification_failed', message: 'The verification code was not visible in the public server MOTD' } });
          return;
        }
        send(200, { data: { token: 'smoke-token-abcdefghijklmnopqrstuvwxyz', created: false, server: serverDetail('smoke-test', 'Smoke Test') } });
        return;
      }
      if (request.method === 'POST' && path === 'paper/unlink') {
        send(request.headers.authorization ? 200 : 401, request.headers.authorization ? { data: { revoked: true } } : { error: { code: 'unauthorized', message: 'A valid server token is required' } });
        return;
      }
      if (request.method === 'GET' && path === 'servers/lookup') {
        const address = url.searchParams.get('address');
        if (address === 'play.example.net') send(200, { data: serverDetail('example-network', 'Example Network') });
        else send(404, { error: { code: 'not_found', message: 'Server not found' } });
        return;
      }
      const historyMatch = /^servers\/([a-z0-9-]+)\/history$/.exec(path);
      if (request.method === 'GET' && historyMatch) {
        const range = url.searchParams.get('range') ?? '7d';
        if (range === '90d' && !request.headers.authorization) {
          send(401, { error: { code: 'unauthorized', message: 'A valid server token is required' } });
          return;
        }
        send(200, { data: history(range) });
        return;
      }
      const detailMatch = /^servers\/([a-z0-9-]+)$/.exec(path);
      if (request.method === 'GET' && detailMatch) {
        send(200, { data: serverDetail(detailMatch[1], detailMatch[1] === 'smoke-test' ? 'Smoke Test' : 'Example Network') });
        return;
      }
      send(404, { error: { code: 'not_found', message: 'Endpoint not found' } });
    } catch (error) {
      send(500, { error: { code: 'internal_error', message: String(error) } });
    }
  });
  await new Promise((resolveListen) => server.listen(0, '127.0.0.1', resolveListen));
  return { port: server.address().port, calls, close: () => new Promise((done) => server.close(done)) };
};

const waitFor = async (predicate, label, timeoutMs = 20_000) => {
  const until = Date.now() + timeoutMs;
  while (Date.now() < until) {
    if (predicate()) return;
    await delay(200);
  }
  throw new Error(`Timed out waiting for ${label}`);
};

const runTarget = async ({ version, java, image, player }) => {
  const localJava = Boolean(java) && await exists(java);
  if (!localJava && !dockerReady) {
    throw new Error(`Paper ${version} needs Java ${version === '1.20.4' ? '21' : '25'}: set JAVA${version === '1.20.4' ? '21' : '25'}_HOME or start Docker`);
  }
  const sourceJar = await paperJar(version);
  const smokeRoot = resolve(output, 'runs');
  await mkdir(smokeRoot, { recursive: true });
  const directory = await mkdtemp(resolve(smokeRoot, `paper-${version.replaceAll('.', '-')}-`));
  await mkdir(resolve(directory, 'plugins'), { recursive: true });
  await copyFile(sourceJar, resolve(directory, 'paper.jar'));
  await copyFile(pluginJar, resolve(directory, 'plugins', 'new-minecraft-servers-companion-0.2.0.jar'));
  await writeFile(resolve(directory, 'eula.txt'), 'eula=true\n', 'utf8');
  const paperPort = await freePort();
  // Ping with the protocol mineflayer knows; status pings answer any client version.
  const pingVersion = player ? version : '1.20.4';
  const api = await startFakeApi(paperPort, pingVersion);
  await writeFile(resolve(directory, 'server.properties'), [
    'online-mode=false',
    'enforce-secure-profile=false',
    'server-ip=127.0.0.1',
    `server-port=${paperPort}`,
    'enable-status=true',
    'view-distance=2',
    'simulation-distance=2',
    'spawn-protection=0',
    'level-type=minecraft\\:flat',
    'motd=NMS smoke test',
  ].join('\n') + '\n', 'utf8');

  const apiBase = `-Dnewminecraftservers.apiBase=http://127.0.0.1:${api.port}/api/v1/`;
  const child = localJava
    ? spawn(java, ['-Xms512M', '-Xmx1G', apiBase, '-jar', 'paper.jar', '--nogui'], {
      cwd: directory,
      stdio: ['pipe', 'pipe', 'pipe'],
    })
    : spawn('docker', [
      'run', '--rm', '-i', '--network', 'host',
      '-v', `${directory}:/server`,
      '-w', '/server',
      image,
      'java', '-Xms512M', '-Xmx1G', apiBase, '-jar', 'paper.jar', '--nogui',
    ], { stdio: ['pipe', 'pipe', 'pipe'] });
  let outputText = '';
  child.stdout.on('data', (chunk) => { outputText += chunk.toString(); });
  child.stderr.on('data', (chunk) => { outputText += chunk.toString(); });
  const log = resolve(smokeRoot, `paper-${version}.log`);
  const runConsole = async (command, wait = 900) => {
    child.stdin.write(`${command}\n`);
    await delay(wait);
  };

  try {
    const ready = Date.now() + 240_000;
    while (!/Done \([^)]+\)!/.test(outputText)) {
      if (child.exitCode !== null) throw new Error(`Paper ${version} exited before startup:\n${outputText.slice(-4_000)}`);
      if (Date.now() > ready) throw new Error(`Paper ${version} did not become ready:\n${outputText.slice(-4_000)}`);
      await delay(250);
    }

    // Console pass: every command an operator can run from the terminal.
    for (const command of [
      'plugins', 'nms help', 'nms status', 'nms listing', 'nms set name Smoke Test',
      'nms claim', 'nms claim not-a-code', 'nms claim NMS-22222222',
      'nms lookup play.example.net', 'nms lookup missing.example.net', 'nms stats 90d', 'nms nope',
    ]) await runConsole(command);
    await runConsole(`nms claim ${VERIFY_CODE}`, 0);
    await waitFor(() => outputText.includes('Verified. You now manage Smoke Test'), 'console claim verification');
    const afterClaim = await pingMotd(paperPort, pingVersion);
    if (afterClaim.includes(VERIFY_CODE)) throw new Error(`Paper ${version} kept the claim code in the MOTD: ${afterClaim}`);
    await runConsole('nms status');
    await runConsole(`nms link 127.0.0.1:${paperPort}`, 0);
    await waitFor(() => outputText.includes('Linked to Smoke Test'), 'console link');
    await runConsole('nms stats 90d', 1_500);
    await runConsole('nms unlink');
    await runConsole('nms reload');

    const playerLines = [];
    if (player) {
      const bot = mineflayer.createBot({
        host: '127.0.0.1', port: paperPort, username: 'SmokeOwner', version, auth: 'offline',
        hideErrors: true,
      });
      bot.on('message', (message) => playerLines.push(message.toString()));
      await new Promise((resolveSpawn, reject) => {
        bot.once('spawn', resolveSpawn);
        bot.once('kicked', (reason) => reject(new Error(`Player was kicked: ${reason}`)));
        bot.once('error', reject);
      });
      let since = 0;
      const said = (text) => playerLines.slice(since).some((line) => line.includes(text));
      /** Waits for `expect` in chat that arrived after this command, never earlier output. */
      const run = async (command, expect, timeoutMs = 10_000) => {
        since = playerLines.length;
        bot.chat(command);
        await waitFor(() => said(expect), `"${expect}" after ${command}`, timeoutMs);
      };

      // Everyone can use the read-only commands.
      await run('/nms help', 'NewMinecraftServers Companion 0.2.0');
      if (said('Server owners')) throw new Error('Non-operators must not see owner commands in help');
      await run('/nms claim NMS-7K3QPX9A', 'You do not have permission to use this command.');
      const guestCompletions = await bot.tabComplete('/nms ');
      if (guestCompletions.some(({ match }) => match === 'claim')) throw new Error('Non-operators must not be offered claim');
      await run('/nms lookup play.example.net', 'Example Network · 7d');
      await run('/nms lookup missing.example.net', 'That server is not listed on NewMinecraftServers.');

      // Operators get the owner commands.
      await runConsole('op SmokeOwner', 1_500);
      await run('/nms help', 'Server owners');
      const ownerCompletions = await bot.tabComplete('/nms ');
      if (!ownerCompletions.some(({ match }) => match === 'claim')) throw new Error('Operators must be offered claim');
      await run('/nms claim nms 7k3q px9a', 'Verified. You now manage Smoke Test');
      await run('/nms status', 'Smoke Test');
      await run(`/nms claim ${SLOW_CODE}`, 'Not visible at play.example.net yet');
      const during = await pingMotd(paperPort, version);
      if (!during.includes(SLOW_CODE)) throw new Error(`The claim code was not in the live MOTD: ${during}`);
      await run(`/nms link 127.0.0.1:${paperPort}`, 'Another verification is already running');
      await run('/nms claim cancel', 'Stopped. Your server list shows the normal MOTD again.');
      const restored = await pingMotd(paperPort, version);
      if (restored.includes(SLOW_CODE)) throw new Error(`The MOTD kept the code after cancel: ${restored}`);
      await run('/nms set website https://example.com', 'Listing edits moved to the website.');
      await run('/nms stats 7d', 'Full charts:');
      bot.quit();
      await delay(1_000);
    }

    await runConsole('stop', 0);
    const exitCode = await new Promise((resolveExit) => child.once('exit', resolveExit));
    await writeFile(log, `${outputText}\n\n--- player chat ---\n${playerLines.join('\n')}\n\n--- API calls ---\n${api.calls.join('\n')}\n`, 'utf8');
    if (exitCode !== 0) throw new Error(`Paper ${version} exited with ${exitCode}; see ${log}`);

    const required = [
      'NewMinecraftServers Companion enabled; API: http://127.0.0.1:',
      'NewMinecraftServersCompanion',
      'Server is not linked. An operator can run /nms link <public-address>.',
      'Listing edits moved to the website.',
      'Start a claim on the website, then run /nms claim <code> here.',
      'That is not a claim code. Codes look like NMS-7K3QPX9A.',
      'No claim uses that code.',
      'Example Network · 7d',
      'That server is not listed on NewMinecraftServers.',
      '90-day history needs a linked server.',
      'Unknown command. Try /nms help.',
      'Verified. You now manage Smoke Test on NewMinecraftServers.',
      'Paper now: ',
      'Linked to Smoke Test. 90-day stats are now available here.',
      '99.0% across 144 checks',
      'Unlinked. The public listing and its history stay online.',
      'configuration reloaded',
    ];
    for (const text of required) {
      if (!outputText.includes(text)) throw new Error(`Paper ${version} missing smoke evidence: ${text}; see ${log}`);
    }
    if (!api.calls.includes(`motd-visible:${VERIFY_CODE}:true`)) throw new Error(`The API never saw the claim code in the MOTD; see ${log}`);
    if (!api.calls.includes('link-visible:true')) throw new Error(`The API never saw the link challenge in the MOTD; see ${log}`);
    if (/Could not load ['"]plugins\/new-minecraft|Exception in plugin NewMinecraftServers|NoSuchMethodError|UnsupportedClassVersionError|Task #\d+ for NewMinecraftServers.*generated an exception/.test(outputText)) {
      throw new Error(`Paper ${version} reported a plugin load/runtime failure; see ${log}`);
    }
    return { version, log, player, playerMessages: playerLines.length };
  } catch (error) {
    await writeFile(log, `${outputText}\n\n--- API calls ---\n${api.calls.join('\n')}\n`, 'utf8').catch(() => undefined);
    if (child.exitCode === null) child.kill('SIGTERM');
    throw error;
  } finally {
    await api.close();
  }
};

const results = [];
for (const target of targets) results.push(await runTarget(target));
await writeFile(resolve(output, 'smoke-results.json'), JSON.stringify({ testedAt: new Date().toISOString(), results }, null, 2) + '\n');
for (const result of results) {
  console.log(`Paper ${result.version}: PASS${result.player ? ` (player session: ${result.playerMessages} chat lines)` : ' (console)'} — ${result.log}`);
}
