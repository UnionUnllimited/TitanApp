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
