package com.radwan.autowebetu

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("settings", 0)
    fun bool(c: Context, k: String, d: Boolean) = sp(c).getBoolean(k, d)
    fun setBool(c: Context, k: String, v: Boolean) = sp(c).edit().putBoolean(k, v).apply()
    fun str(c: Context, k: String, d: String) = sp(c).getString(k, d) ?: d
    fun setStr(c: Context, k: String, v: String) = sp(c).edit().putString(k, v).apply()
    // أيام الأسبوع: 1=الاثنين ... 7=الأحد (java.time) — الافتراضي: الأحد إلى الخميس
    fun days(c: Context): Set<Int> = str(c, "days", "7,1,2,3,4").split(",").mapNotNull { it.toIntOrNull() }.toSet()
    fun setDays(c: Context, d: Set<Int>) = setStr(c, "days", d.joinToString(","))
}

object Creds {
    private const val ALIAS = "autowebetu_key"
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return g.generateKey()
    }
    private fun enc(s: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(c.iv + c.doFinal(s.toByteArray()), Base64.NO_WRAP)
    }
    private fun dec(s: String): String {
        val b = Base64.decode(s, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b.copyOfRange(0, 12)))
        return String(c.doFinal(b, 12, b.size - 12))
    }
    private fun put(c: Context, k: String, v: String) = Prefs.setStr(c, k, enc(v))
    private fun get(c: Context, k: String): String? = Prefs.str(c, k, "").let { if (it.isEmpty()) null else try { dec(it) } catch (e: Exception) { null } }
    fun save(c: Context, u: String, p: String) { put(c, "cu", u); put(c, "cp", p) }
    fun user(c: Context) = get(c, "cu")
    fun pass(c: Context) = get(c, "cp")
    fun has(c: Context) = user(c) != null && pass(c) != null
    fun saveToken(c: Context, t: String) = put(c, "tok", t)
    fun token(c: Context) = get(c, "tok")
}

object History {
    fun all(c: Context): List<JSONObject> {
        val a = JSONArray(Prefs.str(c, "history", "[]"))
        return (0 until a.length()).map { a.getJSONObject(it) }
    }
    fun add(c: Context, date: String, meal: String, ok: Boolean, msg: String) {
        val list = all(c).toMutableList()
        list.add(0, JSONObject().put("ts", System.currentTimeMillis()).put("date", date).put("meal", meal).put("ok", ok).put("msg", msg))
        val a = JSONArray(); list.take(200).forEach { a.put(it) }
        Prefs.setStr(c, "history", a.toString())
    }
    fun booked(c: Context, date: String, meal: String) =
        all(c).any { it.getString("date") == date && it.getString("meal") == meal && it.getBoolean("ok") }
    fun clear(c: Context) = Prefs.setStr(c, "history", "[]")
}
