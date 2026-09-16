package com.example.i18n

import android.content.Context
import androidx.annotation.StringRes
import com.example.R

/** Supported app languages. The selected value is persisted in app-private preferences. */
enum class AppLanguage(val code: String, @StringRes val displayNameRes: Int) {
    ARABIC("ar", R.string.language_arabic),
    ENGLISH("en", R.string.language_english),
    FRENCH("fr", R.string.language_french),
    SPANISH("es", R.string.language_spanish),
    GERMAN("de", R.string.language_german),
    ITALIAN("it", R.string.language_italian),
    PORTUGUESE("pt", R.string.language_portuguese),
    TURKISH("tr", R.string.language_turkish),
    RUSSIAN("ru", R.string.language_russian),
    HINDI("hi", R.string.language_hindi),
    BENGALI("bn", R.string.language_bengali),
    INDONESIAN("id", R.string.language_indonesian),
    MALAY("ms", R.string.language_malay),
    URDU("ur", R.string.language_urdu),
    CHINESE("zh", R.string.language_chinese),
    JAPANESE("ja", R.string.language_japanese),
    KOREAN("ko", R.string.language_korean);

    companion object {
        fun fromCode(code: String?): AppLanguage = entries.firstOrNull { it.code == code } ?: ARABIC
    }
}

class LanguageStore(context: Context) {
    private val storageContext = context.applicationContext ?: context
    private val prefs = storageContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(): AppLanguage = AppLanguage.fromCode(prefs.getString(KEY_LANGUAGE, AppLanguage.ARABIC.code))

    fun set(language: AppLanguage) {
        prefs.edit().putString(KEY_LANGUAGE, language.code).apply()
    }

    private companion object {
        const val PREFS_NAME = "phone_fortress_language"
        const val KEY_LANGUAGE = "selected_language"
    }
}

fun AppLanguage.isRtl(): Boolean = this == AppLanguage.ARABIC || this == AppLanguage.URDU
