package app.gamenative.ui.widget

import android.content.Context
import android.content.ContextWrapper
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.test.core.app.ApplicationProvider
import app.gamenative.ui.data.PerformanceHudConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HudBatteryDemandRegressionTest {
    private val base = PerformanceHudConfig(showCpuUsage = false, showGpuUsage = false, showRamUsage = false,
        showBatteryLevel = false, showPowerDraw = false, showBatteryRuntime = false, showBatteryTemperature = false,
        showCpuTemperature = false, showGpuTemperature = false, showGpuClock = false)
    private fun collect(config: PerformanceHudConfig): Pair<BatteryManager, HudSnapshot> {
        val battery = mock<BatteryManager>()
        whenever(battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)).thenReturn(76)
        whenever(battery.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)).thenReturn(-2000000L)
        whenever(battery.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)).thenReturn(4000000L)
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext()) {
            override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter?): Intent =
                Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_DISCHARGING)
                    .putExtra(BatteryManager.EXTRA_VOLTAGE, 4000).putExtra(BatteryManager.EXTRA_TEMPERATURE, 320)
            override fun getSystemService(name: String): Any? = if (name == Context.BATTERY_SERVICE) battery else super.getSystemService(name)
        }
        val view = PerformanceHudView(context, { 60f }, config)
        val snapshot = PerformanceHudView::class.java.getDeclaredMethod("collectSnapshot", Float::class.javaPrimitiveType,
            PerformanceHudConfig::class.java, FrameTimeReading::class.java)
            .apply { isAccessible = true }.invoke(view, 60f, config, null) as HudSnapshot
        return battery to snapshot
    }
    @Test fun temperatureOnlyDoesNotQueryAnyBinderBatteryProperty() {
        val (battery, _) = collect(base.copy(showBatteryTemperature = true))
        verify(battery, never()).getIntProperty(any())
        verify(battery, never()).getLongProperty(any())
    }
    @Test fun batteryPercentOnlyDoesNotQueryCurrentOrCharge() {
        val (battery, snapshot) = collect(base.copy(showBatteryLevel = true))
        assertEquals("BAT 76%", snapshot.battery)
        verify(battery).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        verify(battery, never()).getLongProperty(any())
    }
    @Test fun powerOnlyReadsCurrentButNotChargeOrCapacity() {
        val (battery, snapshot) = collect(base.copy(showPowerDraw = true))
        assertEquals("PWR 8.0W", snapshot.power)
        verify(battery).getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        verify(battery, never()).getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        verify(battery, never()).getIntProperty(any())
    }
    @Test fun runtimeKeepsBothLivePropertiesAndAllOffReadsNone() {
        val (battery, snapshot) = collect(base.copy(showBatteryRuntime = true))
        assertNotNull(snapshot.runtime)
        verify(battery).getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        verify(battery).getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val (off, _) = collect(base)
        verify(off, never()).getIntProperty(any())
        verify(off, never()).getLongProperty(any())
    }
}
