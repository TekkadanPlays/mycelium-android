package social.mycelium.android.services

import android.content.Context
import android.util.Log
import social.mycelium.android.relay.RelayConnectionStateMachine
import social.mycelium.android.utils.ClientTagManager
import com.example.cybin.core.Event
import com.example.cybin.core.EventTemplate
import com.example.cybin.core.TagArrayBuilder
import com.example.cybin.signer.NostrSigner
import com.example.cybin.relay.RelayUrlNormalizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import social.mycelium.android.relay.RelayHealthTracker

/**
 * Result of an event publish attempt.
 */
sealed class PublishResult {
    data class Success(val eventId: String, val event: Event) : PublishResult()
    data class Error(val message: String) : PublishResult()
}

/**
 * Centralized event publisher for all Nostr event kinds.
 *
 * Extracts the common pattern shared by every publish method:
 *   1. Build an EventTemplate (caller provides kind + content + tags)
 *   2. Optionally inject NIP-89 client tag
 *   3. Sign with the provided NostrSigner
 *   4. Send to the provided relay URLs
 */
object EventPublisher {

    private const val TAG = "EventPublisher"
    private const val PUBLISH_OK_TIMEOUT_MS = 10_000L

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Shared event bus: emits every successfully published event so active ViewModels can
     *  inject them into their pending lists for live awareness (e.g. thread replies, topics). */
    private val _publishedEvents = MutableSharedFlow<Event>(extraBufferCapacity = 16)
    val publishedEvents: SharedFlow<Event> = _publishedEvents.asSharedFlow()

    /**
     * Build, sign, and send a Nostr event.
     *
     * @param context Application context (for ClientTagManager preference).
     * @param signer The NostrSigner to sign the event with.
     * @param relayUrls Raw relay URLs to publish to. Will be normalized; empty set after normalization is an error.
     * @param kind The Nostr event kind (1, 7, 11, 1011, 1111, 1311, 30073, etc.).
     * @param content The event content string.
     * @param tags Lambda to add custom tags to the event template builder.
     * @return [PublishResult.Success] with the event ID, or [PublishResult.Error] with a message.
     */
    suspend fun publish(
        context: Context,
        signer: NostrSigner,
        relayUrls: Set<String>,
        kind: Int,
        content: String,
        tags: (TagArrayBuilder.() -> Unit)? = null
    ): PublishResult {
        val normalized = normalizeRelays(relayUrls)
        if (normalized.isEmpty()) {
            return PublishResult.Error("No valid relays selected")
        }

        return try {
            // Build event template
            val template = Event.build(kind, content) {
                tags?.invoke(this)
                if (ClientTagManager.isEnabled(context)) add(ClientTagManager.CLIENT_TAG)
            }

            // Sign
            val signed = signer.sign(template)
            if (signed.sig.isBlank()) {
                return PublishResult.Error("Signing failed (empty signature)")
            }

            // Send
            RelayConnectionStateMachine.getInstance().send(signed, normalized)
            RelayConnectionStateMachine.getInstance().nip42AuthHandler
                .trackPublishedEvent(signed, normalized)
            Log.d(TAG, "Kind-$kind published: ${signed.id.take(8)} → ${normalized.size} relays")
            _publishedEvents.tryEmit(signed)

            // Track publish results per relay — store signed event for retry capability
            RelayHealthTracker.storePublishedEvent(signed.id, signed)
            RelayHealthTracker.registerPendingPublish(signed.id, kind, normalized)
            scope.launch {
                delay(PUBLISH_OK_TIMEOUT_MS)
                RelayHealthTracker.finalizePendingPublish(signed.id)
            }

            PublishResult.Success(signed.id, signed)
        } catch (e: Exception) {
            Log.e(TAG, "Kind-$kind publish failed: ${e.message}", e)
            PublishResult.Error(e.message?.take(80) ?: "Unknown error")
        }
    }

    /**
     * Overload accepting a pre-built EventTemplate (for complex events like reactions
     * that use library builders). Still injects client tag and handles sign+send.
     */
    suspend fun publish(
        context: Context,
        signer: NostrSigner,
        relayUrls: Set<String>,
        template: EventTemplate
    ): PublishResult {
        val normalized = normalizeRelays(relayUrls)
        if (normalized.isEmpty()) {
            return PublishResult.Error("No valid relays selected")
        }

        return try {
            // Inject client tag if enabled
            val finalTemplate = if (ClientTagManager.isEnabled(context)) {
                EventTemplate(template.createdAt, template.kind, template.tags + arrayOf(ClientTagManager.CLIENT_TAG), template.content)
            } else template

            val signed: Event = signer.sign(finalTemplate)
            if (signed.sig.isBlank()) {
                return PublishResult.Error("Signing failed (empty signature)")
            }

            RelayConnectionStateMachine.getInstance().send(signed, normalized)
            RelayConnectionStateMachine.getInstance().nip42AuthHandler
                .trackPublishedEvent(signed, normalized)
            Log.d(TAG, "Kind-${template.kind} published: ${signed.id.take(8)} → ${normalized.size} relays")
            _publishedEvents.tryEmit(signed)

            // Track publish results per relay — store signed event for retry capability
            RelayHealthTracker.storePublishedEvent(signed.id, signed)
            RelayHealthTracker.registerPendingPublish(signed.id, template.kind, normalized)
            scope.launch {
                delay(PUBLISH_OK_TIMEOUT_MS)
                RelayHealthTracker.finalizePendingPublish(signed.id)
            }

            PublishResult.Success(signed.id, signed)
        } catch (e: Exception) {
            Log.e(TAG, "Kind-${template.kind} publish failed: ${e.message}", e)
            PublishResult.Error(e.message?.take(80) ?: "Unknown error")
        }
    }

    /** Normalize raw relay URL strings to a plain String set. */
    private fun normalizeRelays(urls: Set<String>): Set<String> =
        urls.mapNotNull { RelayUrlNormalizer.normalizeOrNull(it)?.url }.toSet()
}
