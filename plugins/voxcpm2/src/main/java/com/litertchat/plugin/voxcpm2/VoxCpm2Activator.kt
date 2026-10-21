// Ponko-EX · (c) 2026 visitor257 · MIT License · github.com/visitor257/Ponko
package com.litertchat.plugin.voxcpm2

import com.litertchat.plugin.PonkoActivatorActivity

/**
 * 无界面激活组件。
 *
 * 一行就够 —— 判断「是不是只为激活而来」、立刻 finish()、不绘制任何东西，
 * 全在 [PonkoActivatorActivity] 里。桌面没有图标是 Manifest 的事
 * （这个 Activity 没挂 MAIN/LAUNCHER，只挂自定义的 ACTIVATOR action）。
 */
class VoxCpm2Activator : PonkoActivatorActivity()
