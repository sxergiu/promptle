export type CellType = 'empty' | 'body' | 'screen' | 'phone' | 'button' | 'antenna';

export interface PixelCell {
  id: number;
  row: number;
  col: number;
  type: CellType;
  color: string;
}

export interface Palette {
  colors: string[];
  base: string;
}

// ── Logo layout: 16 cols x 28 rows ──
// Retro phone with antenna, screen, red handset, 9 grey buttons
const _ = 'empty';
const B = 'body';
const S = 'screen';
const R = 'phone';
const G = 'button';
const A = 'antenna';

const LOGO_LAYOUT: CellType[][] = [
  // Antenna (top-right)
  [_,_,_,_,_,_,_,_,_,_,_,_,A,A,_,_],  // 0
  [_,_,_,_,_,_,_,_,_,_,_,_,A,A,_,_],  // 1
  [_,_,_,_,_,_,_,_,_,_,_,_,A,A,_,_],  // 2
  [_,_,_,_,_,_,_,_,_,_,_,_,A,A,_,_],  // 3
  [_,_,_,_,_,_,_,_,_,_,_,_,A,A,_,_],  // 4
  // Phone body top (antenna joins)
  [_,B,B,B,B,B,B,B,B,B,B,B,B,B,B,_],  // 5
  [_,B,B,B,B,B,B,B,B,B,B,B,B,B,B,_],  // 6
  // Screen
  [_,B,B,S,S,S,S,S,S,S,S,S,S,B,B,_],  // 7
  [B,B,B,S,S,S,S,S,S,S,S,S,S,B,B,_],  // 8  ← side button 1
  // Handset earpiece (5 wide, cols 5-9)
  [B,B,B,S,S,R,R,R,R,R,S,S,S,B,B,_],  // 9  ← side button 1
  [B,B,B,S,S,R,R,R,R,R,S,S,S,B,B,_],  // 10 ← side button 1
  // Handset handle (2 wide, cols 8-9, connects right side)
  [_,B,B,S,S,S,S,S,R,R,S,S,S,B,B,_],  // 11
  [_,B,B,S,S,S,S,S,R,R,S,S,S,B,B,_],  // 12
  // Handset mouthpiece (5 wide, cols 5-9)
  [B,B,B,S,S,R,R,R,R,R,S,S,S,B,B,_],  // 13 ← side button 2
  [B,B,B,S,S,R,R,R,R,R,S,S,S,B,B,_],  // 14 ← side button 2
  // Screen bottom
  [B,B,B,S,S,S,S,S,S,S,S,S,S,B,B,_],  // 15 ← side button 2
  [_,B,B,S,S,S,S,S,S,S,S,S,S,B,B,_],  // 16
  // Body divider
  [_,B,B,B,B,B,B,B,B,B,B,B,B,B,B,_],  // 17
  // Buttons row 1
  [_,B,B,G,G,B,B,G,G,B,B,G,G,B,B,_],  // 18
  [_,B,B,G,G,B,B,G,G,B,B,G,G,B,B,_],  // 19
  [_,B,B,B,B,B,B,B,B,B,B,B,B,B,B,_],  // 20
  // Buttons row 2
  [_,B,B,G,G,B,B,G,G,B,B,G,G,B,B,_],  // 21
  [_,B,B,G,G,B,B,G,G,B,B,G,G,B,B,_],  // 22
  [_,B,B,B,B,B,B,B,B,B,B,B,B,B,B,_],  // 23
  // Buttons row 3
  [_,B,B,G,G,B,B,G,G,B,B,G,G,B,B,_],  // 24
  [_,B,B,G,G,B,B,G,G,B,B,G,G,B,B,_],  // 25
  // Phone bottom
  [_,B,B,B,B,B,B,B,B,B,B,B,B,B,B,_],  // 26
  [_,B,B,B,B,B,B,B,B,B,B,B,B,B,B,_],  // 27
];

export const GRID_COLS = 16;
export const GRID_ROWS = 28;

// ── Theme-aware colors ──

function isDark(): boolean {
  return document.documentElement.getAttribute('data-theme') === 'dark';
}

function getBodyColor(): string {
  return isDark() ? '#2e2e2e' : '#1a1a1a';
}

function getAntennaColor(): string {
  return isDark() ? '#3a3a3a' : '#222222';
}

function getButtonColor(): string {
  return isDark() ? '#6a6a6a' : '#999999';
}

function getPhoneColor(): string {
  return isDark() ? '#cc3358' : '#990030';
}

function getScreenBase(): string {
  return isDark() ? '#1a2a1e' : '#dde5df';
}

// ── Palettes (screen cells only) ──

const PALETTES_LIGHT: Palette[] = [
  { base: '#dde5df', colors: ['#628266', '#3a5a3e', '#a3c4a7', '#c9dccb', '#4a7050'] },
  { base: '#e8e0d4', colors: ['#990030', '#cc3358', '#e8a0b0', '#628266', '#a3c4a7'] },
  { base: '#e0dce8', colors: ['#5c4b8a', '#8b6fbf', '#c4a8e0', '#a080c0', '#7060a0'] },
  { base: '#e8ddd4', colors: ['#c45e20', '#e89050', '#f0c090', '#d07030', '#b05818'] },
  { base: '#d4e0e8', colors: ['#2060a0', '#4090d0', '#90c0e8', '#3078b8', '#185888'] },
  { base: '#e8e4d0', colors: ['#b8a020', '#d0c050', '#e8dc90', '#c0b030', '#a09018'] },
];

const PALETTES_DARK: Palette[] = [
  { base: '#1a2a1e', colors: ['#3a5a3e', '#628266', '#2e4830', '#4a7050', '#1a3a1e'] },
  { base: '#2a2020', colors: ['#7a2040', '#990030', '#5a1828', '#cc3358', '#4a1020'] },
  { base: '#2a2030', colors: ['#5c4b8a', '#3a2a5a', '#7060a0', '#8b6fbf', '#2a1a4a'] },
  { base: '#2e2418', colors: ['#7a3510', '#a04810', '#5a2808', '#c45e20', '#4a2008'] },
  { base: '#182030', colors: ['#103070', '#1850a0', '#0a2050', '#2060a0', '#081838'] },
  { base: '#2a2818', colors: ['#6a5a08', '#8a7a10', '#504408', '#b8a020', '#3a3008'] },
];

export function getPalettes(): Palette[] {
  return isDark() ? PALETTES_DARK : PALETTES_LIGHT;
}

// ── Grid builder ──

export function buildGrid(): PixelCell[] {
  const cells: PixelCell[] = [];
  for (let r = 0; r < GRID_ROWS; r++) {
    for (let c = 0; c < GRID_COLS; c++) {
      const type = LOGO_LAYOUT[r][c];
      cells.push({
        id: r * GRID_COLS + c,
        row: r,
        col: c,
        type,
        color: getInitialColor(type),
      });
    }
  }
  return cells;
}

function getInitialColor(type: CellType): string {
  switch (type) {
    case 'body': return getBodyColor();
    case 'antenna': return getAntennaColor();
    case 'screen': return getScreenBase();
    case 'phone': return getPhoneColor();
    case 'button': return getButtonColor();
    case 'empty': return 'transparent';
  }
}

// ── Wave patterns (screen cells only) ──

export type WavePattern = 'diagonal' | 'spiral' | 'scatter' | 'horizontal' | 'radial';

const PATTERNS: WavePattern[] = ['diagonal', 'spiral', 'scatter', 'horizontal', 'radial'];

export function randomPattern(): WavePattern {
  return PATTERNS[Math.floor(Math.random() * PATTERNS.length)];
}

export function randomPalette(excludeIndex: number): { palette: Palette; index: number } {
  const palettes = getPalettes();
  let idx: number;
  do {
    idx = Math.floor(Math.random() * palettes.length);
  } while (idx === excludeIndex && palettes.length > 1);
  return { palette: palettes[idx], index: idx };
}

// Screen area: cols 3-12, rows 7-16
const SCREEN_MIN_COL = 3;
const SCREEN_MAX_COL = 12;
const SCREEN_MIN_ROW = 7;
const SCREEN_MAX_ROW = 16;
const SCREEN_W = SCREEN_MAX_COL - SCREEN_MIN_COL + 1;
const SCREEN_H = SCREEN_MAX_ROW - SCREEN_MIN_ROW + 1;

export function computeDelay(cell: PixelCell, pattern: WavePattern): number {
  const sc = cell.col - SCREEN_MIN_COL;
  const sr = cell.row - SCREEN_MIN_ROW;
  const maxDim = SCREEN_W + SCREEN_H;

  switch (pattern) {
    case 'diagonal':
      return ((sr + sc) / maxDim) * 2000;
    case 'horizontal':
      return (sc / SCREEN_W) * 2000;
    case 'radial': {
      const cx = SCREEN_W / 2, cy = SCREEN_H / 2;
      const dist = Math.sqrt((sc - cx) ** 2 + (sr - cy) ** 2);
      return (dist / Math.sqrt(cx ** 2 + cy ** 2)) * 2000;
    }
    case 'spiral': {
      const cx = SCREEN_W / 2, cy = SCREEN_H / 2;
      const angle = Math.atan2(sr - cy, sc - cx) + Math.PI;
      const dist = Math.sqrt((sc - cx) ** 2 + (sr - cy) ** 2);
      const maxDist = Math.sqrt(cx ** 2 + cy ** 2);
      return ((angle / (2 * Math.PI)) * 0.5 + (dist / maxDist) * 0.5) * 2000;
    }
    case 'scatter':
      return Math.random() * 2000;
  }
}

export function pickColor(palette: Palette): string {
  return palette.colors[Math.floor(Math.random() * palette.colors.length)];
}

export function getScreenNeighborIndices(index: number): number[] {
  const row = Math.floor(index / GRID_COLS);
  const col = index % GRID_COLS;
  const neighbors: number[] = [];
  for (let dr = -1; dr <= 1; dr++) {
    for (let dc = -1; dc <= 1; dc++) {
      const r = row + dr;
      const c = col + dc;
      if (r >= 0 && r < GRID_ROWS && c >= 0 && c < GRID_COLS) {
        if (LOGO_LAYOUT[r][c] === 'screen') {
          neighbors.push(r * GRID_COLS + c);
        }
      }
    }
  }
  return neighbors;
}

// ── Button identification ──
// 9 buttons in a 3x3 grid:
// Row 0: rows 18-19 | Row 1: rows 21-22 | Row 2: rows 24-25
// Col 0: cols 3-4   | Col 1: cols 7-8   | Col 2: cols 11-12

export function getButtonIndex(cell: PixelCell): number {
  if (cell.type !== 'button') return -1;
  const rowGroup = cell.row <= 19 ? 0 : cell.row <= 22 ? 1 : 2;
  const colGroup = cell.col <= 4 ? 0 : cell.col <= 8 ? 1 : 2;
  return rowGroup * 3 + colGroup;
}

export function getScreenCellIndices(): number[] {
  const indices: number[] = [];
  for (let r = 0; r < GRID_ROWS; r++) {
    for (let c = 0; c < GRID_COLS; c++) {
      if (LOGO_LAYOUT[r][c] === 'screen') {
        indices.push(r * GRID_COLS + c);
      }
    }
  }
  return indices;
}

export function getScreenColumnMap(): Map<number, number[]> {
  const columns = new Map<number, number[]>();
  for (let r = 0; r < GRID_ROWS; r++) {
    for (let c = 0; c < GRID_COLS; c++) {
      if (LOGO_LAYOUT[r][c] === 'screen') {
        if (!columns.has(c)) columns.set(c, []);
        columns.get(c)!.push(r * GRID_COLS + c);
      }
    }
  }
  return columns;
}

export function getScreenBase_(): string {
  return getScreenBase();
}

export const DISCO_COLORS = ['#ff0066', '#00ff88', '#0088ff', '#ffff00', '#ff00ff', '#00ffff'];
