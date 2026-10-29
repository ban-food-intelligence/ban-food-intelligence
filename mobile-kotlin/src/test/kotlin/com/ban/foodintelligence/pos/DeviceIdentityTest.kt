package com.ban.foodintelligence.pos

import com.ban.foodintelligence.pos.security.DeviceIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** Each installation gets its own device id, so two phones never share one pinned key. */
class DeviceIdentityTest {

    @Test fun idIsPosPlusTwelveHexDigits() =
        assertEquals("POS-0192C8807B1A", DeviceIdentity.newId(UUID.fromString("0192c880-7b1a-7000-8000-000000000001")))

    @Test fun freshIdsDiffer() = assertNotEquals(DeviceIdentity.newId(), DeviceIdentity.newId())

    // The API accepts device ids up to 64 characters.
    @Test fun idFitsTheApi() = assertTrue(DeviceIdentity.newId().matches(Regex("POS-[0-9A-F]{12}")))
}
