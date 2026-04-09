import { Component } from 'inferno';
import { createElement as h } from 'inferno-create-element';
import { sessionMetadata, channelStats, startupPhases, relayHealthMap, feedMetrics, activePanel, notificationStats } from '../../signals';
import { Card, CardHeader, CardTitle, CardContent, Badge, Table, TableHeader, TableRow, TableHead, TableBody, TableCell, Separator } from 'blazecn';
import { TimelineChart } from '../TimelineChart';

function StatCard({ title, value, icon, description, valueClass = '' }: { title: string, value: string | number, icon?: any, description?: string, valueClass?: string }) {
  return h(Card, { className: 'shadow-sm hover:shadow-md transition-all' },
    h(CardHeader, { className: 'flex flex-row items-center justify-between space-y-0 pb-2' },
      h(CardTitle, { className: 'text-sm font-medium' }, title),
      icon && h('div', { className: 'text-muted-foreground opacity-70' }, icon)
    ),
    h(CardContent, null,
      h('div', { className: `text-2xl font-bold tracking-tight ${valueClass}` }, value),
      description && h('p', { className: 'text-xs text-muted-foreground mt-1' }, description)
    )
  );
}

function SessionHeader() {
  const meta = sessionMetadata.value;
  if (!meta) return null;

  return h('div', { className: 'flex flex-col md:flex-row gap-4 items-start md:items-center justify-between mb-8 pb-6 border-b' },
    h('div', null,
      h('h2', { className: 'text-3xl font-bold tracking-tight text-foreground flex items-center gap-3' },
        'Session Diagnostic',
        h(Badge, { variant: 'default', className: 'text-xs' }, meta.device)
      ),
      h('div', { className: 'flex items-center gap-2 mt-2 text-sm text-muted-foreground font-medium' },
         h('span', null, `App v${meta.version} (${meta.buildCode})`),
         h('span', { className: 'mx-1 opacity-50' }, '•'),
         h('span', null, `SDK ${meta.sdkVersion}`),
         h('span', { className: 'mx-1 opacity-50' }, '•'),
         h('span', null, meta.sessionStart)
      )
    ),
    h('div', { className: 'flex items-center gap-4 text-right' },
      h('div', { className: 'flex flex-col' },
        h('span', { className: 'text-xs text-muted-foreground uppercase font-bold tracking-wider' }, 'Duration'),
        h('span', { className: 'text-xl font-mono text-foreground font-semibold' }, `${meta.sessionDurationSec}s`)
      ),
      h('div', { className: 'w-px h-10 bg-border' }),
      h('div', { className: 'flex flex-col' },
        h('span', { className: 'text-xs text-muted-foreground uppercase font-bold tracking-wider' }, 'Total Logs'),
        h('span', { className: 'text-xl font-mono text-foreground font-semibold' }, meta.totalLogLines.toLocaleString())
      )
    )
  );
}

function KPIs() {
  const meta = sessionMetadata.value;
  const stats = channelStats.value;
  const relays = relayHealthMap.value;
  const fMetrics = feedMetrics.value;
  
  if (!meta) return null;

  let totalWarnings = 0;
  let totalErrors = 0;
  for (const s of stats) {
    totalWarnings += s.warnings;
    totalErrors += s.errors;
  }

  const connectedRelays = relays.filter(r => r.connects > 0).length;
  const healthyRelays = relays.filter(r => r.connects > 0 && r.failures === 0 && !r.flagged && !r.blocked).length;
  const relayHealthRatio = connectedRelays > 0 ? Math.round((healthyRelays / connectedRelays) * 100) : 0;
  
  const phases = startupPhases.value;
  const startupTime = phases.length > 0 && phases[phases.length - 1].endTs && phases[0].startTs
    ? Math.round((phases[phases.length - 1].endTs! - phases[0].startTs) / 1000 * 10) / 10
    : 0;

  return h('div', { className: 'grid grid-cols-2 md:grid-cols-3 lg:grid-cols-6 gap-4 mb-8' },
    h(StatCard, { 
       title: 'Startup Time', 
       value: startupTime > 0 ? `${startupTime}s` : 'N/A', 
       description: `${phases.length} phases`,
       valueClass: startupTime > 5 ? 'text-destructive' : 'text-foreground'
    }),
    h(StatCard, { 
       title: 'Relay Health', 
       value: `${relayHealthRatio}%`, 
       description: `${healthyRelays} of ${connectedRelays} healthy`,
       valueClass: relayHealthRatio < 80 ? 'text-destructive' : 'text-primary'
    }),
    h(StatCard, { 
       title: 'DB Commits', 
       value: fMetrics.totalDbCommits.toLocaleString(), 
       description: `${fMetrics.totalDbRows.toLocaleString()} rows`
    }),
    h(StatCard, { 
       title: 'Feed Latency (p95)', 
       value: fMetrics.p95FlushMs ? `${fMetrics.p95FlushMs}ms` : 'N/A', 
       description: `Avg: ${fMetrics.avgFlushMs || 0}ms`,
       valueClass: (fMetrics.p95FlushMs || 0) > 1000 ? 'text-yellow-500' : 'text-foreground'
    }),
    h(StatCard, { 
       title: 'Total Warnings', 
       value: totalWarnings.toLocaleString(), 
       description: 'Across all channels',
       valueClass: totalWarnings > 500 ? 'text-yellow-500' : 'text-foreground'
    }),
    h(StatCard, { 
       title: 'Total Errors', 
       value: totalErrors.toLocaleString(), 
       description: 'Require attention',
       valueClass: totalErrors > 50 ? 'text-destructive' : 'text-foreground'
    })
  );
}

function ChannelBreakdown() {
  const stats = channelStats.value;
  
  return h(Card, { className: 'mb-8' },
    h(CardHeader, null,
      h(CardTitle, null, 'Channel Breakdown')
    ),
    h(CardContent, null,
      h('div', { className: 'overflow-x-auto' },
        h(Table, null,
          h(TableHeader, null,
            h(TableRow, null,
              h(TableHead, { className: 'w-[200px]' }, 'Channel'),
              h(TableHead, { className: 'text-right' }, 'Total Logs'),
              h(TableHead, { className: 'text-right text-destructive' }, 'Errors'),
              h(TableHead, { className: 'text-right text-yellow-500' }, 'Warnings'),
              h(TableHead, { className: 'text-right' }, 'Info'),
              h(TableHead, { className: 'text-right text-muted-foreground' }, 'Debug/Trace'),
            )
          ),
          h(TableBody, null,
             stats.map(s => 
               h(TableRow, { key: s.channel },
                 h(TableCell, { className: 'font-medium uppercase tracking-wider text-xs flex items-center gap-2' }, 
                   s.channel,
                   s.errors > 0 ? h(Badge, { variant: 'destructive', className: 'text-[10px] px-1 py-0 h-4 min-h-0' }, 'Issues') : null
                 ),
                 h(TableCell, { className: 'text-right font-mono text-sm' }, s.total.toLocaleString()),
                 h(TableCell, { className: `text-right font-mono text-sm ${s.errors > 0 ? 'text-destructive font-bold' : 'text-muted-foreground'}` }, s.errors.toLocaleString()),
                 h(TableCell, { className: `text-right font-mono text-sm ${s.warnings > 0 ? 'text-yellow-500 font-bold' : 'text-muted-foreground'}` }, s.warnings.toLocaleString()),
                 h(TableCell, { className: 'text-right font-mono text-sm' }, s.info.toLocaleString()),
                 h(TableCell, { className: 'text-right font-mono text-sm text-muted-foreground' }, (s.debug + s.verbose).toLocaleString()),
               )
             )
          )
        )
      )
    )
  );
}

function NotificationDashboard() {
  const stats = notificationStats.value;
  if (stats.fired === 0 && stats.suppressed === 0) return null;

  return h(Card, { className: 'mb-8' },
    h(CardHeader, null, h(CardTitle, null, 'Notification Processing')),
    h(CardContent, null,
      h('div', { className: 'grid grid-cols-2 md:grid-cols-4 gap-4 mb-4' },
        h(Card, { className: 'bg-muted/30 border-none' },
           h(CardHeader, { className: 'pb-2' }, h(CardTitle, { className: 'text-xs font-medium text-muted-foreground uppercase tracking-wider' }, 'Fired')),
           h(CardContent, null, h('div', { className: 'text-2xl font-bold text-primary' }, stats.fired))
        ),
        h(Card, { className: 'bg-muted/30 border-none' },
           h(CardHeader, { className: 'pb-2' }, h(CardTitle, { className: 'text-xs font-medium text-muted-foreground uppercase tracking-wider' }, 'Suppressed')),
           h(CardContent, null, h('div', { className: 'text-2xl font-bold text-muted-foreground' }, stats.suppressed))
        ),
        h(Card, { className: 'bg-muted/30 border-none' },
           h(CardHeader, { className: 'pb-2' }, h(CardTitle, { className: 'text-xs font-medium text-muted-foreground uppercase tracking-wider' }, 'Total Zap Receipts')),
           h(CardContent, null, h('div', { className: 'text-2xl font-bold text-yellow-500' }, stats.zaps.length))
        ),
        h(Card, { className: 'bg-muted/30 border-none' },
           h(CardHeader, { className: 'pb-2' }, h(CardTitle, { className: 'text-xs font-medium text-muted-foreground uppercase tracking-wider' }, 'Largest Zap')),
           h(CardContent, null, h('div', { className: 'text-2xl font-bold text-yellow-500' }, stats.zaps.length > 0 ? Math.max(...stats.zaps) : 0))
        )
      ),
      h('div', { className: 'mt-4' },
        h('h4', { className: 'text-sm font-semibold mb-2' }, 'By Type'),
        h('div', { className: 'flex flex-wrap gap-2' },
           Object.keys(stats.types).map(type => 
             h(Badge, { key: type, variant: 'outline', className: 'text-xs bg-card hover:bg-card px-2 py-1' }, `${type}: ${stats.types[type]}`)
           )
        )
      )
    )
  );
}

export class OverviewPanel extends Component<{}, {}> {
  render() {
    return h('div', { className: 'animate-in fade-in slide-in-from-bottom-4 duration-500 py-6 max-w-7xl mx-auto w-full space-y-8' },
      h(SessionHeader, null),
      h(KPIs, null),
      h(TimelineChart, null),
      h(NotificationDashboard, null),
      h(ChannelBreakdown, null)
    );
  }
}
