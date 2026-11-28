package com.example.i18n

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Supported app languages. The selected value is persisted in app-private preferences.
 *
 * [nativeName] is the endonym — the language's own name for itself — and is what
 * the picker shows. Rendering it from a string resource meant an English-speaking
 * user saw "Arabic" spelled in Arabic once the UI was Arabic, and an Arabic
 * speaker hunting for their language in an English UI saw "Arabic" in Latin
 * script. An endonym is readable to the person looking for it in either case.
 */
enum class AppLanguage(val code: String, val nativeName: String) {
    ARABIC("ar", "العربية"),
    ENGLISH("en", "English"),
    FRENCH("fr", "Français"),
    SPANISH("es", "Español"),
    GERMAN("de", "Deutsch"),
    ITALIAN("it", "Italiano"),
    PORTUGUESE("pt", "Português"),
    TURKISH("tr", "Türkçe"),
    RUSSIAN("ru", "Русский"),
    HINDI("hi", "हिन्दी"),
    BENGALI("bn", "বাংলা"),
    INDONESIAN("id", "Bahasa Indonesia"),
    MALAY("ms", "Bahasa Melayu"),
    URDU("ur", "اردو"),
    CHINESE("zh", "中文"),
    JAPANESE("ja", "日本語"),
    KOREAN("ko", "한국어");

    companion object {
        fun fromCode(code: String?): AppLanguage =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: ENGLISH

        /**
         * First-run default: the device language when the app is translated into
         * it, English otherwise. Defaulting to Arabic regardless — which is what
         * happened before — showed an English phone an Arabic UI on first launch.
         */
        fun fromDeviceLocale(locale: Locale): AppLanguage =
            entries.firstOrNull { it.code.equals(locale.language, ignoreCase = true) } ?: ENGLISH
    }
}

class LanguageStore(context: Context) {
    private val storageContext = context.applicationContext ?: context
    private val prefs = storageContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(): AppLanguage {
        val stored = prefs.getString(KEY_LANGUAGE, null)
        return if (stored != null) {
            AppLanguage.fromCode(stored)
        } else {
            AppLanguage.fromDeviceLocale(Locale.getDefault())
        }
    }

    fun set(language: AppLanguage) {
        prefs.edit().putString(KEY_LANGUAGE, language.code).apply()
    }

    private companion object {
        const val PREFS_NAME = "phone_fortress_language"
        const val KEY_LANGUAGE = "selected_language"
    }
}

fun AppLanguage.isRtl(): Boolean = this == AppLanguage.ARABIC || this == AppLanguage.URDU

/**
 * Services, workers and receivers are created with the device locale, so their own
 * [getString] ignores the language chosen inside the app. Resolve user-visible text
 * through this context instead.
 */
fun Context.localizedForAppLanguage(): Context {
    val storage = applicationContext ?: this
    val locale = Locale.forLanguageTag(LanguageStore(storage).get().code)
    return createConfigurationContext(Configuration(resources.configuration).apply { setLocale(locale) })
}
