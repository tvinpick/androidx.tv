package com.tvinpick.lighthome

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

data class AppEntry(
    val packageName: String,
    val label: String,
    val component: ComponentName,
    val leanback: Boolean,
    val installed: Long = 0,
)

class AppImage(val bitmap: ImageBitmap, val isBanner: Boolean)

object Apps {
    /** Все запускаемые приложения: LEANBACK_LAUNCHER и LAUNCHER, без дублей, по имени. */
    fun load(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val result = LinkedHashMap<String, AppEntry>()
        for ((category, leanback) in listOf(
            Intent.CATEGORY_LEANBACK_LAUNCHER to true,
            Intent.CATEGORY_LAUNCHER to false,
        )) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (ri in pm.queryIntentActivities(intent, 0)) {
                val ai = ri.activityInfo ?: continue
                if (ai.packageName == context.packageName) continue
                if (result.containsKey(ai.packageName)) continue
                result[ai.packageName] = AppEntry(
                    packageName = ai.packageName,
                    label = ri.loadLabel(pm).toString(),
                    component = ComponentName(ai.packageName, ai.name),
                    leanback = leanback,
                    installed = runCatching { pm.getPackageInfo(ai.packageName, 0).firstInstallTime }
                        .getOrDefault(0L),
                )
            }
        }
        return result.values.sortedBy { it.label.lowercase() }
    }

    fun openInfo(context: Context, packageName: String) {
        runCatching {
            context.startActivity(
                Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.fromParts("package", packageName, null),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    fun launch(context: Context, app: AppEntry) {
        val category = if (app.leanback) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(category)
            .setComponent(app.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        runCatching { context.startActivity(intent) }
    }

    private val images = object : LruCache<String, AppImage>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: AppImage) =
            value.bitmap.width * value.bitmap.height * 4
    }

    fun cachedImage(packageName: String): AppImage? = images.get(packageName)

    /** Вызывать не в главном потоке. Баннер приложения, если нет — иконка. */
    fun loadImage(context: Context, app: AppEntry): AppImage? {
        images.get(app.packageName)?.let { return it }
        val pm = context.packageManager
        val banner = runCatching { pm.getApplicationBanner(app.packageName) }.getOrNull()
            ?: runCatching { pm.getActivityBanner(app.component) }.getOrNull()
        val image = if (banner != null) {
            AppImage(banner.toBitmap(320, 180).asImageBitmap(), isBanner = true)
        } else {
            val icon = runCatching { pm.getActivityIcon(app.component) }.getOrNull() ?: return null
            AppImage(icon.toBitmap(128, 128).asImageBitmap(), isBanner = false)
        }
        images.put(app.packageName, image)
        return image
    }

    fun trimCache() = images.evictAll()

    private fun Drawable.toBitmap(maxW: Int, maxH: Int): Bitmap {
        if (this is BitmapDrawable && bitmap != null &&
            bitmap.width <= maxW && bitmap.height <= maxH
        ) return bitmap
        val w0 = intrinsicWidth.takeIf { it > 0 } ?: maxW
        val h0 = intrinsicHeight.takeIf { it > 0 } ?: maxH
        val k = minOf(maxW.toFloat() / w0, maxH.toFloat() / h0, 1f)
        val w = (w0 * k).toInt().coerceAtLeast(1)
        val h = (h0 * k).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        setBounds(0, 0, w, h)
        draw(canvas)
        return bmp
    }
}
