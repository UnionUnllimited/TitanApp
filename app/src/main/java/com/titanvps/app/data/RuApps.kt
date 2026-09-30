package com.titanvps.app.data

/**
 * Russian apps that refuse to work or ask to turn the VPN off (banks, marketplaces,
 * government services). Excluded from the VPN by default so they see no VPN at all.
 * Packages that aren't installed are simply skipped.
 */
object RuApps {
    val PACKAGES = setOf(
        // Banks and payments
        "ru.sberbankmobile",
        "com.idamob.tinkoff.android",
        "ru.vtb24.mobilebanking.android",
        "ru.alfabank.mobile.android",
        "ru.nspk.mirpay",
        // Marketplaces and services
        "com.wildberries.ru",
        "ru.ozon.app.android",
        "ru.beru.android",
        "com.avito.android",
        "ru.instamart",
        "com.octopod.russianpost.client",
        // Government
        "ru.rostel",
        // Yandex
        "ru.yandex.taxi",
        "ru.yandex.yandexmaps",
        "ru.yandex.searchplugin",
        "com.yandex.browser",
        "ru.yandex.music",
        "ru.kinopoisk",
        // VK / messengers / stores
        "com.vkontakte.android",
        "ru.oneme.app",
        "ru.vk.store",
        // Mobile operators
        "ru.mts.mymts",
        "ru.megafon.mlk",
        "ru.beeline.services",
        "ru.tele2.mytele2",
    )
}
