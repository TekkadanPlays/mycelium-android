import { Component } from 'inferno';
import { createElement as h } from 'inferno-create-element';
import { effect } from '@preact/signals-core';
import { Chart, LineController, LineElement, PointElement, BarController, BarElement, LinearScale, CategoryScale, Tooltip as ChartTooltip, ScatterController, Filler } from 'chart.js';
import { feedInspectorData, type FeedInspectorData, heapMetrics } from '../signals';
import { Card, CardHeader, CardTitle, CardContent, Badge, Table, TableHeader, TableRow, TableHead, TableBody, TableCell } from 'blazecn';

// Register Chart.js components
Chart.register(LineController, LineElement, PointElement, BarController, BarElement, LinearScale, CategoryScale, ChartTooltip, ScatterController, Filler);

export class HomeFeedInspector extends Component<{}, {}> {
  private timelineCanvasObj: HTMLCanvasElement | null = null;
  private ingestionCanvasObj: HTMLCanvasElement | null = null;
  private throughputCanvasObj: HTMLCanvasElement | null = null;
  private timelineChart: Chart | null = null;
  private ingestionChart: Chart | null = null;
  private throughputChart: Chart | null = null;
  private dispose: (() => void) | null = null;

  componentDidMount() {
    this.dispose = effect(() => {
      const data = feedInspectorData.value;
      if (!data || data.timeline.length === 0) {
        this.timelineChart?.destroy();
        this.timelineChart = null;
        this.ingestionChart?.destroy();
        this.ingestionChart = null;
        this.throughputChart?.destroy();
        this.throughputChart = null;
        return;
      }
      this.updateCharts(data);
    });
  }

  componentWillUnmount() {
    this.dispose?.();
    this.timelineChart?.destroy();
    this.ingestionChart?.destroy();
    this.throughputChart?.destroy();
  }

  /**
   * Build or update the two core charts: timeline and ingestion activity.
   * Uses only fields that exist in FeedInspectorData.
   */
  updateCharts(data: FeedInspectorData) {
    if (!this.timelineCanvasObj || !this.ingestionCanvasObj) return;

    // ---------- Timeline Chart ----------
    const timelineLabels = data.timeline.map(d => d.wallClock);
    const finalNotes = data.timeline.map(d => d.finalNotes);
    const scrollDepth = data.timeline.map(d => d.scrollDepthHours);
    const cursorJumpMarkers = data.timeline.map(d => d.isCursorJump ? d.scrollDepthHours : null);

    if (this.timelineChart) {
      this.timelineChart.data.labels = timelineLabels;
      this.timelineChart.data.datasets[0].data = finalNotes;
      this.timelineChart.data.datasets[1].data = scrollDepth;
      this.timelineChart.data.datasets[2].data = cursorJumpMarkers as any;
      this.timelineChart.update('none');
    } else {
      this.timelineChart = new Chart(this.timelineCanvasObj, {
        type: 'line',
        data: {
          labels: timelineLabels,
          datasets: [
            {
              label: 'Visible Note Count',
              data: finalNotes,
              borderColor: 'rgba(34, 197, 94, 0.9)',
              backgroundColor: 'rgba(34, 197, 94, 0.1)',
              borderWidth: 2,
              fill: true,
              pointRadius: 0,
              yAxisID: 'y'
            },
            {
              label: 'Scroll Depth (hours ago)',
              data: scrollDepth,
              borderColor: 'rgba(239, 68, 68, 0.8)',
              borderWidth: 2,
              borderDash: [5, 5],
              pointRadius: 0,
              yAxisID: 'y2'
            },
            {
              type: 'line',
              showLine: false,
              label: 'Cursor Jumps',
              data: cursorJumpMarkers,
              backgroundColor: 'rgba(168, 85, 247, 1)',
              borderColor: 'rgba(168, 85, 247, 1)',
              pointStyle: 'star',
              pointRadius: 6,
              yAxisID: 'y2'
            }
          ]
        },
        options: {
          responsive: true,
          maintainAspectRatio: false,
          animation: { duration: 500, easing: 'easeOutQuart' },
          interaction: { mode: 'index', intersect: false },
          plugins: {
            legend: {
              display: true,
              position: 'top',
              labels: { boxWidth: 10, usePointStyle: true, font: { size: 11, family: 'Inter, sans-serif' }, color: '#a1a1aa' }
            },
            tooltip: {
              backgroundColor: 'rgba(9, 9, 11, 0.9)',
              titleFont: { size: 13, family: 'Inter, sans-serif' },
              bodyFont: { size: 12, family: 'Inter, sans-serif' },
              padding: 10,
              cornerRadius: 8,
              borderColor: 'rgba(255,255,255,0.1)',
              borderWidth: 1
            }
          },
          scales: {
            x: {
              ticks: { maxTicksLimit: 12, maxRotation: 0, color: '#71717a', font: { size: 10 } },
              grid: { display: false }
            },
            y: {
              type: 'linear',
              position: 'left',
              title: { display: true, text: 'Note Count', color: '#4ade80', font: { size: 11 } },
              ticks: { color: '#4ade80', font: { size: 10 } },
              border: { dash: [4, 4] },
              grid: { color: 'rgba(255, 255, 255, 0.05)' }
            },
            y2: {
              type: 'linear',
              position: 'right',
              title: { display: true, text: 'Hours Ago', color: '#f87171', font: { size: 11 } },
              grid: { drawOnChartArea: false },
              ticks: { color: '#f87171', font: { size: 10 } }
            }
          }
        }
      });
    }

    // ---------- Ingestion Chart ----------
    const ingestionLabels = data.ingestion.map(d => d.wallClock);
    const flushedTotal = data.ingestion.map(d => d.flushedTotal);
    const flushedToFeed = data.ingestion.map(d => d.flushedToFeed);

    if (this.ingestionChart) {
      this.ingestionChart.data.labels = ingestionLabels;
      this.ingestionChart.data.datasets[0].data = flushedTotal;
      this.ingestionChart.data.datasets[1].data = flushedToFeed;
      this.ingestionChart.update('none');
    } else {
      this.ingestionChart = new Chart(this.ingestionCanvasObj, {
        type: 'bar',
        data: {
          labels: ingestionLabels,
          datasets: [
            {
              label: 'Flushed Total',
              data: flushedTotal,
              backgroundColor: 'rgba(59, 130, 246, 0.6)',
              borderColor: 'rgba(59, 130, 246, 1)'
            },
            {
              label: 'Flushed To Feed',
              data: flushedToFeed,
              backgroundColor: 'rgba(234, 179, 8, 0.6)',
              borderColor: 'rgba(234, 179, 8, 1)'
            }
          ]
        },
        options: {
          responsive: true,
          maintainAspectRatio: false,
          animation: { duration: 400, easing: 'easeOutQuart' },
          interaction: { mode: 'index', intersect: false },
          plugins: {
            legend: {
              display: true,
              position: 'top',
              labels: { boxWidth: 10, usePointStyle: true, font: { size: 11, family: 'Inter, sans-serif' }, color: '#a1a1aa' }
            },
            tooltip: {
              backgroundColor: 'rgba(9, 9, 11, 0.9)',
              titleFont: { size: 13, family: 'Inter, sans-serif' },
              bodyFont: { size: 12, family: 'Inter, sans-serif' },
              padding: 10,
              cornerRadius: 8,
              borderColor: 'rgba(255,255,255,0.1)',
              borderWidth: 1
            }
          },
          scales: {
            x: {
              stacked: false,
              ticks: { maxTicksLimit: 12, maxRotation: 0, color: '#71717a', font: { size: 10 } },
              grid: { display: false }
            },
            y: {
              stacked: false,
              title: { display: true, text: 'Events / Second', color: '#71717a', font: { size: 11 } },
              ticks: { color: '#a1a1aa', font: { size: 10 } },
              border: { dash: [4, 4] },
              grid: { color: 'rgba(255, 255, 255, 0.05)' }
            }
          }
        }
      });
    }

    // ---------- Throughput & Heap Chart ----------
    if (this.throughputCanvasObj && data.throughputTimeline) {
       const throughputData = data.throughputTimeline;
       const tpsLabels = throughputData.map(d => d.wallClock);
       const tpsValues = throughputData.map(d => d.eventsPerSec);
       
       // Match heap % by nearest timestamp
       const heapMemory = heapMetrics.value;
       const heapValues = throughputData.map(d => {
          if (heapMemory.length === 0) return 0;
          let closest = heapMemory[0];
          let minDiff = Math.abs(closest.ts - d.ts);
          for (const h of heapMemory) {
             const diff = Math.abs(h.ts - d.ts);
             if (diff < minDiff) { minDiff = diff; closest = h; }
          }
          // Only use if within 30 seconds
          return minDiff < 30000 ? closest.percent : 0;
       });

       if (this.throughputChart) {
          this.throughputChart.data.labels = tpsLabels;
          this.throughputChart.data.datasets[0].data = tpsValues;
          this.throughputChart.data.datasets[1].data = heapValues;
          this.throughputChart.update('none');
       } else {
          this.throughputChart = new Chart(this.throughputCanvasObj, {
            type: 'line',
            data: {
              labels: tpsLabels,
              datasets: [
                {
                  label: 'Ingestion (ev/sec)',
                  data: tpsValues,
                  borderColor: 'rgba(59, 130, 246, 0.8)',
                  backgroundColor: 'rgba(59, 130, 246, 0.1)',
                  yAxisID: 'y',
                  fill: true,
                  pointRadius: 0
                },
                {
                  label: 'Heap %',
                  data: heapValues,
                  borderColor: 'rgba(239, 68, 68, 0.8)',
                  borderDash: [5, 5],
                  yAxisID: 'y2',
                  pointRadius: 0
                }
              ]
            },
            options: {
              responsive: true,
              maintainAspectRatio: false,
              interaction: { mode: 'index', intersect: false },
              scales: {
                x: { display: false },
                y: { type: 'linear', position: 'left', title: { display: true, text: 'ev/s' } },
                y2: { type: 'linear', position: 'right', min: 0, max: 100, title: { display: true, text: 'Heap %' }, grid: { drawOnChartArea: false } }
              }
            }
          });
       }
    }
  }

  render() {
    const data = feedInspectorData.value;
    if (!data || data.timeline.length === 0) {
      return h('div', { className: 'w-full flex flex-col items-center justify-center py-32 text-muted-foreground' },
        h('div', { className: 'text-4xl mb-4 opacity-50' }, '🧐'),
        h('h2', { className: 'text-xl font-medium tracking-tight text-foreground/80' }, 'No Feed Telemetry Detected'),
        h('p', { className: 'text-sm mt-2 max-w-md text-center opacity-70' }, 'Load a diagnostic log export containing FEED channel logs to activate timeline diagnostics.')
      );
    }

    return h('div', { className: 'w-full animate-in fade-in slide-in-from-bottom-4 duration-500 py-6' },
      h('div', { className: 'w-full max-w-7xl mx-auto space-y-6' },
        // Header
        h('div', { className: 'px-2 pb-2' },
          h('h2', { className: 'text-2xl font-bold tracking-tight bg-gradient-to-r from-primary to-primary/60 bg-clip-text text-transparent' }, 'Feed Engine Inspector'),
          h('p', { className: 'text-sm text-muted-foreground mt-1' }, 'Deep diagnostics mapping pagination latency, event throughput, and lifecycle bottlenecks.')
        ),
        // Timeline Chart (full width)
        h('div', { className: 'bg-card/40 backdrop-blur-md border border-border/50 shadow-xl rounded-2xl p-5 transition-all hover:bg-card/60' },
          h('div', { className: 'mb-4 flex items-center gap-3' },
            h('div', { className: 'flex h-8 w-8 items-center justify-center rounded-lg bg-green-500/10 text-green-500' },
              h('span', { className: 'material-symbols-rounded' }, 'show_chart')
            ),
            h('div', null,
              h('h3', { className: 'text-base font-semibold' }, 'Feed State Timeline'),
              h('span', { className: 'text-xs text-muted-foreground' }, 'Note count vs scroll depth with cursor jumps')
            )
          ),
          h('div', { className: 'relative w-full' },
            h('canvas', { ref: (el: any) => this.timelineCanvasObj = el, style: { height: '300px' } })
          )
        ),
        // Two‑column row: Ingestion chart & Key Events
        h('div', { className: 'grid grid-cols-1 lg:grid-cols-2 gap-6' },
          // Ingestion Activity
          h('div', { className: 'space-y-6' },
             h('div', { className: 'bg-card/40 backdrop-blur-md border border-border/50 shadow-xl rounded-2xl p-5 transition-all hover:bg-card/60' },
               h('div', { className: 'mb-4 flex items-center gap-3' },
                 h('div', { className: 'flex h-8 w-8 items-center justify-center rounded-lg bg-blue-500/10 text-blue-500' },
                   h('span', { className: 'material-symbols-rounded' }, 'bar_chart')
                 ),
                 h('div', null,
                   h('h3', { className: 'text-base font-semibold' }, 'Ingestion Activity'),
                   h('span', { className: 'text-xs text-muted-foreground' }, 'Flushed events over time')
                 )
               ),
               h('div', { className: 'relative w-full min-h-[220px]' },
                 h('canvas', { ref: (el: any) => this.ingestionCanvasObj = el, style: { height: '100%' } })
               )
             ),
             h('div', { className: 'bg-card/40 backdrop-blur-md border border-border/50 shadow-xl rounded-2xl p-5 transition-all hover:bg-card/60' },
               h('div', { className: 'mb-4 flex items-center gap-3' },
                 h('div', { className: 'flex h-8 w-8 items-center justify-center rounded-lg bg-purple-500/10 text-purple-500' },
                   h('span', { className: 'material-symbols-rounded' }, 'speed')
                 ),
                 h('div', null,
                   h('h3', { className: 'text-base font-semibold' }, 'Throughput & Heap %'),
                   h('span', { className: 'text-xs text-muted-foreground' }, 'Events/sec vs Memory usage')
                 )
               ),
               h('div', { className: 'relative w-full min-h-[200px]' },
                 h('canvas', { ref: (el: any) => this.throughputCanvasObj = el, style: { height: '100%' } })
               )
             )
          ),
          // Key Events List
          h('div', { className: 'bg-card/40 backdrop-blur-md border border-border/50 shadow-xl rounded-2xl p-5 overflow-y-auto max-h-[500px] transition-all hover:bg-card/60' },
            h('div', { className: 'mb-4 flex items-center gap-3' },
              h('div', { className: 'flex h-8 w-8 items-center justify-center rounded-lg bg-rose-500/10 text-rose-500' },
                h('span', { className: 'material-symbols-rounded' }, 'notifications_active')
              ),
              h('div', null,
                h('h3', { className: 'text-base font-semibold' }, 'Event Monitor'),
                h('span', { className: 'text-xs text-muted-foreground' }, 'Critical pipeline checkpoints')
              )
            ),
            h('div', { className: 'space-y-2.5' },
              data.keyEvents.map((evt, idx) => {
                let icon = 'info';
                let badgeClass = 'bg-gray-200/20 text-gray-600';
                switch (evt.type) {
                  case 'cursor_jump':
                    icon = 'flight_takeoff';
                    badgeClass = 'bg-purple-500/20 text-purple-600';
                    break;
                  case 'display_emit':
                    icon = 'publish';
                    badgeClass = 'bg-green-500/20 text-green-600';
                    break;
                  case 'budget_pressure':
                    icon = 'local_fire_department';
                    badgeClass = 'bg-orange-500/20 text-orange-600';
                    break;
                  case 'deferred_enrichment':
                    icon = 'auto_awesome';
                    badgeClass = 'bg-blue-500/20 text-blue-600';
                    break;
                  default:
                    icon = 'info';
                }
                return h('div', { key: idx, className: `flex items-center gap-3 p-2 rounded-xl border ${badgeClass}` },
                  h('div', { className: 'flex h-6 w-6 items-center justify-center rounded-md bg-white/10' },
                    h('span', { className: 'material-symbols-rounded text-sm' }, icon)
                  ),
                  h('div', { className: 'flex-1' },
                    h('div', { className: 'font-medium' }, evt.label),
                    h('div', { className: 'text-xs text-muted-foreground' }, evt.time)
                  )
                );
              })
            )
          )
        ),
        // Custom tables and grids
        h('div', { className: 'grid grid-cols-1 lg:grid-cols-2 gap-6 mt-6' },
           h(Card, { className: 'bg-card/40 backdrop-blur-md shadow-xl' },
             h(CardHeader, null, h(CardTitle, { className: 'text-base' }, 'Pipeline Display Funnel')),
             h(CardContent, null, 
               h('div', { className: 'overflow-y-auto max-h-[300px]' },
                 h(Table, null,
                   h(TableHeader, null, 
                     h(TableRow, null,
                       h(TableHead, null, 'Time'),
                       h(TableHead, { className: 'text-right' }, 'Total'),
                       h(TableHead, { className: 'text-right' }, 'Relay Filter'),
                       h(TableHead, { className: 'text-right' }, 'Window Filter'),
                       h(TableHead, { className: 'text-right text-primary' }, 'Final')
                     )
                   ),
                   h(TableBody, null,
                     data.displayFilters?.map((df, i) => h(TableRow, { key: i },
                       h(TableCell, { className: 'font-mono text-xs' }, df.wallClock),
                       h(TableCell, { className: 'text-right text-xs text-muted-foreground' }, df.total),
                       h(TableCell, { className: 'text-right text-xs' }, df.relay),
                       h(TableCell, { className: 'text-right text-xs' }, df.window),
                       h(TableCell, { className: 'text-right text-xs font-bold text-primary' }, df.final)
                     ))
                   )
                 )
               )
             )
           ),
           h(Card, { className: 'bg-card/40 backdrop-blur-md shadow-xl' },
             h(CardHeader, null, h(CardTitle, { className: 'text-base' }, 'Event Batches (Per-Relay Comp)')),
             h(CardContent, null, 
               h('div', { className: 'overflow-y-auto max-h-[300px]' },
                 h(Table, null,
                   h(TableHeader, null, 
                     h(TableRow, null,
                       h(TableHead, null, 'Time'),
                       h(TableHead, { className: 'text-right' }, 'Events'),
                       h(TableHead, { className: 'text-right' }, 'Unique'),
                       h(TableHead, { className: 'text-right' }, 'Dupes'),
                       h(TableHead, null, 'Relay Dist')
                     )
                   ),
                   h(TableBody, null,
                     data.batches?.map((b, i) => h(TableRow, { key: i },
                       h(TableCell, { className: 'font-mono text-xs' }, b.wallClock),
                       h(TableCell, { className: 'text-right text-xs font-bold' }, b.events),
                       h(TableCell, { className: 'text-right text-xs text-green-500' }, b.unique),
                       h(TableCell, { className: 'text-right text-xs text-orange-500' }, b.dupes),
                       h(TableCell, { className: 'text-xs text-muted-foreground truncate max-w-[150px]', title: b.relaysInfo }, b.relaysInfo)
                     ))
                   )
                 )
               )
             )
           )
        ),
        h(Card, { className: 'bg-card/40 backdrop-blur-md shadow-xl mt-6' },
             h(CardHeader, null, h(CardTitle, { className: 'text-base' }, 'Phase 5: Deep History Tracker')),
             h(CardContent, null, 
               h('div', { className: 'overflow-y-auto max-h-[300px]' },
                 h(Table, null,
                   h(TableHeader, null, 
                     h(TableRow, null,
                       h(TableHead, null, 'Window Label'),
                       h(TableHead, { className: 'text-right' }, 'Total Events'),
                       h(TableHead, null, 'Breakdown')
                     )
                   ),
                   h(TableBody, null,
                     data.deepHistory?.map((dh, i) => h(TableRow, { key: i },
                       h(TableCell, { className: 'font-medium text-xs' }, dh.windowLabel),
                       h(TableCell, { className: 'text-right text-xs font-bold' }, dh.totalEvents),
                       h(TableCell, { className: 'text-xs flex flex-wrap gap-1' }, 
                         dh.groups.map(g => h(Badge, { variant: 'outline', className: 'text-[10px] px-1 py-0' }, `${g.name}: ${g.count}`))
                       )
                     ))
                   )
                 )
               )
             )
        )
      )
    );
  }
}
