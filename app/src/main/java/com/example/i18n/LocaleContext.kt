package com.example.i18n

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import java.util.Locale

/**
 * A context that resolves strings in the language chosen inside the app.
 *
 * Why this exists: `resources.updateConfiguration()` is deprecated and, on
 * Android 12+, no longer affects the Activity's resources, so picking a language
 * from the in-app picker left the whole UI in the device locale.
 *
 * `Context.getString()`, `getText()` and `stringResource()` all funnel through
 * `getResources()`, and that is the one method [ContextWrapper] leaves open, so
 * redirecting it is enough to localize every text path at once. The wrapper
 * keeps every non-textual call — `startActivity`, `getSystemService`,
 * `packageName` — pointing at the real Activity, which a bare
 * `createConfigurationContext()` result would not.
 */
class LocaleContext(base: Context, locale: Locale) : ContextWrapper(base) {

    private val localized: Context = base.createConfigurationContext(
        Configuration(base.resources.configuration).apply { setLocale(locale) }
    )

    override fun getResources(): Resources = localized.resources

    override fun getAssets() = localized.assets

    override fun getTheme() = localized.theme

    override fun getSystemService(name: String): Any? = localized.getSystemService(name)

    override fun getContentResolver() = localized.contentResolver

    override fun getPackageManager() = localized.packageManager

    override fun getPackageName(): String = localized.packageName

    override fun getApplicationContext(): Context = localized.applicationContext ?: localized
}

/** Walks a [ContextWrapper] chain looking for the Activity that owns the UI. */
fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}
