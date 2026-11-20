package com.radwan.autowebetu

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

/**
 * ⚠️ المسارات أدناه هي النقطة الوحيدة التي تحتاج إلى مطابقة مع منصة WebEtu/PROGRES الفعلية.
 * مسار تسجيل الدخول مبني على واجهة PROGRES المعروفة، أما مسارات الحجز فهي افتراضية
 * ويجب تأكيدها (راجع README) قبل الاعتماد عليها.
 */
object Endpoints {
    const val BASE = "https://progres.mesrs.dz"
    const val LOGIN = "/api/authentication/v1/"          // POST {username,password} -> {token}
    const val BOOK = "/api/infos/restauration/reserver"    // غير مؤكد — POST
    const val RESERVATIONS = "/api/infos/restauration/mesReservations" // غير مؤكد — GET
    const val AUTH_PREFIX = ""                             // مثال: "Bearer " إن لزم
}

class WebEtuClient(private val ctx: Context) {
    class AuthException(msg: String) : Exception(msg)

    private fun call(method: String, path: String, body: JSONObject?, token: String?): Pair<Int, String> {
        val con = URL(Endpoints.BASE + path).openConnection() as HttpURLConnection
        try {
            con.requestMethod = method; con.connectTimeout = 15000; con.readTimeout = 20000
            con.setRequestProperty("Accept", "application/json")
            token?.let { con.setRequestProperty("Authorization", Endpoints.AUTH_PREFIX + it) }
            if (body != null) {
                con.doOutput = true; con.setRequestProperty("Content-Type", "application/json")
                con.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = con.responseCode
            val stream = if (code in 200..299) con.inputStream else con.errorStream
            return code to (stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally { con.disconnect() }
    }

    fun login(): String {
        val u = Creds.user(ctx); val p = Creds.pass(ctx)
        if (u == null || p == null) throw AuthException("لم تُحفظ بيانات الدخول")
        val (code, txt) = call("POST", Endpoints.LOGIN, JSONObject().put("username", u).put("password", p), null)
        if (code == 400 || code == 401 || code == 403) throw AuthException("بيانات الدخول مرفوضة")
        if (code !in 200..299) throw IOException("HTTP $code")
        val t = JSONObject(txt).optString("token")
        if (t.isEmpty()) throw AuthException("لم يُرجع الخادم رمز دخول (قد توجد حماية CAPTCHA)")
        Creds.saveToken(ctx, t); return t
    }

    private fun authed(method: String, path: String, body: JSONObject?): Pair<Int, String> {
        var r = call(method, path, body, Creds.token(ctx))
        if (r.first == 401 || r.first == 403 || Creds.token(ctx) == null) r = call(method, path, body, login())
        if (r.first == 401 || r.first == 403) throw AuthException("انتهت الجلسة")
        return r
    }

    fun book(meal: String, restaurantId: String, date: LocalDate) {
        val (code, txt) = authed("POST", Endpoints.BOOK,
            JSONObject().put("restaurantId", restaurantId).put("repas", meal).put("date", date.toString()))
        if (code !in 200..299) throw IOException("فشل الحجز HTTP $code: ${txt.take(120)}")
    }

    /** يتحقق من ظهور الحجز فعلاً في قائمة حجوزاتي */
    fun isReserved(date: LocalDate, meal: String): Boolean {
        val (code, txt) = authed("GET", Endpoints.RESERVATIONS, null)
        if (code !in 200..299) throw IOException("HTTP $code")
        val arr = try { JSONArray(txt) } catch (e: Exception) { JSONArray().put(JSONObject(txt)) }
        return (0 until arr.length()).any {
            val s = arr.get(it).toString().lowercase()
            s.contains(date.toString()) && s.contains(meal.lowercase())
        }
    }
}
