package com.winlator.renderer
import org.junit.Assert.*
import org.junit.Test
class ScanoutEligibilityTest {
    @Test fun limiterWinsOverDisplayRefresh(){assertEquals(60f,ScanoutEligibility.frameRateTarget(60,0,144f),0f);assertEquals(90f,ScanoutEligibility.frameRateTarget(0,90,144f),0f);assertEquals(144f,ScanoutEligibility.frameRateTarget(0,0,144f),0f)}
    private fun eligible(request: Boolean = true, blocked: Boolean = false, effects: Boolean = false,
        hud: Boolean = false, foreground: Boolean = true, count: Int = 1,
        x: Int = 0, y: Int = 0, w: Int = 1344, h: Int = 608, ahb: Boolean = true) =
        ScanoutEligibility.eligible(request,blocked,effects,hud,foreground,count,x,y,w,h,1344,608,ahb)
    @Test fun defaultIsOffAndFullscreenOptInWorks(){assertFalse(eligible(request=false));assertTrue(eligible())}
    @Test fun overlayHudEffectsAndBackgroundFallBack(){
        assertFalse(eligible(blocked=true));assertFalse(eligible(effects=true))
        assertFalse(eligible(hud=true));assertFalse(eligible(foreground=false));assertFalse(eligible(count=2))
    }
    @Test fun resizeOffsetAndNonHardwarePixmapFallBack(){
        assertFalse(eligible(w=1280));assertFalse(eligible(h=720));assertFalse(eligible(x=1))
        assertFalse(eligible(y=1));assertFalse(eligible(ahb=false))
    }

    private fun reason(request: Boolean = true, blocked: Boolean = false, effects: Boolean = false,
        hud: Boolean = false, foreground: Boolean = true, count: Int = 1,
        x: Int = 0, y: Int = 0, w: Int = 1344, h: Int = 608, ahb: Boolean = true) =
        ScanoutEligibility.reason(request,blocked,effects,hud,foreground,count,x,y,w,h,1344,608,ahb)
    @Test fun diagnosticReasonNamesTheFirstBlockingCondition(){
        assertNull(reason())
        assertEquals("off",reason(request=false,blocked=true));assertEquals("ui",reason(blocked=true,hud=true))
        assertEquals("effects",reason(effects=true));assertEquals("hud",reason(hud=true))
        assertEquals("background",reason(foreground=false));assertEquals("windows",reason(count=2))
        assertEquals("geometry",reason(w=1280));assertEquals("geometry",reason(x=4));assertEquals("not-ahb",reason(ahb=false))
    }
    @Test fun reasonAndEligibilityAgree(){
        for (r in listOf(true,false)) for (b in listOf(true,false)) for (h in listOf(true,false)) for (a in listOf(true,false))
            assertEquals(reason(request=r,blocked=b,hud=h,ahb=a)==null,eligible(request=r,blocked=b,hud=h,ahb=a))
    }
    @Test fun statsReportPerSecondDeltasAndState(){
        ScanoutStats.reset();assertEquals("0,0,off",ScanoutStats.take())
        repeat(3){ScanoutStats.scanoutFrame()};ScanoutStats.compositorFrame();ScanoutStats.setState("eligible")
        assertEquals("3,1,eligible",ScanoutStats.take());assertEquals("0,0,eligible",ScanoutStats.take())
        ScanoutStats.setState("ui:quickmenu+controls");assertFalse(ScanoutStats.take().substringAfter(",").substringAfter(",").contains(","))
        ScanoutStats.reset()
    }
}
