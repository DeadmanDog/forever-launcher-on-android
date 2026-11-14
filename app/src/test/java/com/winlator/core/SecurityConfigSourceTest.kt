package com.winlator.core

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source-level checks for settings that cannot run in a unit test (manifest backup rules, the UDP bridge's socket). */
class SecurityConfigSourceTest {
    private val main = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }

    @Test fun nothingIsBackedUpOrTransferred() {
        val manifest = File(main, "AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:allowBackup=\"false\""))
        assertTrue(manifest.contains("android:fullBackupContent=\"false\""))
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
        val rules = File(main, "res/xml/data_extraction_rules.xml").readText()
        for (section in listOf("cloud-backup", "device-transfer")) {
            val body = rules.substringAfter("<$section>").substringBefore("</$section>")
            for (domain in listOf("root", "file", "database", "sharedpref", "external", "device_root", "device_file", "device_database", "device_sharedpref"))
                assertTrue("$section excludes $domain", body.contains("<exclude domain=\"$domain\" path=\".\" />"))
            assertFalse(section, body.contains("<include"))
        }
    }

    @Test fun theWinHandlerUdpBridgeIsOffAndLoopbackOnly() {
        val source = File(main, "java/com/winlator/winhandler/WinHandler.java").readText()
        assertTrue(source.contains("public static volatile boolean udpBridgeEnabled = false;"))
        assertTrue(source.contains("bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), SERVER_PORT))"))
        assertFalse("no wildcard bind", source.contains("new InetSocketAddress((InetAddress) null"))
        assertFalse(source.contains("InetAddress.getLocalHost()"))
    }
}
