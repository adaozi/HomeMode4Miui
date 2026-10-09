package com.galaxyrio.gracelauncher.data.weather

import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.CancellationSignal
import androidx.core.net.toUri
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Read-only client for Breezy Weather's opt-in sharing provider (protocol 0.x).
 *
 * Contract: https://github.com/breezy-weather/breezy-weather-data-sharing-lib
 * This deliberately has no network client, location permission, or private database access.
 */
class BreezyWeatherRepository(context: Context) {
    private val appContext = context.applicationContext

    fun installedPackage(): String? = try {
        @Suppress("DEPRECATION")
        if (appContext.packageManager.getApplicationInfo(PACKAGE_NAME, 0).enabled) PACKAGE_NAME else null
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    suspend fun load(preferredLocationId: String? = null): WeatherState = withContext(Dispatchers.IO) {
        var locations = emptyList<WeatherLocation>()
        var selectedId: String? = null
        try {
            if (installedPackage() == null) return@withContext WeatherState(WeatherStatus.NotInstalled)

            // Earlier Breezy versions have neither this provider nor its runtime permission.
            @Suppress("DEPRECATION")
            val provider = appContext.packageManager.resolveContentProvider(AUTHORITY, 0)
            if (provider == null || !provider.enabled || !provider.exported || provider.packageName != PACKAGE_NAME) {
                return@withContext WeatherState(WeatherStatus.UnsupportedVersion)
            }
            if (appContext.checkSelfPermission(READ_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
                return@withContext WeatherState(WeatherStatus.PermissionRequired)
            }

            val major = query(uri("version")) { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(cursor.getColumnIndexOrThrow("major")) else null
            }
            if (major != SUPPORTED_MAJOR_VERSION) {
                return@withContext WeatherState(WeatherStatus.UnsupportedVersion)
            }

            locations = query(uri("locations").buildUpon().appendQueryParameter("limit", "50").build()) { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(BreezyWeatherParser.location(cursor))
                }.distinctBy(WeatherLocation::id)
            } ?: throw IOException("Breezy locations are unavailable")
            if (locations.isEmpty()) return@withContext WeatherState(WeatherStatus.NoLocations)

            // IDs are not permanent: always re-list, and use Breezy's first location if one was removed.
            val selected = locations.firstOrNull { it.id == preferredLocationId } ?: locations.first()
            selectedId = selected.id
            val weatherUri = uri("weather").buildUpon()
                .appendQueryParameter("withDaily", "true")
                .appendQueryParameter("withHourly", "true")
                .appendQueryParameter("withMinutely", "false")
                .appendQueryParameter("withAlerts", "false")
                .appendQueryParameter("withNormals", "false")
                // Omitting temperatureUnit follows the user's unit setting in Breezy.
                .build()
            val snapshot = query(weatherUri, "id=${selected.id}") { cursor ->
                if (!cursor.moveToFirst()) return@query null
                val weatherColumn = cursor.getColumnIndexOrThrow("weather")
                if (cursor.isNull(weatherColumn)) return@query null
                val location = BreezyWeatherParser.location(cursor)
                BreezyWeatherParser.weather(cursor.getBlob(weatherColumn), location)
            }
            WeatherState(
                status = if (snapshot == null) WeatherStatus.NoWeather else WeatherStatus.Ready,
                snapshot = snapshot,
                locations = locations,
                selectedLocationId = selectedId,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            // A grant can be revoked between queries; discard any earlier location data too.
            WeatherState(WeatherStatus.PermissionRequired)
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            WeatherState(WeatherStatus.Error, locations = locations, selectedLocationId = selectedId)
        }
    }

    /** Runs on IO; cancellation also interrupts a remote provider query. Every cursor is closed. */
    private suspend fun <T> query(
        uri: Uri,
        selection: String? = null,
        read: (Cursor) -> T,
    ): T? = suspendCancellableCoroutine { continuation ->
        val cancellation = CancellationSignal()
        continuation.invokeOnCancellation { cancellation.cancel() }
        try {
            val value = appContext.contentResolver.query(uri, null, selection, null, null, cancellation)?.use(read)
            continuation.resume(value)
        } catch (error: Exception) {
            continuation.resumeWithException(error)
        }
    }

    private fun uri(path: String): Uri = "content://$AUTHORITY/$path".toUri()

    companion object {
        const val PACKAGE_NAME = "org.breezyweather"
        const val READ_PERMISSION = "$PACKAGE_NAME.READ_PROVIDER"
        const val UPDATE_ACTION = "$PACKAGE_NAME.ACTION_UPDATE_NOTIFIER"
        const val AUTHORITY = "$PACKAGE_NAME.provider.weather"
        private const val SUPPORTED_MAJOR_VERSION = 0
    }
}
