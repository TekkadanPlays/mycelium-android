package social.mycelium.android.utils

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object ZapAmountManager {
    private var _zapAmounts = MutableStateFlow(listOf(1L))
    val zapAmounts: StateFlow<List<Long>> = _zapAmounts.asStateFlow()
    
    private var sharedPreferences: SharedPreferences? = null
    
    fun initialize(context: Context) {
        if (sharedPreferences == null) {
            sharedPreferences = context.getSharedPreferences("zap_amounts", Context.MODE_PRIVATE)
            loadAmounts()
        }
    }
    
    private fun loadAmounts() {
        val amountsString = sharedPreferences?.getString("amounts", "1") ?: "1"
        val amounts = amountsString.split(",").mapNotNull { it.toLongOrNull() }
        _zapAmounts.value = amounts
    }
    
    fun addAmount(amount: Long) {
        val currentAmounts = _zapAmounts.value.toMutableList()
        if (!currentAmounts.contains(amount) && amount > 0) {
            currentAmounts.add(amount)
            saveAmounts(currentAmounts)
        }
    }
    
    fun removeAmount(amount: Long) {
        val currentAmounts = _zapAmounts.value.filter { it != amount }
        saveAmounts(currentAmounts)
    }
    
    fun updateAmounts(amounts: List<Long>) {
        saveAmounts(amounts)
    }
    
    private fun saveAmounts(amounts: List<Long>) {
        _zapAmounts.value = amounts
        val amountsString = amounts.joinToString(",")
        sharedPreferences?.edit()?.putString("amounts", amountsString)?.apply()
        social.mycelium.android.repository.SettingsSyncManager.notifySettingChanged()
    }
}
