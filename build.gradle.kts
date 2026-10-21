// Ponko-EX · (c) 2026 visitor257 · MIT License · github.com/visitor257/Ponko
// Top-level build file
buildscript {
    dependencies {
        // 与宿主 Ponko 保持同一 KGP（宿主用 2.4.20 编译 SDK，
        // 版本不一致时插件侧会报 metadata 不兼容）
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
}

plugins {
    id("com.android.application") version "9.4.0" apply false
}
