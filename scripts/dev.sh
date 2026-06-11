#!/bin/bash
set -uo pipefail

# Promptle Dev Console
# One interactive place to see and run all app-related tasks:
# run backend, run frontend, run both, test, simulate, deploy, export, clear db, status.
#
# Usage:
#   ./scripts/dev.sh            # open the interactive menu
#   ./scripts/dev.sh play       # one command: Postgres + stub images + backend + frontend
#   ./scripts/dev.sh backend    # jump straight to a task
#                               # (play|backend|frontend|both|test|test-backend|test-frontend|test-all|sim|deploy|export|gen-assets|preview-gif|clear-db|status)

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
BACKEND_DIR="$REPO_DIR/promptle"
FRONTEND_DIR="$REPO_DIR/promptle-app"
SIM_DIR="$SCRIPT_DIR/e2e"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
BOLD='\033[1m'
NC='\033[0m'

info()  { echo -e "${GREEN}[+]${NC} $1"; }
warn()  { echo -e "${YELLOW}[!]${NC} $1"; }
error() { echo -e "${RED}[x]${NC} $1"; }

# --- Service config ---------------------------------------------------------
PG_HOST="localhost"; PG_PORT=5432; PG_USER="postgres"; PG_PASS="postgres"; PG_DB="postgres"
BACKEND_PORT=8088
FRONTEND_PORT=4200
COMFYUI_PORT=8188

# --- Port / health helpers --------------------------------------------------
port_open() {
    # $1 = host, $2 = port  -> returns 0 if a TCP connection succeeds
    (exec 3<>"/dev/tcp/$1/$2") 2>/dev/null && exec 3>&- 3<&- && return 0
    return 1
}

http_ok() {
    # $1 = url -> returns 0 on any HTTP response (even 4xx), 1 if unreachable
    curl -s --max-time 3 -o /dev/null "$1" 2>/dev/null
}

# --- Java selection ---------------------------------------------------------
# The backend targets Java 21. A pinned JAVA_HOME (e.g. jenv) or a newer default
# JDK on PATH would otherwise break `./mvnw` with "release version 21 not supported".
# Resolve a JDK 21 home and export it for the backend subshells; warn (don't fail)
# if none is found so the user can still try with whatever is current.
BACKEND_JAVA_HOME=""
resolve_backend_java() {
    [[ -n "$BACKEND_JAVA_HOME" ]] && return 0   # already resolved this session

    # 1) Current JAVA_HOME already on 21?
    if [[ -n "${JAVA_HOME:-}" ]] && "$JAVA_HOME/bin/java" -version 2>&1 | grep -q 'version "21'; then
        BACKEND_JAVA_HOME="$JAVA_HOME"; return 0
    fi
    # 2) macOS java_home helper.
    if [[ -x /usr/libexec/java_home ]]; then
        local jh
        jh=$(/usr/libexec/java_home -v 21 2>/dev/null) && [[ -n "$jh" ]] && { BACKEND_JAVA_HOME="$jh"; return 0; }
    fi
    # 3) Plain `java` on PATH already on 21?
    if command -v java >/dev/null 2>&1 && java -version 2>&1 | grep -q 'version "21'; then
        BACKEND_JAVA_HOME=""; return 0   # default java is fine — no override needed
    fi
    return 1
}

# Echo a `JAVA_HOME=… ` prefix (or nothing) and warn if JDK 21 couldn't be found.
backend_java_env() {
    if resolve_backend_java; then
        [[ -n "$BACKEND_JAVA_HOME" ]] && info "Using JDK 21 at $BACKEND_JAVA_HOME" >&2
    else
        warn "Could not find a JDK 21 (the backend needs it). Trying with the current Java — this may fail to compile." >&2
    fi
}

# --- Pre-flight checks ------------------------------------------------------
check_postgres() {
    if port_open "$PG_HOST" "$PG_PORT"; then
        return 0
    fi
    warn "Postgres is not reachable on :$PG_PORT — the backend needs it."
    warn "Start it (e.g. 'docker compose up -d postgres') before running the backend."
    return 1
}

# Bring Postgres up for host-run dev (the one-command 'play' path) and wait for it.
# Uses a standalone `docker run` that publishes :5432 to the host — NOT the
# docker-compose 'postgres' service, which is deliberately not exposed to the host
# (there the backend runs inside the same Docker network). No volume = no persistence,
# which matches the game's ephemeral state.
PG_CONTAINER="promptle-dev-postgres"
ensure_postgres() {
    if port_open "$PG_HOST" "$PG_PORT"; then
        info "Postgres already up on :$PG_PORT."
        return 0
    fi
    if ! command -v docker >/dev/null 2>&1; then
        error "Postgres isn't running and Docker isn't installed."
        error "Either install Docker, or start a Postgres on :$PG_PORT ($PG_USER/$PG_PASS, db '$PG_DB') yourself and re-run."
        return 1
    fi
    # Reuse the dev container across runs; create it the first time.
    if docker ps -a --format '{{.Names}}' 2>/dev/null | grep -qx "$PG_CONTAINER"; then
        info "Starting existing Postgres container '$PG_CONTAINER'..."
        docker start "$PG_CONTAINER" >/dev/null || { error "Failed to start container '$PG_CONTAINER'."; return 1; }
    else
        info "Creating Postgres container '$PG_CONTAINER' (docker run, :$PG_PORT published)..."
        docker run -d --name "$PG_CONTAINER" \
            -e POSTGRES_USER="$PG_USER" -e POSTGRES_PASSWORD="$PG_PASS" -e POSTGRES_DB="$PG_DB" \
            -p "$PG_PORT:5432" postgres:16-alpine >/dev/null \
            || { error "Failed to start Postgres via docker run."; return 1; }
    fi
    # Wait up to ~30s for the port to accept connections.
    local i
    for i in $(seq 1 30); do
        port_open "$PG_HOST" "$PG_PORT" && { info "Postgres is up on :$PG_PORT."; return 0; }
        sleep 1
    done
    error "Postgres didn't come up on :$PG_PORT in time. Check: docker logs $PG_CONTAINER"
    return 1
}

# --- Tasks ------------------------------------------------------------------
run_backend() {
    check_postgres || { read -rp "Start backend anyway? [y/N] " a; [[ "$a" =~ ^[Yy]$ ]] || return; }
    backend_java_env
    info "Starting backend (Spring Boot) on :$BACKEND_PORT — Ctrl-C to stop and return to menu."
    echo ""
    ( cd "$BACKEND_DIR" && JAVA_HOME="${BACKEND_JAVA_HOME:-$JAVA_HOME}" ./mvnw spring-boot:run )
    echo ""
    info "Backend stopped."
}

run_frontend() {
    info "Starting frontend (ng serve) on :$FRONTEND_PORT — Ctrl-C to stop and return to menu."
    echo ""
    ( cd "$FRONTEND_DIR" && npm run PromptleUI )
    echo ""
    info "Frontend stopped."
}

run_both() {
    # Optional $1: extra args handed to the backend's spring-boot:run (e.g. property overrides).
    local be_extra_args="${1:-}"
    check_postgres || { read -rp "Start anyway? [y/N] " a; [[ "$a" =~ ^[Yy]$ ]] || return; }
    local be_log="$REPO_DIR/.backend.log"
    local fe_log="$REPO_DIR/.frontend.log"
    : > "$be_log"; : > "$fe_log"

    info "Starting backend + frontend together. Ctrl-C stops both and returns to the menu."
    info "Logs: $be_log  |  $fe_log"
    backend_java_env
    echo ""

    ( cd "$BACKEND_DIR" && JAVA_HOME="${BACKEND_JAVA_HOME:-$JAVA_HOME}" ./mvnw spring-boot:run ${be_extra_args:+$be_extra_args} ) > "$be_log" 2>&1 &
    local be_pid=$!
    ( cd "$FRONTEND_DIR" && npm run PromptleUI ) > "$fe_log" 2>&1 &
    local fe_pid=$!

    # Tail both logs (prefixed) until the user interrupts.
    ( tail -n +1 -f "$be_log" | sed -u "s/^/$(printf "${BLUE}[be]${NC} ")/" ) &
    local be_tail=$!
    ( tail -n +1 -f "$fe_log" | sed -u "s/^/$(printf "${GREEN}[fe]${NC} ")/" ) &
    local fe_tail=$!

    cleanup_both() {
        echo ""
        warn "Stopping backend + frontend..."
        kill "$be_tail" "$fe_tail" 2>/dev/null
        # Kill process groups so child java/node processes die too.
        kill "$be_pid" "$fe_pid" 2>/dev/null
        pkill -P "$be_pid" 2>/dev/null
        pkill -P "$fe_pid" 2>/dev/null
        wait "$be_pid" "$fe_pid" 2>/dev/null
        info "Both stopped."
    }
    trap 'cleanup_both; trap - INT; return' INT

    wait "$be_pid" "$fe_pid" 2>/dev/null
    trap - INT
    kill "$be_tail" "$fe_tail" 2>/dev/null
}

# One-command local play: Postgres (docker) + stub images + backend + frontend.
# Forces the stub image provider at runtime so no AI backend (ComfyUI) is needed
# and application.properties is left untouched.
run_play() {
    info "Promptle — one-command local play (stub images, no AI backend needed)."
    ensure_postgres || { read -rp "Start the app anyway? [y/N] " a; [[ "$a" =~ ^[Yy]$ ]] || return; }
    info "Using stub images (override: image.generation.provider=stub)."
    info "Once both are up, open http://localhost:$FRONTEND_PORT and create a room."
    run_both '-Dspring-boot.run.arguments=--image.generation.provider=stub'
}

run_sim() {
    if [[ ! -d "$SIM_DIR/node_modules" ]]; then
        warn "Sim dependencies not installed — running setup (npm install + Playwright)..."
        ( cd "$SIM_DIR" && npm run setup ) || { error "Setup failed."; return; }
    fi
    if ! http_ok "http://localhost:$FRONTEND_PORT" && ! http_ok "http://localhost"; then
        warn "Frontend doesn't look reachable. The sim drives the real UI — start the app first (option 3)."
        read -rp "Run sim anyway? [y/N] " a; [[ "$a" =~ ^[Yy]$ ]] || return
    fi
    read -rp "Watch the browser (headed)? [y/N] " headed
    echo ""
    if [[ "$headed" =~ ^[Yy]$ ]]; then
        ( cd "$SIM_DIR" && HEADED=1 SLOWMO=250 npm run sim )
    else
        ( cd "$SIM_DIR" && npm run sim )
    fi
    echo ""
    info "Simulation finished — screenshots in $SIM_DIR/screenshots"
}

run_backend_tests() {
    backend_java_env
    info "Running backend tests (./mvnw test) — Ctrl-C to abort and return to menu."
    echo ""
    ( cd "$BACKEND_DIR" && JAVA_HOME="${BACKEND_JAVA_HOME:-$JAVA_HOME}" ./mvnw test )
    local rc=$?
    echo ""
    if [[ $rc -eq 0 ]]; then info "Backend tests passed."; else error "Backend tests failed (exit $rc)."; fi
}

run_frontend_tests() {
    info "Running frontend tests (npm run test) — Ctrl-C to abort and return to menu."
    echo ""
    ( cd "$FRONTEND_DIR" && npm run test )
    local rc=$?
    echo ""
    if [[ $rc -eq 0 ]]; then info "Frontend tests passed."; else error "Frontend tests failed (exit $rc)."; fi
}

run_all_tests() {
    info "Running backend tests, then frontend tests."
    run_backend_tests
    run_frontend_tests
}

run_tests() {
    while true; do
        echo ""
        echo -e "  ${BOLD}Tests${NC}"
        echo "  ────────────────────────────────"
        echo "  1) Backend  (./mvnw test)"
        echo "  2) Frontend (npm run test)"
        echo "  3) Both"
        echo "  b) Back"
        echo ""
        read -rp "  > " t
        case "$t" in
            1) run_backend_tests ;;
            2) run_frontend_tests ;;
            3) run_all_tests ;;
            b|B) return ;;
            *) error "Unknown option: $t" ;;
        esac
    done
}

run_deploy() {
    while true; do
        echo ""
        echo -e "  ${BOLD}Deploy${NC} (wraps scripts/deploy.sh)"
        echo "  ────────────────────────────────"
        echo "  1) Deploy all (+ ComfyUI)"
        echo "  2) Deploy backend only"
        echo "  3) Deploy frontend only"
        echo "  4) Clean rebuild (wipes DB + images)"
        echo "  5) Status + smoke tests"
        echo "  6) Stop everything"
        echo "  b) Back"
        echo ""
        read -rp "  > " d
        case "$d" in
            1) "$SCRIPT_DIR/deploy.sh" ;;
            2) "$SCRIPT_DIR/deploy.sh" backend ;;
            3) "$SCRIPT_DIR/deploy.sh" frontend ;;
            4) "$SCRIPT_DIR/deploy.sh" --clean ;;
            5) "$SCRIPT_DIR/deploy.sh" --status ;;
            6) "$SCRIPT_DIR/deploy.sh" --stop ;;
            b|B) return ;;
            *) error "Unknown option: $d" ;;
        esac
    done
}

run_gen_assets() {
    info "Regenerating GIF-export assets (avatars + logo) — wraps scripts/gen-export-assets.sh."
    info "Requires: rsvg-convert (brew install librsvg) and sips (macOS)."
    echo ""
    "$SCRIPT_DIR/gen-export-assets.sh"
    local rc=$?
    echo ""
    if [[ $rc -eq 0 ]]; then info "Export assets regenerated."; else error "Asset generation failed (exit $rc)."; fi
}

run_preview_gif() {
    info "Rendering a sample export GIF — wraps scripts/preview-export-gif.sh."
    info "Requires: ffmpeg on PATH, JDK 21+, and export assets (run gen-assets first if missing)."
    echo ""
    "$SCRIPT_DIR/preview-export-gif.sh"
    local rc=$?
    echo ""
    if [[ $rc -eq 0 ]]; then info "Preview GIF rendered."; else error "Preview render failed (exit $rc)."; fi
}

run_export() {
    while true; do
        echo ""
        echo -e "  ${BOLD}Export assets${NC} (GIF chain export)"
        echo "  ────────────────────────────────"
        echo "  1) Regenerate assets (avatars + logo)"
        echo "  2) Preview export GIF (sample chain)"
        echo "  b) Back"
        echo ""
        read -rp "  > " e
        case "$e" in
            1) run_gen_assets ;;
            2) run_preview_gif ;;
            b|B) return ;;
            *) error "Unknown option: $e" ;;
        esac
    done
}

clear_db() {
    warn "This deletes all rows from every table in '$PG_DB' (public schema) on $PG_HOST:$PG_PORT."
    warn "Tables are kept — only the data is removed."
    read -rp "Are you sure? [y/N] " a
    [[ "$a" =~ ^[Yy]$ ]] || { info "Cancelled."; return; }

    if ! command -v psql >/dev/null 2>&1; then
        error "psql not found. Alternative: './scripts/deploy.sh --clean' wipes the Docker DB volume."
        return
    fi
    if ! port_open "$PG_HOST" "$PG_PORT"; then
        error "Postgres not reachable on :$PG_PORT."
        return
    fi

    # Count tables first — if there are none, the schema was never created.
    local n
    n=$(PGPASSWORD="$PG_PASS" psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d "$PG_DB" -tAc \
        "SELECT count(*) FROM pg_tables WHERE schemaname='public';" 2>/dev/null)
    if [[ -z "$n" ]]; then
        error "Could not query the database."
        return
    fi
    if [[ "$n" == "0" ]]; then
        warn "No tables found — the schema isn't created yet. Start the backend once so Hibernate builds it."
        return
    fi

    # TRUNCATE every table in one transaction; RESTART IDENTITY resets sequences,
    # CASCADE handles FK ordering. Keeps the schema intact.
    if PGPASSWORD="$PG_PASS" psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d "$PG_DB" -v ON_ERROR_STOP=1 >/dev/null 2>&1 <<'SQL'
DO $$
DECLARE tables TEXT;
BEGIN
    SELECT string_agg(format('%I.%I', schemaname, tablename), ', ')
      INTO tables
      FROM pg_tables WHERE schemaname = 'public';
    IF tables IS NOT NULL THEN
        EXECUTE 'TRUNCATE TABLE ' || tables || ' RESTART IDENTITY CASCADE';
    END IF;
END $$;
SQL
    then
        info "Cleared all data from $n table(s). Tables and schema are intact — no restart needed."
    else
        error "Failed to clear the data."
    fi
}

show_status() {
    echo ""
    echo -e "  ${BOLD}Service status${NC}"
    echo "  ────────────────────────────────"
    if port_open "$PG_HOST" "$PG_PORT"; then info "Postgres  :$PG_PORT ........ UP"; else error "Postgres  :$PG_PORT ........ down"; fi
    if http_ok "http://localhost:$BACKEND_PORT/api/rooms"; then info "Backend   :$BACKEND_PORT ........ UP"; else error "Backend   :$BACKEND_PORT ........ down"; fi
    if http_ok "http://localhost:$FRONTEND_PORT" || http_ok "http://localhost"; then info "Frontend  :$FRONTEND_PORT ........ UP"; else error "Frontend  :$FRONTEND_PORT ........ down"; fi
    if http_ok "http://localhost:$COMFYUI_PORT/system_stats"; then info "ComfyUI   :$COMFYUI_PORT ........ UP"; else warn  "ComfyUI   :$COMFYUI_PORT ........ down"; fi
}

# --- Menu -------------------------------------------------------------------
menu() {
    while true; do
        echo ""
        echo -e "  ${BOLD}Promptle Dev Console${NC}"
        echo "  ────────────────────"
        echo "  p) Play locally (Postgres + stub images + app)"
        echo "  1) Run backend"
        echo "  2) Run frontend"
        echo "  3) Run both (backend+frontend)"
        echo "  4) Test (frontend/backend/both)"
        echo "  5) Simulate (2-player)"
        echo "  6) Deploy"
        echo "  7) Export assets (regen / preview GIF)"
        echo "  8) Clear DB"
        echo "  9) Status"
        echo "  q) Quit"
        echo ""
        read -rp "  > " choice
        case "$choice" in
            p|P) run_play ;;
            1) run_backend ;;
            2) run_frontend ;;
            3) run_both ;;
            4) run_tests ;;
            5) run_sim ;;
            6) run_deploy ;;
            7) run_export ;;
            8) clear_db ;;
            9) show_status ;;
            q|Q) info "Bye."; exit 0 ;;
            "") ;;
            *) error "Unknown option: $choice" ;;
        esac
    done
}

# --- Entry ------------------------------------------------------------------
case "${1:-menu}" in
    play)           run_play ;;
    backend)        run_backend ;;
    frontend)       run_frontend ;;
    both)           run_both ;;
    test|tests)     run_tests ;;
    test-backend)   run_backend_tests ;;
    test-frontend)  run_frontend_tests ;;
    test-all)       run_all_tests ;;
    sim|simulate)   run_sim ;;
    deploy)         run_deploy ;;
    export)         run_export ;;
    gen-assets|gen-export-assets) run_gen_assets ;;
    preview-gif|preview-export-gif) run_preview_gif ;;
    clear-db|cleardb) clear_db ;;
    status)         show_status ;;
    menu)           menu ;;
    -h|--help)
        echo "Usage: ./scripts/dev.sh [play|backend|frontend|both|test|test-backend|test-frontend|test-all|sim|deploy|export|gen-assets|preview-gif|clear-db|status]"
        echo "  (no args)  open the interactive menu"
        ;;
    *) error "Unknown task: $1"; echo "Try: ./scripts/dev.sh --help" ;;
esac
