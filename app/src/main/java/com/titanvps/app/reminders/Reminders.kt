package com.titanvps.app.reminders

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.titanvps.app.MainActivity
import com.titanvps.app.R
import com.titanvps.app.TitanApp
import com.titanvps.app.data.Fmt
import com.titanvps.app.data.Subscription
import com.titanvps.app.vpn.KeepAlive
import java.util.Calendar
import java.util.TimeZone

/**
 * Напоминания (as on iOS): the subscription ends in 3 days and tomorrow (at noon), and less
 * than 10 % of the bypass traffic is left. Inexact alarms (no exact-alarm permission), set
 * again on every app start, subscription update, boot and app update; on by default, a
 * switch in Настройки. A tap opens Подписка.
 */
object Reminders {
    const val KIND_EXPIRY3 = "expiry3"
    const val KIND_EXPIRY1 = "expiry1"
    internal const val EXTRA_KIND = "kind"
    internal const val EXTRA_EXPIRE = "expire"

    private const val CHANNEL_ID = "reminders"
    private const val EXPIRY_ID = 10
    private const val TRAFFIC_ID = 11
    private const val CODE_EXPIRY3 = 20
    private const val CODE_EXPIRY1 = 21
    private const val CODE_OPEN = 30
    /** The traffic total the "running out" note was shown for (once per paid amount). */
    private const val KEY_WARNED = "traffic_warned_total"
    private const val TRAFFIC_DELAY_MS = 2_000L

    // Its own object: unit tests load Reminders without Android.
    private object Main {
        val handler = Handler(Looper.getMainLooper())
    }
    private val trafficPost = Any()

    /** Reminders for [sub] (null: signed out, nothing more comes). Main thread. */
    fun update(context: Context, sub: Subscription?) {
        val app = context.applicationContext
        val prefs = KeepAlive.prefs(app)
        cancelAlarms(app)
        if (sub == null || !TitanApp.get(app).settings.reminders.value) {
            Main.handler.removeCallbacksAndMessages(trafficPost)
            if (sub == null) prefs.edit().remove(KEY_WARNED).apply()
            return
        }
        val info = sub.info
        val alarms = app.getSystemService(AlarmManager::class.java)
        if (alarms != null) {
            for ((kind, at) in expiryTimes(info.expireAt, System.currentTimeMillis(), TimeZone.getDefault())) {
                runCatching { alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarm(app, kind, info.expireAt)) }
            }
        }
        // Bypass traffic: once per paid amount, when less than 10 % of it is left.
        if (!trafficLow(info.totalBytes, info.usedBytes)) {
            Main.handler.removeCallbacksAndMessages(trafficPost)
            prefs.edit().remove(KEY_WARNED).apply()
            return
        }
        // Shown (or due in a moment) for this amount already: a second update in a row leaves
        // it be. Not marked while notifications are off: it comes once they are allowed.
        if (prefs.getLong(KEY_WARNED, -1L) == info.totalBytes || !canNotify(app)) return
        prefs.edit().putLong(KEY_WARNED, info.totalBytes).apply()
        val remaining = (info.totalBytes - info.usedBytes).coerceAtLeast(0L)
        val total = info.totalBytes
        Main.handler.postAtTime({ notifyTraffic(app, remaining, total) }, trafficPost, SystemClock.uptimeMillis() + TRAFFIC_DELAY_MS)
    }

    /** Alarms are lost on reboot, force-stop and update: set again for the saved subscription. */
    fun reschedule(context: Context) = update(context, TitanApp.get(context).repository.subscription.value)

    /** The switch turned off: nothing pending comes. */
    fun cancelAll(context: Context) {
        cancelAlarms(context.applicationContext)
        Main.handler.removeCallbacksAndMessages(trafficPost)
    }

    private fun cancelAlarms(app: Context) {
        val alarms = app.getSystemService(AlarmManager::class.java)
        for (code in listOf(CODE_EXPIRY3, CODE_EXPIRY1)) {
            val pending = PendingIntent.getBroadcast(
                app, code, Intent(app, ReminderReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
            ) ?: continue
            alarms?.cancel(pending)
            pending.cancel()
        }
    }

    /**
     * When to remind of the end at [expireAtSec]: 3 days and 1 day before, at 12:00 local
     * time in [zone]; only those still ahead of [nowMillis] and before the end.
     */
    fun expiryTimes(expireAtSec: Long, nowMillis: Long, zone: TimeZone): List<Pair<String, Long>> {
        if (expireAtSec <= 0) return emptyList()
        val end = expireAtSec * 1000
        return listOf(KIND_EXPIRY3 to 3, KIND_EXPIRY1 to 1).mapNotNull { (kind, days) ->
            val at = Calendar.getInstance(zone).apply {
                timeInMillis = end
                add(Calendar.DAY_OF_MONTH, -days)
                set(Calendar.HOUR_OF_DAY, 12)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            if (at > nowMillis && at < end) kind to at else null
        }
    }

    /** Less than 10 % of a limited traffic left (0 total = unlimited). */
    fun trafficLow(total: Long, used: Long): Boolean = total > 0 && (total - used) * 10 < total

    /** From [ReminderReceiver]: the subscription ends at [expireAtSec]. */
    internal fun notifyExpiry(context: Context, expireAtSec: Long) {
        post(
            context, EXPIRY_ID, "Подписка Titan VPS",
            "Подписка закончится " + Fmt.dayTime(expireAtSec * 1000) +
                ". Если ещё не продлили — продлите в личном кабинете, чтобы VPN не отключился.",
        )
    }

    private fun notifyTraffic(context: Context, remaining: Long, total: Long) {
        if (!TitanApp.get(context).settings.reminders.value) return
        post(
            context, TRAFFIC_ID, "Трафик на обходах заканчивается",
            "Осталось " + Fmt.bytes(remaining) + " из " + Fmt.bytes(total) + ". Докупить ГБ можно в личном кабинете.",
        )
    }

    private fun canNotify(context: Context) = NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun post(context: Context, id: Int, title: String, text: String) {
        if (!canNotify(context)) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.reminders_channel), NotificationManager.IMPORTANCE_HIGH)
        )
        val open = PendingIntent.getActivity(
            context, CODE_OPEN,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_SUBSCRIPTION, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        runCatching {
            nm.notify(
                id,
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_shield)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setCategory(NotificationCompat.CATEGORY_REMINDER)
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build(),
            )
        }
    }

    private fun alarm(context: Context, kind: String, expireAtSec: Long): PendingIntent = PendingIntent.getBroadcast(
        context, if (kind == KIND_EXPIRY3) CODE_EXPIRY3 else CODE_EXPIRY1,
        Intent(context, ReminderReceiver::class.java).putExtra(EXTRA_KIND, kind).putExtra(EXTRA_EXPIRE, expireAtSec),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
