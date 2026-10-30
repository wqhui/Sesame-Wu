package com.sesame.wu.ui.miuix

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Upload
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sesame.wu.util.FileUtil
import com.sesame.wu.util.ToastUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import kotlin.coroutines.coroutineContext

/**
 * 日志详情页（分块读取 + 倒序版）。
 *
 * ## 为什么要分块
 * 原来用 `file.useLines { ... }` 把整个文件读成 String 列表再逐行 new LogEntry：
 * 一天的运行日志几 MB / 几万行，就是几万个对象常驻内存，解析还全在主线程上，
 * 结果是 OOM 或界面直接卡死。现在改成：
 * - 从文件**末尾**往前按 16KB 一块扫描，只定位「每条日志在文件里的起止偏移」；
 * - 内存里每条只占两个 Long（[LogEntryRef]），正文滑到哪读到哪；
 * - 正文用 LRU 缓存最多 512 条，滑出窗口就丢掉；
 * - 单行超过 16KB、单条超过 128KB 的部分截断展示，避免一条巨型 JSON 撑爆行高。
 *
 * ## 为什么倒序
 * 用户打开日志页几乎都是为了看**最新**的几条，而日志量一大，正序就得先读完整个文件再滚到底。
 * 倒序后从 EOF 往前读，第一屏就是最新日志，既不用全量读也不用初始滚动。
 *
 * 倒序展示 = 内存里仍按时间正序（旧 → 新）保存条目，UI 用 `asReversed()` 反转成「新 → 旧」。
 * 这样：
 * - 新日志追加到列表尾部，倒序后出现在**顶部**；
 * - 用户向上翻历史时不会被新日志拽走；
 * - 只有用户本来停在顶部（最新）时，才跟随新日志滚到顶部。
 *
 * 实时刷新：页面打开期间每 2 秒检查一次文件长度，只有变长才增量补读新行，不做全量重扫。
 *
 * 正文支持长按选中复制；顶部保留 导入/导出/删除 与搜索入口。
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

/**
 * 解析后的一条日志（可能含多行正文）。
 *
 * [start] 是它在文件中的字节位置，同时用作 LazyColumn 的稳定 key；
 * 正文由 [MiuixLogViewerActivity] 页面按需读取后填充，界面拿到之前只显示占位。
 */
data class LogEntry(
    val start: Long,
    val time: String?,
    val tag: String?,
    val body: String
)

/**
 * 一条日志在文件中的位置（不持有正文）。
 *
 * 为什么只记位置：一天的运行日志可能几 MB、几万行，几万个 String 直接把内存打满；
 * 只存偏移每条 16 字节，滑到哪读到哪，内存占用与文件大小基本无关。
 */
private data class LogEntryRef(
    /** 条目首行（时间戳行）的行首偏移 */
    val start: Long,
    /** 条目结束偏移（下一条目首行 / 文件末尾），读取正文时用它兜住范围 */
    val end: Long
)

/** 分块读取的块大小：与 AG 的 8KB 同量级，这里是 16KB，够摊薄随机读开销又不占内存 */
private const val READ_BUFFER_BYTES = 16 * 1024

/** 判断“这一行是不是时间戳行”只需要行首几个字节，不读整行 */
private const val HEADER_PREFIX_BYTES = 256

/** 超长行截断阈值（AG 同值）：单行最多展示 16KB，防止一行巨型 JSON 卡死排版 */
private const val MAX_DISPLAY_LINE_BYTES = 16 * 1024

/** 单条日志最多读这么多字节，超出部分截断提示（防多行堆栈把一条日志读成几十 MB） */
private const val MAX_ENTRY_BYTES = 128 * 1024

/** 正文 LRU 缓存条数上限（AG 同值 512）：滑出窗口的正文直接丢弃 */
private const val MAX_CACHED_ENTRIES = 512

/** 正文缓存总字符上限，防止缓存里全是超长行时把 512 条的额度吃成几十 MB */
private const val MAX_CACHED_CHARS = 2 * 1024 * 1024

/** 首屏读取条数：够铺满一两屏，剩下的滑到哪加载到哪 */
private const val INITIAL_PAGE_ENTRIES = 200

/** 每次「加载更多」（往前翻历史）的条数 */
private const val PAGE_ENTRIES = 200

/** 每页预解析正文的条数：首屏/新页先解析一部分，避免列表占位闪烁 */
private const val PREFETCH_ENTRIES = 80

/** 每页预解析正文的字节预算：预解析只为让首屏不闪，不能反过来把内存吃满 */
private const val PREFETCH_BYTES = 512L * 1024

/** 索引条数上限（每条只有两个 Long，约 16B），极端大文件也不至于让索引失控 */
private const val MAX_INDEX_ENTRIES = 8192

/** 单页最多扫多少字节；注意至少会扫到一条完整日志为止，保证每次加载都有进展 */
private const val MAX_PAGE_SCAN_BYTES = 4L * 1024 * 1024

/** 增量超过这个大小就不逐行合并了，直接重载尾部一页（比如应用在后台挂了好几小时） */
private const val MAX_APPEND_BYTES = 1L * 1024 * 1024

/** 找文件末尾换行符时最多往回找这么多字节 */
private const val MAX_TAIL_SCAN_BYTES = 1024L * 1024

/** 文件末尾没有换行时，静默超过这个时间就认为最后一行写完了（有些日志实现末尾不补换行） */
private const val TAIL_IDLE_MILLIS = 3000L

/** 搜索最多保留最近这么多条命中，避免一次搜索把整天的日志都塞进列表 */
private const val SEARCH_RESULT_LIMIT = 300

/** 搜索输入防抖，避免每敲一个字就扫一遍文件 */
private const val SEARCH_DEBOUNCE_MILLIS = 260L

/** 轮询间隔，和原来的 2 秒保持一致 */
private const val REFRESH_INTERVAL_MILLIS = 2000L

/** 行首时间戳 + TAG；账号标识在行尾，会原样落进 body，不影响这里的匹配 */
private val LOG_HEADER_REGEX = Regex("^(\\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+(\\w+):\\s*(.*)$")

/** 只用来判断「这一行是不是条目首行」，所以不要求匹配到行尾 */
private val LOG_HEADER_PREFIX_REGEX = Regex("^(\\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+(\\w+):\\s*")

class MiuixLogViewerActivity : MiuixBaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setAppContent {
            LogScreen(this, LogType.fromIntent(intent))
        }
    }
}

/**
 * 日志页：倒序（最新在最上）展示指定类目的日志，按需分块读取。
 * 仿 LSPosed 日志界面：每条一张卡（TAG + 时间 + 正文）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LogScreen(activity: MiuixLogViewerActivity, logType: LogType) {
    val context = LocalContext.current
    // LogType.file 的 getter 内部有磁盘 I/O（exists/createNewFile），只在进入时取一次，
    // 不能放在组合里每次重组都求值
    val file = remember(logType) { logType.file }
    // 分页读取器：随页面创建一次，页面销毁时一起释放（正文缓存也在里面）
    val pager = remember(logType) { LogPager(file) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    var showSearch by remember(logType) { mutableStateOf(false) }
    var searchText by remember(logType) { mutableStateOf("") }

    // 倒序展示：最新的在最上面。asReversed() 是 O(1) 的视图，不会复制列表
    val displayRefs = remember(pager.refs, pager.searchResults) {
        (pager.searchResults ?: pager.refs).asReversed()
    }
    // 停在顶部 = 停在最新。只有这时新日志到来才跟随滚动
    val atTop by remember(listState) {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        }
    }

    // 首屏：只从文件尾部读一页，不再全量读文件
    LaunchedEffect(pager) {
        pager.reload()
    }

    // 轮询刷新：文件变长才增量补读；本来就停在顶部时跟随最新（倒序下就是滚到 index 0）
    LaunchedEffect(pager, listState) {
        while (isActive) {
            delay(REFRESH_INTERVAL_MILLIS)
            // 必须在更新列表之前判断：写入后 LazyColumn 会按 key 锚定当前可见项，
            // 把 firstVisibleItemIndex 从 0 顶到 n，那时就再也判断不出「原本停在顶部」了
            val wasAtTop = listState.firstVisibleItemIndex == 0 &&
                listState.firstVisibleItemScrollOffset == 0
            val changed = pager.refresh()
            if (changed && wasAtTop && pager.displayCount > 0) {
                // 非挂起 API：不阻塞轮询，也不会和用户的手势抢滚动
                listState.requestScrollToItem(0)
            }
        }
    }

    // 滑到「已加载的最旧一条」附近时，自动往前再读一页（倒序下即列表底部）
    LaunchedEffect(pager, listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .collect { lastVisible ->
                if (lastVisible >= 0 && lastVisible >= pager.displayCount - 3) {
                    pager.loadOlder()
                }
            }
    }

    // 搜索：输入停顿后扫全文件（只保留最近 300 条命中），结果仍按倒序展示
    LaunchedEffect(searchText, showSearch) {
        if (!showSearch || searchText.isBlank()) {
            pager.clearSearch()
            return@LaunchedEffect
        }
        delay(SEARCH_DEBOUNCE_MILLIS)
        pager.search(searchText)
    }

    // 出结果后回到顶部：倒序下最新的一条命中就在最上面
    LaunchedEffect(pager.searchResults) {
        if (pager.searchResults != null && pager.displayCount > 0) {
            listState.requestScrollToItem(0)
        }
    }

    Scaffold(
        topBar = {
            Column {
                LogTopBar(
                    title = logType.displayName + " · 实时",
                    onBack = { activity.finish() },
                    onSearch = {
                        showSearch = !showSearch
                        if (!showSearch) searchText = ""
                    },
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
                            searchText = ""
                            scope.launch {
                                pager.reload()
                                ToastUtil.show(context, "已清空")
                            }
                        }
                    }
                )
                if (showSearch) {
                    LogSearchBar(
                        text = searchText,
                        onTextChange = { searchText = it },
                        onClose = {
                            showSearch = false
                            searchText = ""
                        }
                    )
                }
            }
        },
        containerColor = MiuixTheme.colorScheme.surface
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (showSearch) LogSearchSummary(pager)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (pager.displayCount == 0) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = when {
                                pager.loading -> "加载中…"
                                pager.isSearching -> "没有匹配「" + pager.searchKeyword + "」的日志"
                                else -> "(空)"
                            },
                            fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        items(
                            items = displayRefs,
                            // 用文件偏移做 key：条目增删时 LazyColumn 能锚住当前可见项，
                            // 不会因为顶部插入新日志把用户正在看的历史顶走
                            key = { ref -> ref.start }
                        ) { ref ->
                            LogEntryItem(pager, ref)
                        }
                        // 搜索结果是固定的一批命中，没有「加载更多」的说法，不显示底部提示
                        if (!pager.isSearching) {
                            item(key = "footer") {
                                LogListFooter(pager)
                            }
                        }
                    }
                    // 翻到历史里时给个一键回到最新（倒序下就是列表顶部）
                    if (!atTop && !pager.isSearching) {
                        TextButton(
                            text = "回到最新",
                            onClick = {
                                scope.launch { listState.animateScrollToItem(0) }
                            },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(16.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 单条日志：正文不在组合里同步读文件。
 *
 * 缓存命中就立即显示；没命中就让 produceState 去 IO 线程读，
 * 读完写回 state 触发重组。快速滑过的项会被 LazyColumn 回收，
 * 对应的协程随之取消，所以不会堆积读取任务，也不会卡住滚动。
 */
@Composable
private fun LogEntryItem(pager: LogPager, ref: LogEntryRef) {
    // 用整个 ref 做 key：尾部日志被追加内容时 end 会变，key 变化会重新读正文
    val cached = remember(ref) { pager.cachedEntry(ref) }
    val entry by produceState<LogEntry?>(initialValue = cached, ref) {
        value = cached ?: pager.loadEntry(ref)
    }
    val current = entry
    if (current == null) {
        // 占位卡片：保持和正常条目一致的卡片样式，避免列表高度跳一下
        LogEntryCard(LogEntry(ref.start, null, null, "…"))
    } else {
        LogEntryCard(current)
    }
}

/** 单条日志卡片：标题(TAG)+ 右上时间戳 + 下方正文 */
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

/** 列表底部提示：正在加载 / 还能加载 / 已到最早 / 索引到上限 */
@Composable
private fun LogListFooter(pager: LogPager) {
    val text = when {
        pager.loadingOlder -> "正在加载更早的日志…"
        pager.hasOlder -> "上滑加载更早的日志"
        pager.refs.size >= MAX_INDEX_ENTRIES -> "只加载了最近 $MAX_INDEX_ENTRIES 条，更早的日志请导出查看"
        pager.refs.isNotEmpty() -> "— 已到最早 —"
        else -> ""
    }
    if (text.isEmpty()) return
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
    }
}

/** 搜索输入行：miuix TextField + 关闭按钮，风格和其余二级页一致 */
@Composable
private fun LogSearchBar(text: String, onTextChange: (String) -> Unit, onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextField(
            value = text,
            onValueChange = onTextChange,
            label = "搜索日志",
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "关闭搜索",
                tint = MiuixTheme.colorScheme.onBackground
            )
        }
    }
}

/** 搜索结果状态：搜索中 / 命中条数 / 达到上限 */
@Composable
private fun LogSearchSummary(pager: LogPager) {
    val results = pager.searchResults
    val count = results?.size ?: 0
    val text = when {
        pager.searching -> "搜索中…"
        results == null -> ""
        count == 0 -> "没有匹配「" + pager.searchKeyword + "」的日志"
        pager.searchLimitReached -> "命中 $count 条（只显示最近 $SEARCH_RESULT_LIMIT 条）"
        else -> "命中 $count 条"
    }
    if (text.isEmpty()) return
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        fontSize = 12.sp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
    )
}

/**
 * 通用顶部栏:返回图标 + 可选的操作图标(导入/搜索/导出/删除) + 横向 marquee 滚动的标题。
 * 仿 LSPosed 日志页样式。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LogTopBar(
    title: String,
    onBack: () -> Unit,
    onImport: (() -> Unit)? = null,
    onSearch: (() -> Unit)? = null,
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
            if (onSearch != null) {
                IconButton(onClick = onSearch) {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = "搜索",
                        tint = MiuixTheme.colorScheme.onBackground
                    )
                }
            }
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

// =====================================================================================
// 分块读取与缓存
// =====================================================================================

/**
 * 日志分页读取器：内存里只保留「条目在文件中的位置」和最近 512 条正文。
 *
 * 为什么倒着读、为什么分页：
 * - 日志是**追加写**的，最新的内容在文件末尾，倒着读第一屏就是最新日志，用户不用等全量解析；
 * - 全量读会生成几万个对象，内存和主线程都扛不住，所以只按页读；
 * - 只存偏移，正文按需读 + LRU 限制，内存占用与文件大小基本无关。
 *
 * 所有 public 方法都在主线程被调用（Compose 协程），真正的磁盘读放在 [Dispatchers.IO]。
 */
private class LogPager(private val file: File) {

    /** 已加载条目，按时间正序（旧 → 新）保存；展示时由 UI 反转成倒序 */
    var refs: List<LogEntryRef> by mutableStateOf<List<LogEntryRef>>(emptyList())
        private set

    /** 搜索命中结果（正序）；null 表示当前不是搜索模式 */
    var searchResults: List<LogEntryRef>? by mutableStateOf<List<LogEntryRef>?>(null)
        private set

    /** 是否还有更早的日志可以加载 */
    var hasOlder: Boolean by mutableStateOf(false)
        private set

    var loading: Boolean by mutableStateOf(false)
        private set

    var loadingOlder: Boolean by mutableStateOf(false)
        private set

    var searching: Boolean by mutableStateOf(false)
        private set

    /** 命中数是否触顶（只展示最近 [SEARCH_RESULT_LIMIT] 条） */
    var searchLimitReached: Boolean by mutableStateOf(false)
        private set

    /** 当前搜索关键词，用于结果提示文案 */
    var searchKeyword: String by mutableStateOf("")
        private set

    /** 更旧方向的继续位置：下一次「加载更多」从这里往前扫，0 表示已到文件头 */
    private var scanFrom = 0L

    /** 已读到的文件长度快照：只处理这个长度以内的内容，避免追着持续追加的文件读 */
    private var snapshotEnd = 0L

    /** 搜索代次：新一轮搜索/关闭搜索后，旧的搜索结果直接丢弃，防止串台 */
    private var searchToken = 0

    private val cache = EntryCache(MAX_CACHED_ENTRIES, MAX_CACHED_CHARS)

    val displayCount: Int get() = (searchResults ?: refs).size

    val isSearching: Boolean get() = searchResults != null

    /**
     * 首屏加载：只从文件尾部倒着读一页。
     * 文件被清空/轮转时也走这里重置状态。
     */
    suspend fun reload() {
        clearSearch()
        loading = true
        try {
            val page = withContext(Dispatchers.IO) { loadTailPage() }
            cache.clear()
            refs = page.refs
            snapshotEnd = page.snapshotEnd
            scanFrom = page.boundary
            hasOlder = page.boundary > 0L
            for (entry in page.prefetched) cache.put(entry)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // 文件读不了（权限/正在被删）时保持空列表，不要打断页面
            refs = emptyList()
            hasOlder = false
            scanFrom = 0L
            snapshotEnd = 0L
        } finally {
            loading = false
        }
    }

    /**
     * 加载更早的一页（倒序列表滑到底部时触发）。
     * 每次最多 [PAGE_ENTRIES] 条 / [MAX_PAGE_SCAN_BYTES] 字节，读到的条目插到列表头部。
     */
    suspend fun loadOlder() {
        if (loading || loadingOlder || !hasOlder || searchResults != null) return
        if (refs.size >= MAX_INDEX_ENTRIES) {
            hasOlder = false
            return
        }
        loadingOlder = true
        val boundary = scanFrom
        try {
            val page = withContext(Dispatchers.IO) {
                val older = ArrayList<LogEntryRef>(PAGE_ENTRIES)
                val next = if (boundary > 0L) {
                    scanEntriesBackward(file, boundary, PAGE_ENTRIES, MAX_PAGE_SCAN_BYTES) { ref ->
                        older.add(ref)
                        true
                    }
                } else {
                    0L
                }
                older.reverse() // 扫描结果是「新 → 旧」，反转成「旧 → 新」
                // 只预解析「紧挨着当前底部」的那部分，避免一页 200 条超长日志一次性进内存
                EntryPage(
                    refs = older,
                    boundary = next,
                    snapshotEnd = snapshotEnd,
                    prefetched = readEntries(file, older.takeLast(PREFETCH_ENTRIES), PREFETCH_BYTES)
                )
            }
            refs = page.refs + refs
            scanFrom = page.boundary
            hasOlder = page.boundary > 0L
            for (entry in page.prefetched) cache.put(entry)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // 读失败就先停下，避免滚动时反复重试同一个位置
            hasOlder = false
        } finally {
            loadingOlder = false
        }
    }

    /**
     * 轮询刷新：只有文件确实变长才补读新增区间，并返回是否发生变化（决定要不要跟随最新）。
     *
     * 增量读取在这里很重要：日志一天几 MB，如果每 2 秒重扫一遍尾部，等于持续做全量解析。
     */
    suspend fun refresh(): Boolean {
        if (loading || loadingOlder) return false
        val length = file.length()
        if (length < snapshotEnd) {
            // 文件被清空或被轮转（换成新文件）→ 状态不可信，整体重载
            reload()
            return true
        }
        if (length == snapshotEnd) return false
        var changed = false
        try {
            val newEnd = withContext(Dispatchers.IO) { completeEnd(file, length) }
            if (newEnd <= snapshotEnd) return false // 只有写了一半的行，等下一次轮询
            if (newEnd - snapshotEnd > MAX_APPEND_BYTES) {
                // 离上次刷新太久（后台挂起等），增量已经很大，直接重载尾部一页更划算
                reload()
                return true
            }
            val append = withContext(Dispatchers.IO) {
                scanAppended(file, snapshotEnd, newEnd)
            }
            val previousLast = refs.lastOrNull()
            val mergedEnd = append.mergedPreviousEnd
            if (mergedEnd != null) {
                if (previousLast != null) {
                    // 新增内容以「续行」开头：上一条日志被补全了，延长它的范围并作废旧正文缓存
                    refs = refs.dropLast(1) + previousLast.copy(end = maxOf(previousLast.end, mergedEnd))
                    cache.remove(previousLast.start)
                } else {
                    // 之前一条完整日志都没有（例如文件刚创建、首行还没有换行）
                    refs = refs + LogEntryRef(0L, mergedEnd)
                }
                changed = true
            }
            if (append.entries.isNotEmpty()) {
                refs = refs + append.entries
                changed = true
            }
            for (entry in append.prefetched) cache.put(entry)
            snapshotEnd = newEnd
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // 读失败不打断轮询，下一次再试
        }
        return changed
    }

    /**
     * 全文搜索：从文件尾部往前扫，命中最近 [SEARCH_RESULT_LIMIT] 条就停。
     *
     * 为什么在 IO 线程整文件扫：分页只加载了一小段，在「已加载范围」里搜会搜不到昨天的日志，
     * 体验很差；倒着扫 + 命中上限可以保证结果按新→旧、且内存只留命中条目。
     */
    suspend fun search(query: String) {
        val keyword = query.trim()
        if (keyword.isEmpty()) {
            clearSearch()
            return
        }
        searchKeyword = keyword
        searching = true
        searchLimitReached = false
        val token = ++searchToken
        try {
            val hits = withContext(Dispatchers.IO) {
                val found = ArrayList<LogEntryRef>(SEARCH_RESULT_LIMIT)
                val context = coroutineContext
                val end = completeEnd(file, file.length())
                RandomAccessFile(file, "r").use { raf ->
                    scanEntriesBackward(raf, end, Int.MAX_VALUE, Long.MAX_VALUE) { ref ->
                        // 用户改了关键词或退出页面时立刻停下，别让旧搜索占着 IO
                        context.ensureActive()
                        // 用原始正文搜索而不是展示用的 body：展示会按 16KB 截断，
                        // 但搜索不应该因为「行太长」而漏掉后面的关键词
                        val body = runCatching { readRawText(raf, ref) }.getOrDefault("")
                        if (body.contains(keyword, ignoreCase = true)) {
                            found.add(ref)
                        }
                        found.size < SEARCH_RESULT_LIMIT
                    }
                }
                found
            }
            if (token != searchToken) return
            // 扫描是「新 → 旧」，反转成「旧 → 新」，UI 再 asReversed() 展示
            val ordered = hits.asReversed().toList()
            searchResults = ordered
            searchLimitReached = hits.size >= SEARCH_RESULT_LIMIT
            for (entry in readEntries(file, ordered.takeLast(PREFETCH_ENTRIES), PREFETCH_BYTES)) cache.put(entry)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (token == searchToken) {
                searchResults = emptyList()
                searchLimitReached = false
            }
        } finally {
            if (token == searchToken) searching = false
        }
    }

    /** 退出搜索模式（清空关键词与命中结果） */
    fun clearSearch() {
        searchToken++
        searchKeyword = ""
        searchResults = null
        searching = false
        searchLimitReached = false
    }

    /** 缓存命中的正文（组合期同步调用，主线程） */
    fun cachedEntry(ref: LogEntryRef): LogEntry? = cache.get(ref.start)

    /** 按需读取一条正文：先查缓存，未命中再去 IO 线程读，读完回主线程入缓存 */
    suspend fun loadEntry(ref: LogEntryRef): LogEntry {
        cache.get(ref.start)?.let { return it }
        val entry = withContext(Dispatchers.IO) {
            runCatching { readEntry(file, ref) }.getOrNull()
        } ?: LogEntry(ref.start, null, null, "（日志读取失败）")
        cache.put(entry)
        return entry
    }

    /** 首屏读一页：从 EOF 往前扫描，结果反转成「旧 → 新」 */
    private fun loadTailPage(): EntryPage {
        val length = file.length()
        val end = completeEnd(file, length)
        val page = ArrayList<LogEntryRef>(INITIAL_PAGE_ENTRIES)
        var boundary = end
        if (end > 0L) {
            boundary = scanEntriesBackward(file, end, INITIAL_PAGE_ENTRIES, MAX_PAGE_SCAN_BYTES) { ref ->
                page.add(ref)
                true
            }
        }
        page.reverse()
        return EntryPage(page, boundary, end, readEntries(file, page.takeLast(PREFETCH_ENTRIES), PREFETCH_BYTES))
    }

    /**
     * 增量补读 [from, to)：只解析这一小段，把它并进已有列表。
     *
     * 注意首行：如果它不是时间戳行，说明是上一条日志没写完的续行，
     * 要把上一条的 end 延长到下一个时间戳行（或文件末尾），否则正文会缺一段。
     */
    private fun scanAppended(file: File, from: Long, to: Long): AppendResult {
        val size = (to - from).toInt()
        if (size <= 0) return AppendResult(emptyList(), null, emptyList())
        val bytes = ByteArray(size)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(from)
            raf.readFully(bytes)
        }
        val entries = ArrayList<LogEntryRef>()
        var lineStart = 0
        var pendingStart = -1L
        var mergedEnd: Long? = null
        var first = true
        var i = 0
        while (i <= size) {
            val isLineBreak = i == size || bytes[i] == '\n'.code.toByte()
            if (isLineBreak) {
                val absoluteStart = from + lineStart
                val absoluteEnd = from + i
                val header = i > lineStart && isHeaderBytes(bytes, lineStart, i)
                if (header) {
                    if (pendingStart >= 0L) {
                        entries.add(LogEntryRef(pendingStart, absoluteStart))
                    } else if (mergedEnd != null) {
                        // 续行之后终于出现新的时间戳行：上一条到此为止
                        mergedEnd = absoluteStart
                    }
                    pendingStart = absoluteStart
                } else if (first) {
                    mergedEnd = absoluteEnd
                }
                lineStart = i + 1
                first = false
            }
            i++
        }
        if (pendingStart >= 0L) {
            entries.add(LogEntryRef(pendingStart, to))
        } else if (mergedEnd != null) {
            mergedEnd = to
        }
        return AppendResult(entries, mergedEnd, readEntries(file, entries, PREFETCH_BYTES))
    }
}

/** 一页读取结果：refs 为「旧 → 新」，boundary 为更旧方向的继续位置 */
private data class EntryPage(
    val refs: List<LogEntryRef>,
    val boundary: Long,
    val snapshotEnd: Long,
    val prefetched: List<LogEntry>
)

/** 增量读取结果：entries 为新增条目（旧 → 新），mergedPreviousEnd 用于延长上一条 */
private data class AppendResult(
    val entries: List<LogEntryRef>,
    val mergedPreviousEnd: Long?,
    val prefetched: List<LogEntry>
)

/**
 * 正文 LRU 缓存：超过条数或总字符上限就淘汰最久没看的。
 *
 * 为什么还要限字符：512 条如果全是 16KB 超长行就是 8MB，加一个字符总上限更稳。
 */
private class EntryCache(private val maxEntries: Int, private val maxChars: Int) {

    // accessOrder = true：每次 get 都会把条目移到队尾，队首就是最久未使用
    private val map = LinkedHashMap<Long, LogEntry>(64, 0.75f, true)
    private var chars = 0

    fun get(start: Long): LogEntry? = map[start]

    fun put(entry: LogEntry) {
        map.put(entry.start, entry)?.let { chars -= it.charCount }
        chars += entry.charCount
        val iterator = map.entries.iterator()
        while ((map.size > maxEntries || chars > maxChars) && iterator.hasNext()) {
            val eldest = iterator.next()
            chars -= eldest.value.charCount
            iterator.remove()
        }
    }

    fun remove(start: Long) {
        map.remove(start)?.let { chars -= it.charCount }
    }

    fun clear() {
        map.clear()
        chars = 0
    }
}

/** 缓存占用估算：正文占大头，这里只按字符数近似 */
private val LogEntry.charCount: Int
    get() = body.length + (tag?.length ?: 0) + (time?.length ?: 0)

// =====================================================================================
// 文件读取（全部在 IO 线程调用）
// =====================================================================================

/**
 * 从 [from]（右边界，通常是文件末尾或上一页的更旧边界）向前按块扫描，
 * 逐条产出完整日志条目的位置，最多 [maxEntries] 条 / [maxBytes] 字节。
 *
 * 返回更旧方向的继续位置：0 表示已经到文件头，否则下一页从这里接着往前读。
 *
 * 实现要点：
 * - 只扫描字节找 `\n` 定位行边界，**不把行内容读进内存**；
 * - 判断「是不是时间戳行」只读行首 [HEADER_PREFIX_BYTES] 字节（正则锚在行首），
 *   这样每行都不用解码整行，CPU 与内存都与文件大小无关；
 * - 一个条目 = 时间戳行 + 它后面所有续行，条目结束位置 = 下一个时间戳行的行首，
 *   这也是「倒序扫描」时用 groupEnd 记录、遇到时间戳行才落一条的原因。
 *
 * 进度保证：字节上限只在已经产出过条目之后才生效，
 * 否则遇到超长条目（例如几 MB 的堆栈）会永远扫不到时间戳行，导致「加载更多」原地打转。
 */
private fun scanEntriesBackward(
    file: File,
    from: Long,
    maxEntries: Int,
    maxBytes: Long,
    onEntry: (LogEntryRef) -> Boolean
): Long = RandomAccessFile(file, "r").use { raf ->
    scanEntriesBackward(raf, from, maxEntries, maxBytes, onEntry)
}

/** [scanEntriesBackward] 的重载：复用同一个文件句柄，搜索时边扫边读正文 */
private fun scanEntriesBackward(
    raf: RandomAccessFile,
    from: Long,
    maxEntries: Int,
    maxBytes: Long,
    onEntry: (LogEntryRef) -> Boolean
): Long {
    if (from <= 0L || maxEntries <= 0) return 0L
    val buffer = ByteArray(READ_BUFFER_BYTES)
    var boundary = from   // 还没扫描的区间右边界（不含）
    var lineEnd = from    // 当前行内容的结束位置（不含换行符）
    var groupEnd = from   // 正在拼的条目的结束位置
    var emitted = 0
    var scanned = 0L
    var reachedStart = false
    while (boundary > 0L && emitted < maxEntries && (emitted == 0 || scanned < maxBytes)) {
        val chunkStart = (boundary - buffer.size).coerceAtLeast(0L)
        val chunkLen = (boundary - chunkStart).toInt()
        raf.seek(chunkStart)
        raf.readFully(buffer, 0, chunkLen)
        scanned += chunkLen
        var i = chunkLen - 1
        while (i >= 0) {
            if (emitted >= maxEntries) break
            if (buffer[i] == '\n'.code.toByte()) {
                val lineStart = chunkStart + i + 1
                if (isHeaderLine(raf, buffer, chunkStart, chunkLen, lineStart, lineEnd)) {
                    val ref = LogEntryRef(lineStart, groupEnd)
                    groupEnd = lineStart
                    emitted++
                    if (!onEntry(ref)) return lineStart
                }
                lineEnd = chunkStart + i
            }
            i--
        }
        if (i >= 0) {
            // 条目数到上限，这一块没扫完：位置停在 groupEnd，下次从这里继续
            break
        }
        boundary = chunkStart
        if (boundary == 0L) reachedStart = true
    }
    // 只有真正扫到了文件头，才把开头那段不是时间戳行的内容单独收成一条，避免丢正文。
    // 注意不能只看 boundary==0：条目数到上限时 boundary 也可能已经变成 0，那时开头还没扫到。
    if (reachedStart && groupEnd > 0L) {
        onEntry(LogEntryRef(0L, groupEnd))
        return 0L
    }
    return groupEnd
}

/**
 * 判断行 [lineStart, lineEnd) 是否是「时间戳行」。
 * 内容在本次读块里就直接切片，跨块（超长行）才回文件读行首，避免每行一次随机读。
 */
private fun isHeaderLine(
    raf: RandomAccessFile,
    buffer: ByteArray,
    chunkStart: Long,
    chunkLen: Int,
    lineStart: Long,
    lineEnd: Long
): Boolean {
    val available = lineEnd - lineStart
    if (available <= 0L) return false
    val length = minOf(available, HEADER_PREFIX_BYTES.toLong()).toInt()
    val text = try {
        val offset = lineStart - chunkStart
        if (offset >= 0L && offset + length <= chunkLen.toLong()) {
            String(buffer, offset.toInt(), length, StandardCharsets.UTF_8)
        } else {
            val prefix = ByteArray(length)
            raf.seek(lineStart)
            raf.readFully(prefix)
            String(prefix, StandardCharsets.UTF_8)
        }
    } catch (e: Throwable) {
        return false
    }
    return LOG_HEADER_PREFIX_REGEX.find(text) != null
}

/** 增量区间里的行首判断（内容已经在内存里，不用回文件读） */
private fun isHeaderBytes(bytes: ByteArray, start: Int, end: Int): Boolean {
    val length = minOf(end - start, HEADER_PREFIX_BYTES)
    if (length <= 0) return false
    return LOG_HEADER_PREFIX_REGEX.find(String(bytes, start, length, StandardCharsets.UTF_8)) != null
}

/** 读一条日志的正文（每条打开一次文件，适合零散按需读取） */
private fun readEntry(file: File, ref: LogEntryRef): LogEntry =
    RandomAccessFile(file, "r").use { raf -> readEntry(raf, ref) }

/**
 * 读一条日志的原始文本（搜索用）：不做逐行截断，保证超过 16KB 的关键词也能命中。
 * 仍然只读 [MAX_ENTRY_BYTES]，避免一条巨型日志把搜索拖垮。
 */
private fun readRawText(raf: RandomAccessFile, ref: LogEntryRef): String {
    val size = (ref.end - ref.start).coerceAtMost(MAX_ENTRY_BYTES.toLong()).toInt()
    if (size <= 0) return ""
    val bytes = ByteArray(size)
    raf.seek(ref.start)
    raf.readFully(bytes)
    return String(bytes, StandardCharsets.UTF_8)
}

/**
 * 读一条日志的正文：最多读 [MAX_ENTRY_BYTES]，单行再按 [MAX_DISPLAY_LINE_BYTES] 截断。
 *
 * 这样即使某条日志是几 MB 的 JSON / 堆栈，也只会占这么多内存；
 * 账号标识在行尾，属于 body 的一部分，不影响行首时间戳的解析。
 */
private fun readEntry(raf: RandomAccessFile, ref: LogEntryRef): LogEntry {
    val size = (ref.end - ref.start).coerceAtMost(MAX_ENTRY_BYTES.toLong()).toInt()
    if (size <= 0) return LogEntry(ref.start, null, null, "")
    val bytes = ByteArray(size)
    raf.seek(ref.start)
    raf.readFully(bytes)
    val truncated = ref.end - ref.start > size
    val lines = String(bytes, StandardCharsets.UTF_8).split('\n')
    var time: String? = null
    var tag: String? = null
    val body = StringBuilder(size)
    lines.forEachIndexed { index, raw ->
        // 末尾换行会 split 出一个空串，跳过
        if (index == lines.lastIndex && raw.isEmpty()) return@forEachIndexed
        val line = truncateUtf8(raw.removeSuffix("\r"), MAX_DISPLAY_LINE_BYTES)
        if (index == 0) {
            // 首行才是时间戳 + TAG；账号标识在行尾，会原样留在 body 里
            val match = LOG_HEADER_REGEX.find(line)
            if (match != null) {
                time = match.groupValues[1]
                tag = match.groupValues[2]
                body.append(match.groupValues[3])
            } else {
                body.append(line)
            }
        } else {
            body.append('\n').append(line)
        }
    }
    if (truncated) {
        body.append("\n…（超过 ").append(MAX_ENTRY_BYTES / 1024).append("KB，已截断）")
    }
    return LogEntry(ref.start, time, tag, body.toString())
}

/** 批量读一页正文（共用一个文件句柄，避免一页 200 次 open）；[budgetBytes] 限制预解析的总字节数 */
private fun readEntries(
    file: File,
    refs: List<LogEntryRef>,
    budgetBytes: Long = Long.MAX_VALUE
): List<LogEntry> {
    if (refs.isEmpty()) return emptyList()
    val result = ArrayList<LogEntry>(refs.size)
    var used = 0L
    try {
        RandomAccessFile(file, "r").use { raf ->
            for (ref in refs) {
                val size = ref.end - ref.start
                if (result.isNotEmpty() && used + size > budgetBytes) break
                runCatching { readEntry(raf, ref) }.getOrNull()?.let {
                    result.add(it)
                    used += size
                }
            }
        }
    } catch (e: Throwable) {
        // 读失败就少几条正文，不影响列表骨架
    }
    return result
}

/**
 * 找到「最后一个完整行」的结束位置。
 *
 * 日志是持续追加写的，文件末尾可能只有半行；半行当成长度固定下来，
 * 下一条日志写进来时它就会变长，条目边界和正文都会错，所以默认只认换行符之后的区域。
 * 但如果文件已经静默超过 [TAIL_IDLE_MILLIS]，说明最后一行其实已经写完（有些不补换行），
 * 这时把它也算进来，避免「最后一条日志永远看不到」。
 */
private fun completeEnd(file: File, length: Long): Long {
    if (length <= 0L) return 0L
    val lastNewline = findLastNewline(file, length)
    val tailStart = if (lastNewline >= 0L) lastNewline + 1 else 0L
    if (tailStart >= length) return length
    val idle = System.currentTimeMillis() - file.lastModified()
    return if (idle > TAIL_IDLE_MILLIS) length else tailStart
}

/** 从文件末尾往前找最后一个 `\n`，最多回溯 [MAX_TAIL_SCAN_BYTES]（防单行巨无霸时扫全文件） */
private fun findLastNewline(file: File, length: Long): Long {
    val buffer = ByteArray(READ_BUFFER_BYTES)
    var end = length
    var remaining = MAX_TAIL_SCAN_BYTES
    RandomAccessFile(file, "r").use { raf ->
        while (end > 0L && remaining > 0L) {
            val start = (end - buffer.size).coerceAtLeast(0L)
            val lengthInChunk = (end - start).toInt()
            raf.seek(start)
            raf.readFully(buffer, 0, lengthInChunk)
            for (i in lengthInChunk - 1 downTo 0) {
                if (buffer[i] == '\n'.code.toByte()) return start + i
            }
            remaining -= lengthInChunk
            end = start
        }
    }
    return -1L
}

/**
 * 按 UTF-8 字节数截断一行，注意不能把汉字/emoji 切成半个字符。
 * 超长行（例如一整条 base64 或巨型 JSON）全靠这里兜底。
 */
private fun truncateUtf8(text: String, maxBytes: Int): String {
    var used = 0
    var index = 0
    while (index < text.length) {
        val char = text[index]
        val size = when {
            char.code < 0x80 -> 1
            char.code < 0x800 -> 2
            // U+D800..U+DBFF 是代理对的高位，emoji 等 4 字节字符要按两个 Char 处理
            char.code in 0xD800..0xDBFF && index + 1 < text.length -> 4
            else -> 3
        }
        if (used + size > maxBytes) break
        used += size
        index += if (size == 4) 2 else 1
    }
    if (index >= text.length) return text
    return text.substring(0, index) + "…（本行超长，已截断）"
}
