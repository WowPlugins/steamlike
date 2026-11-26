package com.steamlike.controller.core

/**
 * 运行中的 [SteamInput] 实例的全局访问点。
 *
 * 由 [com.steamlike.controller.service.ControllerOverlayService] 在启动映射时赋值、
 * 服务销毁时清空。设置界面在服务未运行时拿到 null，改为读写已保存的配置文件。
 *
 * 不要把 SteamInput 存进某个 Activity 的静态字段：Activity 会被重建，
 * 静态字段的生命周期与 UI 绑定会让「服务是否在运行」变得不可判断。
 */
object SteamInputHolder { // object：进程内单例
    @Volatile // 服务线程写、UI 线程读，保证可见性
    var current: SteamInput? = null // 当前运行中的控制器实例（null = 服务未运行）
}
