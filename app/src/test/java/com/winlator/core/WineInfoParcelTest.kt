package com.winlator.core

import android.app.Application
import android.os.Parcel
import android.os.Parcelable
import androidx.compose.runtime.saveable.SaverScope
import app.gamenative.ui.data.XServerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * XServerState keeps its WineInfo in a Compose saver, and Android writes saver values into the activity's
 * saved-state Bundle: the value has to stay Parcelable or the app crashes when the activity is saved.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class WineInfoParcelTest {
    @Test fun wineInfoSurvivesAParcelRoundTrip() {
        val info = WineInfo.MAIN_WINE_VERSION
        val parcel = Parcel.obtain()
        try {
            parcel.writeParcelable(info, 0)
            parcel.setDataPosition(0)
            val copy = parcel.readParcelable<WineInfo>(WineInfo::class.java.classLoader)
            assertEquals(info.identifier(), copy!!.identifier())
            assertEquals(info.path, copy.path)
        } finally {
            parcel.recycle()
        }
    }

    @Test fun everyValueOfTheXServerStateSaverCanGoIntoABundle() {
        // mapSaver stores the map as a flat list: key, value, key, value, ...
        val saved = with(XServerState.Saver) { SaverScope { true }.save(XServerState()) } as List<*>
        for (i in 1 until saved.size step 2) {
            val key = saved[i - 1]
            val value = saved[i]
            assertTrue("$key (${value?.javaClass}) must be bundle-saveable",
                value is String || value is Boolean || value is Int || value is Parcelable)
        }
    }
}
