// src/client/GlobalNav.ts
// Global navigation component that provides links to all main tabs/modes.
// This component is used across the entire Log Analyzer UI so users can
// switch between "Plain Logs", "Event Analysis", and "Feed Inspector"
// from any page.

import { Component } from 'inferno';
import { createElement as h } from 'inferno-create-element';
import { Button, Badge } from 'blazecn';
import { fileMode, parsedLogs, parsedEvents } from '../signals';

export class GlobalNav extends Component<{}, {}> {
  render() {
    // Determine which mode is currently active for styling
    const mode = fileMode.value;
    const hasLogs = parsedLogs.value.length > 0;
    const hasEvents = parsedEvents.value.length > 0;
    const hasFeed = hasLogs && parsedLogs.value.some((l: any) => l.channel === 'FEED');

    return h('nav', { className: 'border-b bg-background/80 backdrop-blur-md sticky top-0 z-50' },
      h('div', { className: 'mx-auto flex h-14 w-full items-center justify-between px-6 max-w-[1600px]' },
        // Left side: logo and title
        h('div', { className: 'flex items-center gap-2.5' },
          h('div', { className: 'size-7 rounded-lg bg-primary flex items-center justify-center text-primary-foreground text-sm font-bold' }, '🍄'),
          h('span', { className: 'font-bold text-base tracking-tight' }, 'Local Log Analyzer'),
          // Badge showing counts
          (fileMode.value === 'events' && parsedEvents.value.length > 0) ?
            h(Badge, { variant: 'secondary', className: 'ml-2 text-xs tabular-nums hidden sm:inline-flex' }, `${parsedEvents.value.length} events`) :
            (parsedLogs.value.length > 0) ?
              h(Badge, { variant: 'secondary', className: 'ml-2 text-xs tabular-nums hidden sm:inline-flex' }, `${parsedLogs.value.length} total`) : null
        ),
        // Right side: navigation buttons
        h('div', { className: 'flex items-center gap-3' },
          // Plain Logs button
          h(Button, {
            variant: mode === 'logs' ? 'secondary' : 'ghost',
            className: `text-xs ${mode === 'logs' ? '' : 'opacity-50'}`,
            onClick: () => { fileMode.value = 'logs'; }
          }, 'Plain Logs'),
          // Event Analysis button (only show if events exist)
          hasEvents ? h(Button, {
            variant: mode === 'events' ? 'secondary' : 'ghost',
            className: `text-xs ${mode === 'events' ? '' : 'opacity-50'}`,
            onClick: () => { fileMode.value = 'events'; }
          }, 'Event Analysis') : null,
          // Feed Inspector button (only show if FEED logs exist)
          hasFeed ? h(Button, {
            variant: mode === 'feed' ? 'secondary' : 'ghost',
            className: `text-xs ${mode === 'feed' ? '' : 'opacity-50'}`,
            onClick: () => { fileMode.value = 'feed'; }
          }, 'Feed Inspector') : null
        )
      )
    );
  }
}
