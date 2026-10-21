// Ponko · (c) 2026 visitor257 · MIT License · github.com/visitor257/Ponko
package com.litertchat.plugin.echo

import com.litertchat.plugin.PonkoActivatorActivity

/**
 * 无界面的激活组件。
 *
 * 它唯一的作用是：让系统把本应用从 **stopped 状态**里放出来 ——
 * 那个状态下别的应用 `bindService` 到本应用的服务会**静默返回 false**，
 * 而解除方式只有「本应用的某个 Activity 被启动一次」。
 *
 * 继承 [PonkoActivatorActivity] 之后：不画界面、不加转场动画、启动即关闭，
 * 而且因为 Manifest 里**没有声明 `CATEGORY_LAUNCHER`**，桌面上也不会出现图标。
 * 用户只能在「系统设置 → 应用」里看到它，和系统组件一样。
 */
class EchoActivator : PonkoActivatorActivity()
