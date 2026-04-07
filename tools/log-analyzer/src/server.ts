import { Hono } from 'hono';
import { serveStatic } from 'hono/bun';
import { html } from './template';

const app = new Hono();

// Static files
app.use('/public/*', serveStatic({ root: './' }));

// API route — demonstrates Hono's lightweight JSON API
app.get('/api/status', (c) => {
  return c.json({
    app: 'Mycelium Log Analyzer',
    stack: ['Bun', 'Hono', 'InfernoJS', 'Blazecn', 'Preact Signals'],
    uptime: process.uptime(),
    timestamp: Date.now(),
  });
});

// ---------------------------------------------------------------------------
// Benchmark endpoints — used by the live demo to measure real round-trip time
// ---------------------------------------------------------------------------

// Bare-minimum response — measures pure routing + serialization overhead
app.get('/api/ping', (c) => c.json({ t: Date.now() }));

// CPU-bound work — Fibonacci(30) computed synchronously
app.get('/api/fib', (c) => {
  const fib = (n: number): number => n <= 1 ? n : fib(n - 1) + fib(n - 2);
  const start = performance.now();
  const result = fib(30);
  return c.json({ result, computeMs: +(performance.now() - start).toFixed(2), t: Date.now() });
});

// Crypto hash — SHA-256 a random payload
app.get('/api/hash', async (c) => {
  const data = crypto.getRandomValues(new Uint8Array(1024));
  const start = performance.now();
  const hash = await crypto.subtle.digest('SHA-256', data);
  const hex = [...new Uint8Array(hash)].map(b => b.toString(16).padStart(2, '0')).join('');
  return c.json({ hash: hex.slice(0, 16) + '...', computeMs: +(performance.now() - start).toFixed(2), t: Date.now() });
});

// SSR shell — serves the HTML with client bundle
app.get('*', (c) => {
  return c.html(html());
});

export default {
  port: 3000,
  fetch: app.fetch,
};

console.log('🍄 Log Analyzer is live on http://localhost:3000');
