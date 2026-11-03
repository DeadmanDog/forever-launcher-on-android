package app.gamenative.ui.screen.wow

import android.app.Application
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.net.Uri
import com.winlator.container.*
import com.winlator.contents.*
import com.winlator.core.KeyValueSet
import com.winlator.xenvironment.ImageFs
import io.mockk.*
import org.apache.commons.compress.archivers.tar.*
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.*

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class WowDeveloperManagementTest {
    @get:Rule val temp = TemporaryFolder()
    private val files by lazy { temp.newFolder("files") }
    private val context by lazy { object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getFilesDir() = files
        override fun getCacheDir() = File(files, "cache").apply { mkdirs() }
        override fun getApplicationInfo() = ApplicationInfo(super.getApplicationInfo()).apply {
            nativeLibraryDir = File(files, "native").apply { mkdirs() }.path
        }
    } }
    private var previousImage: Any? = null
    @Before fun before() {
        val field = ImageFs::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }
        previousImage = field.get(null); field.set(null, ImageFs.find(File(files, "imagefs")))
        app.gamenative.PluviaApp.keepAlive = false
    }
    @After fun after() {
        unmockkAll()
        ImageFs::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, previousImage)
        app.gamenative.PluviaApp.keepAlive = false
    }

    private fun elf(interpreter: String = "/system/bin/linker64", machine: Int = 183): ByteArray {
        val data = ByteArray(256)
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        b.put(byteArrayOf(127,69,76,70,2,1)); b.putShort(16,3); b.putShort(18,machine.toShort())
        b.putLong(32,64); b.putShort(54,56); b.putShort(56,1)
        b.putInt(64,3); b.putLong(72,128); b.putLong(96,(interpreter.length+1).toLong())
        interpreter.toByteArray().copyInto(data,128)
        return data
    }
    private fun pe(machine: Int = 0xa641): ByteArray = ByteArray(128).also {
        val b = ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN)
        b.putShort(0,0x5a4d); b.putInt(60,64); b.putInt(64,0x4550); b.putShort(68,machine.toShort())
    }
    private fun tar(entries: Map<String,ByteArray>, file: File = temp.newFile("${System.nanoTime()}.wcp"), links: Map<String,String> = emptyMap()): File {
        TarArchiveOutputStream(XZCompressorOutputStream(file.outputStream())).use { t ->
            entries.forEach { (name,data) -> t.putArchiveEntry(TarArchiveEntry(name).apply { size=data.size.toLong() }); t.write(data); t.closeArchiveEntry() }
            links.forEach { (name,target) -> t.putArchiveEntry(TarArchiveEntry(name,TarConstants.LF_SYMLINK).apply { linkName=target }); t.closeArchiveEntry() }
            t.finish()
        }
        return file
    }
    private fun runtimeArchive(machine: Int = 183, interpreter: String = "/system/bin/linker64", type: String = "Proton", peMachine: Int = 0xa641, linkWine: Boolean = false, legacyDrives: Boolean = false, i386: Boolean = true): Uri {
        val pack = tar(mapOf(".wine/drive_c/windows/test" to byteArrayOf(1), ".wine/dosdevices/.keep" to byteArrayOf(),
            ".wine/system.reg" to "new-system".toByteArray(), ".wine/user.reg" to "new-user".toByteArray(), ".wine/userdef.reg" to byteArrayOf()),
            links=if(legacyDrives) mapOf(".wine/dosdevices/d:" to "/storage/emulated/0/Download", ".wine/dosdevices/z:" to "/data/data/old.package/files/imagefs") else emptyMap())
        val profile = JSONObject().apply {
            put("type",type); put("versionName","${type.lowercase()}-12.0-arm64ec"); put("versionCode",1); put("description","Test runtime"); put("files",JSONArray())
            put("proton",JSONObject().put("binPath","bin").put("libPath","lib").put("prefixPack","prefixPack.txz"))
        }
        val entries=mutableMapOf("profile.json" to profile.toString().toByteArray(),
            (if(linkWine) "lib/wine/aarch64-unix/wine" else "bin/wine") to elf(interpreter,machine),
            "bin/wineserver" to elf(), "lib/wine/aarch64-windows/ntdll.dll" to pe(peMachine), "prefixPack.txz" to pack.readBytes())
        if (i386) entries["lib/wine/i386-windows/iexplore.exe"] = pe(0x14c)
        return Uri.fromFile(tar(entries,links=if(linkWine) mapOf("bin/wine" to "../lib/wine/aarch64-unix/wine") else emptyMap()))
    }
    private fun container(id: String = "wow_forever"): Container = Container(id).apply {
        rootDir = File(ImageFs.find(context).rootDir, "home/xuser-$id").apply { mkdirs() }
        loadData(WowContainerProfile.defaultsForNewContainer("/games/wow",false,false))
        wineVersion = "proton-11.0-90624-arm64ec-1" // Existing container migration fixture.
        File(rootDir,".wine/drive_c/windows").mkdirs(); File(rootDir,".wine/system.reg").writeText("old-system")
        saveData()
    }
    private fun driverZip(name: String = "Turnip-Test", binary: ByteArray = elf()): Uri {
        val f = temp.newFile("${System.nanoTime()}.zip")
        ZipOutputStream(f.outputStream()).use { z ->
            mapOf("meta.json" to JSONObject().put("name",name).put("libraryName","libvulkan.so").put("driverVersion","v-test").toString().toByteArray(),
                "libvulkan.so" to binary).forEach { (n,d) -> z.putNextEntry(ZipEntry(n)); z.write(d); z.closeEntry() }
        }
        return Uri.fromFile(f)
    }
    private fun installedDriver(): WowDeveloperComponents.Entry {
        WowDeveloperComponents.importDriver(context,driverZip())
        return WowDeveloperComponents.drivers(context).single()
    }

    @Test fun duplicateTurnipArchiveDestinationIsRejectedWithoutPublishing() {
        val f = temp.newFile("duplicate-driver.zip")
        ZipOutputStream(f.outputStream()).use { zip ->
            for (name in listOf("meta.json", "./meta.json")) {
                zip.putNextEntry(ZipEntry(name)); zip.write("{}".toByteArray()); zip.closeEntry()
            }
        }
        assertThrows(Exception::class.java) { WowDeveloperComponents.importDriver(context, Uri.fromFile(f)) }
        assertTrue(WowDeveloperComponents.drivers(context).isEmpty())
    }

    @Test fun turnipImportListApplyPersistenceAndPlayPreservation() {
        val c = container()
        val before = JSONObject(c.configFile.readText())
        val entry = installedDriver()
        WowDeveloperComponents.applyDriver(c,entry)
        val after = JSONObject(c.configFile.readText())
        before.keys().forEach { key -> if(key !in setOf("graphicsDriverConfig","graphicsDriverVersion")) assertEquals(key,before.get(key).toString(),after.get(key).toString()) }
        assertEquals("Turnip-Test",KeyValueSet(c.graphicsDriverConfig).get("version"))
        assertEquals("1",KeyValueSet(c.graphicsDriverConfig).get("adrenotoolsTurnip"))
        assertEquals("high",KeyValueSet(c.graphicsDriverConfig).get("quality"))
        assertFalse(WowContainerProfile.prepareExistingContainer(c,"/games/wow"))
        assertEquals("Turnip-Test",c.graphicsDriverVersion)
        val saved = ContainerManager(context).getContainerById(c.id)
        val current = WowComponentSelection.driverCurrent(saved.graphicsDriverConfig, saved.graphicsDriverVersion)
        val reopened = WowComponentSelection("", null).refresh(current, WowDeveloperComponents.drivers(context).map { it.id }.toSet())
        assertEquals(entry.id, reopened.current)
        assertEquals(entry.id, reopened.selected)
        assertThrows(IllegalStateException::class.java) { WowDeveloperComponents.delete(context,entry) }
    }
    @Test fun turnipInvalidBinaryAndDuplicateAreRejected() {
        assertThrows(Exception::class.java) { WowDeveloperComponents.importDriver(context,driverZip(binary=elf(machine=62))) }
        assertTrue(WowDeveloperComponents.drivers(context).isEmpty())
        assertThrows(Exception::class.java) { WowDeveloperComponents.importDriver(context,driverZip(name="Other-driver")) }
        val e = installedDriver(); val before = File(e.directory,"meta.json").readText()
        assertThrows(Exception::class.java) { WowDeveloperComponents.importDriver(context,driverZip()) }
        assertEquals(before,File(e.directory,"meta.json").readText())
    }
    @Test fun unusedTurnipCanBeDeletedWithoutReassigningContainers() {
        val c = container(); val before = c.configFile.readText(); val entry = installedDriver()
        WowDeveloperComponents.delete(context,entry)
        assertFalse(entry.directory.exists()); assertEquals(before,c.configFile.readText())
    }
    @Test fun otherContainerAndActiveSessionBlockDeletion() {
        val e = installedDriver(); val c = container("other").apply { graphicsDriverVersion=e.id; saveData() }
        assertTrue(WowDeveloperComponents.inUse(e,listOf(c),false))
        assertThrows(IllegalStateException::class.java) { WowDeveloperComponents.delete(context,e) }
        assertTrue(WowDeveloperComponents.inUse(e,emptyList(),true))
    }
    @Test fun validArm64ecRuntimeImportIsListedAndContainerIsUnchanged() {
        val c = container(); val old = c.configFile.readText()
        assertEquals("proton-12.0-arm64ec-1",WowDeveloperComponents.importRuntime(context,runtimeArchive()))
        assertEquals(1,WowDeveloperComponents.runtimeProfiles(context).size)
        assertEquals(old,c.configFile.readText())
    }
    @Test fun runtimeWithoutThe32BitPayloadIsAccepted() {
        assertEquals("proton-12.0-arm64ec-1",WowDeveloperComponents.importRuntime(context,runtimeArchive(i386=false)))
    }
    @Test fun x86AndGlibcRuntimeImportsAreRejectedWithoutPartialInstall() {
        assertThrows(Exception::class.java) { WowDeveloperComponents.importRuntime(context,runtimeArchive(machine=62)) }
        assertThrows(Exception::class.java) { WowDeveloperComponents.importRuntime(context,runtimeArchive(interpreter="/lib/ld-linux-aarch64.so.1")) }
        assertThrows(Exception::class.java) { WowDeveloperComponents.importRuntime(context,runtimeArchive(peMachine=0x8664)) }
        assertTrue(WowDeveloperComponents.runtimeProfiles(context).isEmpty())
    }
    @Test fun compatibleWineWcpUsesSameCatalogWithoutProtonFallback() {
        val id=WowDeveloperComponents.importRuntime(context,runtimeArchive(type="Wine"))
        assertEquals("wine-12.0-arm64ec-1",id)
        assertEquals(ContentProfile.ContentType.CONTENT_TYPE_WINE,WowDeveloperComponents.runtimeProfiles(context).single().type)
    }
    @Test fun runtimeInternalLoaderSymlinkAndLegacyPrefixDrivesAreHandledSafely() {
        WowDeveloperComponents.importRuntime(context,runtimeArchive(linkWine=true,legacyDrives=true))
        val p=WowDeveloperComponents.runtimeProfiles(context).single()
        val dir=ContentsManager.getInstallDir(context,p)
        assertTrue(Files.isSymbolicLink(File(dir,"bin/wine").toPath()))
        val prefix=temp.newFolder("safe-prefix")
        assertTrue(com.winlator.core.TarCompressorUtils.extractValidated(com.winlator.core.TarCompressorUtils.Type.XZ,
            context,Uri.fromFile(File(dir,"prefixPack.txz")),prefix,true))
        assertFalse(File(prefix,".wine/dosdevices/d:").exists())
        assertEquals("/",Files.readSymbolicLink(File(prefix,".wine/dosdevices/z:").toPath()).toString())
    }
    @Test fun runtimeTransactionRollsBackOnMigrationFailure() {
        val c=container(); val before=c.configFile.readText()
        assertThrows(IllegalStateException::class.java) { RuntimePrefixTransaction.run(c) { candidate ->
            candidate.wineVersion="proton-12.0-arm64ec-1"; File(candidate.rootDir,".wine/system.reg").writeText("changed"); error("migration failed")
        } }
        assertEquals(before,c.configFile.readText()); assertEquals("old-system",File(c.rootDir,".wine/system.reg").readText())
    }
    @Test fun runtimeApplyMigratesBasePrefixAndSurvivesPlayPreparation() {
        val c=container(); val id=WowDeveloperComponents.importRuntime(context,runtimeArchive())
        val base=temp.newFolder("base").apply {
            File(this,"drive_c/windows").mkdirs(); File(this,"dosdevices").mkdirs()
            listOf("system.reg","user.reg","userdef.reg").forEach { File(this,it).writeText("new-$it") }
        }
        File(context.applicationInfo.nativeLibraryDir,"libgnoverlay.so").writeText("overlay")
        File(context.applicationInfo.nativeLibraryDir,"libwowexec.so").writeText("exec-shim")
        mockkStatic(BasePrefix::class); every { BasePrefix.ensure(context,any(),id) } returns base
        mockkConstructor(DeveloperShell::class)
        every { anyConstructed<DeveloperShell>().run(any(),any()) } returns "wine-12.0\nExit: 0"
        WowDeveloperComponents.applyRuntime(context,c,id)
        assertEquals(id,c.wineVersion); assertEquals(base.canonicalPath,c.basePrefix)
        assertEquals("new-system.reg",File(c.rootDir,".wine/system.reg").readText())
        assertFalse(WowContainerProfile.prepareExistingContainer(c,"/games/wow"))
        val saved = ContainerManager(context).getContainerById(c.id)
        val refreshedCatalog = WowDeveloperComponents.runtimeProfiles(context).map { WowDeveloperComponents.runtimeId(it) }.toSet()
        val reopened = WowComponentSelection("", null).refresh(saved.wineVersion, refreshedCatalog)
        assertEquals(id, reopened.current)
        assertEquals(id, reopened.selected)
        assertFalse(reopened.canApply(refreshedCatalog))
        val p=WowDeveloperComponents.runtimeProfiles(context).single()
        val e=WowDeveloperComponents.Entry(WowDeveloperComponents.Kind.RUNTIME,id,"",ContentsManager.getInstallDir(context,p))
        assertThrows(IllegalStateException::class.java) { WowDeveloperComponents.delete(context,e) }
        verify { anyConstructed<DeveloperShell>().run(match { it.command.last()=="--version" && it.directory != c.rootDir },10) }
    }
    @Test fun runtimeLowerDependencyBlocksUnusedVersionDeletion() {
        val dir=temp.newFolder("runtime"); val c=container("other").apply { basePrefix=File(dir,"base_prefix/.wine").path }
        val e=WowDeveloperComponents.Entry(WowDeveloperComponents.Kind.RUNTIME,"proton-99-arm64ec-1","",dir)
        assertTrue(WowDeveloperComponents.inUse(e,listOf(c),false))
    }
    @Test fun interruptedPublishRestoresConfigAndOriginalUpper() {
        val c=container(); val old=c.configFile.readText(); val j=File(c.rootDir,".developer-runtime-transaction").apply { mkdir() }
        File(j,"original-config").writeText(old); File(j,"publishing").writeText("yes")
        File(c.rootDir,".wine").renameTo(File(j,"original-upper"))
        File(c.rootDir,".wine").mkdir(); c.configFile.writeText("broken")
        RuntimePrefixTransaction.recover(c.rootDir)
        assertEquals(old,c.configFile.readText()); assertEquals("old-system",File(c.rootDir,".wine/system.reg").readText())
    }
    @Test fun shellRequestHasContainerContextAndOpeningStartsNoProcess() {
        val c=container(); val s=DeveloperShell(); val r=s.androidRequest(c,"pwd")
        assertEquals(listOf("/system/bin/sh","-c","pwd"),r.command)
        assertEquals(c.rootDir,r.directory); assertEquals(c.rootDir.path,r.environment["HOME"])
        assertFalse(r.command.any { it.contains("WowB") }); s.stop()
    }
    @Test fun shellCapturesOutputAndTimeoutStopsCommand() {
        val shell=DeveloperShell()
        assertTrue(shell.run(DeveloperShell.Request(listOf("/bin/sh","-c","echo stdout; echo stderr >&2"),temp.root,emptyMap())).contains("stderr"))
        assertTrue(shell.run(DeveloperShell.Request(listOf("/bin/sh","-c","exec sleep 5"),temp.root,emptyMap()),1).contains("timed out"))
    }
    @Test fun diagnosticEnvironmentRedactsSecrets() {
        val redacted=DeveloperShell.redactedEnvironment("STEAMHOST_TOKEN=abc CUSTOM_PASSWORD=def SAFE_VALUE=visible")
        assertFalse(redacted.contains("abc")); assertFalse(redacted.contains("def")); assertTrue(redacted.contains("visible"))
    }

    @Test fun unusedRuntimeAndDxvkDeleteRemoveCatalogAndPayload() {
        val id=WowDeveloperComponents.importRuntime(context,runtimeArchive())
        val p=WowDeveloperComponents.runtimeProfiles(context).single()
        val e=WowDeveloperComponents.Entry(WowDeveloperComponents.Kind.RUNTIME,id,"",ContentsManager.getInstallDir(context,p))
        WowDeveloperComponents.delete(context,e)
        assertTrue(WowDeveloperComponents.runtimeProfiles(context).isEmpty()); assertFalse(e.directory.exists())
        val dxvk=ContentProfile().apply { type=ContentProfile.ContentType.CONTENT_TYPE_DXVK; verName="Vegas-Test"; verCode=2 }
        val dir=ContentsManager.getInstallDir(context,dxvk).apply { mkdirs() }
        File(dir,"profile.json").writeText(JSONObject().put("type","DXVK").put("versionName",dxvk.verName).put("versionCode",dxvk.verCode)
            .put("description","").put("files",JSONArray()).toString())
        val entry=WowDeveloperComponents.Entry(WowDeveloperComponents.Kind.DXVK,WowDxvkSelector.identifier(dxvk),"",dir)
        WowDeveloperComponents.delete(context,entry)
        assertFalse(dir.exists()); assertTrue(WowDxvkSelector.installedProfiles(context).isEmpty())
    }
    @Test fun bundledDefaultAndUnreadableContainerPreventDeletion() {
        val e=installedDriver()
        val defaultDir=File(e.directory.parentFile,WowBundledComponents.DRIVER_NAME).apply { mkdirs() }
        val protected=WowDeveloperComponents.Entry(WowDeveloperComponents.Kind.TURNIP,defaultDir.name,"",defaultDir)
        assertThrows(IllegalStateException::class.java) { WowDeveloperComponents.delete(context,protected) }
        val c=container("broken"); c.configFile.writeText("invalid JSON")
        assertThrows(IllegalStateException::class.java) { WowDeveloperComponents.delete(context,e) }
        assertTrue(e.directory.exists())
    }
    @Test fun runtimeApplyFailureRestoresEntireConfigAndPrefix() {
        val c=container(); val before=c.configFile.readText(); val id=WowDeveloperComponents.importRuntime(context,runtimeArchive())
        File(context.applicationInfo.nativeLibraryDir,"libgnoverlay.so").writeText("overlay")
        mockkStatic(BasePrefix::class); every { BasePrefix.ensure(context,any(),id) } returns null
        assertThrows(IllegalStateException::class.java) { WowDeveloperComponents.applyRuntime(context,c,id) }
        assertEquals(before,c.configFile.readText()); assertEquals("old-system",File(c.rootDir,".wine/system.reg").readText())
        assertEquals("proton-11.0-90624-arm64ec-1",c.wineVersion)
    }
    @Test fun wineCmdRequestUsesSelectedRuntimePrefixAndOverlayWithoutGame() {
        val c=container(); val id=WowDeveloperComponents.importRuntime(context,runtimeArchive()); c.wineVersion=id
        val lower=temp.newFolder("shell-lower"); c.basePrefix=lower.path
        File(context.applicationInfo.nativeLibraryDir,"libgnoverlay.so").writeText("overlay")
        File(context.applicationInfo.nativeLibraryDir,"libwowexec.so").writeText("exec-shim")
        val request=DeveloperShell().wineRequest(context,c,"ver")
        assertEquals(listOf("cmd","/d","/c","ver"),request.command.takeLast(4))
        assertTrue(request.command[1].contains("proton-12.0-arm64ec/bin/wine"))
        assertEquals(File(c.rootDir,".wine").path,request.environment["WINEPREFIX"])
        assertEquals(lower.canonicalPath,request.environment[ContainerOverlay.ENV_LOWER])
        assertTrue(request.environment["LD_PRELOAD"]!!.contains("libgnoverlay.so"))
        assertFalse(request.command.any { it.contains("WowB") }); assertEquals("-k",request.cleanupCommand!!.last())
    }
    @Test fun explicitStopTerminatesRunningShellAndRedactsOutputSecrets() {
        val shell=DeveloperShell(); var result=""
        val worker=Thread { result=shell.run(DeveloperShell.Request(listOf("/bin/sh","-c","exec sleep 20"),temp.root,emptyMap())) }.apply { start() }
        Thread.sleep(150); shell.stop(); worker.join(2500)
        assertFalse(worker.isAlive)
        assertTrue(result.contains("Exit:"))
        val output=shell.run(DeveloperShell.Request(listOf("/bin/sh","-c","printf '%s' \"\$CUSTOM_PASSWORD\""),temp.root,mapOf("CUSTOM_PASSWORD" to "sensitive-value")))
        assertFalse(output.contains("sensitive-value")); assertTrue(output.contains("<redacted>"))
    }
    @Test fun symlinkedMigrationDirectoriesAreRefusedWithoutChanges() {
        val c=container(); val before=c.configFile.readText(); val outside=temp.newFolder("outside")
        Files.createSymbolicLink(File(c.rootDir,".wine/dosdevices").toPath(),outside.toPath())
        assertThrows(IllegalStateException::class.java) { RuntimePrefixTransaction.run(c) { error("must not run") } }
        assertEquals(before,c.configFile.readText()); assertTrue(outside.listFiles()!!.isEmpty())
    }
    @Test fun failedRuntimeVersionCheckRollsBackBeforePublishing() {
        val c=container(); val before=c.configFile.readText(); val id=WowDeveloperComponents.importRuntime(context,runtimeArchive())
        val base=temp.newFolder("smoke-base").apply {
            File(this,"drive_c/windows").mkdirs(); File(this,"dosdevices").mkdirs()
            listOf("system.reg","user.reg","userdef.reg").forEach { File(this,it).writeText("new-registry") }
        }
        File(context.applicationInfo.nativeLibraryDir,"libgnoverlay.so").writeText("overlay")
        File(context.applicationInfo.nativeLibraryDir,"libwowexec.so").writeText("exec-shim")
        mockkStatic(BasePrefix::class); every { BasePrefix.ensure(context,any(),id) } returns base
        mockkConstructor(DeveloperShell::class); every { anyConstructed<DeveloperShell>().run(any(),any()) } returns "ELF loader failed\nExit: 127"
        assertThrows(IllegalStateException::class.java) { WowDeveloperComponents.applyRuntime(context,c,id) }
        assertEquals(before,c.configFile.readText()); assertEquals("old-system",File(c.rootDir,".wine/system.reg").readText())
    }
}
