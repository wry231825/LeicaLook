package com.example.lutprocessor

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaScannerConnection
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
        val mimeType = contentResolver.getType(uri)
        val formatText = if (mimeType?.contains("x-adobe-dng") == true) "📸 格式: RAW (DNG)" else "🖼️ 格式: JPG"
        tvFileFormat.text = formatText

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val source = ImageDecoder.createSource(contentResolver, uri)
                val newBitmap = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
                    decoder.isMutableRequired = true
                }
                
                withContext(Dispatchers.Main) {
                    // 彻底清理上一张图片的滤镜和内存，防止重叠和比例失调
                    activeFilter = null
                    gpuImageView.filter = GPUImageFilter()
                    originalBitmap?.recycle() 
                    
                    originalBitmap = newBitmap
                    gpuImageView.setImage(originalBitmap)
                    
                    // 重置控件状态
                    spinnerLut.setSelection(0)
                    seekBarIntensity.progress = 100
                    updatePreview() 
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun updatePreview() {
        if (originalBitmap == null) return
        val lutName = spinnerLut.selectedItem.toString()
        val intensity = seekBarIntensity.progress / 100f

        if (lutName == "无" || intensity == 0f) {
            gpuImageView.filter = GPUImageFilter() 
        } else {
            if (activeFilter == null) activeFilter = GlLutFilter()
            activeFilter!!.intensity = intensity
            
            val lutData = LutParser.parseCube(this, lutName)
            if (lutData != null) {
                activeFilter!!.setLut3DTexture(lutData.first, lutData.second)
            }
            gpuImageView.filter = activeFilter
        }
        gpuImageView.requestRender() 
    }

    private fun saveToGallery(sourceUri: Uri) {
        Toast.makeText(this, "正在处理并保存至相册...", Toast.LENGTH_SHORT).show()
        
        CoroutineScope(Dispatchers.IO).launch {
            if (originalBitmap == null) return@launch

            val processedBitmap = gpuImageView.gpuImage.bitmapWithFilterApplied

            val tempFile = File(cacheDir, "temp_export.jpg")
            FileOutputStream(tempFile).use { out ->
                processedBitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
            }

            // 拷贝 EXIF 并更新时间为当前时间
            copyAllExifAndUpdateTime(sourceUri, tempFile.absolutePath)

            val fileName = "LeicaLook_${System.currentTimeMillis()}.jpg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/LeicaLook")
                put(MediaStore.Images.Media.IS_PENDING, 1) 
            }

            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val imageUri = contentResolver.insert(collection, values)

            if (imageUri != null) {
                contentResolver.openOutputStream(imageUri).use { out ->
                    FileInputStream(tempFile).use { input ->
                        input.copyTo(out!!) 
                    }
                }
                
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                contentResolver.update(imageUri, values, null, null)

                // 强制媒体扫描器立刻扫描这个新文件，让相册立刻显示
                val projection = arrayOf(MediaStore.Images.Media.DATA)
                val cursor = contentResolver.query(imageUri, projection, null, null, null)
                if (cursor != null && cursor.moveToFirst()) {
                    val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                    val absoluteFilePath = cursor.getString(dataColumn)
                    MediaScannerConnection.scanFile(this@MainActivity, arrayOf(absoluteFilePath), arrayOf("image/jpeg"), null)
                    cursor.close()
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "成功！请打开相册查看最新照片", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun copyAllExifAndUpdateTime(sourceUri: Uri, destPath: String) {
        var inputStream: InputStream? = null
        try {
            inputStream = contentResolver.openInputStream(sourceUri)
            if (inputStream != null) {
                val oldExif = ExifInterface(inputStream)
                val newExif = ExifInterface(destPath)

                // 1. 先暴力拷贝所有原有信息（保留光圈、焦距、厂商数据等）
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

                // 2. 强制覆盖时间信息为当前设备时间 (这是解决相册不显示最新照片的核心)
                val currentTime = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.getDefault()).format(Date())
                newExif.setAttribute(ExifInterface.TAG_DATETIME, currentTime)
                newExif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, currentTime)
                newExif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, currentTime)
                
                newExif.saveAttributes()
            }
        } finally {
            inputStream?.close()
        }
    }
}
