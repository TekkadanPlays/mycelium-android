/// <reference lib="webworker" />

// parserWorker.ts – Offloads log/event parsing to a Web Worker.
// Signals live on the main thread; this worker only does the CPU-heavy
// string splitting + regex matching, then posts the structured results back.

interface LogEntry {
  id: number;
  time: string;
  level: string;
  channel: string;
  tag: string;
  message: string;
  traceId?: string;
  raw: string;
}

interface TraceSpan {
  id: string;
  name: string;
  channel: string;
  startTimeStr: string;
  startTimeMs: number;
  endTimeMs?: number;
  data?: string;
}

interface LogEvent {
  ts: number;
  ev: string;
  ch: string;
  tag: string;
  sid?: string;
  [key: string]: any;
}

function parseTime(t: string): number {
  if (!t) return 0;
  const parts = t.split(':');
  if (parts.length < 3) return 0;
  return (parseInt(parts[0], 10) * 3600 + parseInt(parts[1], 10) * 60 + parseFloat(parts[2])) * 1000;
}

function doParseEvents(text: string): LogEvent[] {
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
  return events;
}

function doParseLogs(text: string): { entries: LogEntry[]; spans: TraceSpan[]; synthEvents: LogEvent[] } {
  const lines = text.split('\n');
  const entries: LogEntry[] = [];
  const spansMap = new Map<string, TraceSpan>();
  const activeSpans = new Map<string, string>();
  let id = 0;

  for (const line of lines) {
    const parts = line.split(' | ');
    if (parts.length >= 5) {
      const fullMessage = parts.slice(4).join(' | ').trim();
      let traceId: string | undefined = undefined;
      let finalMessage = fullMessage;
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
        traceId,
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

  // Synthesize events for Event Analysis charts
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
        if (isStart) synthEvents.push({ ts, ev: 'startup.phase_start', ch: 'STARTUP', tag: log.tag, phase: parseInt(m?.[1] || '0') });
        const isEnd = log.message.includes('complete');
        if (isEnd) synthEvents.push({ ts, ev: 'startup.phase_end', ch: 'STARTUP', tag: log.tag, phase: parseInt(m?.[1] || '0') });
      }
    }
  }

  return { entries, spans: Array.from(spansMap.values()), synthEvents };
}

self.addEventListener('message', async (event: MessageEvent) => {
  const file = event.data;
  if (!(file instanceof File || file instanceof Blob)) return;

  const text = await (file as File).text();
  const name = (file as File).name || '';

  if (name.endsWith('.jsonl')) {
    const events = doParseEvents(text);
    (self as any).postMessage({ type: 'events', data: text, parsed: events });
  } else {
    const result = doParseLogs(text);
    (self as any).postMessage({ type: 'logs', data: text, parsed: result });
  }
});
