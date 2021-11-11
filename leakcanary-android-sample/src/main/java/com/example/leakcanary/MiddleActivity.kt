package com.example.leakcanary

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button

class MiddleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.middle_activity)
        findViewById<Button>(R.id.btn_start).setOnClickListener {
            startActivity(Intent(this@MiddleActivity, SecondActivity::class.java))
        }
    }
}