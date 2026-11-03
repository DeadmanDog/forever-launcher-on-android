package app.gamenative.ui.screen.wow

import android.app.Application
import android.content.ContextWrapper
import app.gamenative.PluviaApp
import com.winlator.container.Container
import com.winlator.container.ContainerManager
import com.winlator.contents.AdrenotoolsManager
import com.winlator.contents.ContentProfile
import com.winlator.contents.ContentsManager
import com.winlator.core.WineInfo
import com.winlator.xenvironment.ImageFs
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class WowBundledComponentsTest {
    @get:Rule val temp = TemporaryFolder()
    private val files by lazy { temp.newFolder("files") }
    private val cache by lazy { temp.newFolder("cache") }
    private val context by lazy {
        object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir() = files
            override fun getCacheDir() = cache
        }
    }

    private var previousImage: Any? = null
    @Before fun idle() {
        PluviaApp.keepAlive = false
        val field = ImageFs::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }
        previousImage = field.get(null)
        field.set(null, ImageFs.find(File(files, "imagefs")))
    }
    @After fun restoreImageFs() {
        ImageFs::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, previousImage)
    }

    @Test fun realBundledAssetsMatchPinnedReleaseChecksums() {
        for (bundle in WowBundledComponents.bundles) {
            val digest = MessageDigest.getInstance("SHA-256")
            context.assets.open("bundled_components/${bundle.asset}").use { stream ->
                val data = ByteArray(64 * 1024)
                while (true) {
                    val count = stream.read(data)
                    if (count < 0) break
                    digest.update(data, 0, count)
                }
            }
            assertEquals(bundle.asset, bundle.sha256,
                digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
        }
    }

    @Test fun onlyEarlierBundledProtonAndVkd3dAreSuperseded() {
        assertTrue(WowBundledComponents.supersededProton("proton-11.0-90639.30-arm64ec-30"))
        assertTrue(WowBundledComponents.supersededProton("proton-11.0-90639.29-arm64ec-29"))
        assertTrue(WowBundledComponents.supersededProton("proton-11.0-90639.28-arm64ec-28"))
        assertTrue(WowBundledComponents.supersededProton("proton-11.0-90639.27-arm64ec-27"))
        assertTrue(WowBundledComponents.supersededProton("proton-11.0-90639.26-arm64ec-26"))
        assertFalse(WowBundledComponents.supersededProton(WowBundledComponents.PROTON_ID))
        assertFalse(WowBundledComponents.supersededProton("proton-10.0-arm64ec-5"))          // imported by the player: kept
        assertTrue(WowBundledComponents.supersededVkd3d("vkd3d-FrontMage-VKD3D-Proton-3.0.1-ARM64-r4-4"))
        assertTrue(WowBundledComponents.supersededVkd3d("vkd3d-FrontMage-VKD3D-Proton-3.0.1-ARM64-r2-2"))
        assertTrue(WowBundledComponents.supersededVkd3d("vkd3d-FrontMage-VKD3D-Proton-3.0.1-ARM64-r5-5"))
        assertTrue(WowBundledComponents.supersededVkd3d("vkd3d-FrontMage-VKD3D-Proton-3.0.1-ARM64-r6-6"))
        assertTrue(WowBundledComponents.supersededVkd3d("vkd3d-FrontMage-VKD3D-Proton-3.0.1-ARM64-r7-7"))
        assertTrue(WowBundledComponents.supersededVkd3d("vkd3d-FrontMage-VKD3D-Proton-3.0.1-ARM64-r8-8"))
        assertTrue(WowBundledComponents.supersededVkd3d("vkd3d-FrontMage-VKD3D-Proton-3.0.1-ARM64-r9-9"))
        // The old package name is replaced even for the same r10; the renamed r10 itself stays.
        assertTrue(WowBundledComponents.supersededVkd3d("vkd3d-FrontMage-VKD3D-Proton-3.0.1-ARM64-r10-10"))
        assertTrue(WowBundledComponents.supersededVkd3d("vkd3d-VKD3D-Proton-3.0.1-ARM64-r9-9"))
        assertFalse(WowBundledComponents.supersededVkd3d("vkd3d-${WowBundledComponents.VKD3D_NAME}-${WowBundledComponents.VKD3D_VERSION_CODE}"))
        assertFalse(WowBundledComponents.supersededVkd3d("vkd3d-Other-VKD3D-2.13-1"))
        assertFalse(WowBundledComponents.supersededVkd3d(""))
    }
    @Test fun everyEarlierBundledTurnRipIsSuperseded() {
        assertTrue(WowBundledComponents.supersededDriver("TurnRIP A840 Performance v1.1.14"))   // the old default
        assertTrue(WowBundledComponents.supersededDriver("TurnRIP A840 Performance v1.1.19"))
        assertTrue(WowBundledComponents.supersededDriver("TurnRIP A840 Performance v1.1.18"))
        assertTrue(WowBundledComponents.supersededDriver("TurnRIP A840 Performance v1.1.17"))
        assertTrue(WowBundledComponents.supersededDriver("TurnRIP A840 Performance v1.1.15"))
        assertFalse(WowBundledComponents.supersededDriver(WowBundledComponents.DRIVER_NAME))
        assertFalse(WowBundledComponents.supersededDriver("Turnip 25.1"))
        assertFalse(WowBundledComponents.supersededDriver(""))
    }
    @Test fun onlyEarlierBundledVegas311IsSuperseded() {
        assertTrue(WowBundledComponents.supersededDxvk("dxvk-Vegas-3.1.1-wow-arm64-hybrid-gamenative-r61-61"))
        assertTrue(WowBundledComponents.supersededDxvk("dxvk-Vegas-3.1.1-wow-arm64-hybrid-gamenative-r60-60"))
        assertFalse(WowBundledComponents.supersededDxvk("dxvk-${WowBundledComponents.DXVK_NAME}-${WowBundledComponents.DXVK_VERSION_CODE}"))
        assertTrue(WowBundledComponents.supersededDxvk("dxvk-Vegas-3.1.1-wow-arm64-hybrid-gamenative-r62-62"))
        assertFalse(WowBundledComponents.supersededDxvk(WowBundledComponents.DXVK_ID))                                // current default is not superseded
        // Retired bundled 2.7.3 builds, including the last r51, move to the current default.
        assertTrue(WowBundledComponents.supersededDefaultDxvk("dxvk-Vegas-2.7.3-wow-arm64-hybrid-gamenative-r51-51"))
        assertFalse(WowBundledComponents.supersededDefaultDxvk("dxvk-Vegas-2.7.3-wow-arm64-hybrid-gamenative-r99-99"))
        assertTrue(WowBundledComponents.supersededDefaultDxvk("dxvk-Vegas-2.7.3-wow-arm64-hybrid-gamenative-r50-50"))
        assertTrue(WowBundledComponents.supersededDefaultDxvk("dxvk-Vegas-2.7.3-wow-arm64-hybrid-gamenative-r43-43"))
        assertFalse(WowBundledComponents.supersededDefaultDxvk(WowBundledComponents.DXVK_ID))
        assertFalse(WowBundledComponents.supersededDefaultDxvk("dxvk-Vegas-3.1.1-wow-arm64-hybrid-gamenative-r62-62"))
        assertFalse(WowBundledComponents.supersededDefaultDxvk("dxvk-2.7.1-1"))
        assertFalse(WowBundledComponents.supersededDxvk("dxvk-Vegas-2.7.3-wow-arm64-hybrid-gamenative-r49-49"))     // 2.7.3 line: kept
        assertFalse(WowBundledComponents.supersededDxvk("dxvk-2.7.1-1"))                                               // imported: kept
        assertFalse(WowBundledComponents.supersededDxvk(""))
    }
    @Test fun bundledAssetsContainOnlyTheCurrentRuntimeDxvkAndDriver() {
        assertEquals(4, WowBundledComponents.bundles.size)
        assertEquals(WowBundledComponents.bundles.map { it.asset }.toSet(),
            context.assets.list("bundled_components")!!.toSet())
        assertEquals(1, WowBundledComponents.bundles.count {
            it.type == ContentProfile.ContentType.CONTENT_TYPE_PROTON
        })
        // One VKD3D (r10, it supersedes r2..r9) and one Proton (r31).
        assertEquals(listOf(WowBundledComponents.VKD3D_NAME),
            WowBundledComponents.bundles.filter { it.type == ContentProfile.ContentType.CONTENT_TYPE_VKD3D }.map { it.name })
        assertTrue(WowBundledComponents.bundles.none { it.experimental })
        // Only the current 3.1.1 DXVK is shipped; the retired 2.7.3 package stays in the local backup.
        assertEquals(listOf(WowBundledComponents.DXVK_NAME),
            WowBundledComponents.bundles.filter { it.type == ContentProfile.ContentType.CONTENT_TYPE_DXVK }.map { it.name })
        // One driver: TurnRIP 1.1.20 (it supersedes the old default 1.1.14 and 1.1.15 .. 1.1.19).
        assertEquals(listOf(WowBundledComponents.DRIVER_NAME),
            WowBundledComponents.bundles.filter { it.type == null }.map { it.name })
    }

    @Test fun realPackagesImportResolveAndRepeatWithoutCreatingOrEditingContainer() {
        val status = mutableListOf<String>()
        WowBundledComponents.ensureInstalled(context, { status.add(it) })
        assertEquals(WowBundledComponents.bundles.size, status.size)
        assertNull(ContainerManager(context).getContainerById(WowContainerProfile.CONTAINER_ID))
        val manager = ContentsManager(context).apply { syncContents() }
        val proton = manager.getProfileByEntryName(WowBundledComponents.PROTON_ID)
        assertNotNull(proton)
        assertEquals(WowBundledComponents.PROTON_VERSION_CODE, proton.verCode)
        val info = WineInfo.fromIdentifier(context, manager, WowBundledComponents.PROTON_ID)
        assertTrue(info.isArm64EC)
        assertEquals("11.0", info.version)
        assertEquals(ContentsManager.getInstallDir(context, proton).canonicalFile, File(info.path).canonicalFile)
        assertEquals(setOf(WowBundledComponents.DXVK_VERSION_CODE),
            WowDxvkSelector.installedProfiles(context).map { it.verCode }.toSet())
        val dxvk = WowDxvkSelector.installedProfiles(context).single { it.verCode == WowBundledComponents.DXVK_VERSION_CODE }
        assertEquals(WowBundledComponents.DXVK_VERSION_CODE, dxvk.verCode)
        assertEquals(WowBundledComponents.DXVK_ID, WowDxvkSelector.identifier(dxvk))
        assertEquals(WowBundledComponents.DXVK_ID,
            WowDxvkSelector.identifier(WowDxvkSelector.currentProfile(
                WowBundledComponents.DXVK_ID, "version=${WowBundledComponents.DXVK_VERSION}", listOf(dxvk))!!))
        for (id in listOf(WowBundledComponents.DRIVER_NAME)) {
            assertEquals(id, AdrenotoolsManager.validateDriverDirectory(File(files, "contents/adrenotools/$id")))
        }
        val container = Container(WowContainerProfile.CONTAINER_ID).apply {
            rootDir = File(ImageFs.find(context).rootDir, "home/${ImageFs.USER}-$id").apply { mkdirs() }
            loadData(WowContainerProfile.defaultsForNewContainer("/games/wow", true, false))
            graphicsDriverVersion = "TurnRIP A840 Performance v1.0.1"
            graphicsDriverConfig = "version=TurnRIP A840 Performance v1.0.1,adrenotoolsTurnip=1,quality=low"
            wineVersion = "proton-11.0-90624-arm64ec-1"
            setDXWrapper("dxvk-2.4.1-wow-aarch64-test-1")
            setDXWrapperConfig("version=2.4.1-wow-aarch64-test-1,async=0")
            saveData()
        }
        val registry = File(container.rootDir, ".wine/user.reg").apply { parentFile!!.mkdirs(); writeText("keep registry") }
        val before = container.configFile.readText()
        val protonDirectory = ContentsManager.getInstallDir(context, proton)
        val prefixHash = hash(File(protonDirectory, proton.winePrefixPack))
        status.clear()
        WowBundledComponents.ensureInstalled(context, { status.add(it) })
        assertTrue(status.isEmpty())
        assertEquals(prefixHash, hash(File(protonDirectory, proton.winePrefixPack)))
        assertEquals(before, container.configFile.readText())
        assertEquals("keep registry", registry.readText())
        val saved = ContainerManager(context).getContainerById(WowContainerProfile.CONTAINER_ID)!!
        assertEquals("TurnRIP A840 Performance v1.0.1", saved.graphicsDriverVersion)
        assertEquals("version=TurnRIP A840 Performance v1.0.1,adrenotoolsTurnip=1,quality=low", saved.graphicsDriverConfig)
        assertEquals("proton-11.0-90624-arm64ec-1", saved.wineVersion)
        assertEquals("dxvk-2.4.1-wow-aarch64-test-1", saved.getDXWrapper())
        assertFalse(WowContainerProfile.prepareExistingContainer(saved, "/games/wow"))
        assertTrue(ContentsManager.getTmpDir(context).listFiles().orEmpty().isEmpty())
        assertTrue(cache.listFiles().orEmpty().none { it.name.startsWith("bundled-") || it.name.startsWith("developer-prefix-") })
    }

    @Test fun r16InstallsWithoutResettingExistingR15DxvkOrGoldenR4Proton() {
        val container = Container(WowContainerProfile.CONTAINER_ID).apply {
            rootDir = File(ImageFs.find(context).rootDir, "home/${ImageFs.USER}-$id").apply { mkdirs() }
            loadData(WowContainerProfile.defaultsForNewContainer("/games/wow", true, false))
            wineVersion = "proton-11.0-90639.4-arm64ec-4"
            setDXWrapper("dxvk-Vegas-2.7.3-wow-arm64-hybrid-gamenative-r15-15")
            setDXWrapperConfig("version=Vegas-2.7.3-wow-arm64-hybrid-gamenative-r15-15")
            saveData()
        }
        val before = container.configFile.readText()
        val registry = File(container.rootDir, ".wine/user.reg").apply {
            parentFile!!.mkdirs()
            writeText("preserve Golden prefix")
        }
        WowBundledComponents.ensureInstalled(context)
        val manager = ContentsManager(context).apply { syncContents() }
        assertNotNull(manager.getProfileByEntryName(WowBundledComponents.PROTON_ID))
        assertEquals(before, container.configFile.readText())
        assertEquals("preserve Golden prefix", registry.readText())
        val saved = ContainerManager(context).getContainerById(WowContainerProfile.CONTAINER_ID)!!
        assertFalse(WowContainerProfile.prepareExistingContainer(saved, "/games/wow"))
        assertEquals("proton-11.0-90639.4-arm64ec-4", saved.wineVersion)
        assertEquals("dxvk-Vegas-2.7.3-wow-arm64-hybrid-gamenative-r15-15", saved.dxWrapper)
        assertEquals("version=Vegas-2.7.3-wow-arm64-hybrid-gamenative-r15-15", saved.dxWrapperConfig)
    }

    @Test fun r12InstallationAndPlayKeepExistingR9RuntimeAndPrefix() {
        val container = Container(WowContainerProfile.CONTAINER_ID).apply {
            rootDir = File(ImageFs.find(context).rootDir, "home/${ImageFs.USER}-$id").apply { mkdirs() }
            loadData(WowContainerProfile.defaultsForNewContainer("/games/wow", true, false))
            wineVersion = "proton-11.0-90639.9-arm64ec-9"
            envVars = "CUSTOM=keep WINENTSYNC=0 PROTON_NO_NTSYNC=1"
            saveData()
        }
        val before = container.configFile.readBytes()
        val registry = File(container.rootDir, ".wine/user.reg").apply {
            parentFile!!.mkdirs(); writeText("keep existing r9 prefix")
        }
        WowBundledComponents.ensureInstalled(context)
        assertNotNull(ContentsManager(context).apply { syncContents() }.getProfileByEntryName(WowBundledComponents.PROTON_ID))
        val saved = ContainerManager(context).getContainerById(container.id)!!
        assertFalse(WowContainerProfile.prepareExistingContainer(saved, "/games/wow"))
        assertArrayEquals(before, saved.configFile.readBytes())
        assertEquals("proton-11.0-90639.9-arm64ec-9", saved.wineVersion)
        assertEquals("keep existing r9 prefix", registry.readText())
    }

    @Test fun newBundlesAndPlayPreserveExistingR5ProtonAndR2DxvkSelection() {
        val container = Container(WowContainerProfile.CONTAINER_ID).apply {
            rootDir = File(ImageFs.find(context).rootDir, "home/${ImageFs.USER}-$id").apply { mkdirs() }
            loadData(WowContainerProfile.defaultsForNewContainer("/games/wow", true, false))
            wineVersion = "proton-11.0-90639.5-arm64ec-5"
            setDXWrapper("dxvk-Vegas-2.7.3-wow-arm64-async-gamenative-2")
            setDXWrapperConfig("version=Vegas-2.7.3-wow-arm64-async-gamenative-2,async=0")
            saveData()
        }
        val before = container.configFile.readText()
        WowBundledComponents.ensureInstalled(context)
        val saved = ContainerManager(context).getContainerById(WowContainerProfile.CONTAINER_ID)!!
        assertFalse(WowContainerProfile.prepareExistingContainer(saved, "/games/wow"))
        assertEquals(before, saved.configFile.readText())
        assertEquals("proton-11.0-90639.5-arm64ec-5", saved.wineVersion)
        assertEquals("dxvk-Vegas-2.7.3-wow-arm64-async-gamenative-2", saved.getDXWrapper())
        assertEquals("version=Vegas-2.7.3-wow-arm64-async-gamenative-2,async=0", saved.getDXWrapperConfig())
    }

    @Test fun newBundlesAndPlayPreserveExistingR6ProtonAndR3DxvkSelection() {
        val container = Container(WowContainerProfile.CONTAINER_ID).apply {
            rootDir = File(ImageFs.find(context).rootDir, "home/${ImageFs.USER}-$id").apply { mkdirs() }
            loadData(WowContainerProfile.defaultsForNewContainer("/games/wow", true, false))
            wineVersion = "proton-11.0-90639.6-arm64ec-6"
            setDXWrapper("dxvk-Vegas-2.7.3-wow-arm64-async-gamenative-r3-3")
            setDXWrapperConfig("version=Vegas-2.7.3-wow-arm64-async-gamenative-r3-3,async=0")
            saveData()
        }
        val before = container.configFile.readText()
        WowBundledComponents.ensureInstalled(context)
        val saved = ContainerManager(context).getContainerById(WowContainerProfile.CONTAINER_ID)!!
        assertFalse(WowContainerProfile.prepareExistingContainer(saved, "/games/wow"))
        assertEquals(before, saved.configFile.readText())
        assertEquals("proton-11.0-90639.6-arm64ec-6", saved.wineVersion)
        assertEquals("dxvk-Vegas-2.7.3-wow-arm64-async-gamenative-r3-3", saved.getDXWrapper())
        assertEquals("version=Vegas-2.7.3-wow-arm64-async-gamenative-r3-3,async=0", saved.getDXWrapperConfig())
    }

    @Test fun newBundlesAndPlayPreserveExistingR6ProtonAndR4DxvkSelection() {
        val container = Container(WowContainerProfile.CONTAINER_ID).apply {
            rootDir = File(ImageFs.find(context).rootDir, "home/${ImageFs.USER}-$id").apply { mkdirs() }
            loadData(WowContainerProfile.defaultsForNewContainer("/games/wow", true, false))
            wineVersion = "proton-11.0-90639.6-arm64ec-6"
            setDXWrapper("dxvk-Vegas-2.7.3-wow-arm64-async-gamenative-r4-4")
            setDXWrapperConfig("version=Vegas-2.7.3-wow-arm64-async-gamenative-r4-4,async=0")
            saveData()
        }
        val before = container.configFile.readText()
        WowBundledComponents.ensureInstalled(context)
        val saved = ContainerManager(context).getContainerById(WowContainerProfile.CONTAINER_ID)!!
        assertFalse(WowContainerProfile.prepareExistingContainer(saved, "/games/wow"))
        assertEquals(before, saved.configFile.readText())
        assertEquals("proton-11.0-90639.6-arm64ec-6", saved.wineVersion)
        assertEquals("dxvk-Vegas-2.7.3-wow-arm64-async-gamenative-r4-4", saved.getDXWrapper())
        assertEquals("version=Vegas-2.7.3-wow-arm64-async-gamenative-r4-4,async=0", saved.getDXWrapperConfig())
    }

    @Test fun activeSessionRejectsInstallationBeforeDiskChanges() {
        assertThrows(IllegalStateException::class.java) {
            WowBundledComponents.ensureInstalled(context, isSessionActive = { true })
        }
        assertFalse(File(files, "imagefs_shared/proton").exists())
        assertTrue(cache.listFiles().orEmpty().isEmpty())
    }

    @Test fun conflictingInstalledProfileIsNotOverwritten() {
        val directory = File(ImageFs.getSharedProtonDir(context), WowBundledComponents.PROTON_NAME).apply { mkdirs() }
        val metadata = JSONObject().put("type", "Proton").put("versionName", WowBundledComponents.PROTON_NAME)
            .put("versionCode", 1).put("description", "preserve existing profile").put("files", JSONArray())
            .put("wine", JSONObject().put("binPath", "bin").put("libPath", "lib").put("prefixPack", "prefixPack.txz"))
        val file = File(directory, "profile.json").apply { writeText(metadata.toString()) }
        val before = file.readText()
        val error = assertThrows(IllegalStateException::class.java) { WowBundledComponents.ensureInstalled(context) }
        assertTrue(error.message!!.contains("not overwritten"))
        assertEquals(before, file.readText())
        assertTrue(cache.listFiles().orEmpty().isEmpty())
    }

    @Test fun incompleteInstalledComponentFromAnInterruptedInstallIsRepaired() {
        val directory = File(ImageFs.getSharedProtonDir(context), WowBundledComponents.PROTON_NAME).apply { mkdirs() }
        val marker = File(directory, "marker").apply { writeText("half written") }
        WowBundledComponents.ensureInstalled(context)
        assertFalse("the leftovers of the interrupted install are removed", marker.exists())
        assertTrue(File(directory, "profile.json").isFile)
    }

    @Test fun newDefaultsAndProtectedProfilesReferToExactlyBundledVersions() {
        val defaults = WowContainerProfile.defaultsForNewContainer("/games/wow", true, false)
        assertEquals(WowBundledComponents.PROTON_ID, defaults.getString("wineVersion"))
        assertEquals(WowBundledComponents.DXVK_ID, defaults.getString("dxwrapper"))
        assertEquals("version=${WowBundledComponents.DXVK_VERSION}", defaults.getString("dxwrapperConfig"))
        assertEquals(WowBundledComponents.DRIVER_NAME, defaults.getString("graphicsDriverVersion"))
        assertTrue(WowBundledComponents.protectedNames.containsAll(
            listOf(WowBundledComponents.PROTON_NAME, WowBundledComponents.DXVK_NAME, WowBundledComponents.DRIVER_NAME)))
    }

    @Test fun onlyCurrentDxvkIsBundledRequiredAndUsedByNewDefaults() {
        val defaults = WowContainerProfile.defaultsForNewContainer("/games/wow", true, false)
        val bundle = WowBundledComponents.bundles.single { it.type == ContentProfile.ContentType.CONTENT_TYPE_DXVK }
        assertEquals(WowBundledComponents.DXVK_NAME, bundle.name)
        assertEquals(WowBundledComponents.DXVK_VERSION_CODE, bundle.versionCode)
        assertFalse(bundle.experimental)
        assertEquals(WowBundledComponents.DXVK_ID, defaults.getString("dxwrapper"))
        assertEquals("version=${WowBundledComponents.DXVK_VERSION}", defaults.getString("dxwrapperConfig"))
        assertFalse(context.assets.list("bundled_components")!!.any { it.contains("2.7.3") })
        assertTrue(WowBundledComponents.protectedNames.contains(bundle.name))
    }

    @Test fun retiredBundledDxvkMovesToCurrentWithoutResettingOtherSettings() {
        WowBundledComponents.ensureInstalled(context)
        val c = Container(WowContainerProfile.CONTAINER_ID).apply {
            rootDir = File(ImageFs.find(context).rootDir, "home/${ImageFs.USER}-$id").apply { mkdirs() }
            loadData(WowContainerProfile.defaultsForNewContainer("/games/wow", true, false))
            setDXWrapper("dxvk-Vegas-2.7.3-wow-arm64-hybrid-gamenative-r51-51")
            setDXWrapperConfig("version=Vegas-2.7.3-wow-arm64-hybrid-gamenative-r51-51,async=0,custom=keep")
            screenSize = "1792x810"
            saveData()
        }
        WowBundledComponents.upgradeSuperseded(context, isSessionActive = { false })
        val saved = ContainerManager(context).getContainerById(c.id)!!
        assertEquals(WowBundledComponents.DXVK_ID, saved.dxWrapper)
        assertEquals(WowBundledComponents.DXVK_VERSION, com.winlator.core.KeyValueSet(saved.dxWrapperConfig).get("version"))
        assertEquals("0", com.winlator.core.KeyValueSet(saved.dxWrapperConfig).get("async"))
        assertEquals("keep", com.winlator.core.KeyValueSet(saved.dxWrapperConfig).get("custom"))
        assertEquals(c.wineVersion, saved.wineVersion)
        assertEquals(c.graphicsDriverConfig, saved.graphicsDriverConfig)
        assertEquals(c.screenSize, saved.screenSize)
    }

    @Test fun onlyProvablyMissingFilesMayBeRepaired() {
        assertTrue(WowBundledComponents.isProvablyIncomplete(WowBundledComponents.BundledIncomplete("x")))
        assertTrue(WowBundledComponents.isProvablyIncomplete(java.io.EOFException()))
        assertTrue(WowBundledComponents.isProvablyIncomplete(IllegalArgumentException("Wine executable missing")))
        assertFalse("a generic state error is not proof", WowBundledComponents.isProvablyIncomplete(IllegalStateException("x")))
        assertFalse("an I/O error is not proof", WowBundledComponents.isProvablyIncomplete(java.io.IOException("EIO")))
        assertFalse("a permission problem is not proof", WowBundledComponents.isProvablyIncomplete(java.io.FileNotFoundException("p (Permission denied)")))
        assertFalse(WowBundledComponents.isProvablyIncomplete(OutOfMemoryError()))
        assertFalse("a conflict is never repaired", WowBundledComponents.isProvablyIncomplete(WowBundledComponents.BundledConflict("x")))
    }

    @Test fun anUnreadableProfileOfAnIntactInstallationIsNeverDeleted() {
        WowBundledComponents.ensureInstalled(context)
        val directory = File(ImageFs.getSharedProtonDir(context), WowBundledComponents.PROTON_NAME)
        val profile = File(directory, "profile.json")
        val marker = File(directory, "keep-me").apply { writeText("intact") }
        assertTrue(profile.isFile)
        profile.setReadable(false)
        try {
            org.junit.Assume.assumeFalse("running as a user that ignores permissions", profile.canRead())
            val error = assertThrows(java.io.IOException::class.java) { WowBundledComponents.ensureInstalled(context) }
            assertNotNull(error)
            assertTrue("the installation is untouched", directory.isDirectory && marker.isFile && profile.isFile)
        } finally { profile.setReadable(true) }
    }

    @Test fun anUnparsableProfileIsRepaired() {
        WowBundledComponents.ensureInstalled(context)
        val directory = File(ImageFs.getSharedProtonDir(context), WowBundledComponents.PROTON_NAME)
        File(directory, "profile.json").writeText("{ this is not json")
        val marker = File(directory, "stale").apply { writeText("x") }
        WowBundledComponents.ensureInstalled(context)
        assertFalse("the damaged folder was replaced", marker.exists())
        assertTrue(File(directory, "profile.json").readText().contains("versionCode"))
    }

    private fun hash(file: File): String = file.inputStream().use { stream ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
