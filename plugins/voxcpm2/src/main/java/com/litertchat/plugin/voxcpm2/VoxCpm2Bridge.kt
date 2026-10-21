// Ponko-EX · (c) 2026 visitor257 · MIT License · github.com/visitor257/Ponko
package com.litertchat.plugin.voxcpm2

/**
 * JNI 声明层。**方法名与签名改不得** —— 原生库里是编译期写死的符号名
 * （`Java_com_litertchat_plugin_voxcpm2_VoxCpm2Bridge_nativeXxx`）。
 *
 * 原生库从哪来：
 *   llama.cpp-omni 的 `voxcpm2_runtime` 静态库 + 自己写的 JNI 封装，
 *   用 NDK 27 交叉编译 arm64-v8a，strip 后 5.6 MB，只依赖 libc/libm/libdl/liblog。
 *   源码与编译命令见工程说明（本仓库不含源码，只放成品 .so）。
 *
 * 原生侧是**单例**的：任意时刻只有一个模型在内存里，`nativeGenerate` 会阻塞
 * 到整段语音生成完。所以：
 *   · 进度只能靠**另一个线程轮询** [nativeAudioSeconds] / [nativeStage]
 *   · 取消靠 [nativeCancel] 置标志，原生回调里检查后立刻中断解码
 */
internal object VoxCpm2Bridge {

    init {
        System.loadLibrary("voxcpm2jni")
    }

    /** @return 0 = 成功，1 = 失败（原因见 [nativeLastError]） */
    external fun nativeInit(baseLmPath: String, acousticPath: String, threads: Int): Int

    external fun nativeIsReady(): Boolean

    external fun nativeLastError(): String

    /** 模型原生采样率（VoxCPM2 是 48000） */
    external fun nativeSampleRate(): Int

    /** 置取消标志；正在跑的 [nativeGenerate] 会在下一个回调处退出 */
    external fun nativeCancel()

    /** 0=空闲 1=生成中 2=完成 3=已取消 4=出错 */
    external fun nativeStage(): Int

    /** 已生成了多少秒音频 —— 进度就是用这个算的 */
    external fun nativeAudioSeconds(): Double

    /**
     * 阻塞式合成，直接写出 48 kHz / 16-bit / 单声道 WAV。
     *
     * @param mode 0=纯 TTS（语音设计靠文本前缀）1=参考音频克隆 2=参考音频+转写
     * @param refWav 参考音频路径（16-bit PCM WAV），mode 0 传空串
     * @param promptText 参考音频的逐字稿，仅 mode 2 需要
     * @param cfg 引导强度，<=0 用默认 2.0
     * @param timesteps 扩散步数，<=0 用默认 10
     * @param seed 随机种子，0 = 随机
     * @param maxSteps 自回归步数上限，<=0 用默认 200
     */
    external fun nativeGenerate(
        text: String,
        mode: Int,
        refWav: String,
        promptText: String,
        cfg: Float,
        timesteps: Int,
        seed: Int,
        maxSteps: Int,
        outWav: String,
    ): Boolean

    external fun nativeFree()

    external fun nativeSystemInfo(): String

    // ---- nativeStage() 的取值，别和 PluginConst 的 Stage 混用 ----
    const val STAGE_IDLE = 0
    const val STAGE_GENERATING = 1
    const val STAGE_DONE = 2
    const val STAGE_CANCELLED = 3
    const val STAGE_ERROR = 4
}
