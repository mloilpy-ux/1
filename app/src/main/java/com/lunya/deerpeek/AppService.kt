package com.lunya.deerpeek

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.*
import android.widget.ImageView
import androidx.core.app.NotificationCompat

class AppService : Service() {
    private var windowManager: WindowManager? = null
    private var deerView: ImageView? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForeground(1, createNotification())
        showDeer()
    }

    private fun createNotification(): Notification {
        val channelId = "deer_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Deer Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Луня на связи")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()
    }

    private fun showDeer() {
        deerView = ImageView(this).apply {
            setImageResource(android.R.drawable.star_big_on) // Замени на свой ресурс
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) 
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY 
            else WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            300, 300,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.BOTTOM or Gravity.START
        
        try {
            windowManager?.addView(deerView, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        deerView?.let { windowManager?.removeView(it) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
