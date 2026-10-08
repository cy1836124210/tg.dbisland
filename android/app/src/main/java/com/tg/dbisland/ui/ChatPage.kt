package com.tg.dbisland.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tg.dbisland.IslandBridge
import com.tg.dbisland.ui.glass.GlassBox

/**
 * 「聊天」页。
 *
 * 布局（用户要求的形态）：
 *   ┌──────────┬─────────────────────────┐
 *   │ 会话框    │  具体聊天页面             │
 *   │（可折叠） │  消息气泡（只读）          │
 *   └──────────┴─────────────────────────┘
 *
 * 折叠不是「消失」，而是**滑出 + 变窄**：收起时左边只留一个 0dp 的占位，
 * 由标题栏左侧的汉堡按钮 / 右滑手势打开；打开时盖一层很淡的遮罩，点遮罩收起。
 *
 * **第 48 条**：用户要求「app 内取消可以回复的能力，只保留悬浮窗可以回复的能力」，
 * 所以这一页是**只读**的会话记录（可看可滚动），底部只剩一行提示。
 * 发送能力本身仍在 [IslandBridge.sendReplyToConv]（悬浮窗 / 调试入口共用），
 * 这里只是不再提供 UI 入口。
 *
 * 数据来自 [BridgeHub]（按 cid 归档的真实推流）。
 */
@Composable
fun ChatPage() {
    val st by BridgeHub.state.collectAsStateSafe()
    var expanded by rememberSaveable { mutableStateOf(false) }
    var selectedCid by rememberSaveable { mutableStateOf<String?>(null) }

    // 会话列表变化时保证选中项有效：优先保留当前选中，否则选最新一条。
    val convs = st.conversations
    LaunchedEffect(convs) {
        if (convs.isNotEmpty()) {
            val stillThere = convs.any { it.cid == selectedCid }
            if (!stillThere) selectedCid = convs.last().cid
        }
    }
    val selected = convs.firstOrNull { it.cid == selectedCid }

    Box(Modifier.fillMaxSize()) {
        // 注意：磨砂**不在这一层**。
        //
        // 用户直接纠正过这件事：「你怎么把整个页面都磨砂了，只要弹出的选项框是磨砂」。
        // `Modifier.blur` 只模糊它作用的那一层自己画的像素，把 blur 加在「面板
        // 背后的内容」上，效果就是**整页被糊**、而面板本身还是一块半透明板子。
        // 正确做法是把模糊加在**面板自己**（见 ConversationRail 的 GlassBox
        // `frosted = true`），文字作为子节点画在模糊层之上，始终清晰。
        Column(Modifier.fillMaxSize()) {
            ChatTopBar(
                title = selected?.title ?: "聊天",
                subtitle = selected?.let {
                    if (it.streaming) "正在回答…" else "共 ${it.replies} 条答复"
                } ?: "还没有会话",
                showMenu = !expanded,
                onMenu = { expanded = true },
                onNewest = { convs.lastOrNull()?.let { c -> selectedCid = c.cid } },
                hasConvs = convs.isNotEmpty(),
            )
            Box(
                Modifier
                    .weight(1f)
                    // 会话框展开时，点右侧聊天区就收起（替代那层全屏遮罩）
                    .then(
                        if (expanded) Modifier.clickableNoRipple { expanded = false }
                        else Modifier
                    ),
            ) {
                if (selected == null) {
                    EmptyChatHint()
                } else {
                    ChatDetail(selected)
                }
            }
        }

        // ---- 可折叠的左侧会话框（磨砂玻璃面板）----
        ConversationRail(
            expanded = expanded,
            conversations = convs,
            selectedCid = selected?.cid,
            onPick = { cid -> selectedCid = cid; expanded = false },
            onClose = { expanded = false },
        )
    }
}

@Composable
private fun ChatTopBar(
    title: String,
    subtitle: String,
    showMenu: Boolean,
    onMenu: () -> Unit,
    onNewest: () -> Unit,
    hasConvs: Boolean,
) {
    // 注意布局方式（用户报过的问题）：标题**必须居中且不随三横杆移动**。
    //
    // 旧版用「Row + `Column(weight(1f))`」，汉堡按钮的 `AnimatedVisibility`
    // 一出现/消失就改变了可用宽度，标题被推着左右跳。现在改成
    // `Box` + 三段绝对定位：左/右两个 **48dp 定宽槽**，中间那段用
    // `fillMaxWidth` + `Alignment.Center` 居中 —— 两侧槽的宽度恒定，
    // 所以标题永远在同一位置，跟汉堡在不在、有没有「最新」无关。
    //
    // 已知的小妥协：右侧两个控件（最新 + 占位）加起来比左侧一个宽一点，
    // 所以严格几何居中会略微偏右（≈12dp）。要绝对居中就得牺牲「最新」的
    // 右对齐观感，这里选了「位置固定不跳动」优先。
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        // 左槽：汉堡。展开时用 alpha 隐去（**不移除**，否则宽度变化又会推标题）
        IconButton(
            onClick = onMenu,
            enabled = showMenu,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .alpha(if (showMenu) 1f else 0f),
        ) {
            Icon(Icons.Filled.Menu, "会话列表", tint = AppColor.text)
        }

        // 中间：标题 + 副标题，始终居中
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                title, color = AppColor.text, fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            Text(
                subtitle, color = AppColor.textDim, fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }

        // 右槽：定宽的「最新」槽位（没有会话时也占位，保证两侧对称）
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(72.dp)
                .height(40.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (hasConvs) {
                Text(
                    "最新", color = AppColor.accent, fontSize = 13.sp,
                    modifier = Modifier
                        .clip(AppShape.chip)
                        .clickable(onClick = onNewest)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** 左侧会话框：收起 = 宽度 0；展开 = 280dp 玻璃面板 + 遮罩。 */
@Composable
private fun ConversationRail(
    expanded: Boolean,
    conversations: List<HubConversation>,
    selectedCid: String?,
    onPick: (String) -> Unit,
    onClose: () -> Unit,
) {
    val width by animateDpAsState(
        targetValue = if (expanded) 280.dp else 0.dp,
        animationSpec = tween(260),
        label = "railWidth",
    )

    // 展开时不加任何全屏遮罩（scrim）。
    //
    // 真机踩坑：早期版本在这里盖了一层 `Color.Black.copy(alpha = 0.35f)` 的
    // 全屏遮罩，用户一眼就看出来「页面上有一层遮罩」—— 它把整页压暗，而这个
    // App 的背景本来就是近黑，遮罩看不出「点这里关闭」的提示，只剩下「整屏发灰」
    // 的副作用。现在改成都靠卡片自己的磨砂玻璃边界来区分层级：
    // 卡片是磨砂的、背后仍是原样的聊天内容，关闭靠卡片右上角的 ✕ 或点卡片外。
    if (width > 0.dp) {
        Box(
            Modifier
                .fillMaxHeight()
                .width(width)
                .padding(
                    start = 10.dp,
                    // 让会话框从状态栏下面开始：这样它不会和背后的标题栏叠在一起
                    // （真机第一版就是「模拟sim-b」和「会话」两行字重叠，很难看）。
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 4.dp,
                    bottom = 6.dp,
                ),
        ) {
            GlassBox(
                modifier = Modifier.fillMaxSize(),
                shape = AppShape.card,
                strong = true,
                // 磨砂**就在这里**：面板自己真模糊 + 半透明底，面板里的文字
                // 作为子节点画在模糊层之上 → 面板是磨砂的，字是清晰的。
                // 这正是用户要的「只要弹出的选项框是磨砂」。
                frosted = true,
                radius = 20.dp,
            ) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "会话", color = AppColor.text, fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "${conversations.size} 个会话", color = AppColor.textDim,
                                fontSize = 11.5.sp,
                            )
                        }
                        IconButton(onClick = onClose) {
                            Icon(Icons.Filled.Close, "收起", tint = AppColor.textDim)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    HairLine()
                    if (conversations.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                "还没有会话\n豆包一开口就会出现在这里",
                                color = AppColor.textFaint, fontSize = 12.5.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
                    } else {
                        LazyColumn(
                            Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(8.dp),
                            reverseLayout = false,
                        ) {
                            // 最新在最上面，符合「找刚发生的事」的直觉
                            items(conversations.reversed(), key = { it.cid }) { c ->
                                ConversationRow(
                                    c = c,
                                    selected = c.cid == selectedCid,
                                    onClick = { onPick(c.cid) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(c: HubConversation, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (selected) AppColor.fill.copy(alpha = 0.09f) else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(
                    if (c.streaming) AppColor.accent.copy(alpha = 0.25f)
                    else AppColor.fill.copy(alpha = 0.07f)
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.ChatBubbleOutline, null,
                tint = if (c.streaming) AppColor.accent else AppColor.textDim,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                c.title, color = AppColor.text, fontSize = 13.5.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                c.preview.ifBlank { if (c.streaming) "正在回答…" else "（无内容）" },
                color = AppColor.textDim, fontSize = 11.5.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (c.streaming) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(AppColor.accent))
        } else if (c.replies > 0) {
            Pill("${c.replies}", AppColor.textFaint, filled = true)
        }
    }
}

/** 右侧具体聊天页：**只读**的气泡列表（第 48 条起这里不再有回复入口）。
 *
 *  用户本轮规则：「app 内取消可以回复的能力，只保留悬浮窗可以回复的能力」。
 *  所以底部的输入行 + 发送按钮整段删掉，只留一行提示；会话记录照旧可看、可滚动。
 *  发送能力本身**没有删** —— [com.tg.dbisland.IslandBridge.sendReplyToConv] 仍是
 *  唯一漏斗，悬浮窗（ReplyPanel）与调试入口都走它；这里只是没有 UI 入口了。 */
@Composable
private fun ChatDetail(c: HubConversation) {
    val listState = rememberLazyListState()

    // 滚动期间把 dock 的磨砂关掉（见 LocalDockFrost 的说明）
    val scrolling by remember { derivedStateOf { listState.isScrollInProgress } }
    CompositionLocalProvider(LocalDockFrost provides dockFrostFor(scrolling)) {
        // 用户要求：「聊天页每次都要自动滑动到最下面视觉不好」。
        // 所以只有**用户本来就在底部**时才跟着新内容走 —— 正在往上读历史的人
        // 不会被一秒一次的动画拽回去。判断放在布局之后（visibleItemsInfo 已经
        // 反映最新一帧），并且用 scrollToItem（瞬时）而不是 animateScrollToItem：
        // 流式回答每来一段就播一次滑动动画，正是「视觉不好」的来源。
        val atBottom = remember { mutableStateOf(true) }
        LaunchedEffect(listState) {
            snapshotFlow {
                val info = listState.layoutInfo
                val last = info.visibleItemsInfo.lastOrNull()
                last == null || (last.index >= info.totalItemsCount - 1 &&
                    last.offset + last.size <= info.viewportEndOffset + 24)
            }.collect { atBottom.value = it }
        }
        // 进入这条会话时**直接定位**到最后一条（无动画、不闪），只做一次
        LaunchedEffect(c.cid) {
            if (c.messages.isNotEmpty()) listState.scrollToItem(c.messages.lastIndex)
        }
        // 新内容到达时跟随（仅在底部）
        LaunchedEffect(c.messages.size, c.messages.lastOrNull()?.text?.length) {
            if (c.messages.isNotEmpty() && atBottom.value) {
                listState.scrollToItem(c.messages.lastIndex)
            }
        }

        Column(Modifier.fillMaxSize().imePadding()) {
            Box(Modifier.weight(1f)) {
                if (c.messages.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "这条会话还没有内容", color = AppColor.textFaint, fontSize = 13.sp,
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(c.messages, key = { it.mid }) { m -> MessageBubble(m) }
                    }
                }
            }
            ReplyHintBar()
        }
    }
}

/** 一条答复。think 行单独画成一条灰条（和豆包 App 的「思考中」一致）。
 *
 *  第 41 条起磁盘上也有 `role=user` 的行（用户自己发出去的），所以这里按角色
 *  分开画：用户那条靠右、用主色底，别和豆包的回答混在一起。 */
@Composable
private fun MessageBubble(m: HubMessage) {
    val mine = m.role == "user"
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        if (m.think.isNotBlank()) {
            Row(
                Modifier
                    .clip(AppShape.cardSmall)
                    .background(AppColor.fill.copy(alpha = 0.05f))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(5.dp).clip(CircleShape).background(AppColor.accent2))
                Spacer(Modifier.width(8.dp))
                Text(
                    m.think, color = AppColor.textDim, fontSize = 12.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(6.dp))
        }
        Box(
            Modifier
                .fillMaxWidth(0.94f)
                .clip(
                    RoundedCornerShape(
                        topStart = if (mine) 16.dp else 4.dp,
                        topEnd = if (mine) 4.dp else 16.dp,
                        bottomEnd = 16.dp, bottomStart = 16.dp,
                    )
                )
                .background(if (mine) AppColor.accent.copy(alpha = 0.28f)
                    else AppColor.bubbleBot)
                .padding(horizontal = 14.dp, vertical = 11.dp),
        ) {
            Column {
                Text(
                    m.text.ifBlank { if (m.ended) "（空回复）" else "…" },
                    color = AppColor.text, fontSize = 14.5.sp,
                    lineHeight = 21.sp,
                )
                if (m.ended) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (mine) "已发给豆包" else "已生成",
                        color = if (mine) AppColor.accent else AppColor.ok,
                        fontSize = 10.5.sp,
                    )
                }
            }
        }
    }
}

/** 第 48 条：原来这里是「回复输入框 + 发送按钮」([ReplyBar] 已删)。
 *
 *  用户要求把 **App 内的回复能力取消**，只留悬浮窗 —— 所以这里只剩一行提示，
 *  整页变成**只读**的会话记录（可看、可滚动、气泡样式与行为一个都没变）。 */
@Composable
private fun ReplyHintBar() {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(AppColor.accent2))
        Spacer(Modifier.width(8.dp))
        Text(
            "回复请用岛上的悬浮窗（回答结束后点岛卡上的「回复」）",
            color = AppColor.textFaint, fontSize = 11.5.sp,
        )
    }
}

@Composable
private fun EmptyChatHint() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(28.dp),
        ) {
            Icon(
                Icons.Outlined.ChatBubbleOutline, null,
                tint = AppColor.textFaint.copy(alpha = 0.6f),
                modifier = Modifier.size(44.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text("还没有会话", color = AppColor.textDim, fontSize = 15.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                "等豆包开口，或者到「设置」页用模拟通道造一条会话\n" +
                    "（模块没生效时也能验证界面）",
                color = AppColor.textFaint, fontSize = 12.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                lineHeight = 18.sp,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                "会话号示例：" + isolateHint(),
                color = AppColor.textFaint.copy(alpha = 0.8f), fontSize = 10.5.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

private fun isolateHint(): String = "ib_sim.txt → chat.start/delta/end"
