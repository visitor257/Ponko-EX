// Ponko-EX · (c) 2026 visitor257 · MIT License · github.com/visitor257/Ponko
//
// 插件线：Ponko-EX_VoxCPM2
//
// 把 VoxCPM2（OpenBMB，2B 语音合成）接进 Ponko。
// APK 里**只有推理引擎**（libvoxcpm2jni.so，5.6 MB），权重在插件内按需下载。
//
// 许可：本插件 MIT；引擎 llama.cpp-omni/ggml 为 MIT；权重 VoxCPM2 为 Apache-2.0。
// 详见同目录 NOTICE.md 与插件界面里的「许可」一节。
plugins {
    id("com.android.application")
}

android {
    namespace = "com.litertchat.plugin.voxcpm2"
    compileSdk = 36

    defaultConfig {
        // ⚠ 与宿主 com.litertchat.app 必须不同，否则不能共存
        applicationId = "com.litertchat.plugin.voxcpm2"
        minSdk = 28
        targetSdk = 36
        versionCode = 20
        versionName = "0.7.5"

        ndk {
            // 引擎只有 arm64 版（NDK 交叉编译产物），x86 平板装不了
            abiFilters += "arm64-v8a"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    packaging {
        jniLibs {
            // 和宿主那边同样的理由：不开这个，AGP 会给未压缩的 .so 补 4K 对齐的
            // 零填充，5.6 MB 的 so 能让包多出 5.7 MB。纯属浪费。
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // 只依赖 SDK 的 AAR（由 LiteRT-Chat 发布到 C:\ponko-maven）
    implementation("com.litertchat.ponko:plugin-sdk:1.0.0")
}
