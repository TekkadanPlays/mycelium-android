// src/plugins/pluginRegistry.ts
// Simple static plugin system for the Log Analyzer UI.
// Plugins can be registered manually via `registerPlugin`.

import { createElement as h } from 'inferno-create-element';
import { Component } from 'inferno';

export interface Plugin {
  name: string;
  Component: any; // Inferno component type
}

export const pluginRegistry: Plugin[] = [];

/** Register a plugin manually (useful for tests or static plugins) */
export function registerPlugin(plugin: Plugin) {
  if (!pluginRegistry.find(p => p.name === plugin.name)) {
    pluginRegistry.push(plugin);
  }
}

/** Component that renders all registered plugins (currently none) */
export class PluginsContainer extends Component<{}, {}> {
  render() {
    return h('div', { className: 'plugins-container space-y-4' },
      pluginRegistry.map(p =>
        h('div', { key: p.name, className: 'plugin-wrapper' },
          h(p.Component, null)
        )
      )
    );
  }
}
