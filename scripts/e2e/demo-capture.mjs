#!/usr/bin/env node
/**
 * Promptle demo-GIF capturer.
 *
 * Drives the REAL UI through its highlight features and records the host page to
 * video, baking an on-brand neon scene label (the app's own Press Start 2P font)
 * into each segment. Emits a markers.json that build-gif.mjs turns into the GIF.
 *
 * Two sessions, because the lobby wants a crowd but the rest of the game reads
 * better small:
 *   - Session "L" (home + lobby): avatar shuffle, then 7 players gathering.
 *   - Session "G" (gameplay): a tight 2-player game — prompting → generating →
 *     guessing → results.
 *
 * Each session records ONE continuous host video; per-scene [start,end] windows
 * (seconds from that video's start) are written to markers.json so build-gif.mjs
 * can cut, label-already-baked, zoom, and stitch them.
 *
 * PREREQUISITES (this script does NOT start them) — same as simulate.mjs:
 *   1. Backend on :8088 with the stub image provider.
 *   2. Frontend on :4200.
 *
 * USAGE:
 *   cd scripts/e2e
 *   npm run setup          # once: installs playwright + chromium
 *   node demo-capture.mjs  # writes demo-build/{L,G}.webm + markers.json
 *   node build-gif.mjs     # turns those into ../../assets/demo.gif
 *
 * ENV OVERRIDES:
 *   BASE_URL   frontend origin   (default http://localhost:4200)
 *   HEADED=1   show the browser  (default headless)
 *   OUTDIR     build dir         (default ./demo-build)
 */

import { chromium } from 'playwright';
import { mkdir, rm, writeFile, rename } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

const BASE_URL = process.env.BASE_URL ?? 'http://localhost:4200';
const HEADLESS = process.env.HEADED !== '1';
const OUTDIR = process.env.OUTDIR ?? path.join(__dirname, 'demo-build');

// 16:10 — comfortable for the TV-frame layout, downscaled later for the GIF.
const VIEWPORT = { width: 1200, height: 760 };

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const PROMPTS = [
  'A cat astronaut planting a flag on the moon, vaporwave',
  'A medieval knight duelling a giant rubber duck',
  'A neon city skyline reflected in a coffee cup',
];
const GUESSES = [
  'Spacecat conquers the moon',
  'Knight fights a huge bath duck',
  'A glowing city inside a mug',
];

// ---------------------------------------------------------------------------
// Theme — force dark for the flashy neon look (mirrors simulate.mjs).
// ---------------------------------------------------------------------------
async function setDark(page) {
  await page
    .evaluate(() => {
      document.documentElement.setAttribute('data-theme', 'dark');
      document.documentElement.setAttribute('data-bs-theme', 'dark');
      try { localStorage.setItem('theme', 'dark'); } catch {}
    })
    .catch(() => {});
}

// ---------------------------------------------------------------------------
// Scene label overlay — injected into the page so it's recorded natively in the
// app's own Press Start 2P pixel font with a crimson/sage neon glow matching the
// brand (--p-red #cc3358 / --p-green #628266). pointer-events:none so it never
// eats a click. Fades in/out for a polished feel.
// ---------------------------------------------------------------------------
async function installLabelOverlay(page) {
  await page.addStyleTag({
    content: `
      #demo-label {
        position: fixed; left: 50%; bottom: 5%; transform: translateX(-50%) translateY(12px);
        z-index: 2147483647; pointer-events: none;
        font-family: 'Press Start 2P', monospace;
        font-size: 22px; line-height: 1.4; letter-spacing: 1px; white-space: nowrap;
        color: #fff2f6;
        padding: 16px 26px; border-radius: 10px;
        background: rgba(18, 12, 16, 0.62);
        border: 2px solid rgba(204, 51, 88, 0.85);
        text-shadow: 0 0 6px #cc3358, 0 0 14px #cc3358, 0 0 22px rgba(204,51,88,0.7);
        box-shadow: 0 0 10px rgba(204,51,88,0.55), 0 0 26px rgba(204,51,88,0.35), inset 0 0 12px rgba(204,51,88,0.25);
        opacity: 0; transition: opacity 280ms ease, transform 280ms ease;
      }
      #demo-label.show { opacity: 1; transform: translateX(-50%) translateY(0); }
    `,
  });
  await page.evaluate(() => {
    const el = document.createElement('div');
    el.id = 'demo-label';
    document.body.appendChild(el);
  });
}

async function showLabel(page, text) {
  await page.evaluate((t) => {
    const el = document.getElementById('demo-label');
    if (!el) return;
    el.textContent = t;
    el.classList.add('show');
  }, text);
}

async function hideLabel(page) {
  await page.evaluate(() => document.getElementById('demo-label')?.classList.remove('show'));
}

// ---------------------------------------------------------------------------
// Scene timing — record [start,end] windows (seconds) against each session's
// video clock. t0 is captured right after the host page is created, which is
// when Playwright begins the video, so offsets line up.
// ---------------------------------------------------------------------------
function makeRecorder() {
  let t0 = 0;
  const scenes = [];
  return {
    start: () => { t0 = Date.now(); },
    now: () => (Date.now() - t0) / 1000,
    begin(label, zoom = null) {
      const scene = { label, zoom, start: (Date.now() - t0) / 1000, end: null };
      scenes.push(scene);
      return scene;
    },
    end(scene) { scene.end = (Date.now() - t0) / 1000; },
    scenes,
  };
}

// ---------------------------------------------------------------------------
// Shared driving helpers (trimmed from simulate.mjs).
// ---------------------------------------------------------------------------
async function detectPhase(page) {
  if (/\/results(\b|$|\/)/.test(page.url())) return 'results';
  try {
    return await page.evaluate(() => {
      if (document.querySelector('.results-content, .results-title')) return 'results';
      if (document.querySelector('app-generating, .generating-title, .pixel-canvas')) return 'generating';
      if (document.querySelector('app-prompting .prompt-textarea')) return 'prompting';
      if (document.querySelector('app-guessing .guess-input')) return 'guessing';
      if (document.querySelector('.btn-start, .lobby-content')) return 'lobby';
      if (document.querySelector('app-player-setup')) return 'home';
      return 'unknown';
    });
  } catch { return 'unknown'; }
}

async function waitForPhaseChange(page, from, timeoutMs = 120000) {
  const start = Date.now();
  let last = from;
  while (Date.now() - start < timeoutMs) {
    const p = await detectPhase(page);
    if (p !== from && p !== 'unknown') return p;
    last = p;
    await sleep(300);
  }
  throw new Error(`Timed out waiting for phase to change from "${from}" (still "${last}")`);
}

async function fillPlayerSetupAndPlay(page, name) {
  await page.waitForSelector('app-player-setup .name-input', { timeout: 30000 });
  await page.fill('.name-input', name);
  await page.click('.btn-play:not([disabled])');
}

async function isSubmitted(page, scope, fieldSel) {
  if ((await page.locator(`${scope} .submitted-label`).count()) > 0) return true;
  const field = page.locator(`${scope} ${fieldSel}`);
  if ((await field.count()) === 0) return false;
  return field.first().evaluate((el) => el.readOnly).catch(() => false);
}

async function submitPhase(page, scope, fieldSel, text, { type = false } = {}) {
  await page.waitForSelector(`${scope} ${fieldSel}`, { state: 'visible', timeout: 30000 });
  for (let attempt = 1; attempt <= 25; attempt++) {
    if (await isSubmitted(page, scope, fieldSel)) {
      await page.waitForSelector(`${scope} .submitted-label`, { timeout: 5000 }).catch(() => {});
      return;
    }
    if ((await page.locator(`${scope} ${fieldSel}`).count()) === 0) return;
    // First attempt can "type" for a human-paced effect (used for the captured scene).
    if (type && attempt === 1) {
      await page.fill(`${scope} ${fieldSel}`, '', { timeout: 3000 }).catch(() => {});
      await page.type(`${scope} ${fieldSel}`, text, { delay: 38, timeout: 15000 }).catch(() => {});
    } else {
      await page.fill(`${scope} ${fieldSel}`, text, { timeout: 3000 }).catch(() => {});
    }
    await page.click(`${scope} button.btn-cta:not([disabled])`, { timeout: 3000 }).catch(() => {});
    await sleep(700);
  }
  throw new Error(`${scope}: submission never registered (WS never connected?)`);
}

// ===========================================================================
// SESSION L — home (avatar shuffle) + lobby (7 players gathering)
// ===========================================================================
async function captureLobbySession(browser, rec) {
  console.log('\n[Session L] home + 7-player lobby');
  const hostCtx = await browser.newContext({
    viewport: VIEWPORT,
    recordVideo: { dir: OUTDIR, size: VIEWPORT },
  });
  const host = await hostCtx.newPage();
  rec.start();

  await host.goto(BASE_URL, { waitUntil: 'networkidle' });
  await setDark(host);
  await host.waitForSelector('app-player-setup .name-input');
  await installLabelOverlay(host);
  await sleep(600);

  // --- Scene: avatar shuffle ---
  console.log('  · avatar shuffle');
  await showLabel(host, 'Pick your avatar');
  const sShuffle = rec.begin('Pick your avatar', { mode: 'in', cx: 0.5, cy: 0.46, zoom: 1.18 });
  await sleep(400);
  for (let i = 0; i < 6; i++) {
    await host.click('.shuffle-badge').catch(() => {});
    await sleep(520);
  }
  await sleep(300);
  rec.end(sShuffle);
  await hideLabel(host);
  await sleep(350);

  // --- Enter lobby ---
  await fillPlayerSetupAndPlay(host, 'Pixl');
  await host.waitForURL(/\/lobby\//, { timeout: 30000 });
  const roomCode = new URL(host.url()).pathname.split('/').pop();
  console.log(`  · room ${roomCode}`);
  await host.waitForSelector('.lobby-content');
  await host
    .waitForFunction(() => (document.querySelector('.player-list')?.textContent || '').includes('Pixl'), null, { timeout: 15000 })
    .catch(() => {});
  await installLabelOverlay(host); // route changed → re-inject
  await sleep(500);

  // --- Scene: lobby filling up to 7 players ---
  console.log('  · guests joining');
  await showLabel(host, 'Gather up to 8 players');
  const sLobby = rec.begin('Gather up to 8 players');
  await sleep(500);

  const guestNames = ['Ava', 'Bo', 'Cyrus', 'Dot', 'Edie', 'Fox'];
  for (const name of guestNames) {
    const gctx = await browser.newContext({ viewport: VIEWPORT });
    const g = await gctx.newPage();
    await g.goto(`${BASE_URL}/join/${roomCode}`, { waitUntil: 'domcontentloaded' }).catch(() => {});
    await fillPlayerSetupAndPlay(g, name).catch(() => {});
    // Wait until this guest's card shows up on the host, so each pop-in is on camera.
    await host
      .waitForFunction((n) => (document.querySelector('.player-list')?.textContent || '').includes(n), name, { timeout: 15000 })
      .catch(() => {});
    await sleep(450); // let the card animate in and breathe
  }
  await sleep(900);
  rec.end(sLobby);
  await hideLabel(host);
  await sleep(400);

  await hostCtx.close(); // finalizes the host video
  const videoPath = await host.video().path();
  const dest = path.join(OUTDIR, 'L.webm');
  await rename(videoPath, dest).catch(async () => { await rm(dest, { force: true }); await rename(videoPath, dest); });
  console.log(`  ✓ ${dest}`);
  return { video: 'L.webm', scenes: rec.scenes };
}

// ===========================================================================
// SESSION G — a tight 2-player game: prompting → generating → guessing → results
// ===========================================================================
async function captureGameSession(browser, rec) {
  console.log('\n[Session G] 3-player gameplay');
  const hostCtx = await browser.newContext({
    viewport: VIEWPORT,
    recordVideo: { dir: OUTDIR, size: VIEWPORT },
  });
  const host = await hostCtx.newPage();
  rec.start();

  await host.goto(BASE_URL, { waitUntil: 'networkidle' });
  await setDark(host);
  await host.waitForSelector('app-player-setup .name-input');
  await installLabelOverlay(host);

  await fillPlayerSetupAndPlay(host, 'Pixl');
  await host.waitForURL(/\/lobby\//, { timeout: 30000 });
  const roomCode = new URL(host.url()).pathname.split('/').pop();
  console.log(`  · room ${roomCode}`);
  await host.waitForSelector('.lobby-content');

  // One bot guest → a 2-player game: just 2 rounds, so only ~4 real image
  // generations before results (each ComfyUI image is ~slow, so keep it short).
  const guests = [];
  for (const name of ['Bo']) {
    const gctx = await browser.newContext({ viewport: VIEWPORT });
    const g = await gctx.newPage();
    await g.goto(`${BASE_URL}/join/${roomCode}`, { waitUntil: 'domcontentloaded' });
    await fillPlayerSetupAndPlay(g, name);
    await g.waitForURL(/\/lobby\//, { timeout: 30000 }).catch(() => {});
    guests.push(g);
  }
  await host
    .waitForFunction(() => (document.querySelectorAll('.player-list app-player-card').length >= 2), null, { timeout: 15000 })
    .catch(() => {});
  await sleep(600);

  await host.click('.btn-start:not([disabled])');

  // Drive the game inside try/finally so a slow/stalled image generation never
  // costs us the footage: whatever scenes were captured are kept and the video
  // is always finalized.
  try {
  // Only the FIRST occurrence of each phase becomes a labelled scene; later rounds
  // are driven silently just to reach results.
  let phase = await waitForPhaseChange(host, 'lobby');
  let promptIdx = 0, guessIdx = 0, guard = 0;
  let didPrompt = false, didGenerating = false, didGuess = false;

  while (phase !== 'results' && guard++ < 30) {
    console.log(`  · phase: ${phase}`);

    if (phase === 'prompting') {
      await host.waitForSelector('app-prompting .prompt-textarea', { state: 'visible', timeout: 30000 });
      await installLabelOverlay(host).catch(() => {});
      let scene = null;
      if (!didPrompt) {
        await sleep(500);
        await showLabel(host, 'Write a prompt');
        scene = rec.begin('Write a prompt');
        await sleep(300);
      }
      await submitPhase(host, 'app-prompting', '.prompt-textarea', PROMPTS[promptIdx++ % PROMPTS.length], { type: !didPrompt });
      if (scene) {
        await sleep(700); // hold on the "✓ submitted" state
        rec.end(scene);
        await hideLabel(host);
        didPrompt = true;
      }
      for (const g of guests) {
        await submitPhase(g, 'app-prompting', '.prompt-textarea', PROMPTS[promptIdx++ % PROMPTS.length]).catch(() => {});
      }
      phase = await waitForPhaseChange(host, 'prompting');
    } else if (phase === 'generating') {
      await installLabelOverlay(host).catch(() => {});
      let scene = null;
      if (!didGenerating) {
        await showLabel(host, 'AI generates an image');
        scene = rec.begin('AI generates an image');
      }
      // The stub provider is fast; give the generating animation a moment regardless.
      const before = Date.now();
      phase = await waitForPhaseChange(host, 'generating', 300000);
      if (scene) {
        // Ensure at least ~2.2s of the generating animation is on camera.
        const elapsed = Date.now() - before;
        if (elapsed < 2200) await sleep(2200 - elapsed);
        rec.end(scene);
        await hideLabel(host);
        didGenerating = true;
      }
    } else if (phase === 'guessing') {
      await host.waitForSelector('app-guessing .guess-input', { state: 'visible', timeout: 30000 });
      await host.waitForSelector('app-guessing .guess-image', { state: 'visible', timeout: 30000 }).catch(() => {});
      await installLabelOverlay(host).catch(() => {});
      let scene = null;
      if (!didGuess) {
        await sleep(600);
        await showLabel(host, 'Guess the prompt');
        scene = rec.begin('Guess the prompt', { mode: 'in', cx: 0.5, cy: 0.4, zoom: 1.12 });
        await sleep(300);
      }
      await submitPhase(host, 'app-guessing', '.guess-input', GUESSES[guessIdx++ % GUESSES.length], { type: !didGuess });
      if (scene) {
        await sleep(700);
        rec.end(scene);
        await hideLabel(host);
        didGuess = true;
      }
      for (const g of guests) {
        await submitPhase(g, 'app-guessing', '.guess-input', GUESSES[guessIdx++ % GUESSES.length]).catch(() => {});
      }
      phase = await waitForPhaseChange(host, 'guessing');
    } else {
      await sleep(400);
      phase = await detectPhase(host);
    }
  }

  // --- Scene: results / chain reveal ---
  console.log('  · results');
  await host.waitForSelector('.results-content', { timeout: 30000 });
  await installLabelOverlay(host).catch(() => {});
  await sleep(400);
  await showLabel(host, 'The chain, revealed');
  const sResults = rec.begin('The chain, revealed');
  // Let the first chain reveal progressively (entries pop in on a timer), then
  // give it a beat. We only need one chain's reveal for the demo.
  await host
    .waitForFunction(() => {
      const btns = [...document.querySelectorAll('.results-actions button')];
      const lobby = btns.find((b) => /LOBBY/.test(b.textContent));
      const next = btns.find((b) => /NEXT/.test(b.textContent));
      return !!lobby || (next && !next.disabled);
    }, null, { timeout: 60000 })
    .catch(() => {});
  await sleep(1800);
  rec.end(sResults);
  await hideLabel(host);
  await sleep(400);
  } catch (err) {
    console.error(`  ⚠ game drive stopped early: ${err.message} — keeping captured scenes`);
  } finally {
    for (const g of guests) await g.context().close().catch(() => {});
    await hostCtx.close();
  }

  const videoPath = await host.video().path();
  const dest = path.join(OUTDIR, 'G.webm');
  await rename(videoPath, dest).catch(async () => { await rm(dest, { force: true }); await rename(videoPath, dest); });
  console.log(`  ✓ ${dest}`);
  return { video: 'G.webm', scenes: rec.scenes };
}

// ===========================================================================
async function main() {
  await rm(OUTDIR, { recursive: true, force: true });
  await mkdir(OUTDIR, { recursive: true });

  console.log(`\nPromptle demo capture`);
  console.log(`  base url : ${BASE_URL}`);
  console.log(`  output   : ${OUTDIR}`);
  console.log(`  headless : ${HEADLESS}`);

  const browser = await chromium.launch({ headless: HEADLESS });
  try {
    const lobby = await captureLobbySession(browser, makeRecorder());
    const game = await captureGameSession(browser, makeRecorder());

    // Ordered list of clips the GIF is stitched from.
    const markers = {
      viewport: VIEWPORT,
      sessions: [lobby, game],
      // The final GIF order: shuffle, lobby, prompting, generating, guessing, results.
      order: [
        { video: 'L.webm', label: 'Pick your avatar' },
        { video: 'L.webm', label: 'Gather up to 8 players' },
        { video: 'G.webm', label: 'Write a prompt' },
        { video: 'G.webm', label: 'AI generates an image' },
        { video: 'G.webm', label: 'Guess the prompt' },
        { video: 'G.webm', label: 'The chain, revealed' },
      ],
    };
    await writeFile(path.join(OUTDIR, 'markers.json'), JSON.stringify(markers, null, 2));
    console.log(`\n✅ Capture done. Markers → ${path.join(OUTDIR, 'markers.json')}`);
    console.log(`   Next: node build-gif.mjs\n`);
  } catch (err) {
    console.error('\n❌ Capture failed:', err.message);
    process.exitCode = 1;
  } finally {
    await browser.close();
  }
}

main();
