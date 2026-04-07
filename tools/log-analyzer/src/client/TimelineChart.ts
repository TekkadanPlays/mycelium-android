import { Component } from 'inferno';
import { createElement as h } from 'inferno-create-element';
import { effect } from '@preact/signals-core';
import { Chart, BarController, BarElement, LinearScale, CategoryScale, Tooltip as ChartTooltip, DefaultDataPoint } from 'chart.js';
import { parsedLogs, type LogEntry } from '../signals';

Chart.register(BarController, BarElement, LinearScale, CategoryScale, ChartTooltip);

function parseTime(t: string): number {
  if (!t) return 0;
  const parts = t.split(':');
  if (parts.length < 3) return 0;
  return (parseInt(parts[0], 10) * 3600 + parseInt(parts[1], 10) * 60 + parseFloat(parts[2])) * 1000;
}

export class TimelineChart extends Component<{}, {}> {
  private canvasRef: HTMLCanvasElement | null = null;
  private chart: Chart | null = null;
  private dispose: (() => void) | null = null;

  componentDidMount() {
    this.dispose = effect(() => {
      const logs = parsedLogs.value;
      if (logs.length === 0) {
        this.chart?.destroy();
        this.chart = null;
        return;
      }
      this.updateChart(logs);
    });
  }

  componentWillUnmount() {
    this.dispose?.();
    this.chart?.destroy();
  }

  updateChart(logs: LogEntry[]) {
    if (!this.canvasRef) return;

    // Filter logs with valid time
    const timedLogs = logs.filter(l => l.time);
    if (timedLogs.length === 0) return;

    let min = Infinity;
    let max = -Infinity;
    
    // Determine bounds
    for (const l of timedLogs) {
      const ms = parseTime(l.time);
      if (ms > 0) {
        if (ms < min) min = ms;
        if (ms > max) max = ms;
      }
    }

    if (min === Infinity) return;

    // Calculate dynamic bucket size based on duration to restrict canvas points to ~100
    const durationMs = max - min;
    const targetBuckets = 100;
    const bucketSizeMs = Math.max(1000, Math.ceil(durationMs / targetBuckets)); 
    
    // Number of buckets
    const bucketCount = Math.ceil((max - min) / bucketSizeMs) + 1;
    
    // We will separate counts by Level: Error, Warn, Info, Debug/Verbose
    const errors = new Array(bucketCount).fill(0);
    const warns = new Array(bucketCount).fill(0);
    const infos = new Array(bucketCount).fill(0);
    const others = new Array(bucketCount).fill(0);

    for (const l of timedLogs) {
      const ms = parseTime(l.time);
      if (ms > 0) {
        const idx = Math.floor((ms - min) / bucketSizeMs);
        const lvl = l.level?.toUpperCase().trim() || 'V';
        if (lvl === 'E') errors[idx]++;
        else if (lvl === 'W') warns[idx]++;
        else if (lvl === 'I') infos[idx]++;
        else others[idx]++;
      }
    }

    const labels = new Array(bucketCount).fill('');
    for (let i = 0; i < bucketCount; i++) {
        const t = new Date(min + i * bucketSizeMs);
        // Fake UTC time to extract HH:MM:SS format
        const h = Math.floor((min + i * bucketSizeMs) / 3600000) % 24;
        const m = Math.floor((min + i * bucketSizeMs) / 60000) % 60;
        const s = Math.floor((min + i * bucketSizeMs) / 1000) % 60;
        labels[i] = `${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`;
    }

    // Chart.js requires CSS variables to be resolved if we use oklch or custom vars.
    // For simplicity, we can use exact rgba values that look great in UI.
    const errColor = 'rgba(239, 68, 68, 0.9)'; // red-500
    const warnColor = 'rgba(234, 179, 8, 0.9)'; // yellow-500
    const infoColor = 'rgba(59, 130, 246, 0.8)'; // blue-500
    const otherColor = 'rgba(100, 116, 139, 0.5)'; // slate-500

    if (this.chart) {
      this.chart.data.labels = labels;
      this.chart.data.datasets[0].data = errors;
      this.chart.data.datasets[1].data = warns;
      this.chart.data.datasets[2].data = infos;
      this.chart.data.datasets[3].data = others;
      this.chart.update('none');
    } else {
      this.chart = new Chart(this.canvasRef, {
        type: 'bar',
        data: {
          labels,
          datasets: [
            { label: 'Errors', data: errors, backgroundColor: errColor, stacked: true },
            { label: 'Warnings', data: warns, backgroundColor: warnColor, stacked: true },
            { label: 'Info', data: infos, backgroundColor: infoColor, stacked: true },
            { label: 'Verbose/Debug', data: others, backgroundColor: otherColor, stacked: true }
          ]
        },
        options: {
          responsive: true,
          maintainAspectRatio: false,
          animation: { duration: 0 },
          plugins: {
            legend: { 
                display: true, 
                position: 'top', 
                align: 'end',
                labels: { boxWidth: 10, usePointStyle: true, font: { size: 10 }, color: '#888' } 
            },
            tooltip: { mode: 'index', intersect: false }
          },
          scales: {
            x: { 
                stacked: true, 
                ticks: { maxTicksLimit: 15, maxRotation: 0, color: '#888', font: { size: 10 } },
                grid: { display: false }
            },
            y: { 
                stacked: true, 
                beginAtZero: true, 
                ticks: { color: '#888', font: { size: 10 }, precision: 0 },
                grid: { color: 'rgba(128, 128, 128, 0.1)' } 
            }
          }
        }
      });
    }
  }

  render() {
    return h('div', { className: 'w-full h-48 bg-background border border-border rounded-lg p-3 my-4 overflow-hidden shadow-sm' },
      h('div', { className: 'text-xs font-semibold text-muted-foreground uppercase tracking-wider mb-2' }, 'Application Timeline Volume'),
      h('div', { className: 'relative w-full' },
        h('canvas', { ref: (el: any) => this.canvasRef = el, style: { height: '140px' } })
      )
    );
  }
}
