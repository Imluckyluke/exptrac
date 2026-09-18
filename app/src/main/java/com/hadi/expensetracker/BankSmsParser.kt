package com.hadi.expensetracker

/**
 * Extracts a best-effort (title, amount) guess from a bank SMS body. When the sender matches
 * a known built-in preset (see [BankPresets]), that bank's specific message format is parsed
 * directly for a more reliable result. Otherwise a generic heuristic is used: it looks for a
 * number next to a currency word ("ریال"/"تومان"), converts Rial to Toman when needed, and
 * looks for common transaction keywords for the title. The result is always shown to the user
 * for confirmation before it becomes a real expense, so an imperfect guess never silently
 * creates wrong data.
 */
object BankSmsParser {

    data class ParsedTransaction(
        val title: String,
        val amount: Double?,
        /** The account balance reported in the same message, if the bank's format includes one. */
        val balance: Double? = null
    )

    private const val PERSIAN_DIGITS = "۰۱۲۳۴۵۶۷۸۹"
    private const val ARABIC_INDIC_DIGITS = "٠١٢٣٤٥٦٧٨٩"

    private fun normalizeDigits(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input) {
            val persianIndex = PERSIAN_DIGITS.indexOf(ch)
            val arabicIndex = ARABIC_INDIC_DIGITS.indexOf(ch)
            when {
                persianIndex >= 0 -> sb.append(persianIndex)
                arabicIndex >= 0 -> sb.append(arabicIndex)
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    // A number (optionally comma-grouped) directly followed by a currency word.
    private val amountWithCurrency = Regex(
        "([0-9][0-9,]{2,})\\s*(ریال|ريال|تومان|rial|toman)",
        RegexOption.IGNORE_CASE
    )

    // Fallback: any comma-grouped number (e.g. 150,000) even without a currency word nearby.
    private val amountGrouped = Regex("([0-9]{1,3}(?:,[0-9]{3})+)")

    private val merchantAfterKeyword =
        Regex("(?:خرید از|خرید در|پرداخت به|انتقال به)\\s+([^\\s,،\\n]+)")

    private val transactionKeywords = listOf("خرید", "برداشت", "انتقال", "واریز", "پرداخت")

    /**
     * Entry point used by SMS import: routes to a bank-specific parser when [sender] matches a
     * known preset, otherwise falls back to the generic heuristic.
     */
    fun parse(sender: String, body: String): ParsedTransaction {
        return when (BankPresets.findMatchingPreset(sender)?.id) {
            BankPresets.BLU -> parseBlu(body)
            BankPresets.MELLI -> parseMelli(body)
            else -> parseGeneric(body)
        }
    }

    fun parseGeneric(body: String): ParsedTransaction {
        val normalized = normalizeDigits(body)

        var amount: Double? = null
        amountWithCurrency.find(normalized)?.let { match ->
            val digitsOnly = match.groupValues[1].replace(",", "")
            val unit = match.groupValues[2].lowercase()
            digitsOnly.toLongOrNull()?.let { value ->
                amount = if (unit.contains("ریال") || unit.contains("ريال") || unit.contains("rial")) {
                    value / 10.0 // Rial -> Toman
                } else {
                    value.toDouble() // already Toman
                }
            }
        }
        if (amount == null) {
            amountGrouped.find(normalized)?.let { match ->
                match.value.replace(",", "").toLongOrNull()?.let { value ->
                    amount = value.toDouble()
                }
            }
        }

        val merchant = merchantAfterKeyword.find(body)?.groupValues?.get(1)
        val keyword = transactionKeywords.firstOrNull { body.contains(it) }
        val title = when {
            merchant != null && keyword != null -> "$keyword $merchant"
            keyword != null -> keyword
            else -> ""
        }

        return ParsedTransaction(title, amount)
    }

    // ---- Blu Bank ----
    // Example body:
    //   هادی عزیز، 100,000,000 ریال به حساب شما نشست.
    //   موجودی: 100,164,105 ریال
    // "نشست" = deposit, "پرید" = withdrawal. The transaction amount is the first ریال figure
    // that isn't on the "موجودی" (balance) line.
    private fun parseBlu(body: String): ParsedTransaction {
        val normalized = normalizeDigits(body)

        var amount: Double? = null
        var balance: Double? = null
        for (line in normalized.lines()) {
            val match = amountWithCurrency.find(line) ?: continue
            val digitsOnly = match.groupValues[1].replace(",", "")
            val unit = match.groupValues[2].lowercase()
            val value = digitsOnly.toLongOrNull() ?: continue
            val toman = if (unit.contains("ریال") || unit.contains("ريال") || unit.contains("rial")) {
                value / 10.0 // Rial -> Toman
            } else {
                value.toDouble()
            }
            if (line.contains("موجودی")) {
                balance = toman
            } else if (amount == null) {
                amount = toman
            }
        }

        val title = when {
            normalized.contains("نشست") || normalized.contains("واریز") -> "واریز (بلوبانک)"
            normalized.contains("پرید") || normalized.contains("برداشت") -> "برداشت (بلوبانک)"
            else -> "تراکنش بلوبانک"
        }

        return ParsedTransaction(title, amount, balance)
    }

    // ---- Bank Melli ----
    // Example body:
    //   بانك ملي ايران
    //   انتقال:100,037,800-
    //   حساب:47002
    //   مانده:44,426,807
    //   0619-14:05
    // The transaction line is the only one where a number is directly followed by a +/- sign
    // (account and balance lines never carry a sign), so that's what identifies it — and the
    // word before the colon doubles as a ready-made title ("انتقال", "خريداينترنتي", ...).
    private val melliAmountWithSign = Regex("([^\\s:]+):([0-9,]+)([+-])")
    private val melliBalance = Regex("مانده:([0-9,]+)")

    private fun parseMelli(body: String): ParsedTransaction {
        val normalized = normalizeDigits(body)
        val match = melliAmountWithSign.find(normalized)

        val amount = match?.groupValues?.get(2)
            ?.replace(",", "")
            ?.toLongOrNull()
            ?.let { it / 10.0 } // Bank Melli reports Rial -> Toman

        val balance = melliBalance.find(normalized)?.groupValues?.get(1)
            ?.replace(",", "")
            ?.toLongOrNull()
            ?.let { it / 10.0 }

        val keyword = match?.groupValues?.get(1)
        // The sign tells a deposit (+, money coming in — not an expense) apart from a
        // withdrawal (-); without it both looked identical, so a salary deposit or refund
        // could get suggested and added as a regular expense with no visual cue either way.
        val sign = match?.groupValues?.get(3)
        val direction = if (sign == "+") "واریز" else "برداشت"
        val title = when {
            !keyword.isNullOrBlank() -> "$direction: $keyword (بانک ملی)"
            match != null -> "$direction (بانک ملی)"
            else -> "تراکنش بانک ملی"
        }

        return ParsedTransaction(title, amount, balance)
    }
}
