package me.river.remoter

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResult.AccessibilityCheckResultType
import com.google.android.apps.common.testing.accessibility.framework.integrations.espresso.AccessibilityValidator
import kotlinx.collections.immutable.persistentListOf
import me.river.remoter.core.design.RemoterTheme
import me.river.remoter.core.design.components.RemoterSheet
import me.river.remoter.core.net.ClaudeLink
import me.river.remoter.core.net.DenyReason
import me.river.remoter.core.net.FsEntry
import me.river.remoter.core.net.Link
import me.river.remoter.core.net.ListResponse
import me.river.remoter.core.net.LocalState
import me.river.remoter.core.net.PairedLaptop
import me.river.remoter.core.net.SessionState
import me.river.remoter.core.net.SessionSummary
import me.river.remoter.core.net.StuckReason
import me.river.remoter.core.net.SymlinkKind
import me.river.remoter.feature.browser.BrowserCallbacks
import me.river.remoter.feature.browser.BrowserContent
import me.river.remoter.feature.browser.BrowserUi
import me.river.remoter.feature.home.FolderItem
import me.river.remoter.feature.home.HomeCallbacks
import me.river.remoter.feature.home.HomeContent
import me.river.remoter.feature.home.HomeUi
import me.river.remoter.feature.onboarding.CameraAccess
import me.river.remoter.feature.onboarding.OnboardingCallbacks
import me.river.remoter.feature.onboarding.OnboardingContent
import me.river.remoter.feature.onboarding.OnboardingStep
import me.river.remoter.feature.session.DetailUi
import me.river.remoter.feature.session.SessionDetailContent
import me.river.remoter.feature.session.SessionId
import me.river.remoter.feature.session.StartCallbacks
import me.river.remoter.feature.session.StartContent
import me.river.remoter.feature.session.StartForm
import me.river.remoter.feature.session.StartState
import me.river.remoter.feature.session.StartTarget
import me.river.remoter.feature.session.StartUi
import me.river.remoter.feature.session.Step
import me.river.remoter.feature.settings.AuditContent
import me.river.remoter.feature.settings.AuditUi
import me.river.remoter.feature.settings.SettingsCallbacks
import me.river.remoter.feature.settings.SettingsContent
import me.river.remoter.feature.settings.SettingsUi
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Google's accessibility checks (ATF) over every screen, on a device, where
 * the results mean something (they are inconclusive under Robolectric).
 * Any ERROR fails: touch targets, labels, contrast as ATF measures it.
 */
@RunWith(AndroidJUnit4::class)
class AccessibilityChecksTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun checks() {
        val screenBottom = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.heightPixels
        // The one exception: an element cut by the bottom of the screen, like a row of the
        // home sheet below its peek. ATF measures the visible sliver; the full row is 64 dp.
        val cutByScreenEdge = object : org.hamcrest.TypeSafeMatcher<com.google.android.apps.common.testing.accessibility.framework.AccessibilityViewCheckResult>() {
            override fun describeTo(d: org.hamcrest.Description) {
                d.appendText("touch target cut by the bottom of the screen")
            }
            override fun matchesSafely(r: com.google.android.apps.common.testing.accessibility.framework.AccessibilityViewCheckResult): Boolean =
                r.sourceCheckClass.simpleName == "TouchTargetSizeCheck" && (r.element?.boundsInScreen?.bottom ?: 0) >= screenBottom
        }
        compose.enableAccessibilityChecks(
            AccessibilityValidator().setRunChecksFromRootView(true)
                .setThrowExceptionFor(AccessibilityCheckResultType.ERROR)
                .setSuppressingResultMatcher(cutByScreenEdge),
        )
    }

    private fun check(dark: Boolean = true, content: @Composable () -> Unit) {
        compose.setContent { RemoterTheme(dark = dark, reducedMotion = true) { content() } }
        compose.waitForIdle()
        compose.onRoot().tryPerformAccessibilityChecks()
    }

    private val now = 1_790_620_000_000
    private val sess = SessionSummary("rc-a", "remoter", "Projects/remoter", null, now - 5_000_000, SessionState.Ready, null, null, ClaudeLink("s", "https://claude.ai/code/s", null))
    private val home = HomeUi(
        hostname = "r1v3r", link = Link.Up(38), battery = 64, onAc = true, loaded = true, nowMs = now,
        pinned = persistentListOf(FolderItem("Projects/remoter", "remoter", true)),
        recent = persistentListOf(FolderItem("Projects/api", "api", true)),
        sessions = persistentListOf(sess),
    )
    private fun e(n: String, deny: DenyReason? = null) = FsEntry(n, 0, true, true, SymlinkKind.None, null, 3, 0, true, deny == null, deny, false)
    private val list = ListResponse("Projects", false, true, true, null, listOf(e("api"), e("remoter"), e(".ssh", DenyReason.Denied)), false, false)
    private val start = StartUi(open = true, target = StartTarget("Projects/remoter", "remoter", true), form = StartForm(name = "remoter"), hostname = "r1v3r")
    private val laptop = PairedLaptop("r1v3r", "oaGhoaGhoaGhoaGhoaGhoaGhoaGhoaGhoaGhoaGhoaE", "dev", 0, "StrongBox", "TEE", 0)

    @Composable
    private fun Sheet(u: StartUi) = Box(Modifier.fillMaxSize()) { RemoterSheet(true, {}) { StartContent(u, StartCallbacks()) } }

    @Test fun home_dark() = check { HomeContent(home, HomeCallbacks(), false) }
    @Test fun home_light() = check(dark = false) { HomeContent(home, HomeCallbacks(), false) }
    @Test fun home_first_run() = check { HomeContent(home.copy(pinned = persistentListOf(), recent = persistentListOf(), sessions = persistentListOf()), HomeCallbacks(), false) }
    @Test fun home_vpn_off() = check { HomeContent(home.copy(link = Link.VpnOff), HomeCallbacks(), false) }
    @Test fun browser() = check { BrowserContent(BrowserUi("Projects", list, loading = false), BrowserCallbacks(), false) }
    @Test fun browser_light() = check(dark = false) { BrowserContent(BrowserUi("Projects", list, loading = false), BrowserCallbacks(), false) }
    @Test fun start_form() = check { Sheet(start) }
    @Test fun start_form_light() = check(dark = false) { Sheet(start) }
    @Test fun start_starting() = check { Sheet(start.copy(state = StartState.Starting(SessionId("rc-a"), persistentListOf(Step("Fingerprint", true), Step("Accepted by r1v3r", false)), 0, false, false))) }
    @Test fun start_ready() = check { Sheet(start.copy(state = StartState.Ready(SessionId("rc-a"), "remoter", null))) }
    @Test fun start_stuck() = check { Sheet(start.copy(state = StartState.Stuck(SessionId("rc-a"), StuckReason.Untrusted, persistentListOf("Error: Workspace not trusted.")))) }
    @Test fun detail() = check { SessionDetailContent(DetailUi(sess, persistentListOf("line"), now, nowMs = now, hostname = "r1v3r"), {}, {}, {}, {}) }
    @Test fun settings() = check { SettingsContent(SettingsUi(LocalState(laptop = laptop)), SettingsCallbacks()) }
    @Test fun settings_light() = check(dark = false) { SettingsContent(SettingsUi(LocalState(laptop = laptop)), SettingsCallbacks()) }
    @Test fun audit_empty() = check { AuditContent(AuditUi(loading = false, end = true), {}, {}) }
    @Test fun onboarding_connect() = check { OnboardingContent(OnboardingStep.Connect(true, false), CameraAccess.Denied, OnboardingCallbacks(), false) }
    @Test fun onboarding_scan() = check { OnboardingContent(OnboardingStep.Scan(), CameraAccess.Denied, OnboardingCallbacks(), false) }
    @Test fun onboarding_confirm() = check { OnboardingContent(OnboardingStep.Confirm("481207", "A1F309CE"), CameraAccess.Denied, OnboardingCallbacks(), false) }
}
