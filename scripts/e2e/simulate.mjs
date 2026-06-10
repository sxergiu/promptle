#!/usr/bin/env node
/**
 * Promptle 2-player game simulation + screenshotter.
 *
 * Drives a full game through the real UI:
 *   - HOST: created and screenshotted at every screen (home, lobby, prompting,
 *           generating, guessing, results), in BOTH light and dark mode.
 *   - GUEST: a bot, driven just enough to satisfy "all players submitted".
 *
 * The script is phase-driven: it reacts to whatever phase the game is in
 * (PROMPTING / GENERATING / GUESSING / RESULTS) over the WebSocket, so it works
 * regardless of how many rounds a 2-player game produces.
 *
 * PREREQUISITES (this script does NOT start them):
 *   1. Backend on :8088 with the stub image provider (no ComfyUI needed):
 *        cd promptle && SPRING_APPLICATION_JSON='{"image":{"generation":{"provider":"stub"}}}' ./mvnw spring-boot:run
 *      (or set image.generation.provider=stub in application.properties)
 *      PostgreSQL must be reachable on :5432 as configured.
 *   2. Frontend on :4200:  cd promptle-app && npm install && npx ng serve
 *
 * USAGE:
 *   cd scripts/sim-2player
 *   npm run setup      # once: installs playwright + chromium
 *   npm run sim        # run the simulation
 *
 * ENV OVERRIDES:
 *   BASE_URL   frontend origin           (default http://localhost:4200)
 *   HEADED=1   show the browser windows  (default headless)
 *   SLOWMO     ms delay per action       (default 0; try 250 to watch)
 *   OUTDIR     screenshot directory      (default ./screenshots)
 */

import { chromium } from 'playwright';
import { mkdir, rm } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

const BASE_URL = process.env.BASE_URL ?? 'http://localhost:4200';
const HEADLESS = process.env.HEADED !== '1';
const SLOWMO = Number(process.env.SLOWMO ?? 0);
const OUTDIR = process.env.OUTDIR ?? path.join(__dirname, 'screenshots');

const HOST_NAME = 'Hostie';
const GUEST_NAME = 'Botley';

const PROMPTS = [
  'A cat astronaut planting a flag on the moon, vaporwave',
  'A medieval knight fighting a giant rubber duck',
  'A neon city skyline reflected in a coffee cup',
];
const GUESSES = [
  'A cat in space on the moon',
  'A knight versus a huge duck',
  'A glowing city inside a mug',
];

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ---------------------------------------------------------------------------
// Theme helper — ThemeService keys off the `data-theme` / `data-bs-theme`
// attributes on <html> (see core/services/theme.service.ts). Setting them
// directly flips the CSS theme without going through the app's signal/toggle,
// so we can re-screenshot the exact same screen in the other theme.
// ---------------------------------------------------------------------------
async function setTheme(page, theme) {
  await page
    .evaluate((t) => {
      document.documentElement.setAttribute('data-theme', t);
      document.documentElement.setAttribute('data-bs-theme', t);
      try { localStorage.setItem('theme', t); } catch {}
    }, theme)
    .catch(() => {});
}

// ---------------------------------------------------------------------------
// Screenshot helper — captures each screen in BOTH light and dark mode.
//
// Host doubles every shot: flip to light → snap, flip to dark → snap, restore
// to light. Doing it on the one page the host already drives gives identical
// framing in both themes, vs. mirroring screens on the bot guest (which never
// visits most of them).
// ---------------------------------------------------------------------------
let shotIndex = 0;
async function shot(page, label) {
  const idx = String(++shotIndex).padStart(2, '0');
  for (const theme of ['light', 'dark']) {
    await setTheme(page, theme);
    await sleep(150); // let theme color transitions settle
    await page.screenshot({ path: path.join(OUTDIR, `${idx}-${label}-${theme}.png`), fullPage: false });
  }
  await setTheme(page, 'light'); // restore so the app keeps running in its default theme
  console.log(`  📸 ${idx}-${label} (light + dark)`);
}

// ---------------------------------------------------------------------------
// Phase detection — inspect the host DOM to figure out where we are.
// ---------------------------------------------------------------------------
async function detectPhase(page) {
  if (/\/results(\b|$|\/)/.test(page.url())) return 'results';
  try {
    return await page.evaluate(() => {
      if (document.querySelector('.results-title')) return 'results';
      if (document.querySelector('app-generating, .generating-title, .pixel-canvas')) return 'generating';
      if (document.querySelector('app-prompting .prompt-textarea')) return 'prompting';
      if (document.querySelector('app-guessing .guess-input')) return 'guessing';
      if (document.querySelector('.btn-start, .lobby-content')) return 'lobby';
      if (document.querySelector('app-player-setup')) return 'home';
      return 'unknown';
    });
  } catch {
    // Page navigated mid-evaluate (phase transition) — let the caller retry.
    return 'unknown';
  }
}

async function waitForPhaseChange(page, from, timeoutMs = 120000) {
  const start = Date.now();
  let last = from;
  let lastLog = 0;
  while (Date.now() - start < timeoutMs) {
    const p = await detectPhase(page);
    if (p !== from && p !== 'unknown') return p;
    // Heartbeat during long waits (e.g. image generation) so it doesn't look frozen.
    if (Date.now() - lastLog > 15000) {
      console.log(`     …waiting (${from}, ${Math.round((Date.now() - start) / 1000)}s)`);
      lastLog = Date.now();
    }
    last = p;
    await sleep(400);
  }
  throw new Error(`Timed out waiting for phase to change from "${from}" (still "${last}")`);
}

// The Angular dev server injects a <vite-error-overlay> on compile errors (e.g. while
// the app source is being edited and HMR rebuilds). It covers the page and intercepts
// pointer events, breaking clicks. Wait it out before interacting.
async function waitForNoBuildError(page, timeout = 60000) {
  await page.waitForSelector('vite-error-overlay', { state: 'detached', timeout }).catch(() => {});
}

// ---------------------------------------------------------------------------
// Player setup screen (shared by home create + join)
// ---------------------------------------------------------------------------
async function fillPlayerSetupAndPlay(page, name) {
  await page.waitForSelector('app-player-setup .name-input', { timeout: 30000 });
  await waitForNoBuildError(page);
  await page.fill('.name-input', name);
  await page.click('.btn-play:not([disabled])');
}

// ---------------------------------------------------------------------------
// Phase actions
// ---------------------------------------------------------------------------
/**
 * Submit a phase form and confirm it registered.
 *
 * The game WebSocket connects a moment after entering the game route, and the app's
 * `wsConnected` flag is optimistically `true` from the start — so there's no reliable
 * "connected" signal to wait on. onSubmit() does a STOMP publish that THROWS while the
 * client is still connecting, silently dropping the submission (and leaving `submitted`
 * false). So we click, and if the "✓ Submitted — waiting for others" label doesn't
 * appear shortly, the send was dropped — re-fill and re-click until it sticks.
 */
async function isSubmitted(page, scope, fieldSel) {
  // submitted() drives both the readonly field and the "✓ Submitted" label.
  // NB: use count() (instant) before evaluate() — locator.evaluate() auto-waits up to
  // 30s for the element, which would block hard once the phase has moved on.
  if ((await page.locator(`${scope} .submitted-label`).count()) > 0) return true;
  const field = page.locator(`${scope} ${fieldSel}`);
  if ((await field.count()) === 0) return false;
  return field.first().evaluate((el) => el.readOnly).catch(() => false);
}

async function submitPhase(page, scope, fieldSel, text) {
  await page.waitForSelector(`${scope} ${fieldSel}`, { state: 'visible', timeout: 30000 });
  await waitForNoBuildError(page);
  for (let attempt = 1; attempt <= 25; attempt++) {
    if (await isSubmitted(page, scope, fieldSel)) {
      // Make sure the label (what we screenshot) has rendered.
      await page.waitForSelector(`${scope} .submitted-label`, { timeout: 5000 }).catch(() => {});
      return;
    }
    // If the field is gone, the phase already advanced (e.g. the server timer fired) —
    // stop trying; there's nothing to submit here anymore.
    if ((await page.locator(`${scope} ${fieldSel}`).count()) === 0) return;
    // Short per-attempt timeouts so a stuck/unexpected state fails fast across the 25
    // attempts instead of hanging ~30s (Playwright's default) on every retry.
    await page.fill(`${scope} ${fieldSel}`, text, { timeout: 3000 }).catch(() => {});
    await page.click(`${scope} button.btn-cta:not([disabled])`, { timeout: 3000 }).catch(() => {});
    await sleep(800); // let the STOMP publish + change detection settle, then re-check
  }
  throw new Error(`${scope}: submission never registered (WS never connected?)`);
}

async function submitPrompt(page, text) {
  await submitPhase(page, 'app-prompting', '.prompt-textarea', text);
}

async function submitGuess(page, text) {
  // Wait for the generated image to load before guessing (cosmetic, for the shot).
  await page.waitForSelector('app-guessing .guess-image', { timeout: 30000 }).catch(() => {});
  await submitPhase(page, 'app-guessing', '.guess-input', text);
}

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------
async function main() {
  await rm(OUTDIR, { recursive: true, force: true });
  await mkdir(OUTDIR, { recursive: true });
  console.log(`\nPromptle 2-player simulation`);
  console.log(`  base url : ${BASE_URL}`);
  console.log(`  output   : ${OUTDIR}`);
  console.log(`  headless : ${HEADLESS}\n`);

  const browser = await chromium.launch({ headless: HEADLESS, slowMo: SLOWMO });

  // Two isolated contexts = two independent players (separate localStorage).
  const hostCtx = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const guestCtx = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const host = await hostCtx.newPage();
  const guest = await guestCtx.newPage();

  try {
    // --- 1. HOST: home / create room -------------------------------------
    console.log('1. Host opens home and creates a room');
    await host.goto(BASE_URL, { waitUntil: 'networkidle' });
    await host.waitForSelector('app-player-setup .name-input');
    await shot(host, 'home');

    await fillPlayerSetupAndPlay(host, HOST_NAME);
    await host.waitForURL(/\/lobby\//, { timeout: 30000 });
    const roomCode = new URL(host.url()).pathname.split('/').pop();
    console.log(`   room code: ${roomCode}`);
    await host.waitForSelector('.lobby-content');
    // Wait for the host's own player card (populated from the room snapshot) to render,
    // otherwise the lobby screen is still blank.
    await host
      .waitForFunction((n) => (document.querySelector('.player-list')?.textContent || '').includes(n), HOST_NAME, { timeout: 15000 })
      .catch(() => {});
    await sleep(400); // settle render
    await shot(host, 'lobby-host-alone');

    // --- 2. GUEST: join the room -----------------------------------------
    console.log('2. Guest joins the room');
    await guest.goto(`${BASE_URL}/join/${roomCode}`, { waitUntil: 'networkidle' });
    await fillPlayerSetupAndPlay(guest, GUEST_NAME);
    await guest.waitForURL(/\/lobby\//, { timeout: 30000 });

    // Host lobby now shows 2 players — wait for the guest's card to appear on the host.
    await host
      .waitForFunction((n) => (document.querySelector('.player-list')?.textContent || '').includes(n), GUEST_NAME, { timeout: 15000 })
      .catch(() => {});
    await sleep(400); // settle the join event render
    await shot(host, 'lobby-two-players');

    // --- 3. HOST: start the game -----------------------------------------
    console.log('3. Host starts the game');
    await waitForNoBuildError(host);
    await host.click('.btn-start:not([disabled])');

    // --- 4. Phase loop ----------------------------------------------------
    let phase = await waitForPhaseChange(host, 'lobby');
    let promptIdx = 0;
    let guessIdx = 0;
    let guard = 0;

    while (phase !== 'results' && guard++ < 30) {
      console.log(`4.${guard} phase: ${phase}`);

      if (phase === 'prompting') {
        // Wait for the card to actually render/animate in before screenshotting.
        await host.waitForSelector('app-prompting .prompt-textarea', { state: 'visible', timeout: 30000 });
        await host.waitForSelector('app-prompting .phase-title', { state: 'visible', timeout: 30000 });
        await sleep(500); // settle fade-in transition
        await shot(host, `round-prompting`);
        const p = PROMPTS[promptIdx++ % PROMPTS.length];
        await submitPrompt(host, p); // host submits first, waits for its submitted state
        await shot(host, `round-prompting-submitted`);
        // Only now does the bot submit — strictly after the submitted screenshot.
        await submitPrompt(guest, PROMPTS[promptIdx++ % PROMPTS.length]).catch(() => {});
        phase = await waitForPhaseChange(host, 'prompting');
      } else if (phase === 'generating') {
        await shot(host, `generating`);
        // Real image generation can be slow; match the backend's 300s timeout.
        phase = await waitForPhaseChange(host, 'generating', 300000);
      } else if (phase === 'guessing') {
        // Wait for the card + generated image to render before screenshotting.
        await host.waitForSelector('app-guessing .guess-input', { state: 'visible', timeout: 30000 });
        await host.waitForSelector('app-guessing .guess-image', { state: 'visible', timeout: 30000 }).catch(() => {});
        await sleep(500); // settle fade-in transition
        await shot(host, `round-guessing`);
        const g = GUESSES[guessIdx++ % GUESSES.length];
        // Capture the in-between state: guess typed, READY enabled, not yet clicked.
        await host.fill('app-guessing .guess-input', g).catch(() => {});
        await sleep(200); // let the button enable (disabled until text is non-empty)
        await shot(host, `round-guessing-filled`);
        await submitGuess(host, g); // host submits first, waits for its submitted state
        await shot(host, `round-guessing-submitted`);
        // Only now does the bot submit — strictly after the submitted screenshot.
        await submitGuess(guest, GUESSES[guessIdx++ % GUESSES.length]).catch(() => {});
        phase = await waitForPhaseChange(host, 'guessing');
      } else {
        // unexpected lobby/home — bail
        await sleep(500);
        phase = await detectPhase(host);
      }
    }

    // --- 5. RESULTS -------------------------------------------------------
    console.log('5. Results');
    await host.waitForSelector('.results-content', { timeout: 30000 });

    // Entries within a chain reveal progressively (on a timer). A chain is fully
    // revealed exactly when NEXT becomes enabled (canProceed) or — on the final chain —
    // the LOBBY button appears (allChainsCompleted). Screenshot each chain only then.
    const chainFullyRevealed = () =>
      host.waitForFunction(() => {
        const btns = [...document.querySelectorAll('.results-actions button')];
        const lobby = btns.find((b) => /LOBBY/.test(b.textContent));
        const next = btns.find((b) => /NEXT/.test(b.textContent));
        return !!lobby || (next && !next.disabled);
      }, { timeout: 60000 }).catch(() => {});

    let chain = 1;
    while (chain <= 12) {
      await chainFullyRevealed();
      await sleep(500); // settle the last reveal + autoscroll
      await shot(host, `results-chain-${chain}`);

      // Final chain → LOBBY button is shown; we're done.
      if ((await host.locator('.results-actions button:has-text("LOBBY")').count()) > 0) break;

      const nextBtn = host.locator('.results-actions button:has-text("NEXT")');
      if ((await nextBtn.count()) === 0) break;
      await nextBtn.first().click();
      chain++;
      await sleep(1000); // let the next chain reset and begin revealing before we wait
    }

    console.log(`\n✅ Done. ${shotIndex} screenshots in ${OUTDIR}\n`);
  } catch (err) {
    console.error('\n❌ Simulation failed:', err.message);
    await shot(host, 'ERROR-host').catch(() => {});
    process.exitCode = 1;
  } finally {
    await browser.close();
  }
}

main();
