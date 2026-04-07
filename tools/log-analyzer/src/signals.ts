import { signal, computed, effect, batch } from '@preact/signals-core';

export { signal, computed, effect, batch };

// ── Structured event types (events.jsonl) ────────────────────────────────────

export interface LogEvent {
  ts: number;           // epoch ms
  ev: string;           // event type e.g. "relay.connected"
  ch: string;           // channel e.g. "RELAY"
  tag: string;          // source tag
  sid?: string;         // span ID
  [key: string]: any;   // typed payload fields
}

export interface StartupPhase {
  phase: number;
  name: string;
  startTs: number;
  endTs?: number;
  elapsedMs?: number;
  relayCount: number;
}

export interface RelayHealth {
  url: string;
  connects: number;
  failures: number;
  latencies: number[];
  avgLatencyMs: number | null;
  p95LatencyMs: number | null;
  eoseCount: number;
  blocked: boolean;
  blockedHours: number;
  flagged: boolean;
}

export interface AuthEvent {
  ts: number;
  url: string;
  ev: string;
  message?: string;
  replayCount?: number;
}

export interface FeedMetrics {
  totalFlushes: number;
  totalEventsFlushed: number;
  totalDbCommits: number;
  totalDbRows: number;
  avgFlushMs: number | null;
  p95FlushMs: number | null;
  eoseCount: number;
  flushLatencies: number[];
}

// ── Event signals ─────────────────────────────────────────────────────────────

export const parsedEvents = signal<LogEvent[]>([]);
export const fileMode = signal<'logs' | 'events'>('logs');

export interface LogEntry {
  id: number;
  time: string;
  level: string;
  channel: string;
  tag: string;
  message: string;
  traceId?: string;
  raw: string;
}

export interface TraceSpan {
   id: string;
   name: string;
   channel: string;
   startTimeStr: string;
   startTimeMs: number;
   endTimeMs?: number;
   data?: string;
}

export const rawLogs = signal<string>('');
export const parsedLogs = signal<LogEntry[]>([]);
export const parsedSpans = signal<TraceSpan[]>([]);
export const filterQuery = signal<string>('');
export const filterLevel = signal<string>('');
export const filterChannel = signal<string>('');
export const filterTraceId = signal<string>('');
export const renderLimit = signal<number>(1000);

export const filteredLogs = computed(() => {
  const query = filterQuery.value.toLowerCase();
  const level = filterLevel.value;
  const channel = filterChannel.value;
  const trace = filterTraceId.value;

  return parsedLogs.value.filter(log => {
    if (level && log.level !== level) return false;
    if (channel && log.channel !== channel) return false;
    if (trace && log.traceId !== trace) return false;
    if (query) {
      if (!log.message.toLowerCase().includes(query) && !log.tag.toLowerCase().includes(query)) {
        return false;
      }
    }
    return true;
  });
});

export const availableChannels = computed(() => {
  const channels = new Set<string>();
  for (const log of parsedLogs.value) {
    if (log.channel) channels.add(log.channel);
  }
  return Array.from(channels).sort();
});

export const availableLevels = computed(() => {
  const levels = new Set<string>();
  for (const log of parsedLogs.value) {
    if (log.level) levels.add(log.level);
  }
  return Array.from(levels).sort();
});

export const availableTraceIds = computed(() => {
  const traces = new Set<string>();
  for (const log of parsedLogs.value) {
    if (log.traceId) traces.add(log.traceId);
  }
  return Array.from(traces).sort();
});

export function parseLogs(text: string) {
  const lines = text.split('\n');
  const entries: LogEntry[] = [];
  const spansMap = new Map<string, TraceSpan>();
  const activeSpans = new Map<string, string>();
  let id = 0;

  function parseTime(t: string): number {
    if (!t) return 0;
    const parts = t.split(':');
    if (parts.length < 3) return 0;
    return (parseInt(parts[0], 10) * 3600 + parseInt(parts[1], 10) * 60 + parseFloat(parts[2])) * 1000;
  }

  for (const line of lines) {
    const parts = line.split(' | ');
    if (parts.length >= 5) {
      const fullMessage = parts.slice(4).join(' | ').trim();
      let traceId = undefined;
      let finalMessage = fullMessage;
      // Grouping ID inside brackets, but restrict to alphanumeric/dash/underscore so we don't accidentally grab [wss://relay.damus.io]
      const traceMatch = fullMessage.match(/^\[([a-zA-Z0-9_\-]+)\] (.*)$/);
      if (traceMatch) {
         traceId = traceMatch[1];
         finalMessage = traceMatch[2];
      }
      
      const channelSourceKey = `${parts[2].trim()}:${parts[3].trim()}`;

      if (finalMessage.startsWith("SPAN_START")) {
        const match = finalMessage.match(/SPAN_START \{([^}]+)\} (.*)/);
        if (match) {
           const sId = match[1];
           spansMap.set(sId, { id: sId, name: match[2].trim(), channel: parts[2].trim(), startTimeStr: parts[0].trim(), startTimeMs: parseTime(parts[0].trim()) });
           activeSpans.set(channelSourceKey, sId);
        }
      } else if (finalMessage.startsWith("SPAN_END")) {
        const match = finalMessage.match(/SPAN_END \{([^}]+)\} (.*?)(?: : (.*))?$/);
        if (match) {
           const span = spansMap.get(match[1]);
           if (span) {
               span.endTimeMs = parseTime(parts[0].trim());
               span.data = match[3];
               if (activeSpans.get(channelSourceKey) === match[1]) {
                   activeSpans.delete(channelSourceKey);
               }
           }
        }
      }
      
      if (!traceId) {
          traceId = activeSpans.get(channelSourceKey);
      }

      entries.push({
        id: id++,
        time: parts[0].trim(),
        level: parts[1].trim(),
        channel: parts[2].trim(),
        tag: parts[3].trim(),
        message: finalMessage,
        traceId: traceId,
        raw: line,
      });
    } else {
      if (line.trim().length > 0) {
        entries.push({
          id: id++,
          time: '',
          level: '',
          channel: '',
          tag: '',
          message: line,
          raw: line,
        });
      }
    }
  }

  // Synthesize events for Event Analysis charts so the user doesn't strictly need .jsonl
  const synthEvents: LogEvent[] = [];
  for (const log of entries) {
      if (!log.time) continue;
      const ts = parseTime(log.time);
      if (log.channel === 'RELAY') {
          if (log.tag === 'RelayHealthTracker') {
              if (log.message.includes('FLAGGED')) {
                  const url = log.message.match(/Relay (wss?:\/\/[^\s]+) FLAGGED/)?.[1] || '';
                  synthEvents.push({ ts, ev: 'relay.flagged', ch: 'RELAY', tag: log.tag, url });
              } else if (log.message.includes('AUTO-BLOCKED')) {
                  const url = log.message.match(/Relay (wss?:\/\/[^\s]+) AUTO-BLOCKED/)?.[1] || '';
                  synthEvents.push({ ts, ev: 'relay.blocked', ch: 'RELAY', tag: log.tag, url, duration_hours: 6 });
              }
          } else if (log.tag === 'RelayConnectionState') {
              if (log.message.includes('Disconnected') && log.message.match(/\[(wss?:\/\/[^\s]+)\]/)) {
                  const url = log.message.match(/\[(wss?:\/\/[^\s]+)\]/)?.[1] || '';
                  synthEvents.push({ ts, ev: 'relay.failed', ch: 'RELAY', tag: log.tag, url });
              }
          }
      } else if (log.channel === 'STARTUP') {
          if (log.message.includes('advancing to Phase')) {
             const m = log.message.match(/Phase (\d+)/);
             if (m) synthEvents.push({ ts, ev: 'startup.phase_start', ch: 'STARTUP', tag: log.tag, phase: parseInt(m[1]) });
          } else if (log.tag === 'StartupOrchestrator' && log.message.match(/Phase (\d+)/)) {
             const m = log.message.match(/Phase (\d+)/);
             const isStart = log.message.includes('fetching') || log.message.includes('started') || log.message.includes('launched');
             if (isStart) synthEvents.push({ ts, ev: 'startup.phase_start', ch: 'STARTUP', tag: log.tag, phase: parseInt(m?.[1]||'0') });
             const isEnd = log.message.includes('complete');
             if (isEnd) synthEvents.push({ ts, ev: 'startup.phase_end', ch: 'STARTUP', tag: log.tag, phase: parseInt(m?.[1]||'0') });
          }
      }
  }

  batch(() => {
    rawLogs.value = text;
    parsedLogs.value = entries;
    parsedSpans.value = Array.from(spansMap.values());
    if (synthEvents.length > 0) {
      parsedEvents.value = synthEvents; // Populate fallback
    }
    filterQuery.value = '';
    filterLevel.value = '';
    filterChannel.value = '';
    filterTraceId.value = '';
    renderLimit.value = 1000;
  });
}

export function clearLogs() {
  batch(() => {
    rawLogs.value = '';
    parsedLogs.value = [];
    parsedSpans.value = [];
    parsedEvents.value = [];
    fileMode.value = 'logs';
  });
}

// ── JSONL event parser ────────────────────────────────────────────────────────

export function parseEvents(text: string) {
  const lines = text.split('\n');
  const events: LogEvent[] = [];
  for (const line of lines) {
    const t = line.trim();
    if (!t.startsWith('{')) continue;
    try {
      const obj = JSON.parse(t);
      if (obj.ev && obj.ts) events.push(obj as LogEvent);
    } catch {}
  }
  batch(() => {
    parsedEvents.value = events;
    fileMode.value = 'events';
    // Clear any plain-text log state so the UI stays coherent
    rawLogs.value = '';
    parsedLogs.value = [];
    parsedSpans.value = [];
  });
}

// ── Derived: startup phases ───────────────────────────────────────────────────

export const startupPhases = computed<StartupPhase[]>(() => {
  const events = parsedEvents.value;
  const starts = new Map<number, LogEvent>();
  const phases: StartupPhase[] = [];

  for (const e of events) {
    if (e.ev === 'startup.reset') { starts.clear(); }
    else if (e.ev === 'startup.phase_start') {
      const ph = Number(e.phase ?? -1);
      if (ph >= 0 && !starts.has(ph)) starts.set(ph, e);
    } else if (e.ev === 'startup.phase_end') {
      const ph = Number(e.phase ?? -1);
      const start = starts.get(ph);
      if (start) {
        const elapsed = e.ts - start.ts;
        phases.push({
          phase: ph,
          name: String(e.name ?? start.name ?? `Phase ${ph}`),
          startTs: start.ts,
          endTs: e.ts,
          elapsedMs: elapsed,
          relayCount: Number(start.relay_count ?? 0),
        });
      }
    }
  }

  // Include in-progress phases (started but no end)
  for (const [ph, start] of starts) {
    if (!phases.find(p => p.phase === ph)) {
      phases.push({
        phase: ph,
        name: String(start.name ?? `Phase ${ph}`),
        startTs: start.ts,
        endTs: undefined,
        elapsedMs: undefined,
        relayCount: Number(start.relay_count ?? 0),
      });
    }
  }

  return phases.sort((a, b) => a.phase - b.phase);
});

// ── Derived: relay health ─────────────────────────────────────────────────────

function pct(arr: number[], p: number): number {
  if (!arr.length) return 0;
  const s = [...arr].sort((a, b) => a - b);
  return s[Math.max(0, Math.floor(s.length * p / 100) - 1)];
}

export const relayHealthMap = computed<RelayHealth[]>(() => {
  const map = new Map<string, RelayHealth>();
  const get = (url: string): RelayHealth => {
    if (!map.has(url)) map.set(url, {
      url, connects: 0, failures: 0, latencies: [],
      avgLatencyMs: null, p95LatencyMs: null,
      eoseCount: 0, blocked: false, blockedHours: 6, flagged: false,
    });
    return map.get(url)!;
  };

  for (const e of parsedEvents.value) {
    const url = String(e.url ?? '');
    if (!url) continue;
    switch (e.ev) {
      case 'relay.connecting':  get(url).connects++; break;
      case 'relay.connected': {
        const lat = Number(e.latency_ms);
        if (lat > 0) get(url).latencies.push(lat);
        break;
      }
      case 'relay.failed':    get(url).failures++; break;
      case 'relay.flagged':   get(url).flagged = true; break;
      case 'relay.unflagged': get(url).flagged = false; break;
      case 'relay.blocked':   { get(url).blocked = true; get(url).blockedHours = Number(e.duration_hours ?? 6); break; }
      case 'relay.unblocked': get(url).blocked = false; break;
      case 'sub.eose':        get(url).eoseCount++; break;
    }
  }

  for (const r of map.values()) {
    if (r.latencies.length) {
      r.avgLatencyMs = Math.round(r.latencies.reduce((a, b) => a + b, 0) / r.latencies.length);
      r.p95LatencyMs = pct(r.latencies, 95);
    }
  }

  return Array.from(map.values()).sort((a, b) => b.connects - a.connects);
});

// ── Derived: auth events ──────────────────────────────────────────────────────

export const authEvents = computed<AuthEvent[]>(() =>
  parsedEvents.value
    .filter(e => e.ev.startsWith('auth.'))
    .map(e => ({
      ts: e.ts,
      url: String(e.url ?? ''),
      ev: e.ev,
      message: e.message ? String(e.message) : undefined,
      replayCount: e.replay_count != null ? Number(e.replay_count) : undefined,
    }))
);

// ── Derived: feed metrics ─────────────────────────────────────────────────────

export const feedMetrics = computed<FeedMetrics>(() => {
  let totalFlushes = 0, totalEventsFlushed = 0, totalDbCommits = 0, totalDbRows = 0, eoseCount = 0;
  const flushLatencies: number[] = [];

  for (const e of parsedEvents.value) {
    if (e.ev === 'feed.flush') {
      totalFlushes++;
      totalEventsFlushed += Number(e.events ?? 0);
      const lat = Number(e.elapsed_ms ?? 0);
      if (lat > 0) flushLatencies.push(lat);
    } else if (e.ev === 'feed.batch_commit') {
      totalDbCommits++;
      totalDbRows += Number(e.rows ?? 0);
    } else if (e.ev === 'sub.eose') {
      eoseCount++;
    }
  }

  return {
    totalFlushes, totalEventsFlushed, totalDbCommits, totalDbRows,
    avgFlushMs: flushLatencies.length ? Math.round(flushLatencies.reduce((a, b) => a + b, 0) / flushLatencies.length) : null,
    p95FlushMs: flushLatencies.length ? pct(flushLatencies, 95) : null,
    eoseCount,
    flushLatencies,
  };
});
