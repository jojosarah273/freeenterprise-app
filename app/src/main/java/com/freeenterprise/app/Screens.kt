package com.freeenterprise.app

import android.app.Activity
import android.content.Intent

/** Routes to the right editor variant based on the selected UI mode. */
object Screens {
    fun openEditor(activity: Activity, flags: String, seed: String) {
        val ff4 = EditorPrefs.uiMode(activity) != EditorPrefs.UI_CLASSIC
        val target = if (ff4) FF4FlagEditorActivity::class.java else FlagEditorActivity::class.java
        activity.startActivity(
            Intent(activity, target)
                .putExtra("flags", flags)
                .putExtra("seed", seed)
        )
    }
}