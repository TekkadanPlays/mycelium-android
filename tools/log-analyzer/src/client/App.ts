import { Component } from 'inferno';
import { createElement as h } from 'inferno-create-element';
import { effect } from '@preact/signals-core';
import {
  parsedLogs, parsedEvents, fileMode, filteredLogs, filterQuery, filterLevel, filterChannel, filterTraceId, renderLimit,
  parseLogs, parseEvents, clearLogs, availableChannels, availableLevels, availableTraceIds
} from '../signals';
import {
  Button, Badge, Card, CardHeader, CardTitle, CardContent,
  ThemeToggle, ThemeSelector,
  Toaster, toast,
  initTheme,
} from 'blazecn';
import { TimelineChart } from './TimelineChart';
import { TraceExplorer } from './TraceExplorer';
import { EventsView } from './EventsView';

// Bridge pattern...
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

function IconZap() {
  return h('svg', { className: 'size-10', viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', 'stroke-width': '1.5' },
    h('path', { strokeLinecap: 'round', strokeLinejoin: 'round', d: 'M13 2L3 14h9l-1 8 10-12h-9l1-8' })
  );
}

class FileUpload extends Component<{}, { isDragging: boolean }> {
  state = { isDragging: false };

  onDragOver = (e: any) => {
    e.preventDefault();
    this.setState({ isDragging: true });
  }

  onDragLeave = (e: any) => {
    e.preventDefault();
    this.setState({ isDragging: false });
  }

  onDrop = (e: any) => {
    e.preventDefault();
    this.setState({ isDragging: false });
    const file = e.dataTransfer.files?.[0];
    this.handleFile(file);
  }

  handleFile = (file: any) => {
    if (!file) return;
    const reader = new FileReader();
    reader.onload = (ev) => {
      const text = ev.target?.result;
      if (typeof text === 'string') {
        const isEvents = file.name.endsWith('.jsonl') || text.trimStart().startsWith('{');
        if (isEvents) {
          parseEvents(text);
        } else {
          parseLogs(text);
        }
        toast.success(`Loaded ${file.name}`);
      }
    };
    reader.readAsText(file);
  }

  render() {
    return h('div', {
      className: `w-full max-w-3xl mx-auto mt-20 border-2 border-dashed rounded-2xl p-16 transition-colors flex flex-col items-center justify-center space-y-8 cursor-pointer ${this.state.isDragging ? 'border-primary bg-primary/5' : 'border-border/60 bg-muted/20 hover:bg-muted/40 hover:border-primary/50'}`,
      onDragOver: this.onDragOver,
      onDragLeave: this.onDragLeave,
      onDrop: this.onDrop,
      onClick: () => {
         const input = document.createElement('input');
         input.type = 'file';
         input.accept = '.txt,.log,.jsonl';
         input.onchange = (e: any) => this.handleFile(e.target.files?.[0]);
         input.click();
      }
    },
      h('div', { className: 'size-24 rounded-full bg-primary/10 flex items-center justify-center text-primary border shadow-sm' },
        h(IconZap, null)
      ),
      h('h2', { className: 'text-3xl font-bold tracking-tight text-foreground' }, 'Drop diagnostic log file here'),
      h('p', { className: 'text-muted-foreground text-center max-w-md leading-relaxed text-lg' }, 'Accepts plain-text logs (.log/.txt) or structured event streams (.jsonl). Processed entirely in your browser.'),
      h('div', { className: 'mt-6 flex flex-col items-center gap-3 text-xs text-muted-foreground w-full max-w-lg' },
         h('span', null, 'To pull logs from your device:'),
         h('code', { className: 'font-mono bg-muted px-4 py-2 rounded-lg border shadow-sm text-[11px] font-semibold text-primary w-full text-center' }, 'adb shell run-as social.mycelium.android.debug cat files/diag_logs/all.log > dump.txt'),
         h('span', { className: 'opacity-60' }, 'or for structured event analysis:'),
         h('code', { className: 'font-mono bg-muted px-4 py-2 rounded-lg border shadow-sm text-[11px] font-semibold text-primary w-full text-center' }, 'adb shell run-as social.mycelium.android.debug cat files/diag_logs/events.jsonl > events.jsonl'),
      )
    );
  }
}

function copyOptimizedLogs() {
  const visible = filteredLogs.value;
  const optimized = visible.map(l => l.raw).join('\n');
  navigator.clipboard.writeText(optimized);
  toast.success(`Copied ${visible.length} optimized lines to clipboard.`);
}

function Controls() {
  return h('div', { className: 'flex flex-wrap items-center gap-3 py-4 border-b' },
    h('input', {
      type: 'text',
      placeholder: 'Search messages...',
      className: 'flex h-10 w-full md:w-auto rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:cursor-not-allowed disabled:opacity-50 min-w-[250px]',
      value: filterQuery.value,
      onInput: (e: any) => { filterQuery.value = e.target.value; }
    }),
    h('select', {
      className: 'flex h-10 w-full md:w-auto rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:cursor-not-allowed disabled:opacity-50',
      value: filterLevel.value,
      onChange: (e: any) => { filterLevel.value = e.target.value; }
    },
      h('option', { value: '' }, 'All Levels'),
      ...availableLevels.value.map(lv => h('option', { value: lv }, LogLevelName(lv) || 'None'))
    ),
    h('select', {
      className: 'flex h-10 w-full md:w-[200px] rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:cursor-not-allowed disabled:opacity-50 truncate',
      value: filterChannel.value,
      onChange: (e: any) => { filterChannel.value = e.target.value; }
    },
      h('option', { value: '' }, 'All Channels'),
      ...availableChannels.value.map(ch => h('option', { value: ch }, ch || 'None'))
    ),
    h('select', {
      className: 'flex h-10 w-full md:w-[200px] rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:cursor-not-allowed disabled:opacity-50 truncate',
      value: filterTraceId.value,
      onChange: (e: any) => { filterTraceId.value = e.target.value; }
    },
      h('option', { value: '' }, 'All Traces/Sessions'),
      ...availableTraceIds.value.map(tr => h('option', { value: tr }, tr || 'None'))
    ),
    h('div', { className: 'flex-1' }),
    h(Button, { onClick: copyOptimizedLogs, className: 'shrink-0' }, 'Copy Optimized for AI'),
    h(Button, { onClick: clearLogs, variant: 'outline', className: 'shrink-0' }, 'Clear')
  );
}

function LogLevelName(level: string) {
  switch(level.toUpperCase().trim()) {
    case 'E': return 'ERROR';
    case 'W': return 'WARN';
    case 'I': return 'INFO';
    case 'D': return 'DEBUG';
    case 'V': return 'VERBOSE';
    default: return level;
  }
}

function LogColor(level: string) {
  switch(level.toUpperCase().trim()) {
    case 'E': return 'text-destructive font-bold';
    case 'W': return 'text-yellow-600 dark:text-yellow-400 font-bold';
    case 'I': return 'text-blue-600 dark:text-blue-400 font-medium';
    case 'V': return 'text-muted-foreground';
    default: return 'text-foreground';
  }
}

function LogList() {
  const visible = filteredLogs.value;
  const limit = renderLimit.value;
  const toRender = visible.slice(0, limit);
  const total = visible.length;

  return h('div', { className: 'space-y-0.5 py-4 font-mono text-[11px] md:text-xs overflow-x-auto whitespace-pre' },
    ...toRender.map(log => {
      if (!log.time) {
         return h('div', { key: log.id, className: 'text-muted-foreground opacity-80 pl-2' }, log.message);
      }
      return h('div', { key: log.id, className: `flex gap-3 px-3 py-1.5 hover:bg-muted/50 rounded-lg leading-tight items-baseline border-b border-transparent hover:border-border/40 ${log.traceId ? 'bg-primary/5' : ''}` },
        h('span', { className: 'text-muted-foreground w-20 md:w-24 shrink-0 font-medium tracking-tight' }, log.time),
        h('span', { className: `w-4 shrink-0 text-center ${LogColor(log.level)}`, title: LogLevelName(log.level) }, log.level),
        h('span', { className: 'text-primary w-24 md:w-32 shrink-0 truncate font-semibold uppercase tracking-wider text-[10px]', title: log.channel }, log.channel),
        h('span', { className: 'text-muted-foreground w-32 md:w-48 shrink-0 truncate font-medium', title: log.tag }, log.tag),
        h('span', { className: 'flex-1 break-words whitespace-pre-wrap flex flex-col gap-0.5' }, 
            log.traceId ? h('span', { className: 'text-[10px] font-bold text-secondary uppercase bg-secondary/10 px-1.5 py-0.5 rounded-sm self-start inline-block mb-0.5' }, `Session: ${log.traceId}`) : null,
            h('span', null, log.message)
        ),
      );
    }),
    total > limit ? h('div', { className: 'py-8 flex justify-center' },
      h(Button, { variant: 'outline', onClick: () => renderLimit.value += 1000 }, `Load More (Showing ${limit} of ${total})`)
    ) : (
      total > 0 && h('div', { className: 'py-8 text-center text-muted-foreground text-sm' }, `End of logs (${total} records matching filters)`)
    ),
    total === 0 && h('div', { className: 'py-16 text-center text-muted-foreground text-lg' }, 'No logs match the current filters.')
  );
}

export class App extends Component<{}, {}> {
  componentDidMount() {
    initTheme();
  }

  render() {
    return h('div', { className: 'min-h-screen bg-background text-foreground flex flex-col font-sans' },
      h(Toaster, { position: 'bottom-right' }),
      
      h('nav', { className: 'border-b bg-background/80 backdrop-blur-md sticky top-0 z-50' },
        h('div', { className: 'mx-auto flex h-14 w-full items-center justify-between px-6 max-w-[1600px]' },
          h('div', { className: 'flex items-center gap-2.5' },
            h('div', { className: 'size-7 rounded-lg bg-primary flex items-center justify-center text-primary-foreground text-sm font-bold' }, '🍄'),
            h('span', { className: 'font-bold text-base tracking-tight' }, 'Local Log Analyzer'),
            S(() => {
              if (fileMode.value === 'events' && parsedEvents.value.length > 0)
                return h(Badge, { variant: 'secondary', className: 'ml-2 text-xs tabular-nums hidden sm:inline-flex' }, `${parsedEvents.value.length} events`);
              if (parsedLogs.value.length > 0)
                return h(Badge, { variant: 'secondary', className: 'ml-2 text-xs tabular-nums hidden sm:inline-flex' }, `${parsedLogs.value.length} total`);
              return null;
            })
          ),
          h('div', { className: 'flex items-center gap-3' },
            h(ThemeSelector, null),
            h(ThemeToggle, null)
          )
        )
      ),

      h('main', { className: 'flex-1 px-4 sm:px-6 w-full max-w-[1600px] mx-auto' },
        S(() => {
          const hasLogs   = parsedLogs.value.length > 0;
          const hasEvents = parsedEvents.value.length > 0;
          const mode      = fileMode.value;

          if (!hasLogs && !hasEvents) return h(FileUpload, null);

          if (mode === 'events') {
            return h('div', { className: 'flex flex-col' },
              // Mode switcher
              h('div', { className: 'flex gap-2 py-3 border-b' },
                h(Button, {
                  variant: 'ghost',
                  className: 'text-xs opacity-50',
                  onClick: () => { fileMode.value = 'logs'; }
                }, 'Plain Logs'),
                h(Button, {
                  variant: 'secondary',
                  className: 'text-xs',
                }, 'Event Analysis'),
                h('div', { className: 'flex-1' }),
                h(Button, { onClick: clearLogs, variant: 'outline', className: 'shrink-0 text-xs' }, 'Clear'),
              ),
              S(() => h(EventsView, null)),
            );
          }

          // Plain log mode
          return h('div', { className: 'flex flex-col h-full' },
            hasEvents ? h('div', { className: 'flex gap-2 py-3 border-b' },
              h(Button, {
                variant: 'secondary',
                className: 'text-xs',
              }, 'Plain Logs'),
              h(Button, {
                variant: 'ghost',
                className: 'text-xs opacity-50',
                onClick: () => { fileMode.value = 'events'; }
              }, 'Event Analysis'),
            ) : null,
            S(() => h(TraceExplorer, null)),
            S(() => h(TimelineChart, null)),
            S(() => h(Controls, null)),
            S(() => h(LogList, null)),
          );
        })
      )
    );
  }
}
