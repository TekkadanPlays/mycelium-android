import { Component } from 'inferno';
import { createElement as h } from 'inferno-create-element';
import { effect } from '@preact/signals-core';
import { parsedSpans, filterTraceId, type TraceSpan } from '../signals';

export class TraceExplorer extends Component<{}, {}> {
   private dispose: (() => void) | null = null;
   
   componentDidMount() {
      this.dispose = effect(() => {
          this.forceUpdate();
      });
   }
   
   componentWillUnmount() {
      this.dispose?.();
   }
   
   render() {
       const spans = parsedSpans.value;
       if (spans.length === 0) return null; 

       let minTime = Infinity;
       let maxTime = -Infinity;
       
       for (const s of spans) {
           if (s.startTimeMs < minTime) minTime = s.startTimeMs;
           const end = s.endTimeMs ?? s.startTimeMs + 10;
           if (end > maxTime) maxTime = end;
       }

       const duration = maxTime - minTime;
       if (duration <= 0) return null;

       // Group by channel
       const channels = new Map<string, TraceSpan[]>();
       for (const s of spans) {
           if (!channels.has(s.channel)) channels.set(s.channel, []);
           channels.get(s.channel)!.push(s);
       }

       return h('div', { className: 'w-full bg-background border border-border rounded-lg p-4 my-4 overflow-x-auto shadow-sm flex flex-col gap-6' }, 
           h('div', { className: 'text-xs font-semibold tracking-wider text-muted-foreground uppercase flex items-center justify-between' }, 
              h('span', null, 'Application Lifecycle Traces'),
              h('span', { className: 'bg-muted px-2 py-0.5 rounded-full text-[10px]' }, `${spans.length} Operations`)
           ),
           ...Array.from(channels.entries()).map(([channel, channelSpans]) => {
               // Sort by start time for packing
               channelSpans.sort((a, b) => a.startTimeMs - b.startTimeMs);
               
               // Compute lanes using a greedy interval packing algorithm
               const lanes: number[] = [];
               const spanLanes = new Map<string, number>();
               for (const span of channelSpans) {
                   let placed = false;
                   for (let i = 0; i < lanes.length; i++) {
                       if (lanes[i] <= span.startTimeMs) {
                           lanes[i] = span.endTimeMs ?? maxTime;
                           spanLanes.set(span.id, i);
                           placed = true;
                           break;
                       }
                   }
                   if (!placed) {
                       lanes.push(span.endTimeMs ?? maxTime);
                       spanLanes.set(span.id, lanes.length - 1);
                   }
               }
               
               const laneHeight = 28;
               const gap = 4;
               const containerHeight = lanes.length * (laneHeight + gap);

               return h('div', { className: 'flex flex-col' },
                   h('div', { className: 'text-[11px] font-bold text-foreground mb-2 flex items-center gap-2' }, 
                       h('span', { className: `w-2 h-2 rounded-full ${getChannelColor(channel)}` }),
                       channel
                   ),
                   h('div', { className: 'relative w-full min-w-[800px]', style: { height: `${containerHeight}px` } },
                       ...channelSpans.map(span => {
                           const startPct = ((span.startTimeMs - minTime) / duration) * 100;
                           const end = span.endTimeMs ?? maxTime; 
                           const widthPct = Math.max(0.5, ((end - span.startTimeMs) / duration) * 100);
                           const isOngoing = !span.endTimeMs;
                           const durationStr = isOngoing ? 'ongoing' : `${end - span.startTimeMs}ms`;
                           const laneIndex = spanLanes.get(span.id) || 0;
                           const topOffset = laneIndex * (laneHeight + gap);
                           const isSelected = filterTraceId.value === span.id;
                           
                           return h('div', { 
                              className: `absolute rounded-md border text-[10px] flex items-center px-2 cursor-pointer transition-colors overflow-hidden group hover:opacity-100 ${isSelected ? 'ring-2 ring-ring ring-offset-1 z-10 ' : 'z-0 opacity-80 '}${isOngoing ? 'bg-amber-500/20 border-amber-500/50 text-amber-700 dark:text-amber-400' : 'bg-primary/20 border-primary/40 hover:bg-primary/30 text-primary'}`,
                              style: { left: `${startPct}%`, width: `${widthPct}%`, minWidth: '4px', top: `${topOffset}px`, height: `${laneHeight}px` },
                              title: `${span.name} (${durationStr})`,
                              onClick: () => {
                                  filterTraceId.value = isSelected ? '' : span.id;
                              }
                           },
                              widthPct > 3 ? h('span', { className: `truncate whitespace-nowrap ${isSelected ? 'font-bold' : 'font-semibold'}` }, span.name) : null,
                              h('div', { className: 'absolute right-2 opacity-0 group-hover:opacity-100 transition-opacity pointer-events-none' }, 
                                 h('span', { className: 'text-[9px] text-primary/70 font-mono' }, durationStr)
                              )
                           );
                       })
                   )
               );
           })
       );
   }
}

function getChannelColor(channel: string) {
    if (channel === 'FEED') return 'bg-blue-500';
    if (channel === 'RELAY') return 'bg-amber-500';
    if (channel === 'SYNC') return 'bg-emerald-500';
    if (channel === 'STARTUP') return 'bg-purple-500';
    return 'bg-gray-500';
}
