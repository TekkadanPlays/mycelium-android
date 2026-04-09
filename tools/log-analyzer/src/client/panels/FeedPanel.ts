import { Component } from 'inferno';
import { createElement as h } from 'inferno-create-element';
import { HomeFeedInspector } from '../HomeFeedInspector';
// If we had a specific EventsView chart, we could include it here.
// For now, we'll wrap HomeFeedInspector.
import { EventsView } from '../EventsView';
import { fileMode } from '../../signals';
import { Button } from 'blazecn';

export class FeedPanel extends Component<{}, { view: 'inspector' | 'events' }> {
  state: { view: 'inspector' | 'events' } = { view: 'inspector' };

  render() {
    return h('div', { className: 'h-full flex flex-col' },
      h('div', { className: 'flex items-center justify-between mb-4 px-4 pt-4' },
        h('h2', { className: 'text-2xl font-bold tracking-tight' }, 'Feed Pipeline'),
        h('div', { className: 'flex gap-2' },
          h(Button, { 
             variant: this.state.view === 'inspector' ? 'secondary' : 'ghost', 
             size: 'sm',
             onClick: () => this.setState({ view: 'inspector' }) 
          }, 'Feed Metrics & Pipeline'),
          h(Button, { 
             variant: this.state.view === 'events' ? 'secondary' : 'ghost', 
             size: 'sm',
             onClick: () => this.setState({ view: 'events' }) 
          }, 'Legacy Event Analysis')
        )
      ),
      h('div', { className: 'flex-1 overflow-auto' },
         this.state.view === 'inspector' 
            ? h(HomeFeedInspector, null)
            : h(EventsView, null)
      )
    );
  }
}
