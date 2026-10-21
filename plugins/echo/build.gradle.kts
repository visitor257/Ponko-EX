// Ponko-EX · (c) 2026 visitor257 · MIT License · github.com/visitor257/Ponko
//
// 主线测试插件：Echo。
//
// **不加载任何模型、不含任何 .so**，合成时生成一段 C5-E5-G5 琶音回传。
// 用途是验证「发现 → 绑定 → 版本校验 → 进度回调 → 音频 FD 跨进程回传 → 解绑」整条链路。
//
// 它也是各模型插件的模板：把 EchoPluginService.synthesizeBlocking 里的琶音生成
// 换成真正的推理调用即可，其余（Manifest / 保活 / 错误映射 / 状态机）都在 SDK 基类里。
plugins {
    id("com.android.application")
}

android {
    namespace = "com.litertchat.plugin.echo"
    compileSdk = 36

    defaultConfig {
        // ⚠ 必须与宿主 Ponko（com.litertchat.app）不同包名，否则不能共存
        applicationId = "com.litertchat.plugin.echo"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
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
}

dependencies {
    // 只依赖 SDK 的 AAR（由 LiteRT-Chat 发布到 C:\ponko-maven）。
    // **不能**用 project(":ponko-plugin-sdk") —— 那是宿主工程内的路径，跨项目不可用。
    implementation("com.litertchat.ponko:plugin-sdk:1.0.0")
}
