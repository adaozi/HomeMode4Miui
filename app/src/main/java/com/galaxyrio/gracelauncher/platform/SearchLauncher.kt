package com.galaxyrio.gracelauncher.platform

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.galaxyrio.gracelauncher.data.SearchContact
import com.galaxyrio.gracelauncher.data.SearchSettings
import java.net.URI

internal object SearchLauncher {
    fun contact(context: Context, contact: SearchContact): Boolean = open(context, Intent(Intent.ACTION_VIEW, contact.uri))

    fun isValidSearchUrl(template: String): Boolean = runCatching {
        val value = template.trim()
        if (!value.contains("{query}") || Uri.parse(value).authority.orEmpty().contains("{query}")) return false
        val uri = URI(value.replace("{query}", "search"))
        uri.scheme?.lowercase() in setOf("https", "http") && !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null && uri.port in -1..65535
    }.getOrDefault(false)

    fun internetUri(query: String, settings: SearchSettings): Uri? {
        val template = (settings.engine.urlTemplate ?: settings.customEngineUrl).trim()
        if (query.isBlank() || !isValidSearchUrl(template)) return null
        // Encode only the inserted query, not the template's existing parameters.
        return Uri.parse(template.replace("{query}", Uri.encode(query.trim())))
    }

    fun internet(context: Context, query: String, settings: SearchSettings): Boolean {
        val uri = internetUri(query, settings) ?: return false
        val intent = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
        // Resolve a generic HTTPS link first so a search engine's app link cannot
        // replace the default browser. Resolution is local; this URL is never opened.
        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).addCategory(Intent.CATEGORY_BROWSABLE)
        val browser = context.packageManager.resolveActivity(browserIntent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
        if (browser != null && browser.exported && browser.packageName != "android" && !browser.name.contains("ResolverActivity")) {
            intent.setPackage(browser.packageName)
        }
        return open(context, intent)
    }

    private fun open(context: Context, intent: Intent): Boolean = runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)
}
