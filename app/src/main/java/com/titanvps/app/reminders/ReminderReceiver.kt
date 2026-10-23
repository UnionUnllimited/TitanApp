package com.titanvps.app.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.titanvps.app.TitanApp

/**
 * A subscription reminder is due (alarm from [Reminders]). Shown only if the switch is
 * still on and the subscription still ends then (not renewed in between).
 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = TitanApp.get(context)
        if (!app.settings.reminders.value) return
        val expire = intent.getLongExtra(Reminders.EXTRA_EXPIRE, 0L)
        val sub = app.repository.subscription.value ?: return
        if (expire <= 0 || sub.info.expireAt != expire) return
        Reminders.notifyExpiry(context, expire)
    }
}
