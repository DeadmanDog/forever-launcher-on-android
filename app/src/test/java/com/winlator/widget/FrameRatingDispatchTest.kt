package com.winlator.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class FrameRatingDispatchTest {
    private class RecordingRating(context: Context) : FrameRating(context) {
        var posts = 0
        var pending: Runnable? = null
        override fun post(action: Runnable): Boolean {
            posts++
            pending = action
            return true
        }
    }

    @Test fun stalledUiQueuesOneRefresh() {
        val rating = RecordingRating(ApplicationProvider.getApplicationContext())
        val initialPosts = rating.posts
        repeat(22_500) { rating.update(1000L + it * 16L) }
        assertEquals(62.5f, rating.currentFPS, 0.1f)
        assertEquals(1, rating.posts - initialPosts)
        rating.pending!!.run()
        repeat(64) { rating.update(361_000L + it * 16L) }
        assertEquals(2, rating.posts - initialPosts)
    }

    @Test fun multipleProducersKeepTheReadoutFinite() {
        val rating = RecordingRating(ApplicationProvider.getApplicationContext())
        val threads = List(4) { producer -> Thread {
            repeat(5000) { rating.update(1000L + it * 16 + producer) }
        } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertTrue(rating.currentFPS.isFinite())
    }
}
