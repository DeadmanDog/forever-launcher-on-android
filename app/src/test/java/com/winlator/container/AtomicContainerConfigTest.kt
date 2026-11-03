package com.winlator.container

import com.winlator.core.OneShotTasks
import android.app.Application
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class AtomicContainerConfigTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun newConfigPublishesAllDefaultsAndLeavesNoTemporaryFile() {
        val root = temp.newFolder(); val config = File(root, ".container")
        val defaults = JSONObject("{\"wineVersion\":\"proton-custom\",\"screenSize\":\"1920x1080\"}")
        AtomicContainerConfig.save(config, JSONObject(defaults.toString()), defaults)
        assertEquals("proton-custom", JSONObject(config.readText()).getString("wineVersion"))
        assertEquals(listOf(".container"), root.listFiles()!!.map { it.name })
    }
    @Test fun staleWriterPreservesNewerFieldsAndUnknownNestedProperties() {
        val config = temp.newFile()
        val baseline = JSONObject("{\"wineVersion\":\"old\",\"name\":\"WoW\",\"extraData\":{\"cache\":\"old\"}}")
        config.writeText("{\"wineVersion\":\"new\",\"name\":\"WoW\",\"futureKey\":42,\"extraData\":{\"cache\":\"new\",\"future\":true}}")
        val desired = JSONObject(baseline.toString()).put("name", "Renamed")
        AtomicContainerConfig.save(config, baseline, desired)
        val saved = JSONObject(config.readText())
        assertEquals("new", saved.getString("wineVersion")); assertEquals("Renamed", saved.getString("name"))
        assertEquals(42, saved.getInt("futureKey")); assertTrue(saved.getJSONObject("extraData").getBoolean("future"))
        assertEquals("new", saved.getJSONObject("extraData").getString("cache"))
    }
    @Test fun concurrentWritersMergeIndependentChanges() {
        val config = temp.newFile().apply { writeText("{\"a\":0,\"b\":0}") }
        val baseline = JSONObject(config.readText()); val pool = Executors.newFixedThreadPool(2)
        try {
            val tasks = listOf("a", "b").map { key -> pool.submit { AtomicContainerConfig.save(config, baseline, JSONObject(baseline.toString()).put(key, 1)) } }
            tasks.forEach { it.get(5, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
        val result = JSONObject(config.readText()); assertEquals(1, result.getInt("a")); assertEquals(1, result.getInt("b"))
    }
    @Test fun corruptExistingConfigIsNotReplaced() {
        val config = temp.newFile().apply { writeText("incomplete{") }
        assertThrows(Exception::class.java) { AtomicContainerConfig.save(config, null, JSONObject().put("id", "wow")) }
        assertEquals("incomplete{", config.readText())
    }
    @Test fun failedPublicationDoesNotLeaveTemporaryFiles() {
        val root = temp.newFolder(); val config = File(root, ".container").apply { mkdir() }
        assertThrows(Exception::class.java) { AtomicContainerConfig.save(config, null, JSONObject().put("id", "wow")) }
        assertTrue(config.isDirectory); assertEquals(listOf(".container"), root.listFiles()!!.map { it.name })
    }
    @Test fun loadedContainersDoNotOverwriteUnrelatedChanges() {
        val root = temp.newFolder()
        val first = Container("wow_forever").apply { rootDir = root; wineVersion = "initial"; saveData() }
        val second = Container("wow_forever").apply { rootDir = root; loadData(JSONObject(first.configFile.readText())) }
        first.wineVersion = "custom"; first.saveData()
        second.name = "Renamed"; second.saveData()
        val saved = JSONObject(first.configFile.readText())
        assertEquals("custom", saved.getString("wineVersion")); assertEquals("Renamed", saved.getString("name"))
    }
    @Test fun unchangedSaveDoesNotReplaceTheFile() {
        val config = temp.newFile().apply { writeText("{\"a\":1}"); setLastModified(100000) }
        AtomicContainerConfig.save(config, JSONObject(config.readText()), JSONObject(config.readText()))
        assertEquals(100000L, config.lastModified())
    }
    @Test fun oneShotWorkersTerminateAfterSuccessAndFailure() {
        val success = Executors.newSingleThreadExecutor()
        assertEquals(42, OneShotTasks.submit(success) { 42 }.get(5, TimeUnit.SECONDS))
        assertTrue(success.awaitTermination(5, TimeUnit.SECONDS))
        val failure = Executors.newSingleThreadExecutor()
        assertThrows(Exception::class.java) { OneShotTasks.submit<Int>(failure) { error("failure") }.get(5, TimeUnit.SECONDS) }
        assertTrue(failure.awaitTermination(5, TimeUnit.SECONDS))
    }
}
