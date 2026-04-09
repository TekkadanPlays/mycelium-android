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
export const fileMode = signal<'logs' | 'events' | 'feed'>('logs');

export interface FeedInspectorData {
  summary: {
    peakNotes: number;
    maxDepthHours: number;
    cursorJumpCount: number;
    displayEmitCount: number;
    deepHistoryWindowCount: number;
    totalEventsPersisted: number;
    outboxRelays: number;
    outboxAuthors: number;
  };
  timeline: {
    ts: number;
    wallClock: string;
    finalNotes: number;
    scrollDepthHours: number;
    isCursorJump: boolean;
  }[];
  ingestion: {
    ts: number;
    wallClock: string;
    flushedTotal: number;
    flushedToFeed: number;
    flushedReposts: number;
  }[];
  deepHistory: {
    windowLabel: string;
    startTs: number;
    endTs: number;
    totalEvents: number;
    groups: { name: string; count: number }[];
  }[];
  outbox: {
    followCount: number;
    relayCount: number;
    authorsCovered: number;
    authorsNoNip65: number;
  } | null;
  keyEvents: {
    ts: number;
    time: string;
    type: 'cursor_jump' | 'display_emit' | 'budget_pressure' | 'deferred_enrichment' | 'deep_window_complete' | 'outbox_active' | 'room_tail_routing' | 'burst_ended';
    label: string;
    value?: number;
  }[];
  displayFilters: {
    ts: number;
    wallClock: string;
    total: number;
    relay: number;
    follow: number;
    noReply: number;
    window: number;
    final: number;
    connectedRelays: number;
    followListSize: number;
  }[];
  batches: {
    ts: number;
    wallClock: string;
    events: number;
    unique: number;
    dupes: number;
    relaysInfo: string;
  }[];
  throughputTimeline: {
    ts: number;
    wallClock: string;
    eventsPerSec: number;
  }[];
}

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
    if (parts.length >= 4) {
      const isFourPart = parts[1].trim().length > 2;
      const time = parts[0].trim();
      const level = isFourPart ? 'I' : parts[1].trim();
      const channelIdx = isFourPart ? 1 : 2;
      const channel = parts[channelIdx].trim();
      const tag = parts[channelIdx + 1].trim();
      
      const fullMessage = parts.slice(channelIdx + 2).join(' | ').trim();
      let traceId = undefined;
      let finalMessage = fullMessage;
      
      const traceMatch = fullMessage.match(/^\[([a-zA-Z0-9_\-]+)\] (.*)$/);
      if (traceMatch) {
         traceId = traceMatch[1];
         finalMessage = traceMatch[2];
      }
      
      const channelSourceKey = `${channel}:${tag}`;

      if (finalMessage.startsWith("SPAN_START")) {
        const match = finalMessage.match(/SPAN_START \{([^}]+)\} (.*)/);
        if (match) {
           const sId = match[1];
           spansMap.set(sId, { id: sId, name: match[2].trim(), channel: channel, startTimeStr: time, startTimeMs: parseTime(time) });
           activeSpans.set(channelSourceKey, sId);
        }
      } else if (finalMessage.startsWith("SPAN_END")) {
        const match = finalMessage.match(/SPAN_END \{([^}]+)\} (.*?)(?: : (.*))?$/);
        if (match) {
           const span = spansMap.get(match[1]);
           if (span) {
               span.endTimeMs = parseTime(time);
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
        time: time,
        level: level,
        channel: channel,
        tag: tag,
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
              } else if (log.message.includes('Connected') && !log.message.includes('Disconnected')) {
                  const url = log.message.match(/\[(wss?:\/\/[^\s]+)\]/)?.[1] || '';
                  if (url) synthEvents.push({ ts, ev: 'relay.connected', ch: 'RELAY', tag: log.tag, url, latency_ms: 0 });
              }
          }
      } else if (log.channel === 'GENERAL' && log.tag === 'SubscriptionMultiplexer' && log.message.includes('EOSE')) {
          const m = log.message.match(/for (wss?:\/\/[^\s]+)/);
          if (m) synthEvents.push({ ts, ev: 'sub.eose', ch: 'GENERAL', tag: log.tag, url: m[1] });
      } else if (log.channel === 'AUTH' && log.message.includes('NIP-42')) {
          const url = log.message.match(/for (wss?:\/\/[^\s\]]+)/)?.[1] || '';
          synthEvents.push({ ts, ev: 'auth.challenge', ch: 'AUTH', tag: log.tag, url, message: log.message });
      } else if (log.channel === 'STARTUP') {
          if (log.message.includes('Startup complete — all phases done')) {
              synthEvents.push({ ts, ev: 'startup.phase_end', ch: 'STARTUP', tag: log.tag, phase: 4 });
          } else {
              const m = log.message.match(/Phase (\d+)([a-c])?:?\s*(.*)/);
              if (m) {
                  const phaseNum = parseInt(m[1]);
                  const subPhaseStr = m[2] ? m[1] + m[2] : m[1];
                  const content = m[3];
                  if (content.includes('fetching') || content.includes('started') || content.includes('starting') || content.includes('launched')) {
                      synthEvents.push({ ts, ev: 'startup.phase_start', ch: 'STARTUP', tag: log.tag, phase: phaseNum, subPhase: subPhaseStr });
                  }
                  if (content.includes('complete') || content.includes('advancing to')) {
                      synthEvents.push({ ts, ev: 'startup.phase_end', ch: 'STARTUP', tag: log.tag, phase: phaseNum, subPhase: subPhaseStr });
                  }
              }
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

function parseCursorDepth(dateStr: string): number {
  if (!dateStr) return 0;
  const parts = dateStr.trim().split(' ');
  if (parts.length !== 2) return 0;
  const dateParts = parts[0].split('/');
  const timeParts = parts[1].split(':');
  if (dateParts.length !== 2 || timeParts.length !== 2) return 0;
  
  const d = new Date(); // Use current year for this parsing
  d.setMonth(parseInt(dateParts[0], 10) - 1, parseInt(dateParts[1], 10));
  d.setHours(parseInt(timeParts[0], 10), parseInt(timeParts[1], 10), 0, 0);
  return d.getTime();
}

function parseTimeToEpoch(t: string): number {
  if (!t) return 0;
  const parts = t.split(':');
  if (parts.length < 3) return 0;
  return (parseInt(parts[0], 10) * 3600 + parseInt(parts[1], 10) * 60 + parseFloat(parts[2])) * 1000;
}

export const feedInspectorData = computed<FeedInspectorData>(() => {
  const empty: FeedInspectorData = {
    summary: { peakNotes: 0, maxDepthHours: 0, cursorJumpCount: 0, displayEmitCount: 0, deepHistoryWindowCount: 0, totalEventsPersisted: 0, outboxRelays: 0, outboxAuthors: 0 },
    timeline: [], ingestion: [], deepHistory: [], outbox: null, keyEvents: [],
    displayFilters: [], batches: [], throughputTimeline: []
  };

  const logs = parsedLogs.value.filter(l => l.channel === 'FEED' && l.time);
  if (logs.length === 0) return empty;

  const data: FeedInspectorData = JSON.parse(JSON.stringify(empty));
  let currentFinalNotes = 0;
  let currentScrollDepthHours = 0;
  let maxCursorTs = 0;
  let totalEventsPersisted = 0;
  let currentWindow: FeedInspectorData['deepHistory'][0] | null = null;
  let lastBudgetPressureTs = 0;

  for (const log of logs) {
    const ts = parseTimeToEpoch(log.time);
    if (!ts) continue;
    const msg = log.message;

    // displayFilter — note count snapshot
    if (msg.includes('displayFilter:')) {
      const mFinal = msg.match(/→final=(\d+)/);
      if (mFinal) {
        currentFinalNotes = parseInt(mFinal[1]);
        if (currentFinalNotes > data.summary.peakNotes) data.summary.peakNotes = currentFinalNotes;
      }
      
      const parts = msg.match(/total=(\d+).*relay=(\d+).*follow=(\d+).*noReply=(\d+).*window=(\d+).*final=(\d+).*relays=(\d+).*followList=(\d+)/);
      if (parts) {
         data.displayFilters.push({
           ts,
           wallClock: log.time,
           total: parseInt(parts[1]),
           relay: parseInt(parts[2]),
           follow: parseInt(parts[3]),
           noReply: parseInt(parts[4]),
           window: parseInt(parts[5]),
           final: parseInt(parts[6]),
           connectedRelays: parseInt(parts[7]),
           followListSize: parseInt(parts[8])
         });
      }
      data.timeline.push({ ts, wallClock: log.time, finalNotes: currentFinalNotes, scrollDepthHours: currentScrollDepthHours, isCursorJump: false });
    }

    // 🔵 BATCH: X events, Y unique, Z dupes, relays={...}
    if (msg.includes('🔵 BATCH:')) {
       const m = msg.match(/BATCH: (\d+) events, (\d+) unique, (\d+) dupes, relays=(\{.*\})/);
       if (m) {
          data.batches.push({ ts, wallClock: log.time, events: parseInt(m[1]), unique: parseInt(m[2]), dupes: parseInt(m[3]), relaysInfo: m[4] });
       }
    }
    
    // Outbox pagination: +N notes to roomTail (total=M)
    if (msg.includes('Outbox pagination: +') && msg.includes('roomTail')) {
       const m = msg.match(/\+(\d+) notes to roomTail.*total=(\d+)/);
       if (m) {
          data.keyEvents.push({ ts, time: log.time, type: 'room_tail_routing', label: `Outbox routing: +${m[1]} to scroll tail (total=${m[2]})`, value: parseInt(m[1]) });
       }
    }
    
    // Burst ended: flushed shadow list
    if (msg.includes('Burst ended: flushed shadow list')) {
       const m = msg.match(/\((\d+) notes\)/);
       data.keyEvents.push({ ts, time: log.time, type: 'burst_ended', label: `Burst ended: flushed shadow list (${m ? m[1] : 0})` });
    }

    // PipelineDiagnostics
    // 📊 Pipeline │ ingested=X (Y/s) │ flushes=Z avg=Wms │ db commits=A rows=B
    if (msg.includes('📊 Pipeline │') || msg.includes('ingested=')) {
       const m = msg.match(/ingested=\d+ \((\d+)\/s\)/);
       if (m) {
          data.throughputTimeline.push({ ts, wallClock: log.time, eventsPerSec: parseInt(m[1]) });
       }
    }

    // Cursor advanced: 04/07 18:53 → 04/06 15:18
    if (msg.includes('Cursor advanced:')) {
      const m = msg.match(/→\s+([0-9]{2}\/[0-9]{2}\s+[0-9]{2}:[0-9]{2})/);
      if (m) {
        const cursorTs = parseCursorDepth(m[1]);
        if (cursorTs > 0) {
          if (cursorTs > maxCursorTs) maxCursorTs = cursorTs;
          currentScrollDepthHours = Math.max(0, (maxCursorTs - cursorTs) / (1000 * 60 * 60));
          if (currentScrollDepthHours > data.summary.maxDepthHours) data.summary.maxDepthHours = currentScrollDepthHours;
        }
        data.summary.cursorJumpCount++;
        data.keyEvents.push({ ts, time: log.time, type: 'cursor_jump', label: `Cursor → ${m[1]} (${currentScrollDepthHours.toFixed(1)}h back)`, value: currentScrollDepthHours });
        data.timeline.push({ ts, wallClock: log.time, finalNotes: currentFinalNotes, scrollDepthHours: currentScrollDepthHours, isCursorJump: true });
      }
    }

    // Flushed X events (Y reposts): Z to feed
    if (msg.includes('Flushed') && msg.includes('to feed')) {
      const m = msg.match(/Flushed (\d+) events \((\d+) reposts\): (\d+) to feed/);
      if (m && parseInt(m[1]) > 0) {
        data.ingestion.push({ ts, wallClock: log.time, flushedTotal: parseInt(m[1]), flushedToFeed: parseInt(m[3]), flushedReposts: parseInt(m[2]) });
      }
    }

    // Event store: persisted X events
    if (msg.includes('Event store: persisted')) {
      const m = msg.match(/persisted (\d+) events/);
      if (m) totalEventsPersisted += parseInt(m[1]);
    }

    // Display emit (IDs changed) — meaningful UI update
    if (msg.includes('Display emit') && msg.includes('IDs changed')) {
      const m = msg.match(/:\s+(\d+) notes/);
      const count = m ? parseInt(m[1]) : 0;
      data.summary.displayEmitCount++;
      data.keyEvents.push({ ts, time: log.time, type: 'display_emit', label: `Feed updated → ${count} notes`, value: count });
    }

    // Budget exhaustion (debounce bursts to 2s)
    if (msg.includes('Budget') && msg.includes('quoteSlots=0/') && msg.includes('oneShot=3')) {
      if (ts - lastBudgetPressureTs > 2000) {
        data.keyEvents.push({ ts, time: log.time, type: 'budget_pressure', label: 'Budget exhausted — oneShot=3, quoteSlots=0' });
        lastBudgetPressureTs = ts;
      }
    }

    // Deferred enrichment
    if (msg.includes('Firing deferred enrichment')) {
      const m = msg.match(/for (\d+) accumulated/);
      const count = m ? parseInt(m[1]) : 0;
      data.keyEvents.push({ ts, time: log.time, type: 'deferred_enrichment', label: `Enrichment fired for ${count} notes`, value: count });
    }

    // DeepHistoryFetcher
    if (log.tag === 'DeepHistoryFetcher') {
      // Window start: "Window 2026-03-02 → 2026-04-01 — fetching 8 kind groups"
      if (msg.match(/Window \d{4}-\d{2}-\d{2}.+—/) || msg.match(/Window \d{4}-\d{2}-\d{2}.+–/)) {
        const m = msg.match(/Window (.+?)\s+[—–]/);
        if (m) currentWindow = { windowLabel: m[1].trim(), startTs: ts, endTs: ts, totalEvents: 0, groups: [] };
      }
      // Group total line: "  feed: 836 events"
      else if (currentWindow && msg.match(/^\s+\S[^:]+:\s+\d+ events/)) {
        const m = msg.match(/^\s+([^:]+):\s+(\d+) events/);
        if (m) {
          const gName = m[1].trim();
          const gCount = parseInt(m[2]);
          const ex = currentWindow.groups.find(g => g.name === gName);
          if (ex) ex.count = gCount; else currentWindow.groups.push({ name: gName, count: gCount });
        }
      }
      // Window complete
      else if (msg.includes('Window complete:')) {
        const m = msg.match(/(\d+) events persisted/);
        if (currentWindow && m) {
          currentWindow.endTs = ts;
          currentWindow.totalEvents = parseInt(m[1]);
          data.deepHistory.push(currentWindow);
          data.summary.deepHistoryWindowCount++;
          data.keyEvents.push({ ts, time: log.time, type: 'deep_window_complete', label: `Window done: ${currentWindow.windowLabel} (${m[1]} events)`, value: parseInt(m[1]) });
          currentWindow = null;
        }
      }
    }

    // OutboxFeedManager
    if (log.tag === 'OutboxFeedManager') {
      if (msg.includes('Starting outbox feed:') && !data.outbox) {
        const m = msg.match(/(\d+) follows/);
        if (m) data.outbox = { followCount: parseInt(m[1]), relayCount: 0, authorsCovered: 0, authorsNoNip65: 0 };
      } else if (msg.includes('Subscribing to') && msg.includes('outbox relays')) {
        const m = msg.match(/Subscribing to (\d+) outbox relays covering (\d+) authors/);
        if (m) {
          if (data.outbox) { data.outbox.relayCount = parseInt(m[1]); data.outbox.authorsCovered = parseInt(m[2]); }
          data.summary.outboxRelays = parseInt(m[1]);
          data.summary.outboxAuthors = parseInt(m[2]);
          data.keyEvents.push({ ts, time: log.time, type: 'outbox_active', label: `Outbox active: ${m[1]} relays, ${m[2]} authors covered`, value: parseInt(m[1]) });
        }
      } else if (msg.includes('NO outbox relay')) {
        const m = msg.match(/(\d+) followed authors have NO outbox relay/);
        if (m && data.outbox) data.outbox.authorsNoNip65 = parseInt(m[1]);
      }
    }
  }

  if (currentWindow) {
    data.deepHistory.push(currentWindow);
    data.summary.deepHistoryWindowCount++;
  }
  data.summary.totalEventsPersisted = totalEventsPersisted;
  data.keyEvents.sort((a, b) => a.ts - b.ts);
  return data;
});

// ── Dashboard navigation ──────────────────────────────────────────────────────

export const activePanel = signal<string>('overview');

// ── Session metadata ──────────────────────────────────────────────────────────

export interface SessionMetadata {
  exportDate: string;
  sessionStart: string;
  version: string;
  buildCode: number;
  sdkVersion: number;
  device: string;
  sessionDurationSec: number;
  totalLogLines: number;
  channels: string[];
}

export const sessionMetadata = computed<SessionMetadata | null>(() => {
  const raw = rawLogs.value;
  if (!raw) return null;

  let exportDate = '';
  let sessionStart = '';
  let version = '';
  let buildCode = 0;
  let sdkVersion = 0;
  let device = '';

  const exportMatch = raw.match(/Exported:\s*(.+)/);
  if (exportMatch) exportDate = exportMatch[1].trim();

  const sessionMatch = raw.match(/SESSION START:\s*(.+)/);
  if (sessionMatch) sessionStart = sessionMatch[1].trim();

  const versionMatch = raw.match(/version=(\S+)\s+code=(\d+)/);
  if (versionMatch) { version = versionMatch[1]; buildCode = parseInt(versionMatch[2]); }

  const sdkMatch = raw.match(/sdk=(\d+)\s+device=(.+)/);
  if (sdkMatch) { sdkVersion = parseInt(sdkMatch[1]); device = sdkMatch[2].trim(); }

  const logs = parsedLogs.value;
  let firstTs = 0, lastTs = 0;
  for (const log of logs) {
    if (!log.time) continue;
    const ts = parseTimeToEpoch(log.time);
    if (ts > 0) {
      if (firstTs === 0) firstTs = ts;
      lastTs = ts;
    }
  }

  const channels = Array.from(new Set(logs.map(l => l.channel).filter(Boolean))).sort();

  return {
    exportDate,
    sessionStart,
    version,
    buildCode,
    sdkVersion,
    device,
    sessionDurationSec: firstTs && lastTs ? Math.round((lastTs - firstTs) / 1000) : 0,
    totalLogLines: logs.length,
    channels,
  };
});

// ── Channel statistics ────────────────────────────────────────────────────────

export interface ChannelStat {
  channel: string;
  total: number;
  errors: number;
  warnings: number;
  info: number;
  debug: number;
  verbose: number;
  firstTime: string;
  lastTime: string;
}

export const channelStats = computed<ChannelStat[]>(() => {
  const map = new Map<string, ChannelStat>();
  for (const log of parsedLogs.value) {
    const ch = log.channel || 'UNKNOWN';
    if (!map.has(ch)) {
      map.set(ch, { channel: ch, total: 0, errors: 0, warnings: 0, info: 0, debug: 0, verbose: 0, firstTime: log.time, lastTime: log.time });
    }
    const s = map.get(ch)!;
    s.total++;
    if (log.time) s.lastTime = log.time;
    switch (log.level.toUpperCase().trim()) {
      case 'E': s.errors++; break;
      case 'W': s.warnings++; break;
      case 'I': s.info++; break;
      case 'D': s.debug++; break;
      case 'V': s.verbose++; break;
    }
  }
  return Array.from(map.values()).sort((a, b) => b.total - a.total);
});

// ── DB throughput timeline ────────────────────────────────────────────────────

export interface DbThroughputPoint {
  ts: number;
  wallClock: string;
  persisted: number;
  queued: number;
}

export const dbThroughput = computed<DbThroughputPoint[]>(() => {
  const points: DbThroughputPoint[] = [];
  for (const log of parsedLogs.value) {
    if (!log.time || log.channel !== 'FEED') continue;
    const m = log.message.match(/Event store: persisted (\d+) events, (\d+) queued/);
    if (m) {
      points.push({
        ts: parseTimeToEpoch(log.time),
        wallClock: log.time,
        persisted: parseInt(m[1]),
        queued: parseInt(m[2]),
      });
    }
  }
  return points;
});

// ── Relay connection timeline ─────────────────────────────────────────────────

export interface RelayTimelineEvent {
  ts: number;
  wallClock: string;
  url: string;
  type: 'disconnect' | 'flagged' | 'blocked' | 'unflagged' | 'subscription' | 'persistent_set';
  detail: string;
}

export const relayTimeline = computed<RelayTimelineEvent[]>(() => {
  const events: RelayTimelineEvent[] = [];
  for (const log of parsedLogs.value) {
    if (!log.time || log.channel !== 'RELAY') continue;
    const ts = parseTimeToEpoch(log.time);
    const msg = log.message;

    // Disconnections
    const discMatch = msg.match(/\[(wss?:\/\/[^\]]+)\] Disconnected/);
    if (discMatch) {
      events.push({ ts, wallClock: log.time, url: discMatch[1], type: 'disconnect', detail: msg });
      continue;
    }
    // Flagged
    const flagMatch = msg.match(/Relay (wss?:\/\/\S+) FLAGGED/);
    if (flagMatch) {
      events.push({ ts, wallClock: log.time, url: flagMatch[1], type: 'flagged', detail: msg });
      continue;
    }
    // Auto-blocked
    const blockMatch = msg.match(/Relay (wss?:\/\/\S+) AUTO-BLOCKED/);
    if (blockMatch) {
      events.push({ ts, wallClock: log.time, url: blockMatch[1], type: 'blocked', detail: msg });
      continue;
    }
    // Subscription updates
    if (msg.includes('Subscription updated') || msg.includes('subscription started')) {
      events.push({ ts, wallClock: log.time, url: '', type: 'subscription', detail: msg });
      continue;
    }
    // Persistent relay set
    if (msg.includes('Persistent relay URLs set')) {
      events.push({ ts, wallClock: log.time, url: '', type: 'persistent_set', detail: msg });
    }
  }
  return events;
});

// ── Performance spans (enhanced) ──────────────────────────────────────────────

export interface PerfSpan {
  id: string;
  name: string;
  channel: string;
  startWallClock: string;
  endWallClock: string;
  startTs: number;
  endTs: number;
  durationMs: number;
}

export const perfSpans = computed<PerfSpan[]>(() => {
  const spans: PerfSpan[] = [];
  for (const raw of parsedSpans.value) {
    if (raw.endTimeMs != null && raw.startTimeMs > 0) {
      spans.push({
        id: raw.id,
        name: raw.name,
        channel: raw.channel,
        startWallClock: raw.startTimeStr,
        endWallClock: '', // We don't have the end wall clock string in the raw span
        startTs: raw.startTimeMs,
        endTs: raw.endTimeMs,
        durationMs: Math.round(raw.endTimeMs - raw.startTimeMs),
      });
    }
  }
  return spans.sort((a, b) => a.startTs - b.startTs);
});

// ── Health score (computed from all channels) ─────────────────────────────────

export interface HealthScore {
  startupTimeMs: number | null;
  relayHealthy: number;
  relayTotal: number;
  relayFlagged: number;
  relayBlocked: number;
  feedPeakNotes: number;
  totalEventsPersisted: number;
  dbDrainAvgMs: number | null;
  dbDrainP95Ms: number | null;
  totalErrors: number;
  totalWarnings: number;
}

export const healthScore = computed<HealthScore>(() => {
  const stats = channelStats.value;
  const totalErrors = stats.reduce((s, c) => s + c.errors, 0);
  const totalWarnings = stats.reduce((s, c) => s + c.warnings, 0);

  const phases = startupPhases.value;
  const completedPhases = phases.filter(p => p.elapsedMs != null);
  const startupTimeMs = completedPhases.length > 0
    ? Math.max(...completedPhases.map(p => (p.endTs ?? p.startTs) - (phases[0]?.startTs ?? 0)))
    : null;

  const relays = relayHealthMap.value;
  const relayHealthy = relays.filter(r => r.connects > 0 && !r.blocked && !r.flagged).length;
  const relayFlagged = relays.filter(r => r.flagged).length;
  const relayBlocked = relays.filter(r => r.blocked).length;

  const feed = feedInspectorData.value;
  const spans = perfSpans.value;
  const drainDurations = spans.filter(s => s.name.includes('DB Queue Drain')).map(s => s.durationMs);
  const dbDrainAvgMs = drainDurations.length > 0
    ? Math.round(drainDurations.reduce((a, b) => a + b, 0) / drainDurations.length)
    : null;
  const dbDrainP95Ms = drainDurations.length > 0 ? pct(drainDurations, 95) : null;

  return {
    startupTimeMs,
    relayHealthy,
    relayTotal: relays.length,
    relayFlagged,
    relayBlocked,
    feedPeakNotes: feed.summary.peakNotes,
    totalEventsPersisted: feed.summary.totalEventsPersisted,
    dbDrainAvgMs,
    dbDrainP95Ms,
    totalErrors,
    totalWarnings,
  };
});

// ── Flush throughput timeline ─────────────────────────────────────────────────

export interface FlushPoint {
  ts: number;
  wallClock: string;
  total: number;
  toFeed: number;
  reposts: number;
}

export const flushTimeline = computed<FlushPoint[]>(() => {
  const points: FlushPoint[] = [];
  for (const log of parsedLogs.value) {
    if (!log.time || log.channel !== 'FEED') continue;
    const m = log.message.match(/Flushed (\d+) events \((\d+) reposts\): (\d+) to feed/);
    if (m && parseInt(m[1]) > 0) {
      points.push({
        ts: parseTimeToEpoch(log.time),
        wallClock: log.time,
        total: parseInt(m[1]),
        toFeed: parseInt(m[3]),
        reposts: parseInt(m[2]),
      });
    }
  }
  return points;
});

// ── Heap Metrics ──────────────────────────────────────────────────────────────

export interface HeapMetric {
  ts: number;
  wallClock: string;
  usedMb: number;
  maxMb: number;
  percent: number;
  feedNotes: number;
}

export const heapMetrics = computed<HeapMetric[]>(() => {
  const arr: HeapMetric[] = [];
  for (const log of parsedLogs.value) {
    if (!log.time) continue;
    const m = log.message.match(/Heap: (\d+)MB\/(\d+)MB \((\d+)%\) \| feed=(\d+) notes/);
    if (m) {
       arr.push({ ts: parseTimeToEpoch(log.time), wallClock: log.time, usedMb: parseInt(m[1]), maxMb: parseInt(m[2]), percent: parseInt(m[3]), feedNotes: parseInt(m[4]) });
    }
  }
  return arr.sort((a, b) => a.ts - b.ts);
});

// ── Notification Stats ────────────────────────────────────────────────────────

export interface NotificationStats {
  fired: number;
  suppressed: number;
  types: Record<string, number>;
  zaps: number[];
}

export const notificationStats = computed<NotificationStats>(() => {
  const stats: NotificationStats = { fired: 0, suppressed: 0, types: {}, zaps: [] };
  for (const log of parsedLogs.value) {
    if (log.channel !== 'NOTIFICATION') continue;
    if (log.message.includes('SUPPRESSED')) {
      stats.suppressed++;
    } else if (log.message.includes('fireNotif')) {
      stats.fired++;
    }
    const typeMatch = log.message.match(/type=([A-Z_]+)/);
    if (typeMatch) {
      const type = typeMatch[1];
      stats.types[type] = (stats.types[type] || 0) + 1;
    }
    const zapMatch = log.message.match(/parseZapAmount:\s*(\d+)/);
    if (zapMatch) {
      stats.zaps.push(parseInt(zapMatch[1]));
    }
  }
  return stats;
});