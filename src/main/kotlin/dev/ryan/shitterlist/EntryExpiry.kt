package dev.ryan.throwerlist

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

object EntryExpiry {
    private val tokenPattern = Regex(
        """(\d+)\s*(years?|year|y|months?|month|mt|days?|day|d|hours?|hour|hrs?|hr|h|minutes?|minute|mins?|min|m|seconds?|second|secs?|sec|s)""",
        RegexOption.IGNORE_CASE,
    )

    data class Definition(
        val years: Long = 0,
        val months: Long = 0,
        val days: Long = 0,
        val hours: Long = 0,
        val minutes: Long = 0,
        val seconds: Long = 0,
    ) {
        val isEmpty: Boolean
            get() = years == 0L && months == 0L && days == 0L && hours == 0L && minutes == 0L && seconds == 0L

        val canonical: String
            get() = listOfNotNull(
                formatUnit(years, "year"),
                formatUnit(months, "month"),
                formatUnit(days, "day"),
                formatUnit(hours, "hour"),
                formatUnit(minutes, "minute"),
                formatUnit(seconds, "second"),
            ).joinToString(" ")

        fun applyTo(baseMillis: Long, zoneId: ZoneId = ZoneId.systemDefault()): Long {
            var base = Instant.ofEpochMilli(baseMillis).atZone(zoneId)
            if (years > 0) {
                base = base.plusYears(years)
            }
            if (months > 0) {
                base = base.plusMonths(months)
            }
            if (days > 0) {
                base = base.plusDays(days)
            }
            if (hours > 0) {
                base = base.plusHours(hours)
            }
            if (minutes > 0) {
                base = base.plusMinutes(minutes)
            }
            if (seconds > 0) {
                base = base.plusSeconds(seconds)
            }
            return base.toInstant().toEpochMilli()
        }
    }

    data class NormalizedEntryExpiry(
        val timeframe: String?,
        val expiresAt: Long?,
    )

    fun parse(rawInput: String): Definition? {
        val input = rawInput.trim()
        if (input.isEmpty()) {
            return null
        }

        var index = 0
        var definition = Definition()
        while (index < input.length) {
            while (index < input.length && (input[index].isWhitespace() || input[index] == ',')) {
                index++
            }
            if (index >= input.length) {
                break
            }

            val match = tokenPattern.find(input, index)
            if (match == null || match.range.first != index) {
                return null
            }

            val amount = match.groupValues[1].toLongOrNull() ?: return null
            if (amount <= 0L) {
                return null
            }

            definition = addAmount(definition, amount, parseUnit(match.groupValues[2]) ?: return null)
            index = match.range.last + 1
        }

        return definition.takeUnless { it.isEmpty }
    }

    fun normalize(rawInput: String?): String? =
        rawInput
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let(::parse)
            ?.canonical

    fun normalizeEntry(entry: PlayerEntry, nowMillis: Long = System.currentTimeMillis()): NormalizedEntryExpiry {
        val parsed = normalize(entry.autoRemoveAfter)?.let(::parse)
        if (parsed == null) {
            return NormalizedEntryExpiry(timeframe = null, expiresAt = null)
        }

        val canonical = parsed.canonical
        val existingExpiry = entry.expiresAt
        val normalizedExpiry = when {
            existingExpiry != null -> existingExpiry
            entry.ts != null -> parsed.applyTo(entry.ts!!)
            else -> parsed.applyTo(nowMillis)
        }

        return NormalizedEntryExpiry(
            timeframe = canonical,
            expiresAt = normalizedExpiry,
        )
    }

    fun hasExpired(expiresAt: Long?, nowMillis: Long = System.currentTimeMillis()): Boolean =
        expiresAt != null && expiresAt <= nowMillis

    fun formatExpiresAt(expiresAt: Long?, formatter: (Long) -> String): String =
        expiresAt?.let(formatter) ?: "None"

    fun remaining(expiresAt: Long?, nowMillis: Long = System.currentTimeMillis()): String? {
        if (expiresAt == null || expiresAt <= nowMillis) {
            return null
        }

        var remaining = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), ZoneId.systemDefault())
        val target = ZonedDateTime.ofInstant(Instant.ofEpochMilli(expiresAt), ZoneId.systemDefault())
        var years = 0L
        var months = 0L
        var days = 0L
        var hours = 0L
        var minutes = 0L
        var seconds = 0L

        while (!remaining.plusYears(1).isAfter(target)) {
            remaining = remaining.plusYears(1)
            years++
        }
        while (!remaining.plusMonths(1).isAfter(target)) {
            remaining = remaining.plusMonths(1)
            months++
        }
        while (!remaining.plusDays(1).isAfter(target)) {
            remaining = remaining.plusDays(1)
            days++
        }
        while (!remaining.plusHours(1).isAfter(target)) {
            remaining = remaining.plusHours(1)
            hours++
        }
        while (!remaining.plusMinutes(1).isAfter(target)) {
            remaining = remaining.plusMinutes(1)
            minutes++
        }
        while (!remaining.plusSeconds(1).isAfter(target)) {
            remaining = remaining.plusSeconds(1)
            seconds++
        }

        return Definition(
            years = years,
            months = months,
            days = days,
            hours = hours,
            minutes = minutes,
            seconds = seconds,
        ).canonical.ifBlank { "less than 1 second" }
    }

    private fun addAmount(definition: Definition, amount: Long, unit: ExpiryUnit): Definition =
        when (unit) {
            ExpiryUnit.YEARS -> definition.copy(years = definition.years + amount)
            ExpiryUnit.MONTHS -> definition.copy(months = definition.months + amount)
            ExpiryUnit.DAYS -> definition.copy(days = definition.days + amount)
            ExpiryUnit.HOURS -> definition.copy(hours = definition.hours + amount)
            ExpiryUnit.MINUTES -> definition.copy(minutes = definition.minutes + amount)
            ExpiryUnit.SECONDS -> definition.copy(seconds = definition.seconds + amount)
        }

    private fun parseUnit(rawUnit: String): ExpiryUnit? =
        when (rawUnit.trim().lowercase(Locale.ROOT)) {
            "y", "year", "years" -> ExpiryUnit.YEARS
            "mt", "month", "months" -> ExpiryUnit.MONTHS
            "d", "day", "days" -> ExpiryUnit.DAYS
            "h", "hr", "hrs", "hour", "hours" -> ExpiryUnit.HOURS
            "m", "min", "mins", "minute", "minutes" -> ExpiryUnit.MINUTES
            "s", "sec", "secs", "second", "seconds" -> ExpiryUnit.SECONDS
            else -> null
        }

    private fun formatUnit(value: Long, unit: String): String? {
        if (value <= 0L) {
            return null
        }

        return if (value == 1L) {
            "1 $unit"
        } else {
            "$value ${unit}s"
        }
    }

    private enum class ExpiryUnit {
        YEARS,
        MONTHS,
        DAYS,
        HOURS,
        MINUTES,
        SECONDS,
    }
}
