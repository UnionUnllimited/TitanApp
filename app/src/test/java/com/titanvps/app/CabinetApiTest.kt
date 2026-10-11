package com.titanvps.app

import com.titanvps.app.data.CabinetApi
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The site's /cabinet markup (trimmed, made-up data) → what the app shows. */
class CabinetApiTest {
    private val html = """
        <div class="cab-pass is-live">
          <div class="cab-pass-top"><div class="cab-pass-status"><span></span> Активна </div></div>
          <div class="cab-pass-hero">
            <div class="cab-pass-num tabular-nums">274<span class="u">дня</span><span class="h">19 ч</span></div>
            <p class="cab-pass-caption">осталось</p><p class="cab-pass-until">до 13.07.2027</p>
          </div>
          <div class="cab-pass-traffic">
            <span class="cab-pass-stat-pct tabular-nums">12.41 / 1004.0 ГБ · 1%</span>
            <div class="cab-pass-bar"><div class="cab-pass-bar-fill traffic-bar-green" style="width:1%"></div></div>
            <p class="cab-pass-traffic-note">Ограничение трафика не на всех серверах</p>
          </div>
          <span class="cab-id-v" id="client-id-label">1234567</span>
        </div>
        <input type="text" id="promoCodeInput">
        <div class="limit-slider" data-limits='[1, 2, 3]'></div>
        <button type="button" class="tariff-row tariff-pick" data-limit="1" data-kind="sub" data-tid="58"
                data-name="🗓 30 дней | 1 уст." data-price="150.0" data-traffic="3" data-methods="platega"
                data-limit-label="1 устройство" data-traffic-label="3 ГБ"></button>
        <button type="button" class="tariff-row tariff-pick" data-limit="2" data-kind="sub" data-tid="59"
                data-name="🗓 30 дней | 2 уст." data-price="270.0" data-methods="platega" style="display:none"></button>
        <div id="payMethodsList-sub">
          <button type="button" data-pm="platega" class="pay-method-btn hidden">
            <span class="pay-marks"><img class="pay-mark pay-mark-sbp"><img class="pay-mark pay-mark-mir"><img class="pay-mark pay-mark-btc"></span>
            <span>Оплатить</span>
          </button>
        </div>
    """

    @Test
    fun parsesSubscriptionAndTariffs() {
        val d = CabinetApi.parse(Jsoup.parse(html))
        assertTrue(d.active)
        assertEquals("Активна", d.status)
        assertEquals("274", d.days)
        assertEquals("дня", d.daysUnit)
        assertEquals("19 ч", d.hours)
        assertEquals("до 13.07.2027", d.until)
        assertEquals("12.41 / 1004.0 ГБ · 1%", d.traffic)
        assertEquals(1f, d.trafficPercent)
        assertEquals("1234567", d.clientId)
        assertTrue(d.promo)
        assertEquals(listOf(1, 2, 3), d.limits)
        assertEquals(2, d.tariffs.size)
        val t = d.tariffs[0]
        assertEquals("58", t.id); assertEquals(150.0, t.price, 0.0); assertEquals(1, t.limit)
        assertEquals("3 ГБ", t.trafficLabel); assertEquals(listOf("platega"), t.methods); assertTrue(t.shown)
        assertTrue(!d.tariffs[1].shown)
        assertEquals("СБП · МИР · Крипта", d.methods.getValue("sub").single().title)
    }
}
