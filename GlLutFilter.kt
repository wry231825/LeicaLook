import android.opengl.GLES30
import jp.co.cyberagent.android.gpuimage.filter.GPUImageFilter
import java.nio.FloatBuffer

class GlLutFilter : GPUImageFilter(NO_FILTER_VERTEX_SHADER, LUT_FRAGMENT_SHADER) {

    private var lutTextureId = -1
    private var intensityLocation = -1
    private var lutTextureLocation = -1
    
    var intensity: Float = 1.0f 
        set(value) {
            field = value
            setFloat(intensityLocation, field)
        }

    // 接收从 CUBE 文件解析出来的 3D 浮点数组 (通常大小为 33x33x33 * 3)
    fun setLut3DTexture(lutBuffer: FloatBuffer, lutSize: Int) {
        runOnDraw {
            if (lutTextureId != -1) {
                GLES30.glDeleteTextures(1, intArrayOf(lutTextureId), 0)
            }
            
            val textures = IntArray(1)
            GLES30.glGenTextures(1, textures, 0)
            lutTextureId = textures[0]

            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTextureId)
            
            // 设置纹理过滤模式（线性插值保证色彩平滑过渡）
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)

            // 将 CUBE 数据加载进 GPU 显存
            GLES30.glTexImage3D(
                GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB16F, 
                lutSize, lutSize, lutSize, 0, 
                GLES30.GL_RGB, GLES30.GL_FLOAT, lutBuffer
            )
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, 0)
        }
    }

    override fun onInit() {
        super.onInit()
        intensityLocation = GLES30.glGetUniformLocation(program, "intensity")
        lutTextureLocation = GLES30.glGetUniformLocation(program, "lutTexture")
        intensity = 1.0f
    }

    override fun onDrawArraysPre() {
        super.onDrawArraysPre()
        if (lutTextureId != -1) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTextureId)
            GLES30.glUniform1i(lutTextureLocation, 3)
        }
    }

    companion object {
        // 使用 #version 300 es 启用 3D 纹理支持
        private const val LUT_FRAGMENT_SHADER = """
            #version 300 es
            precision highp float;
            in vec2 textureCoordinate;
            uniform sampler2D inputImageTexture;
            uniform mediump sampler3D lutTexture; 
            uniform float intensity;
            out vec4 fragColor;

            void main() {
                vec4 textureColor = texture(inputImageTexture, textureCoordinate);
                // 将原图色彩空间映射到 3D LUT 坐标内查找新颜色
                vec3 lutColor = texture(lutTexture, textureColor.rgb).rgb;
                // 根据滑块强度混合颜色
                fragColor = vec4(mix(textureColor.rgb, lutColor, intensity), textureColor.a);
            }
        """
        
        // 需同步提供 GLES 3.0 的顶点着色器
        private const val NO_FILTER_VERTEX_SHADER = """
            #version 300 es
            in vec4 position;
            in vec4 inputTextureCoordinate;
            out vec2 textureCoordinate;
            void main() {
                gl_Position = position;
                textureCoordinate = inputTextureCoordinate.xy;
            }
        """
    }
}
