package com.titanvps.app.data

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.TimeUnit

/**
 * The site's limited personal cabinet, read natively: signed in by the link the subscription
 * brings (profile-web-page-url → /auth/magic/<portal token>), the /cabinet page is parsed
 * for the subscription and the tariffs; payments and payment codes go to the same endpoints
 * the page's buttons use (POST /pay/<id> → {ok, redirect}; POST /pay/promo-code).
 * Same code in the Windows app.
 */
/** The session cookie of the cabinet, for this run only. */
private class MemoryCookieJar : CookieJar {
    private val cookies = LinkedHashMap<String, Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookies.forEach { this.cookies[it.domain + it.path + it.name] = it }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        return cookies.values.filter { it.expiresAt > now && it.matches(url) }
    }
}

class CabinetApi private constructor(private val base: HttpUrl, private val client: OkHttpClient) {

    data class Tariff(
        /** "sub" (renewal) or "traffic" (extra GB). */
        val kind: String,
        val id: String,
        val name: String,
        val price: Double,
        val limit: Int?,
        val limitLabel: String,
        val trafficLabel: String,
        val methods: List<String>,
        /** Shown by the page itself (no device-limit choice offered). */
        val shown: Boolean,
    )

    data class Method(val id: String, val title: String)

    data class Data(
        val active: Boolean,
        val status: String,
        val days: String,
        val daysUnit: String,
        val hours: String,
        /** The whole "time left" block as text, when it isn't days + hours. */
        val heroText: String,
        val until: String,
        val traffic: String?,
        val trafficPercent: Float?,
        val trafficNote: String?,
        val clientId: String?,
        /** Device limits to choose from (first purchase); empty: fixed already. */
        val limits: List<Int>,
        val tariffs: List<Tariff>,
        /** Payment methods per tariff kind. */
        val methods: Map<String, List<Method>>,
        val promo: Boolean,
    )

    class CabinetException(message: String) : Exception(message)

    /** Reads the cabinet again (after a payment or a code). */
    fun load(): Data = parse(get(base.resolve("/cabinet")!!))

    /** Starts paying [tariff] with [method]; returns the payment page to open. */
    fun pay(tariff: Tariff, method: String): String {
        val path = if (tariff.kind == "traffic") "/pay/traffic/${tariff.id}" else "/pay/${tariff.id}"
        val json = post(path, "method", method)
        if (json.optBoolean("ok") && json.optString("redirect").isNotEmpty()) return json.getString("redirect")
        throw CabinetException(json.optString("error").ifEmpty { "Не удалось создать платёж" })
    }

    /** Applies a payment code; returns the site's message. */
    fun applyCode(code: String): String {
        val json = post("/pay/promo-code", "code", code)
        if (json.optBoolean("ok")) return json.optString("message").ifEmpty { "Код применён" }
        throw CabinetException(json.optString("error").ifEmpty { "Не удалось применить код" })
    }

    private fun get(url: HttpUrl): Document {
        client.newCall(Request.Builder().url(url).header("Accept", "text/html").build()).execute().use { r ->
            val final = r.request.url
            if (!r.isSuccessful || !final.encodedPath.startsWith("/cabinet")) {
                throw CabinetException("Не удалось войти в кабинет. Обновите подписку и попробуйте ещё раз")
            }
            return Jsoup.parse(r.body.string(), final.toString())
        }
    }

    private fun post(path: String, field: String, value: String): JSONObject {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart(field, value).build()
        val request = Request.Builder().url(base.resolve(path)!!).post(body).header("Accept", "application/json").build()
        client.newCall(request).execute().use { r ->
            val text = r.body.string()
            return runCatching { JSONObject(text) }.getOrElse {
                throw CabinetException(if (r.isSuccessful) "Неожиданный ответ сайта" else "Ошибка сайта (${r.code})")
            }
        }
    }

    companion object {
        /** Signs in with the cabinet link from the subscription and reads the cabinet. */
        fun open(link: String): Pair<CabinetApi, Data> {
            val url = link.toHttpUrlOrNull() ?: throw CabinetException("Нет ссылки на кабинет в подписке")
            val base = url.newBuilder().encodedPath("/").query(null).build()
            val client = OkHttpClient.Builder()
                .cookieJar(MemoryCookieJar())
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .callTimeout(40, TimeUnit.SECONDS)
                .build()
            val api = CabinetApi(base, client)
            return api to parse(api.get(url))
        }

        fun parse(doc: Document): Data {
            val num = doc.selectFirst(".cab-pass-num")
            val days = num?.ownText()?.trim().orEmpty()
            val tariffs = doc.select("button.tariff-pick").mapNotNull { b ->
                val id = b.attr("data-tid").ifEmpty { return@mapNotNull null }
                Tariff(
                    kind = b.attr("data-kind").ifEmpty { "sub" },
                    id = id,
                    name = b.attr("data-name").ifEmpty { b.selectFirst(".tariff-pick-name")?.text().orEmpty() },
                    price = b.attr("data-price").toDoubleOrNull() ?: 0.0,
                    limit = b.attr("data-limit").toIntOrNull(),
                    limitLabel = b.attr("data-limit-label"),
                    trafficLabel = b.attr("data-traffic-label"),
                    methods = b.attr("data-methods").split(',').map { it.trim() }.filter { it.isNotEmpty() },
                    shown = !b.attr("style").replace(" ", "").contains("display:none"),
                )
            }
            val methods = listOf("sub", "traffic").associateWith { kind ->
                doc.select("#payMethodsList-$kind [data-pm]").map { m ->
                    val marks = m.select("img.pay-mark").mapNotNull { img ->
                        when {
                            img.hasClass("pay-mark-sbp") -> "СБП"
                            img.hasClass("pay-mark-mir") -> "МИР"
                            img.hasClass("pay-mark-btc") -> "Крипта"
                            else -> null
                        }
                    }
                    Method(m.attr("data-pm"), marks.joinToString(" · ").ifEmpty { m.text().trim().ifEmpty { m.attr("data-pm") } })
                }
            }
            val limits = doc.selectFirst(".limit-slider[data-limits]")?.attr("data-limits")
                ?.let { Regex("\\d+").findAll(it).map { m -> m.value.toInt() }.toList() }.orEmpty()
            return Data(
                active = doc.selectFirst(".cab-pass.is-live") != null,
                status = doc.selectFirst(".cab-pass-status")?.text()?.trim().orEmpty(),
                days = days,
                daysUnit = num?.selectFirst(".u")?.text()?.trim().orEmpty(),
                hours = num?.selectFirst(".h")?.text()?.trim().orEmpty(),
                heroText = doc.selectFirst(".cab-pass-hero")?.text()?.trim().orEmpty(),
                until = doc.selectFirst(".cab-pass-until")?.text()?.trim().orEmpty(),
                traffic = doc.selectFirst(".cab-pass-stat-pct")?.text()?.trim(),
                trafficPercent = doc.selectFirst(".cab-pass-bar-fill")?.attr("style")
                    ?.let { Regex("width:\\s*([0-9.]+)%").find(it)?.groupValues?.get(1)?.toFloatOrNull() },
                trafficNote = doc.selectFirst(".cab-pass-traffic-note")?.text()?.trim(),
                clientId = doc.selectFirst("#client-id-label")?.text()?.trim(),
                limits = limits,
                tariffs = tariffs,
                methods = methods,
                promo = doc.selectFirst("#promoCodeInput") != null,
            )
        }
    }
}
