package hr.beather.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import hr.beather.R

/** Tiny builder for the settings screens: sections, chips, switches, sliders, colour swatches. */
class UiKit(private val ctx: Context, private val root: LinearLayout) {

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    private val pad get() = dp(20)

    fun section(title: String) {
        root.addView(TextView(ctx).apply {
            text = title
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
            setTextColor(resolveColor(androidx.appcompat.R.attr.colorPrimary))
            setPadding(pad, dp(22), pad, dp(6))
        })
    }

    fun label(text: String) {
        root.addView(TextView(ctx).apply {
            this.text = text
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            setPadding(pad, dp(8), pad, dp(2))
        })
    }

    fun hint(text: String) {
        root.addView(TextView(ctx).apply {
            this.text = text
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            alpha = 0.75f
            setPadding(pad, 0, pad, dp(4))
        })
    }

    /** Single-choice chips. */
    fun choice(options: List<String>, selected: Int, onSelect: (Int) -> Unit): ChipGroup {
        val group = ChipGroup(ctx).apply {
            isSingleSelection = true
            isSelectionRequired = true
            setPadding(pad, dp(2), pad, dp(2))
        }
        options.forEachIndexed { i, label ->
            group.addView(Chip(ctx).apply {
                id = View.generateViewId()
                text = label
                isCheckable = true
                isChecked = i == selected
                setOnClickListener { onSelect(i) }
            })
        }
        root.addView(group)
        return group
    }

    fun switch(label: String, checked: Boolean, onChange: (Boolean) -> Unit): MaterialSwitch {
        val sw = MaterialSwitch(ctx).apply {
            text = label
            isChecked = checked
            setPadding(pad, dp(6), pad, dp(6))
            setOnCheckedChangeListener { _, on -> onChange(on) }
        }
        root.addView(sw, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return sw
    }

    fun slider(
        label: String, from: Int, to: Int, step: Int, value: Int,
        format: (Int) -> String = { it.toString() }, onChange: (Int) -> Unit,
    ) {
        val title = TextView(ctx).apply {
            text = "$label: ${format(value)}"
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            setPadding(pad, dp(8), pad, 0)
        }
        val slider = Slider(ctx).apply {
            valueFrom = from.toFloat()
            valueTo = to.toFloat()
            stepSize = step.toFloat()
            this.value = value.coerceIn(from, to).toFloat()
            setLabelFormatter { format(it.toInt()) }
            addOnChangeListener { _, v, fromUser ->
                title.text = "$label: ${format(v.toInt())}"
                if (fromUser) onChange(v.toInt())
            }
        }
        root.addView(title)
        root.addView(slider, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(8); marginEnd = dp(8)
        })
    }

    /** Row of colour circles plus a "custom" circle that asks for a hex value. */
    fun colors(palette: IntArray, selected: Int, onPick: (Int) -> Unit) {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(pad, dp(6), pad, dp(6))
        }
        val swatches = mutableListOf<Pair<View, Int>>()
        fun refresh(current: Int) = swatches.forEach { (v, c) -> v.background = circle(c, (c or 0xFF000000.toInt()) == (current or 0xFF000000.toInt())) }

        palette.forEach { color ->
            val v = View(ctx)
            swatches += v to color
            v.setOnClickListener { onPick(color); refresh(color) }
            row.addView(v, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(10) })
        }
        val custom = TextView(ctx).apply {
            text = "+"
            gravity = Gravity.CENTER
            textSize = 18f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(2), resolveColor(com.google.android.material.R.attr.colorOutline))
            }
            setOnClickListener {
                askHex(selected) { c -> onPick(c); refresh(c) }
            }
        }
        row.addView(custom, LinearLayout.LayoutParams(dp(36), dp(36)))
        refresh(selected)
        root.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(row) })
    }

    private fun askHex(current: Int, onColor: (Int) -> Unit) {
        val input = EditText(ctx).apply {
            setText(String.format("#%06X", current and 0xFFFFFF))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setSingleLine()
        }
        val box = LinearLayout(ctx).apply { setPadding(pad, dp(8), pad, 0); addView(input, LinearLayout.LayoutParams(-1, -2)) }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.custom_color)
            .setView(box)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                runCatching { Color.parseColor(input.text.toString().trim().let { if (it.startsWith("#")) it else "#$it" }) }
                    .getOrNull()?.let(onColor)
            }
            .show()
    }

    private fun circle(color: Int, selected: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color or 0xFF000000.toInt())
        if (selected) setStroke(dp(3), resolveColor(androidx.appcompat.R.attr.colorPrimary))
        else setStroke(dp(1), 0x55888888)
    }

    private fun resolveColor(attr: Int): Int {
        val ta = ctx.obtainStyledAttributes(intArrayOf(attr))
        val c = ta.getColor(0, Color.GRAY)
        ta.recycle()
        return c
    }

    companion object {
        val BG_PALETTE = intArrayOf(
            0xFF000000.toInt(), 0xFF263238.toInt(), 0xFF37474F.toInt(), 0xFF0D1B3E.toInt(), 0xFF1565C0.toInt(),
            0xFF00695C.toInt(), 0xFF4A148C.toInt(), 0xFF880E4F.toInt(), 0xFFB71C1C.toInt(), 0xFFFFFFFF.toInt(),
        )
        val TEXT_PALETTE = intArrayOf(
            0xFFFFFFFF.toInt(), 0xFFECEFF1.toInt(), 0xFFFFF8E1.toInt(), 0xFFB3E5FC.toInt(),
            0xFFFFD54F.toInt(), 0xFF212121.toInt(), 0xFF000000.toInt(),
        )
        val ACCENT_PALETTE = intArrayOf(
            0xFF90CAF9.toInt(), 0xFF4FC3F7.toInt(), 0xFF80CBC4.toInt(), 0xFFFFD54F.toInt(),
            0xFFFF8A65.toInt(), 0xFFF48FB1.toInt(), 0xFFFFFFFF.toInt(),
        )

        @Suppress("unused")
        fun tint(c: Int) = ColorStateList.valueOf(c)
    }
}
