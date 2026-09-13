package com.hadi.expensetracker

import android.content.Context

/**
 * A single enabled SMS source, whether it's one of the hardcoded [BankPresets] or a bank the
 * user added themselves via the settings screen. [key] is what balances are stored under (see
 * [BalanceStore]) and what the widget uses to tell rows apart.
 */
object BankRegistry {

    data class Source(val key: String, val label: String, val isCustom: Boolean)

    private fun normalizeDigits(s: String): String = s.filter { it.isDigit() }.trimStart('0')

    private fun customKey(id: Long) = "custom_$id"

    /** All sources the user currently has switched on, built-ins first. */
    fun allEnabledSources(context: Context, dbHelper: DbHelper): List<Source> {
        val list = mutableListOf<Source>()
        for (preset in BankPresets.ALL) {
            if (SmsPrefs.isPresetEnabled(context, preset.id)) {
                list.add(Source(preset.id, context.getString(preset.labelResId), isCustom = false))
            }
        }
        for (bank in dbHelper.getCustomBanks()) {
            if (bank.enabled) list.add(Source(customKey(bank.id), bank.label, isCustom = true))
        }
        return list
    }

    /** The enabled source (built-in or custom) whose sender matches [sender], if any. */
    fun findMatchingSource(context: Context, dbHelper: DbHelper, sender: String): Source? {
        BankPresets.findMatchingPreset(sender)?.let { preset ->
            if (SmsPrefs.isPresetEnabled(context, preset.id)) {
                return Source(preset.id, context.getString(preset.labelResId), isCustom = false)
            }
        }
        val senderDigits = normalizeDigits(sender)
        for (bank in dbHelper.getCustomBanks()) {
            if (!bank.enabled) continue
            val bankDigits = normalizeDigits(bank.sender)
            val matches = if (bankDigits.isNotEmpty()) {
                senderDigits.isNotEmpty() && (senderDigits == bankDigits || senderDigits.endsWith(bankDigits))
            } else {
                sender.contains(bank.sender, ignoreCase = true)
            }
            if (matches) return Source(customKey(bank.id), bank.label, isCustom = true)
        }
        return null
    }
}
