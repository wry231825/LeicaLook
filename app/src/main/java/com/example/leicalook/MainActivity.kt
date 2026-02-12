package com.example.leicalook

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // 设置暗色主题
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFD32F2F), // 徕卡红
                    background = Color(0xFF121212),
                    surface = Color(0xFF1E1E1E)
                )
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    LeicaLookApp()
                }
            }
        }
    }
}

// ------ 数据模型 ------
data class CubeLut(val size: Int, val data: FloatArray, val name: String)

@Composable
fun LeicaLookApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    // 状态管理
    var originalUri by remember { mutableStateOf<Uri?>(null) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) } // 当前显示的图
    var originalBitmapSmall by remember { mutableStateOf<Bitmap?>(null) } // 原始缩略图（缓存用）
    
    var currentLut by remember { mutableStateOf<CubeLut?>(null) }
    var intensity by remember { mutableStateOf(100f) } // 0-100
    
    var isProcessing by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf("Ready") }
    
    // 防抖动 Job
    var debounceJob by remember { mutableStateOf<Job?>(null) }

    // 1. 图片选择器
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            originalUri = uri
            isProcessing = true
            statusMsg = "Loading image..."
            scope.launch {
                // 读取图片 (限制预览尺寸为 1200px，防止卡顿)
                val bitmap = loadResizedBitmap(context, uri, 1200)
                if (bitmap != null) {
                    originalBitmapSmall = bitmap
                    previewBitmap = bitmap // 初始显示原图
                    
                    // 如果已经选了 LUT，重新应用
                    if (currentLut != null) {
                        previewBitmap = applyLutWithIntensity(bitmap, currentLut!!, intensity / 100f)
                    }
                    statusMsg = "Image loaded"
                } else {
                    statusMsg = "Failed to load image"
                }
                isProcessing = false
            }
        }
    }

    // 2. LUT 选择器 (.cube)
    val pickLut = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            isProcessing = true
            statusMsg = "Parsing LUT..."
            scope.launch {
                val lut = parseCubeFile(context, uri)
                if (lut != null) {
                    currentLut = lut
                    statusMsg = "LUT: ${lut.name}"
                    // 立即应用到当前图片
                    if (originalBitmapSmall != null) {
                        previewBitmap = applyLutWithIntensity(originalBitmapSmall!!, lut, intensity / 100f)
                    }
                } else {
                    statusMsg = "Invalid .cube file"
                }
                isProcessing = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 标题栏
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Leica", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 28.sp)
            Text("Look", color = Color(0xFFD32F2F), fontWeight = FontWeight.Bold, fontSize = 28.sp)
        }
        
        Spacer(modifier = Modifier.height(24.dp))

        // --- 预览区域 ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(420.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF252525)),
            contentAlignment = Alignment.Center
        ) {
            if (previewBitmap != null) {
                Image(
                    bitmap = previewBitmap!!.asImageBitmap(),
                    contentDescription = "Preview",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Select DNG/JPG/PNG", color = Color.Gray)
                }
            }
            
            if (isProcessing) {
                CircularProgressIndicator(color = Color(0xFFD32F2F))
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // --- 强度滑块 ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Intensity", color = Color.White)
            Text("${intensity.toInt()}%", color = Color.Gray)
        }
        
        Slider(
            value = intensity,
            onValueChange = { newValue -> 
                intensity = newValue
                // 防抖动逻辑：拖动时不计算，停下才计算
                debounceJob?.cancel()
                debounceJob = scope.launch {
                    delay(40) // 延迟 40ms
                    if (originalBitmapSmall != null && currentLut != null) {
                        previewBitmap = applyLutWithIntensity(
                            originalBitmapSmall!!, 
                            currentLut!!, 
                            newValue / 100f
                        )
                    }
                }
            },
            valueRange = 0f..100f,
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFFD32F2F),
                activeTrackColor = Color(0xFFD32F2F),
                inactiveTrackColor = Color(0xFF444444)
            ),
            enabled = currentLut != null
        )

        Spacer(modifier = Modifier.height(32.dp))

        // --- 底部按钮 ---
        Row(
            modifier = Modifier.fillMaxWidth(), 
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Button(
                onClick = { pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                modifier = Modifier.weight(1f).height(56.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333))
            ) {
                Text("1. Photo")
            }
            
            Button(
                onClick = { pickLut.launch(arrayOf("*/*")) },
                modifier = Modifier.weight(1f).height(56.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333))
            ) {
                Text("2. LUT")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        
        // --- 保存按钮 ---
        Button(
            onClick = {
                if (originalUri != null && currentLut != null) {
                    isProcessing = true
                    statusMsg = "Processing full resolution..."
                    scope.launch {
                        saveFullResImage(context, originalUri!!, currentLut!!, intensity / 100f) { msg ->
                            statusMsg = msg
                        }
                        isProcessing = false
                    }
                } else {
                    Toast.makeText(context, "Please select Photo & LUT first", Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)), // Leica Red
            enabled = !isProcessing && originalUri != null && currentLut != null
        ) {
            Text("3. Export to Gallery", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
        
        Text(
            text = statusMsg, 
            color = Color.Gray, 
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 16.dp)
        )
    }
}

// ================== 核心算法 ==================

// 1. 读取并压缩图片
fun loadResizedBitmap(context: Context, uri: Uri, targetWidth: Int): Bitmap? {
    return try {
        val inputStream = context.contentResolver.openInputStream(uri)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(inputStream, null, options)
        inputStream?.close()

        var scale = 1
        while (options.outWidth / scale / 2 >= targetWidth) {
            scale *= 2
        }

        val o2 = BitmapFactory.Options().apply { 
            inSampleSize = scale 
            inPreferredConfig = Bitmap.Config.ARGB_8888 // 保证色彩
        }
        val input2 = context.contentResolver.openInputStream(uri)
        val bmp = BitmapFactory.decodeStream(input2, null, o2)
        input2?.close()
        bmp
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

// 2. 智能解析 .cube 文件
suspend fun parseCubeFile(context: Context, uri: Uri): CubeLut? {
    return withContext(Dispatchers.IO) {
        try {
            val input = context.contentResolver.openInputStream(uri) ?: return@withContext null
            val reader = BufferedReader(InputStreamReader(input))
            val dataList = ArrayList<Float>()
            var size = 0
            val name = uri.path?.substringAfterLast("/") ?: "Custom LUT"

            var line = reader.readLine()
            while (line != null) {
                line = line.trim()
                // 忽略注释和空行
                if (line.isEmpty() || line.startsWith("#")) {
                    line = reader.readLine()
                    continue
                }

                if (line.startsWith("LUT_3D_SIZE")) {
                    val parts = line.split("\\s+".toRegex())
                    if (parts.size >= 2) size = parts[1].toInt()
                } else if (!line.startsWith("TITLE") && !line.startsWith("DOMAIN")) {
                    // 解析 RGB 数据: "0.123 0.456 0.789"
                    val parts = line.split("\\s+".toRegex())
                    if (parts.size >= 3) {
                        try {
                            dataList.add(parts[0].toFloat())
                            dataList.add(parts[1].toFloat())
                            dataList.add(parts[2].toFloat())
                        } catch (e: Exception) {}
                    }
                }
                line = reader.readLine()
            }
            reader.close()

            // 如果没读到 SIZE，根据数据量倒推
            if (size == 0 && dataList.isNotEmpty()) {
                // size^3 * 3 = count  ->  size = cbrt(count/3)
                size = Math.cbrt((dataList.size / 3).toDouble()).toInt()
            }

            if (dataList.isEmpty() || size == 0) null else CubeLut(size, dataList.toFloatArray(), name)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}

// 3. 应用 LUT + 强度混合 (Lerp)
suspend fun applyLutWithIntensity(src: Bitmap, lut: CubeLut, intensity: Float): Bitmap {
    return withContext(Dispatchers.Default) {
        val width = src.width
        val height = src.height
        val pixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        val size = lut.size
        val data = lut.data
        val sizeSq = size * size
        val scale = (size - 1) / 255f
        
        val alpha = intensity.coerceIn(0f, 1f)
        val invAlpha = 1f - alpha

        if (alpha > 0.01f) {
            for (i in pixels.indices) {
                val color = pixels[i]
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF

                // 计算 LUT 坐标 (简单查找)
                val ri = (r * scale).roundToInt().coerceIn(0, size - 1)
                val gi = (g * scale).roundToInt().coerceIn(0, size - 1)
                val bi = (b * scale).roundToInt().coerceIn(0, size - 1)

                // 查找表索引
                val index = (ri + gi * size + bi * sizeSq) * 3

                if (index + 2 < data.size) {
                    val lutR = (data[index] * 255f)
                    val lutG = (data[index + 1] * 255f)
                    val lutB = (data[index + 2] * 255f)

                    // 强度混合公式: result = original * (1-intensity) + lut * intensity
                    val finalR = (r * invAlpha + lutR * alpha).toInt().coerceIn(0, 255)
                    val finalG = (g * invAlpha + lutG * alpha).toInt().coerceIn(0, 255)
                    val finalB = (b * invAlpha + lutB * alpha).toInt().coerceIn(0, 255)

                    pixels[i] = (0xFF shl 24) or (finalR shl 16) or (finalG shl 8) or finalB
                }
            }
        }

        val newBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        newBitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        newBitmap
    }
}

// 4. 保存高清图 (支持后台处理)
suspend fun saveFullResImage(context: Context, uri: Uri, lut: CubeLut, intensity: Float, log: (String) -> Unit) {
    withContext(Dispatchers.IO) {
        try {
            // 加载全尺寸图 (注意：这里直接加载可能会慢，但保证画质)
            val inputStream = context.contentResolver.openInputStream(uri)
            val originalFull = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()

            if (originalFull == null) {
                withContext(Dispatchers.Main) { log("Failed to load full image") }
                return@withContext
            }

            // 应用 LUT
            val processed = applyLutWithIntensity(originalFull, lut, intensity)

            // 保存到相册
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "LeicaLook_${System.currentTimeMillis()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/LeicaLook")
                }
            }

            val resolver = context.contentResolver
            val item = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)

            if (item != null) {
                resolver.openOutputStream(item).use { out ->
                    processed.compress(Bitmap.CompressFormat.JPEG, 100, out!!)
                }
                withContext(Dispatchers.Main) { log("Saved to Gallery successfully!") }
            }
            
            // 立即回收大图内存
            originalFull.recycle()
            if (processed != originalFull) processed.recycle()

        } catch (e: Exception) {
            e.printStackTrace()
            withContext(Dispatchers.Main) { log("Error: ${e.message}") }
        }
    }
}
