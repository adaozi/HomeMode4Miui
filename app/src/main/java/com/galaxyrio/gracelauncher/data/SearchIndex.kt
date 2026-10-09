package com.galaxyrio.gracelauncher.data

import java.util.Locale

/** Shared local matching for app names and contact names. No remote search/index service. */
internal class SearchQuery(val text: String, val fuzzy: Boolean) {
    val literal = text.trim().lowercase(Locale.ROOT)
    val compact = compactSearchText(text)
    val phone = text.filter(Char::isDigit)
    val isPhone = phone.isNotEmpty() && text.all { it.isDigit() || it.isWhitespace() || it in "+-()." }
}

internal class SearchName(val label: String) {
    private val literal = label.lowercase(Locale.ROOT)
    private val compact = compactSearchText(label)
    private val initials = buildString {
        var wordStart = true
        var previousLowercase = false
        label.codePoints().forEach { code ->
            val han = Character.UnicodeScript.of(code) == Character.UnicodeScript.HAN
            val letter = Character.isLetterOrDigit(code)
            if (letter && (han || wordStart || previousLowercase && Character.isUpperCase(code))) {
                compactSearchText(String(Character.toChars(code))).firstOrNull()?.let(::append)
            }
            wordStart = !letter || han
            previousLowercase = Character.isLowerCase(code)
        }
    }

    /** Lower scores rank first; disabling fuzzy search retains ordinary case-insensitive matching. */
    fun score(query: SearchQuery): Int? {
        if (query.literal.isEmpty()) return null
        if (!query.fuzzy || query.compact.isEmpty()) return when {
            literal == query.literal -> 0
            literal.startsWith(query.literal) -> 2
            literal.contains(query.literal) -> 8
            else -> null
        }
        val term = query.compact
        return when {
            compact == term -> 0
            compact.startsWith(term) -> 2
            compact.contains(term) -> 8
            initials.startsWith(term) -> 12
            initials.contains(term) -> 16
            else -> {
                // Ordered subsequences also support short Latin queries such as 'gml' for Gmail.
                var position = 0
                var first = -1
                for (character in term) {
                    val found = compact.indexOf(character, position)
                    if (found < 0) return null
                    if (first < 0) first = found
                    position = found + 1
                }
                40 + position - first - term.length
            }
        }
    }
}

private fun compactSearchText(value: String): String = appSortKey(value).filter(Char::isLetterOrDigit)
