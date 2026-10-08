package me.river.remoter.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The daily path against the staging laptop: browse, search, make a folder, start it, look, end it. */
@RunWith(AndroidJUnit4::class)
class FlowE2eTest {
    private val p = Phone()
    private val name = "e2e-${Args.runId}"

    @Before
    fun paired() {
        p.launch()
        p.needPaired()
    }

    @Test
    fun daily_path() {
        p.textHas("Connected", 30_000)

        p.tapNew()
        p.tap("Folder name or path")
        p.type(By.clazz("android.widget.EditText"), Args.folder.substringAfterLast('/').take(3))
        assertTrue("no search results: ${p.visible()}", p.has(Args.folder.substringAfterLast('/'), 5_000))
        p.d.pressBack()
        p.d.pressBack()

        // Into the folder, row by row, the way a thumb gets there.
        p.tapNew()
        p.tap("Folder name or path")
        p.d.pressBack()
        for (part in Args.folder.split('/')) p.tap(part)

        p.tap("+ New folder")
        p.type(By.clazz("android.widget.EditText"), name)
        p.tap("Create")
        p.text(name, 15_000)

        p.tap(name)
        p.tap("Start in $name")
        p.tap("Start session")
        p.text("Accepted by ${Args.host}", 15_000)
        p.text("Ready", 60_000)
        p.tap("Done")

        p.find(By.descContains("Session $name"), 15_000).click()
        p.text("End session", 10_000).click()
        p.text("Sessions", 10_000)
        assertTrue("the card never went away", p.d.wait(Until.gone(By.descContains("Session $name")), 30_000))
    }
}
