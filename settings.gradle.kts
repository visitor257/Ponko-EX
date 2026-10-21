// Ponko-EX · (c) 2026 visitor257 · MIT License · github.com/visitor257/Ponko
//
// Ponko 插件项目（独立于宿主）。
//
// 为什么单开一个项目：
//   · 插件不该跟宿主一起发版 —— 宿主一年发几次，插件按需发
//   · 插件只依赖 SDK 的 AAR，不依赖宿主的任何实现代码
//   · 一条线一个模型家族，互不干扰
//
// 线（分支）：
//   main            只有文档 + 这份构建骨架，**没有任何插件模块**
//   Ponko-EX_test   + plugins/echo（Echo 测试插件）
//   以后每条插件线自己 include 自己的 module
//
// ⚠ 在 main 上 include 任何东西都要谨慎：
//   main 的定位是"文档和约定"，不是"随时能编出 APK"。
//   要构建就 git checkout Ponko-EX_test（或你自己的插件线）。
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Ponko 插件 SDK 的本地 maven 仓库（由宿主项目发布）
        //   发布命令：在宿主的构建沙箱里跑
        //     gradle :ponko-plugin-sdk:publishReleasePublicationToLocalRepoRepository
        // ⚠ 用 ASCII 路径 —— AGP 拒绝中文路径
        maven {
            url = uri("file:///C:/ponko-maven")
            isAllowInsecureProtocol = false
        }
    }
}
rootProject.name = "Ponko-EX"

// ---- 模块 ----
//
// 主线（main）**故意没有 include**。这不是漏写 ——
// 测试插件在 Ponko-EX_test 线上，那条线自己 include。
//
// 加一条新插件线时：
//   include(":plugins:<名字>")
