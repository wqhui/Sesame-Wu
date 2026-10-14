package com.surexu.sesame.ui.miuix

import android.content.Intent
import android.os.Bundle
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Upload
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.surexu.sesame.util.FileUtil
import com.surexu.sesame.util.ToastUtil
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.File

/**
 * 二级日志页支持的日志类型(与一级页面日志类目一一对应)。
 * 一级页点击哪个类目,二级页就只展示该类。
 */
enum class LogType(val displayName: String) {
    FOREST("森林记录"),
    GOLDENBEANS("金豆记录"),
    FARM("庄园/农场/新村"),
    MEMBER("会员记录"),
    OTHER("其他记录"),
    DEBUG("抓包记录"),
    ERROR("查看异常日志"),
    RUNTIME("查看运行日志");

    /** 每次访问都重新取当日文件,避免跨天后路径过期 */
    val file: File
        get() = when (this) {
            FOREST -> FileUtil.getForestLogFile()
            GOLDENBEANS -> FileUtil.getGoldenBeansLogFile()
            FARM -> FileUtil.getFarmLogFile()
            MEMBER -> FileUtil.getMemberLogFile()
            OTHER -> FileUtil.getOtherLogFile()
            DEBUG -> FileUtil.getDebugLogFile()
            ERROR -> FileUtil.getErrorLogFile()
            RUNTIME -> FileUtil.getRuntimeLogFile()
        }

    companion object {
        /** 一级页跳转时携带的 extra key,值为 LogType.name */
        const val EXTRA_LOG_TYPE = "sesame_log_type"

        fun fromIntent(intent: Intent?): LogType {
            val name = intent?.getStringExtra(EXTRA_LOG_TYPE)
            return entries.firstOrNull { it.name == name } ?: RUNTIME
        }
    }
}

/** 单条日志条目(可能含多行正文) */
data class LogEntry(
    val lineNumber: Int,
    val time: String?,
    val tag: String?,
    val body: String
)

class MiuixLogViewerActivity : MiuixBaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setAppContent {
            LogScreen(this, LogType.fromIntent(intent))
        }
    }
}

/**
 * 日志详情页:展示指定类目的全部条目卡片。
 * 仿 LSPosed 日志界面:每条目一张卡(标签 + 时间 + 正文)。
 *
 * 实时刷新:页面打开期间每 2 秒重读当日日志文件,新条目自动出现。
 * 列表为**倒序**(最新在最上):打开即看到最新一条,无需初始滚动。
 * 新条目插在头部,用户向下翻历史时不会被拽走;
 * 只有用户本来就停在顶部时才自动跟随最新,滚回顶部即恢复跟随。
 * 正文支持长按选中复制。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LogScreen(activity: MiuixLogViewerActivity, logType: LogType) {
    val context = LocalContext.current
    // 只在进入时取一次文件:LogType.file 的 getter 内部有磁盘 I/O(exists/createNewFile 等),
    // 不能放在组合里每次重组都求值
    val file = remember(logType) { logType.file }
    var entries by remember(logType) { mutableStateOf(loadLogEntries(file)) }
    val listState = rememberLazyListState()
    // 倒序展示:最新的日志在最上面。asReversed() 是 O(1) 的视图,不会复制列表
    val displayEntries = entries.asReversed()

    // 轮询刷新:打开期间每 2 秒重读文件,内容有变化则更新列表。
    // 新条目插在列表头部,用户向下翻历史时不会被拽走;仅当用户本来就停在顶部时才跟随最新。
    LaunchedEffect(file) {
        while (isActive) {
            delay(2000)
            val fresh = loadLogEntries(file)
            val old = entries
            if (fresh.size != old.size ||
                (fresh.isNotEmpty() && old.isNotEmpty() && fresh.last() != old.last())
            ) {
                // 必须在写入 entries 之前判定:写入后 LazyColumn 会按 key 锚定当前可见项,
                // 把 firstVisibleItemIndex 从 0 顶到 1,那时再读就永远判断不出"原本停在顶部"了。
                val wasAtTop = listState.firstVisibleItemIndex == 0 &&
                    listState.firstVisibleItemScrollOffset == 0
                entries = fresh
                if (wasAtTop && fresh.isNotEmpty()) {
                    // 非挂起 API:把"顶部"登记给下一次测量,并跳过 key 锚定。
                    // 不阻塞轮询,也不会和用户的手势抢滚动。
                    listState.requestScrollToItem(0)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            LogTopBar(
                title = logType.displayName + " · 实时",
                onBack = { activity.finish() },
                onExport = {
                    val exported = FileUtil.exportFile(file)
                    if (exported != null) {
                        ToastUtil.show(context, "已导出: " + exported.path)
                    } else {
                        ToastUtil.show(context, "导出失败")
                    }
                },
                onClear = {
                    if (FileUtil.clearFile(file)) {
                        entries = loadLogEntries(file)
                        ToastUtil.show(context, "已清空")
                    }
                }
            )
        },
        containerColor = MiuixTheme.colorScheme.surface
    ) { padding ->
        if (entries.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "(空)",
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                itemsIndexed(
                    items = displayEntries,
                    key = { _, e -> "${e.lineNumber}-${e.hashCode()}" }
                ) { _, entry ->
                    LogEntryCard(entry)
                }
            }
        }
    }
}

/** 单条日志卡片:标题(TAG)+ 右上时间戳 + 下方正文 */
@Composable
fun LogEntryCard(entry: LogEntry) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MiuixTheme.colorScheme.surfaceContainer)
            .padding(12.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = entry.tag ?: "日志",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.primary
            )
            Text(
                text = entry.time ?: "",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
        if (entry.body.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            // 正文可长按选中复制,方便把日志贴到 issue / 群里
            SelectionContainer {
                Text(
                    text = entry.body,
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onBackground
                )
            }
        }
    }
}

/**
 * 通用顶部栏:返回图标 + 可选的操作图标(导入/导出/删除) + 横向 marquee 滚动的标题。
 * 仿 LSPosed 日志页样式。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LogTopBar(
    title: String,
    onBack: () -> Unit,
    onImport: (() -> Unit)? = null,
    onExport: (() -> Unit)? = null,
    onClear: (() -> Unit)? = null
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MiuixTheme.colorScheme.surface)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = MiuixTheme.colorScheme.onBackground
                )
            }
            Text(
                text = title,
                modifier = Modifier
                    .weight(1f)
                    .basicMarquee(),
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onBackground,
                maxLines = 1
            )
            if (onImport != null) {
                IconButton(onClick = onImport) {
                    // 导入图标:把 Upload 旋转 180°(朝下)与导出(朝上)区分
                    Icon(
                        imageVector = Icons.Filled.Upload,
                        contentDescription = "导入",
                        tint = MiuixTheme.colorScheme.onBackground,
                        modifier = Modifier.rotate(180f)
                    )
                }
            }
            if (onExport != null) {
                IconButton(onClick = onExport) {
                    Icon(
                        imageVector = Icons.Filled.Upload,
                        contentDescription = "导出",
                        tint = MiuixTheme.colorScheme.onBackground
                    )
                }
            }
            if (onClear != null) {
                IconButton(onClick = onClear) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "删除",
                        tint = MiuixTheme.colorScheme.onBackground
                    )
                }
            }
        }
    }
}

/** 读取日志文件并按行解析为条目;无时间戳的行合并到上一条(多行日志聚合为同一卡片) */
private fun loadLogEntries(file: File?): List<LogEntry> {
    if (file == null || !file.exists()) {
        return emptyList()
    }
    val timeRegex = Regex("^(\\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+(\\w+):\\s*(.*)$")
    val entries = mutableListOf<LogEntry>()
    return try {
        file.useLines { lines ->
            var lineNumber = 0
            lines.forEach { line ->
                lineNumber++
                val match = timeRegex.find(line)
                if (match != null) {
                    entries.add(
                        LogEntry(
                            lineNumber = lineNumber,
                            time = match.groupValues[1],
                            tag = match.groupValues[2],
                            body = match.groupValues[3]
                        )
                    )
                } else {
                    // 无时间戳:视为上一条的续行,合并到同卡片
                    if (entries.isNotEmpty()) {
                        val last = entries.removeAt(entries.size - 1)
                        entries.add(last.copy(body = last.body + "\n" + line))
                    } else {
                        entries.add(LogEntry(lineNumber, null, null, line))
                    }
                }
            }
            entries
        }
    } catch (e: Throwable) {
        emptyList()
    }
}