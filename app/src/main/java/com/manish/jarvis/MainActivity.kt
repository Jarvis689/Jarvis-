package com.manish.jarvis

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = TextView(this)
        view.text = "Jarvis ready, sir."
        view.textSize = 28f
        view.setPadding(48, 200, 48, 48)
        setContentView(view)
    }
}
