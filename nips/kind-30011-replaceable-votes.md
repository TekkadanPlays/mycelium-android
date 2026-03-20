# Kind 30011: Parameterized Replaceable Votes

`draft` `optional`

This NIP defines a parameterized replaceable event (kind `30011`) for expressing upvotes, downvotes, and vote cancellations on Nostr events. It is designed for use cases where aggregate sentiment scoring is needed — such as topic ranking, content curation, and community moderation — while preserving the one-vote-per-user-per-target invariant enforced by the replaceable event model.

## Motivation

Existing reaction mechanisms (kind 7, NIP-25) are append-only: each reaction is a new event, making it impossible to change or retract a vote without publishing a deletion event. They also lack a standardized semantic for downvotes or net scoring.

Kind 30011 solves this by using **parameterized replaceable events** (NIP-33). Because each user can only have one event per `d` tag value, voting is inherently deduplicated — a user's latest vote replaces any previous vote on the same target. This enables:

- **Toggle semantics**: voting +1 when already +1 publishes 0 (cancel)
- **Flip semantics**: voting +1 when already -1 publishes +1 (replace)
- **Net scoring**: aggregating all voters' latest values yields a reliable score
- **Efficient relay queries**: `kinds=[30011]` + `#e=[<target>]` returns at most one event per voter

## Event Format

```json
{
  "kind": 30011,
  "content": "<vote_value>",
  "tags": [
    ["d", "vote:<target_event_id>"],
    ["e", "<target_event_id>", "<relay_hint>"],
    ["p", "<target_event_author_pubkey>"],
    ["k", "<target_event_kind>"]
  ]
}
```

### Fields

| Field | Description |
|-------|-------------|
| `kind` | `30011` (parameterized replaceable per NIP-33) |
| `content` | Vote value as a string: `"1"` (upvote), `"-1"` (downvote), or `"0"` (cancel/retract) |
| `pubkey` | The voter's public key (standard Nostr event field) |
| `created_at` | Unix timestamp (standard Nostr event field); latest wins for deduplication |

### Tags

| Tag | Required | Description |
|-----|----------|-------------|
| `d` | **Yes** | `"vote:<target_event_id>"` — the replaceable event identifier. Ensures one vote per voter per target. |
| `e` | **Yes** | The target event ID being voted on. An optional relay hint MAY be included as the third element. |
| `p` | **Yes** | The public key of the target event's author. Enables notifications and author-scoped queries. |
| `k` | Recommended | The kind of the target event as a string (e.g. `"1"` for notes, `"11"` for topics, `"1111"` for comments). Enables kind-scoped filtering. |

## Vote Semantics

### Values

| Value | Meaning |
|-------|---------|
| `"1"` | Upvote (positive sentiment) |
| `"-1"` | Downvote (negative sentiment) |
| `"0"` | Cancel / retract (neutral — removes the voter's influence from the score) |

Clients SHOULD treat any `content` value that is not parseable as an integer as invalid and ignore the event.

### Toggle Behavior

When a user votes in the same direction as their current vote, the vote is cancelled. When voting in the opposite direction, the vote flips:

| Current Vote | User Action | Published Value |
|-------------|-------------|-----------------|
| (none) | Upvote | `"1"` |
| (none) | Downvote | `"-1"` |
| `"1"` | Upvote | `"0"` (cancel) |
| `"1"` | Downvote | `"-1"` (flip) |
| `"-1"` | Downvote | `"0"` (cancel) |
| `"-1"` | Upvote | `"1"` (flip) |

## Deduplication

Because kind 30011 is a **parameterized replaceable event** (NIP-33), relays MUST store only the latest event per `pubkey` + `d` tag combination. This means:

- Each user can have at most **one active vote per target event**
- Publishing a new vote on the same target automatically replaces the previous one
- No explicit deletion events are needed to change or retract a vote

### Client-Side Deduplication

When aggregating votes from multiple relays, clients SHOULD:

1. Maintain a per-target, per-voter map: `target_event_id → { voter_pubkey → (value, created_at) }`
2. For each incoming vote event, only accept it if `created_at` is strictly greater than the existing entry for that voter
3. Compute scores by summing all voters' latest values

## Querying

### Fetch votes for specific events

```json
["REQ", "<sub_id>", {
  "kinds": [30011],
  "#e": ["<target_event_id_1>", "<target_event_id_2>"]
}]
```

### Fetch a specific user's votes

```json
["REQ", "<sub_id>", {
  "kinds": [30011],
  "authors": ["<voter_pubkey>"]
}]
```

### Fetch votes on events of a specific kind

```json
["REQ", "<sub_id>", {
  "kinds": [30011],
  "#e": ["<target_event_id>"],
  "#k": ["11"]
}]
```

## Score Computation

The **net score** for a target event is:

```
score = count(votes where value > 0) - count(votes where value < 0)
```

Votes with value `0` are cancellations and MUST NOT be counted as either upvotes or downvotes.

Clients MAY also display separate upvote and downvote counts for transparency.

## Optimistic Updates

Clients SHOULD apply vote changes optimistically in the UI before relay confirmation:

1. On user tap, immediately update the displayed vote state and score
2. Publish the kind 30011 event to relays
3. On relay echo, verify consistency (the relay's version should match due to same `d` tag)
4. On publish failure, revert the optimistic update and notify the user

To prevent relay echoes from regressing an optimistic update, clients SHOULD assign the optimistic entry a sentinel timestamp (e.g. `MAX_VALUE`) that is always greater than any real `created_at`.

## Use Cases

### Topic Ranking

Kind 30011 votes are used to rank kind-11 topic events by community sentiment. The `"k"` tag with value `"11"` identifies these votes as topic-scoped. Aggregated scores enable sort orders like "Most Popular" in topic discovery views.

### Content Curation

Votes on kind-1 notes enable community-driven content ranking beyond simple reaction counts. Unlike kind-7 reactions which can only express positive sentiment (or a limited emoji set), kind 30011 provides a clear positive/negative signal.

### Comment Quality

Votes on kind-1111 comment events enable threaded discussions with quality signals, similar to forum upvote/downvote systems.

## Relation to Other NIPs

| NIP | Relation |
|-----|----------|
| NIP-25 (Reactions) | Kind 7 reactions are append-only and lack standardized downvote semantics. Kind 30011 provides replaceable, bidirectional voting. Both can coexist. |
| NIP-33 (Parameterized Replaceable Events) | Kind 30011 relies on NIP-33's replaceable semantics for deduplication. |
| NIP-10 (Event References) | The `e` and `p` tags follow NIP-10 conventions for referencing target events and authors. |

## Example

Alice upvotes Bob's topic (kind 11, event ID `abc123`):

```json
{
  "kind": 30011,
  "pubkey": "<alice_pubkey>",
  "created_at": 1710000000,
  "content": "1",
  "tags": [
    ["d", "vote:abc123"],
    ["e", "abc123", "wss://relay.example.com"],
    ["p", "<bob_pubkey>"],
    ["k", "11"]
  ],
  "id": "<event_id>",
  "sig": "<signature>"
}
```

Alice changes her mind and cancels the vote:

```json
{
  "kind": 30011,
  "pubkey": "<alice_pubkey>",
  "created_at": 1710000060,
  "content": "0",
  "tags": [
    ["d", "vote:abc123"],
    ["e", "abc123", "wss://relay.example.com"],
    ["p", "<bob_pubkey>"],
    ["k", "11"]
  ],
  "id": "<event_id_2>",
  "sig": "<signature_2>"
}
```

The relay replaces the first event with the second (same `pubkey` + `d` tag). Clients querying votes for `abc123` now see Alice's vote as `0` (neutral) and exclude it from the score.
