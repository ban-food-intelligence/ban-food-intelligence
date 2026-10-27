package com.ban.foodintelligence.pos

import com.ban.foodintelligence.pos.net.ApiConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class ApiConfigTest {

    @Test fun addsSchemeAndPort() =
        assertEquals("http://192.168.1.20:8080", ApiConfig.normalize("192.168.1.20"))

    @Test fun keepsExplicitPortAndDropsSlash() =
        assertEquals("http://192.168.1.20:9000", ApiConfig.normalize(" http://192.168.1.20:9000/ "))

    @Test fun recognisesPresets() {
        assertEquals(ApiConfig.Preset.USB, ApiConfig.presetFor("127.0.0.1:8080"))
        assertEquals(ApiConfig.Preset.EMULATOR, ApiConfig.presetFor("http://10.0.2.2:8080"))
        assertEquals(ApiConfig.Preset.LAN, ApiConfig.presetFor("10.18.109.20"))
    }
}
