package com.hadi.expensetracker

/**
 * Built-in bank SMS sources the user can toggle on/off individually in the SMS settings
 * dialog, as an alternative to typing a custom sender. Each preset is matched against the
 * SMS sender address by its known number/short-code, independent of whatever display name
 * the sender is saved as in the user's contacts.
 */
object BankPresets {

    const val BLU = "blu"
    const val MELLI = "melli"

    data class Preset(
        val id: String,
        val labelResId: Int,
        val descriptionResId: Int,
        /** Digits-only sender number/short-code this bank sends transaction SMS from. */
        val senderDigits: String
    )

    val ALL: List<Preset> = listOf(
        Preset(
            id = BLU,
            labelResId = R.string.bank_preset_blu,
            descriptionResId = R.string.bank_preset_blu_desc,
            senderDigits = "9999987641"
        ),
        Preset(
            id = MELLI,
            labelResId = R.string.bank_preset_melli,
            descriptionResId = R.string.bank_preset_melli_desc,
            senderDigits = "700717"
        )
    )

    /** Compares by digits only, ignoring a leading trunk "0", so +98/0098/00 prefixes all match. */
    private fun normalizeDigits(s: String): String = s.filter { it.isDigit() }.trimStart('0')

    fun matches(preset: Preset, sender: String): Boolean {
        val senderDigits = normalizeDigits(sender)
        if (senderDigits.isEmpty()) return false
        val presetDigits = normalizeDigits(preset.senderDigits)
        return senderDigits == presetDigits || senderDigits.endsWith(presetDigits)
    }

    fun findMatchingPreset(sender: String): Preset? = ALL.firstOrNull { matches(it, sender) }
}
