package com.titanvps.app

import com.titanvps.app.data.AccountApi
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class AccountApiTest {
    @Test
    fun uuidFromSubscriptionUrl() {
        assertEquals(
            "c277430c-bd59-43a7-9cf6-279a6cad4d27",
            AccountApi.uuidOf("https://api1.titanvps.su/sub/c277430c-bd59-43a7-9cf6-279a6cad4d27"),
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
