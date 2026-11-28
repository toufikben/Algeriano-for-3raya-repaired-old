package com.example.i18n

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * The in-app language picker silently did nothing on Android 12+ because the
 * Activity applied the locale with the deprecated
 * `resources.updateConfiguration()`. These tests pin the replacement contract.
 *
 * Robolectric does not re-resolve resource qualifiers for a context produced by
 * `createConfigurationContext`, so the wrapper is asserted on the two things
 * that actually matter and that Robolectric can check honestly: the locale
 * reaches the wrapper's resources, and every supported language really has its
 * own translated strings.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocaleContextTest {

    private val base: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `wrapper carries the requested locale for every supported language`() {
        for (language in AppLanguage.entries) {
            val ctx = LocaleContext(base, Locale.forLanguageTag(language.code))
            val actual = ctx.resources.configuration.locales[0].language
            assertEquals(
                "LocaleContext(${language.code}) reported $actual",
                language.code,
                actual
            )
        }
    }

    @Test
    fun `arabic strings differ from english strings`() {
        RuntimeEnvironment.setQualifiers("+en")
        val english = ApplicationProvider.getApplicationContext<Context>()
            .getString(R.string.notification_boot_title)

        RuntimeEnvironment.setQualifiers("+ar")
        val arabic = ApplicationProvider.getApplicationContext<Context>()
            .getString(R.string.notification_boot_title)

        assertNotEquals(
            "Arabic and English must not resolve to the same string",
            english,
            arabic
        )
        assertTrue(
            "Expected Arabic glyphs in: $arabic",
            arabic.any { it.code in 0x0600..0x06FF }
        )
    }

    @Test
    fun `no supported language falls back to a blank string`() {
        val blank = mutableListOf<String>()
        for (language in AppLanguage.entries) {
            RuntimeEnvironment.setQualifiers("+${language.code}")
            val value = ApplicationProvider.getApplicationContext<Context>()
                .getString(R.string.notification_boot_title)
            if (value.isBlank()) blank += language.code
        }
        assertEquals("Languages with an empty string: $blank", emptyList<String>(), blank)
    }

    @Test
    fun `findActivity unwraps a wrapper chain and terminates`() {
        // Robolectric's application context has no Activity above it; the walk
        // must terminate without throwing and return null rather than loop.
        assertEquals(null, LocaleContext(base, Locale.forLanguageTag("ar")).findActivity())
    }
}
