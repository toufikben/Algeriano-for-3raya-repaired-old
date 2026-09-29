package com.example.i18n

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocalizedStringSpacingTest {

    @Test
    fun `separator keeps the spacing aapt would otherwise trim`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        RuntimeEnvironment.setQualifiers("+en")
        assertEquals("; ", context.getString(R.string.ui_separator))

        RuntimeEnvironment.setQualifiers("+fr")
        assertEquals(" ; ", context.getString(R.string.ui_separator))
    }
}
