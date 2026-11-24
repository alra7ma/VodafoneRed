package com.example.vfwatch

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume

class CheckWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {

    override suspend fun doWork(): Result {
        val o = withTimeoutOrNull(90_000) { withContext(Dispatchers.Main) { scrape() } }
            ?: return Result.retry()
        val total = o.getInt("total")
        val loaded = o.getInt("loaded")
        val arr = o.getJSONArray("numbers")
        val nums = HashSet<String>()
        for (i in 0 until arr.length()) nums.add(arr.getString(i))

        val sp = applicationContext.getSharedPreferences("vf", Context.MODE_PRIVATE)
        val old = sp.getInt("total", -1)
        val oldNums = sp.getStringSet("nums", null)
        if (old != -1 && old != total) {
            var msg = "العداد: $old ← $total"
            if (oldNums != null && loaded == total) {
                val added = nums.filter { it !in oldNums }.take(5)
                if (added.isNotEmpty()) msg += "\nجديد: " + added.joinToString("  ")
            }
            alert(msg)
        }
        val e = sp.edit().putInt("total", total).putInt("loaded", loaded)
            .putLong("time", System.currentTimeMillis())
        if (loaded == total) e.putStringSet("nums", nums)
        e.apply()
        return Result.success()
    }

    // يفتح الصفحة في WebView خفي ويقرأ العداد بعد اكتمال رسمها، ويتأكد أن القيمة ثابتة مرتين متتاليتين
    private suspend fun scrape(): JSONObject? = suspendCancellableCoroutine<JSONObject?> { c ->
        val wv = WebView(applicationContext)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        val h = Handler(Looper.getMainLooper())
        var last = ""
        var same = 0
        var tries = 0
        fun finish(o: JSONObject?) {
            h.removeCallbacksAndMessages(null)
            wv.destroy()
            if (c.isActive) c.resume(o)
        }
        val poll = object : Runnable {
            override fun run() {
                val self = this
                wv.evaluateJavascript(JS) { raw ->
                    val s = (raw?.takeIf { it != "null" }?.let { JSONTokener(it).nextValue() } as? String) ?: ""
                    tries++
                    same = if (s.isNotEmpty() && s == last) same + 1 else 0
                    last = s
                    when {
                        same >= 2 -> finish(JSONObject(s))
                        tries >= 40 -> finish(null)
                        else -> h.postDelayed(self, 1500)
                    }
                }
            }
        }
        c.invokeOnCancellation { h.post { h.removeCallbacksAndMessages(null); wv.destroy() } }
        wv.loadUrl(PAGE)
        h.postDelayed(poll, 3000)
    }

    private fun alert(text: String) {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(NotificationChannel("vf", "Vodafone", NotificationManager.IMPORTANCE_HIGH))
        val n = NotificationCompat.Builder(applicationContext, "vf")
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle("تغيّر عدد أرقام فودافون")
            .setContentText(text.substringBefore('\n'))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        nm.notify(1, n)
    }

    companion object {
        const val PAGE = "https://eshop.vodafone.com.eg/en/lines/red/numbers?channelId=REDPlanAR&c_id=DACS0335&c_medium=PPC&c_name=REDPlanAR&c_source=Search&c_term=REDPlanAR&c_type=Anonymous"
        // العداد: <span class="lines-numbers"> 1779 / 1779 </span> ، والأرقام: .msisdn-item p
        const val JS = """(function(){var e=document.querySelector('.lines-numbers');if(!e)return '';var m=e.textContent.match(/(\d+)\s*\/\s*(\d+)/);if(!m)return '';var n=[].map.call(document.querySelectorAll('.msisdn-item p'),function(p){return p.textContent.trim()});return JSON.stringify({loaded:+m[1],total:+m[2],numbers:n})})()"""
    }
}
