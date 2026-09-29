package com.example.i18n

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppLanguageContextTest {

    @Test
    fun `localizedForAppLanguage follows the in-app language and keeps separator spacing`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = LanguageStore(context)
        try {
            store.set(AppLanguage.ARABIC)
            assertEquals("؛ ", context.localizedForAppLanguage().getString(R.string.ui_separator))

            store.set(AppLanguage.FRENCH)
            assertEquals(" ; ", context.localizedForAppLanguage().getString(R.string.ui_separator))
        } finally {
            store.set(AppLanguage.ARABIC)
        }
    }
}
