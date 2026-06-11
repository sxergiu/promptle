#!/usr/bin/env node
/**
 * Focused capture: reach the PROMPTING screen solo, type a prompt to ENABLE the Ready
 * button, and screenshot both the full screen and a close-up of the button so the
 * checkmark icon is clearly visible. No image generation involved — fast.
 *
 * Usage: node capture-ready.mjs   (frontend on :4200 + backend on :8088 must be running)
 */
import { chromium } from 'playwright';
import { mkdir } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const BASE_URL = process.env.BASE_URL ?? 'http://localhost:4200';
const OUTDIR = path.join(__dirname, 'screenshots');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const browser = await chromium.launch({ headless: process.env.HEADED !== '1' });
const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 } });
const page = await ctx.newPage();
try {
  await mkdir(OUTDIR, { recursive: true });
  await page.goto(BASE_URL, { waitUntil: 'networkidle' });
  await page.waitForSelector('app-player-setup .name-input');
  await page.fill('.name-input', 'Hostie');
  await page.click('.btn-play:not([disabled])');
  await page.waitForURL(/\/lobby\//, { timeout: 30000 });
  await page.waitForSelector('.btn-start:not([disabled])', { timeout: 15000 });
  await page.click('.btn-start:not([disabled])');

  // Wait for the prompting card, then type to enable the Ready button.
  await page.waitForSelector('app-prompting .prompt-textarea', { state: 'visible', timeout: 30000 });
  await page.fill('app-prompting .prompt-textarea', 'A cat astronaut planting a flag on the moon, vaporwave');
  await page.waitForSelector('app-prompting button.btn-cta:not([disabled])', { timeout: 5000 });
  await sleep(300);

  await page.screenshot({ path: path.join(OUTDIR, 'ready-button-full.png') });
  await page.locator('app-prompting button.btn-cta').screenshot({ path: path.join(OUTDIR, 'ready-button-closeup.png') });
  console.log('✅ saved ready-button-full.png and ready-button-closeup.png');
} catch (e) {
  console.error('❌', e.message);
  await page.screenshot({ path: path.join(OUTDIR, 'ready-button-ERROR.png') }).catch(() => {});
  process.exitCode = 1;
} finally {
  await browser.close();
}
