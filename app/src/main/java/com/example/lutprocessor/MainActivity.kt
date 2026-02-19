package com.example.lutprocessor

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.exifinterface.media.ExifInterface
import jp.co.cyberagent.android.gpuimage.GPUImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

class MainActivity : AppCompatActivity() {

    private lateinit var imageViewPreview: ImageView
    private lateinit var spinnerLut: Spinner
    private lateinit var seekBarIntensity: SeekBar
    private var currentUri: Uri? = null
    private var originalBitmap: Bitmap? = null

    private val lutPresets = arrayOf("无", "Contemporary.CUBE", "Sepia.CUBE", "Selenium.CUBE", "Eternal.CUBE", "Blue.CUBE", "Classic.CUBE")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        imageViewPreview = findViewById(R.id.imageView_preview)
        spinnerLut = findViewById(R.id.spinner_lut)
        seekBarIntensity = findViewById(R.id.seekBar_intensity)
        val btnLoad = findViewById<Button>(R.id.btn_load)
        val btnSave = findViewById<Button>(R.id.btn_save)

        spinnerLut.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, lutPresets)

        val pickImage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let {
                currentUri = it
                loadImage(it)
            }
        }

        btnLoad.setOnClickListener { pickImage.launch(arrayOf("image/jpeg", "image/x-adobe-dng")) }
        
        btnSave.setOnClickListener {
            currentUri?.let { uri ->
                val selectedLut = spinnerLut.selectedItem.toString()
                val intensity = seekBarIntensity.progress / 100f
                Toast.makeText(this, "正在处理并保存...", Toast.LENGTH_SHORT).show()
                processAndSaveImage(uri, selectedLut, intensity)
            }
        }
    }

    private fun loadImage(uri: Uri) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val source = ImageDecoder.createSource(contentResolver, uri)
                originalBitmap = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
                    decoder.isMutableRequired = true
                }
                withContext(Dispatchers.Main) {
                    imageViewPreview.setImageBitmap(originalBitmap)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun processAndSaveImage(sourceUri: Uri, lutName: String, intensity: Float) {
        CoroutineScope(Dispatchers.IO).launch {
            if (originalBitmap == null) return@launch

            val processedBitmap: Bitmap = if (lutName == "无" || intensity == 0f) {
                originalBitmap!!
            } else {
                val gpuImage = GPUImage(this@MainActivity)
                gpuImage.setImage(originalBitmap)
                val lutFilter = GlLutFilter()
                lutFilter.intensity = intensity
                
                val lutData = LutParser.parseCube(this@MainActivity, lutName)
                if (lutData != null) {
                    lutFilter.setLut3DTexture(lutData.first, lutData.second)
                }
                gpuImage.setFilter(lutFilter)
                gpuImage.bitmapWithFilterApplied
            }

            val outputFile = File(getExternalFilesDir(null), "EXPORT_${System.currentTimeMillis()}.jpg")
            FileOutputStream(outputFile).use { out ->
                processedBitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
            }

            // 暴力全量拷贝 EXIF (确保包含厂商自定义标签)
            copyAllExif(sourceUri, outputFile.absolutePath)

            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "已保存至: ${outputFile.absolutePath}", Toast.LENGTH_LONG).show()
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
