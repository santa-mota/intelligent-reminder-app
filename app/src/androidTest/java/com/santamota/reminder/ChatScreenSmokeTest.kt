package com.santamota.reminder

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.santamota.reminder.ui.MainActivity
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Light Compose smoke test: launches MainActivity, ensures the chat tab
 * renders the composer field.
 *
 * Requires a connected device or emulator. Skipped in CI environments that
 * don't have one.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ChatScreenSmokeTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun composer_is_shown_on_launch() {
        composeRule.onNodeWithText("Tell me what to remind you about…")
            .assertIsDisplayed()
    }

    @Test
    fun typing_into_composer_works() {
        composeRule.onNodeWithText("Tell me what to remind you about…")
            .performTextInput("remind me at 3 PM")
    }
}
