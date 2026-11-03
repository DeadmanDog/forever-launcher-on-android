package com.winlator.core

import org.junit.Assert.*
import org.junit.Test

class DXVKConfigTest {
    @Test fun versionOnlyConfigIsEmpty() {
        assertEquals("", DXVKHelper.buildConfig(KeyValueSet("version=custom")))
    }
    @Test fun optionsAreSemicolonSeparatedWithoutShellQuotes() {
        val value = DXVKHelper.buildConfig(KeyValueSet("version=custom,maxDeviceMemory=2048,maxFeatureLevel=11_0,constantBufferRangeCheck=1"))
        assertTrue(value.startsWith("dxgi.maxDeviceMemory = 2048;"))
        assertTrue(value.contains("dxgi.maxSharedMemory = 2048;"))
        assertTrue(value.contains("d3d11.maxFeatureLevel = 11_0;"))
        assertTrue(value.contains("d3d11.constantBufferRangeCheck = \"True\";"))
        assertFalse(value.contains('\n'))
        assertFalse(value.startsWith("\""))
    }
    @Test fun incompleteCustomDeviceDoesNotCrashLaunch() {
        assertEquals("", DXVKHelper.buildConfig(KeyValueSet("customDevice=1234:5678")))
    }
    @Test fun quotedDeviceNamesRemainQuotedValues() {
        val value = DXVKHelper.buildConfig(KeyValueSet("customDevice=1234:5678:Test GPU"))
        assertTrue(value.contains("dxgi.customDeviceDesc = \"Test GPU\";"))
        assertTrue(value.contains("d3d9.customDeviceDesc = \"Test GPU\";"))
    }
}
