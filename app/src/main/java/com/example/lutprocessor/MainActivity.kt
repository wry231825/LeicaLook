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
        gpuImageView
