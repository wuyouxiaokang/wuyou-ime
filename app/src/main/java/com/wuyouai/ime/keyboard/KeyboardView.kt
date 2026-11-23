package com.wuyouai.ime.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.wuyouai.ime.settings.SettingsManager
import com.wuyouai.ime.themes.KeyboardTheme

/**
 * 键盘主体视图。
 * 自绘实现，支持 QWERTY / 九宫格两种布局、长按、点击反馈、光标移动。
 */
class KeyboardView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Mode { QWERTY_PINYIN, QWERTY_EN, T9_PINYIN, SYMBOLS, NUMBERS, EMOJI }

    var mode: Mode = Mode.QWERTY_PINYIN
        set(v) { field = v; invalidate() }

    var isShift = false
    var isCapsLock = false

    var onKeyPress: ((String) -> Unit)? = null       // 字符输入
    var onSpecialKey: ((SpecialKey) -> Unit)? = null  // 功能键
    var onKeyLongPress: ((String) -> Unit)? = null

    enum class SpecialKey { SHIFT, DELETE, ENTER, SPACE, MODE_SWITCH, LANG_SWITCH, SYMBOL, NUMBER, BACK, EMOJI, CLIPBOARD }

    private var theme = com.wuyouai.ime.themes.ThemeManager.byId("pure_white")
    private val settings = SettingsManager.get(context)

    private val keyCapPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val capEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val keyTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hintTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val iconPath = Path()
    private val capRect = RectF()
    private val shadowRect = RectF()
    private val glyphBounds = Rect()

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glowMatrix = Matrix()
    private val glowBounds = RectF()
    private var glowX = 0f
    private var glowY = 0f
    private var trackingGlow = false
    private var glowReleasedAt = 0L

    // ---- 液态玻璃专用画笔 ----
    /** 背景墙：垂直渐变，让半透明键帽「透」出颜色变化 */
    private val wallPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    /** 键帽玻璃主体：半透明填充 */
    private val glassFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    /** 顶部高光：白色线性渐变，从上到下衰减到透明 */
    private val glassHiPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    /** 键帽描边：极细的亮边，制造玻璃厚度 */
    private val glassEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    /** 内折射：按下时在底部叠加一圈更亮的弧光 */
    private val glassRefractPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var wallTop = 0xFF9FD0FF.toInt()
    private var wallBottom = 0xFFF1D7FF.toInt()

    data class Key(
        val label: String,          // 显示文本
        val output: String,         // 输出字符（小写）
        val code: Int,              // KeyCode 或 -1
        val special: SpecialKey? = null,
        val row: Int,
        val col: Float,             // 同row内的排序位置（仅用于稳定排序）
        val width: Float = 1f,      // 相对宽度（权重）
        val hint: String = "",      // 长按提示（九宫格字母）
        val isT9: Boolean = false,
        val pad: Float = 0f         // 行首缩进（单位为标准键宽）
    ) {
        var rect: RectF = RectF()
        var pressed: Boolean = false
    }

    var keys: List<Key> = emptyList()
    private var longPressHandler: Runnable? = null
    private var repeatDelete: Runnable? = null
    private var currentPressed: Key? = null

    init {
        keyTextPaint.textAlign = Paint.Align.CENTER
        hintTextPaint.textAlign = Paint.Align.RIGHT
        shadowPaint.style = Paint.Style.FILL
    }

    fun setTheme(t: KeyboardTheme) {
        theme = t
        keyTextPaint.color = t.keyText
        capEdgePaint.strokeWidth = dp(0.7f)
        capEdgePaint.color = if (t.isDark) 0x20FFFFFF else 0x1C000000
        iconPaint.strokeWidth = dp(1.65f)
        glowPaint.strokeWidth = dp(1.5f)
        glowPaint.shader = if (t.isDark && !t.liquidGlass) {
            RadialGradient(
                0f, 0f, dp(54f), t.accent, withAlpha(t.accent, 0), Shader.TileMode.CLAMP
            )
        } else {
            trackingGlow = false
            glowReleasedAt = 0L
            null
        }

        // 液态玻璃画笔
        wallTop = if (t.glassWallTop != 0) t.glassWallTop else t.bg
        wallBottom = if (t.glassWallBottom != 0) t.glassWallBottom else t.bg
        glassFillPaint.color = t.keyBg
        glassEdgePaint.style = Paint.Style.STROKE
        glassEdgePaint.strokeWidth = dp(1f) * 0.7f
        glassEdgePaint.color = if (t.isDark) 0x33FFFFFF else 0x4DFFFFFF
        glassRefractPaint.color = if (t.isDark) 0x22FFFFFF else 0x30FFFFFF
        glassHiPaint.color = Color.WHITE

        invalidate()
    }

    /** 密度，用于把 dp 换成分辨率无关的像素 */
    private val density: Float get() = resources.displayMetrics.density

    private fun dp(v: Float): Float = v * density

    private fun shadeColor(base: Int, factor: Float): Int = Color.rgb(
        (Color.red(base) * factor).toInt().coerceAtMost(255),
        (Color.green(base) * factor).toInt().coerceAtMost(255),
        (Color.blue(base) * factor).toInt().coerceAtMost(255)
    )

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        buildKeysIfNeeded()

        val radius = dp((settings.cachedKeyRadius.takeIf { it > 0 } ?: 8).toFloat())
        val shadowDy = dp(1.5f)

        if (theme.liquidGlass) {
            drawLiquidGlass(canvas, radius)
        } else {
            canvas.drawColor(theme.bg)
            drawFlatKeys(canvas, radius, shadowDy)
            drawTouchGlow(canvas, radius)
        }
    }

    /** 常规扁平键帽（非玻璃主题） */
    private fun drawFlatKeys(canvas: Canvas, radius: Float, shadowDy: Float) {
        keys.forEach { key ->
            val isSpecial = key.special != null
            val isEnter = key.special == SpecialKey.ENTER
            val baseColor = when {
                isEnter -> theme.accent
                isSpecial && key.special != SpecialKey.SPACE -> theme.specialKeyBg
                else -> theme.keyBg
            }

            if (!key.pressed) {
                shadowPaint.color = shadeColor(baseColor, if (theme.isDark) 0.55f else 0.86f)
                shadowRect.set(key.rect)
                shadowRect.offset(0f, shadowDy)
                canvas.drawRoundRect(shadowRect, radius, radius, shadowPaint)
            }

            setCapRect(key)
            val bodyColor = when {
                !key.pressed -> baseColor
                theme.isDark && !isEnter -> shadeColor(baseColor, 1.38f)
                else -> shadeColor(baseColor, 0.88f)
            }
            keyCapPaint.color = bodyColor
            canvas.drawRoundRect(capRect, radius, radius, keyCapPaint)
            canvas.drawRoundRect(capRect, radius, radius, capEdgePaint)

            val iconColor = if (!isEnter) theme.keyText
                else if (Color.luminance(bodyColor) > 0.179f) Color.BLACK else Color.WHITE
            if (!isSpecial || !drawFunctionIcon(canvas, key.special, iconColor)) {
                drawKeyLabel(canvas, key, isSpecial, capRect)
            }
        }
    }

    /** 只移动绘制用的矩形，命中区域始终是原来的 key.rect。 */
    private fun setCapRect(key: Key) {
        capRect.set(key.rect)
        if (key.pressed) capRect.offset(0f, dp(1.2f))
    }

    private fun drawFunctionIcon(canvas: Canvas, special: SpecialKey?, color: Int): Boolean {
        val x = capRect.centerX()
        val y = capRect.centerY()
        val s = minOf(dp(10f), capRect.width() * 0.34f, capRect.height() * 0.29f)
        iconPaint.color = color
        iconPath.reset()
        when (special) {
            SpecialKey.SHIFT -> {
                iconPath.moveTo(x, y - s * 0.9f)
                iconPath.lineTo(x - s * 0.85f, y)
                iconPath.lineTo(x - s * 0.38f, y)
                iconPath.lineTo(x - s * 0.38f, y + s * 0.68f)
                iconPath.lineTo(x + s * 0.38f, y + s * 0.68f)
                iconPath.lineTo(x + s * 0.38f, y)
                iconPath.lineTo(x + s * 0.85f, y)
                iconPath.close()
                if (isShift || isCapsLock) iconPaint.style = Paint.Style.FILL_AND_STROKE
            }
            SpecialKey.DELETE -> {
                iconPath.moveTo(x - s, y)
                iconPath.lineTo(x - s * 0.42f, y - s * 0.62f)
                iconPath.lineTo(x + s * 0.9f, y - s * 0.62f)
                iconPath.lineTo(x + s * 0.9f, y + s * 0.62f)
                iconPath.lineTo(x - s * 0.42f, y + s * 0.62f)
                iconPath.close()
                iconPath.moveTo(x, y - s * 0.25f)
                iconPath.lineTo(x + s * 0.5f, y + s * 0.25f)
                iconPath.moveTo(x + s * 0.5f, y - s * 0.25f)
                iconPath.lineTo(x, y + s * 0.25f)
            }
            SpecialKey.ENTER -> {
                iconPath.moveTo(x + s * 0.8f, y - s * 0.58f)
                iconPath.lineTo(x + s * 0.8f, y + s * 0.12f)
                iconPath.lineTo(x - s * 0.72f, y + s * 0.12f)
                iconPath.moveTo(x - s * 0.26f, y - s * 0.32f)
                iconPath.lineTo(x - s * 0.72f, y + s * 0.12f)
                iconPath.lineTo(x - s * 0.26f, y + s * 0.56f)
            }
            SpecialKey.SPACE -> {
                iconPath.moveTo(x - s, y - s * 0.18f)
                iconPath.lineTo(x - s, y + s * 0.22f)
                iconPath.lineTo(x + s, y + s * 0.22f)
                iconPath.lineTo(x + s, y - s * 0.18f)
            }
            else -> return false
        }
        canvas.drawPath(iconPath, iconPaint)
        iconPaint.style = Paint.Style.STROKE
        return true
    }

    /** 光晕只描亮指尖附近的键帽轮廓，不覆盖字或图标。 */
    private fun drawTouchGlow(canvas: Canvas, radius: Float) {
        val shader = glowPaint.shader ?: return
        if (!trackingGlow && glowReleasedAt == 0L) return
        val elapsed = if (trackingGlow) 0L else SystemClock.uptimeMillis() - glowReleasedAt
        if (elapsed >= 210L) return

        glowPaint.alpha = (255 * (210L - elapsed) / 210L).toInt()
        glowMatrix.setTranslate(glowX, glowY)
        shader.setLocalMatrix(glowMatrix)
        val reach = dp(54f)
        glowBounds.set(glowX - reach, glowY - reach, glowX + reach, glowY + reach)
        keys.forEach { key ->
            setCapRect(key)
            if (RectF.intersects(capRect, glowBounds)) {
                canvas.drawRoundRect(capRect, radius, radius, glowPaint)
            }
        }
        if (!trackingGlow) postInvalidateOnAnimation()
    }

    private fun moveTouchGlow(x: Float, y: Float) {
        if (!trackingGlow) return
        glowX = x
        glowY = y
        invalidate()
    }

    private fun fadeTouchGlow() {
        if (!trackingGlow) return
        trackingGlow = false
        glowReleasedAt = SystemClock.uptimeMillis()
    }

    /**
     * 液态玻璃键帽。
     * 层次（由后到前）：
     *   ① 背景渐变墙——让半透明玻璃「透」出颜色
     *   ② 玻璃主体：半透明填充 + 圆角
     *   ③ 顶部高光：白色渐变向下衰减（模拟光从上方打在弧面上）
     *   ④ 亮边描边：1px 高亮轮廓，制造厚度与切边感
     *   ⑤ 按下时：底部叠一层更亮的折射弧光 + 整体染强调色
     */
    private fun drawLiquidGlass(canvas: Canvas, radius: Float) {
        // ① 背景墙：渐变随尺寸缓存，尺寸没变就不重建
        if (wallShader == null || wallW != width || wallH != height) {
            wallShader = android.graphics.LinearGradient(
                0f, 0f, width.toFloat(), height.toFloat(),
                wallTop, wallBottom,
                android.graphics.Shader.TileMode.CLAMP
            )
            wallW = width
            wallH = height
            wallPaint.shader = wallShader
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), wallPaint)

        val inset = dp(1f)
        val pressInset = dp(1.2f)
        val hiRatio = 0.52f
        val whiteTop = if (theme.isDark) 0x40FFFFFF else 0xB8FFFFFF

        keys.forEach { key ->
            val isSpecial = key.special != null

            // 复用同一个 RectF，避免每帧给每个键 new 对象
            tmpRect.set(key.rect)
            tmpRect.inset(inset, inset)

            if (key.pressed) {
                tmpPress.set(tmpRect)
                tmpPress.inset(pressInset, pressInset)
                glassFillPaint.color = withAlpha(theme.accent, 0x66)
                canvas.drawRoundRect(tmpPress, radius, radius, glassFillPaint)
            } else {
                // ② 玻璃主体
                glassFillPaint.color = if (isSpecial) {
                    withAlpha(Color.WHITE, if (theme.isDark) 0x24 else 0x5A)
                } else {
                    withAlpha(Color.WHITE, if (theme.isDark) 0x38 else 0x99)
                }
                canvas.drawRoundRect(tmpRect, radius, radius, glassFillPaint)

                // ③ 顶部高光：占键帽上 52%，向下渐隐到透明
                val hiH = tmpRect.top + tmpRect.height() * hiRatio
                // 渐变与键的高光区尺寸相关；同尺寸（同一行）可安全复用，
                // 尺寸或主题变化时必须重建，否则会把上一行的渐变套到这一行。
                if (hiShader == null ||
                    hiTop != tmpRect.top ||
                    hiBottom != hiH ||
                    hiDark != theme.isDark
                ) {
                    hiShader = android.graphics.LinearGradient(
                        0f, tmpRect.top, 0f, hiH,
                        whiteTop, 0x00FFFFFF,
                        android.graphics.Shader.TileMode.CLAMP
                    )
                    hiTop = tmpRect.top
                    hiBottom = hiH
                    hiDark = theme.isDark
                }
                glassHiPaint.shader = hiShader
                tmpHi.set(tmpRect.left, tmpRect.top, tmpRect.right, hiH)
                canvas.drawRoundRect(tmpHi, radius, radius, glassHiPaint)

                // ④ 亮边描边
                glassEdgePaint.strokeWidth = dp(0.9f)
                canvas.drawRoundRect(tmpRect, radius, radius, glassEdgePaint)

                // ⑤ 底部折射弧光
                tmpBtm.set(
                    tmpRect.left + dp(2f),
                    tmpRect.bottom - dp(2.6f),
                    tmpRect.right - dp(2f),
                    tmpRect.bottom - dp(0.8f)
                )
                if (tmpBtm.height() > 0f) {
                    canvas.drawRoundRect(tmpBtm, radius * 0.6f, radius * 0.6f, glassRefractPaint)
                }
            }
        }

        keys.forEach { key ->
            drawKeyLabel(canvas, key, key.special != null)
        }
    }

    // ---- 液态玻璃绘制缓存：复用对象，避免每帧为每个键分配 ----
    private var wallShader: android.graphics.LinearGradient? = null
    private var wallW = -1
    private var wallH = -1
    private var hiShader: android.graphics.LinearGradient? = null
    private var hiTop = Float.NaN
    private var hiBottom = Float.NaN
    private var hiDark = false
    private val tmpRect = RectF()
    private val tmpPress = RectF()
    private val tmpHi = RectF()
    private val tmpBtm = RectF()

    private fun withAlpha(color: Int, alpha: Int): Int =
        (alpha shl 24) or (color and 0x00FFFFFF)

    /** 绘制单个按键的文字与提示 */
    private fun drawKeyLabel(canvas: Canvas, key: Key, isSpecial: Boolean, rect: RectF = key.rect) {
        val keyH = rect.height()
        val labelText = when {
            (mode == Mode.QWERTY_EN) && (isShift || isCapsLock) &&
                key.output.length == 1 && key.output[0] in 'a'..'z' -> key.output.uppercase()
            else -> key.label
        }
        // 单字符字母键用较大的字号；功能键文本较长则缩小，防止撑出键帽
        val labelSize = if (isSpecial) {
            minOf(keyH * 0.30f, rect.width() * 0.30f).coerceAtLeast(dp(11f))
        } else {
            keyH * 0.42f
        }
        val textPaintFor = if (isSpecial) hintTextPaint.apply {
            textAlign = Paint.Align.CENTER
            color = theme.keyText
        } else keyTextPaint
        textPaintFor.textSize = labelSize
        if (labelText == "，" || labelText == "。") {
            // 全角标点的字形偏在字符格左下角，按笔画边界居中而非按字符宽度居中。
            textPaintFor.textAlign = Paint.Align.LEFT
            textPaintFor.getTextBounds(labelText, 0, labelText.length, glyphBounds)
            canvas.drawText(
                labelText,
                rect.centerX() - glyphBounds.exactCenterX(),
                rect.centerY() - glyphBounds.exactCenterY(),
                textPaintFor
            )
            textPaintFor.textAlign = Paint.Align.CENTER
        } else {
            val fm = textPaintFor.fontMetrics
            val baseline = rect.centerY() - (fm.ascent + fm.descent) / 2f
            canvas.drawText(labelText, rect.centerX(), baseline, textPaintFor)
        }

        // 九宫格字母提示（右上角，小字）
        if (key.hint.isNotEmpty()) {
            hintTextPaint.apply {
                textAlign = Paint.Align.RIGHT
                color = theme.accent
                textSize = dp(9f)
            }
            canvas.drawText(
                key.hint,
                rect.right - dp(6f),
                rect.top + dp(13f),
                hintTextPaint
            )
        }
    }


    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutKeys()
    }

    private fun buildKeysIfNeeded() {
        if (keys.isEmpty()) {
            keys = buildLayout()
            layoutKeys()
        } else if (modeChanged) {
            keys = buildLayout()
            layoutKeys()
            lastMode = mode
        }
    }

    private var lastMode: Mode? = null
    private val modeChanged: Boolean get() = lastMode != mode

    private fun layoutKeys() {
        lastMode = mode
        if (keys.isEmpty()) return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 键间距与行间距按密度换算，避免高密度屏上挤成一片
        val gapX = dp(3f)
        val gapY = dp(4f)
        val edge = dp(4f)

        val rows = keys.groupBy { it.row }
        val rowCount = rows.keys.maxOrNull()?.plus(1) ?: return
        val rowHeight = h / rowCount

        rows.forEach { (rowIdx, rowKeys) ->
            val totalWeight = rowKeys.sumOf { it.width.toDouble() }.toFloat()
            if (totalWeight <= 0f) return@forEach
            // 行首缩进（QWERTY 的 a 行 / z 行需要半键缩进，才像真实键盘）
            val padUnits = rowKeys.firstOrNull()?.pad ?: 0f
            val count = rowKeys.size
            // 可用宽度扣掉行内所有间隙与行首缩进
            val avail = w - edge * 2 - gapX * (count - 1)
            val unit = avail / totalWeight
            val padPx = unit * padUnits
            val rowAvail = avail - padPx
            val unitW = if (totalWeight > 0f) rowAvail / totalWeight else 0f
            var x = edge + padPx
            val y = rowIdx * rowHeight + gapY / 2f
            rowKeys.sortedBy { it.col }.forEach { key ->
                val kw = unitW * key.width
                key.rect = RectF(
                    x,
                    y,
                    x + kw,
                    (rowIdx + 1) * rowHeight - gapY
                )
                x += kw + gapX
            }
        }
    }

    /** 根据模式构建按键布局 */
    private fun buildLayout(): List<Key> = when (mode) {
        Mode.QWERTY_PINYIN, Mode.QWERTY_EN -> buildQwerty()
        Mode.T9_PINYIN -> buildT9()
        Mode.SYMBOLS -> buildSymbols()
        Mode.NUMBERS -> buildNumbers()
        Mode.EMOJI -> emptyList() // emoji 用专门面板
    }

    private fun buildQwerty(): List<Key> = listOf(
        // 行 0：10 键，顶格
        Key("q","q",-1,row=0,col=0f), Key("w","w",-1,row=0,col=1f), Key("e","e",-1,row=0,col=2f),
        Key("r","r",-1,row=0,col=3f), Key("t","t",-1,row=0,col=4f), Key("y","y",-1,row=0,col=5f),
        Key("u","u",-1,row=0,col=6f), Key("i","i",-1,row=0,col=7f), Key("o","o",-1,row=0,col=8f),
        Key("p","p",-1,row=0,col=9f),
        // 行 1：9 键，缩进半键（真实 QWERTY 的错位感）
        Key("a","a",-1,row=1,col=0f,pad=0.5f), Key("s","s",-1,row=1,col=1f),
        Key("d","d",-1,row=1,col=2f), Key("f","f",-1,row=1,col=3f),
        Key("g","g",-1,row=1,col=4f), Key("h","h",-1,row=1,col=5f),
        Key("j","j",-1,row=1,col=6f), Key("k","k",-1,row=1,col=7f),
        Key("l","l",-1,row=1,col=8f),
        // 行 2：Shift + 7 键 + 退格
        Key("⇧","",-1,special=SpecialKey.SHIFT,row=2,col=0f,width=1.5f),
        Key("z","z",-1,row=2,col=1f), Key("x","x",-1,row=2,col=2f),
        Key("c","c",-1,row=2,col=3f), Key("v","v",-1,row=2,col=4f),
        Key("b","b",-1,row=2,col=5f), Key("n","n",-1,row=2,col=6f),
        Key("m","m",-1,row=2,col=7f),
        Key("⌫","",-1,special=SpecialKey.DELETE,row=2,col=8f,width=1.5f),
        // 行 3：功能行
        Key("123","",-1,special=SpecialKey.NUMBER,row=3,col=0f),
        Key(if (mode == Mode.QWERTY_EN) "中" else "英","",-1,
            special=SpecialKey.LANG_SWITCH,row=3,col=1f),
        Key("，","，",-1,row=3,col=2f),
        Key("空格"," ",-1,special=SpecialKey.SPACE,row=3,col=3f,width=4.5f),
        Key("。","。",-1,row=3,col=4f),
        Key("⏎","",-1,special=SpecialKey.ENTER,row=3,col=5f,width=1.5f)
    )

    private fun buildT9(): List<Key> = listOf(
        Key("1","1",-1,row=0,col=0f,hint=""), Key("2","2",-1,row=0,col=1f,hint="abc"),
        Key("3","3",-1,row=0,col=2f,hint="def"),
        Key("4","4",-1,row=1,col=0f,hint="ghi"), Key("5","5",-1,row=1,col=1f,hint="jkl"),
        Key("6","6",-1,row=1,col=2f,hint="mno"),
        Key("7","7",-1,row=2,col=0f,hint="pqrs"), Key("8","8",-1,row=2,col=1f,hint="tuv"),
        Key("9","9",-1,row=2,col=2f,hint="wxyz"),
        Key("⌫","",-1,special=SpecialKey.DELETE,row=2,col=3f),
        Key("英/中","",-1,special=SpecialKey.LANG_SWITCH,row=3,col=0f),
        Key("0","0",-1,special=null,row=3,col=1f),
        Key("空格"," ",-1,special=SpecialKey.SPACE,row=3,col=2f,width=2f),
        Key("⏎","",-1,special=SpecialKey.ENTER,row=3,col=3f)
    )

    private fun buildSymbols(): List<Key> {
        val symbols = listOf(
            "1","2","3","4","5","6","7","8","9","0",
            "@","#","￥","%","&","*","-","+","=","/",
            "!","\"","'",":",";","<",">","?","\\","|",
            "(",")","[","]","{","}","~","^","·","_"
        )
        val keys = mutableListOf<Key>()
        symbols.forEachIndexed { i, s ->
            keys.add(Key(s, s, -1, row = i / 10, col = (i % 10).toFloat()))
        }
        keys.add(Key("abc","",-1,special=SpecialKey.MODE_SWITCH,row=3,col=0f,width=2f))
        keys.add(Key("空格"," ",-1,special=SpecialKey.SPACE,row=3,col=2f,width=6f))
        keys.add(Key("⏎","",-1,special=SpecialKey.ENTER,row=3,col=8f,width=2f))
        return keys
    }

    private fun buildNumbers(): List<Key> = listOf(
        Key("1","1",-1,row=0,col=0f), Key("2","2",-1,row=0,col=1f), Key("3","3",-1,row=0,col=2f),
        Key("⌫","",-1,special=SpecialKey.DELETE,row=0,col=3f),
        Key("4","4",-1,row=1,col=0f), Key("5","5",-1,row=1,col=1f), Key("6","6",-1,row=1,col=2f),
        Key("abc","",-1,special=SpecialKey.MODE_SWITCH,row=1,col=3f),
        Key("7","7",-1,row=2,col=0f), Key("8","8",-1,row=2,col=1f), Key("9","9",-1,row=2,col=2f),
        Key("空格"," ",-1,special=SpecialKey.SPACE,row=2,col=3f),
        Key("英/中","",-1,special=SpecialKey.LANG_SWITCH,row=3,col=0f),
        Key("0","0",-1,row=3,col=1f), Key(".",".",-1,row=3,col=2f),
        Key("⏎","",-1,special=SpecialKey.ENTER,row=3,col=3f)
    )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val key = keyAt(event.x, event.y) ?: return false
                trackingGlow = glowPaint.shader != null
                moveTouchGlow(event.x, event.y)
                currentPressed = key
                key.pressed = true
                invalidate()
                startLongPress(key)
            }
            MotionEvent.ACTION_MOVE -> {
                moveTouchGlow(event.x, event.y)
                val key = keyAt(event.x, event.y)
                if (key !== currentPressed) {
                    currentPressed?.pressed = false
                    stopLongPress()
                    currentPressed = key
                    key?.pressed = true
                    if (key != null) startLongPress(key)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                fadeTouchGlow()
                val key = currentPressed
                stopLongPress()
                cancelRepeatDelete()
                key?.pressed = false
                currentPressed = null
                invalidate()
                if (key != null && keyAt(event.x, event.y) === key) {
                    handleKeyTap(key)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                fadeTouchGlow()
                currentPressed?.pressed = false
                currentPressed = null
                stopLongPress()
                cancelRepeatDelete()
                invalidate()
            }
        }
        return true
    }

    private fun keyAt(x: Float, y: Float): Key? =
        keys.firstOrNull { it.rect.contains(x, y) }

    private fun handleKeyTap(key: Key) {
        performHaptic()
        if (key.special != null) {
            onSpecialKey?.invoke(key.special)
        } else if (key.output.isNotEmpty()) {
            onKeyPress?.invoke(key.output)
        }
    }

    private fun startLongPress(key: Key) {
        longPressHandler = Runnable {
            key.pressed = false
            invalidate()
            if (key.special == SpecialKey.DELETE) {
                startRepeatDelete()
            } else {
                onKeyLongPress?.invoke(key.output)
            }
        }
        postDelayed(longPressHandler!!, 400)
    }

    private fun stopLongPress() {
        longPressHandler?.let { removeCallbacks(it) }
        longPressHandler = null
    }

    private fun startRepeatDelete() {
        onSpecialKey?.invoke(SpecialKey.DELETE)
        repeatDelete = object : Runnable {
            override fun run() {
                onSpecialKey?.invoke(SpecialKey.DELETE)
                postDelayed(this, 60)
            }
        }
        postDelayed(repeatDelete!!, 200)
    }

    private fun cancelRepeatDelete() {
        repeatDelete?.let { removeCallbacks(it) }
        repeatDelete = null
    }

    private fun performHaptic() {
        if (settings.cachedVibrate) {
            VibratorHelper.vibrate(context, 15)
        }
        if (settings.cachedSound) {
            AudioManagerHelper.playKeyClick(context)
        }
    }

    fun release() {
        stopLongPress()
        cancelRepeatDelete()
    }
}
