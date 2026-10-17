package com.freeenterprise.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/** Chooses the UI style at launch: FF4 menu shell (default) or the classic UI. */
class RouterActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Every launch starts with a clean flagset.
        FlagSession.clear()
        val classic = EditorPrefs.uiMode(this) == EditorPrefs.UI_CLASSIC
        startActivity(
            Intent(
                this,
                if (classic) MainActivity::class.java else FF4MainActivity::class.java
            )
        )
        finish()
    }
}