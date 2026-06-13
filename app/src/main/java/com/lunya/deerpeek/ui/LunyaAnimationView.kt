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

    /**
     * Инициализация и жесткая привязка оверлея к подсистеме окон Android.
     */
    fun attachOverlay() {
        if (containerView != null) {
            Log.w("OverlayRenderer", "Обнаружена активная сессия оверлея. Сброс вызова.")
            return
        }

        // Контейнер: горизонтальная компоновка элементов
        containerView = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 24, 24, 24)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E60A0A0A")) // 90% непрозрачности, глубокий серый
                cornerRadius = 32f                     // Скругление матрицы геометрии
                setStroke(3, Color.parseColor("#8000FF00")) // Тонкий неоново-зеленый контур (волосы Луни)
            }
        }

        // Интеграция кастомного слоя анимации персонажа
        deerView = LunyaAnimationView(context).apply {
            // Системный дефолтный ассет до момента генерации первого ИИ-стикера
            setImageResource(android.R.drawable.sym_def_app_icon) 
            layoutParams = LinearLayout.LayoutParams(220, 220).apply {
                setMargins(0, 0, 24, 0)
            }
        }

        // Вывод текстовой матрицы ИИ
        responseTv = TextView(context).apply {
            setTextColor(Color.parseColor("#00FF00")) // Терминальный зеленый спектр
            textSize = 13f
            maxWidth = 650
            text = "СИСТЕМА СИНХРОНИЗИРОВАНА. ОЖИДАНИЕ ТРАНЗАКЦИЙ..."
        }

        containerView?.addView(deerView)
        containerView?.addView(responseTv)

        // Изоляция типов окон в зависимости от версии API
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
            Log.d("OverlayRenderer", "Матрица оверлея успешно внедрена в WindowManager.")
        } catch (e: Exception) {
            Log.e("OverlayRenderer", "Критический отказ подсистемы окон: ${e.message}")
        }
    }

    /**
     * Потокобезопасное обновление текстового массива на дисплее.
     */
    fun updateText(text: String) {
        responseTv?.text = text
    }

    /**
     * Накатывание сгенерированного через Imagen 3 стикера-реакции на холст.
     */
    fun applyNewSticker(bitmap: Bitmap) {
        try {
            deerView?.setImageBitmap(bitmap)
            Log.d("OverlayRenderer", "Новый визуальный ассет (стикер) успешно применен.")
        } catch (e: Exception) {
            Log.e("OverlayRenderer", "Сбой отрисовки битмапа: ${e.message}")
        }
    }

    /**
     * Маршрутизация состояния вычислительного ядра в анимационный слой (смена частоты и цвета неона).
     */
    fun setVisualState(isThinking: Boolean, isError: Boolean) {
        deerView?.setSystemState(isThinking, isError)
        
        // Дополнительно подсвечиваем рамку самого контейнера при ошибке
        val containerBorderColor = when {
            isError -> Color.parseColor("#FF0000") // Красный отказ
            isThinking -> Color.parseColor("#80FF00FF") // Фиолетовая пульсация расчета
            else -> Color.parseColor("#8000FF00") // Штатный режим
        }
        
        (containerView?.background as? GradientDrawable)?.setStroke(3, containerBorderColor)
    }

    /**
     * Деструктуризация и освобождение ресурсов. Предотвращает утечки памяти (Memory Leaks).
     */
    fun detachOverlay() {
        try {
            deerView?.release() // Принудительная остановка ValueAnimator-ов
            containerView?.let {
                windowManager.removeView(it)
                Log.d("OverlayRenderer", "Оверлей успешно демонтирован из памяти WindowManager.")
            }
        } catch (e: Exception) {
            Log.e("OverlayRenderer", "Ошибка при деструктуризации оверлея: ${e.message}")
        } finally {
            containerView = null
            responseTv = null
            deerView = null
        }
    }
}
