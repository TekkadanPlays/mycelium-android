import { Component } from 'inferno';
import { createElement as h } from 'inferno-create-element';
import { effect } from '@preact/signals-core';
import {
  startupPhases, relayHealthMap, authEvents, feedMetrics,
  type StartupPhase, type RelayHealth, type AuthEvent, type FeedMetrics,
} from '../signals';

// ── Signal bridge ─────────────────────────────────────────────────────────────

class SignalBridge extends Component<{ children: () => any }, {}> {
  private dispose: (() => void) | null = null;
  private _mounted = false;
  componentDidMount() {
    this._mounted = true;
    this.dispose = effect(() => {
      this.props.children();
      if (this._mounted) this.forceUpdate();
    });
  }
  componentWillUnmount() {
    this._mounted = false;
    this.dispose?.();
  }
  render() { return this.props.children(); }
}
function S(fn: () => any) { return h(SignalBridge, { children: fn }); }

// ── Helpers ───────────────────────────────────────────────────────────────────

function fmtMs(ms: number | null | undefined): string {
  if (ms == null) return '—';
  if (ms < 1000) return `${ms}ms`;
  return `${(ms / 1000).toFixed(2)}s`;
}

function fmtTs(epochMs: number): string {
  const d = new Date(epochMs);
  return `${d.getHours().toString().padStart(2,'0')}:${d.getMinutes().toString().padStart(2,'0')}:${d.getSeconds().toString().padStart(2,'0')}.${d.getMilliseconds().toString().padStart(3,'0')}`;
}

function pill(text: string, cls: string) {
  return h('span', { className: `inline-flex items-center px-2 py-0.5 rounded-full text-[10px] font-bold uppercase tracking-wider ${cls}` }, text);
}

function SectionHeader(title: string, badge?: string) {
  return h('div', { className: 'flex items-center gap-3 mb-4' },
    h('h2', { className: 'text-sm font-bold uppercase tracking-wider text-muted-foreground' }, title),
    badge ? h('span', { className: 'bg-muted text-muted-foreground text-[10px] px-2 py-0.5 rounded-full font-semibold' }, badge) : null,
  );
}

function StatCard(label: string, value: string, sub?: string, highlight?: boolean) {
  return h('div', { className: `rounded-xl border bg-card px-5 py-4 flex flex-col gap-1 shadow-sm ${highlight ? 'border-primary/40 bg-primary/5' : ''}` },
    h('span', { className: 'text-[11px] text-muted-foreground font-medium uppercase tracking-wider' }, label),
    h('span', { className: `text-2xl font-bold tabular-nums ${highlight ? 'text-primary' : 'text-foreground'}` }, value),
    sub ? h('span', { className: 'text-[11px] text-muted-foreground' }, sub) : null,
  );
}

// ── Startup Waterfall ─────────────────────────────────────────────────────────

const PHASE_COLORS = [
  'bg-purple-500', 'bg-blue-500', 'bg-emerald-500',
  'bg-amber-500', 'bg-rose-500', 'bg-cyan-500',
];
const PHASE_TRACK_COLORS = [
  'bg-purple-500/20', 'bg-blue-500/20', 'bg-emerald-500/20',
  'bg-amber-500/20', 'bg-rose-500/20', 'bg-cyan-500/20',
];
const PHASE_TEXT_COLORS = [
  'text-purple-600 dark:text-purple-400', 'text-blue-600 dark:text-blue-400',
  'text-emerald-600 dark:text-emerald-400', 'text-amber-600 dark:text-amber-400',
  'text-rose-600 dark:text-rose-400', 'text-cyan-600 dark:text-cyan-400',
];

class StartupWaterfall extends Component<{}, {}> {
  private dispose: (() => void) | null = null;
  componentDidMount() { this.dispose = effect(() => { startupPhases.value; this.forceUpdate(); }); }
  componentWillUnmount() { this.dispose?.(); }

  render() {
    const phases = startupPhases.value;
    if (phases.length === 0) {
      return h('div', { className: 'rounded-xl border bg-card p-6' },
        SectionHeader('Startup Phases'),
        h('p', { className: 'text-sm text-muted-foreground' }, 'No startup.phase events found in this file.'),
      );
    }

    const tStart = phases[0].startTs;
    const tEnd = Math.max(...phases.map(p => p.endTs ?? (p.startTs + (p.elapsedMs ?? 0))));
    const totalSpan = Math.max(tEnd - tStart, 1);

    const completedPhases = phases.filter(p => p.elapsedMs != null);
    const totalElapsed = completedPhases.length > 0
      ? (Math.max(...completedPhases.map(p => (p.endTs ?? p.startTs) - tStart)))
      : null;

    return h('div', { className: 'rounded-xl border bg-card p-6 shadow-sm' },
      SectionHeader('Startup Phases', `${phases.length} phases`),

      // Summary stats row
      h('div', { className: 'grid grid-cols-2 sm:grid-cols-4 gap-3 mb-6' },
        StatCard('Total Duration', fmtMs(totalElapsed), 'from phase 0 start'),
        StatCard('Phases Tracked', String(phases.length), `${completedPhases.length} completed`),
        ...completedPhases.length > 0 ? [
          StatCard('Slowest Phase',
            fmtMs(Math.max(...completedPhases.map(p => p.elapsedMs!))),
            completedPhases.find(p => p.elapsedMs === Math.max(...completedPhases.map(q => q.elapsedMs!)))?.name ?? ''),
          StatCard('Relay Count', String(phases[0].relayCount || '—'), 'at phase 0'),
        ] : [],
      ),

      // Gantt bars
      h('div', { className: 'flex flex-col gap-2 min-w-[400px]' },
        ...phases.map((p, i) => {
          const colorIdx = i % PHASE_COLORS.length;
          const leftPct = ((p.startTs - tStart) / totalSpan) * 100;
          const widthPct = p.elapsedMs != null
            ? Math.max(0.5, (p.elapsedMs / totalSpan) * 100)
            : Math.max(0.5, ((tEnd - p.startTs) / totalSpan) * 100);
          const ongoing = p.elapsedMs == null;

          return h('div', { key: p.phase, className: 'grid items-center gap-3', style: { gridTemplateColumns: '8rem 1fr 5rem' } },
            // Phase label
            h('div', { className: 'flex items-center gap-1.5 min-w-0' },
              h('span', { className: `size-2 rounded-full shrink-0 ${PHASE_COLORS[colorIdx]}` }),
              h('span', { className: `text-[11px] font-semibold truncate ${PHASE_TEXT_COLORS[colorIdx]}`, title: p.name }, `P${p.phase}: ${p.name}`),
            ),
            // Bar track
            h('div', { className: 'relative h-6 bg-muted rounded-md overflow-hidden' },
              h('div', {
                className: `absolute top-0 h-full rounded-md transition-all ${PHASE_TRACK_COLORS[colorIdx]} border ${ongoing ? 'border-dashed' : ''} border-current/20`,
                style: { left: `${leftPct}%`, width: `${widthPct}%` }
              }),
            ),
            // Duration
            h('div', { className: 'text-right' },
              ongoing
                ? pill('running', 'bg-amber-500/20 text-amber-700 dark:text-amber-400')
                : h('span', { className: `text-xs font-mono font-semibold tabular-nums ${PHASE_TEXT_COLORS[colorIdx]}` }, fmtMs(p.elapsedMs)),
            ),
          );
        }),
      ),
    );
  }
}

// ── Relay Health Table ────────────────────────────────────────────────────────

class RelayHealthTable extends Component<{}, {}> {
  private dispose: (() => void) | null = null;
  componentDidMount() { this.dispose = effect(() => { relayHealthMap.value; this.forceUpdate(); }); }
  componentWillUnmount() { this.dispose?.(); }

  render() {
    const relays = relayHealthMap.value;
    if (relays.length === 0) {
      return h('div', { className: 'rounded-xl border bg-card p-6' },
        SectionHeader('Relay Health'),
        h('p', { className: 'text-sm text-muted-foreground' }, 'No relay events found in this file.'),
      );
    }

    const connected = relays.filter(r => r.connects > 0 && r.failures === 0 && !r.blocked);
    const degraded  = relays.filter(r => r.failures > 0 && !r.blocked && !r.flagged);
    const flagged   = relays.filter(r => r.flagged);
    const blocked   = relays.filter(r => r.blocked);

    return h('div', { className: 'rounded-xl border bg-card p-6 shadow-sm' },
      SectionHeader('Relay Health', `${relays.length} relays`),

      // Summary pills
      h('div', { className: 'flex flex-wrap gap-2 mb-5' },
        pill(`${connected.length} healthy`, 'bg-emerald-500/15 text-emerald-700 dark:text-emerald-400'),
        pill(`${degraded.length} degraded`, 'bg-amber-500/15 text-amber-700 dark:text-amber-400'),
        pill(`${flagged.length} flagged`, 'bg-orange-500/15 text-orange-700 dark:text-orange-400'),
        pill(`${blocked.length} blocked`, 'bg-destructive/15 text-destructive'),
      ),

      // Table
      h('div', { className: 'overflow-x-auto' },
        h('table', { className: 'w-full text-xs' },
          h('thead', null,
            h('tr', { className: 'border-b text-left text-muted-foreground font-semibold uppercase tracking-wider text-[10px]' },
              h('th', { className: 'py-2 pr-4 font-semibold' }, 'Relay'),
              h('th', { className: 'py-2 pr-4 text-right' }, 'Connects'),
              h('th', { className: 'py-2 pr-4 text-right' }, 'Failures'),
              h('th', { className: 'py-2 pr-4 text-right' }, 'Avg Latency'),
              h('th', { className: 'py-2 pr-4 text-right' }, 'p95 Latency'),
              h('th', { className: 'py-2 pr-4 text-right' }, 'EOSEs'),
              h('th', { className: 'py-2 text-center' }, 'Status'),
            )
          ),
          h('tbody', null,
            ...relays.map(r => {
              const successRate = r.connects > 0
                ? Math.round(((r.connects - r.failures) / r.connects) * 100)
                : 0;
              const statusEl = r.blocked
                ? pill('blocked', 'bg-destructive/15 text-destructive')
                : r.flagged
                  ? pill('flagged', 'bg-orange-500/15 text-orange-700 dark:text-orange-400')
                  : r.failures > 0
                    ? pill('degraded', 'bg-amber-500/15 text-amber-700 dark:text-amber-400')
                    : pill('ok', 'bg-emerald-500/15 text-emerald-700 dark:text-emerald-400');

              const latencyClass = r.avgLatencyMs != null
                ? r.avgLatencyMs < 200 ? 'text-emerald-600 dark:text-emerald-400'
                  : r.avgLatencyMs < 500 ? 'text-amber-600 dark:text-amber-400'
                  : 'text-destructive'
                : 'text-muted-foreground';

              return h('tr', { key: r.url, className: 'border-b border-border/40 hover:bg-muted/30 transition-colors' },
                h('td', { className: 'py-2 pr-4 font-mono text-[10px] max-w-[200px] truncate text-foreground', title: r.url }, r.url),
                h('td', { className: 'py-2 pr-4 text-right tabular-nums' }, String(r.connects)),
                h('td', { className: `py-2 pr-4 text-right tabular-nums ${r.failures > 0 ? 'text-destructive font-bold' : ''}` },
                  r.failures > 0 ? `${r.failures} (${100 - successRate}%)` : '0'
                ),
                h('td', { className: `py-2 pr-4 text-right tabular-nums font-mono ${latencyClass}` }, fmtMs(r.avgLatencyMs)),
                h('td', { className: `py-2 pr-4 text-right tabular-nums font-mono ${latencyClass}` }, fmtMs(r.p95LatencyMs)),
                h('td', { className: 'py-2 pr-4 text-right tabular-nums' }, String(r.eoseCount)),
                h('td', { className: 'py-2 text-center' }, statusEl),
              );
            })
          )
        )
      )
    );
  }
}

// ── Auth Flow ─────────────────────────────────────────────────────────────────

class AuthFlowPanel extends Component<{}, {}> {
  private dispose: (() => void) | null = null;
  componentDidMount() { this.dispose = effect(() => { authEvents.value; this.forceUpdate(); }); }
  componentWillUnmount() { this.dispose?.(); }

  render() {
    const events = authEvents.value;
    if (events.length === 0) return null;

    const challenges  = events.filter(e => e.ev === 'auth.challenge').length;
    const successes   = events.filter(e => e.ev === 'auth.success').length;
    const rejections  = events.filter(e => e.ev === 'auth.rejected').length;
    const replays     = events.filter(e => e.ev === 'auth.replay');
    const totalReplayed = replays.reduce((sum, e) => sum + (e.replayCount ?? 0), 0);

    const evColor = (ev: string) => {
      if (ev === 'auth.success') return 'text-emerald-600 dark:text-emerald-400';
      if (ev === 'auth.rejected' || ev === 'auth.sign_failed') return 'text-destructive';
      if (ev === 'auth.challenge') return 'text-blue-600 dark:text-blue-400';
      if (ev === 'auth.replay') return 'text-amber-600 dark:text-amber-400';
      return 'text-muted-foreground';
    };

    return h('div', { className: 'rounded-xl border bg-card p-6 shadow-sm' },
      SectionHeader('NIP-42 Auth Flow', `${events.length} events`),

      h('div', { className: 'grid grid-cols-2 sm:grid-cols-4 gap-3 mb-5' },
        StatCard('Challenges', String(challenges)),
        StatCard('Successes', String(successes), undefined, successes === challenges),
        StatCard('Rejections', String(rejections), undefined, rejections > 0),
        StatCard('Replayed Reqs', String(totalReplayed), `across ${replays.length} auth flows`),
      ),

      h('div', { className: 'flex flex-col gap-1 max-h-64 overflow-y-auto' },
        ...events.map((e, i) =>
          h('div', { key: i, className: 'flex items-start gap-3 py-1.5 border-b border-border/30 last:border-0 text-xs' },
            h('span', { className: 'text-muted-foreground font-mono shrink-0 w-24' }, fmtTs(e.ts)),
            h('span', { className: `font-mono font-bold shrink-0 w-28 ${evColor(e.ev)}` }, e.ev.replace('auth.', '')),
            h('span', { className: 'text-muted-foreground font-mono text-[10px] truncate', title: e.url }, e.url),
            e.message ? h('span', { className: 'text-destructive text-[10px] ml-2 shrink-0' }, e.message) : null,
            e.replayCount ? h('span', { className: 'text-amber-600 dark:text-amber-400 text-[10px] ml-2 shrink-0' }, `+${e.replayCount} replayed`) : null,
          )
        )
      )
    );
  }
}

// ── Feed Metrics ──────────────────────────────────────────────────────────────

class FeedMetricsPanel extends Component<{}, {}> {
  private dispose: (() => void) | null = null;
  componentDidMount() { this.dispose = effect(() => { feedMetrics.value; this.forceUpdate(); }); }
  componentWillUnmount() { this.dispose?.(); }

  render() {
    const m = feedMetrics.value;
    if (m.totalFlushes === 0 && m.eoseCount === 0) {
      return h('div', { className: 'rounded-xl border bg-card p-6' },
        SectionHeader('Feed Pipeline'),
        h('p', { className: 'text-sm text-muted-foreground' }, 'No feed.flush or sub.eose events found in this file.'),
      );
    }

    const avgEventsPerFlush = m.totalFlushes > 0
      ? Math.round(m.totalEventsFlushed / m.totalFlushes)
      : 0;
    const avgRowsPerCommit = m.totalDbCommits > 0
      ? Math.round(m.totalDbRows / m.totalDbCommits)
      : 0;

    return h('div', { className: 'rounded-xl border bg-card p-6 shadow-sm' },
      SectionHeader('Feed Pipeline'),

      h('div', { className: 'grid grid-cols-2 sm:grid-cols-4 gap-3 mb-5' },
        StatCard('Events Flushed', String(m.totalEventsFlushed), `${m.totalFlushes} flush cycles`),
        StatCard('Avg / Flush', fmtMs(m.avgFlushMs), `p95: ${fmtMs(m.p95FlushMs)}`,
          m.avgFlushMs != null && m.avgFlushMs > 200),
        StatCard('DB Commits', String(m.totalDbCommits), `${m.totalDbRows} rows total`),
        StatCard('EOSEs Received', String(m.eoseCount)),
      ),

      m.flushLatencies.length > 1 ? h('div', { className: 'mt-4' },
        h('div', { className: 'text-[10px] font-semibold uppercase tracking-wider text-muted-foreground mb-2' }, 'Flush Latency Distribution'),
        h('div', { className: 'flex items-end gap-px h-16 w-full' },
          ...buildHistogram(m.flushLatencies, 20).map((bucket, i) =>
            h('div', {
              key: i,
              className: 'flex-1 bg-primary/60 rounded-sm',
              style: { height: `${bucket.heightPct}%` },
              title: `${bucket.label}: ${bucket.count}`,
            })
          )
        ),
        h('div', { className: 'flex justify-between text-[9px] text-muted-foreground mt-1' },
          h('span', null, '0ms'),
          h('span', null, `${fmtMs(Math.max(...m.flushLatencies))}`),
        )
      ) : null,

      h('div', { className: 'grid grid-cols-3 gap-3 mt-4 text-xs text-muted-foreground' },
        h('div', null, h('span', { className: 'font-semibold text-foreground' }, String(avgEventsPerFlush)), ' avg events/flush'),
        h('div', null, h('span', { className: 'font-semibold text-foreground' }, String(avgRowsPerCommit)), ' avg rows/commit'),
        h('div', null, h('span', { className: 'font-semibold text-foreground' }, String(m.eoseCount)), ' total EOSEs'),
      )
    );
  }
}

function buildHistogram(values: number[], buckets: number): { label: string; count: number; heightPct: number }[] {
  if (!values.length) return [];
  const min = Math.min(...values);
  const max = Math.max(...values);
  const range = max - min || 1;
  const counts = new Array(buckets).fill(0);
  for (const v of values) {
    const idx = Math.min(buckets - 1, Math.floor(((v - min) / range) * buckets));
    counts[idx]++;
  }
  const maxCount = Math.max(...counts, 1);
  return counts.map((count, i) => ({
    label: `${Math.round(min + (i / buckets) * range)}–${Math.round(min + ((i + 1) / buckets) * range)}ms`,
    count,
    heightPct: Math.max(2, (count / maxCount) * 100),
  }));
}

// ── Root EventsView ───────────────────────────────────────────────────────────

export class EventsView extends Component<{}, {}> {
  render() {
    return h('div', { className: 'flex flex-col gap-6 py-6' },
      S(() => h(StartupWaterfall, null)),
      S(() => h(RelayHealthTable, null)),
      S(() => h(AuthFlowPanel, null)),
      S(() => h(FeedMetricsPanel, null)),
    );
  }
}
