import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader

object LutParser {
    fun parseCube(context: Context, fileName: String): FloatArray? {
        if (fileName == "无") return null
        
        val inputStream = context.assets.open("luts/$fileName")
        val reader = BufferedReader(InputStreamReader(inputStream))
        var size = 0
        val colors = mutableListOf<Float>()

        reader.useLines { lines ->
            for (line in lines) {
                if (line.startsWith("LUT_3D_SIZE")) {
                    size = line.split(" ")[1].toInt()
                } else if (line.matches(Regex("^\\d.*"))) {
                    // 解析 RGB 浮点数
                    val rgb = line.split(" ")
                    colors.add(rgb[0].toFloat())
                    colors.add(rgb[1].toFloat())
                    colors.add(rgb[2].toFloat())
                }
            }
        }
        return colors.toFloatArray()
    }
}
