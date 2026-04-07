# 🍄 Local Log Analyzer

This tool provides a rapid, entirely localized, and token-optimized way to view your Mycelium application logs directly in your browser. 
It uses preact signals, blazecn, and InfernoJS on top of the ultra-fast Bun runtime to instantly parse tens of thousands of log lines without ever sending your sensitive device data to a server.

## Installation

### Prerequisites
You need to have [Bun](https://bun.sh) installed.

### Setup
1. Open a terminal and navigate to this directory:
   ```bash
   cd tools/log-analyzer
   ```
2. Install the necessary packages:
   ```bash
   bun install
   ```

## Running the Log Analyzer

To serve the UI locally, simply run:
```bash
bun run start
```
*Note: If you make changes to the source code, use `bun run dev` to actively bundle changes while you develop.*

Once running, navigate to `http://localhost:3000` in your web browser.

## How to use
1. Once you compile `.log` or `.txt` diagnostic logs from Mycelium (using Android device logcat or Mycelium's export feature), drag and drop them into the web analyzer.
2. Select desired Warning/Error filtering layers, or drill down specifically to your offending tag (e.g. `NotesRepository` or `RelayHealthTracker`).
3. If you want to use LLM assistance to debug, use the **Copy Optimized for AI** button to copy exactly your filtered logs into a prompt without consuming excessive tokens over massive dumps.
