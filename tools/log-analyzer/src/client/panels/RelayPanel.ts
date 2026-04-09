import { Component } from 'inferno';
import { createElement as h } from 'inferno-create-element';
import { relayHealthMap, relayTimeline, authEvents } from '../../signals';
import { Card, CardHeader, CardTitle, CardContent, Badge, Table, TableHeader, TableRow, TableHead, TableBody, TableCell } from 'blazecn';

function RelaySummaryCards() {
  const relays = relayHealthMap.value;
  const connected = relays.filter(r => r.connects > 0).length;
  const healthy = relays.filter(r => r.connects > 0 && r.failures === 0 && !r.flagged && !r.blocked).length;
  const flagged = relays.filter(r => r.flagged).length;
  const blocked = relays.filter(r => r.blocked).length;
  const degraded = relays.filter(r => r.failures > 0 && !r.blocked && !r.flagged).length;

  return h('div', { className: 'grid grid-cols-2 md:grid-cols-4 gap-4 mb-8' },
    h(Card, null,
      h(CardHeader, { className: 'pb-2' }, h(CardTitle, { className: 'text-sm font-medium text-muted-foreground' }, 'Healthy')),
      h(CardContent, null, h('div', { className: 'text-3xl font-bold text-primary' }, healthy))
    ),
    h(Card, null,
      h(CardHeader, { className: 'pb-2' }, h(CardTitle, { className: 'text-sm font-medium text-muted-foreground' }, 'Degraded')),
      h(CardContent, null, h('div', { className: 'text-3xl font-bold text-yellow-500' }, degraded))
    ),
    h(Card, null,
      h(CardHeader, { className: 'pb-2' }, h(CardTitle, { className: 'text-sm font-medium text-muted-foreground' }, 'Flagged')),
      h(CardContent, null, h('div', { className: 'text-3xl font-bold text-orange-500' }, flagged))
    ),
    h(Card, null,
      h(CardHeader, { className: 'pb-2' }, h(CardTitle, { className: 'text-sm font-medium text-muted-foreground' }, 'Blocked')),
      h(CardContent, null, h('div', { className: 'text-3xl font-bold text-destructive' }, blocked))
    )
  );
}

function RelayHealthTable() {
  const relays = relayHealthMap.value;

  return h(Card, { className: 'mb-8' },
    h(CardHeader, null, h(CardTitle, null, 'Relay Intelligence & Health')),
    h(CardContent, null,
      h('div', { className: 'overflow-x-auto' },
        h(Table, null,
          h(TableHeader, null,
            h(TableRow, null,
              h(TableHead, null, 'URL'),
              h(TableHead, { className: 'text-right' }, 'Connects'),
              h(TableHead, { className: 'text-right' }, 'Failures'),
              h(TableHead, { className: 'text-right' }, 'Avg Latency'),
              h(TableHead, { className: 'text-right' }, 'p95 Latency'),
              h(TableHead, { className: 'text-right' }, 'EOSE'),
              h(TableHead, null, 'Status')
            )
          ),
          h(TableBody, null,
            relays.map((r) => {
              let statusText = 'Healthy';
              let badgeVar = 'default';
              if (r.blocked) { statusText = `Blocked (${r.blockedHours}h)`; badgeVar = 'destructive'; }
              else if (r.flagged) { statusText = 'Flagged'; badgeVar = 'outline'; }
              else if (r.failures > 0) { statusText = 'Degraded'; badgeVar = 'secondary'; }

              return h(TableRow, { key: r.url },
                h(TableCell, { className: 'font-medium truncate max-w-[250px]' }, r.url),
                h(TableCell, { className: 'text-right font-mono text-sm' }, r.connects),
                h(TableCell, { className: `text-right font-mono text-sm ${r.failures > 0 ? 'text-destructive' : 'text-muted-foreground'}` }, r.failures),
                h(TableCell, { className: 'text-right font-mono text-sm' }, r.avgLatencyMs ? `${r.avgLatencyMs}ms` : '-'),
                h(TableCell, { className: 'text-right font-mono text-sm' }, r.p95LatencyMs ? `${r.p95LatencyMs}ms` : '-'),
                h(TableCell, { className: 'text-right font-mono text-sm' }, r.eoseCount),
                h(TableCell, null, h(Badge, { variant: badgeVar as any }, statusText))
              );
            })
          )
        )
      )
    )
  );
}

function RelayTimelineEvents() {
    const timeline = relayTimeline.value;

    return h(Card, { className: 'mb-8' },
      h(CardHeader, null, h(CardTitle, null, 'Relay State Transitions')),
      h(CardContent, null,
        timeline.length === 0 ? h('div', { className: 'text-muted-foreground' }, 'No transitions recorded.') :
        h('div', { className: 'space-y-4 max-h-[400px] overflow-y-auto pr-4' },
           timeline.map((t, i) => {
               let color = 'text-foreground';
               if (t.type === 'disconnect') color = 'text-yellow-500';
               if (t.type === 'flagged') color = 'text-orange-500';
               if (t.type === 'blocked') color = 'text-destructive';
               if (t.type === 'subscription') color = 'text-primary';

               return h('div', { key: i, className: 'flex gap-4 items-start text-sm border-b pb-2 last:border-0' },
                  h('span', { className: 'font-mono text-xs w-24 shrink-0 text-muted-foreground mt-0.5' }, t.wallClock),
                  h('div', { className: 'flex-1' },
                     h('span', { className: `font-semibold ${color} uppercase text-[10px] tracking-wider px-1.5 py-0.5 rounded-sm bg-muted mr-2` }, t.type),
                     t.url && h('span', { className: 'font-medium mr-2' }, t.url),
                     h('span', { className: 'text-muted-foreground text-xs break-all' }, t.detail)
                  )
               );
           })
        )
      )
    );
}

function AuthEventsTable() {
  const events = authEvents.value;
  if (events.length === 0) return null;

  return h(Card, { className: 'mb-8' },
    h(CardHeader, null, h(CardTitle, null, 'NIP-42 Auth Challenges')),
    h(CardContent, null,
      h('div', { className: 'overflow-x-auto max-h-[300px]' },
        h(Table, null,
          h(TableHeader, null,
            h(TableRow, null,
               h(TableHead, null, 'Time'),
               h(TableHead, null, 'Relay'),
               h(TableHead, null, 'Event'),
               h(TableHead, null, 'Message')
            )
          ),
          h(TableBody, null,
             events.map((e, idx) => 
               h(TableRow, { key: idx },
                  h(TableCell, { className: 'font-mono text-xs' }, new Date(e.ts).toISOString().split('T')[1].replace('Z', '')),
                  h(TableCell, { className: 'font-medium text-xs truncate max-w-[200px]' }, e.url),
                  h(TableCell, null, h(Badge, { variant: 'outline', className: 'text-[10px]' }, e.ev)),
                  h(TableCell, { className: 'text-xs text-muted-foreground truncate max-w-[300px]' }, e.message || '')
               )
             )
          )
        )
      )
    )
  );
}

export class RelayPanel extends Component<{}, {}> {
  render() {
    return h('div', { className: 'animate-in fade-in slide-in-from-bottom-4 duration-500 py-6 max-w-7xl mx-auto w-full' },
      h('div', { className: 'mb-8 pb-4 border-b' },
        h('h2', { className: 'text-3xl font-bold tracking-tight text-foreground' }, 'Relay Network'),
        h('p', { className: 'text-muted-foreground mt-2' }, 'Connectivity, health monitoring, and blocklist events.')
      ),
      h(RelaySummaryCards, null),
      h(RelayHealthTable, null),
      h(AuthEventsTable, null),
      h(RelayTimelineEvents, null)
    );
  }
}
