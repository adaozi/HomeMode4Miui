package com.galaxyrio.gracelauncher.data

internal fun normalizeIconPackOrder(packages: List<String>): List<String> =
    packages.filter(String::isNotBlank).distinct()

/** A pack's generic fallback must not hide a matching icon in a later pack. */
internal inline fun <P, I : Any> firstMatchingPackIcon(packs: List<P>, resolve: (P) -> I?): Pair<P, I>? {
    for (pack in packs) resolve(pack)?.let { return pack to it }
    return null
}
