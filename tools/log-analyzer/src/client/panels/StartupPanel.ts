import { Component } from 'inferno';
import { createElement as h } from 'inferno-create-element';
import { startupPhases, parsedEvents } from '../../signals';
import { Card, CardHeader, CardTitle, CardContent, Badge } from 'blazecn';

function StartupWaterfall() {
  const phases = startupPhases.value;
  if (phases.length === 0) return h('div', { className: 'p-8 text-center text-muted-foreground' }, 'No startup phase data found.');

  const totalTime = phases[phases.length - 1].endTs && phases[0].startTs
      ? phases[phases.length - 1].endTs! - phases[0].startTs
      : Math.max(...phases.map(p => (p.endTs || p.startTs) - phases[0].startTs));

  const maxTotal = totalTime > 0 ? totalTime : 1;

  return h(Card, { className: 'mb-8' },
    h(CardHeader, null, h(CardTitle, null, 'Startup Orchestrator Waterfall')),
    h(CardContent, null,
      h('div', { className: 'space-y-6' },
        phases.map(p => {
          const startOffset = p.startTs - phases[0].startTs;
          const duration = p.elapsedMs || ((p.endTs || p.startTs) - p.startTs) || 1;
          const widthPct = Math.max(0.5, (duration / maxTotal) * 100);
          const leftPct = (startOffset / maxTotal) * 100;

          const subEvents = parsedEvents.value.filter(e => e.ch === 'STARTUP' && e.phase === p.phase && e.ev === 'startup.phase_start' && e.subPhase && e.subPhase !== String(p.phase));

          return h('div', { key: p.phase, className: 'flex flex-col gap-2' },
            h('div', { className: 'flex justify-between items-center text-sm' },
               h('span', { className: 'font-medium flex items-center gap-2' }, 
                 h(Badge, { variant: p.phase === 5 ? 'secondary' : 'outline', className: 'px-1 py-0 h-5 min-h-0 text-[11px]' }, `Phase ${p.phase}`),
                 p.name
               ),
               h('span', { className: 'font-bold font-mono text-xs' }, `${duration}ms`)
            ),
            h('div', { className: 'w-full bg-muted rounded-full h-4 overflow-hidden relative' },
               h('div', {
                 className: 'absolute top-0 bottom-0 bg-primary/80 rounded-full border border-primary/20 transition-all',
                 style: { left: `${leftPct}%`, width: `${widthPct}%` }
               })
            ),
            subEvents.length > 0 && h('div', { className: 'mt-1 mb-2 space-y-1' },
               subEvents.map((se, i) => {
                  const seEnd = parsedEvents.value.find(e => e.ch === 'STARTUP' && e.ev === 'startup.phase_end' && e.subPhase === se.subPhase) || se;
                  const seDuration = seEnd.ts - se.ts;
                  return h('div', { key: i, className: 'flex justify-between items-center text-xs text-muted-foreground ml-4 pl-4 border-l-2' },
                     h('span', null, `Sub-phase ${se.subPhase || ''}`),
                     h('span', { className: 'font-mono' }, seDuration > 0 ? `+${seDuration}ms` : '...')
                  );
               })
            )
          );
        })
      )
    )
  );
}

function PhaseDetailCards() {
  const events = parsedEvents.value.filter(e => e.ch === 'STARTUP');
  if (events.length === 0) return null;

  return h(Card, { className: 'mb-8' },
    h(CardHeader, null, h(CardTitle, { className: 'text-lg' }, 'Phase Execution Details')),
    h(CardContent, null,
      h('div', { className: 'grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4' },
         events.filter(e => e.ev === 'startup.phase_start' && !e.subPhase?.includes('a') && !e.subPhase?.includes('b') && !e.subPhase?.includes('c')).map(e => 
           h('div', { key: `${e.phase}_${e.ts}`, className: 'p-4 rounded-xl border bg-card/60 shadow-sm flex flex-col gap-2' },
             h('div', { className: 'flex items-center gap-2 mb-1' },
               h(Badge, { variant: 'default', className: 'bg-blue-500 hover:bg-blue-600' }, `Phase ${e.phase}`),
               h('span', { className: 'text-xs text-muted-foreground font-mono' }, new Date(e.ts).toISOString().split('T')[1].replace('Z', ''))
             ),
             h('p', { className: 'text-sm font-medium' }, e.message || `Phase ${e.phase} execution started`),
             // we could extract specifics if available in e.message, fallback to generic
             h('div', { className: 'text-xs text-muted-foreground mt-auto pt-2 border-t' }, `Tracked via ${e.tag}`)
           )
         )
      )
    )
  );
}

export class StartupPanel extends Component<{}, {}> {
  render() {
    return h('div', { className: 'animate-in fade-in slide-in-from-bottom-4 duration-500 py-6 max-w-7xl mx-auto w-full' },
      h('div', { className: 'mb-8 pb-4 border-b' },
        h('h2', { className: 'text-3xl font-bold tracking-tight text-foreground' }, 'App Boot Timeline'),
        h('p', { className: 'text-muted-foreground mt-2' }, 'Waterfall analysis of startup orchestrator phases.')
      ),
      h(StartupWaterfall, null),
      h(PhaseDetailCards, null)
    );
  }
}
