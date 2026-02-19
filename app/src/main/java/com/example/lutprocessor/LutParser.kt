package com.example.lutprocessor

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

object LutParser {
    fun parseCube(context: Context, fileName: String): Pair<FloatBuffer, Int>? {
        if (fileName == "无") return null
        
        val inputStream = context.assets.open("luts/$fileName")
        val reader = BufferedReader(InputStreamReader(inputStream))
        var size = 0
        val colors = mutableListOf<Float>()

        reader.useLines { lines ->
            for (line in lines) {
                if (line.startsWith("LUT_3D_SIZE")) {
                    size = line.split(" ")[1].toInt()
                } else if (line.matches(Regex("^[-+]?[0-9]*\\.?[0-9]+([eE][-+]?[0-9]+)?\\s+[-+]?[0-9]*\\.?[0-9]+([eE][-+]?[0-9]+)?\\s+[-+]?[0-9]*\\.?[0-9]+([eE][-+]?[0-9]+)?.*"))) {
                    val rgb = line.trim().split(Regex("\\s+"))
                    if (rgb.size >= 3) {
                        colors.add(rgb[0].toFloat())
                        colors.add(rgb[1].toFloat())
                        colors.add(rgb[2].toFloat())
                    }
                }
            }
        }
        
        val buffer = ByteBuffer.allocateDirect(colors.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        buffer.put(colors.toFloatArray()).position(0)
        
        return Pair(buffer, size)
    }
}
