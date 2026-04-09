import { Component } from 'inferno';
import { createElement as h } from 'inferno-create-element';
import { effect } from '@preact/signals-core';
import {
  filteredLogs, filterQuery, filterLevel, filterChannel, filterTraceId, renderLimit,
  clearLogs, availableChannels, availableLevels, availableTraceIds
} from '../../signals';
import { Button, toast } from 'blazecn';
import { TraceExplorer } from '../TraceExplorer';

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
    h(Button, { onClick: copyOptimizedLogs, className: 'shrink-0' }, 'Copy Filtered'),
    h(Button, { onClick: clearLogs, variant: 'outline', className: 'shrink-0' }, 'Clear Logs')
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

// Ensure signal bridge functions
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


export class LogExplorerPanel extends Component<{}, {}> {
  render() {
    return h('div', { className: 'flex flex-col h-full' },
      S(() => h(TraceExplorer, null)),
      S(() => h(Controls, null)),
      S(() => h(LogList, null))
    );
  }
}
