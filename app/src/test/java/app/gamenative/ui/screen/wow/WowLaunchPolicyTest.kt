package app.gamenative.ui.screen.wow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import com.winlator.core.envvars.EnvVars
@RunWith(RobolectricTestRunner::class)
class WowLaunchPolicyTest {
 @Test fun multilineInspectorRecognizesSavedOverride(){assertTrue(WowLaunchPolicy.hasSavedTuDebugDisplay("OTHER=x\nTU_DEBUG=noconform,sysmem\nWINENTSYNC=1"));assertFalse(WowLaunchPolicy.hasSavedTuDebugDisplay("OTHER=TU_DEBUG"))}
 @Test fun modesAreExclusiveAndKeepOtherFlags(){
  assertEquals("nolrz,noconform,gmem",WowLaunchPolicy.flags("sysmem,gmem,nolrz",WowLaunchPolicy.Memory.GMEM))
  assertEquals("noconform",WowLaunchPolicy.flags("noconform,sysmem",WowLaunchPolicy.Memory.DEFAULT))
  assertEquals("noconform,sysmem",WowLaunchPolicy.flags("",WowLaunchPolicy.Memory.SYSMEM))
 }
 @Test fun applyNeverChangesAnEnvironmentThatSavedItsOwnTuDebug(){
  val context=androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
  val saved="TU_DEBUG=noconform,gmem OTHER=retained";val launch=EnvVars()
  WowLaunchPolicy.apply(context,launch,saved)
  assertFalse(launch.has("TU_DEBUG"));assertEquals("noconform,gmem",EnvVars(saved).get("TU_DEBUG"))}
 @Test fun applyIsLaunchLocalAndUsesTheSelectedMemoryMode(){
  val context=androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
  val launch=EnvVars();WowLaunchPolicy.apply(context,launch,"OTHER=retained")
  assertEquals("noconform,sysmem",launch.get("TU_DEBUG"))}
}
