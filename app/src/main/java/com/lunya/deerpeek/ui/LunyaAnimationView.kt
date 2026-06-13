package com.lunya.deerpeek.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView

class LunyaAnimationView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : ImageView(context, attrs, defStyleAttr) {

    private var scaleAnimator: ValueAnimator? = null
    private var glowAnimator: ValueAnimator? = null
    private var currentGlowRadius = 10f
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var neonColor = Color.parseColor("#00FF00") // Базовый неоново-зеленый

    init {
        setupAnimators()
    }

    private fun setupAnimators() {
        // Эффект органического дыхания (масштабирование)
        scaleAnimator = ValueAnimator.ofFloat(0.96f, 1.04f).apply {
            duration = 3000
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                val scale = animator.animatedValue as Float
                scaleX = scale
                scaleY = scale
            }
        }

        // Пульсация неоновой ауры
        glowAnimator = ValueAnimator.ofFloat(8f, 24f).apply {
            duration = 1500
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { animator ->
                currentGlowRadius = animator.animatedValue as Float
                invalidate() // Перерисовка кадра
            }
        }

        scaleAnimator?.start()
        glowAnimator?.start()
    }

    fun setSystemState(isThinking: Boolean, isError: Boolean) {
        neonColor = when {
            isError -> Color.RED
            isThinking -> Color.parseColor("#FF00FF") // Фиолетовый (в цвет глаз/когтей) при расчете
            else -> Color.parseColor("#00FF00") // Неоново-зеленый (в цвет волос) в режиме ожидания
        }
        
        // Ускоряем пульсацию, если ИИ думает
        glowAnimator?.duration = if (isThinking) 400L else 1500L
    }

    override fun onDraw(canvas: Canvas) {
        // Отрисовка фонового неонового свечения
        glowPaint.color = neonColor
        glowPaint.style = Paint.Style.STROKE
        glowPaint.strokeWidth = currentGlowRadius / 2
        glowPaint.maskFilter = BlurMaskFilter(currentGlowRadius, BlurMaskFilter.Blur.NORMAL)
        
        setLayerType(LAYER_TYPE_SOFTWARE, glowPaint) // Необходимо для корректного размытия тени
        
        canvas.drawCircle(width / 2f, height / 2f, (width / 2f) - 20f, glowPaint)
        super.onDraw(canvas)
    }

    fun release() {
        scaleAnimator?.cancel()
        glowAnimator?.cancel()
    }
}
