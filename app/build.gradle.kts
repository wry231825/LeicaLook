dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    // EXIF 核心库，用于无损继承信息
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    
    // 推荐引入 GPUImage 库来简化繁琐的 OpenGL EGL 环境配置
    // 它底层依然是原生的 OpenGL ES，但省去了几百行的上下文搭建代码
    implementation("jp.co.cyberagent.android:gpuimage:2.1.0")
}
