<#
.SYNOPSIS
    Mycelium log analyzer — extracts key diagnostic sections from exported device logs.

.PARAMETER Path
    Path to the log file. Defaults to the newest file in device_logs/.

.PARAMETER Section
    Which section(s) to show. Comma-separated. Options:
      all, summary, startup, feed, pagination, outbox, relay, errors, timeline, notifications
    Default: summary

.EXAMPLE
    .\scripts\parse-logs.ps1                           # summary of newest log
    .\scripts\parse-logs.ps1 -Section feed,pagination  # feed + pagination details
    .\scripts\parse-logs.ps1 -Section all              # everything
    .\scripts\parse-logs.ps1 -Path .\device_logs\mycelium_logs_20260406_100740t.txt -Section outbox
#>
param(
    [string]$Path,
    [string]$Section = "summary"
)

$ErrorActionPreference = "Stop"

# Auto-detect newest log if no path given
if (-not $Path) {
    $logDir = Join-Path $PSScriptRoot "..\device_logs"
    if (Test-Path $logDir) {
        $newest = Get-ChildItem $logDir -Filter "mycelium_logs_*.txt" | Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if ($newest) { $Path = $newest.FullName }
    }
    if (-not $Path) { Write-Error "No log file found. Pass -Path explicitly."; exit 1 }
}

if (-not (Test-Path $Path)) { Write-Error "File not found: $Path"; exit 1 }

$lines = [System.IO.File]::ReadAllLines($Path)
$sections = $Section.ToLower() -split ","
$showAll = $sections -contains "all"

function Write-Header($title) {
    Write-Host ""
    Write-Host ("=" * 70) -ForegroundColor Cyan
    Write-Host "  $title" -ForegroundColor Cyan
    Write-Host ("=" * 70) -ForegroundColor Cyan
}

function Match-Lines($pattern, [int]$max = 200) {
    $count = 0
    foreach ($line in $lines) {
        if ($line -match $pattern) {
            Write-Host $line
            $count++
            if ($count -ge $max) { Write-Host "  ... ($count lines shown, truncated)" -ForegroundColor DarkGray; break }
        }
    }
    if ($count -eq 0) { Write-Host "  (none)" -ForegroundColor DarkGray }
    return $count
}

# ── SUMMARY ──────────────────────────────────────────────────────────────
if ($showAll -or $sections -contains "summary") {
    Write-Header "LOG SUMMARY"

    $totalLines = $lines.Count
    Write-Host "  File: $(Split-Path $Path -Leaf)"
    Write-Host "  Lines: $totalLines"

    # Session info
    $session = $lines | Where-Object { $_ -match "SESSION START:" } | Select-Object -First 1
    if ($session) { Write-Host "  $($session.Trim())" }
    $version = $lines | Where-Object { $_ -match "version=" } | Select-Object -First 1
    if ($version) { Write-Host "  $($version.Trim())" }

    # Count by category
    $categories = @{}
    foreach ($line in $lines) {
        if ($line -match "^\d{2}:\d{2}:\d{2}\.\d{3} \| [DWIEV] \| (\w+)") {
            $cat = $Matches[1]
            if (-not $categories.ContainsKey($cat)) { $categories[$cat] = 0 }
            $categories[$cat]++
        }
    }
    Write-Host ""
    Write-Host "  Log lines by category:" -ForegroundColor Yellow
    $categories.GetEnumerator() | Sort-Object Value -Descending | ForEach-Object {
        Write-Host ("    {0,-20} {1,6}" -f $_.Key, $_.Value)
    }

    # Errors/warnings count
    $errors = ($lines | Where-Object { $_ -match "\| E \|" }).Count
    $warnings = ($lines | Where-Object { $_ -match "\| W \|" }).Count
    Write-Host ""
    Write-Host "  Errors: $errors  |  Warnings: $warnings" -ForegroundColor $(if ($errors -gt 0) { "Red" } else { "Green" })

    # Feed stats
    $flushed = $lines | Where-Object { $_ -match "Flushed \d+ events" }
    $roomTail = $lines | Where-Object { $_ -match "roomTail=" } | Select-Object -Last 1
    $displayFilter = $lines | Where-Object { $_ -match "displayFilter:" } | Select-Object -Last 1
    Write-Host ""
    Write-Host "  Feed flushes: $($flushed.Count)" -ForegroundColor Yellow
    if ($roomTail) { Write-Host "  Last roomTail: $($roomTail.Trim().Substring(0, [Math]::Min(120, $roomTail.Trim().Length)))" }
    if ($displayFilter) { Write-Host "  Last display: $($displayFilter.Trim().Substring(0, [Math]::Min(120, $displayFilter.Trim().Length)))" }

    # Outbox stats
    $outboxPag = ($lines | Where-Object { $_ -match "Pagination: \+\d+ events" }).Count
    $outboxExhausted = ($lines | Where-Object { $_ -match "ALL EXHAUSTED" }).Count
    Write-Host "  Outbox pagination cycles: $outboxPag$(if ($outboxExhausted -gt 0) { " (exhausted: $outboxExhausted)" })"

    # Relay connections
    $connected = ($lines | Where-Object { $_ -match "Connected to" }).Count
    $disconnected = ($lines | Where-Object { $_ -match "Disconnect|connection lost" }).Count
    Write-Host "  Relay connects: $connected  |  Disconnects: $disconnected"
}

# ── STARTUP ──────────────────────────────────────────────────────────────
if ($showAll -or $sections -contains "startup") {
    Write-Header "STARTUP PHASES"
    Match-Lines "STARTUP\s+\|.*Phase|STARTUP\s+\|.*outbox|STARTUP\s+\|.*Preload|STARTUP\s+\|.*follow|SESSION START"
}

# ── FEED ─────────────────────────────────────────────────────────────────
if ($showAll -or $sections -contains "feed") {
    Write-Header "FEED PIPELINE"

    Write-Host "`n--- Flush activity ---" -ForegroundColor Yellow
    Match-Lines "Flushed \d+ events|to feed.*to pending|roomTail|displayFilter:"

    Write-Host "`n--- Display updates ---" -ForegroundColor Yellow
    Match-Lines "displayFilter:|_displayedNotes|scheduleDisplay"
}

# ── PAGINATION ───────────────────────────────────────────────────────────
if ($showAll -or $sections -contains "pagination") {
    Write-Header "PAGINATION"

    Write-Host "`n--- Room pagination ---" -ForegroundColor Yellow
    Match-Lines "loadOlderNotes|loadPage|roomTail=|Room served|paginat.*exhaust|FeedWindow"

    Write-Host "`n--- Cursor advancement ---" -ForegroundColor Yellow
    Match-Lines "cursor=|advancePag|paginationCursor|stall"
}

# ── OUTBOX ───────────────────────────────────────────────────────────────
if ($showAll -or $sections -contains "outbox") {
    Write-Header "OUTBOX FEED"

    Write-Host "`n--- Discovery ---" -ForegroundColor Yellow
    Match-Lines "OutboxFeed.*discover|OutboxFeed.*relay|OutboxFeed.*author|OutboxFeed.*phase|OutboxFeed.*start|outbox feed start"

    Write-Host "`n--- Auto-pagination ---" -ForegroundColor Yellow
    Match-Lines "OutboxFeed.*Pagination|OutboxFeed.*Auto-pag|OutboxFeed.*exhaust|Outbox pagination:.*roomTail"
}

# ── RELAY ────────────────────────────────────────────────────────────────
if ($showAll -or $sections -contains "relay") {
    Write-Header "RELAY STATUS"

    Write-Host "`n--- Connections ---" -ForegroundColor Yellow
    Match-Lines "RELAY.*Connected to|RELAY.*Disconnect|RELAY.*connection lost|RELAY.*flagged|RELAY.*blocked|RELAY.*auth required|AUTH challenge" 80

    Write-Host "`n--- Health ---" -ForegroundColor Yellow
    Match-Lines "health.*score|sweepStale|idle relay|reconnect" 40

    Write-Host "`n--- Slots ---" -ForegroundColor Yellow
    Match-Lines "slot|active sub|queued sub" 30
}

# ── ERRORS ───────────────────────────────────────────────────────────────
if ($showAll -or $sections -contains "errors") {
    Write-Header "ERRORS & WARNINGS"

    Write-Host "`n--- Errors ---" -ForegroundColor Red
    Match-Lines "\| E \|" 50

    Write-Host "`n--- Key warnings ---" -ForegroundColor Yellow
    Match-Lines "OOM|heap|OutOfMemory|timeout.*fail|FEED.*failed|RELAY.*failed|exception" 50
}

# ── TIMELINE ─────────────────────────────────────────────────────────────
if ($showAll -or $sections -contains "timeline") {
    Write-Header "TIMELINE / EVENT TIMESTAMPS"
    Match-Lines "oldest|newest|floor=|window=|cursor=|since=|until=" 60
}

# ── NOTIFICATIONS ────────────────────────────────────────────────────────
if ($showAll -or $sections -contains "notifications") {
    Write-Header "NOTIFICATIONS"
    Match-Lines "NOTIFICATION.*Flushed|NOTIFICATION.*exhaust|NOTIFICATION.*target|notification.*push|fireAndroid" 40
}

Write-Host ""
