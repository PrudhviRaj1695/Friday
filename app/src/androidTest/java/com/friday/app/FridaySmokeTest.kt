package com.friday.app

import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class FridaySmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun homeScreenLaunchesAndShowsFridayBrand() {
        composeRule.onNodeWithText("FRIDAY").assertExists()
        composeRule.onNodeWithText("PRIVATE BY DESIGN  •  CLOUD OFF").assertExists()
    }

    @Test
    fun setupDialogCanBeOpened() {
        composeRule.onNodeWithText("⋮").performClick()
        composeRule.onNodeWithText("FRIDAY setup").assertExists()
        composeRule.onNodeWithText("ON-DEVICE MODEL").assertExists()
    }
}
