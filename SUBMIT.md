# v1.2 提交材料

星河岛（`astraflow.cc/island`）没有插件市场、没有上架表单，所以这里的「提交」指的是
**用正式签名重新打包出的 APK**，并把下面这段如实的简介放到分发页（GitHub Release 等）。

## 一、可直接粘贴的简介（v1.2）

> **豆包岛桥 v1.2** —— 把手机版豆包的回答实时投到星河岛。
> 手机侧走 LSPosed 钩子（豆包进程内），**电脑端已从本项目分离**（`pc/` 冻结留档，
> 安卓端不再连接/不再显示/不再发送到电脑端）；岛上可以直接回复、删除会话。
> 本版是**真机联调修复版**：修掉了「root 组件起不来、扫进程卡死、namespace 看不见豆包数据、
> 孤儿 nc 占端口、岛卡片不渲染、保活告警刷屏、卸载后又被自动装回、卡片不常驻、
> 岛上文字停在开头不滚动、配置行被藏起」等 **48 个**真机问题；
> 第 33 条是交付后用户报的「会话的标题没有被显示」：手机侧推流走 native IM 通道，
> 从来拿不到桌面端那条 `conversation_info`，卡片标题一直是兜底值；
> 现已从豆包会话对象里学会真名（必须读字段，那个 bean 的属性是 `@JvmField`，
> 没有 getter），标题变为「安卓包-<会话名>」（真机实测 `title='安卓包-测试'`）。
> 第 34 条按用户要求**整体切掉电脑端**（SSE 客户端、电脑端配置块、`pc on` 模拟来源、
> root 中继/局域网监听组件、`INTERNET` 等网络权限全部删除），
> 第 35 条把**包名与代码 namespace** 从 `com.islandbridge` 全量改成 `com.tg.dbisland`。
> 第 38 条把**界面整体重做**成 Compose + 液体玻璃：底部 **dock** 三格
> —— **聊天 / 日志 / 设置**；聊天页左边是**可折叠的会话框**（列出所有豆包会话，
> 点一条切换）、右边是**具体聊天页**（气泡对话，只读可滚 —— 回复入口已按第 48 条
> 收进岛上的悬浮窗）；
> 日志页是**模块运行情况**（模块心跳 / 岛连接 / 推流帧 / 上行命令 + 级别筛选的
> 实时日志流）；设置页是**岛的显示配置**（正文一行字数、胶囊分组长、
> 回复输入条开关、岛上预览——都即时生效）与 **root / LSPosed 正常检测**。
> 其中**回复上行**是一个真机才会现形的坑：App 发的命令广播在 Android 14+ 默认不带
> 发送方身份，模块拿到的是**接收方自己**的 uid，于是被自己的 uid 白名单拦下。
> v1.1 的 6 条安全整改**仍然有效**（其中的 root 监听/卸载两条已随电脑端一并移除）。
> **改名只需要做一件事**：旧包 `com.islandbridge` 是另一个 applicationId，不会自动
> 消失，需要用户自己卸载。**LSPosed 不用手动折腾** —— 清单里的 `xposedscope` 让它在
> 安装时自动登记并启用新模块（实测 `modules_state` = `enabled=1`，
> 作用域 = `system` + `com.larus.nova`，见 `CHANGELOG.md` 第 37 条对第 35 条的更正）。
> 已移除的权限（如实声明）：本版**不再申请** `INTERNET` / `ACCESS_NETWORK_STATE`
> （没有任何网络请求，也没有 `usesCleartextTraffic`）。
> 已知限制（如实声明）：豆包进程被系统冻结/回收时收不到推流（钩子住在豆包进程里），
> 需把豆包拉回前台；本 App 在前台时星河岛不显示本 App 的内容（岛规则）。

## 二、产物与实测

| 来源 | 说明 |
|---|---|
| APK | `android/dist/doubaodao-v1.2-release.apk` |
| 包名 | `com.tg.dbisland`（`namespace` 与 `applicationId` 一致；`aapt2 dump badging` 实测） |
| 界面 | Compose + 液体玻璃；底部 dock 三格（聊天 / 日志 / 设置），聊天页左会话框可折叠 + 右聊天页 |
| 大小 | **1513629 字节**（比上一版 1722540 小：第 48 条那几处改动 + R8 重新收紧） |
| SHA-256 | `fc7a36ebe8cafc50ab255f315814c440e176c4c9e3274e874568b652ef1b1d18` |
| 签名 | `CN=IslandBridge, OU=Doubaodao, O=IslandBridge, C=CN`，证书 SHA-256 `738c3ae2…614`（与 v1.1 同一把；但**因为 applicationId 变了，这次是并存安装而不是覆盖升级**） |
| versionCode / Name | `3` / `1.2` |
| debug 标志 | 无 `application-debuggable` |
| 图标 | `application: label='豆包岛桥' icon='res/BW.xml'`，`application-icon-160/240/320/640/65534` 均有值 |
| 自定义/签名级权限 | 3 个：`com.tg.dbisland.permission.CONTROL` / `PUBLISH_ACTIVITY` / AndroidX `com.tg.dbisland.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` |
| 普通系统权限 | **5 个**：FOREGROUND_SERVICE / FOREGROUND_SERVICE_DATA_SYNC / POST_NOTIFICATIONS / RECEIVE_BOOT_COMPLETED / WAKE_LOCK（`INTERNET`、`ACCESS_NETWORK_STATE` 已随电脑端删除） |
| root 资产 | **无**（`assets/root/` 6 个文件已整目录删除；App 不再向 `/data/adb` 写任何东西） |
| 回归测试 | `python -m unittest discover -s pc/tests` → **Ran 72 tests — OK**；DSH 插件 `node --run test` → 94 断言 0 失败 |

## 三、v1.2 真机修复（48 条，详表见 `CHANGELOG.md`）

| # | 问题 | 修法 |
|---|---|---|
| 1 | launcher 把自己误判成 worker → root 组件起不来 | argv 末参精确匹配 + pidfile；App 拆两次 su |
| 2 | 逐个扫 /proc 太慢、D 状态阻塞（rc=137/4 分钟） | `grep -lF` 批量扫：41ms |
| 3 | App 的 su 看不见 `/data/data/com.larus.nova` | `nsenter -t 1 -m` 重入 init ns |
| 4 | 孤儿 `nc` 占 8799 | 记 nc pidfile + 清理孤儿 + 退避限流 |
| 5 | 岛卡片不渲染（照片类图片不支持呼吸） | glyph → 内置符号，build 失败再重试 |
| 6 | 保活告警刷屏 | 60s 节流 + 只记 info |
| 7 | 卸载后 App 又自动装回 root 组件 | `uninstall.sh` 落标记，前台重装才清 |
| 8 | 回复上行误判失败 + 之后岛不再刷新 | 超时 1.5s→8s；无回执不做破坏性动作 |
| 9 | 卡片不常驻 | `DismissPolicy.untilEnded()` + 始终给「我知道了」+ 可切 GenericCard |
| 10 | 岛上文字停在开头、不滚动 | 正文取尾部；最新文字放**胶囊右侧**做滚动 |
| 11 | 推流中断时卡片静静停住 | 15s 看门狗 → 胶囊改「内容已暂停」 |
| 12 | IP/端口配置行不见了 | `SHOW_PC_CONFIG=true` + 上半部分放进 ScrollView |
| 13 | **回复上行被自己的 uid 校验拦下**（真机 uid 误判） | App 发命令时开身份共享；模块不再用 `Binder.getCallingUid()` 兜底、「uid == 自己」视为无法归因 |
| 14 | 豆包退后台收不到内容 | **30s 保活窗**：离开前台开窗（唚醒锁 + 3s 一次 binder ping），有推流就续期，静默 30s 收手交回系统 |
| 15 | 【自查自纠】模块在所有进程都注入失败 | companion 里不再做触碰 Android 运行时的初始化（主线程 Handler 改惰性） |
| 16 | 豆包在后台时提问发不出去（要手动打开豆包才回复） | 发送后无回执则用 **root 唤醒**（`am start-service` + root 直投，按 id 去重）；进程被回收时**如实上报**并在**息屏**下用豆包自己的通道补发 |
| 17 | 岛里回复后豆包什么都没回 | 发送层**等真实回执**再报结果（不再假成功）；只认 `sendMessageV2` 为模板源（治模板中毒）；被拒时自动用豆包自己的通道补投 |
| 18 | 胶囊里到处是「…」 | 改成 15 字一组的歌词播放器，组内流式、组满换组，不再截断 |
| 19 | 在豆包里发完瞬退就没回答 | 发送即开保活窗 + 8s 无推流则叫回豆包（不重发） |
| 20 | 装完 App 岛上什么都不显示 | ColorOS 拦自启动（OplusAppStartupManager），装完需打开一次或允许自启动 |
| 21 | 省略号还在冒（正文那两行） | `tailOf()` 不再补 `…`；正文上限 48→36 字；分组默认 10 字且可用 `ib_lyric.txt` 实时调 |
| 22 | 推流中卡住/不刷新 | 正文等长不断句 + 刷新节拍 1s；岛窗口 Relayout 降到 2 次 |
| 23 | 正文只显示一行 | `EXPAND_MAX=18字`，超出部分滑动窗口滚动 |
| 24 | 岛上出现 `**` | 新增 `markdownToPlain()`，正文与胶囊都只显示纯文本 |
| 25 | 正文仍是两行 | 正文一行字数可实时调（App 内 −/＋ 与岛上预览） |
| 26 | 卡片形态 | 改成明细卡：一个 logo + 手机/电脑并列（按存在且有响应逻辑） |
| 27 | 卡片形态 | 明细卡形态**已撤下**（默认消息卡），可用 `pc/sim_island.py` 做来源模拟 |
| 28 | 收起态显示 | 胶囊左侧静态显示 安卓包/电脑包 + 回答进行/回答结束；**右侧滚动区留空、呼吸动画关闭**（收起时完全静止）；停留 10 分钟 |
| 29 | 胶囊两槽 | 左=来源（安卓包/电脑包）、右=状态（回答进行/回答结束）；SDK 无法控制岛长度，缩短文案是唯一旋钮 |
| 30 | 无人确认 | 一分钟无操作 → 状态栏「您的豆包消息未确认」（内容可展开）+ 岛收起来；任何操作都会取消该计时 |
| 31 | 图标 | 启动图标 + 胶囊来源图标都用豆包 App 图标（取自本机 base.apk 的 mipmap-xxxhdpi-v4） |
| 32 | 多会话：回复卡片只有一份状态、共用一个活动 id | 改成**按会话一份**（`Conv` + 活动 id `reply:<cid>`）：**先到内容的那条会话占主岛**（`Priority.HIGH`），后来者进副岛（`DEFAULT`）；**先到者被收掉后**，下一条还在线的会话用 `HIGH` **重新 post 一次**升为主岛；按钮/回复/删除经 `convOfId()` 反解，只作用于自己那张卡；点卡片改成每会话一个 `PendingIntent`（extra 带 `cid`）。真机证据见 `CHANGELOG.md` 第三节 43~50 行 |
| 33 | 会话标题不显示：卡片标题永远是兜底值 | 手机侧推流走 native IM 通道，桌面端那条 `conversation_info` 根本不出现 → 模块改为从豆包会话对象学真名（`OmniConversationDispatcher` 入参 + `getConversation(cid)` 主动查 + `chat.end` 后补查），发 `chat.conv`；App 侧学到后**按需重推同一张卡**。**必须读字段**（`@JvmField`，没有 getter）。真机：`会话名 cid=849922 '测试'（2字）来源=chat.conv` → `岛card … title='安卓包-测试'`，见 `CHANGELOG.md` 第三节 51、52 行 |
| 34 | **切割电脑端**（用户要求：安卓端彻底断） | 删除 `SseClient.kt` + okhttp 依赖、`BridgeApp.link`/`owner`、`MainActivity` 的「电脑端连接配置」整块、`BridgeService` 的「保持与电脑的连接」文案、`IslandBridge` 的 `PC_LIVE_MS`/`simPcLive()`（`pc on`）/`电脑包`/`deleteViaPc`/`sendViaPc`、`EventProvider` 的 `"pc","desktop","电脑" -> false` 映射、`Receivers` 的 `EVENT_PC`；**root 中继组件**（`assets/root/` 6 个 + App 内安装/卸载/令牌入口）整体删除；`INTERNET`/`ACCESS_NETWORK_STATE`/`usesCleartextTraffic` 一并去掉。保留：hook 推流、星河岛卡片、多会话（`reply:<cid>` + 先到者占主岛）、保活/唤醒、一分钟无操作通知者收起、豆包图标、胶囊左=来源/右=状态；模拟通道保留多会话 JSON 注入，删掉「模拟电脑在线」。`pc/` 冻结留档（新增 `pc/FROZEN.md`），`sim_multi.py` 移到 `tools/`。证据：`CHANGELOG.md` 第 34 条 + 第三节 54、55 行 |
| 35 | **包名迁移** `com.islandbridge` → `com.tg.dbisland`（含 namespace） | `build.gradle.kts` 的 `namespace`/`applicationId`、源码目录 `java/com/islandbridge/**` → `java/com/tg/dbisland/**`（11 个 `.kt`）、全部 `package`/`import`、签名权限、provider authority `com.tg.dbisland.events`、全部自定义 action、`assets/xposed_init` 入口类、`proguard-rules.pro`、`BridgeSecurity.bridgeUidOf()` 的 `getPackageUid("com.tg.dbisland")`、root 唤醒脚本里的 `/data/data/com.tg.dbisland/files/ib_send.req` 与 `am broadcast -a com.tg.dbisland.SEND`。证据：`aapt2 dump badging` 的 `package: name='com.tg.dbisland'` + `application-icon-*` 有值 + `label='豆包岛桥'`；`am start -n com.tg.dbisland/com.tg.dbisland.MainActivity` 成功、无崩溃（`CHANGELOG.md` 第 35 条 + 第三节 56 行）。**用户可见后果**：旧包得自己卸、LSPosed 要为新包重新启用并勾选作用域 |
| 36 | **设备侧收尾：电脑端根组件撤离 + 星流来源残留清理** | 星流 `shared_prefs/island_app.xml` 的 `seen_sources` 里永久留着 `com.islandbridge`/`com.tugou.dsh`/`com.tgdsh.xingdao` 三个死条目（包早卸载也不清）—— 这才是「星流识别到残留」的真身；已删并回读校验（1359→1235 字节，属主权限保持 `u0_a50:u0_a50 660`）。设备侧用 `uninstall.sh` 撤掉 PC 中继：relay/listen/`nc -p 8799` 进程无、8799 不监听、`/data/adb/islandbridge` 与 `/data/adb/modules/islandbridge` 均不存在；旧包 `com.islandbridge` 已卸载。证据：`CHANGELOG.md` 第 36 条 + 第三节 58~60 行 |
| 37 | **事实更正：改名后 LSPosed 不需要人工启用** | 第 35 条曾写「必须在 LSPosed 里为新包重新启用」。实测**不需要**：清单 `xposedscope` 让 LSPosed 安装时自动登记并启用，`modules_state(com.tg.dbisland,0,enabled=1)`、`scope` = system + com.larus.nova。同时完成新包端到端验证（6 帧：保活续期 + `岛card … title='安卓包-豆包'`）。证据：`CHANGELOG.md` 第 37 条 + 第三节 61、62 行 |
| 38 | **界面重做：液体玻璃 + 底部 dock + 三页** | Compose 化（Kotlin 2.1.20→2.2.10 + compose 插件 + 依赖钉在缓存版本，不引第三方 UI 库）；`MainActivity` 从「LinearLayout + chat.html WebView」变成 `ComponentActivity + setContent`；删除 `ChatStore.kt` 与 `assets/chat/`；新增 `ui/` 9 个文件（`BridgeHub` 数据源 + 三页 + 玻璃/着色器）；新增 `IslandBridge.sendReply(cid, text)`。玻璃实现**返工三次**：半透明 → 糊背后内容（用户：整页都磨砂了）→ 糊面板自己（用户：糊多了、看不见内容、dock 跑到中间）→ 最终「高不透明度底 + 渐变反光 + AGSL 内阴影 + 亮边」，并把 shader/坑都写进注释。另删掉全屏遮罩。证据：`CHANGELOG.md` 第 38 条 + 第三节 63~66 行 + 截图 `dev/ui_final_chat.png`、`dev/ui_title_open.png`、`dev/ui_final_logs.png`、`dev/ui_settings.png` |
| 39 | **幽灵会话占主岛（测试残留）** | 界面重做复测发现真实会话只有 `prio=default`；根因是留下的 `filesDir/ib_sim.txt` 被每次启动重放（去重水位只在内存），两条 `sim-*` 幽灵会话先到并占住主岛。删除该文件 + 重启后复测 `prio=high`。`ib_sim.txt` 仅 root/adb 可写，非用户可见缺陷；~~已记入「重放语义是坑」的后续改进建议~~ **→ 第 41 条已按「处理完即清空」修掉**。证据：`CHANGELOG.md` 第 39 条 + 第三节 69 行 |
| 40 | **岛上直接回复（展开才给回复框）** | 平时 `GenericCard` 保常驻与「主岛归先到者」；宿主回调 `onExpanded(reply:<cid>)` → **同一个 id** 重投 `MessageCard`（豆包头像 + 会话名 + 当前正文 + `setReplyEnabled(true)`，发送按钮是宿主自带的）；`onCollapsed` → 同 id 切回 `GenericCard`；`onReply(id, text)` → 反解 cid 走原有发送通道真发给豆包，卡片副标题/状态位改成「已交给豆包发送」（不动标题），日志只留 cid 后 6 位与字数。`PREF_REPLY_BAR` 保留（打开=永远 MessageCard），默认改为新行为；`QUOTA_EXCEEDED`/`RATE_LIMITED` 不再静默。模拟通道新增 `sim.expand/collapse/reply`（与宿主回调同一批函数）。证据：`CHANGELOG.md` 第 40 条 + 第三节 70~72 行 |
| 41 | **日志与聊天记录固化到磁盘（各 100 MB 上限）** | 新增 `LogStore.kt`（`filesDir/logs`，~6 MB 一片，总量 >100 MB 时从最旧的片整片删，写盘在后台线程、IO 异常只降级一次）与 `ChatStore.kt`（`filesDir/chat/<cid>.jsonl`，一行一条 JSON 且转义，单会话 400 行上限，全局 >100 MB 时删最旧的会话整份）。「日志」页改读磁盘并显示占用；设置页新增「固化与清理」卡片（占用 + 清空日志 / 清空聊天记录）。**R9「聊天记录已落盘（`files/chat/<cid>.jsonl`，全局上限 100 MB，超出删最旧）。上限压到 8 KB / 6 KB 实测过真实裁剪（`logs/sim_limits_41.txt` 证据）。**诚实边界：LSPosed 模块在豆包进程内，它自己的日志仍然只在 logcat。** 证据：`CHANGELOG.md` 第 41 条 + 第三节 73~77 行 |
| 42 | **消息卡去按钮** | 消息卡一个按钮都不给，只留回复框 + 宿主自带发送按钮（原「我知道了/删除会话」被宿主画成勾/叉小图标）。证据：`CHANGELOG.md` 第 42 条 |
| 43 | **岛卡形态固定为一种** | 全程只用 `MessageCard`、`setReplyEnabled(true)` 全程保留回复框、不给按钮；实测 `卡片形态统计: {'msg': 9}`、无形态切换、无异常。证据：`CHANGELOG.md` 第 43 条 |
| 44 | **岛卡方案定稿 A** | 平时通用卡常驻（受 Priority 管辖）、展开才换消息卡给回复框；消息卡正文与通用卡同一份一行文本，消除两行→一行闪烁。证据：`CHANGELOG.md` 第 44 条 |
| 45 | **岛上三按钮 + 本应用自己的悬浮窗回复面板** | 卡片按钮按 `[回复,知道了,删除] → [知道了,删除] → [知道了] → []` 四档降级，真机接受第 1 档（`岛按钮组合被接受: [回复,知道了,删除]（第 1/4 档、通用卡）`）。点「回复」不再用宿主输入框，而是弹本应用的 `TYPE_APPLICATION_OVERLAY` 面板（可打字、`IME_ACTION_SEND`、贴输入法上沿、锁屏不弹、30s 无操作自动关）；面板出现 `end()` 把岛**缩回**，关掉再 `start()` **补回**。全链路用 `sim.overlay`（与手指点按钮同一个 `onAction`）无人值守跑通：`已发送到手机豆包` → 豆包继续作答 → `files/chat/<cid>.jsonl` 尾部同时留下**自己发出去的那句**和**新回答**。顺带把上行记录下沉到唯一漏斗 `sendReplyToConv()`（原来悬浮窗这条一条都没记）。证据：`CHANGELOG.md` 第 45 条 |
| 46 | **设置页重排 + 主题进设置 + 浅色底部黑带 + Documents 编号副本** | 设置页按「外观 / 岛显示 / 运行环境 / 固化与清理 / 调试 / 关于」六组重排，组内行样式统一、删掉不符行为的旧文案（不改行为、不改 pref key）。`ThemeMode{SYSTEM,LIGHT,DARK}` 存 `theme_mode`，改完**立刻全应用生效**（状态驱动配色）、冷启动保持、系统栏图标跟着翻。浅色底部黑带根因是 `AppShell` 渐变第三色标写死 `Color(0xFF080A0E)`，改 `AppColor.bgBottom`（浅色 `0xFFE7EBF5`），并把硬编码黑白统一换成 `AppColor.fill`。Documents 日志镜像出 `(1)(2)(3)` 编号副本的根因是分片名带 `:` 被 MediaStore 改写导致按名精确查永不命中：分片改 `_`、`find()` 改成归一化容错匹配、启动时 `dedupe()` + 同名覆盖重写（真机清掉 **6 个**编号副本，之后一直是 1 个文件）。证据：`CHANGELOG.md` 第 46 条 + `dev/theme_{light,dark}_bottom.png` |
| 47 | **中途再发一条 → 第二次的回答只显示头部、不再流式** | 用户原话：「会话中，中途向豆包发送内容，第一次的内容正常显示，发送之后第二次的内容只显示头部的一些输出然后不会流式显示下文」。根因是模块侧 `MobileFeedParser.mid` 这个**共享可变字段**：`react()` 遇到任何嵌套的 `message_id/msg_id/mid/server_message_id` 就改写它，用户中途插话时同一条流夹带那条**用户消息**的回声帧（`meta.user_type==1`），一条回答于是被拆到两个消息号上、`isUser` 判真后**后续增量被静默丢掉**（用户落盘记录 `#22 len=159 ended=False` 是 `#24 len=524` 的**严格前缀**）；omni 通道更把**新回答的 38 条增量、490 字挂在上一条回答的 mid 上**。修法：开轮钉住 `curMid`（整轮只用它、不跨轮复用）、用户回声只在本轮开始前丢帧、`feedOmniMessage` 换 messageId 就先收上一轮、`emitShared` 的 `sseMids` 不再在 `chat.end` 就遗忘、App 侧 `finishMessage` 按 mid 收尾且**绝不写用户那条**、`IslandBridge` 用 `chat.reply` 全量文本做单向兜底。实测：中途插话第 1 轮 74 字 / 第 2 轮 457 字，两轮顺发 513 / 456 字，均 `ended=True`、用户那句不再被覆盖；单轮 `回答进行 → 回答结束` + 三按钮第 1 档照旧。证据：`CHANGELOG.md` 第 47 条 + `dev/rep_interrupt_fix3.log` / `dev/rep_share_reply_fix.log` / `dev/rep_one_turn.txt` |
| 48 | **岛卡按状态换按钮组 + 回复只留悬浮窗 + 删除不再「收不掉 / 会复活 / 卡在后台」** | 用户三条原话：①「回答结束才可以显示回复按钮的模板，正在回答则使用两个按钮的模板，app 内取消可以回复的能力，只保留悬浮窗可以回复的能力」；②「我点击删除之后，我们岛还有内容在输出，甚至还有流式显示」；③「离开豆包后在岛上选择删除会卡一会，进入豆包之后才看见被删除了，岛上还出现了结束回复才有的模板」。修法：`buttons(st,msg)` 按状态换组（回答结束 `[回复,知道了,删除]`（第 1/4 档）/ 其余 `[知道了,删除]`（第 1/3 档），同 `GenericCard` 同 id，只换按钮组）；`ChatPage` 去掉回复输入行（聊天页只读，`EditText` 节点数 = 0，截图 `dev/item48_chatpage_noinput.png`）；`sendReplyToConv()` 仍是唯一上行漏斗，悬浮窗仍是唯一入口。删除三处真 bug：**①** `chat.delta` 里 `suppressed` 判断原来在 `beginConv` 兜底之后 → 收卡后下一帧 delta 把卡片复活（修复前收卡后 25s 还上岛 **56 帧**/模块 98 条 delta，修复后 **0 帧**/109 条全丢）；**②** 删除是广播、豆包在后台被冻住就得等它解冻（用户那次 3.4s 且回执变「无主」）→ 新增 `ST_DELETING =「正在删除…」` 状态位 + `chat.end`/`chat.conv` 改走 `statusWord`（不再把状态写回「回答结束」、不再换成带「回复」的三按钮）+ `armDeleteWake` root 唤醒（2.6s 无回执 → 拉起豆包 + 重投三次 DELETE，超时 8s→14s；force-stop 豆包的等价场景 **5.75s** 拿到回执）；**③** 删除成功的回执落**墓碑 `deadCids`**（含「无主回执」分支）+ 空 cid 的 `chat.*` 帧不再凭空建 `reply:-` 卡（改接最近一条活着的会话，没有就丢）→ 修前 `--stop` 场景删除后 25s 还有 **53 帧**，修后 **0 帧**（同期模块仍投 70 条 delta，全被丢）。顺带修掉悬浮窗 `EditText` 拿不到焦点（外层 FrameLayout 抢焦点 → 硬件键盘/ADB 打不进字）：`FOCUS_AFTER_DESCENDANTS` + 200/700ms 补 `requestFocus()`，日志 `输入框 requestFocus=true hasFocus=true`。证据：`CHANGELOG.md` 第 48 条 + `dev/item48_report.txt` / `dev/item48b_report.txt` / `dev/item48c_bgdelete_report.txt` / `dev/item48d_wake_report.txt` + 截图 `dev/item48_island_answering.png`、`dev/item48_island_done.png`、`dev/item48_chatpage_noinput.png` |

## 四、上一轮 5 条预审整改（v1.1，仍然有效）

原文见下方 v1.1 章节，摘要：

| # | 预审意见 | 现状 |
|---|---|---|
| 1 | `SEND`/`DELETE` 接收器导出且无校验 | 改为 signature 级权限 `com.tg.dbisland.permission.CONTROL` + uid 白名单 |
| 2 | root 监听 `0.0.0.0:8799` 且无令牌 | ~~默认只绑 `127.0.0.1`，强制 32 位十六进制令牌（0600）~~ **整改对象已随电脑端删除**：那套局域网监听（`listen.sh`）本来就是给电脑端灌帧用的，第 34 条已整体移除，App 不再监听任何端口 |
| 3 | `/data/adb` 自启服务卸载后残留 | ~~标准模块布局 + `uninstall.sh`~~ **整改对象已随电脑端删除**：App 里已没有 root 组件安装/卸载入口，也不再向 `/data/adb` 写任何东西；设备上历史遗留的 `/data/adb/modules/islandbridge` 由用户在 root 管理器里移除 |
| 4 | debug 证书 + debuggable | release 签名（自建证书）+ 关闭 debuggable |
| 5 | 行为披露不完整 | `README.md` / `SECURITY.md` 如实披露 |

> 第 2、3 条的**历史整改记录**保留在下面 v1.1 章节与 `SECURITY.md` 里（那是过往事实，不改写）；
> 上面只更新「现状」一栏：这两条的风险面随着电脑端整体移除而消失。

## 五、提交前清单

- [x] release 签名、无 debuggable、versionCode 递增
- [x] ~~root 资产与源码逐字节一致~~ **已无 root 资产**（第 34 条整目录删除）
- [x] PC 回归测试 72 用例 OK（`pc/` 冻结未改代码，只加了 `FROZEN.md`；第 40、41 条只动 Android 侧，再跑一次仍 72 OK）
- [x] 真机：**新包安装成功** `adb install -r` → `Success`
- [x] 真机：`aapt2 dump badging` → `package: name='com.tg.dbisland'`、`label='豆包岛桥'`、`application-icon-160/240/320/640/65534` 有值
- [x] 真机：root 拉起 `am start -n com.tg.dbisland/com.tg.dbisland.MainActivity` 成功，logcat 有焦点窗口与前台进程记录，**无 FATAL/AndroidRuntime 崩溃**
- [x] 真机：UI 里**没有电脑端配置块**，环境自检也**没有「Root 组件」行**，三个 root 组件按钮都没了（截图 `dev/ib_v12_cut.png`）
- [x] 真机：~~配置行可见（dump 实测 y=342~715，第一屏）~~ **配置行本身已删除**（第 34 条），改为验证「配置块不存在」（见上一条）
- [x] 真机：`uses-permission` 里**没有** `INTERNET` / `ACCESS_NETWORK_STATE`
- [x] 真机：豆包对话真实路径（钩子 → provider → 卡片持续更新）（v1.2 早前版本上验证；**本次改名后的包未复测**）
- [x] 真机：**回复上行**端到端（App → 命令广播 → 豆包进程 → `done 20ms err=ok` → App `send.result` → 豆包会话里真的出现该消息）（同上，本次未复测）
- [x] 真机：**会话标题**（真实豆包会话 + 真实回复流 → `会话名 … 来源=chat.conv` → 卡片标题 `安卓包-测试`）（同上，本次未复测）
- [x] ~~**人工 + 阻塞项**：在 LSPosed 里为 `com.tg.dbisland` 重新启用模块并勾选作用域~~
      **已证明不需要**（`CHANGELOG.md` 第 37 条）：`xposedscope` 让 LSPosed 安装时自动登记并启用。
- [x] **人工**：~~决定何时卸载旧包 `com.islandbridge`~~ **已卸载**（第 36 条：`pm path` 只剩 `com.tg.dbisland`）
- [x] **人工**：~~移除 `/data/adb/modules/islandbridge`~~ **已移除**（第 36 条：目录与进程、8799 监听均不存在）
- [x] **真机**：新界面三页逐页截图确认（第 38 条：dock 在底部、聊天页/会话框/日志页/设置页均正常渲染，会话框内容清晰可读）
- [x] **真机**：第 40 条岛回复链路（模拟通道 `sim.expand` → `sim.reply`，与宿主回调同一批函数）：`岛展开 … 同 id 换 MessageCard（带回复输入条）` → `岛card … card=msg` → `岛上回复 … 交本应用发送` → `已递交豆包进程发送` → `已发送到手机豆包`；真实豆包会话上再跑一次，豆包回答回在同一张卡上
- [x] **真机**：第 40 条配额/限流日志（8 会话同屏 → `QUOTA_EXCEEDED`/`RATE_LIMITED` 各一行，不再静默）
- [x] **真机**：第 41 条聊天记录固化 + R9 修复（冷启动 `已从磁盘恢复 1 条会话的聊天记录（共 3 条消息）`，`files/chat/<cid>.jsonl` 三行含用户那句）
- [x] **真机**：第 41 条日志固化（`files/logs/*.log` 一行一条，`固化:` / `上限:` 两行自检）
- [x] **真机**：第 41 条裁剪（上限临时压到 8 KB / 6 KB → `日志已按上限裁掉最旧的 1 片` + `聊天记录已按上限裁掉最旧的 1 条`；验证后复位 100 MB）
- [x] **真机**：第 41 条模拟通道不再重放（`模拟通道已投递 N 条并清空文件`，`ib_sim.txt` 归 0）
- [ ] **人工**：**真手指点岛上的回复框**（在豆包对话页或息屏下点开卡片 → 输入 → 发送）；本轮只验证了「宿主回调 → 换卡 → 真的发出去」这条链路
- [ ] **人工**：锁屏下岛**不显示回复框**（官方文档说的行为，代码里没有专门判断，未实测）
- [ ] **人工**：岛卡片目视确认（切到豆包或 Home，别停在豆包岛桥 App 里）
- [ ] **人工**：卡片常驻 / 胶囊滚动 / 「内容已暂停」提示
- [ ] ~~**人工**：岛上「删除会话」上行到 `pc/bridge_daemon.py`~~ **已不适用**（电脑端整体删除）
- [ ] ~~**人工**：App 里「卸载 root 组件」按钮路径~~ **按钮已删除**
- [ ] **未验证**：v1.1 → v1.2 覆盖升级（同证书 + versionCode 2→3 只是前提）；**并且从 `com.islandbridge` 到 `com.tg.dbisland` 不是覆盖升级，是并存的新包**
- [ ] **未验证**：改名后**岛上端到端**（豆包推流上岛、岛上回复/删除）—— 等 LSPosed 启用新包后才能做

---

（以下为 v1.1 提交材料原文，未改动）

# v1.1 提交材料（历史）

星河岛（`astraflow.cc/island`）没有插件市场、没有上架表单，所以这里的
「提交」指的是**用正式签名重新打包分发 APK**，并把下面这段如实说明
放到分发页（GitHub Release / 群文件 / 应用市场）的简介里。

---

## 一、可直接粘贴的「简介 / 描述」

> ### 豆包岛桥 · doubaodao v1.1
>
> 把豆包 AI 的流式回复和计划任务实时推送到手机**星河岛**（星流）灵动岛。
> 回复实时滚动上岛，可带头像与会话名；计划任务显示下载样式进度环；
> 卡片上可直接回消息、或一键删除会话。
>
> **⚠️ 使用前必读：这个 App 会做下面这些事**
>
> - **以你的名义发送消息**：只在你主动点岛卡片上的「回复」并提交文字、
>   或在 App 回复页提交时，通过豆包自身的发送接口发出。
> - **永久删除会话**：只在你主动点岛卡片上的「删除会话」时，调用豆包自己的
>   删除接口删除当前会话。**删除不可恢复，且岛侧没有二次确认。**
> - **读取你的回复内容并转发**：hook 豆包的流式接口，把回复文本推给星河岛，
>   以及你自己填写的电脑端（局域网）。
> - **后台唤醒豆包**：每 20 秒用 `startService` 拉起豆包的候选组件（不弹界面），
>   保证豆包进程活着才能继续抓流。
> - **以 root 身份常驻一段脚本**：安装到 `/data/adb/modules/islandbridge/`
>   （标准 KernelSU/Magisk 模块，可在 App 内或管理器里一键卸载；
>   **卸载 App 不会自动删除它**，卸载说明见仓库 README）。
> - **监听网络端口**：root 脚本默认**只监听 `127.0.0.1`**，并且**强制校验令牌**；
>   只有你手动把 `ib_listen.conf` 里的 `BIND` 改成 `0.0.0.0` 才会对局域网开放。
> - **联网**：只连你自己填写的电脑端 IP:端口（家用局域网明文 HTTP），
>   **不上传任何数据到第三方服务器**，没有广告与统计 SDK。
>
> **前置条件**：已 root + Zygisk + LSPosed（用来注入豆包进程）；
> 设备 Android 15+ 且已启用星河岛/星流；豆包版本 15.1.0。
>
> **本版更新（v1.1）**
> 1. 命令通道加签名级权限（`com.islandbridge.permission.CONTROL`）保护，
>    并对所有导出接收器做发送方 uid 白名单校验；
> 2. root 监听默认仅本机 `127.0.0.1`，强制令牌鉴权；
> 3. root 组件改为标准 KernelSU/Magisk 模块，可一键卸载并附清理脚本；
> 4. 改用自建正式证书签名、关闭可调试；
> 5. 补齐行为披露、隐私说明与卸载说明；
> 6. 迁移到星河岛 SDK 0.1.0（通信版本 7）—— 旧接入库已停止服务。
>
> 仅供学习研究，请遵守相关软件的服务条款。

---

## 二、预审问题逐条复核

| # | 预审意见 | 现在的情况 | 在哪看 |
|---|---|---|---|
| 1 | SEND/DELETE 接收器导出且不校验发送方 | 注册时传入 **signature 级权限** `com.islandbridge.permission.CONTROL`，系统在投递前强制校验发送方是否持有；另加 uid 白名单（仅 0/2000/本 App）。manifest 导出的其余接收器每个 `onReceive` 首行做 uid 白名单校验 | `BridgeSecurity.kt`、`DoubaoHookEntry.kt`、`Receivers.kt`、`AndroidManifest.xml`；说明见 `SECURITY.md` §1 |
| 2 | root 监听默认 `0.0.0.0:8799` 且无 token | 默认 `BIND=127.0.0.1`；令牌由 `/dev/urandom` 生成 32 位并落 `token`(0600)，**不能关闭**，少于 16 位拒绝启动；请求必须匹配令牌前缀；指定绑定失败不再回退通配 | `assets/root/listen.sh`；`EnvCheck.kt` 会在 App 内显示「仅本机 127.0.0.1 · 已强制令牌」 |
| 3 | `/data/adb` 开机服务无法卸载 | 改为 `/data/adb/modules/islandbridge/` 标准模块（`module.prop`/`service.sh`/**`uninstall.sh`**）；App 内新增「卸载 root 组件」；自动清除 1.0 遗留的 `/data/adb/service.d/islandbridge.sh`；README 给出三条清理路径 | `assets/root/*`、`BridgeService.kt`、`MainActivity.kt`、`README.md` |
| 4 | debug 证书 + debuggable | release 用自建 RSA-4096/PKCS12 密钥库签名，`isDebuggable = false`；产物附 SHA-256；密钥不入库 | `app/build.gradle.kts`、`android/keystore/`、`SECURITY.md` §4 |
| 5 | 简介未披露 | 仓库 README 新增「行为披露 / 权限与隐私说明 / 卸载后剩什么 / root 组件安装与卸载」；本文件第一节是可直接粘贴的简介 | `README.md`、`SECURITY.md` §5、本文件 |
| 6 | 豆包切到后台后被 ColorOS HANS 冻结 → 进程内没有 Omni/SSE 回调 → 内容中断；解冻后积压帧一次性涌出（"突然给一大串然后卡住"），结束事件不到导致"回答结束却仍显示回答进行" | 在 **system_server** 侧拦下 HANS 的冻结**执行点** `HansCGroup.hansFreezeLocked`（两个重载），对目标 uid 在有界豁免窗口内返回"没冻成"；窗口由 App 在 `chat.start`/`chat.delta`/`chat.end` 续期，不在轮次里时自动过期；另留 1.5s `hansUnFreeze` 兜底。定位方法：`dexfindstr.py` 按日志文案反查（前面三轮猜路径全部落空） | `android/.../xposed/DoubaoHookEntry.kt`（HANS 段）、`IslandBridge.kt`（`armHansExempt`）、`dev/coloros_framework/dexfindstr.py`、`FREEZE_FIX.md` §5 |
| 7 | 会话记录里出现 `m0` 占位消息号：同一段正文分裂成两条（真实号那条 `ended=false`、占位号那条 `ended=true`），重建历史时表现为"已结束却仍显示回答进行"。**部分修复，根因已定位** | 已改：`mid`/`curMid` 不再共用字面量 `"m0"`；占位号改本轮唯一 `pending#<turnSeq>`；学到真实号时 `adoptMid()` 迁移整轮并给占位轮补 `chat.end`。**真机逐帧日志显示根因是双摄取通道 id 不一致**：`ev[sse] … mid=19009538`（HTTP chunk 路径）与 `ev[omni] … mid=pending#0`（OmniMessageDispatcher 路径）抱同一段回答但只有前者有真实 mid。用户可见结果正常（岛卡 `回答进行 → 回答结束`、结束记录正文完整），但记录分裂未根治，下一步做事源归并 | `android/.../xposed/MobileFeedParser.kt`、`CHANGELOG.md` 第 86/87 条、`ARCH_REVIEW.md` P1 |
| 8 | 岛卡正文「回答进行中读不到内容、只有结束时冒一下」 | **死代码遮蔽**：`pushReplyItem` 里 `if (c.containerBody.isNotBlank()) c.containerBody else tailOf(…)`，而 `containerBody` 恒为非空（只在 `flushContainer` 赋成当前 10 字分组）→ 设计好的「等长滑动窗口」分支从未执行，`chat.end` 推的完整正文也被丢弃。已改为推累积正文并统一走 `tailOf(clean, bodyMax(), boundary=ended)`（推流中等长滑动、无省略号；收尾按句边界给结论）；维持用户确认的「一行」策略。真机复查：整轮 `body=14字`（改前 8~10 字） | `android/.../IslandBridge.kt`（`flushContainer`/`pushReplyItem`）、`CHANGELOG.md` 第 88/89/90 条 |
| 9 | 冻结豁免窗口在轮次进行中被过早清空（出现最长约 10s 无保护间隙） | **代际竞态**：`hansArm` 每次新建 `Handler` 实例再 `removeCallbacks`，队列里 message 按 (Handler, Runnable) 配对，新实例移不掉旧实例 post 的过期回调；且无代际判断，旧回调把**当前**窗口清掉（真机：窗口开出后 **18ms** 就 `keepAlive=false（窗口到期）`，此后每 10s 一次）。已改为同一个 `hansHandler` + `hansGen` 代际判断（过期回调仅在代际未变时清窗口）。真机复查：`chat.start` 开窗 → `已拦下冻结 剩余=58s` → `chat.delta` 续期，**中间无 `窗口到期`**；本轮 `freeze uid: 10375` **0 行** | `android/.../xposed/DoubaoHookEntry.kt`（`hansArm`）、`CHANGELOG.md` 第 91/92 条 |
| 10 | 结束态模板没有按钮（用户报「结束事件的模板缺少了按钮」） | **根因**：`buttons(st, msg)` 对消息卡直接 `return listOf(emptyList())`（第 42 条"消息卡一个按钮都不给"的旧取舍）→ 结束态 MessageCard 按钮组恒为空。**修法**：结束态给 `[知道了,删除]` → `[知道了]` → `[]` 三档降级链（官方回复栏仍由 `setReplyEnabled(true)` 提供）。真机日志：改前 `[]（第 1/1 档、消息卡）` → 改后 `[知道了,删除]（第 1/3 档、消息卡）`。附带修掉吞证据的去重键（改为带卡片形态） | `android/.../IslandBridge.kt`（`buttons`/`buildReplyCard`）、`CHANGELOG.md` 第 93/94 条 |
| 11 | 设置页文字赘述 | 压缩分组副标题、步进器 hint、五段说明与两处按钮文案，删除三处与相邻控件重复的说明；`StartupCheck.kt` 三条「推断依据」各压成一句。真机四屏截图核对 | `android/.../ui/SettingsPage.kt`、`android/.../StartupCheck.kt`、`CHANGELOG.md` 第 95 条 |
| 12 | 发布版残留测试 / 调试内容 | 删除设置页「调试」分组（测试进度 / 测试歌词 / 结束测试三条伪造事件入口）、`SmallAction` 组件、`IslandBridge` 两个隐藏调试开关（`useMessageCard`+`PREF_REPLY_BAR`、`useDetailsCard`）及失效的 `DetailsCard` 分支与 import。卡形态改由会话状态唯一决定。真机已核对设置页无「调试」分组 | `android/.../ui/SettingsPage.kt`、`android/.../IslandBridge.kt`、`CHANGELOG.md` 第 97 条 |
| 13 | 交付的源码包在交付路径下编译不了 | 把交付 zip 解到中文目录直接 `BUILD FAILED`：AGP 拒绝路径含非 ASCII 字符的工程。已加 `android.overridePathCheck=true`（无 native/NDK 代码）。复测中文目录 → `BUILD SUCCESSFUL`；APK 逐字节未变 | `android/gradle.properties`、`CHANGELOG.md` 第 99 条 |
| 14 | 换一台机装完出现「回答一会就不回答」（用户报） | **不是代码问题，是安装时序**：模块装在了开机之后，而冻结豁免（HANS）装在 `system_server` 里、**只在开机时注入一次**，于是 system_server 跑的还是开机那份旧代码（日志里模块 id 仍是旧包名 `com.islandbridge`、HANS 相关 0 行），豆包一退后台就被冻结、推流 4 秒即断。已把交付说明第 8 节改为「**必须整机重启一次**」并写清判断标准；作用域点明为「豆包」+**「系统框架」**。整机重启后端到端复验：`HANS 已拦下冻结 uid=10329 … 剩余=54s`、退后台后仍产出 69 行事件、整轮 371 字在后台跑完、`freeze uid: 10329` 0 行。另清掉该机旧 root 设计的残留（开机脚本 + `nc -lk -p 8799` 活体监听） | `CHANGELOG.md` 第 101–106 条、交付说明第 6/8 节 |

---

## 三、提交前自检命令

```powershell
$env:JAVA_HOME='D:\tool\java\jdk17'; $env:ANDROID_HOME='D:\tool\android-sdk'
$bt='D:\tool\android-sdk\build-tools\36.0.0'
cd D:\aiwork\doubaoni\android

# 1) 构建正式包（产物 + sha256 落在 android\dist\）
.\gradlew.bat :app:distRelease

# 2) 签名者不得是 Android Debug；应为 CN=IslandBridge Release
& "$bt\apksigner.bat" verify --print-certs --verbose `
    ..\dist\doubaodao-v1.1-release.apk

# 3) 不得出现 application-debuggable；确认 versionCode=2 / versionName=1.1
& "$bt\aapt2.exe" dump badging ..\dist\doubaodao-v1.1-release.apk |
    Select-String 'package:|debuggable|PUBLISH_ACTIVITY|islandbridge.permission'

# 4) 确认签名权限与接收器导出状态
& "$bt\aapt2.exe" dump xmltree ..\dist\doubaodao-v1.1-release.apk `
    --file AndroidManifest.xml | Select-String -Context 0,3 'receiver|permission'
```

预期结果：第 2 步的签名者**不含** `Android Debug`；
第 3 步**没有** `application-debuggable` 行，且能看到 SDK 自动合并进来的
`com.astraflow.tool.island.permission.PUBLISH_ACTIVITY`。

### 已实测结果（v1.1 本地构建）

```
package: name='com.islandbridge' versionCode='2' versionName='1.1' compileSdkVersion='36'
Signer #1 certificate DN: CN=IslandBridge, OU=Doubaodao, O=IslandBridge, C=CN
Signer #1 certificate SHA-256 digest: 738c3ae28cc70ad5349c589b2c08d650b42cf22a2d8aa5f5b178ea11e90c5614
（aapt2 dump badging 无 application-debuggable 行）
uses-permission: com.islandbridge.permission.CONTROL
uses-permission: com.astraflow.tool.island.permission.PUBLISH_ACTIVITY   ← SDK 自动合并
<permission android:name="com.islandbridge.permission.CONTROL" protectionLevel=0x2（signature）>
APK SHA-256: 9be8e5e7a72ad3fb8c4dd715744bd354c9eb8c75d76101c2b40e4ce739ad3a38
             （同目录 doubaodao-v1.1-release.apk.sha256；每次重新构建都会变）
```

> 剩余风险（电脑端 8787 无鉴权、局域网明文、uid 无法归因时放行等）见
> [`SECURITY.md`](SECURITY.md) 末尾「剩余风险与已知取舍」；
> 真机验证到了哪一步见同文件 R6 与
> [`CHANGELOG.md`](CHANGELOG.md) 第七节。


---

## 四、装机复测清单

**当前设备状态（2026-10-04，PJZ110 / Android 16 / KernelSU / LSPosed 1.9.x）**：
v1.1 release 已装机并通过签名/版本/权限校验；旧版 v1.0 与早期试验包已卸载；
v1.0 的 `/data/adb` 残留（含当时活着的 `[::]:8799` 监听）已清除，**重启后未复现**。
**功能层复测待做**：本模块在 LSPosed 里尚未启用（`enabled=0`），新版 root 组件也还没装 ——
所以下面只能打勾已真实做过的两项。

- [x] 旧版残留清理后**重启**复核：`/data/adb/service.d/islandbridge.sh` 与
      `/data/adb/islandbridge` 未复现、`ss -tln` 无 8799、无相关进程
- [x] v1.1 release 装机校验：versionCode 2 / versionName 1.1 / 无 DEBUGGABLE /
      证书 `738c3ae2…` / `com.islandbridge.permission.CONTROL` 在 / `assets/root` 6 个
- [ ] 在 LSPosed 里启用「豆包岛桥」并勾选作用域（`com.larus.nova` + `android`）后重启
- [ ] 点 App 内「安装 root 组件」→ `/data/adb/modules/islandbridge` 出现
- [ ] ~~环境自检四项全绿，其中「Root 组件」显示「已安装 · 仅本机 127.0.0.1 · 已强制令牌」~~
      **已不适用**：第 34 条删掉了「Root 组件」这一行（现在只有三项）
- [ ] ~~电脑端不带令牌请求 `http://<手机IP>:8799` → 被拒（403）~~ **已不适用**：没有 8799 监听了
- [ ] ~~从另一台设备 `nc` 手机 `8799` → **连不上**（默认只绑 127.0.0.1）~~ **已不适用**：同上
- [ ] 用任意第三方 App 发 `com.tg.dbisland.SEND` 广播 → 豆包**不**发送消息（日志可见被拒）
- [ ] 豆包回复 → 卡片上岛；点「删除会话」→ 会话真的消失且卡片收起
- [ ] ~~点「卸载 root 组件」→ `/data/adb/modules/islandbridge` 消失、~~
      ~~`ps -A | grep islandbridge` 无残留、重启后不再自启~~
      **按钮已删除**；改为在 root 管理器里移除历史模块并复验无残留
- [ ] 卸载 App 后按 README 手动路径清理 → 同样无残留

