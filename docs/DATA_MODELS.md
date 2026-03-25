# Mycelium Android — Data Models Reference

> Last verified against source: v0.5.03 (versionCode 41)
> All types live in `app/src/main/java/social/mycelium/android/data/`

This document describes every data class in the `data/` package. These are the core types that flow through the entire application — from relay ingestion through repositories and ViewModels to Compose UI.

---

## Table of Contents

- [Note](#note)
- [Author](#author)
- [ThreadReply & ThreadedReply](#threadreply--threadedreply)
- [NoteWithReplies](#notewithreplies)
- [DirectMessage & Conversation](#directmessage--conversation)
- [NotificationData](#notificationdata)
- [LiveActivity & LiveChatMessage](#liveactivity--livechatmessage)
- [Relay Types](#relay-types)
- [RelayCategory & RelayProfile](#relaycategory--relayprofile)
- [RelayDiscovery Types (NIP-66)](#relaydiscovery-types-nip-66)
- [Draft](#draft)
- [UrlPreviewInfo](#urlpreviewinfo)
- [IMetaData (NIP-92)](#imetadata-nip-92)
- [Poll Types (NIP-88)](#poll-types-nip-88)
- [Zap Poll Types](#zap-poll-types)
- [MediaServer](#mediaserver)
- [AccountInfo](#accountinfo)
- [SyncedSettings (NIP-78)](#syncedsettings-nip-78)
- [UserProfile & AuthState](#userprofile--authstate)
- [Miscellaneous Types](#miscellaneous-types)
- [Cybin Library Types](#cybin-library-types)

---

## Note

**File:** `Note.kt` · **Annotation:** `@Immutable @Serializable`

The primary content unit in the application. Represents a kind-1 text note, kind-11 topic, kind-6 repost, kind-1068 poll, kind-6969 zap poll, or kind-30023 long-form article. Every feed item, thread root, and quoted note is a `Note`.

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `id` | `String` | — | Nostr event ID (64-char hex) |
| `author` | `Author` | — | Resolved author metadata |
| `content` | `String` | — | Note text content (may contain `nostr:` URIs, URLs, hashtags) |
| `timestamp` | `Long` | — | `created_at` in milliseconds |
| `likes` | `Int` | `0` | Kind-7 reaction count |
| `shares` | `Int` | `0` | Kind-6 repost count |
| `comments` | `Int` | `0` | Reply count (kind-1 or kind-1111) |
| `zapCount` | `Int` | `0` | Kind-9735 zap receipt count |
| `reactions` | `List<String>` | `[]` | NIP-25 emoji reactions (e.g. `["❤️", "🔥"]`) |
| `isLiked` | `Boolean` | `false` | Whether current user has reacted |
| `isShared` | `Boolean` | `false` | Whether current user has reposted |
| `mediaUrls` | `List<String>` | `[]` | Image/video URLs extracted from content |
| `mediaMeta` | `Map<String, IMetaData>` | `{}` | NIP-92 imeta metadata keyed by URL |
| `hashtags` | `List<String>` | `[]` | Hashtags from `t` tags |
| `urlPreviews` | `List<UrlPreviewInfo>` | `[]` | Resolved URL preview cards |
| `quotedEventIds` | `List<String>` | `[]` | Event IDs from `nostr:nevent1`/`nostr:note1` in content |
| `relayUrl` | `String?` | `null` | Primary relay URL this note was received from |
| `relayUrls` | `List<String>` | `[]` | All relay URLs this note was seen on (for relay orbs) |
| `isReply` | `Boolean` | `false` | True if this kind-1 is a NIP-10 reply (filtered from primary feed) |
| `rootNoteId` | `String?` | `null` | NIP-10 root e-tag (thread root) |
| `replyToId` | `String?` | `null` | NIP-10 reply e-tag (direct parent) |
| `kind` | `Int` | `1` | Nostr event kind (1, 6, 11, 1068, 6969, 30023) |
| `topicTitle` | `String?` | `null` | Subject/title for kind-11 topics and kind-1111 thread roots |
| `tags` | `List<List<String>>` | `[]` | Raw event tags for NIP-22 I tags, NIP-10 e tags, etc. |
| `originalNoteId` | `String?` | `null` | For reposts: the original note's event ID (dedup key) |
| `repostedByAuthors` | `List<Author>` | `[]` | Authors who reposted this note (kind-6) |
| `repostTimestamp` | `Long?` | `null` | Timestamp of the latest repost event |
| `mentionedPubkeys` | `List<String>` | `[]` | Pubkeys from p-tags (for reply chain auto-tagging) |
| `summary` | `String?` | `null` | NIP-23 article summary |
| `imageUrl` | `String?` | `null` | NIP-23 article cover image |
| `dTag` | `String?` | `null` | Parameterized replaceable event d-tag (NIP-23, NIP-33) |
| `pollData` | `PollData?` | `null` | NIP-88 poll data (non-null when kind == 1068) |
| `zapPollData` | `ZapPollData?` | `null` | Zap poll data (non-null when kind == 6969) |
| `publishState` | `PublishState?` | `null` | Publish lifecycle for locally-published notes. `@Transient` — not serialized. |

**Computed properties:**
- `repostedBy: Author?` — First reposter, convenience for NoteCard UI

**PublishState enum:** `Sending` → `Confirmed` → `Failed`

Drives the thin progress line on NoteCard: shimmer while sending, green on confirmed, red on failed, auto-clears after 3 seconds.

### How Notes Flow Through the System

```
WebSocket frame
  → CybinRelayPool (parse JSON → Event)
  → RelayConnectionStateMachine (kind routing)
  → NotesRepository.pendingKind1Events (ConcurrentLinkedQueue)
  → flushKind1Events() (120ms debounce, batch merge into StateFlow)
  → DashboardViewModel observes notesRepository.notes
  → DashboardScreen LazyColumn renders NoteCard per Note
```

---

## Author

**File:** `Note.kt` (same file as Note) · **Annotation:** `@Immutable @Serializable`

Resolved profile metadata for a Nostr user. Populated from kind-0 events via `ProfileMetadataCache`.

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `id` | `String` | — | Hex pubkey (64 chars) |
| `username` | `String` | — | `name` field from kind-0 (fallback: truncated npub) |
| `displayName` | `String` | — | `display_name` from kind-0 |
| `avatarUrl` | `String?` | `null` | `picture` URL from kind-0 |
| `isVerified` | `Boolean` | `false` | NIP-05 verification status |
| `about` | `String?` | `null` | Bio/about text from kind-0 |
| `nip05` | `String?` | `null` | NIP-05 identifier (e.g. `user@domain.com`) |
| `website` | `String?` | `null` | Profile website URL |
| `lud16` | `String?` | `null` | Lightning address for zaps (LUD-16) |
| `banner` | `String?` | `null` | Profile banner image URL |
| `pronouns` | `String?` | `null` | Pronouns from kind-0 |

### Author Resolution

Authors are resolved lazily. When a note arrives, `NotesRepository` creates an `Author` with whatever is in `ProfileMetadataCache`. If the profile hasn't been fetched yet, the author will have a truncated pubkey as `username` and no avatar. `ProfileMetadataCache` emits on `profileUpdated: SharedFlow<String>` when profiles arrive, and NoteCard re-renders via a `profileRevision` counter.

---

## ThreadReply & ThreadedReply

**File:** `ThreadReply.kt` · **Annotation:** `@Serializable`

### ThreadReply

A single reply in a thread. Used for both kind-1111 (NIP-22 thread replies) and kind-1 (NIP-10 reply chain) events when displayed in thread view.

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `id` | `String` | — | Event ID |
| `author` | `Author` | — | Resolved author |
| `content` | `String` | — | Reply text |
| `timestamp` | `Long` | — | `created_at` in ms |
| `likes` | `Int` | `0` | Reaction count |
| `shares` | `Int` | `0` | Repost count |
| `replies` | `Int` | `0` | Child reply count |
| `isLiked` | `Boolean` | `false` | Current user has reacted |
| `hashtags` | `List<String>` | `[]` | Hashtag tags |
| `mediaUrls` | `List<String>` | `[]` | Media URLs in content |
| `mediaMeta` | `Map<String, IMetaData>` | `{}` | NIP-92 imeta metadata |
| `rootNoteId` | `String?` | `null` | Root thread event ID |
| `replyToId` | `String?` | `null` | Direct parent event ID |
| `threadLevel` | `Int` | `0` | Nesting depth (0 = direct reply to root) |
| `relayUrls` | `List<String>` | `[]` | Relay URLs for relay orbs |
| `kind` | `Int` | `1111` | Event kind (1 or 1111) |
| `mentionedPubkeys` | `List<String>` | `[]` | p-tag pubkeys |

**Computed properties:**
- `shortContent: String` — First 100 characters with ellipsis
- `isDirectReply: Boolean` — True if replying directly to root note

**Static parsing:** `ThreadReply.parseThreadTags(tags)` handles both NIP-22 (uppercase `E` = root, lowercase `e` = parent) and NIP-10 marker style (`"root"`, `"reply"` markers). Returns `Triple<rootId, replyToId, level>`.

### ThreadedReply

Wraps a `ThreadReply` into a tree structure for hierarchical display.

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `reply` | `ThreadReply` | — | The reply data |
| `children` | `List<ThreadedReply>` | `[]` | Nested child replies |
| `level` | `Int` | `0` | Display nesting level |
| `isOrphan` | `Boolean` | `false` | True when parent wasn't fetched (promoted to root) |

**Computed properties:**
- `totalReplies: Int` — Recursive count of all descendants
- `flatten(): List<Pair<ThreadReply, Int>>` — Flattens tree into display list with indent levels

### Conversion Functions

The file provides extension functions to convert between `Note` and `ThreadReply`:
- `Note.toThreadReply()` — Convert for thread display
- `Note.toThreadReplyForThread()` — Convert preserving NIP-10 root/reply IDs
- `ThreadReply.toNote()` — Convert back for components that expect `Note`

---

## NoteWithReplies

**File:** `ThreadReply.kt` (same file) · **Annotation:** `@Serializable`

Combines a root note with all its replies.

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `note` | `Note` | — | The root note |
| `replies` | `List<ThreadReply>` | `[]` | Flat list of all replies |
| `totalReplyCount` | `Int` | `0` | Total reply count (may differ from `replies.size` if some weren't fetched) |
| `isLoadingReplies` | `Boolean` | `false` | Whether replies are still being fetched |

**Computed properties:**
- `threadedReplies: List<ThreadedReply>` — Replies organized into tree structure
- `directReplies: List<ThreadReply>` — Only level-0 replies

---

## DirectMessage & Conversation

**File:** `DirectMessage.kt` · **Annotation:** `@Immutable`

### DirectMessage

A single NIP-17 gift-wrapped DM. The layering is: kind-1059 gift wrap → kind-13 seal → kind-14 rumor (the actual message).

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `id` | `String` | — | Gift wrap event ID (kind-1059) |
| `senderPubkey` | `String` | — | Actual sender (from seal pubkey) |
| `recipientPubkey` | `String` | — | Recipient (from gift wrap p-tag) |
| `content` | `String` | — | Decrypted message content |
| `createdAt` | `Long` | — | Timestamp from the rumor (kind-14) |
| `replyToId` | `String?` | `null` | Reply-to event ID (e-tag in rumor) |
| `subject` | `String?` | `null` | Subject tag in rumor |
| `isOutgoing` | `Boolean` | `false` | Whether sent by current user |
| `relayUrls` | `List<String>` | `[]` | Relay URLs where gift wrap was seen |

### Conversation

A DM conversation with a specific user.

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `peerPubkey` | `String` | — | Other party's hex pubkey |
| `peerDisplayName` | `String` | `""` | Resolved from profile cache |
| `peerAvatarUrl` | `String?` | `null` | Resolved from profile cache |
| `lastMessage` | `DirectMessage?` | `null` | Most recent message |
| `messageCount` | `Int` | `0` | Total messages in conversation |
| `unreadCount` | `Int` | `0` | Unread message count |

---

## NotificationData

**File:** `NotificationData.kt`

A single notification item. Supports replies, likes, zaps, reposts, mentions, badge awards, poll votes, and more. Notifications are consolidated — multiple likes on the same note become one `NotificationData` with multiple `actorPubkeys`.

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `id` | `String` | — | Notification ID |
| `type` | `NotificationType` | — | Notification category |
| `text` | `String` | — | Display text |
| `note` | `Note?` | `null` | The notification event as a note (e.g. reply content) |
| `author` | `Author?` | `null` | Primary actor |
| `targetNote` | `Note?` | `null` | Note that was liked/zapped (for display below) |
| `targetNoteId` | `String?` | `null` | Target note ID when note hasn't been fetched yet |
| `rootNoteId` | `String?` | `null` | Root note ID for threading |
| `replyNoteId` | `String?` | `null` | Reply event ID |
| `replyKind` | `Int?` | `null` | 1 = kind-1 reply, 1111 = kind-1111 comment |
| `reposterPubkeys` | `List<String>` | `[]` | Consolidated repost actors |
| `actorPubkeys` | `List<String>` | `[]` | Consolidated notification actors |
| `sortTimestamp` | `Long` | `0` | Sort key (latest activity time) |
| `zapAmountSats` | `Long` | `0` | Total zap amount in sats |
| `reactionEmoji` | `String?` | `null` | NIP-25 reaction emoji |
| `reactionEmojis` | `List<String>` | `[]` | All unique emojis in consolidated notification |
| `customEmojiUrl` | `String?` | `null` | NIP-30 custom emoji URL |
| `customEmojiUrls` | `Map<String, String>` | `{}` | All custom emoji URLs keyed by shortcode |
| `mediaUrls` | `List<String>` | `[]` | Media in notification note |
| `quotedEventIds` | `List<String>` | `[]` | Quoted event references |
| `badgeName` | `String?` | `null` | NIP-58 badge name |
| `badgeImageUrl` | `String?` | `null` | NIP-58 badge image |
| `pollId` | `String?` | `null` | NIP-88 poll event ID |
| `pollQuestion` | `String?` | `null` | Poll question text |
| `pollOptionCodes` | `List<String>` | `[]` | Voted option codes |
| `pollOptionLabels` | `List<String>` | `[]` | Voted option labels |
| `pollAllOptions` | `List<String>` | `[]` | All poll options for context |
| `pollIsMultipleChoice` | `Boolean` | `false` | Multiple choice flag |
| `rawContent` | `String?` | `null` | Content before nostr:npub resolution (for mention detection) |

### NotificationType

```
LIKE, REPLY, COMMENT, MENTION, REPOST, ZAP, DM,
HIGHLIGHT, REPORT, QUOTE, BADGE_AWARD, POLL_VOTE
```

---

## LiveActivity & LiveChatMessage

### LiveActivity

**File:** `LiveActivity.kt` · **Annotation:** `@Immutable`

NIP-53 live activity (kind 30311). Represents a live stream or audio space.

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `id` | `String` | — | Event ID |
| `hostPubkey` | `String` | — | Host's pubkey |
| `dTag` | `String` | — | Addressable event d-tag |
| `title` | `String?` | — | Stream title |
| `summary` | `String?` | — | Description |
| `imageUrl` | `String?` | — | Preview image |
| `streamingUrl` | `String?` | — | HLS / streaming URL (`.m3u8`) |
| `recordingUrl` | `String?` | — | Post-stream recording URL |
| `status` | `LiveActivityStatus` | — | `PLANNED`, `LIVE`, or `ENDED` |
| `startsAt` | `Long?` | — | Start timestamp (seconds) |
| `endsAt` | `Long?` | — | End timestamp (seconds) |
| `currentParticipants` | `Int?` | — | Current viewer count |
| `totalParticipants` | `Int?` | — | Lifetime participant count |
| `participants` | `List<LiveActivityParticipant>` | `[]` | Participants with roles |
| `hashtags` | `List<String>` | `[]` | Topic hashtags |
| `relayUrls` | `List<String>` | `[]` | Relay URLs from tags |
| `sourceRelayUrl` | `String?` | `null` | Relay this event was received from |
| `createdAt` | `Long` | — | Event created_at (seconds) |
| `hostAuthor` | `Author?` | `null` | Resolved host profile |

**LiveActivityParticipant:** `pubkey`, `role?`, `relayHint?`

**LiveActivityStatus:** `PLANNED`, `LIVE`, `ENDED` — parsed from status tag value.

### LiveChatMessage

**File:** `LiveChatMessage.kt` · **Annotation:** `@Immutable`

Kind-1311 chat message in a live activity stream.

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `id` | `String` | — | Event ID |
| `pubkey` | `String` | — | Author pubkey |
| `content` | `String` | — | Message text |
| `createdAt` | `Long` | — | Timestamp (seconds) |
| `author` | `Author?` | `null` | Resolved author |

---

## Relay Types

**File:** `Relay.kt` · **Annotations:** `@Immutable @Serializable` (most types)

### RelayInformation

NIP-11 relay information document. Fetched via HTTP from relay URLs.

| Field | Type | Description |
|-------|------|-------------|
| `name` | `String?` | Relay name |
| `description` | `String?` | Relay description |
| `pubkey` | `String?` | Operator pubkey |
| `contact` | `String?` | Operator contact |
| `supported_nips` | `List<Int>?` | Supported NIP numbers |
| `software` | `String?` | Server software name |
| `version` | `String?` | Server software version |
| `limitation` | `RelayLimitation?` | Rate limits and restrictions |
| `relay_countries` | `List<String>?` | Country codes |
| `language_tags` | `List<String>?` | Language tags |
| `tags` | `List<String>?` | General tags |
| `posting_policy` | `String?` | Posting policy URL |
| `payments_url` | `String?` | Payments URL |
| `fees` | `RelayFees?` | Fee structure |
| `icon` | `String?` | Relay icon URL |
| `image` | `String?` | Relay image URL |

### RelayLimitation

Nested in `RelayInformation`. Contains `max_message_length`, `max_subscriptions`, `max_filters`, `max_limit`, `max_subid_length`, `max_event_tags`, `max_content_length`, `min_pow_difficulty`, `auth_required`, `payment_required`, `restricted_writes`, `created_at_lower_limit`, `created_at_upper_limit`.

### UserRelay

A relay in the user's configuration with NIP-11 info attached.

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `url` | `String` | — | WebSocket URL (`wss://...`) |
| `read` | `Boolean` | `true` | Read from this relay |
| `write` | `Boolean` | `true` | Write to this relay |
| `info` | `RelayInformation?` | `null` | Cached NIP-11 document |
| `isOnline` | `Boolean` | `false` | Current connection status |
| `lastChecked` | `Long` | `0` | Last connection check timestamp |
| `addedAt` | `Long` | now | When the relay was added |

**Computed properties:**
- `displayName` — `info.name` or URL without protocol prefix
- `profileImage` — `info.icon` or `info.image`
- `description` — `info.description`
- `supportedNips` — `info.supported_nips` or empty
- `software` — Formatted software + version string

### RelayHealth

Enum: `HEALTHY`, `WARNING`, `CRITICAL`, `UNKNOWN`

### RelayConnectionStatus

Enum: `CONNECTED`, `CONNECTING`, `DISCONNECTED`, `ERROR`

---

## RelayCategory & RelayProfile

**File:** `RelayCategory.kt` · **Annotations:** `@Immutable @Serializable`

### RelayCategory

A user-defined group of relays.

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `id` | `String` | UUID | Unique identifier |
| `name` | `String` | — | Category display name |
| `relays` | `List<UserRelay>` | `[]` | Relays in this category |
| `isDefault` | `Boolean` | `false` | Whether this is the default "Home Relays" category |
| `isSubscribed` | `Boolean` | `true` | Whether to subscribe to this category's relays |
| `createdAt` | `Long` | now | Creation timestamp |

### RelayProfile

A complete network configuration. Users can create multiple profiles and switch between them.

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `id` | `String` | UUID | Unique identifier |
| `name` | `String` | — | Profile name (e.g. "Default", "Privacy", "Media") |
| `categories` | `List<RelayCategory>` | `[]` | Categories in this profile |
| `isActive` | `Boolean` | `false` | Currently active profile |
| `createdAt` | `Long` | now | Creation timestamp |

**Defaults:** `DefaultRelayCategories.getDefaultCategory()` creates a "Home Relays" category. `DefaultRelayProfiles.getDefaultProfile()` creates a "Default" profile containing it.

---

## RelayDiscovery Types (NIP-66)

**File:** `RelayDiscovery.kt` · **Annotation:** `@Immutable`

### RelayDiscoveryEvent

Raw NIP-66 relay discovery event (kind 30166) from a relay monitor.

| Field | Type | Description |
|-------|------|-------------|
| `relayUrl` | `String` | Relay being described |
| `monitorPubkey` | `String` | Monitor that published this event |
| `createdAt` | `Long` | Event timestamp |
| `relayTypes` | `List<RelayType>` | NIP-66 `T` tag types |
| `supportedNips` | `List<Int>` | NIP support from monitor |
| `requirements` | `List<String>` | Access requirements |
| `network` | `String?` | Network type |
| `rttOpen` / `rttRead` / `rttWrite` | `Int?` | Round-trip times (ms) |
| `topics` | `List<String>` | Topics/hashtags |
| `geohash` | `String?` | Location geohash |
| `nip11Content` | `String?` | Raw NIP-11 JSON |
| `countryCode` / `isp` / `asNumber` / `asName` | `String?` | Host metadata from l-tags |

### RelayType

Enum with NIP-66 `T` tag taxonomy:

```
PUBLIC_OUTBOX, PUBLIC_INBOX, PRIVATE_INBOX, PRIVATE_STORAGE,
SEARCH, DIRECTORY, COMMUNITY, ALGO, ARCHIVAL, LOCAL_CACHE,
BLOB, BROADCAST, PROXY, TRUSTED, PUSH
```

Each has a `tag` (e.g. `"PublicOutbox"`) and `displayName` (e.g. `"Public Outbox"`).

### DiscoveredRelay

Aggregated relay info combining data from multiple monitors. This is the cached, UI-ready form.

| Field | Type | Description |
|-------|------|-------------|
| `url` | `String` | Relay URL |
| `types` | `Set<RelayType>` | All observed types |
| `supportedNips` | `Set<Int>` | Union of NIP support |
| `requirements` | `Set<String>` | Access requirements |
| `network` | `String?` | Network type |
| `avgRttOpen` / `avgRttRead` / `avgRttWrite` | `Int?` | Average RTTs |
| `topics` | `Set<String>` | Topics |
| `monitorCount` | `Int` | How many monitors reported this relay |
| `lastSeen` | `Long` | Most recent report timestamp |
| `nip11Json` | `String?` | Raw NIP-11 document |
| `software` / `version` / `name` / `description` / `icon` / `banner` | `String?` | Parsed NIP-11 fields |
| `paymentRequired` / `authRequired` / `restrictedWrites` | `Boolean` | Access flags |
| `operatorPubkey` | `String?` | Relay operator |
| `countryCode` / `isp` | `String?` | Host metadata |
| `seenByMonitors` | `Set<String>` | Monitor pubkeys |

**Computed properties:**
- `isSearch`, `isOutbox`, `isInbox`, `isPrivateInbox`, `isDirectory` — type checks
- `bestRtt: Int?` — Prefers rttRead → rttOpen → rttWrite
- `softwareShort: String?` — Cleaned, truncated software name for display

### RelayMonitorAnnouncement

Kind-10166 monitor self-description: `pubkey`, `frequencySeconds`, `checks`, `timeouts`, `geohash`.

---

## Draft

**File:** `Draft.kt` · **Annotation:** `@Serializable`

A saved draft for any compose flow.

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `id` | `String` | UUID | Draft identifier |
| `type` | `DraftType` | — | What kind of compose flow |
| `content` | `String` | — | Draft text |
| `title` | `String?` | `null` | Topic title (for kind-11) |
| `rootId` | `String?` | `null` | Root note being replied to |
| `rootPubkey` | `String?` | `null` | Root note author |
| `parentId` | `String?` | `null` | Direct parent (for nested replies) |
| `parentPubkey` | `String?` | `null` | Parent author |
| `hashtags` | `List<String>` | `[]` | Hashtags |
| `createdAt` | `Long` | now | Creation time |
| `updatedAt` | `Long` | now | Last modification time |
| `scheduledAt` | `Long?` | `null` | Future publish time (for scheduled notes) |
| `isScheduled` | `Boolean` | `false` | Whether this is a scheduled draft |
| `signedEventJson` | `String?` | `null` | Pre-signed event JSON (for scheduled publish) |
| `relayUrls` | `List<String>` | `[]` | Target relay URLs |
| `publishError` | `String?` | `null` | Last publish error message |
| `isCompleted` | `Boolean` | `false` | Whether this draft has been published |

### DraftType

```
NOTE, TOPIC, REPLY_KIND1, REPLY_KIND1111, TOPIC_REPLY
```

---

## UrlPreviewInfo

**File:** `UrlPreview.kt` · **Annotations:** `@Immutable @Serializable`

Open Graph metadata for a URL, used for rich link preview cards.

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `url` | `String` | — | Original URL |
| `title` | `String` | `""` | `og:title` |
| `description` | `String` | `""` | `og:description` |
| `imageUrl` | `String` | `""` | `og:image` |
| `siteName` | `String` | `""` | `og:site_name` |
| `mimeType` | `String` | `"text/html"` | Content type |

**Computed properties:**
- `verifiedUrl: URL?` — Parsed URL object (null if invalid)
- `imageUrlFullPath: String` — Resolves relative image URLs against base
- `rootDomain: String` — Extracted host for display
- `hasCompleteInfo()` / `hasBasicInfo()` — Completeness checks

### UrlPreviewState

Sealed class: `Loading`, `Loaded(previewInfo)`, `Error(message)`

---

## IMetaData (NIP-92)

**File:** `Note.kt` (nested in same file) · **Annotations:** `@Immutable @Serializable`

Parsed NIP-92 `imeta` tag metadata for a single media URL. Extracted at event-processing time so the UI can size image containers and show blurhash placeholders before media loads.

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `url` | `String` | — | Media URL |
| `width` | `Int?` | `null` | Image/video width (from `dim` field) |
| `height` | `Int?` | `null` | Image/video height |
| `blurhash` | `String?` | `null` | Blurhash placeholder string |
| `mimeType` | `String?` | `null` | MIME type (e.g. `image/jpeg`, `video/mp4`) |
| `alt` | `String?` | `null` | Alt text / description |

**Methods:**
- `aspectRatio(): Float?` — Width/height ratio, or null if dimensions unknown

**Parsing (static):**
- `IMetaData.parseIMetaTag(tag: Array<String>)` — Parse single imeta tag
- `IMetaData.parseAll(tags: Array<Array<String>>)` — Parse all imeta tags into URL-keyed map

Tag format: `["imeta", "url https://...", "dim 1920x1080", "blurhash ...", "m image/jpeg", "alt ..."]`

---

## Poll Types (NIP-88)

**File:** `Note.kt` (nested)

### PollData

Kind-1068 poll metadata.

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `options` | `List<PollOption>` | — | Poll choices |
| `pollType` | `String` | `"singlechoice"` | `"singlechoice"` or `"multiplechoice"` |
| `endsAt` | `Long?` | `null` | Close time (epoch seconds) |
| `relays` | `List<String>` | `[]` | Relays for response collection |

**PollOption:** `code: String`, `label: String`

**Computed:** `isMultipleChoice`, `hasEnded`

**Parsing:** `PollData.parseFromTags(tags)` extracts `option`, `polltype`, `endsAt`, `relay` tags.

---

## Zap Poll Types

**File:** `Note.kt` (nested)

### ZapPollData

Kind-6969 Amethyst-style zap poll metadata.

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `options` | `List<ZapPollOption>` | — | Poll choices |
| `valueMinimum` | `Long?` | `null` | Minimum sats per vote |
| `valueMaximum` | `Long?` | `null` | Maximum sats per vote |
| `closedAt` | `Long?` | `null` | Close time (epoch seconds) |
| `consensusThreshold` | `Int?` | `null` | Consensus percentage (0–100) |

**ZapPollOption:** `index: Int`, `description: String`

**Parsing:** `ZapPollData.parseFromTags(tags)` extracts `poll_option`, `value_minimum`, `value_maximum`, `closed_at`, `consensus_threshold` tags.

---

## MediaServer

**File:** `MediaServer.kt` · **Annotation:** `@Serializable`

Configuration for a media upload server.

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `name` | `String` | — | Display name |
| `baseUrl` | `String` | — | Server base URL |
| `type` | `MediaServerType` | `BLOSSOM` | `BLOSSOM` or `NIP96` |

### MediaServerType

```
BLOSSOM,  // BUD-01/02/04 HTTP blob storage with kind-24242 auth
NIP96     // NIP-96 HTTP file storage with kind-27235 auth
```

### DefaultMediaServers

Predefined server lists:
- **BLOSSOM_SERVERS** (9): Nostr.Build (blossom.band), 24242.io, Azzamo, YakiHonne, Primal, Sovbit, Nostr.Download, Satellite (Paid), NostrMedia (Paid)
- **NIP96_SERVERS** (4): Nostr.Build, NostrCheck.me, Sovbit, Void.cat

Default Blossom server: Nostr.Build (`https://blossom.band/`)

---

## AccountInfo

**File:** `AccountInfo.kt` · **Annotation:** `@Serializable`

Minimal account record for multi-account management. Stored in SharedPreferences as JSON.

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `npub` | `String` | — | Public key in npub format |
| `hasPrivateKey` | `Boolean` | `false` | Whether nsec is stored |
| `isExternalSigner` | `Boolean` | `false` | Whether using Amber |
| `isTransient` | `Boolean` | `false` | Temporary session flag |
| `displayName` | `String?` | `null` | Cached display name |
| `picture` | `String?` | `null` | Cached avatar URL |
| `lastUsed` | `Long` | now | Last activation timestamp |

**Methods:**
- `toHexKey(): String?` — Convert npub to hex via Cybin's `Nip19Parser`
- `toShortNpub(): String` — Truncated npub for display (`npub1abc...xyz`)
- `getDisplayNameOrNpub(): String` — Display name with npub fallback

---

## SyncedSettings (NIP-78)

**File:** `SyncedSettings.kt`

User preferences synced across devices via kind-30078 (NIP-78). Published as NIP-44 encrypted JSON with d-tag `"MyceliumSettings"`.

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `zapAmounts` | `List<Long>` | `[1]` | Zap amount presets |
| `showSensitive` | `Boolean` | `false` | Show sensitive content |
| `muteStrangers` | `Boolean` | `false` | Mute non-followed users |
| `theme` | `String` | `"DARK"` | Theme mode name |
| `accent` | `String` | `"VIOLET"` | Accent color name |
| `compactMedia` | `Boolean` | `false` | Compact media layout |
| `autoplayVideos` | `Boolean` | `true` | Autoplay videos in feed |
| `autoplaySound` | `Boolean` | `false` | Autoplay video sound |
| `autoPipLive` | `Boolean` | `true` | Auto picture-in-picture for live streams |
| `notifyReactions` / `notifyZaps` / `notifyReposts` / `notifyMentions` / `notifyReplies` / `notifyDMs` | `Boolean` | `true` | Per-type notification toggles |

**Methods:**
- `toJson() / fromJson(json)` — JSON serialization via `org.json`
- `fromLocalPreferences()` — Snapshot current local preferences into SyncedSettings
- `applyToLocalPreferences(settings)` — Apply remote settings to all local preference singletons

Only includes settings that should follow the user across devices. Device-specific settings (connection mode, relay lists, etc.) stay local.

---

## UserProfile & AuthState

**File:** `UserProfile.kt` · **Annotation:** `@Serializable`

### UserProfile

Represents a user profile for authentication state tracking. Separate from `Author` — this is used by the auth/login system, while `Author` is used by content display.

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `pubkey` | `String` | — | Hex pubkey |
| `displayName` | `String?` | `null` | Display name |
| `name` | `String?` | `null` | Username |
| `about` | `String?` | `null` | Bio |
| `picture` | `String?` | `null` | Avatar URL |
| `banner` | `String?` | `null` | Banner URL |
| `website` | `String?` | `null` | Website |
| `lud16` | `String?` | `null` | Lightning address |
| `nip05` | `String?` | `null` | NIP-05 identifier |
| `createdAt` | `Long` | now | Profile creation/fetch time |

**Computed:** `displayNameOrName`, `isGuest`

### AuthState

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `isAuthenticated` | `Boolean` | `false` | Login status |
| `isGuest` | `Boolean` | `true` | Guest mode |
| `userProfile` | `UserProfile?` | `null` | Current profile |
| `isLoading` | `Boolean` | `false` | Auth in progress |
| `error` | `String?` | `null` | Auth error message |

A `GUEST_PROFILE` constant is provided with `pubkey = "guest"`.

---

## Miscellaneous Types

### QuotedNoteMeta

**File:** `Note.kt` · **Annotations:** `@Immutable @Serializable`

Metadata for a quoted note (from `nostr:nevent1`/`nostr:note1` references).

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `eventId` | `String` | — | Quoted event ID |
| `authorId` | `String` | — | Quoted event author pubkey |
| `contentSnippet` | `String` | — | Truncated content for preview |
| `fullContent` | `String` | `= contentSnippet` | Full content for "read more" |
| `createdAt` | `Long` | `0` | Timestamp |
| `relayUrl` | `String?` | `null` | Relay where event was seen |
| `rootNoteId` | `String?` | `null` | Thread root (if quoted event is a reply) |
| `kind` | `Int` | `1` | Event kind |
| `tags` | `List<List<String>>` | `[]` | Raw tags (for poll parsing) |

### Comment

**File:** `Note.kt` · **Annotations:** `@Immutable @Serializable`

Simple comment model: `id`, `author`, `content`, `timestamp`, `likes`, `isLiked`.

### WebSocketMessage

**File:** `Note.kt` · **Annotations:** `@Immutable @Serializable`

Raw WebSocket message: `type`, `data`.

### NoteAction

Enum: `LIKE`, `UNLIKE`, `SHARE`, `COMMENT`, `DELETE`

### NoteUpdate

**File:** `Note.kt` · **Annotations:** `@Immutable @Serializable`

Event update record: `noteId`, `action`, `userId`, `timestamp`.

### FlexibleIntListSerializer

**File:** `FlexibleIntListSerializer.kt`

Custom kotlinx.serialization deserializer that handles NIP-11 `supported_nips` arrays containing mixed ints and non-int values (some relays return malformed NIP-11 with strings in the array). Silently skips non-integer entries.

### SampleData

**File:** `SampleData.kt`

Sample authors and notes for `@Preview` composables only. Not used at runtime.

---

## Cybin Library Types

These types live in `cybin/cybin/src/main/java/com/example/cybin/` and are the protocol-level primitives that the app-level data models are built on.

### Event (`core/Event.kt`)

Signed Nostr event (NIP-01). Contains `id`, `pubkey`, `created_at`, `kind`, `tags`, `content`, `sig`. Includes JSON serialization and `toJson()` method.

### EventTemplate (`core/EventTemplate.kt`)

Unsigned event ready for signing. Used by `EventPublisher` — build a template, inject tags, then sign with `NostrSigner`.

### Filter (`core/Filter.kt`)

NIP-01 REQ filter: `ids`, `authors`, `kinds`, `#e`, `#p`, `since`, `until`, `limit`. Used to construct relay subscription requests.

### TagArrayBuilder (`core/TagArrayBuilder.kt`)

DSL builder for event tag arrays:

```kotlin
val template = eventTemplate(kind = 1) {
    tag("p", recipientPubkey)
    tag("e", referencedEventId, relayHint, "reply")
    content = "Hello Nostr!"
}
```

### Types (`core/Types.kt`)

Type aliases: `HexKey = String`, `Kind = Int`, `TagArray = Array<Array<String>>`.

### KeyPair (`crypto/KeyPair.kt`)

secp256k1 key pair via secp256k1-kmp. Wraps private key bytes, derives public key. Used by `NostrSignerInternal` for local signing.

### NostrSigner (`signer/NostrSigner.kt`)

Abstract signer interface with two implementations:
- **`NostrSignerInternal`** — Local Schnorr signing with secp256k1 `KeyPair`
- **`NostrSignerExternal`** — Amber (NIP-55) via Android ContentProvider IPC

### SubscriptionPriority (`relay/CybinRelayClient.kt`)

```
CRITICAL(4) > HIGH(3) > NORMAL(2) > LOW(1) > BACKGROUND(0)
```

Higher priority subscriptions can preempt lower-priority EOSE'd subscriptions when relay slots are full.