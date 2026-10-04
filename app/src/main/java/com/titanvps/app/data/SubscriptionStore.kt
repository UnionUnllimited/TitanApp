package com.titanvps.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Persists the single subscription the app is bound to. */
class SubscriptionStore(context: Context) {

    private val prefs = context.getSharedPreferences("subscription", Context.MODE_PRIVATE)

    var selectedServerId: String?
        get() = prefs.getString(KEY_SELECTED, null)
        set(value) = prefs.edit().putString(KEY_SELECTED, value).apply()

    fun load(): Subscription? {
        val raw = prefs.getString(KEY_DATA, null) ?: return null
        return runCatching { decode(JSONObject(raw)) }.getOrNull()
    }

    fun save(sub: Subscription) {
        prefs.edit().putString(KEY_DATA, encode(sub).toString()).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun encode(s: Subscription) = JSONObject()
        .put("url", s.url)
        .put("fetchedAt", s.fetchedAt)
        .put("info", JSONObject()
            .put("title", s.info.title)
            .put("upload", s.info.uploadBytes)
            .put("download", s.info.downloadBytes)
            .put("total", s.info.totalBytes)
            .put("expire", s.info.expireAt)
            .put("supportUrl", s.info.supportUrl)
            .put("webPageUrl", s.info.webPageUrl)
            .put("announce", s.info.announce)
            .put("updateHours", s.info.updateIntervalHours)
            .put("deviceLimit", s.info.deviceLimit ?: -1)
            .put("devicesUsed", s.info.devicesUsed ?: -1))
        .put("servers", JSONArray().apply {
            s.servers.forEach {
                put(JSONObject().put("id", it.id).put("name", it.name).put("json", it.xrayJson).put("tag", it.proxyTag))
            }
        })

    private fun decode(o: JSONObject): Subscription {
        val i = o.getJSONObject("info")
        val arr = o.getJSONArray("servers")
        return Subscription(
            url = o.getString("url"),
            fetchedAt = o.optLong("fetchedAt"),
            info = SubscriptionInfo(
                title = i.optStringOrNull("title"),
                uploadBytes = i.optLong("upload"),
                downloadBytes = i.optLong("download"),
                totalBytes = i.optLong("total"),
                expireAt = i.optLong("expire"),
                supportUrl = i.optStringOrNull("supportUrl"),
                webPageUrl = i.optStringOrNull("webPageUrl"),
                announce = i.optStringOrNull("announce"),
                updateIntervalHours = i.optInt("updateHours", SubscriptionInfo.DEFAULT_UPDATE_HOURS),
                deviceLimit = i.optInt("deviceLimit", -1).takeIf { it >= 0 },
                devicesUsed = i.optInt("devicesUsed", -1).takeIf { it >= 0 },
            ),
            servers = (0 until arr.length()).map {
                val s = arr.getJSONObject(it)
                Server(s.getString("id"), s.getString("name"), s.getString("json"), s.getString("tag"))
            },
        )
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

    private companion object {
        const val KEY_DATA = "data"
        const val KEY_SELECTED = "selected"
    }
}
