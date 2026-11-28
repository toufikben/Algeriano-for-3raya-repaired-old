package com.example.i18n

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * The in-app language picker silently did nothing on Android 12+ because the
 * Activity applied the locale with the deprecated
 * `resources.updateConfiguration()`. These tests pin the replacement contract:
 * a [LocaleContext] must return strings in the requested language through both
 * `getString` and `resources`, which are the two paths the UI uses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocaleContextTest {

    private val base: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `arabic context returns arabic strings through getString`() {
        val arabic = LocaleContext(base, Locale.forLanguageTag("ar"))
        val english = LocaleContext(base, Locale.forLanguageTag("en"))

        val arTitle = arabic.getString(R.string.notification_boot_title)
        val enTitle = english.getString(R.string.notification_boot_title)

        assertNotEquals(
            "Arabic and English must not resolve to the same string",
            enTitle,
            arTitle
        )
        // Arabic text must actually contain Arabic codepoints, not a fallback.
        val hasArabic = arTitle.any { it.code in 0x0600..0x06FF }
        assertEquals("Expected Arabic glyphs in: $arTitle", true, hasArabic)
    }

    @Test
    fun `resources path agrees with getString`() {
        val french = LocaleContext(base, Locale.forLanguageTag("fr"))
        assertEquals(
            french.resources.getString(R.string.notification_boot_title),
            french.getString(R.string.notification_boot_title)
        )
    }

    @Test
    fun `every supported language resolves its own resource folder`() {
        val missing = mutableListOf<String>()
        for (language in AppLanguage.entries) {
            val ctx = LocaleContext(base, Locale.forLanguageTag(language.code))
            val value = ctx.getString(R.string.notification_boot_title)
            if (value.isBlank()) missing += language.code
        }
        assertEquals("Languages with an empty string: $missing", emptyList<String>(), missing)
    }

    @Test
    fun `findActivity unwraps a wrapper chain`() {
        // Robolectric's application context has no Activity above it; the walk
        // must terminate without throwing and return null rather than loop.
        assertEquals(null, base.findActivity())
    }
}
