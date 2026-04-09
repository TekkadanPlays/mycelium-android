import { Component } from 'inferno';
import { createElement as h } from 'inferno-create-element';
import { effect } from '@preact/signals-core';
import {
  parsedLogs, parsedEvents, parsedSpans, rawLogs, fileMode, filterQuery, filterLevel, filterChannel, filterTraceId, renderLimit,
  parseLogs, parseEvents, batch, activePanel, clearLogs
} from '../signals';

import { OverviewPanel } from './panels/OverviewPanel';
import { StartupPanel } from './panels/StartupPanel';
import { RelayPanel } from './panels/RelayPanel';
import { FeedPanel } from './panels/FeedPanel';
import { PerformancePanel } from './panels/PerformancePanel';
import { LogExplorerPanel } from './panels/LogExplorerPanel';

import {
  Button, Badge,
  ThemeToggle, ThemeSelector,
  Toaster, toast,
  initTheme
} from 'blazecn';

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

function Spinner() {
  return h('svg', { className: 'size-10 animate-spin text-primary', viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', 'stroke-width': '2' },
    h('circle', { cx: '12', cy: '12', r: '10', stroke: 'currentColor', 'stroke-opacity': '0.2' }),
    h('path', { d: 'M12 2a10 10 0 0 1 10 10', strokeLinecap: 'round' })
  );
}

class FileUpload extends Component<{}, { isDragging: boolean, isLoading: boolean }> {
  state = { isDragging: false, isLoading: false };
  private worker: Worker | null = null;
  private workerReady = false;

  componentDidMount() {
    try {
      this.worker = new Worker('/public/dist/parserWorker.js', { type: 'module' });
      this.workerReady = true;
      this.worker.onmessage = (e) => {
        const msg = e.data;
        if (msg.type === 'logs' && msg.parsed) {
          // Hydrate signals on the main thread from worker results
          batch(() => {
            rawLogs.value = msg.data;
            parsedLogs.value = msg.parsed.entries;
            parsedSpans.value = msg.parsed.spans;
            if (msg.parsed.synthEvents?.length > 0) {
              parsedEvents.value = msg.parsed.synthEvents;
            }
            fileMode.value = 'logs';
            filterQuery.value = '';
            filterLevel.value = '';
            filterChannel.value = '';
            filterTraceId.value = '';
            renderLimit.value = 1000;
            activePanel.value = 'overview';
          });
        } else if (msg.type === 'events' && msg.parsed) {
          batch(() => {
            parsedEvents.value = msg.parsed;
            fileMode.value = 'events';
            rawLogs.value = '';
            parsedLogs.value = [];
            parsedSpans.value = [];
            activePanel.value = 'overview';
          });
        }
        this.setState({ isLoading: false });
        toast.success('File processed successfully');
      };
      this.worker.onerror = () => {
        this.workerReady = false;
      };
    } catch {
      this.workerReady = false;
    }
  }

  componentWillUnmount() {
    this.worker?.terminate();
  }

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

  handleFile = async (file: any) => {
    if (!file || this.state.isLoading) return;
    this.setState({ isLoading: true });
    if (this.workerReady && this.worker) {
      this.worker.postMessage(file);
    } else {
      // Fallback: parse on main thread
      const text = await file.text();
      if (file.name?.endsWith('.jsonl')) {
        parseEvents(text);
      } else {
        parseLogs(text);
      }
      activePanel.value = 'overview';
      this.setState({ isLoading: false });
      toast.success('File processed successfully');
    }
  }

  render() {
    return h('div', {
      className: `w-full max-w-3xl mx-auto mt-20 border-2 border-dashed rounded-2xl p-16 transition-colors flex flex-col items-center justify-center space-y-8 cursor-pointer ${this.state.isDragging ? 'border-primary bg-primary/5' : 'border-border/60 bg-muted/20 hover:bg-muted/40 hover:border-primary/50'}`,
      onDragOver: this.onDragOver,
      onDragLeave: this.onDragLeave,
      onDrop: this.onDrop,
      onClick: () => {
         if (this.state.isLoading) return;
         const input = document.createElement('input');
         input.type = 'file';
         input.accept = '.txt,.log,.jsonl';
         input.onchange = (e: any) => this.handleFile(e.target.files?.[0]);
         input.click();
      }
    },
      h('div', { className: 'size-24 rounded-full bg-primary/10 flex items-center justify-center text-primary border shadow-sm' },
        this.state.isLoading ? h(Spinner, null) : h(IconZap, null)
      ),
      h('h2', { className: 'text-3xl font-bold tracking-tight text-foreground' }, this.state.isLoading ? 'Processing...' : 'Drop diagnostic log file here'),
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

const NAVIGATION = [
  { id: 'overview', label: 'Overview', icon: '🍄' },
  { id: 'startup', label: 'Startup', icon: '🚀' },
  { id: 'relays', label: 'Relays', icon: '🌐' },
  { id: 'feed', label: 'Feed Pipeline', icon: '📝' },
  { id: 'perf', label: 'Performance', icon: '⚡' },
  { id: 'logs', label: 'Log Explorer', icon: '🔍' },
];

function Sidebar() {
  const active = activePanel.value;
  return h('div', { className: 'w-64 border-r bg-muted/20 flex flex-col pt-6 px-4 space-y-1 overflow-y-auto shrink-0' },
    NAVIGATION.map(nav => 
      h(Button, {
        key: nav.id,
        variant: active === nav.id ? 'secondary' : 'ghost',
        className: 'justify-start w-full gap-3',
        onClick: () => { activePanel.value = nav.id; }
      },
        h('span', null, nav.icon),
        h('span', null, nav.label)
      )
    ),
    h('div', { className: 'flex-1' }),
    h(Button, {
       variant: 'outline',
       className: 'mt-6 justify-center w-full mb-6',
       onClick: clearLogs
    }, 'Clear Data')
  );
}

export class App extends Component<{}, {}> {
  componentDidMount() {
    initTheme();
  }

  render() {
    return h('div', { className: 'h-screen bg-background text-foreground flex flex-col overflow-hidden' },
      h(Toaster, null),
      h('nav', { className: 'border-b bg-background/80 backdrop-blur-md sticky top-0 z-50 shrink-0 h-14 flex items-center justify-between px-6' },
        h('div', { className: 'flex items-center gap-2.5' },
          h('div', { className: 'size-7 rounded-lg bg-primary flex items-center justify-center text-primary-foreground text-sm font-bold' }, '🍄'),
          h('span', { className: 'font-bold text-base tracking-tight' }, 'Mycelium Diagnostics'),
          S(() => {
             if (parsedEvents.value.length > 0)
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
      ),

      h('div', { className: 'flex flex-1 overflow-hidden' },
        S(() => {
          const hasLogs   = parsedLogs.value.length > 0;
          const hasEvents = parsedEvents.value.length > 0;
          if (!hasLogs && !hasEvents) return null; // No sidebar if no file
          return h(Sidebar, null);
        }),
        h('main', { className: 'flex-1 overflow-auto bg-background' },
          S(() => {
             const hasLogs   = parsedLogs.value.length > 0;
             const hasEvents = parsedEvents.value.length > 0;
             if (!hasLogs && !hasEvents) {
                return h('div', { className: 'p-6 h-full overflow-y-auto w-full' }, h(FileUpload, null));
             }

             return h('div', { className: 'p-6 h-full' }, 
               (() => {
                 switch (activePanel.value) {
                   case 'overview': return h(OverviewPanel, null);
                   case 'startup': return h(StartupPanel, null);
                   case 'relays': return h(RelayPanel, null);
                   case 'feed': return h(FeedPanel, null);
                   case 'perf': return h(PerformancePanel, null);
                   case 'logs': return h(LogExplorerPanel, null);
                   default: return h(OverviewPanel, null);
                 }
               })()
             );
          })
        )
      )
    );
  }
}
