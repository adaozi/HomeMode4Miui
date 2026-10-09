package com.galaxyrio.gracelauncher.data

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.UserHandle
import android.util.AtomicFile
import android.util.Base64
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/** Last known launcher metadata only; Android still controls access to app data and execution. */
class PrivateAppCache(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "private-apps.json"))

    suspend fun load(user: UserHandle, serial: Long): List<LauncherApp> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val root = JSONObject(file.readFully().toString(Charsets.UTF_8))
                if (root.getLong("serial") != serial) return@runCatching emptyList()
                val entries = root.getJSONArray("apps")
                (0 until entries.length()).mapNotNull { index ->
                    val entry = entries.getJSONObject(index)
                    val component = ComponentName.unflattenFromString(entry.getString("component")) ?: return@mapNotNull null
                    LauncherApp(component, entry.getString("label"), decodeIcon(entry.optString("icon")),
                        monochromeIcon = decodeIcon(entry.optString("monochrome")),
                        monochromeScale = entry.optDouble("scale", 1.4).toFloat(),
                        isSystemApp = entry.optBoolean("system"), isAdaptiveIcon = entry.optBoolean("adaptive"),
                        user = user, userSerial = serial, isPrivateSpace = true)
                }
            }.getOrDefault(emptyList())
        }
    }

    suspend fun save(serial: Long, apps: List<LauncherApp>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val entries = JSONArray()
            apps.forEach { app ->
                entries.put(JSONObject().put("component", app.componentName.flattenToString()).put("label", app.originalLabel)
                    .put("icon", encodeIcon(app.icon)).put("monochrome", encodeIcon(app.monochromeIcon))
                    .put("scale", app.monochromeScale).put("system", app.isSystemApp).put("adaptive", app.isAdaptiveIcon))
            }
            val bytes = JSONObject().put("serial", serial).put("apps", entries).toString().toByteArray(Charsets.UTF_8)
            val stream = file.startWrite()
            try { stream.write(bytes); file.finishWrite(stream) }
            catch (error: Exception) { file.failWrite(stream); throw error }
        }
    }

    private fun encodeIcon(icon: ImageBitmap?): String = icon?.let {
        ByteArrayOutputStream().use { output ->
            check(it.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, output))
            Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
        }
    }.orEmpty()

    private fun decodeIcon(encoded: String): ImageBitmap? = if (encoded.isEmpty()) null else runCatching {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }.getOrNull()

    companion object { private val mutex = Mutex() }
}
