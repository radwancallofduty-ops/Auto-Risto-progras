@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.radwan.autowebetu

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale

val Teal = Color(0xFF0F766E)
const val DEV = "Radwan Khedim"
const val VERSION = "1.0.0"
const val UPDATED = "2026-11-20"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33)
            registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme(colorScheme = lightColorScheme(primary = Teal)) { Root() }
            }
        }
    }
}

@Composable fun Root() {
    var splash by remember { mutableStateOf(true) }
    var tab by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { delay(1800); splash = false }
    if (splash) { Splash(); return }
    val names = listOf("الرئيسية", "السجل", "الإعدادات", "حول التطبيق"); val icons = listOf("🏠", "📋", "⚙️", "ℹ️")
    Scaffold(bottomBar = {
        NavigationBar { names.forEachIndexed { i, n ->
            NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Text(icons[i]) }, label = { Text(n, fontSize = 10.sp) })
        } }
    }) { pad -> Box(Modifier.padding(pad)) { when (tab) { 0 -> Home(); 1 -> HistoryScreen(); 2 -> SettingsScreen(); else -> About() } } }
}

@Composable fun Splash() {
    Box(Modifier.fillMaxSize().background(Teal), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🍽️", fontSize = 64.sp)
            Text("Auto WebEtu", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Text("الحجز الجامعي الذكي", color = Color.White, fontSize = 16.sp)
            Spacer(Modifier.height(32.dp))
            Text("من برمجة $DEV", color = Color.White.copy(alpha = .85f), fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, fontSize = 15.sp)
        }
    }
}

@Composable fun Home() {
    val c = LocalContext.current
    var on by remember { mutableStateOf(Prefs.bool(c, "auto", false)) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { tick++; delay(3000) } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Auto WebEtu", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Teal)
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("الحجز التلقائي", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(if (on) "🟢 مفعّل" else "🔴 متوقف")
                    val n = Scheduler.next(c); tick
                    if (on) Text(if (n != null) "الحجز القادم: ${Scheduler.label(n.second)} — ${n.first.toLocalDate()} ${n.first.toLocalTime()}" else "لا توجد مواعيد قادمة", fontSize = 12.sp)
                }
                Switch(checked = on, onCheckedChange = {
                    if (it && !Creds.has(c)) { Toast.makeText(c, "أدخل بيانات الدخول من الإعدادات أولاً", Toast.LENGTH_LONG).show(); return@Switch }
                    on = it; Prefs.setBool(c, "auto", it); if (it) Scheduler.scheduleNext(c) else Scheduler.cancel(c)
                })
            }
        }
        MealCard("🍽️ الغداء", "lunch", tick)
        MealCard("🌙 العشاء", "dinner", tick)
        Spacer(Modifier.height(16.dp))
        Text("من برمجة $DEV", Modifier.fillMaxWidth(), textAlign = TextAlign.Center, fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, fontSize = 14.sp, color = Teal)
    }
}

@Composable fun MealCard(title: String, meal: String, tick: Int) {
    val c = LocalContext.current
    var on by remember { mutableStateOf(Prefs.bool(c, "${meal}_on", true)) }
    val last = History.all(c).firstOrNull { it.getString("meal") == meal }; tick
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Switch(checked = on, onCheckedChange = { on = it; Prefs.setBool(c, "${meal}_on", it); Scheduler.scheduleNext(c) })
            }
            Text("وقت الحجز: ${Scheduler.triggerTime(c, meal)}", fontSize = 13.sp)
            Text(if (last == null) "لا توجد محاولات بعد" else (if (last.getBoolean("ok")) "✅ " else "❌ ") + last.getString("date") + " — " + last.getString("msg"), fontSize = 12.sp)
            OutlinedButton(onClick = {
                Scheduler.enqueue(c, meal, LocalDate.now(), 0, true)
                Toast.makeText(c, "بدأت محاولة الحجز…", Toast.LENGTH_SHORT).show()
            }) { Text("حجز الآن (اختبار)") }
        }
    }
}

@Composable fun HistoryScreen() {
    val c = LocalContext.current
    var items by remember { mutableStateOf(History.all(c)) }
    LaunchedEffect(Unit) { while (true) { items = History.all(c); delay(3000) } }
    val f = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("سجل الحجوزات", Modifier.weight(1f), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Teal)
            TextButton(onClick = { History.clear(c); items = emptyList() }) { Text("مسح") }
        }
        if (items.isEmpty()) Text("لا يوجد سجل بعد")
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items.forEach {
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                    Text((if (it.getBoolean("ok")) "✅ " else "❌ ") + Scheduler.label(it.getString("meal")) + " — " + it.getString("date"), fontWeight = FontWeight.Bold)
                    Text(it.getString("msg"), fontSize = 13.sp)
                    Text(f.format(Date(it.getLong("ts"))), fontSize = 11.sp)
                } }
            }
        }
    }
}

@Composable fun SettingsScreen() {
    val c = LocalContext.current; val scope = rememberCoroutineScope()
    var user by remember { mutableStateOf(Creds.user(c) ?: "") }
    var pass by remember { mutableStateOf("") }
    var lunch by remember { mutableStateOf(Scheduler.triggerTime(c, "lunch").toString()) }
    var dinner by remember { mutableStateOf(Scheduler.triggerTime(c, "dinner").toString()) }
    var rid by remember { mutableStateOf(Prefs.str(c, "restaurant_id", "")) }
    var rname by remember { mutableStateOf(Prefs.str(c, "restaurant_name", "")) }
    var days by remember { mutableStateOf(Prefs.days(c)) }
    val dn = listOf(7 to "الأحد", 1 to "الإثنين", 2 to "الثلاثاء", 3 to "الأربعاء", 4 to "الخميس", 5 to "الجمعة", 6 to "السبت")
    val timeRe = Regex("([01]\\d|2[0-3]):[0-5]\\d")
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("الإعدادات", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Teal)
        Text("حساب الطالب (يُخزَّن مشفّراً بـ Android Keystore)", fontWeight = FontWeight.Bold)
        OutlinedTextField(user, { user = it }, label = { Text("اسم المستخدم") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(pass, { pass = it }, label = { Text("كلمة المرور") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                if (user.isNotBlank() && pass.isNotBlank()) { Creds.save(c, user.trim(), pass); pass = ""; Toast.makeText(c, "تم الحفظ", Toast.LENGTH_SHORT).show() }
            }) { Text("حفظ الحساب") }
            OutlinedButton(onClick = {
                scope.launch {
                    val msg = withContext(Dispatchers.IO) { try { WebEtuClient(c).login(); "✅ تسجيل الدخول ناجح" } catch (e: Exception) { "❌ ${e.message}" } }
                    Toast.makeText(c, msg, Toast.LENGTH_LONG).show()
                }
            }) { Text("اختبار الدخول") }
        }
        HorizontalDivider()
        Text("أوقات تنفيذ الحجز (HH:mm)", fontWeight = FontWeight.Bold)
        OutlinedTextField(lunch, { lunch = it }, label = { Text("الغداء (الوجبة 12:00 — الحد الأقصى 06:00)") }, singleLine = true, isError = !timeRe.matches(lunch), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(dinner, { dinner = it }, label = { Text("العشاء (الوجبة 19:00 — الحد الأقصى 13:00)") }, singleLine = true, isError = !timeRe.matches(dinner), modifier = Modifier.fillMaxWidth())
        Text("أيام الحجز", fontWeight = FontWeight.Bold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            dn.forEach { (v, n) -> FilterChip(selected = v in days, onClick = { days = if (v in days) days - v else days + v }, label = { Text(n) }) }
        }
        HorizontalDivider()
        Text("المطعم الجامعي المفضل", fontWeight = FontWeight.Bold)
        OutlinedTextField(rname, { rname = it }, label = { Text("اسم المطعم") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(rid, { rid = it }, label = { Text("معرّف المطعم (من المنصة)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            if (!timeRe.matches(lunch) || !timeRe.matches(dinner)) { Toast.makeText(c, "صيغة الوقت غير صحيحة", Toast.LENGTH_SHORT).show(); return@Button }
            Prefs.setStr(c, "lunch_at", lunch); Prefs.setStr(c, "dinner_at", dinner)
            Prefs.setStr(c, "restaurant_id", rid.trim()); Prefs.setStr(c, "restaurant_name", rname.trim()); Prefs.setDays(c, days)
            Scheduler.scheduleNext(c); Toast.makeText(c, "تم حفظ الإعدادات", Toast.LENGTH_SHORT).show()
        }, modifier = Modifier.fillMaxWidth()) { Text("حفظ الإعدادات") }
    }
}

@Composable fun About() {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("🍽️", fontSize = 56.sp)
        Text("Auto WebEtu", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Teal)
        Text("الحجز الجامعي الذكي", fontSize = 16.sp)
        Spacer(Modifier.height(24.dp))
        Text("تم التصميم والبرمجة بواسطة $DEV", fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, fontSize = 17.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Text("الإصدار: $VERSION", fontSize = 14.sp)
        Text("آخر تحديث: $UPDATED", fontSize = 14.sp)
        Spacer(Modifier.height(16.dp))
        Text("لا تُرسل بيانات حسابك إلى أي خادم غير WebEtu الرسمي.", fontSize = 12.sp, textAlign = TextAlign.Center)
    }
}
