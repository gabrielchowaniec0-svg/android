package com.kinetic.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.Direction
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Walks through what you do most in Kinetic — starting it, swiping between pages,
 * opening and scrolling the app list — so Android can pre-compile exactly that
 * code at install time.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = "com.kinetic.launcher", includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()
        device.waitForIdle()

        val w = device.displayWidth
        val h = device.displayHeight
        // Swipe between home pages.
        repeat(2) {
            device.swipe((w * 0.85).toInt(), h / 2, (w * 0.15).toInt(), h / 2, 12)
            device.waitForIdle()
        }
        repeat(2) {
            device.swipe((w * 0.15).toInt(), h / 2, (w * 0.85).toInt(), h / 2, 12)
            device.waitForIdle()
        }
        // Open the app list, scroll it, close it.
        device.swipe(w / 2, (h * 0.8).toInt(), w / 2, (h * 0.25).toInt(), 10)
        device.waitForIdle()
        device.findObject(androidx.test.uiautomator.By.scrollable(true))?.fling(Direction.DOWN)
        device.waitForIdle()
        device.pressBack()
        device.waitForIdle()
    }
}
