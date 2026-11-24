package com.xsaturn.browser

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** شاشة البداية: تشغّل فيديو X SATURN ثم تفتح المتصفح. اللمس يتخطّى الفيديو. */
class SplashActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        val video = findViewById<VideoView>(R.id.splashVideo)
        video.setVideoURI(Uri.parse("android.resource://$packageName/${R.raw.splash}"))
        video.setOnCompletionListener { openBrowser() }
        video.setOnErrorListener { _, _, _ ->
            openBrowser()
            true
        }
        findViewById<View>(R.id.splashRoot).setOnClickListener { openBrowser() }
        video.start()

        // شبكة أمان: لا نبقى هنا أكثر من 8 ثوانٍ مهما حدث
        handler.postDelayed({ openBrowser() }, 8000)
    }

    private fun openBrowser() {
        if (finished) return
        finished = true
        handler.removeCallbacksAndMessages(null)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
