# Promptle 2-player simulation

Drives a full 2-player Promptle game through the real UI with Playwright and
saves a screenshot of every screen the **host** sees, at **three viewports**
(desktop, mobile portrait, mobile landscape) in **both light and dark mode**.
The second player is a bot, driven just enough to advance each phase.

## What it captures

`screenshots/` (cleared each run) holds one folder per flow —
`<viewport>-<theme>`, where `<viewport>` is one of `desktop` (1280×900),
`mobile-portrait` (390×844), `mobile-landscape` (844×390) and `<theme>` is
`light` or `dark`:

```
screenshots/
  desktop-light/          desktop-dark/
  mobile-portrait-light/  mobile-portrait-dark/
  mobile-landscape-light/ mobile-landscape-dark/
```

Each folder contains the same set of screens, numbered in order as
`<NN>-<label>.png` (the host resizes the viewport and flips the `data-theme`
attribute, re-snapping the same screen into each folder):

1. `home` — player setup / create room
2. `lobby-host-alone` — host in lobby before guest joins
3. `lobby-two-players` — lobby with both players
4. `round-prompting` / `round-prompting-submitted`
5. `generating`
6. `round-guessing` / `round-guessing-filled` (guess typed, READY not yet
   clicked) / `round-guessing-submitted`
7. `results-chain-1`, `results-chain-2`, … `results-final`

## Prerequisites (start these yourself first)

**Backend** on `:8088` using the **stub** image provider (no ComfyUI needed).
PostgreSQL must be reachable as configured in `application.properties`.

```bash
cd promptle
SPRING_APPLICATION_JSON='{"image":{"generation":{"provider":"stub"}}}' ./mvnw spring-boot:run
```

(or set `image.generation.provider=stub` in
`promptle/src/main/resources/application.properties`).

**Frontend** on `:4200`:

```bash
cd promptle-app
npm install
npx ng serve
```

## Run

```bash
cd scripts/e2e
npm run setup    # once — installs playwright + chromium
npm run sim
```

### Env overrides

| Var        | Default                  | Meaning                          |
| ---------- | ------------------------ | -------------------------------- |
| `BASE_URL` | `http://localhost:4200`  | Frontend origin                  |
| `HEADED=1` | (headless)               | Show the browser windows         |
| `SLOWMO`   | `0`                      | ms delay per action (try `250`)  |
| `OUTDIR`   | `./screenshots`          | Screenshot output directory      |

Example — watch it run:

```bash
HEADED=1 SLOWMO=250 npm run sim
```

---

## Demo GIF (the one embedded in the root `README.md`)

`demo-capture.mjs` + `build-gif.mjs` produce `../../assets/demo.gif` — a fast-paced,
labelled tour of the game's highlight features. Same prerequisites as the sim
(backend on `:8088`, frontend on `:4200`). With **ComfyUI** running the GIF shows
real AI images; with the **stub** provider it still works but every image is the
fixed placeholder.

```bash
npm run setup    # once — installs playwright + chromium
npm run demo     # capture, then build the GIF in one go
```

Or the two stages separately (handy when iterating on pacing/zoom — the capture
is the slow part, the build is seconds):

```bash
npm run demo:capture   # drives the UI, records demo-build/{L,G}.webm + markers.json
npm run demo:gif       # ffmpeg → ../../assets/demo.gif (re-run freely, no re-capture)
```

**How it works**

- Two capture sessions, because the lobby wants a crowd but the rest reads better
  small: session **L** = home (avatar shuffle) + a lobby filling to **7 players**;
  session **G** = a tight **2-player** game (prompting → generating → guessing →
  results), kept to 2 players so there are only ~4 real image generations before
  results (each ComfyUI image is slow).
- Each scene's neon label (the app's own *Press Start 2P* font) is injected into
  the page and recorded natively, so it's perfectly on-brand — no font wrangling.
- `markers.json` records each scene's `[start,end]` window; `build-gif.mjs` cuts,
  trims, speeds up, ken-burns-zooms the punchy scenes, concatenates, and
  palette-optimises to a GIF.

**Tuning the GIF** (env vars on `demo:gif`):

| Var         | Default | Meaning                                  |
| ----------- | ------- | ---------------------------------------- |
| `GIF_WIDTH` | `880`   | output width in px (height keeps aspect) |
| `GIF_FPS`   | `16`    | output frames per second                 |
| `GIF_OUT`   | `../../assets/demo.gif` | output path              |

The committed GIF was built with `GIF_WIDTH=760 GIF_FPS=14`. Per-scene pacing
(`maxDur` + `speed`) lives in the `TUNE` map at the top of `build-gif.mjs`.

> `demo-build/` (the raw `.webm` recordings + intermediates) is git-ignored; only
> the final `assets/demo.gif` is committed.
