// VoiceCommandHandler.kt — 语音指令识别与处理
// 在用户语音发送给 AI 之前先进行本地指令匹配，快速响应
package com.gameai.ai

import android.content.Context
import android.content.Intent
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.util.regex.Pattern

/**
 * 语音指令处理器
 *
 * 在用户语音文本被送往 AI 对话模型之前，先用本地正则引擎快速匹配指令关键词。
 * 匹配到指令则立即执行本地动作 + 简短 TTS 反馈，不走 AI 对话流程。
 * 未匹配则透传给 AI 对话模型正常处理。
 *
 * 支持的指令分类：
 * - 游戏辅助控制：开始/暂停/停止 屏幕分析
 * - 对局管理：开始/结束对局、开始/结束语音对话
 * - 信息查询：查看评分/经济/对局时间/KDA
 * - 英雄相关：切换英雄、查看出装/技能
 * - 通用控制：帮助、关闭、暂停输入
 */
object VoiceCommandHandler {

    private const val TAG = "VoiceCmd"

    /** 命令类型 */
    enum class CommandType {
        // 分析控制
        START_ANALYSIS,
        STOP_ANALYSIS,
        PAUSE_ANALYSIS,
        RESUME_ANALYSIS,

        // 对局管理
        START_MATCH,
        END_MATCH,

        // 对话控制
        START_CONVERSATION,
        END_CONVERSATION,
        PAUSE_LISTENING,
        RESUME_LISTENING,

        // 信息查询（触发快速查看，不进入对话）
        QUERY_SCORE,
        QUERY_KDA,
        QUERY_ECONOMY,
        QUERY_TIME,

        // 英雄相关
        SWITCH_HERO,
        QUERY_BUILD,
        QUERY_SKILL,

        // 通用
        HELP,
        UNKNOWN
    }

    /** 解析出的指令 */
    data class ParsedCommand(
        val type: CommandType,
        val original: String,
        val param: String? = null   // 额外参数，如英雄名
    )

    /** 指令执行回调 */
    var onCommand: ((ParsedCommand) -> Boolean)? = null

    // 指令上下文
    private var currentHero: String = ""
    private var isAnalysisRunning: Boolean = false
    private var isInMatch: Boolean = false
    private var isConversationActive: Boolean = false

    /** 更新上下文 */
    fun setHero(hero: String) { currentHero = hero }
    fun setAnalysisRunning(running: Boolean) { isAnalysisRunning = running }
    fun setInMatch(inMatch: Boolean) { isInMatch = inMatch }
    fun setConversationActive(active: Boolean) { isConversationActive = active }

    // ============================================================
    //  指令库（优先级从高到低）
    // ============================================================

    private val commandPatterns = linkedMapOf<Pattern, CommandType>(

        // --- 对话控制 ---
        regex("(结束|停止|关闭|退出|退出语音).*(对话|交谈|聊天|话筒|语音)") to CommandType.END_CONVERSATION,
        regex("(暂停|停止|别说了|别吵|安静|闭嘴|先等等|等[一下])(输入|说话|对话|听|监听)") to CommandType.PAUSE_LISTENING,
        regex("(继续|接着|恢复)(说话|对话|输入|听|监听)") to CommandType.RESUME_LISTENING,
        regex("(打开|启动|开始|开启).*(语音|对话|交谈|聊天)") to CommandType.START_CONVERSATION,

        // --- 分析控制 ---
        regex("(开始|启动|打开|开启).*(分析|屏幕分析|AI分析|辅助|屏幕识别)") to CommandType.START_ANALYSIS,
        regex("(关掉|关闭|停止|结束|退出).*(分析|屏幕分析|AI分析|辅助)") to CommandType.STOP_ANALYSIS,
        regex("(暂停|暂时).*(分析|屏幕分析|AI分析|辅助)") to CommandType.PAUSE_ANALYSIS,
        regex("(继续|恢复|接着).*(分析|屏幕分析|AI分析|辅助)") to CommandType.RESUME_ANALYSIS,

        // --- 对局管理 ---
        regex("(开始|开启|启动|进入).*(对局|比赛|游戏|打)") to CommandType.START_MATCH,
        regex("(结束|退出|完成|打完|不打了).*(对局|比赛|游戏|这局)") to CommandType.END_MATCH,

        // --- 信息查询 ---
        regex("(查看|告诉我|多少|显示|现在是|当前).*(评分|分|分数)") to CommandType.QUERY_SCORE,
        regex("(查看|告诉我|多少|显示).*(KDA|战绩|击杀|死亡|助攻)") to CommandType.QUERY_KDA,
        regex("(查看|告诉我|多少|显示).*(经济|金币|钱|GPM)") to CommandType.QUERY_ECONOMY,
        regex("(查看|告诉我|多少|显示).*(时间|对局时间|打了多久)") to CommandType.QUERY_TIME,

        // --- 英雄相关 ---
        regex("(切换|换成|改用|换为|选|用|玩).*(英雄|角色)") to CommandType.SWITCH_HERO,
        regex("(查看|显示|告诉).*(出装|装备|推荐装|核心装|六神装)") to CommandType.QUERY_BUILD,
        regex("(查看|显示|告诉).*(技能|天赋|铭文|连招|召唤师技能)") to CommandType.QUERY_SKILL,

        // --- 帮助 ---
        regex("(帮助|帮助列表|help|能做什么|有什么功能|有哪些命令|不会用)") to CommandType.HELP,
        regex("(你能|你会|你可以?|你有什么).*(做|帮助|功能|能力|干什么)") to CommandType.HELP,
    )

    // ============================================================
    //  核心方法：解析语音文本 → 返回指令或 null
    // ============================================================

    /**
     * 尝试从用户语音文本中提取指令
     * @return ParsedCommand 匹配到指令；null 表示不是指令，应发给 AI 对话模型
     */
    fun tryParseCommand(text: String): ParsedCommand? {
        val trimmed = text.trim().lowercase()

        for ((pattern, type) in commandPatterns) {
            val matcher = pattern.matcher(trimmed)
            if (matcher.find()) {
                // 提取参数（如果有）
                val param = try {
                    matcher.group(1)  // 第一个捕获组通常是参数
                } catch (_: Exception) { null }

                // 语境感知校验
                val validatedType = validateCommandInContext(type)

                if (validatedType != CommandType.UNKNOWN) {
                    android.util.Log.i(TAG, "识别到指令: $type → 原文: \"$text\"")
                    return ParsedCommand(validatedType, text, param)
                }
            }
        }

        return null  // 不是指令，透传给 AI
    }

    /**
     * 语境感知：根据当前状态调整指令语义
     * 例如："开始分析" 在分析已运行时 → 不需要执行
     */
    private fun validateCommandInContext(type: CommandType): CommandType {
        return when (type) {
            CommandType.START_ANALYSIS -> if (isAnalysisRunning) CommandType.UNKNOWN else type
            CommandType.STOP_ANALYSIS -> if (!isAnalysisRunning) CommandType.UNKNOWN else type
            CommandType.PAUSE_ANALYSIS -> if (!isAnalysisRunning) CommandType.UNKNOWN else type
            CommandType.RESUME_ANALYSIS -> if (isAnalysisRunning) CommandType.UNKNOWN else type
            CommandType.END_CONVERSATION -> if (!isConversationActive) CommandType.UNKNOWN else type
            CommandType.PAUSE_LISTENING -> if (!isConversationActive) CommandType.UNKNOWN else type
            else -> type
        }
    }

    // ============================================================
    //  指令执行：生成执行结果文本
    // ============================================================

    /**
     * 执行指令并返回给用户的反馈文字（供 TTS 播报 + UI 显示）
     */
    fun executeCommand(cmd: ParsedCommand): String {
        // 通知外部回调
        val handled = onCommand?.invoke(cmd) ?: false

        return when (cmd.type) {
            CommandType.START_ANALYSIS ->
                if (handled) "好的，已启动屏幕分析" else "正在启动屏幕分析..."

            CommandType.STOP_ANALYSIS ->
                if (handled) "已停止屏幕分析" else "屏幕分析已停止"

            CommandType.PAUSE_ANALYSIS ->
                if (handled) "分析已暂停" else "屏幕分析已暂停"

            CommandType.RESUME_ANALYSIS ->
                if (handled) "继续分析屏幕" else "屏幕分析已恢复"

            CommandType.START_MATCH ->
                if (handled) "对局已开始，加油！" else "准备开始对局..."

            CommandType.END_MATCH ->
                if (handled) "对局结束，正在生成报告" else "对局已结束"

            CommandType.END_CONVERSATION ->
                if (handled) "好的，语音对话已结束" else "已退出语音对话"

            CommandType.PAUSE_LISTENING ->
                if (handled) "好的，我先不听了。说\"继续听\"我就回来" else "聆听已暂停，说\"继续听\"恢复"

            CommandType.RESUME_LISTENING ->
                if (handled) "我在呢，请说吧" else "聆听已恢复"

            CommandType.QUERY_SCORE ->
                "当前评分信息请在悬浮球上查看哦"

            CommandType.QUERY_KDA ->
                "KDA 信息正在采集中，请稍候"

            CommandType.QUERY_ECONOMY ->
                "经济数据采集中，可以在 Dashboard 查看"

            CommandType.QUERY_TIME ->
                "对局时间显示在 Dashboard 页面上"

            CommandType.SWITCH_HERO -> {
                val hero = cmd.param ?: cmd.original
                if (handled) "已切换到 ${hero}" else "请先指定英雄名称"
            }

            CommandType.QUERY_BUILD ->
                if (handled) "出装建议已显示" else "请在游戏页面查看推荐出装"

            CommandType.QUERY_SKILL ->
                if (handled) "技能信息已显示" else "请查看英雄详情页"

            CommandType.HELP ->
                buildHelpText()

            else -> "你好，我没有理解这个指令。说\"帮助\"查看我能做什么"
        }
    }

    /** 获取命令的简短提示音文字（很短的确认词） */
    fun getCommandAck(cmd: ParsedCommand): String {
        return when (cmd.type) {
            CommandType.START_ANALYSIS -> "分析已开"
            CommandType.STOP_ANALYSIS -> "分析已关"
            CommandType.PAUSE_ANALYSIS -> "已暂停"
            CommandType.RESUME_ANALYSIS -> "已恢复"
            CommandType.START_MATCH -> "对局开始"
            CommandType.END_MATCH -> "对局结束"
            CommandType.END_CONVERSATION -> "好的再见"
            CommandType.PAUSE_LISTENING -> "好的"
            CommandType.RESUME_LISTENING -> "在的"
            CommandType.HELP -> "功能说明"
            else -> "明白"
        }
    }

    // ============================================================
    //  广播
    // ============================================================

    const val ACTION_VOICE_COMMAND = "com.gameai.VOICE_COMMAND"
    const val EXTRA_COMMAND_TYPE = "command_type"
    const val EXTRA_COMMAND_PARAM = "command_param"

    fun broadcastCommand(context: Context, cmd: ParsedCommand) {
        val intent = Intent(ACTION_VOICE_COMMAND).apply {
            putExtra(EXTRA_COMMAND_TYPE, cmd.type.name)
            putExtra(EXTRA_COMMAND_PARAM, cmd.param ?: "")
        }
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
    }

    // ============================================================
    //  帮助文本
    // ============================================================

    private fun buildHelpText(): String {
        val sb = StringBuilder("我能帮你做这些事：\n\n")
        sb.append("🎮 游戏控制：\n")
        sb.append("  · \"开始分析\" / \"停止分析\" — 控制屏幕分析\n")
        sb.append("  · \"开始对局\" / \"结束对局\" — 管理游戏对局\n")
        sb.append("  · \"切换英雄\" — 切换当前英雄\n\n")
        sb.append("🎤 对话控制：\n")
        sb.append("  · \"暂停听\" — 暂停语音聆听\n")
        sb.append("  · \"继续说话\" — 恢复语音聆听\n")
        sb.append("  · \"结束对话\" — 退出语音对话\n\n")
        sb.append("📊 信息查询：\n")
        sb.append("  · \"查看评分\" / \"查看KDA\" / \"查看经济\"\n")
        sb.append("  · \"查看出装\" / \"查看技能\"\n")
        return sb.toString()
    }

    // ============================================================
    //  工具
    // ============================================================

    private fun regex(pattern: String): Pattern = Pattern.compile(pattern)
}
