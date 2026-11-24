package com.example.vfwatch

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.work.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private lateinit var tv: TextView
    private val sp by lazy { getSharedPreferences("vf", Context.MODE_PRIVATE) }
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> show() }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Build.VERSION.SDK_INT >= 33)
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        val wm = WorkManager.getInstance(this)
        tv = TextView(this).apply { textSize = 22f; setPadding(0, 0, 0, 48) }
        fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; setOnClickListener { f() } }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 120, 48, 48) }
        box.addView(tv)
        box.addView(btn("افحص الآن") {
            wm.enqueue(OneTimeWorkRequestBuilder<CheckWorker>().build())
            Toast.makeText(this, "جارٍ الفحص… قد يستغرق دقيقة", Toast.LENGTH_SHORT).show()
        })
        box.addView(btn("ابدأ المراقبة (كل 15 دقيقة)") {
            val req = PeriodicWorkRequestBuilder<CheckWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork("vf-watch", ExistingPeriodicWorkPolicy.UPDATE, req)
            Toast.makeText(this, "بدأت المراقبة", Toast.LENGTH_SHORT).show()
        })
        box.addView(btn("أوقف المراقبة") {
            wm.cancelUniqueWork("vf-watch")
            Toast.makeText(this, "أُوقفت المراقبة", Toast.LENGTH_SHORT).show()
        })
        setContentView(box)
    }

    override fun onResume() { super.onResume(); sp.registerOnSharedPreferenceChangeListener(listener); show() }
    override fun onPause() { super.onPause(); sp.unregisterOnSharedPreferenceChangeListener(listener) }

    private fun show() {
        val t = sp.getLong("time", 0)
        tv.text = if (t == 0L) "لم يُفحص بعد" else
            "العداد: ${sp.getInt("loaded", 0)} / ${sp.getInt("total", 0)}\nآخر فحص: " +
                SimpleDateFormat("d/M HH:mm", Locale.getDefault()).format(Date(t))
    }
}
