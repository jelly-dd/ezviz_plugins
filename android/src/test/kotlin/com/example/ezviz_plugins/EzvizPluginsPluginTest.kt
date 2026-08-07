package com.example.ezviz_plugins

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.Test

internal class EzvizPluginsPluginTest {
    @Test
    fun probeErrorsAreMappedToFiveBusinessStates() {
        assertEquals("add", EzvizPluginsPlugin.classifyProbeStatus(null))
        assertEquals("add", EzvizPluginsPlugin.classifyProbeStatus(120021))
        assertEquals("connectNetwork", EzvizPluginsPlugin.classifyProbeStatus(120023))
        assertEquals("connectNetwork", EzvizPluginsPlugin.classifyProbeStatus(120002))
        assertEquals("connectNetwork", EzvizPluginsPlugin.classifyProbeStatus(120029))
        assertEquals("alreadyAdded", EzvizPluginsPlugin.classifyProbeStatus(120020))
        assertEquals("addedByOtherAccount", EzvizPluginsPlugin.classifyProbeStatus(120022))
        assertEquals("addedByOtherAccount", EzvizPluginsPlugin.classifyProbeStatus(120024))
        assertEquals("retry", EzvizPluginsPlugin.classifyProbeStatus(120006))
    }

    @Test
    fun provisioningMethodUsesConfiguredPriority() {
        assertEquals("ap", EzvizPluginsPlugin.selectProvisioningMethod(2, 3, 1))
        assertEquals("smartAndSoundWave", EzvizPluginsPlugin.selectProvisioningMethod(0, 3, 1))
        assertEquals("smart", EzvizPluginsPlugin.selectProvisioningMethod(0, 3, 0))
        assertEquals("soundWave", EzvizPluginsPlugin.selectProvisioningMethod(0, 0, 1))
        assertNull(EzvizPluginsPlugin.selectProvisioningMethod(0, 0, 0))
    }
}
