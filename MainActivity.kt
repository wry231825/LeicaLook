import androidx.exifinterface.media.ExifInterface

private fun copyAllExif(sourcePath: String, destPath: String) {
    val oldExif = ExifInterface(sourcePath)
    val newExif = ExifInterface(destPath)

    // 反射获取 ExifInterface 中所有以 "TAG_" 开头的常量
    val fields = ExifInterface::class.java.fields
    for (field in fields) {
        if (field.name.startsWith("TAG_")) {
            try {
                val tag = field.get(null) as? String
                if (tag != null) {
                    val value = oldExif.getAttribute(tag)
                    if (value != null) {
                        // 特殊处理：图像的宽高在应用滤镜后可能发生极微小变化，
                        // 但你要求“尺寸按照原来的格式来”，所以直接硬拷原图所有数据。
                        newExif.setAttribute(tag, value)
                    }
                }
            } catch (e: Exception) {
                // 忽略反射异常
            }
        }
    }
    newExif.saveAttributes()
}
