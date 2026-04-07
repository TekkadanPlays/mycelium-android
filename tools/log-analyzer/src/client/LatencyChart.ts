import { Component } from 'inferno';
import { createElement } from 'inferno-create-element';
import { effect } from '@preact/signals-core';
import {
  Chart, BarController, BarElement,
  LinearScale, CategoryScale,
  Tooltip as ChartTooltip,
} from 'chart.js';
import {
  results,
  type PingResult,
} from '../signals';

Chart.register(BarController, BarElement, LinearScale, CategoryScale, ChartTooltip);

// ---------------------------------------------------------------------------
// LatencyChart — two bar charts + percentile badges
//   1. Latency distribution histogram (horizontal)
//   2. Throughput bar chart (req/s in rolling windows)
//
// Key design decisions:
//   - No forceUpdate() — charts update via Chart.js only, badges via SignalBridge
//   - Colors use oklch() with / alpha syntax, not hex suffixes
//   - Throughput uses fixed 500ms windows for smooth bars at any request rate
// ---------------------------------------------------------------------------

const BUCKETS = [
  { label: '0–2',   min: 0,   max: 2   },
  { label: '2–5',   min: 2,   max: 5   },
  { label: '5–10',  min: 5,   max: 10  },
  { label: '10–25', min: 10,  max: 25  },
  { label: '25–50', min: 25,  max: 50  },
  { label: '50+',   min: 50,  max: Infinity },
];

// Read CSS custom properties and convert to usable colors
function getTheme() {
  const s = getComputedStyle(document.documentElement);
  const get = (v: string, fb: string) => s.getPropertyValue(v).trim() || fb;
  return {
    primary:     get('--primary', 'oklch(0.68 0.19 150)'),
    secondary:   get('--secondary', 'oklch(0.55 0.15 250)'),
    destructive: get('--destructive', 'oklch(0.55 0.2 25)'),
    mutedFg:     get('--muted-foreground', 'oklch(0.55 0 0)'),
    border:      get('--border', 'oklch(0.3 0 0)'),
    foreground:  get('--foreground', 'oklch(0.95 0 0)'),
    card:        get('--card', 'oklch(0.15 0 0)'),
  };
}

type TC = ReturnType<typeof getTheme>;

// oklch-safe alpha: wrap `oklch(L C H)` → `oklch(L C H / alpha)`
function withAlpha(color: string, alpha: number): string {
  const m = color.match(/^oklch\(([^)]+)\)$/);
  if (m) return `oklch(${m[1]} / ${alpha})`;
  return color; // fallback: return as-is
}

function bucketColor(idx: number, tc: TC): string {
  if (idx <= 1) return tc.primary;
  if (idx <= 3) return tc.secondary;
  return tc.destructive;
}

function computeBuckets(r: PingResult[]): number[] {
  const counts = new Array(BUCKETS.length).fill(0);
  for (const p of r) {
    for (let i = 0; i < BUCKETS.length; i++) {
      if (p.latency >= BUCKETS[i].min && p.latency < BUCKETS[i].max) {
        counts[i]++;
        break;
      }
    }
  }
  return counts;
}

// Throughput in 500ms windows, last 20 shown, normalized to req/s
const TPUT_WINDOW_MS = 500;
const TPUT_MAX_BARS = 20;

function computeThroughput(r: PingResult[]): { labels: string[]; data: number[] } {
  if (r.length < 2) return { labels: [], data: [] };
  const endTs = r[r.length - 1].ts;
  const windowStart = endTs - TPUT_MAX_BARS * TPUT_WINDOW_MS;
  const counts = new Array(TPUT_MAX_BARS).fill(0);
  for (const p of r) {
    if (p.ts <= windowStart) continue;
    const idx = Math.min(Math.floor((p.ts - windowStart) / TPUT_WINDOW_MS), TPUT_MAX_BARS - 1);
    counts[idx]++;
  }
  // Normalize to req/s (each window is 500ms = 0.5s)
  const rps = counts.map(c => Math.round(c / (TPUT_WINDOW_MS / 1000)));
  const labels = rps.map((_, i) => i === TPUT_MAX_BARS - 1 ? 'now' : '');
  return { labels, data: rps };
}

export class LatencyChart extends Component<{}, {}> {
  private histRef: HTMLCanvasElement | null = null;
  private tputRef: HTMLCanvasElement | null = null;
  private histChart: Chart | null = null;
  private tputChart: Chart | null = null;
  private dispose: (() => void) | null = null;
  private themeObs: MutationObserver | null = null;
  private _prevLen = 0;
  private _histYMax = 0;  // sticky y-max: only grows during burst
  private _tputYMax = 0;

  componentDidMount() {
    this.initHist();
    this.initThroughput();
    this.dispose = effect(() => {
      const r = results.value;
      if (r.length !== this._prevLen) {
        this._prevLen = r.length;
        this.updateCharts(r);
      }
    });
    this.themeObs = new MutationObserver(() => this.updateCharts(results.value));
    this.themeObs.observe(document.documentElement, {
      attributes: true,
      attributeFilter: ['class', 'style', 'data-theme'],
    });
  }

  componentWillUnmount() {
    this.dispose?.();
    this.themeObs?.disconnect();
    this.histChart?.destroy();
    this.tputChart?.destroy();
  }

  private initHist() {
    if (!this.histRef) return;
    const tc = getTheme();
    this.histChart = new Chart(this.histRef, {
      type: 'bar',
      data: {
        labels: BUCKETS.map(b => b.label),
        datasets: [{
          data: new Array(BUCKETS.length).fill(0),
          backgroundColor: BUCKETS.map((_, i) => bucketColor(i, tc)),
          borderRadius: 3,
          borderSkipped: false,
          barPercentage: 0.8,
          categoryPercentage: 0.9,
        }],
      },
      options: {
        indexAxis: 'y',
        responsive: true,
        maintainAspectRatio: false,
        animation: { duration: 0 },
        plugins: {
          legend: { display: false },
          tooltip: {
            backgroundColor: tc.card,
            titleColor: tc.foreground,
            bodyColor: tc.mutedFg,
            borderColor: tc.border,
            borderWidth: 1,
            cornerRadius: 6,
            padding: 8,
            callbacks: {
              label: (item) => {
                const total = results.value.length || 1;
                const count = item.parsed.x ?? 0;
                const pct = ((count / total) * 100).toFixed(1);
                return `${count} (${pct}%)`;
              },
            },
          },
        },
        scales: {
          x: {
            display: true,
            beginAtZero: true,
            grid: { color: withAlpha(tc.border, 0.12) },
            ticks: { color: tc.mutedFg, font: { size: 9 }, precision: 0 },
          },
          y: {
            display: true,
            grid: { display: false },
            ticks: { color: tc.mutedFg, font: { size: 10 } },
          },
        },
      },
    });
  }

  private initThroughput() {
    if (!this.tputRef) return;
    const tc = getTheme();
    this.tputChart = new Chart(this.tputRef, {
      type: 'bar',
      data: {
        labels: [],
        datasets: [{
          data: [],
          backgroundColor: withAlpha(tc.primary, 0.7),
          borderRadius: 2,
          barPercentage: 0.95,
          categoryPercentage: 0.95,
        }],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        animation: { duration: 0 },
        plugins: {
          legend: { display: false },
          tooltip: {
            backgroundColor: tc.card,
            titleColor: tc.foreground,
            bodyColor: tc.mutedFg,
            borderColor: tc.border,
            borderWidth: 1,
            cornerRadius: 6,
            padding: 8,
            callbacks: { label: (item) => `${item.parsed.y} req/s` },
          },
        },
        scales: {
          x: {
            display: true,
            grid: { display: false },
            ticks: { color: tc.mutedFg, font: { size: 8 }, maxRotation: 0 },
          },
          y: {
            display: true,
            beginAtZero: true,
            grid: { color: withAlpha(tc.border, 0.08) },
            ticks: { color: tc.mutedFg, font: { size: 9 }, precision: 0, maxTicksLimit: 4 },
          },
        },
      },
    });
  }

  private updateCharts(r: PingResult[]) {
    const tc = getTheme();

    if (this.histChart) {
      const counts = computeBuckets(r);
      const dataMax = Math.max(...counts, 1);
      // Sticky max: grow instantly, shrink slowly (10% decay per update)
      this._histYMax = Math.max(dataMax, this._histYMax * 0.95);
      const ds = this.histChart.data.datasets[0];
      ds.data = counts;
      ds.backgroundColor = BUCKETS.map((_, i) => bucketColor(i, tc)) as any;
      const xScale = this.histChart.options.scales!.x as any;
      xScale.suggestedMax = Math.ceil(this._histYMax * 1.1);
      this.applyTheme(this.histChart, tc);
      this.histChart.update('none');
    }

    if (this.tputChart) {
      const tp = computeThroughput(r);
      const dataMax = Math.max(...tp.data, 1);
      this._tputYMax = Math.max(dataMax, this._tputYMax * 0.95);
      this.tputChart.data.labels = tp.labels;
      const ds = this.tputChart.data.datasets[0];
      ds.data = tp.data;
      ds.backgroundColor = withAlpha(tc.primary, 0.7);
      const yScale = this.tputChart.options.scales!.y as any;
      yScale.suggestedMax = Math.ceil(this._tputYMax * 1.1);
      this.applyTheme(this.tputChart, tc);
      this.tputChart.update('none');
    }
  }

  private applyTheme(chart: Chart, tc: TC) {
    const o = chart.options;
    if (o.scales?.x) { const x = o.scales.x as any; if (x.ticks) x.ticks.color = tc.mutedFg; if (x.grid) x.grid.color = withAlpha(tc.border, 0.12); }
    if (o.scales?.y) { const y = o.scales.y as any; if (y.ticks) y.ticks.color = tc.mutedFg; if (y.grid) y.grid.color = withAlpha(tc.border, 0.08); }
    if (o.plugins?.tooltip) { const t = o.plugins.tooltip as any; t.backgroundColor = tc.card; t.titleColor = tc.foreground; t.bodyColor = tc.mutedFg; t.borderColor = tc.border; }
  }

  // Static render — canvases never re-create, Chart.js handles all visual updates
  render() {
    return createElement('div', { className: 'space-y-3' },
      createElement('div', { className: 'grid grid-cols-1 sm:grid-cols-2 gap-4' },
        createElement('div', null,
          createElement('div', { className: 'text-[10px] font-medium text-muted-foreground mb-1 uppercase tracking-wider' }, 'Latency distribution'),
          createElement('div', { style: { position: 'relative', height: '160px' } },
            createElement('canvas', {
              ref: (el: HTMLCanvasElement | null) => { this.histRef = el; },
            } as any),
          ),
        ),
        createElement('div', null,
          createElement('div', { className: 'text-[10px] font-medium text-muted-foreground mb-1 uppercase tracking-wider' }, 'Throughput'),
          createElement('div', { style: { position: 'relative', height: '160px' } },
            createElement('canvas', {
              ref: (el: HTMLCanvasElement | null) => { this.tputRef = el; },
            } as any),
          ),
        ),
      ),
    );
  }
}
