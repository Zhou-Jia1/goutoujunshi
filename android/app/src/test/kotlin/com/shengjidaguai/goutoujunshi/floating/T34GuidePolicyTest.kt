package com.shengjidaguai.goutoujunshi.floating

import android.app.Application
import android.content.Intent
import android.provider.Settings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[35])
class T34GuidePolicyTest {
    @Test fun projectionCodesNeverWrapOrCollideWithRegistryAndExhaustionFailsClosed() {
        val codes=ProjectionRequestCodes(64999)
        assertEquals(64999,codes.allocate());assertEquals(65000,codes.allocate())
        assertNull(codes.allocate());assertNull(codes.allocate())
    }
    @Test fun normalizedManufacturersAndSubBrandsUseHonestFallback() {
        mapOf(" Xiaomi " to GuideBrand.XIAOMI,"REDMI" to GuideBrand.XIAOMI,"pOcO" to GuideBrand.XIAOMI,
            "Huawei" to GuideBrand.HUAWEI,"HONOR" to GuideBrand.HONOR,"OPPO" to GuideBrand.OPPO,
            "OnePlus" to GuideBrand.ONEPLUS,"vivo" to GuideBrand.VIVO,"iQOO" to GuideBrand.VIVO,
            "unknown" to GuideBrand.UNKNOWN).forEach { (name,expected) ->
            assertEquals(expected,GuideBrand.identify(name,""));assertEquals(expected,GuideBrand.identify("",name))
        }
        assertEquals(GuideBrand.HONOR,GuideBrand.identify("Huawei","Honor"))
        assertEquals(GuideBrand.ONEPLUS,GuideBrand.identify("OPPO","OnePlus"))
    }
    @Test fun unresolvedCandidateFallsBackToOnlyThisApplication() {
        val launched=mutableListOf<Intent>()
        val settings=GuideSettingsLauncher({it.action==Settings.ACTION_APPLICATION_DETAILS_SETTINGS},{launched+=it},"synthetic.app")
        assertTrue(settings.open(Intent("synthetic.vendor.settings")))
        assertEquals("package:synthetic.app",launched.single().data.toString())
    }
    @Test fun resolveOrLaunchExceptionsFallBackAndDetailsFailureLeavesManualGuide() {
        for(failResolve in listOf(true,false)) {
            val launched=mutableListOf<Intent>()
            val settings=GuideSettingsLauncher({if(failResolve && it.action=="synthetic.vendor") throw SecurityException();true},
                {if(it.action=="synthetic.vendor") throw android.content.ActivityNotFoundException();launched+=it},"synthetic.app")
            assertTrue(settings.open(Intent("synthetic.vendor")))
            assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,launched.single().action)
        }
        assertFalse(GuideSettingsLauncher({true},{throw SecurityException()},"synthetic.app").open())
        assertFalse(GuideSettingsLauncher({false},{fail("Unresolvable intent launched")},"synthetic.app").open())
    }
    @Test fun staleResultsStopLockCancellationAndReplacementCannotAuthorizeAnotherRequest() {
        val gate=PermissionGuideGate();val session=FloatingSession()
        val a=gate.begin(session.request(),4)
        assertTrue(gate.valid(a,a.ticket,4,true,session::valid))
        val b=gate.begin(session.request(),4)
        assertFalse(gate.valid(a,b.ticket,4,true,session::valid))
        assertFalse(gate.valid(b,b.ticket,5,true,session::valid))
        assertFalse(gate.valid(b,b.ticket,4,false,session::valid))
        session.stop();assertFalse(gate.valid(b,b.ticket,4,true,session::valid))
        val c=gate.begin(session.request(),6);gate.cancel()
        assertFalse(gate.valid(c,c.ticket,6,true,session::valid))
        assertFalse(PermissionGuideGate().valid(c,c.ticket,6,true,session::valid))
    }
}
