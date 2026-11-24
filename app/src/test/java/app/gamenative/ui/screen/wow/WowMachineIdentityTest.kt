package app.gamenative.ui.screen.wow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.Random
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35], application = android.app.Application::class)
/** Taken from jaredgei/wow-forever-android 248d8d2: each install gets its own Windows identity (the bundled prefix shared one). */
class WowMachineIdentityTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun generate_makesAPlausibleWindowsIdentity() {
        val identity = WowMachineIdentity.generate(Random(1))

        assertTrue(identity.computerName, Regex("DESKTOP-[A-Z0-9]{7}").matches(identity.computerName))
        assertTrue(identity.machineGuid, Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}").matches(identity.machineGuid))
        assertEquals(4, UUID.fromString(identity.machineGuid).version())
        assertEquals(2, UUID.fromString(identity.machineGuid).variant())
        assertTrue(Regex("[0-9a-f]{8}").matches(identity.volumeSerial))
        assertNotEquals(identity, WowMachineIdentity.generate(Random(2)))
    }

    @Test
    fun applyTo_replacesTheSharedPrefixIdentity() {
        val prefix = tempFolder.newFolder(".wine")
        File(prefix, "drive_c").mkdirs()
        File(prefix, "system.reg").writeText(STOCK_SYSTEM_REG)
        val identity = WowMachineIdentity.generate(Random(1))

        identity.applyTo(prefix)

        // Forever Launcher's WineRegistryEditor has no getters (R8 cleanup), so the values are read from the file.
        val reg = File(prefix, "system.reg").readText()
        fun value(key: String, name: String): String? {
            val section = reg.substringAfter("[$key]", "").substringBefore("\n[")
            return Regex("^\"${Regex.escape(name)}\"=(.*)$", RegexOption.MULTILINE).find(section)?.groupValues?.get(1)
        }
        assertEquals("\"${identity.machineGuid}\"", value("Software\\\\Microsoft\\\\Cryptography", "MachineGuid"))
        assertEquals("dword:%08x".format(identity.installDate), value("Software\\\\Microsoft\\\\Windows NT\\\\CurrentVersion", "InstallDate"))
        assertEquals("dword:%08x".format(identity.installDate), value("Software\\\\Wow6432Node\\\\Microsoft\\\\Windows NT\\\\CurrentVersion", "InstallDate"))
        assertEquals("\"00330-50000-00000-AAOEM\"", value("Software\\\\Microsoft\\\\Windows NT\\\\CurrentVersion", "ProductId"))
        assertEquals("\"${identity.computerName}\"", value("System\\\\ControlSet001\\\\Control\\\\ComputerName\\\\ComputerName", "ComputerName"))
        assertEquals("\"${identity.computerName}\"", value("System\\\\ControlSet001\\\\Services\\\\Tcpip\\\\Parameters", "Hostname"))
        assertEquals("\"${identity.computerName}\"", value("System\\\\ControlSet001\\\\Services\\\\Tcpip\\\\Parameters", "NV Hostname"))
        assertFalse(reg.contains("87447f37-0be9-4ca4-8034-20645c186c64"))
        assertEquals(identity.volumeSerial, File(prefix, "drive_c/.windows-serial").readText().trim())
    }

    @Test
    fun applyTo_leavesAnUpToDatePrefixUntouched() {
        val prefix = tempFolder.newFolder(".wine")
        File(prefix, "drive_c").mkdirs()
        val systemReg = File(prefix, "system.reg").apply { writeText(STOCK_SYSTEM_REG) }
        val identity = WowMachineIdentity.generate(Random(1))
        identity.applyTo(prefix)
        val applied = systemReg.readText()

        identity.applyTo(prefix)

        assertEquals(applied, systemReg.readText())
    }

    private companion object {
        val STOCK_SYSTEM_REG = """
            WINE REGISTRY Version 2
            ;; All keys relative to REGISTRY\\Machine

            [Software\\Microsoft\\Cryptography] 1753725033
            #time=1dbffe81de76732
            "MachineGuid"="87447f37-0be9-4ca4-8034-20645c186c64"

            [Software\\Microsoft\\Windows NT\\CurrentVersion] 1753725046
            "InstallDate"=dword:4be5019a
            "ProductId"="00330-50000-00000-AAOEM"
            "ProductName"="Windows 10 Pro"

            [Software\\Wow6432Node\\Microsoft\\Windows NT\\CurrentVersion] 1753725044
            "InstallDate"=dword:4be5019a
            "ProductId"="00330-50000-00000-AAOEM"

            [System\\ControlSet001\\Control\\ComputerName\\ComputerName] 1753725024
            #time=1dbffe8187431a4
            "ComputerName"="LOCALHOST"

            [System\\ControlSet001\\Services\\Tcpip\\Parameters] 1753725033
            #time=1dbffe81dfb6c82
            "DataBasePath"="C:\\windows\\system32\\drivers\\etc"
            "Domain"=""
            "Hostname"="localhost"
            "NV Hostname"="localhost"

        """.trimIndent()
    }
}
