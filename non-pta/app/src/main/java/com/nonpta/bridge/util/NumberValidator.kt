package com.nonpta.bridge.util

/** Strips formatting and validates Pakistani / international phone numbers. */
object NumberValidator {

    private val stripPattern = Regex("[\\s\\-().]")

    /** Removes spaces, dashes, parens, and dots. */
    fun normalize(number: String): String = number.replace(stripPattern, "")

    /**
     * Validates a phone number.
     * Accepted formats:
     * - Pakistani: 03xx (11 digits), +923xx (13 chars), 923xx (12 digits)
     * - International: starts with + and ≥ 8 digits
     */
    fun isValid(number: String): Boolean {
        val n = normalize(number)
        return when {
            n.startsWith("+92") && n.length == 13 -> true
            n.startsWith("92") && n.length == 12 -> true
            n.startsWith("03") && n.length == 11 -> true
            n.startsWith("+") && n.length >= 9 -> true
            n.length in 7..15 && n.all { it.isDigit() || it == '+' } -> true
            else -> false
        }
    }

    /** Pretty display format: +92 3xx xxx xxxx */
    fun formatDisplay(number: String): String {
        val n = normalize(number)
        return when {
            n.startsWith("+92") && n.length == 13 ->
                "+92 ${n.substring(3, 6)} ${n.substring(6, 9)} ${n.substring(9)}"
            n.startsWith("03") && n.length == 11 ->
                "${n.substring(0, 4)} ${n.substring(4, 7)} ${n.substring(7)}"
            else -> n
        }
    }
}
