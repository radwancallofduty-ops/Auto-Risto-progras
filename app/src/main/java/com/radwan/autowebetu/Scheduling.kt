package com.radwan.autowebetu

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.*
import java.util.concurrent.TimeUnit

object Notifier {
    fun show(c: Context, title: String, text: String) {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("booking", "الحجز", NotificationManager.IMPORTANCE_HIGH))
        val n = NotificationCompat.Builder(c, "booking").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true).build()
        try { nm.notify((System.currentTimeMillis() % 100000).toInt(), n) } catch (_: SecurityException) {}
    }
}

object Scheduler {
    val start = mapOf("lunch" to LocalTime.of(12, 0), "dinner" to LocalTime.of(19, 0))
    val defaultAt = mapOf("lunch" to "05:30", "dinner" to "12:00")
    fun label(m: String) = if (m == "lunch") "الغداء" else "العشاء"

    fun triggerTime(c: Context, meal: String): LocalTime =
        try { LocalTime.parse(Prefs.str(c, "${meal}_at", defaultAt[meal]!!)) } catch (e: Exception) { LocalTime.parse(defaultAt[meal]) }

    /** أقرب موعد حجز قادم (الوجبة نفسها في اليوم نفسه) */
    fun next(c: Context): Pair<LocalDateTime, String>? {
        val now = LocalDateTime.now(); val days = Prefs.days(c)
        return (0..7).flatMap { d ->
            val date = LocalDate.now().plusDays(d.toLong())
            listOf("lunch", "dinner").filter { Prefs.bool(c, "${it}_on", true) && date.dayOfWeek.value in days }
                .map { date.atTime(triggerTime(c, it)) to it }
        }.filter { it.first.isAfter(now) }.minByOrNull { it.first }
    }

    fun cancel(c: Context) = WorkManager.getInstance(c).cancelAllWorkByTag("auto")

    fun scheduleNext(c: Context) {
        cancel(c)
        if (!Prefs.bool(c, "auto", false)) return
        val (at, meal) = next(c) ?: return
        enqueue(c, meal, at.toLocalDate(), Duration.between(LocalDateTime.now(), at).toMillis(), false)
    }

    fun enqueue(c: Context, meal: String, date: LocalDate, delayMs: Long, manual: Boolean) {
        val req = OneTimeWorkRequestBuilder<BookingWorker>()
            .setInitialDelay(maxOf(0, delayMs), TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .setInputData(workDataOf("meal" to meal, "date" to date.toString(), "manual" to manual))
            .apply { if (!manual) addTag("auto") }.build()
        WorkManager.getInstance(c).enqueueUniqueWork(
            (if (manual) "manual_" else "auto_") + meal + "_" + date, ExistingWorkPolicy.REPLACE, req)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) { Scheduler.scheduleNext(c) }
}

class BookingWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val meal = inputData.getString("meal")!!
        val date = LocalDate.parse(inputData.getString("date")!!)
        val label = Scheduler.label(meal); val ds = date.toString()
        fun done(ok: Boolean, msg: String): Result {
            History.add(ctx, ds, meal, ok, msg)
            Notifier.show(ctx, if (ok) "✅ تم حجز $label" else "❌ فشل حجز $label", "$ds — $msg")
            Scheduler.scheduleNext(ctx); return Result.success()
        }
        if (History.booked(ctx, ds, meal)) { Scheduler.scheduleNext(ctx); return@withContext Result.success() }
        val deadline = date.atTime(Scheduler.start[meal]!!).minusHours(6)
        if (LocalDateTime.now().isAfter(deadline)) return@withContext done(false, "انتهت مهلة الحجز المسبق (6 ساعات قبل الوجبة)")
        try {
            val client = WebEtuClient(ctx)
            if (client.isReserved(date, meal)) return@withContext done(true, "الحجز موجود مسبقاً في حجوزاتي")
            client.book(meal, Prefs.str(ctx, "restaurant_id", ""), date)
            if (client.isReserved(date, meal)) done(true, "تم الحجز وتأكد ظهوره في حجوزاتي")
            else done(false, "لم يظهر الحجز في قائمة حجوزاتي")
        } catch (e: WebEtuClient.AuthException) {
            Notifier.show(ctx, "🔐 يلزم تسجيل الدخول مجدداً", e.message ?: "")
            done(false, "خطأ مصادقة: ${e.message}")
        } catch (e: IOException) {
            if (runAttemptCount < 4) Result.retry() else done(false, "خطأ شبكة: ${e.message}")
        }
    }
}
