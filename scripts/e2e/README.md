# Promptle 2-player simulation

Drives a full 2-player Promptle game through the real UI with Playwright and
saves a screenshot of every screen the **host** sees. The second player is a bot,
driven just enough to advance each phase.

## What it captures

`screenshots/` (cleared each run), numbered in order:

1. `home` — player setup / create room
2. `lobby-host-alone` — host in lobby before guest joins
3. `lobby-two-players` — lobby with both players
4. `round-prompting` / `round-prompting-submitted`
5. `generating`
6. `round-guessing` / `round-guessing-submitted`
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
cd scripts/sim-2player
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
