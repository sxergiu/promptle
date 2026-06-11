#!/bin/bash
set -euo pipefail

# Deploy script for Promptle
# Starts ComfyUI (if not running), rebuilds and restarts Docker Compose services.
# One command to go from code changes to a fully running stack.
#
# Usage:
#   ./scripts/deploy.sh              # rebuild & restart everything (+ ComfyUI)
#   ./scripts/deploy.sh backend      # rebuild & restart backend only
#   ./scripts/deploy.sh frontend     # rebuild & restart frontend only
#   ./scripts/deploy.sh --clean      # full clean rebuild (wipes DB + images)
#   ./scripts/deploy.sh --stop       # stop everything (Compose + ComfyUI)

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_DIR"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

info()  { echo -e "${GREEN}[+]${NC} $1"; }
warn()  { echo -e "${YELLOW}[!]${NC} $1"; }
error() { echo -e "${RED}[x]${NC} $1"; }

COMFYUI_SCRIPT="$SCRIPT_DIR/start-comfyui.sh"
COMFYUI_LOG="$REPO_DIR/.comfyui.log"

ensure_comfyui() {
    if curl -s --max-time 3 http://localhost:8188/system_stats > /dev/null 2>&1; then
        info "ComfyUI already running on :8188"
        return
    fi

    if [[ ! -x "$COMFYUI_SCRIPT" ]]; then
        error "ComfyUI not running and $COMFYUI_SCRIPT not found"
        warn "Start ComfyUI manually before redeploying"
        return 1
    fi

    info "Starting ComfyUI in background (log: $COMFYUI_LOG)..."
    nohup "$COMFYUI_SCRIPT" > "$COMFYUI_LOG" 2>&1 &
    local comfy_pid=$!
    echo "$comfy_pid" > "$REPO_DIR/.comfyui.pid"

    # Wait up to 60s for ComfyUI to become ready
    local elapsed=0
    while [[ $elapsed -lt 60 ]]; do
        if curl -s --max-time 2 http://localhost:8188/system_stats > /dev/null 2>&1; then
            info "ComfyUI is ready (pid $comfy_pid)"
            return
        fi
        sleep 3
        elapsed=$((elapsed + 3))
    done

    error "ComfyUI did not start within 60s — check $COMFYUI_LOG"
    return 1
}

stop_comfyui() {
    local pid_file="$REPO_DIR/.comfyui.pid"
    if [[ -f "$pid_file" ]]; then
        local pid
        pid=$(cat "$pid_file")
        if kill -0 "$pid" 2>/dev/null; then
            info "Stopping ComfyUI (pid $pid)..."
            kill "$pid"
            rm -f "$pid_file"
        else
            rm -f "$pid_file"
        fi
    fi
    # Also kill any ComfyUI process we can find
    if pkill -f "ComfyUI/main.py" 2>/dev/null; then
        info "Stopped ComfyUI process"
    fi
}

check_comfyui() {
    if curl -s --max-time 3 http://localhost:8188/system_stats > /dev/null 2>&1; then
        info "ComfyUI is running on :8188"
    else
        warn "ComfyUI is not reachable on :8188"
    fi
}

wait_for_services() {
    info "Waiting for services to be ready..."
    # Wait for frontend (nginx)
    local elapsed=0
    while [[ $elapsed -lt 30 ]]; do
        if curl -s --max-time 2 http://localhost > /dev/null 2>&1; then
            break
        fi
        sleep 2
        elapsed=$((elapsed + 2))
    done
    # Wait for backend (Spring Boot takes longer)
    elapsed=0
    while [[ $elapsed -lt 60 ]]; do
        local status
        status=$(curl -sI --max-time 3 http://localhost/api/rooms 2>/dev/null | head -1 | awk '{print $2}')
        if [[ -n "$status" && "$status" != "502" ]]; then
            return
        fi
        sleep 3
        elapsed=$((elapsed + 3))
    done
}

smoke_test() {
    local passed=0
    local failed=0

    wait_for_services

    echo ""
    info "Running smoke tests..."

    # Frontend
    if curl -sI --max-time 5 http://localhost | head -1 | grep -q "200"; then
        info "Frontend (nginx) .......... OK"
        ((passed++))
    else
        error "Frontend (nginx) .......... FAIL"
        ((failed++))
    fi

    # Backend via nginx proxy
    local status
    status=$(curl -sI --max-time 5 http://localhost/api/rooms 2>/dev/null | head -1 | awk '{print $2}')
    if [[ -n "$status" && "$status" != "502" ]]; then
        info "Backend (via /api proxy) .. OK (HTTP $status)"
        ((passed++))
    else
        error "Backend (via /api proxy) .. FAIL (${status:-no response})"
        ((failed++))
    fi

    # ComfyUI
    check_comfyui

    echo ""
    if [[ $failed -eq 0 ]]; then
        info "All checks passed ($passed/$passed)"
    else
        warn "$failed check(s) failed, $passed passed"
    fi
}

usage() {
    echo "Usage: ./scripts/deploy.sh [backend|frontend|--clean|--status|--stop]"
    echo ""
    echo "  (no args)    Start ComfyUI + rebuild and restart all services"
    echo "  backend      Rebuild and restart backend only"
    echo "  frontend     Rebuild and restart frontend only"
    echo "  --clean      Full clean rebuild (down -v, build --no-cache, up)"
    echo "  --status     Show service status and run smoke tests"
    echo "  --stop       Stop everything (Compose stack + ComfyUI)"
    exit 0
}

case "${1:-all}" in
    -h|--help)
        usage
        ;;
    --status)
        docker compose ps
        smoke_test
        ;;
    --stop)
        warn "Stopping everything..."
        docker compose down -v
        stop_comfyui
        info "All stopped."
        ;;
    --clean)
        ensure_comfyui
        warn "Clean rebuild: stopping services and wiping volumes..."
        docker compose down -v
        info "Building from scratch (no cache)..."
        docker compose build --no-cache
        info "Starting services..."
        docker compose up -d
        smoke_test
        ;;
    backend)
        ensure_comfyui
        info "Rebuilding backend..."
        docker compose build backend
        info "Restarting backend..."
        docker compose up -d backend
        smoke_test
        ;;
    frontend)
        ensure_comfyui
        info "Rebuilding frontend..."
        docker compose build frontend
        info "Restarting frontend..."
        docker compose up -d frontend
        smoke_test
        ;;
    all)
        ensure_comfyui
        info "Rebuilding all services..."
        docker compose build
        info "Restarting services..."
        docker compose up -d
        smoke_test
        ;;
    *)
        error "Unknown option: $1"
        usage
        ;;
esac
