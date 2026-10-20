package com.freeenterprise.desktop

import com.freeenterprise.ff4.Ff4MenuItem
import com.freeenterprise.ff4.Ff4MenuModel
import com.freeenterprise.ff4.Ff4Palette
import com.freeenterprise.ff4.Ff4Scale
import java.awt.Graphics
import java.awt.Toolkit
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.io.File
import javax.swing.JFrame
import javax.swing.JPanel
import kotlin.system.exitProcess

/**
 * The Linux dev build.
 *
 *   ./gradlew :desktop:run                    fullscreen, keyboard driven
 *   ./gradlew :desktop:run --args="--export DIR"   write PNGs of the screens
 *
 * The export exists because it is the only honest way to check rendering without a
 * screen: the window and the PNGs come from the same canvas, so if the images look
 * right the window does too.
 */
fun main(args: Array<String>) {
    val metrics = AwtTextMetrics()
    if (args.firstOrNull() == "--export") {
        export(args.getOrNull(1) ?: "build/ff4shots", metrics)
    } else {
        runWindow(metrics)
    }
}

private fun menuItems() = listOf(
    Ff4MenuItem("Play Free Enterprise", "3 seed(s)"),
    Ff4MenuItem("Generate Seed"),
    Ff4MenuItem("Check for updates", "v0.1.25-dev"),
    Ff4MenuItem("Settings"),
    Ff4MenuItem("Exit"),
)

private fun export(dir: String, metrics: AwtTextMetrics) {
    val width = 1280
    val height = 720
    val scale = Ff4Scale(minOf(width, height), 1f)
    val renderer = ScreenRenderer(scale, metrics)
    val canvas = SoftwareCanvas(width, height, metrics, shadowOffsetPx = scale.unit * 0.5f)
    val out = File(dir)

    for (selected in listOf(0, 2, 4)) {
        val list = renderer.mainMenu(
            width, height, menuItems(), selected,
            status = listOf("Free Enterprise 4.7.0.Gale", "3 seed(s) ready"),
            hint = "A  Select      B  Exit",
        )
        canvas.clear(Ff4Palette.BLACK)
        canvas.draw(list)
        canvas.writePng(File(out, "main-menu-$selected.png"))
    }

    for (progress in listOf(0, 45, 100)) {
        val list = renderer.progressOverlay(
            width, height,
            heading = "GENERATING SEED",
            progress = progress,
            stage = "Shuffling treasures",
            elapsed = "0:%02d".format(progress / 2),
        )
        canvas.clear(Ff4Palette.BLACK)
        canvas.draw(list)
        canvas.writePng(File(out, "generating-$progress.png"))
    }

    println("wrote screen images to ${out.absolutePath}")
}

private fun runWindow(metrics: AwtTextMetrics) {
    val screen = Toolkit.getDefaultToolkit().screenSize
    val width = screen.width
    val height = screen.height
    val scale = Ff4Scale(minOf(width, height), 1f)
    val renderer = ScreenRenderer(scale, metrics)
    val canvas = SoftwareCanvas(width, height, metrics, shadowOffsetPx = scale.unit * 0.5f)
    val model = Ff4MenuModel(menuItems())

    val panel = object : JPanel() {
        override fun paintComponent(g: Graphics) {
            g.drawImage(canvas.image, 0, 0, null)
        }
    }
    panel.isFocusable = true

    fun redraw() {
        canvas.clear(Ff4Palette.BLACK)
        canvas.draw(
            renderer.mainMenu(
                width, height, model.items, model.selected,
                status = listOf("Free Enterprise 4.7.0.Gale", "${model.size} entries"),
                hint = "Up/Down  Move      Enter  Select      Esc  Quit",
            ),
        )
        panel.repaint()
    }

    panel.addKeyListener(object : KeyAdapter() {
        override fun keyPressed(e: KeyEvent) {
            when (e.keyCode) {
                KeyEvent.VK_UP, KeyEvent.VK_W -> if (model.moveSelection(-1)) redraw()
                KeyEvent.VK_DOWN, KeyEvent.VK_S -> if (model.moveSelection(1)) redraw()
                KeyEvent.VK_ENTER, KeyEvent.VK_SPACE -> println(
                    "selected row ${model.activationTarget()}: ${model.items[model.selected].label}",
                )
                KeyEvent.VK_ESCAPE, KeyEvent.VK_Q -> exitProcess(0)
            }
        }
    })

    val frame = JFrame("Free Enterprise - dev build")
    frame.isUndecorated = true
    frame.contentPane.add(panel)
    frame.isVisible = true
    frame.graphicsConfiguration.device.setFullScreenWindow(frame)
    panel.requestFocusInWindow()
    redraw()
}
