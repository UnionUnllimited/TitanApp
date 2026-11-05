package com.titanvps.desktop

/**
 * Why a subscription that did answer can't be used, in words for the user: the panel's
 * own announcement, a stub entry it sends instead of servers (device limit, blocked), or
 * an empty answer. Same rules in the Android app.
 */
object SubscriptionErrors {
    private val STUBS = listOf("не поддерживается", "заблокирован", "лимит устройств", "превышен лимит", "истекл", "неактивн")

    fun unusable(info: SubscriptionInfo, body: String): String = when {
        !info.announce.isNullOrBlank() -> info.announce.trim()
        body.isBlank() -> "Сервер подписки не прислал ни одного сервера. Возможно, превышен лимит устройств или подписка неактивна — напишите в поддержку"
        else -> "Не удалось разобрать подписку. Напишите в поддержку"
    }

    /**
     * The servers to show from one answer. Null: nothing usable with this User-Agent, the next
     * one is tried ([last]: none is left, the reason is thrown instead). The panel's stub entries
     * (device limit, expired…) come with a dummy address, so they are read before such info rows
     * are hidden (as on Android and iPhone), or their text would never be shown.
     */
    fun servers(info: SubscriptionInfo, body: String, last: Boolean): List<Server>? {
        val all = runCatching { XrayConfigs.serversFromXrayJson(body) }.getOrDefault(emptyList())
        if (all.isEmpty()) {
            if (!last) return null
            throw SubscriptionException(unusable(info, body))
        }
        // The panel answers with a stub entry instead of servers (device limit, blocked…).
        stub(all.map { it.name })?.let { throw SubscriptionException(it) }
        val servers = all.filterNot(ServerGroups::isHidden)
        if (servers.isEmpty()) {
            if (!last) return null
            throw SubscriptionException("В подписке нет серверов")
        }
        return servers
    }

    /** The stub's text when every entry is one (no real server came). */
    fun stub(names: List<String>): String? {
        if (names.isEmpty()) return null
        val stubs = names.filter { n -> STUBS.any { it in n.lowercase() } }
        if (stubs.size != names.size) return null
        val text = stubs.first().trim()
        return if ("не поддерживается" in text.lowercase())
            "$text. Чаще всего это значит, что превышен лимит устройств — удалите старое устройство или напишите в поддержку"
        else text
    }
}
