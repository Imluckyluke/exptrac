package com.hadi.expensetracker

import java.text.NumberFormat
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale

object PersianDate {

    private val monthNames = arrayOf(
        "Farvardin", "Ordibehesht", "Khordad", "Tir", "Mordad", "Shahrivar",
        "Mehr", "Aban", "Azar", "Dey", "Bahman", "Esfand"
    )

    private val persianMonthNames = arrayOf(
        "فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
        "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند"
    )

    fun monthName(m: Int, persian: Boolean = false): String =
        (if (persian) persianMonthNames else monthNames)[m - 1]

    fun displayNumber(value: Int, locale: Locale): String =
        NumberFormat.getIntegerInstance(locale).apply { setGroupingUsed(false) }.format(value)

    fun fromEpochMillis(epochMillis: Long): Triple<Int, Int, Int> {
        val cal = GregorianCalendar(Locale.US).apply { timeInMillis = epochMillis }
        return gregorianToJalali(
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH)
        )
    }

    // Gregorian -> Jalali
    fun gregorianToJalali(gy0: Int, gm: Int, gd: Int): Triple<Int, Int, Int> {
        val gDm = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
        var gy = gy0
        var jy: Int
        if (gy > 1600) {
            jy = 979
            gy -= 1600
        } else {
            jy = 0
            gy -= 621
        }
        val gy2 = if (gm > 2) gy + 1 else gy
        var days = (365 * gy) + ((gy2 + 3) / 4) - ((gy2 + 99) / 100) + ((gy2 + 399) / 400) - 80 + gd + gDm[gm - 1]
        jy += 33 * (days / 12053)
        days %= 12053
        jy += 4 * (days / 1461)
        days %= 1461
        if (days > 365) {
            jy += (days - 1) / 365
            days = (days - 1) % 365
        }
        val jm: Int
        val jd: Int
        if (days < 186) {
            jm = 1 + days / 31
            jd = 1 + days % 31
        } else {
            jm = 7 + (days - 186) / 30
            jd = 1 + (days - 186) % 30
        }
        return Triple(jy, jm, jd)
    }

    // Jalali -> Gregorian
    fun jalaliToGregorian(jy0: Int, jm: Int, jd: Int): Triple<Int, Int, Int> {
        var jy = jy0
        var gy: Int
        if (jy > 979) {
            gy = 1600
            jy -= 979
        } else {
            gy = 621
        }
        var days = (365 * jy) + ((jy / 33) * 8) + (((jy % 33) + 3) / 4) + 78 + jd +
                if (jm < 7) (jm - 1) * 31 else ((jm - 7) * 30) + 186
        gy += 400 * (days / 146097)
        days %= 146097
        if (days > 36524) {
            gy += 100 * ((days - 1) / 36524)
            days = (days - 1) % 36524
            if (days >= 365) days += 1
        }
        gy += 4 * (days / 1461)
        days %= 1461
        if (days > 365) {
            gy += (days - 1) / 365
            days = (days - 1) % 365
        }
        val gd = days + 1
        val isLeap = (gy % 4 == 0 && gy % 100 != 0) || gy % 400 == 0
        val salA = intArrayOf(0, 31, if (isLeap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        var gm = 1
        var gdRemain = gd
        while (gm < 13 && gdRemain > salA[gm]) {
            gdRemain -= salA[gm]
            gm += 1
        }
        return Triple(gy, gm, gdRemain)
    }

    fun isLeapJalaliYear(jy: Int): Boolean {
        // simple 33-year cycle rule, accurate for a wide range around present
        val r = jy % 33
        return r == 1 || r == 5 || r == 9 || r == 13 || r == 17 || r == 22 || r == 26 || r == 30
    }

    fun daysInJalaliMonth(jy: Int, jm: Int): Int {
        return when {
            jm <= 6 -> 31
            jm <= 11 -> 30
            else -> if (isLeapJalaliYear(jy)) 30 else 29
        }
    }

    fun dateKey(jy: Int, jm: Int, jd: Int): String =
        String.format(Locale.US, "%04d-%02d-%02d", jy, jm, jd)

    fun monthPrefix(jy: Int, jm: Int): String =
        String.format(Locale.US, "%04d-%02d-", jy, jm)

    fun normalizeDateKey(value: String): String? {
        val ascii = buildString(value.length) {
            value.trim().forEach { ch ->
                val digit = when (ch) {
                    in '۰'..'۹' -> '0'.code + (ch.code - '۰'.code)
                    in '٠'..'٩' -> '0'.code + (ch.code - '٠'.code)
                    in '0'..'9' -> ch.code
                    else -> -1
                }
                if (digit >= 0) append(digit.toChar())
                else append(ch)
            }
        }
        if (ascii.length != 10 || ascii[4] != '-' || ascii[7] != '-') return null
        val year = ascii.substring(0, 4).toIntOrNull() ?: return null
        val month = ascii.substring(5, 7).toIntOrNull() ?: return null
        val day = ascii.substring(8, 10).toIntOrNull() ?: return null
        if (month !in 1..12 || day !in 1..daysInJalaliMonth(year, month)) return null
        return dateKey(year, month, day)
    }
}
