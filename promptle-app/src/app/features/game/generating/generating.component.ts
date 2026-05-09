import { Component, OnInit, OnDestroy, signal } from '@angular/core';
import {
  PixelCell,
  buildGrid,
  randomPattern,
  randomPalette,
  computeDelay,
  pickColor,
  getScreenNeighborIndices,
  getButtonIndex as _getButtonIndex,
  getScreenCellIndices,
  getScreenColumnMap,
  getScreenBase_,
  DISCO_COLORS,
  GRID_COLS,
} from './pixel-grid';
import { shuffleMessages } from './messages';

interface Particle {
  id: number;
  x: number;
  y: number;
  dx: number;
  dy: number;
  color: string;
}

@Component({
  selector: 'app-generating',
  standalone: true,
  styleUrl: './generating.component.scss',
  templateUrl: './generating.component.html',
})
export class GeneratingComponent implements OnInit, OnDestroy {
  gridCols = GRID_COLS;

  cells = signal<PixelCell[]>(buildGrid());
  currentMessage = signal('');
  messageVisible = signal(false);
  poppedCells = signal<Set<number>>(new Set());
  particles = signal<Particle[]>([]);
  ringing = signal(false);
  screenOff = signal(false);
  heartbeating = signal(false);
  pressedButton = signal(-1);

  private timers: ReturnType<typeof setTimeout>[] = [];
  private intervals: ReturnType<typeof setInterval>[] = [];
  private effectIntervals: ReturnType<typeof setInterval>[] = [];
  private messageQueue: string[] = [];
  private currentPaletteIndex = -1;
  private particleIdCounter = 0;

  ngOnInit(): void {
    this.startWaveCycle();
    this.startMessageRotation();
    this.startPhonePulse();
  }

  ngOnDestroy(): void {
    this.timers.forEach(t => clearTimeout(t));
    this.intervals.forEach(i => clearInterval(i));
    this.effectIntervals.forEach(i => clearInterval(i));
  }

  onCanvasTap(event: MouseEvent): void {
    const target = event.target as HTMLElement;
    if (!target.classList.contains('pixel-cell')) return;

    const canvas = target.closest('.pixel-canvas')!;
    const cellElements = Array.from(canvas.querySelectorAll('.pixel-cell'));
    const cellIndex = cellElements.indexOf(target);
    if (cellIndex === -1) return;

    const cell = this.cells()[cellIndex];

    if (cell.type === 'phone') {
      this.triggerRing();
      return;
    }

    if (cell.type === 'button') {
      const btnIdx = _getButtonIndex(cell);
      if (btnIdx >= 0) this.onButtonPress(btnIdx);
      return;
    }

    if (cell.type === 'screen') {
      this.popCell(cellIndex);
      this.spawnParticles(target, canvas as HTMLElement);
    }
  }

  buttonIndex(cell: PixelCell): number {
    return _getButtonIndex(cell);
  }

  // ── Button effects ──

  private onButtonPress(index: number): void {
    this.pressedButton.set(index);
    const t = setTimeout(() => this.pressedButton.set(-1), 200);
    this.timers.push(t);

    switch (index) {
      case 0: this.effectStatic(); break;
      case 1: this.effectPixelRain(); break;
      case 2: this.effectRipple(); break;
      case 3: this.triggerRing(); break;
      case 4: this.effectBlackout(); break;
      case 5: this.effectScreenToggle(); break;
      case 6: this.effectHeartbeat(); break;
      case 7: this.effectDisco(); break;
      case 8: this.effectReverseWave(); break;
    }
  }

  // Btn 0: Static — rapid random colors for 2s
  private effectStatic(): void {
    const screenIndices = getScreenCellIndices();
    const iv = setInterval(() => {
      const { palette } = randomPalette(-1);
      const updated = [...this.cells()];
      for (const si of screenIndices) {
        updated[si] = { ...updated[si], color: pickColor(palette) };
      }
      this.cells.set(updated);
    }, 60);
    this.effectIntervals.push(iv);

    const t = setTimeout(() => clearInterval(iv), 2000);
    this.timers.push(t);
  }

  // Btn 1: Pixel Rain — colors cascade downward
  private effectPixelRain(): void {
    const columnMap = getScreenColumnMap();
    let step = 0;
    const maxSteps = 8;

    const iv = setInterval(() => {
      if (step >= maxSteps) { clearInterval(iv); return; }
      const updated = [...this.cells()];

      for (const [, indices] of columnMap) {
        // Shift colors down: each cell gets the color of the cell above it
        for (let i = indices.length - 1; i > 0; i--) {
          updated[indices[i]] = { ...updated[indices[i]], color: updated[indices[i - 1]].color };
        }
        // Top cell gets a new random color
        const { palette } = randomPalette(-1);
        updated[indices[0]] = { ...updated[indices[0]], color: pickColor(palette) };
      }

      this.cells.set(updated);
      step++;
    }, 150);
    this.effectIntervals.push(iv);
  }

  // Btn 2: Ripple — radial wave from center outward
  private effectRipple(): void {
    const { palette } = randomPalette(-1);
    const current = this.cells();
    const screenCells = current.filter(c => c.type === 'screen');

    for (const cell of screenCells) {
      const delay = computeDelay(cell, 'radial');
      const color = pickColor(palette);

      const t = setTimeout(() => {
        this.cells.update(cells => {
          const copy = [...cells];
          const i = copy.findIndex(c => c.id === cell.id);
          if (i !== -1) copy[i] = { ...copy[i], color };
          return copy;
        });
      }, delay);
      this.timers.push(t);
    }
  }

  // Btn 4: Blackout — screen goes dark, relights cell by cell
  private effectBlackout(): void {
    const screenIndices = getScreenCellIndices();
    const updated = [...this.cells()];
    for (const si of screenIndices) {
      updated[si] = { ...updated[si], color: '#0a0a0a' };
    }
    this.cells.set(updated);

    // Relight one by one with random order
    const shuffled = [...screenIndices].sort(() => Math.random() - 0.5);
    const { palette } = randomPalette(-1);
    for (let i = 0; i < shuffled.length; i++) {
      const color = pickColor(palette);
      const t = setTimeout(() => {
        this.cells.update(cells => {
          const copy = [...cells];
          copy[shuffled[i]] = { ...copy[shuffled[i]], color };
          return copy;
        });
      }, 300 + i * 40);
      this.timers.push(t);
    }
  }

  // Btn 5: Screen On/Off toggle
  private effectScreenToggle(): void {
    const screenIndices = getScreenCellIndices();
    if (this.screenOff()) {
      // Turn on — restore with a palette
      this.screenOff.set(false);
      const { palette } = randomPalette(-1);
      const updated = [...this.cells()];
      for (const si of screenIndices) {
        updated[si] = { ...updated[si], color: pickColor(palette) };
      }
      this.cells.set(updated);
    } else {
      // Turn off
      this.screenOff.set(true);
      const updated = [...this.cells()];
      const base = getScreenBase_();
      for (const si of screenIndices) {
        updated[si] = { ...updated[si], color: base };
      }
      // Also darken screen further
      const darkened = [...updated];
      for (const si of screenIndices) {
        darkened[si] = { ...darkened[si], color: '#0a0a0a' };
      }
      this.cells.set(darkened);
    }
  }

  // Btn 6: Heartbeat — double brightness pulse via CSS class
  private effectHeartbeat(): void {
    this.heartbeating.set(true);
    const t = setTimeout(() => this.heartbeating.set(false), 800);
    this.timers.push(t);
  }

  // Btn 7: Disco — rapid neon strobe for 3s
  private effectDisco(): void {
    const screenIndices = getScreenCellIndices();
    let colorIdx = 0;
    const iv = setInterval(() => {
      const color = DISCO_COLORS[colorIdx % DISCO_COLORS.length];
      const updated = [...this.cells()];
      for (const si of screenIndices) {
        // Alternate between 2 colors in a checkerboard
        const cell = updated[si];
        const checker = (cell.row + cell.col) % 2 === 0;
        const nextColor = DISCO_COLORS[(colorIdx + (checker ? 0 : 3)) % DISCO_COLORS.length];
        updated[si] = { ...cell, color: nextColor };
      }
      this.cells.set(updated);
      colorIdx++;
    }, 150);
    this.effectIntervals.push(iv);

    const t = setTimeout(() => clearInterval(iv), 3000);
    this.timers.push(t);
  }

  // Btn 8: Reverse Wave — wave with inverted delays
  private effectReverseWave(): void {
    const pattern = randomPattern();
    const { palette, index } = randomPalette(this.currentPaletteIndex);
    this.currentPaletteIndex = index;

    const current = this.cells();
    const screenCells = current.filter(c => c.type === 'screen');
    const delays = screenCells.map(c => computeDelay(c, pattern));
    const maxDelay = Math.max(...delays);

    for (let i = 0; i < screenCells.length; i++) {
      const cell = screenCells[i];
      const delay = maxDelay - delays[i]; // reversed
      const color = pickColor(palette);

      const t = setTimeout(() => {
        this.cells.update(cells => {
          const copy = [...cells];
          const idx = copy.findIndex(c => c.id === cell.id);
          if (idx !== -1) copy[idx] = { ...copy[idx], color };
          return copy;
        });
      }, delay);
      this.timers.push(t);
    }
  }

  // ── Core animations ──

  private triggerRing(): void {
    if (this.ringing()) return;
    this.ringing.set(true);
    const t = setTimeout(() => this.ringing.set(false), 600);
    this.timers.push(t);
  }

  private popCell(index: number): void {
    const neighbors = getScreenNeighborIndices(index);
    const { palette } = randomPalette(-1);

    const updated = [...this.cells()];
    for (const ni of neighbors) {
      updated[ni] = { ...updated[ni], color: pickColor(palette) };
    }
    this.cells.set(updated);

    const popped = new Set(neighbors);
    this.poppedCells.set(popped);

    const t = setTimeout(() => this.poppedCells.set(new Set()), 300);
    this.timers.push(t);
  }

  private spawnParticles(cellEl: HTMLElement, canvas: HTMLElement): void {
    const rect = cellEl.getBoundingClientRect();
    const canvasRect = canvas.getBoundingClientRect();
    const cx = rect.left - canvasRect.left + rect.width / 2;
    const cy = rect.top - canvasRect.top + rect.height / 2;

    const newParticles: Particle[] = [];
    for (let i = 0; i < 6; i++) {
      const angle = (Math.PI * 2 * i) / 6 + (Math.random() - 0.5) * 0.5;
      const dist = 15 + Math.random() * 15;
      newParticles.push({
        id: this.particleIdCounter++,
        x: cx,
        y: cy,
        dx: Math.cos(angle) * dist,
        dy: Math.sin(angle) * dist,
        color: this.cells()[Math.floor(Math.random() * this.cells().length)].color,
      });
    }

    this.particles.set([...this.particles(), ...newParticles]);

    const t = setTimeout(() => {
      this.particles.update(ps => ps.filter(p => !newParticles.includes(p)));
    }, 500);
    this.timers.push(t);
  }

  private startWaveCycle(): void {
    this.triggerWave();
    const interval = setInterval(() => {
      if (!this.screenOff()) this.triggerWave();
    }, 8000);
    this.intervals.push(interval);
  }

  private triggerWave(): void {
    const pattern = randomPattern();
    const { palette, index } = randomPalette(this.currentPaletteIndex);
    this.currentPaletteIndex = index;

    const current = this.cells();
    for (const cell of current) {
      if (cell.type !== 'screen') continue;

      const delay = computeDelay(cell, pattern);
      const color = pickColor(palette);

      const t = setTimeout(() => {
        this.cells.update(cells => {
          const copy = [...cells];
          const i = copy.findIndex(c => c.id === cell.id);
          if (i !== -1) copy[i] = { ...copy[i], color };
          return copy;
        });
      }, delay);
      this.timers.push(t);
    }
  }

  private startPhonePulse(): void {
    const scheduleRing = () => {
      const delay = 12000 + Math.random() * 6000;
      const t = setTimeout(() => {
        this.triggerRing();
        scheduleRing();
      }, delay);
      this.timers.push(t);
    };
    scheduleRing();
  }

  private startMessageRotation(): void {
    this.nextMessage();
  }

  private nextMessage(): void {
    if (this.messageQueue.length === 0) {
      this.messageQueue = shuffleMessages();
    }

    this.messageVisible.set(false);

    const t1 = setTimeout(() => {
      this.currentMessage.set(this.messageQueue.pop()!);
      this.messageVisible.set(true);
    }, 500);
    this.timers.push(t1);

    const t2 = setTimeout(() => {
      this.messageVisible.set(false);
    }, 5500);
    this.timers.push(t2);

    const t3 = setTimeout(() => this.nextMessage(), 6200);
    this.timers.push(t3);
  }
}
