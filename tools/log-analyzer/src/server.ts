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

import { readdir } from 'node:fs/promises';
import { join } from 'node:path';

// Get a list of the diagnostic logs available
app.get('/api/logs', async (c) => {
  const logDir = '../../device_logs';
  try {
    const files = await readdir(logDir);
    return c.json({ files: files.filter(f => f.endsWith('.txt')) });
  } catch (err) {
    return c.json({ error: String(err) }, 500);
  }
});

// Get parsed diagnostic log data
app.get('/api/logs/:filename', async (c) => {
  const filename = c.req.param('filename');
  if (!/^[a-zA-Z0-9_\-\.]+$/.test(filename) || filename.includes('..')) {
    return c.json({ error: 'Invalid filename' }, 400);
  }

  const query = c.req.query();
  const filepath = join('../../device_logs', filename); 
  const logFile = Bun.file(filepath);
  
  if (!(await logFile.exists())) {
    return c.json({ error: 'File not found' }, 404);
  }
  
  const text = await logFile.text();
  const lines = text.split(/\r?\n/);
  
  const results = [];
  let currentSegment = 'General';
  let segmentFile = '';

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    if (!line.trim() || line.startsWith('===')) continue;
    if (line.startsWith('--- ')) {
      const match = line.match(/--- (.+?) \((.+?)\) ---/);
      if (match) {
        currentSegment = match[1];
        segmentFile = match[2];
      } else {
        currentSegment = line.replace(/---/g, '').trim();
        segmentFile = '';
      }
      continue;
    }
    
    if (line.match(/^\d{2}:\d{2}:\d{2}\.\d{3}/)) {
      const parts = line.split(' | ');
      let time = parts[0]?.trim();
      let level = 'INFO';
      let category = '';
      let tag = '';
      let message = '';
      
      if (parts.length >= 5) {
        level = parts[1].trim();
        category = parts[2].trim();
        tag = parts[3].trim();
        message = parts.slice(4).join(' | ').trim();
      } else if (parts.length === 4) {
        category = parts[1].trim();
        tag = parts[2].trim();
        message = parts.slice(3).join(' | ').trim();
      } else {
        message = line;
      }
      
      results.push({
        lineNumber: i + 1,
        segment: currentSegment,
        segmentFile,
        time,
        level,
        category,
        tag,
        message,
        // Including raw might make payload too bloated, let's omit or only send if requested. Wait, `raw: line` is okay.
        raw: line
      });
    } else {
      if (results.length > 0 && !line.includes('═════════') && !line.includes('SESSION START')) {
        results[results.length - 1].message += '\n' + line;
        results[results.length - 1].raw += '\n' + line;
      }
    }
  }

  let filtered = results;
  if (query.level) {
    filtered = filtered.filter(r => r.level.toUpperCase() === query.level.toUpperCase());
  }
  if (query.category) {
    filtered = filtered.filter(r => r.category.toUpperCase() === query.category.toUpperCase());
  }
  if (query.tag) {
    filtered = filtered.filter(r => r.tag.toLowerCase().includes(query.tag.toLowerCase()));
  }
  if (query.q) {
    filtered = filtered.filter(r => r.message.toLowerCase().includes(query.q.toLowerCase()));
  }

  return c.json({ total: filtered.length, data: filtered });
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
