package com.titanvps.app

import com.titanvps.app.data.AccountApi
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class AccountApiTest {
    @Test
    fun uuidFromSubscriptionUrl() {
        assertEquals(
            "11111111-2222-4333-8444-555555555555",
            AccountApi.uuidOf("https://sub.example.net/sub/11111111-2222-4333-8444-555555555555"),
        )
    }

    @Test
    fun parsesInfo() {
        val info = AccountApi.parse(
            JSONObject(
                """
                {"isFound": true, "userStatus": "ACTIVE",
                 "subscription": {"limitIp": 0},
                 "devices": {"enabled": true, "count": 1, "limit": 3, "items": [
                   {"os": "iOS", "osVersion": "27.0.1", "model": "iPhone 16 Pro",
                    "userAgent": "Happ/5.9.0/ios/2609171647606", "accessTime": "2026-10-08T15:47:55.152194",
                    "createdAt": "2026-07-15 07:08:17"}]}}
                """
            )
        )
        assertEquals("ACTIVE", info.status)
        assertEquals(3, info.deviceLimit)
        assertEquals("iPhone 16 Pro", info.devices.single().model)
        assertEquals("Happ 5.9.0", AccountApi.appName(info.devices.single().app))
        assertEquals("Titan VPS", AccountApi.appName("Titan"))
    }
}
