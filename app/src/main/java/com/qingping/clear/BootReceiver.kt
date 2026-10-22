package com.qingping.clear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机自启接收器
 *
 * 收到 BOOT_COMPLETED 后启动保活服务。
 * 注意：无障碍服务本身不会随开机自动开启——
 * 安卓系统出于安全考虑，用户手动关闭/重启手机后，
 * 无障碍服务需要用户重新去设置里手动开启一次。
 * 这个接收器只能做到：启动前台保活服务，让APP进程存活。
 *
 * 但如果用户之前已经在系统设置里开启了无障碍服务，
 * 大部分国产ROM在开机后会自动恢复无障碍服务状态。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                context?.let { KeepAliveService.start(it) }
            }
        }
    }
}
