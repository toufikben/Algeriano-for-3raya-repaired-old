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
 * from the in-app picker left the whole UI in the device locale. Compose resolves
 * every `stringResource()` through `LocalContext.resources`, so supplying a
 * context whose resources carry the chosen locale is the only reliable way to
 * re-read the strings without recreating the Activity (which would re-lock the
 * PIN gate).
 *
 * It is a [ContextWrapper], not a bare configuration context, so every
 * non-textual call the screens make — `startActivity`, `getSystemService`,
 * `packageName` — keeps working unchanged, and [findActivity] can still reach
 * the hosting Activity through the wrapper chain.
 */
class LocaleContext(base: Context, locale: Locale) : ContextWrapper(base) {

    private val localized: Context = base.createConfigurationContext(
        Configuration(base.resources.configuration).apply { setLocale(locale) }
    )

    override fun getResources(): Resources = localized.resources

    // ContextWrapper.getString() delegates to the *base* context, whose resources
    // still carry the device locale. Every screen here calls context.getString(),
    // so these must be redirected too or the fix would only cover stringResource.
    override fun getString(id: Int): String = localized.getString(id)

    override fun getString(id: Int, vararg formatArgs: Any): String =
        localized.getString(id, *formatArgs)

    override fun getText(id: Int): CharSequence = localized.getText(id)

    override fun getStringArray(id: Int): Array<String> = localized.resources.getStringArray(id)

    override fun getTextArray(id: Int): Array<CharSequence> = localized.resources.getTextArray(id)

    override fun getAssets() = localized.assets

    override fun getTheme() = localized.theme

    override fun getSystemService(name: String): Any? = localized.getSystemService(name)

    override fun getSystemService(serviceClass: Class<*>): Any? = localized.getSystemService(serviceClass)

    override fun getSystemServiceName(serviceClass: Class<*>): String? =
        localized.getSystemServiceName(serviceClass)

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
