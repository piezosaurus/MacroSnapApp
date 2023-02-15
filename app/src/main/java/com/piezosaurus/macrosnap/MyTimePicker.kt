package com.piezosaurus.macrosnap

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import android.widget.NumberPicker

/**
 * Time Picker that shows hours, minutes and seconds.
 * You can choose to use only minutes and seconds by
 * setting to false the property "includeHours".
 */
class MyTimePicker(context: Context, attrs: AttributeSet): LinearLayout(context, attrs) {

    private lateinit var timePickerLayout: View
    private var hourPicker: NumberPicker
    private var minPicker: NumberPicker
    private var secPicker: NumberPicker
    private var onTimeChangedListener: OnTimeChangedListener? = null

    private var hour: Int = 0
    private var minute: Int = 0
    private var second: Int = 0

    var initialHour: Int = 0
    var initialMinute: Int = 0
    var initialSeconds: Int = 0

    var maxValueHour: Int = 23
    var maxValueMinute: Int = 59
    var maxValueSeconds: Int = 59

    var minValueHour: Int = 0
    var minValueMinute: Int = 0
    var minValueSecond: Int = 0

    var includeHours: Boolean = true

    init {
        val view = inflate(context, R.layout.my_time_picker, this)

        hourPicker = view.findViewById(R.id.hours)
        minPicker = view.findViewById(R.id.minutes)
        secPicker = view.findViewById(R.id.seconds)
        hourPicker.setOnValueChangedListener { picker, oldVal, newVal -> hour = newVal
            onTimeChangedListener?.onTimeChanged( this, hour, minute, second)
        }
        minPicker.setOnValueChangedListener { picker, oldVal, newVal -> minute = newVal
            onTimeChangedListener?.onTimeChanged( this, hour, minute, second)
        }
        secPicker.setOnValueChangedListener { picker, oldVal, newVal -> second = newVal
            onTimeChangedListener?.onTimeChanged( this, hour, minute, second)
        }

        setupMaxValues()
        setupMinValues()
        setupInitialValues()

        if (!includeHours) {
            timePickerLayout.findViewById<LinearLayout>(R.id.hours_container)
                .visibility = View.GONE
        }
    }

    private fun setupMaxValues () {
        hourPicker.maxValue = maxValueHour
        minPicker.maxValue = maxValueMinute
        secPicker.maxValue = maxValueSeconds
    }

    private fun setupMinValues () {
        hourPicker.minValue = minValueHour
        minPicker.minValue = minValueMinute
        secPicker.minValue = minValueSecond
    }

    private fun setupInitialValues () {
        hourPicker.value = initialHour
        minPicker.value = initialMinute
        secPicker.value = initialSeconds
    }

    fun setOnTimeChangedListener(listener: OnTimeChangedListener) {
        onTimeChangedListener = listener
    }

    interface OnTimeChangedListener {
        fun onTimeChanged(var1: MyTimePicker?, var2: Int, var3: Int, var4: Int)
    }
}
