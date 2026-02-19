package com.example.lutprocessor

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.exifinterface.media.ExifInterface
import jp.co.cyberagent.android.gpuimage.GPUImageView
import jp.co.cyberagent.android.gpuimage.filter.GPUImageFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream

class MainActivity : AppCompatActivity() {

    private lateinit var gpuImageView: GPUImageView
    private lateinit var spinnerLut: Spinner
    private lateinit var seekBarIntensity: SeekBar
    private lateinit var tvFileFormat: TextView
    private var currentUri: Uri? = null
    private var originalBitmap: Bitmap? = null
    private var activeFilter: GlLutFilter? = null

    private val lutPresets = arrayOf("无", "Contemporary.CUBE", "Sepia.CUBE", "Selenium.CUBE", "Eternal.CUBE", "Blue.CUBE", "Classic.CUBE")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        gpuImageView = findViewById(R.id.gpuImageView_preview)
        spinnerLut = findViewById(R.id.spinner_lut)
        seekBarIntensity = findViewById(R.id.seekBar_intensity)
        tvFileFormat = findViewById(R.id.tv_file_format)
        val btnLoad = findViewById<Button>(R.id.btn_load)
        val btnSave = findViewById<Button>(R.id.btn_save)

        spinnerLut.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, lutPresets)

        val pickImage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let {
                currentUri = it
                detectFormatAndLoad(it)
            }
        }

        btnLoad.setOnClickListener { pickImage.launch(arrayOf("image/jpeg", "image/x-adobe-dng")) }
        btnSave.setOnClickListener { currentUri?.let { uri -> saveToGallery(uri) } }

        // --- 实时预览逻辑 ---
        spinnerLut.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updatePreview()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        seekBarIntensity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) updatePreview()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun detectFormatAndLoad(uri: Uri) {
        // 判断格式
        val mimeType = contentResolver.getType(uri)
        val formatText = if (mimeType?.contains("x-adobe-dng") == true) "📸 格式: RAW (DNG)" else "🖼️ 格式: JPG"
        tvFileFormat.text = formatText

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val source = ImageDecoder.createSource(contentResolver, uri)
                originalBitmap = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
                    decoder.isMutableRequired = true
                }
                withContext(Dispatchers.Main) {
                    gpuImageView.setImage(originalBitmap)
                    updatePreview() // 载入后立刻刷新滤镜
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // 核心：实时渲染画面
    private fun updatePreview() {
        if (originalBitmap == null) return
        val lutName = spinnerLut.selectedItem.toString()
        val intensity = seekBarIntensity.progress / 100f

        if (lutName == "无" || intensity == 0f) {
            gpuImageView.filter = GPUImageFilter() // 清除滤镜
        } else {
            if (activeFilter == null) activeFilter = GlLutFilter()
            activeFilter!!.intensity = intensity
            
            val lutData = LutParser.parseCube(this, lutName)
            if (lutData != null) {
                activeFilter!!.setLut3DTexture(lutData.first, lutData.second)
            }
            gpuImageView.filter = activeFilter
        }
        gpuImageView.requestRender() // 触发 GPU 极速重绘
    }

    // 核心：保存到公共相册并拷贝 EXIF
    private fun saveToGallery(sourceUri: Uri) {
        Toast.makeText(this, "正在处理并保存至相册...", Toast.LENGTH_SHORT).show()
        
        CoroutineScope(Dispatchers.IO).launch {
            if (originalBitmap == null) return@launch

            // 1. 获取当前滤镜处理后的 Bitmap
            val processedBitmap = gpuImageView.gpuImage.bitmapWithFilterApplied

            // 2. 先存入私有缓存目录作为中转文件（方便写入 EXIF）
            val tempFile = File(cacheDir, "temp_export.jpg")
            FileOutputStream(tempFile).use { out ->
                processedBitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
            }

            // 3. 将原图的 EXIF 全量拷贝给这个中转文件
            copyAllExif(sourceUri, tempFile.absolutePath)

            // 4. 使用 MediaStore 插入到系统公共图库 (Android 10+ 规范)
            val fileName = "LeicaLook_${System.currentTimeMillis()}.jpg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/LeicaLook")
                put(MediaStore.Images.Media.IS_PENDING, 1) // 标记为正在写入
            }

            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val imageUri = contentResolver.insert(collection, values)

            if (imageUri != null) {
                contentResolver.openOutputStream(imageUri).use { out ->
                    FileInputStream(tempFile).use { input ->
                        input.copyTo(out!!) // 将带 EXIF 的文件数据拷贝进相册
                    }
                }
                // 解除写入标记，让相册显示它
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                contentResolver.update(imageUri, values, null, null)

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "已成功保存到相册 Pictures/LeicaLook 目录", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun copyAllExif(sourceUri: Uri, destPath: String) {
        var inputStream: InputStream? = null
        try {
            inputStream = contentResolver.openInputStream(sourceUri)
            if (inputStream != null) {
                val oldExif = ExifInterface(inputStream)
                val newExif = ExifInterface(destPath)

                val fields = ExifInterface::class.java.fields
                for (field in fields) {
                    if (field.name.startsWith("TAG_")) {
                        try {
                            val tag = field.get(null) as? String
                            if (tag != null) {
                                val value = oldExif.getAttribute(tag)
                                if (value != null) {
                                    newExif.setAttribute(tag, value)
                                }
                            }
                        } catch (e: Exception) { }
                    }
                }
                newExif.saveAttributes()
            }
        } finally {
            inputStream?.close()
        }
    }
}
