// RomCompatHelper.kt - 国产 ROM 兼容性辅助工具
package com.gameai.utils

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

/**
 * 国产 ROM 兼容性辅助工具
 *
 * 处理不同厂商对悬浮窗、自启动、后台运行的不同权限策略：
 * - 小米 (MIUI) / 华为 (EMUI) / OPPO (ColorOS) / vivo (OriginOS)
 * - 三星 (One UI) / 魅族 (Flyme)
 */
object RomCompatHelper {

    enum class RomType {
        MIUI,       // 小米
        EMUI,       // 华为
        COLOROS,    // OPPO
        ORIGINOS,   // vivo
        FLYME,      // 魅族
        ONEUI,      // 三星
        UNKNOWN     // 原生/其他
    }

    data class RomGuide(
        val name: String,
        val floatingWindowGuide: String,
        val autoStartIntent: Intent? = null,
        val batteryOptimizationGuide: String = ""
    )

    /** 检测当前 ROM 类型 */
    fun detectRomType(): RomType {
        val brand = Build.BRAND.uppercase()
        val manufacturer = Build.MANUFACTURER.uppercase()

        return when {
            brand.contains("XIAOMI") || brand.contains("REDMI") ||
                manufacturer.contains("XIAOMI") -> RomType.MIUI
            brand.contains("HUAWEI") || brand.contains("HONOR") ||
                manufacturer.contains("HUAWEI") -> RomType.EMUI
            brand.contains("OPPO") || brand.contains("ONEPLUS") ||
                brand.contains("REALME") || manufacturer.contains("OPPO") -> RomType.COLOROS
            brand.contains("VIVO") || brand.contains("IQOO") ||
                manufacturer.contains("VIVO") -> RomType.ORIGINOS
            brand.contains("MEIZU") || manufacturer.contains("MEIZU") -> RomType.FLYME
            brand.contains("SAMSUNG") || manufacturer.contains("SAMSUNG") -> RomType.ONEUI
            else -> RomType.UNKNOWN
        }
    }

    /** 获取当前 ROM 的权限引导信息 */
    fun getRomGuide(context: Context): RomGuide {
        val pkg = context.packageName

        return when (detectRomType()) {
            RomType.MIUI -> RomGuide(
                name = "小米 MIUI",
                floatingWindowGuide = "设置 → 应用设置 → 应用管理 → LookGm → 权限管理 → 显示悬浮窗",
                autoStartIntent = Intent().apply {
                    component = ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"
                    )
                },
                batteryOptimizationGuide = "设置 → 省电与电池 → 应用智能省电 → LookGm → 无限制"
            )

            RomType.EMUI -> RomGuide(
                name = "华为 EMUI",
                floatingWindowGuide = "设置 → 应用 → 权限管理 → LookGm → 悬浮窗",
                autoStartIntent = Intent().apply {
                    component = ComponentName(
                        "com.huawei.systemmanager",
                        "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                    )
                },
                batteryOptimizationGuide = "设置 → 应用 → 应用启动管理 → LookGm → 手动管理（全部开启）"
            )

            RomType.COLOROS -> RomGuide(
                name = "OPPO ColorOS",
                floatingWindowGuide = "设置 → 应用 → 应用管理 → LookGm → 悬浮窗",
                autoStartIntent = Intent().apply {
                    component = ComponentName(
                        "com.coloros.safecenter",
                        "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                    )
                },
                batteryOptimizationGuide = "设置 → 电池 → 应用耗电管理 → LookGm → 允许后台运行"
            )

            RomType.ORIGINOS -> RomGuide(
                name = "vivo OriginOS",
                floatingWindowGuide = "设置 → 应用与权限 → 权限管理 → 悬浮窗 → LookGm",
                autoStartIntent = Intent().apply {
                    component = ComponentName(
                        "com.vivo.permissionmanager",
                        "com.vivo.permissionmanager.activity.SoftPermissionDetailActivity"
                    ).apply {
                        putExtra("packagename", pkg)
                    }
                },
                batteryOptimizationGuide = "设置 → 电池 → 后台高耗电 → LookGm → 允许"
            )

            RomType.FLYME -> RomGuide(
                name = "魅族 Flyme",
                floatingWindowGuide = "设置 → 应用管理 → LookGm → 权限管理 → 悬浮窗",
                batteryOptimizationGuide = "手机管家 → 权限管理 → 后台管理 → LookGm → 保持后台运行"
            )

            RomType.ONEUI -> RomGuide(
                name = "三星 One UI",
                floatingWindowGuide = "设置 → 应用程序 → LookGm → 在其他应用程序上显示",
                batteryOptimizationGuide = "设置 → 设备维护 → 电池 → 未监控的应用程序 → 添加 LookGm"
            )

            RomType.UNKNOWN -> RomGuide(
                name = "标准 Android",
                floatingWindowGuide = "设置 → 应用 → 特殊应用权限 → 在其他应用上显示 → LookGm",
                batteryOptimizationGuide = "设置 → 电池 → 电池优化 → LookGm → 不优化"
            )
        }
    }

    /** 尝试跳转到 ROM 特定的自启动管理页 */
    fun openAutoStartSettings(context: Context): Boolean {
        val intent = getRomGuide(context).autoStartIntent
        return if (intent != null) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            } catch (e: Exception) {
                false
            }
        } else {
            false
        }
    }

    /** 跳转到电池优化设置页 */
    fun openBatteryOptimizationSettings(context: Context): Boolean {
        return try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = android.net.Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 构建 ROM 特定的完整权限引导文本 */
    fun buildPermissionGuideText(context: Context): String {
        val guide = getRomGuide(context)
        return buildString {
            appendLine("📱 检测到 ${guide.name} 系统")
            appendLine()
            appendLine("为确保悬浮球持续运行，请确认以下权限：")
            appendLine()
            appendLine("① 悬浮窗权限")
            appendLine(guide.floatingWindowGuide)
            appendLine()
            if (guide.autoStartIntent != null) {
                appendLine("② 自启动权限（防止被系统杀死）")
                appendLine("请在自启动管理中将 LookGm 设为允许")
                appendLine()
            }
            appendLine("③ 电池优化")
            appendLine(guide.batteryOptimizationGuide)
            appendLine()
            appendLine("④ 后台运行")
            appendLine("多任务界面锁定 LookGm，防止滑动清理")
            appendLine()
            appendLine("⑤ 锁屏保持")
            appendLine("关闭对 LookGm 的锁屏清理策略")
        }
    }
}
