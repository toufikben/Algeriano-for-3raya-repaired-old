package com.example.i18n

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.BuildConfig
import com.example.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VersionLabelTest {

    // The version used to be written into the strings by hand and showed 1.1.0 on 1.1.5.
    @Test
    fun `version labels show the installed version in every locale`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (qualifier in listOf("", "+en", "+fr", "+de", "+zh", "+ur")) {
            if (qualifier.isNotEmpty()) RuntimeEnvironment.setQualifiers(qualifier)
            val footer = context.getString(R.string.ui_9eb289360bce, BuildConfig.VERSION_NAME)
            val dialog = context.getString(R.string.ui_ed3327bec79e, BuildConfig.VERSION_NAME)
            assertTrue("footer [$qualifier]: $footer", footer.contains(BuildConfig.VERSION_NAME))
            assertTrue("dialog [$qualifier]: $dialog", dialog.contains(BuildConfig.VERSION_NAME))
        }
    }
}
