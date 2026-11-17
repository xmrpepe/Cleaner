package dev.pixelforge

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UiSmokeTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun launchesAndNavigates() {
        rule.waitUntil(90_000) {
            rule.onAllNodesWithText("Enter PixelForge").fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodesWithText("SMART OPTIMIZE").fetchSemanticsNodes().isNotEmpty()
        }
        if (rule.onAllNodesWithText("Enter PixelForge").fetchSemanticsNodes().isNotEmpty()) {
            rule.onNodeWithText("Enter PixelForge").performScrollTo().performClick()
        }
        rule.waitUntil(90_000) { rule.onAllNodesWithText("SMART OPTIMIZE").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Monitor").performClick()
        rule.waitUntil(60_000) { rule.onAllNodesWithText("Per-core").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Lab").performClick()
        rule.waitUntil(60_000) { rule.onAllNodesWithText("Benchmark Lab").fetchSemanticsNodes().isNotEmpty() }
    }
}
