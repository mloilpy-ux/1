package com.lunya.deerpeek.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

class OverlayRenderer(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var containerView: LinearLayout? = null
    private var responseTv: TextView? = null
    private var deerView: LunyaAnimationView? = null

    fun attachOverlay() {
        if (containerView != null) return

        containerView = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 24, 24, 24)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E60A0A0A"))
                cornerRadius = 32f
                setStroke(3, Color.parseColor("#8000FF00"))
            }
        }

        deerView = LunyaAnimationView(context).apply {
            setImageResource(android.R.drawable.sym_def_app_icon) 
            layoutParams = LinearLayout.LayoutParams(220, 220).apply {
                setMargins(0, 0, 24, 0)
            }
        }

        responseTv = TextView(context).apply {
            setTextColor(Color.parseColor("#00FF00"))
            textSize = 13f
            maxWidth = 650
            text = "СИСТЕМА СИНХРОНИЗИРОВАНА. ОЖИДАНИЕ ТРАНЗАКЦИЙ..."
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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            x = 45
            y = 120
        }

        try {
            windowManager.addView(containerView, params)
        } catch (e: Exception) {
            Log.e("OverlayRenderer", "WindowManager error: ${e.message}")
        }
    }

    fun updateText(text: String) {
        responseTv?.text = text
    }

    fun applyNewSticker(bitmap: Bitmap) {
        try {
            deerView?.setImageBitmap(bitmap)
        } catch (e: Exception) {
            Log.e("OverlayRenderer", "Bitmap error: ${e.message}")
        }
    }

    fun setVisualState(isThinking: Boolean, isError: Boolean) {
        deerView?.setSystemState(isThinking, isError)
        val containerBorderColor = when {
            isError -> Color.parseColor("#FF0000")
            isThinking -> Color.parseColor("#80FF00FF")
            else -> Color.parseColor("#8000FF00")
        }
        (containerView?.background as? GradientDrawable)?.setStroke(3, containerBorderColor)
    }

    fun detachOverlay() {
        try {
            deerView?.release()
            containerView?.let { windowManager.removeView(it) }
        } catch (e: Exception) {
            Log.e("OverlayRenderer", "Destroy error: ${e.message}")
        } finally {
            containerView = null
            responseTv = null
            deerView = null
        }
    }
}
