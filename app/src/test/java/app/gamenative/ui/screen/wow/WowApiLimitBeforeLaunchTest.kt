package app.gamenative.ui.screen.wow

import android.app.Application
import com.winlator.container.Container
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * 1.2.26 (launch review): XServerScreen applies the session's first limit before the setup thread reaches WowRuntimeTuning.apply,
 * so live() used the previous session's API limit (0 in a fresh process): with "api" pacing or a typed DXVK/VKD3D limiter the app
 * Present limiter stayed on top of DXVK/VKD3D's, and the driver FPS file went back to the Quick Menu limit after a background trip.
 * expectLaunch sets the coming launch's API limit first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class WowApiLimitBeforeLaunchTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private fun container(saved: String, limit: Int, dx12: Boolean = false) = Container("wow_forever").apply {
        envVars = saved
        dxWrapper = WowBundledComponents.DXVK_ID
        putExtra("fpsLimiterTarget", limit)
        if (dx12) putExtra(WowVkd3dComponents.API_KEY, WowVkd3dComponents.DX12)
    }

    @Test fun firstLiveLimitOfASessionUsesTheComingLaunchsApiLimit() {
        val prefs = context.getSharedPreferences(WowLaunchPolicy.PREFS, 0)
        prefs.edit().clear().commit()
        WowRuntimeTuning.expectLaunch(context, container("DXVK_CONFIG=dxgi.maxFrameRate=30", 60))
        WowRuntimeTuning.live(true, 60).let { assertEquals(0, it.app); assertEquals(30, it.inForce) }
        WowRuntimeTuning.expectLaunch(context, container("VKD3D_FRAME_RATE=30", 60, dx12 = true))
        WowRuntimeTuning.live(true, 60).let { assertEquals(0, it.app); assertEquals(30, it.inForce) }
        prefs.edit().putString("pacing", "api").commit()
        WowRuntimeTuning.expectLaunch(context, container("", 60))
        WowRuntimeTuning.live(true, 60).let { assertEquals(0, it.app); assertEquals(60, it.inForce) }
        assertEquals("a lower Quick Menu request still binds", 45, WowRuntimeTuning.live(true, 45).app)
        prefs.edit().clear().commit()
        WowRuntimeTuning.expectLaunch(context, container("", 60))
        assertEquals("app pacing owns nothing API-side", 0, WowRuntimeTuning.apiLimitAtLaunch)
    }

    @Test fun driverFileKeepsTheApiLimitAcrossABackgroundTrip() {
        context.getSharedPreferences(WowLaunchPolicy.PREFS, 0).edit().clear().commit()
        val writes = mutableListOf<String>()
        WowRuntimeTuning.expectLaunch(context, container("DXVK_CONFIG=dxgi.maxFrameRate=30", 60))
        val d = WowRuntimeTuning.DriverFpsFile(File.createTempFile("fps", null)) { _, fps, hz -> synchronized(writes) { writes += "$fps $hz" } }
        d.setLimit(WowRuntimeTuning.live(true, 60).inForce, 144)
        d.setBackground(true)
        d.setBackground(false)
        val end = System.currentTimeMillis() + 2000
        while (System.currentTimeMillis() < end && synchronized(writes) { writes.lastOrNull() } != "30 144") Thread.sleep(10)
        assertEquals("30 144", synchronized(writes) { writes.last() })
    }
}
