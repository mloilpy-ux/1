package com.lunya.deerpeek

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

class OverlayRenderer(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var containerView: LinearLayout? = null
    private var responseTv: TextView? = null
    private var deerView: ImageView? = null

    fun attachOverlay() {
        if (containerView != null) return

        containerView = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20, 20, 20, 20)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E60A0A0A")) // 90% opacity dark grey
                cornerRadius = 30f
                setStroke(2, Color.parseColor("#8000FF00")) // Neon green border
            }
        }

        deerView = ImageView(context).apply {
            // Замени на R.drawable.lunya_base при наличии ассета
            setImageResource(android.R.drawable.ic_menu_camera) 
            layoutParams = LinearLayout.LayoutParams(150, 150).apply {
                setMargins(0, 0, 20, 0)
            }
        }

        responseTv = TextView(context).apply {
            setTextColor(Color.parseColor("#00FF00")) // Terminal green
            textSize = 14f
            maxWidth = 600
            text = "СИСТЕМА В ОЖИДАНИИ..."
        }

        containerView?.addView(deerView)
        containerView?.addView(responseTv)

        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            x = 40
            y = 100
        }

        windowManager.addView(containerView, params)
    }

    fun updateText(text: String) {
        responseTv?.text = text
    }

    fun updateStateIcon(isError: Boolean) {
        // Логика смены эмоций/состояний
        val iconRes = if (isError) android.R.drawable.ic_delete else android.R.drawable.ic_menu_camera
        deerView?.setImageResource(iconRes)
    }

    fun detachOverlay() {
        containerView?.let { windowManager.removeView(it) }
        containerView = null
    }
}
