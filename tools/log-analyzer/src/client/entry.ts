import { render } from 'inferno';
import { createElement } from 'inferno-create-element';
import { App } from './App';

const root = document.getElementById('app');
if (root) {
  render(createElement(App, null), root);
}
