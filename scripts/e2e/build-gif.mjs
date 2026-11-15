#!/usr/bin/env node
/**
 * Turns the clips captured by demo-capture.mjs into the README demo GIF.
 *
 * Reads demo-build/markers.json, and for each scene in `order`:
 *   - cuts its [start,end] window out of the session's .webm (accurate seek),
 *   - applies a subtle ken-burns zoom on scenes that asked for one,
 *   - normalises fps + resolution,
 * then concatenates the scenes and palette-optimises the result to a GIF.
 *
 * The scene labels are ALREADY baked into the video by the capturer (neon
 * Press Start 2P overlay), so this step is pure ffmpeg — no text rendering here.
 *
 * USAGE:  node build-gif.mjs
 *
 * ENV OVERRIDES:
 *   OUTDIR     build dir          (default ./demo-build)
 *   GIF_OUT    output gif path    (default ../../assets/demo.gif)
 *   GIF_WIDTH  output width px    (default 880)
 *   GIF_FPS    output frames/sec  (default 16)
 *   PAD        seconds trimmed off each end of a scene to hide label fades (default 0)
 */

import { readFile, writeFile, rm, mkdir } from 'node:fs/promises';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const exec = promisify(execFile);
const __dirname = path.dirname(fileURLToPath(import.meta.url));

const OUTDIR = process.env.OUTDIR ?? path.join(__dirname, 'demo-build');
const GIF_OUT = process.env.GIF_OUT ?? path.join(__dirname, '..', '..', 'assets', 'demo.gif');
const GIF_WIDTH = Number(process.env.GIF_WIDTH ?? 880);
const GIF_FPS = Number(process.env.GIF_FPS ?? 16);
const PAD = Number(process.env.PAD ?? 0);

const SEG_DIR = path.join(OUTDIR, 'segments');

async function ff(args) {
  // -y overwrite, -loglevel error to keep noise down (warnings still shown).
  await exec('ffmpeg', ['-y', '-loglevel', 'error', ...args], { maxBuffer: 1 << 26 });
}

function findScene(session, label) {
  // Returns null (not throws) for an uncaptured scene, so a partial capture still
  // builds a GIF from whatever scenes it did get.
  return session.scenes.find((x) => x.label === label && x.end != null && x.end > x.start) ?? null;
}

async function main() {
  const markers = JSON.parse(await readFile(path.join(OUTDIR, 'markers.json'), 'utf8'));
  const { width: vw, height: vh } = markers.viewport;
  const W = GIF_WIDTH % 2 ? GIF_WIDTH + 1 : GIF_WIDTH;
  const H = Math.round((W * vh) / vw / 2) * 2; // keep aspect, force even

  await rm(SEG_DIR, { recursive: true, force: true });
  await mkdir(SEG_DIR, { recursive: true });
  await mkdir(path.dirname(GIF_OUT), { recursive: true });

  const sessionByVideo = Object.fromEntries(markers.sessions.map((s) => [s.video, s]));
  const segFiles = [];

  // Per-scene pacing. Captured windows are raw (a real generation can be ~3min);
  // here we keep the GIF flashy and fast: take at most `maxDur` seconds (from the
  // scene start) and play it at `speed`× so the whole tour stays short.
  const TUNE = {
    'Pick your avatar':       { maxDur: 3.2, speed: 1.0 },
    'Gather up to 8 players': { maxDur: 15.0, speed: 2.6 },
    'Write a prompt':         { maxDur: 6.5, speed: 1.5 },
    'AI generates an image':  { maxDur: 2.6, speed: 1.0 },
    'Guess the prompt':       { maxDur: 6.5, speed: 1.4 },
    'The chain, revealed':    { maxDur: 11.0, speed: 1.4 },
  };
  const DEFAULT_TUNE = { maxDur: 6, speed: 1.0 };

  console.log(`\nBuilding GIF  ${W}x${H} @ ${GIF_FPS}fps  →  ${GIF_OUT}\n`);

  for (let i = 0; i < markers.order.length; i++) {
    const { video, label } = markers.order[i];
    const session = sessionByVideo[video];
    const scene = findScene(session, label);
    if (!scene) {
      console.log(`  [${i + 1}/${markers.order.length}] ${label}  — SKIPPED (not captured)`);
      continue;
    }
    const tune = TUNE[label] ?? DEFAULT_TUNE;
    const start = Math.max(0, scene.start + PAD);
    const avail = Math.max(0.3, scene.end - PAD - start);
    const dur = Math.min(avail, tune.maxDur); // source seconds we keep
    const end = start + dur;
    const speed = tune.speed;
    const outDur = dur / speed;                // playback seconds after speed-up
    const src = path.join(OUTDIR, video);
    const out = path.join(SEG_DIR, `seg${String(i).padStart(2, '0')}.mp4`);

    // setpts speeds the clip up; everything else normalises fps/size.
    const speedF = speed !== 1 ? `setpts=PTS/${speed},` : '';
    let vf;
    if (scene.zoom && scene.zoom.mode === 'in') {
      // Ken-burns zoom-in to (cx,cy). Increment per OUTPUT frame so the zoom speed
      // is stable regardless of clip length.
      const Z = scene.zoom.zoom ?? 1.15;
      const cx = scene.zoom.cx ?? 0.5;
      const cy = scene.zoom.cy ?? 0.5;
      const frames = Math.max(1, Math.round(outDur * GIF_FPS));
      const inc = (Z - 1) / frames;
      vf =
        `${speedF}fps=${GIF_FPS},scale=${W}:${H},` +
        `zoompan=z='min(zoom+${inc.toFixed(6)},${Z})':d=1:` +
        `x='(iw-iw/zoom)*${cx}':y='(ih-ih/zoom)*${cy}':s=${W}x${H}:fps=${GIF_FPS},` +
        `setsar=1`;
    } else {
      vf = `${speedF}fps=${GIF_FPS},scale=${W}:${H},setsar=1`;
    }

    console.log(`  [${i + 1}/${markers.order.length}] ${label}  (${dur.toFixed(1)}s → ${outDur.toFixed(1)}s @ ${speed}×${scene.zoom ? ', zoom' : ''})`);
    // Input seeking (-ss/-t BEFORE -i) cuts the source to exactly `dur` seconds
    // before filtering, so setpts speed-up isn't fighting an output -to limit.
    await ff([
      '-ss', start.toFixed(3),
      '-t', dur.toFixed(3),
      '-i', src,
      '-an',
      '-vf', vf,
      '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-crf', '18', '-preset', 'veryfast',
      out,
    ]);
    segFiles.push(out);
  }

  // Concat the normalised segments.
  const listFile = path.join(SEG_DIR, 'list.txt');
  await writeFile(listFile, segFiles.map((f) => `file '${f}'`).join('\n'));
  const combined = path.join(SEG_DIR, 'combined.mp4');
  await ff(['-f', 'concat', '-safe', '0', '-i', listFile, '-c', 'copy', combined]);

  // Two-pass palette for a clean, small GIF.
  const palette = path.join(SEG_DIR, 'palette.png');
  await ff(['-i', combined, '-vf', `fps=${GIF_FPS},palettegen=stats_mode=diff`, palette]);
  await ff([
    '-i', combined, '-i', palette,
    '-lavfi', `fps=${GIF_FPS}[x];[x][1:v]paletteuse=dither=bayer:bayer_scale=3:diff_mode=rectangle`,
    GIF_OUT,
  ]);

  const { stdout } = await exec('du', ['-h', GIF_OUT]);
  console.log(`\n✅ ${GIF_OUT}  (${stdout.trim().split('\t')[0]})\n`);
}

main().catch((err) => {
  console.error('\n❌ build-gif failed:', err.stderr || err.message);
  process.exitCode = 1;
});
