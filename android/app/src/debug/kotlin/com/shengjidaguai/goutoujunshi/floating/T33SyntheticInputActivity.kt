package com.shengjidaguai.goutoujunshi.floating

import android.app.Activity
import android.os.Bundle
import android.widget.*
import com.shengjidaguai.goutoujunshi.R

/** Native synthetic input target; has no projection grant, capture or network bypass. */
class T33SyntheticInputActivity:Activity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val rows=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        val editor=EditText(this).apply { hint=getString(R.string.t33_synthetic_input);contentDescription=hint;isSaveEnabled=false }
        rows.addView(editor)
        rows.addView(Button(this).apply {
            text=getString(R.string.t33_synthetic_panel);isFocusable=false
            setOnClickListener { FloatingRuntime.showAnswer?.invoke() }
        })
        repeat(40) { rows.addView(TextView(this).apply { text=getString(R.string.t33_synthetic_row,it);setPadding(0,(com.shengjidaguai.goutoujunshi.designsystem.JunshiLayout.touch.value * resources.displayMetrics.density).toInt(),0,0) }) }
        setContentView(ScrollView(this).apply { addView(rows) })
        editor.requestFocus()
    }
}
