package com.cappyjune.muse.desktop.spike

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.remember
import com.cappyjune.muse.desktop.ui.vector
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * Developer tool: the launcher icon as PNGs for the package, from the phone's adaptive icon
 * (its foreground on the same dark background, as a rounded square like GNOME's own icons).
 * Usage: Icon <out dir>
 */
fun main(args: Array<String>) {
    val out = File(args.getOrElse(0) { "packaging/icons" }).apply { mkdirs() }
    listOf(16, 24, 32, 48, 64, 128, 256, 512).forEach { px ->
        // A little inset, as GNOME's icons sit inside their square with room for a shadow.
        val inset = px / 16f
        val scene = ImageComposeScene(px, px, Density(1f)) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().scale((px - 2 * inset) / px).clip(RoundedCornerShape(22)).background(Color(0xFF23272F))) {
                    // The adaptive foreground is 108 units with the art in the middle 72: scaled up to fill.
                    Image(rememberVectorPainter(remember { vector("ic_launcher_fg") }), null, Modifier.fillMaxSize().scale(108f / 72f))
                }
            }
        }
        File(out, "muse-$px.png").writeBytes(scene.render().encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
    }
    kotlin.system.exitProcess(0)
}
