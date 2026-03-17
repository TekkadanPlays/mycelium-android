# LNURL Proxy Server Architecture

## Purpose

Bridge the gap between LNURL-pay (DNS/HTTPS-based) and the embedded Phoenix Lightning
wallet (Nostr relay-based via NWC). This allows any Lightning client to zap a Mycelium
user by resolving their Lightning Address, even though the wallet lives on a mobile device
with no public IP or domain.

## Overview

```
Zap sender                    mycelium.social               Nostr relay           User's phone
    │                              │                            │                      │
    │ GET /.well-known/            │                            │                      │
    │   lnurlp/{npub}             │                            │                      │
    │─────────────────────────────>│                            │                      │
    │                              │  kind-23194 make_invoice   │                      │
    │                              │  (NIP-47, NIP-04 encrypted)│                      │
    │                              │───────────────────────────>│                      │
    │                              │                            │  kind-23194          │
    │                              │                            │─────────────────────>│
    │                              │                            │                      │
    │                              │                            │  kind-23195 response │
    │                              │                            │<─────────────────────│
    │                              │  kind-23195 (bolt11)       │                      │
    │                              │<───────────────────────────│                      │
    │     { "pr": "lnbc1..." }     │                            │                      │
    │<─────────────────────────────│                            │                      │
    │                              │                            │                      │
    │ pays bolt11 invoice          │                            │                      │
    │──────────────────────────────────────────────────────────────────────────────────>│
    │                              │                            │                  ⚡ received
```

## Components

### 1. LNURL-pay Endpoint (HTTPS)

**Route:** `GET https://mycelium.social/.well-known/lnurlp/{identifier}`

Where `{identifier}` is the user's npub (bech32) or hex pubkey.

**Response (LNURL-pay metadata):**
```json
{
  "status": "OK",
  "tag": "payRequest",
  "callback": "https://mycelium.social/api/lnurl/pay/{identifier}",
  "minSendable": 1000,
  "maxSendable": 100000000000,
  "metadata": "[[\"text/plain\",\"Zap to {identifier}\"],[\"text/identifier\",\"{identifier}@mycelium.social\"]]",
  "allowsNostr": true,
  "nostrPubkey": "{server_hex_pubkey}"
}
```

### 2. Pay Callback Endpoint (HTTPS)

**Route:** `GET https://mycelium.social/api/lnurl/pay/{identifier}?amount={msats}&nostr={zap_request}`

**Flow:**
1. Validate `amount` is within range
2. Look up the user's NWC service pubkey from a registration database
3. Create a NIP-47 `make_invoice` request (kind 23194):
   - Encrypt to the user's NWC service pubkey
   - Sign with the server's NWC client key
   - Include amount and description hash
4. Publish to the NWC relay
5. Subscribe for kind-23195 response (with timeout ~15s)
6. Extract bolt11 invoice from response
7. Return `{ "status": "OK", "pr": "lnbc1..." }`

### 3. User Registration

When a user creates a wallet in the Mycelium app, the app should register with the
proxy server so it knows which NWC service pubkey maps to which npub.

**Route:** `POST https://mycelium.social/api/lnurl/register`
```json
{
  "npub": "npub1abc...",
  "nwc_service_pubkey": "abcdef1234...",
  "nwc_relay": "wss://relay.damus.io",
  "signature": "..."
}
```

The request must be signed by the user's Nostr identity to prove ownership.
The server stores the mapping: `npub → (nwc_service_pubkey, nwc_relay)`.

### 4. Lightning Address

Users get a Lightning Address of the form:

```
npub1abc123@mycelium.social
```

Or optionally, if NIP-05 is integrated:

```
alice@mycelium.social
```

## Server Implementation Notes

### Tech Stack
- Any HTTP framework (Express, Fastify, Hono, etc.)
- A Nostr client library for signing/encrypting NIP-47 events
- WebSocket connection to NWC relay(s)
- Simple key-value store for npub→NWC mapping (Redis, SQLite, Postgres)

### Server Keypair
The server needs its own Nostr keypair to sign NWC requests. This is the "client"
side of the NWC connection — the server acts as a client requesting invoices from
the user's wallet.

### Timeout Handling
If the user's wallet is offline (phone off, no internet), the `make_invoice` request
will time out. The server should return an appropriate LNURL error:
```json
{ "status": "ERROR", "reason": "Wallet is offline. Try again later." }
```

### Security Considerations
- Rate limit the pay callback per IP and per identifier
- Validate zap request signatures (NIP-57)
- The server never holds funds — it only brokers invoice creation
- NWC traffic is NIP-04 encrypted end-to-end (server cannot read other NWC traffic)
- Registration endpoint must verify Nostr signatures to prevent impersonation

### Scaling
The proxy is stateless except for the npub→NWC mapping. It holds no funds,
no channels, no wallet state. A single server can handle thousands of users
since it only relays NWC messages.

## App-Side Integration (Future)

When the Phoenix wallet is created in the Mycelium app:
1. `NwcServiceProvider.start()` begins listening for NWC requests
2. App calls `POST /api/lnurl/register` with the user's npub and NWC service pubkey
3. User's Lightning Address becomes active: `{npub}@mycelium.social`
4. The user can set `lud16: "{npub}@mycelium.social"` in their Nostr profile (kind-0)

## Dependencies on App Side (Already Implemented)
- `NwcServiceProvider.kt` — Handles `make_invoice`, `pay_invoice`, `get_balance`
- `PhoenixWalletManager.kt` — Creates invoices and processes payments
- `RelayConnectionStateMachine` — Manages relay subscriptions for NWC traffic
