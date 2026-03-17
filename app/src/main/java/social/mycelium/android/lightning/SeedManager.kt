package social.mycelium.android.lightning

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import fr.acinq.bitcoin.MnemonicCode
import fr.acinq.lightning.Lightning

/**
 * Manages the wallet seed (12-word BIP39 mnemonic) using EncryptedSharedPreferences
 * backed by Android Keystore. The seed never leaves encrypted storage.
 */
object SeedManager {
    private const val TAG = "SeedManager"
    private const val PREFS_FILE = "phoenix_wallet_seed"
    private const val KEY_MNEMONIC = "mnemonic_words"

    private fun getPrefs(context: Context) = EncryptedSharedPreferences.create(
        PREFS_FILE,
        MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
        context.applicationContext,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    /** Returns true if a wallet seed exists in encrypted storage. */
    fun hasSeed(context: Context): Boolean {
        return getPrefs(context).getString(KEY_MNEMONIC, null) != null
    }

    /** Generate a new 12-word BIP39 mnemonic and store it encrypted. Returns the words. */
    fun generateAndStore(context: Context): List<String> {
        val entropy = Lightning.randomBytes(16) // 128 bits = 12 words
        val mnemonics = MnemonicCode.toMnemonics(entropy)
        storeMnemonic(context, mnemonics)
        Log.d(TAG, "Generated and stored new wallet seed (${mnemonics.size} words)")
        return mnemonics
    }

    /** Store an existing mnemonic (e.g. from restore flow). Validates first. */
    fun storeMnemonic(context: Context, words: List<String>) {
        MnemonicCode.validate(words)
        getPrefs(context).edit()
            .putString(KEY_MNEMONIC, words.joinToString(" "))
            .apply()
    }

    /** Load the stored mnemonic. Returns null if no seed exists. */
    fun loadMnemonic(context: Context): List<String>? {
        val stored = getPrefs(context).getString(KEY_MNEMONIC, null) ?: return null
        return stored.split(" ")
    }

    /** Derive a BIP39 seed from the stored mnemonic. Returns null if no seed exists. */
    fun deriveSeed(context: Context): ByteArray? {
        val words = loadMnemonic(context) ?: return null
        return MnemonicCode.toSeed(words, "").copyOf(32)
    }

    /** Delete the stored seed. This is irreversible. */
    fun deleteSeed(context: Context) {
        getPrefs(context).edit().remove(KEY_MNEMONIC).apply()
        Log.w(TAG, "Wallet seed deleted from encrypted storage")
    }
}
