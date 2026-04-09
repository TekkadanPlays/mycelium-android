import { Component } from 'inferno';
import { createElement as h } from 'inferno-create-element';
import { perfSpans, healthScore, feedInspectorData, heapMetrics } from '../../signals';
import { Card, CardHeader, CardTitle, CardContent, Badge, Table, TableHeader, TableRow, TableHead, TableBody, TableCell } from 'blazecn';
import { effect } from '@preact/signals-core';
import { Chart, LineController, LineElement, PointElement, LinearScale, CategoryScale, Tooltip as ChartTooltip, Filler } from 'chart.js';

Chart.register(LineController, LineElement, PointElement, LinearScale, CategoryScale, ChartTooltip, Filler);

function SpanDurationChart() {
  const spans = perfSpans.value;
  const dbDrains = spans.filter(s => s.name.includes('DB Queue Drain') || s.name.includes('processBurst') || s.name.includes('flush'));
  
  if (dbDrains.length === 0) return h('div', { className: 'p-8 text-center text-muted-foreground' }, 'No sufficient performance spans found for DB Operations.');

  const p95 = healthScore.value.dbDrainP95Ms || 500; // default 500ms if null
  const maxDuration = Math.max(...dbDrains.map(s => s.durationMs));

  return h(Card, { className: 'mb-8' },
    h(CardHeader, null,
      h(CardTitle, null, 'Background Spans & DB Drains')
    ),
    h(CardContent, null,
      h('div', { className: 'space-y-3' },
        dbDrains.map((s, i) => {
           const pct = Math.max(1, Math.min(100, (s.durationMs / (maxDuration || 1)) * 100));
           const isAnomaly = s.durationMs > p95;
           return h('div', { key: `${s.id}-${i}`, className: 'flex flex-col gap-1' },
             h('div', { className: 'flex justify-between items-center text-xs' },
                h('span', { className: 'font-mono text-muted-foreground' }, `${s.startWallClock} - ${s.name}`),
                h('span', { className: `font-bold ${isAnomaly ? 'text-destructive' : 'text-foreground'}` }, `${s.durationMs}ms`)
             ),
             h('div', { className: 'w-full bg-muted rounded-full h-2 overflow-hidden flex' },
                h('div', { className: `h-full rounded-full transition-all ${isAnomaly ? 'bg-destructive' : 'bg-primary'}`, style: `width: ${pct}%` })
             )
           );
        })
      )
    )
  );
}

function BudgetPressureAnnotations() {
  const feed = feedInspectorData.value;
  const pressureEvents = feed.keyEvents.filter(e => e.type === 'budget_pressure');

  return h(Card, { className: 'mb-8' },
    h(CardHeader, null,
      h(CardTitle, null, 'Budget Pressure Events')
    ),
    h(CardContent, null,
      pressureEvents.length === 0 
        ? h('div', { className: 'text-muted-foreground text-sm' }, 'No budget pressure events recorded.')
        : h(Table, null,
            h(TableHeader, null,
              h(TableRow, null,
                h(TableHead, null, 'Time'),
                h(TableHead, null, 'Details'),
              )
            ),
            h(TableBody, null,
              pressureEvents.map((e, i) => 
                h(TableRow, { key: i },
                  h(TableCell, { className: 'font-mono text-xs w-[120px]' }, e.time),
                  h(TableCell, { className: 'text-sm' }, 
                     h(Badge, { variant: 'outline', className: 'bg-yellow-500/10 text-yellow-500 border-yellow-500/20' }, 'Exhausted'),
                     h('span', { className: 'ml-3 text-muted-foreground' }, e.label)
                  )
                )
              )
            )
          )
    )
  );
}

export class HeapMonitorChart extends Component<{}, {}> {
  private canvas: HTMLCanvasElement | null = null;
  private chart: Chart | null = null;
  private dispose: (() => void) | null = null;

  componentDidMount() {
    this.dispose = effect(() => {
      const metrics = heapMetrics.value;
      if (metrics.length === 0) {
        this.chart?.destroy();
        this.chart = null;
        return;
      }

      const labels = metrics.map(m => m.wallClock);
      const percentData = metrics.map(m => m.percent);
      const feedNotesData = metrics.map(m => m.feedNotes);

      if (this.chart) {
        this.chart.data.labels = labels;
        this.chart.data.datasets[0].data = percentData;
        this.chart.data.datasets[1].data = feedNotesData;
        this.chart.update('none');
      } else {
        this.chart = new Chart(this.canvas!, {
          type: 'line',
          data: {
            labels,
            datasets: [
              {
                label: 'Heap Usage %',
                data: percentData,
                borderColor: 'rgba(239, 68, 68, 0.8)',
                backgroundColor: 'rgba(239, 68, 68, 0.1)',
                fill: true,
                yAxisID: 'y'
              },
              {
                label: 'Feed Notes',
                data: feedNotesData,
                borderColor: 'rgba(59, 130, 246, 0.8)',
                borderDash: [5, 5],
                yAxisID: 'y2'
              }
            ]
          },
          options: {
            responsive: true,
            maintainAspectRatio: false,
            interaction: { mode: 'index', intersect: false },
            scales: {
              x: { display: false },
              y: { type: 'linear', position: 'left', min: 0, max: 100, title: { display: true, text: 'Heap %' } },
              y2: { type: 'linear', position: 'right', title: { display: true, text: 'Notes' }, grid: { drawOnChartArea: false } }
            }
          }
        });
      }
    });
  }

  componentWillUnmount() {
    this.dispose?.();
    this.chart?.destroy();
  }

  render() {
    if (heapMetrics.value.length === 0) return null;
    return h(Card, { className: 'mb-8' },
      h(CardHeader, null, h(CardTitle, null, 'Memory & Heap Monitor')),
      h(CardContent, null,
        h('div', { className: 'relative w-full', style: { height: '250px' } },
          h('canvas', { ref: (el: any) => this.canvas = el })
        )
      )
    );
  }
}

export class PerformancePanel extends Component<{}, {}> {
  render() {
    return h('div', { className: 'animate-in fade-in slide-in-from-bottom-4 duration-500 py-6 max-w-7xl mx-auto w-full' },
      h('div', { className: 'mb-8 pb-4 border-b' },
        h('h2', { className: 'text-3xl font-bold tracking-tight text-foreground' }, 'Performance Profiling'),
        h('p', { className: 'text-muted-foreground mt-2' }, 'Identify bottlenecks in background ingestion, DB queue drains, and deduplication drops.')
      ),
      h(SpanDurationChart, null),
      h(HeapMonitorChart, null),
      h(BudgetPressureAnnotations, null)
    );
  }
}
