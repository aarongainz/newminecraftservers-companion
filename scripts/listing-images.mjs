/*
 * Renders the plugin-site listing images from artwork/cover.html and artwork/gallery.html with
 * headless Chrome: a 2048×1024 cover and four 1920×1080 gallery scenes, into artwork/.
 * Refresh artwork/site-home.png first if the homepage changed.
 */
import { spawnSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const artwork = resolve(dirname(fileURLToPath(import.meta.url)), '../artwork');
const chrome = process.env.CHROME_PATH ?? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
if (!existsSync(chrome)) throw new Error(`Chrome not found at ${chrome}; set CHROME_PATH`);

const render = (page, hash, width, height, output) => {
  const url = `${pathToFileURL(resolve(artwork, page)).href}${hash ? `#${hash}` : ''}`;
  const result = spawnSync(chrome, [
    '--headless=new', '--disable-gpu', '--hide-scrollbars', '--allow-file-access-from-files',
    '--force-device-scale-factor=2', `--window-size=${width},${height}`, '--virtual-time-budget=2000',
    `--screenshot=${resolve(artwork, output)}`, url,
  ], { stdio: 'ignore', timeout: 60_000 });
  if (result.status !== 0) throw new Error(`Chrome failed rendering ${output}`);
  console.log(resolve(artwork, output));
};

render('cover.html', '', 1024, 512, 'cover-2048x1024.png');
for (const scene of ['claim', 'help', 'stats', 'site']) render('gallery.html', scene, 960, 540, `gallery-${scene}.png`);
