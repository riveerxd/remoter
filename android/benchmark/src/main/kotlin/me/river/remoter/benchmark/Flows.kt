package me.river.remoter.benchmark

import android.content.Intent
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until

const val PACKAGE = "me.river.remoter"

fun MacrobenchmarkScope.startHome() {
    startActivityAndWait(Intent().setClassName(PACKAGE, "$PACKAGE.MainActivity").putExtra("fixture_paired", true))
    device.wait(Until.hasObject(By.text("Start in…")), 5_000)
}

fun MacrobenchmarkScope.intoFoldersAndBack() {
    // In through the search pill; back once to drop the keyboard, staying in the browser.
    device.wait(Until.findObject(By.text("Folder name or path")), 3_000)?.click()
    device.wait(Until.hasObject(By.text("New folder")), 3_000)
    device.pressBack()
    // Rows recompose during the slide, so find the row afresh for every tap.
    repeat(3) {
        runCatching { device.wait(Until.findObject(By.text("remoter")), 2_000)?.click() }
        device.wait(Until.hasObject(By.text("New folder")), 2_000)
        device.waitForIdle()
    }
    repeat(3) {
        device.pressBack()
        device.waitForIdle()
    }
}

fun MacrobenchmarkScope.dragStartSheet() {
    device.findObject(By.text("remoter"))?.click()
    device.wait(Until.hasObject(By.text("Start session")), 3_000)
    val h = device.displayHeight
    val w = device.displayWidth
    repeat(2) {
        device.swipe(w / 2, (h * 0.45).toInt(), w / 2, (h * 0.75).toInt(), 30)
        device.waitForIdle()
    }
    device.pressBack()
}

fun MacrobenchmarkScope.scrollHome() {
    val sheet = device.findObject(By.text("Start in…")) ?: return
    val h = device.displayHeight
    val w = device.displayWidth
    device.swipe(w / 2, (h * 0.85).toInt(), w / 2, (h * 0.3).toInt(), 20)
    device.waitForIdle()
    device.findObject(By.scrollable(true))?.let { it.scroll(Direction.DOWN, 0.8f); it.scroll(Direction.UP, 0.8f) }
    device.swipe(w / 2, (h * 0.3).toInt(), w / 2, (h * 0.85).toInt(), 20)
}
