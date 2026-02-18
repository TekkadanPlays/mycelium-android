package social.mycelium.android.cache.nip11

import social.mycelium.android.data.RelayInformation

/**
 * Sealed class representing the state of a NIP-11 relay information retrieval.
 * Based on Amethyst's RetrieveResult pattern.
 */
sealed class RetrieveResult(
    val data: RelayInformation,
    val time: Long
) {
    /**
     * Error state - fetch failed but we have fallback data
     */
    class Error(
        data: RelayInformation,
        val errorCode: Nip11Retriever.ErrorCode,
        val message: String? = null
    ) : RetrieveResult(data, System.currentTimeMillis())

    /**
     * Success state - fresh data from relay
     */
    class Success(
        data: RelayInformation
    ) : RetrieveResult(data, System.currentTimeMillis())

    /**
     * Loading state - fetch in progress, showing placeholder data
     */
    class Loading(
        data: RelayInformation
    ) : RetrieveResult(data, System.currentTimeMillis())

    /**
     * Empty state - no data fetched yet, showing default/placeholder
     */
    class Empty(
        data: RelayInformation
    ) : RetrieveResult(data, System.currentTimeMillis())

    /**
     * Check if the cached result is still valid (within 1 hour)
     */
    fun isValid(): Boolean {
        val now = System.currentTimeMillis()
        val oneHourAgo = now - (60 * 60 * 1000L)
        return time > oneHourAgo
    }
}
