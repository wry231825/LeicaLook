import android.opengl.GLES20
import jp.co.cyberagent.android.gpuimage.filter.GPUImageFilter

class GlLutFilter : GPUImageFilter(NO_FILTER_VERTEX_SHADER, LUT_FRAGMENT_SHADER) {
    
    private var lutTextureId = -1
    private var intensityLocation = -1
    var intensity: Float = 1.0f 
        set(value) {
            field = value
            setFloat(intensityLocation, field)
        }

    // 将解析出的 CUBE 浮点数组绑定为 OpenGL 的 3D 纹理
    fun setLutArray(lutData: FloatArray?, size: Int) {
        // 此处省略具体的 OpenGL 3D纹理绑定代码 (glTexImage3D)
        // 核心是将 lutData 载入 GPU 显存
    }

    override fun onInit() {
        super.onInit()
        intensityLocation = GLES20.glGetUniformLocation(program, "intensity")
        intensity = 1.0f
    }

    companion object {
        // 这是运行在 GPU 上的 C 语言风格代码，速度极快
        private const val LUT_FRAGMENT_SHADER = """
            precision highp float;
            varying highp vec2 textureCoordinate;
            uniform sampler2D inputImageTexture; // 原图
            uniform sampler3D lutTexture;        // 3D LUT 纹理
            uniform float intensity;             // 强度 (0.0 - 1.0)

            void main() {
                vec4 textureColor = texture2D(inputImageTexture, textureCoordinate);
                
                // 将原图像素的 RGB 值作为 3D 坐标，在 LUT 纹理中查找对应的新颜色
                vec3 lutColor = texture3D(lutTexture, textureColor.rgb).rgb;
                
                // mix 函数相当于: original * (1 - intensity) + lutColor * intensity
                vec3 finalColor = mix(textureColor.rgb, lutColor, intensity);
                
                gl_FragColor = vec4(finalColor, textureColor.a);
            }
        """
    }
}
