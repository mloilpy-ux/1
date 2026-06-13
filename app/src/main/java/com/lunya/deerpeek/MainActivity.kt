package com.lunya.deerpeek

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val text = TextView(this)
        text.text = "DeerPeek запущен"
        text.textSize = 24f
        setContentView(text)
    }
}
