package com.example.i18n

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale

/**
 * The in-app language picker silently did nothing on Android 12+ because the
 * Activity applied the locale with the deprecated
 * `resources.updateConfiguration()`.
 *
 * Two separate things are pinned here, because Robolectric can only answer one
 * of them honestly:
 *
 *  1. The wrapper really redirects `getResources()` — that is the production
 *     mechanism, since `getString`, `getText` and Compose's `stringResource`
 *     all read from it. Robolectric's `createConfigurationContext` shadow does
 *     not re-resolve resource qualifiers, so the locale it returns cannot be
 *     asserted here; only the redirection can.
 *  2. Every supported language genuinely has its own translated strings, which
 *     is checked through Robolectric's own qualifier support.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocaleContextTest {

    private val base: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `wrapper redirects resources away from the base context`() {
        val wrapped = LocaleContext(base, Locale.forLanguageTag("ar"))
        assertNotSame(
            "getResources() must not fall through to the base context, or the " +
                "device locale would keep winning",
            base.resources,
            wrapped.resources
        )
    }

    @Test
    fun `every supported language has non-blank translated strings`() {
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
    fun `every advertised language ships its own values folder`() {
        // res/xml/locales_config.xml advertises these languages to Android 13+'s
        // per-app language picker. If a language is advertised without a
        // values-<code>/strings.xml, the picker offers a language that renders
        // English — worse than not offering it at all.
        val missing = AppLanguage.entries
            .filter { it.code != "en" }
            .filter { code ->
                val dir = File("src/main/res/values-${code.code}")
                !File(dir, "strings.xml").isFile
            }
            .map { it.code }
        assertEquals("Advertised languages without translations: $missing", emptyList<String>(), missing)
    }

    @Test
    fun `findActivity unwraps a wrapper chain and terminates`() {
        // Robolectric's application context has no Activity above it; the walk
        // must terminate without throwing and return null rather than loop.
        assertEquals(null, LocaleContext(base, Locale.forLanguageTag("ar")).findActivity())
    }
}
