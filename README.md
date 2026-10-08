# 豆包岛桥 · doubaodao

把**豆包 AI 的流式回复和计划任务**实时推送到手机**星河岛**（AstraFlow / 星流）灵动岛。

- **回复 → 岛卡片**：豆包每条流式回复实时滚动上岛，带豆包头像与会话名
- **多会话 → 一会话一张卡**：多条会话同时回答时，**最先收到内容的那条占主岛**，
  其余进副岛；把主岛那条收掉（「我知道了」/ 点卡片空白处）后，下一条**还在线**的
  会话自动升为主岛。按钮 / 回复 / 删除都只作用于**它自己那张卡**
- **计划进度 → 下载样式进度环**：按子任务完成数显示 `done/total`
- **任务开始/结束 → 岛展开/缩回**：开始自动展开，结束以 outro 收尾
- **卡片操作**：点卡片空白处打开豆包 / 「删除会话」直接删掉豆包里的会话
- **回复 → 只走悬浮窗**：**回答结束后**岛卡上才出现「回复」，点了弹**本应用的悬浮窗**
  打字回发；**回答中只有「知道了 / 删除」两个按钮**，App 内也没有回复入口了（第 48 条）
- **环境自检**：App 内直接显示 Root / LSPosed / 模块注入 三项状态

> **电脑端已从本项目分离**（`pc/` 目录冻结留档，见 [`pc/FROZEN.md`](pc/FROZEN.md)）：
> 安卓端**不再连接、不再显示、不再向电脑端发送任何东西** —— SSE 客户端、App 里的
> 「电脑 IP / 端口」配置块、「电脑包」来源、`pc on` 模拟在线、root 中继/局域网监听组件、
> 以及 `INTERNET` / `ACCESS_NETWORK_STATE` 权限**全部删除**（详见
> [`CHANGELOG.md`](CHANGELOG.md) 第 34 条）。
> **包名也已从 `com.islandbridge` 全量改成 `com.tg.dbisland`**（含代码 namespace，
> 见第 35 条）—— 旧包 `com.islandbridge` 是**另一个应用**，不会自动消失，需要你自己卸载。
> LSPosed 那边**不用手动折腾**：清单里的 `xposedscope` 让它在安装时就**自动登记并启用**
> （实测 `modules_state` = `enabled=1`，作用域 = `system` + `com.larus.nova`，见第 37 条
> 对第 35 条的事实更正）。
>
> **界面已重做**（第 38 条）：Compose + 液体玻璃，底部 **dock** 三格
> —— **聊天 / 日志 / 设置**；聊天页左边是可折叠的**会话框**、右边是**具体聊天页**；
> 日志页显示**模块运行情况**；设置页（第 46 条重排为六组）是**主题 + 岛的显示配置 +
> 运行环境检测 + 固化与清理**。

> 当前版本 **1.2**（versionCode 3）。1.1 → 1.2 是**真机联调修复**：把 v1.1 装到
> 真机上跑通时挖出 **48 个**「装了但用不了 / 用起来不对」的问题，逐条改掉并在真机上验证；
> **安全模型未变**。其中最要命的一条是**回复上行**：App 发的命令广播在
> Android 14+ 默认不带发送方身份，模块拿到的是**接收方自己**的 uid，于是被自己的
> uid 白名单拦下（真机实测 `rejected: sender uid=10375`）。
> 第 33 条是交付后用户报的「会话的标题没有被显示」：手机侧推流从来拿不到桌面端那条
> `conversation_info`，卡片标题一直是兜底值 `安卓包-豆包`；现在改成从豆包会话对象里
> 学会真名（**必须读字段**，那个 bean 的属性是 `@JvmField`、没有 getter），
> 标题变成 `安卓包-<会话名>`（真机实测 `title='安卓包-测试'`）。
> 第 34 条按用户要求切掉电脑端，第 35 条改名到 `com.tg.dbisland`，
> 第 38 条把界面整体重做成 Compose（dock + 三页 + 会话框）。
> 第 45 条把岛上「回复」做成**本应用自己的悬浮窗回复面板**（三按钮四档降级、面板出现岛缩回 /
> 关掉岛补回、贴输入法上沿、锁屏不弹、30s 自动关）；第 46 条把**设置页按六组重排**、
> 把**主题（跟随系统 / 浅色 / 深色）搬进设置**（改完立刻全应用生效、冷启动保持）、
> 修掉**浅色主题底部那条黑带**、以及 `Documents` 日志镜像重复生成 `(1)(2)(3)` 编号副本的问题。
> 第 47 条修的是交付后用户报的「**中途再发一条，第二次的回答只显示头部、不再流式显示下文**」：
> 根因是模块侧 `mid` 这个共享可变字段被用户消息的回声帧改道，一条回答被拆到两个消息号上、
> 后续增量被静默丢掉（omni 通道还会把新回答挂到上一条回答的 mid 上）；现在开轮就把消息号
> 钉死、换消息号即换轮、App 侧收尾按 mid 认人且**绝不写用户自己那条**，并用 `chat.reply`
> 的全量文本给岛做单向兜底。
> 第 48 条按用户要求**改写岛卡按钮规则**：**回答中两个按钮**（`知道了 / 删除`，没有「回复」）、
> **回答结束才三个**（`回复 / 知道了 / 删除`），**App 内的回复入口取消、回复只留岛上的悬浮窗**；
> 同轮修掉三个删除相关的真机 bug：点了删除卡片还继续流式（收掉的卡被下一帧 delta 复活）、
> 离开豆包后在岛上点删除「卡一会」（删除原来没有 root 唤醒）、删完卡片又回来（成功回执
> 没落墓碑，加上空 cid 帧凭空建了一张 `reply:-` 卡）。
> 1.1 相对 1.0 的改动是**一次安全整改**：命令通道改为签名级权限保护、
> 局域网监听默认仅本机并强制令牌（该监听已随电脑端删除）、root 组件改为标准
> KernelSU/Magisk 模块（已随电脑端删除）、改用自建正式证书签发且关闭可调试。
> 逐条对应见 [`SECURITY.md`](SECURITY.md)，修复细节见
> [`CHANGELOG.md`](CHANGELOG.md) 的 v1.2 一节。

## 本版使用要点（真机实测）

| 你要找的东西 | 在哪 |
|---|---|
| ~~**电脑 IP / 端口 + 连接 / 断开**~~ | **已删除**（第 34 条）：App 首页标题下面直接就是「正文一行字数 / 胶囊分组长 / 在岛上预览回复」 |
| **卡片一直常驻** | 默认就是：卡片只在你点「我知道了」或手动划掉时消失 |
| 要不要**卡片上的回复输入条** | 首页勾选框「岛卡片显示回复输入条」：勾上 = MessageCard（有回复条，但按星河岛规则约 6s 会让位副岛）；不勾 = GenericCard（真常驻，岛上无回复条）。**第 48 条起这只是「卡片形态对照」开关** —— 「回复」按钮给不给由状态决定：回答中只给 `知道了 / 删除`，回答结束才多一个「回复」 |
| **点了「删除」之后** | 状态位立刻变「**正在删除…**」、按钮只有 `知道了 / 删除`（不会变成带「回复」那套）；后台/进程被杀时 App 会用 root 把豆包拉起来重投删除（实测 force-stop 场景 5.75s 拿到回执）；成功回执落**墓碑**，这条会话的残余推流一律不再上岛 |
| **像歌词一样滚动** | 胶囊**右侧**那行字（星河岛里唯一会滚动的区域）；卡片正文固定两行，只显示最新一段 |
| **卡片突然不动了** | 多半是豆包进程被系统冻结/回收 —— 15s 后胶囊会显示「**内容已暂停**」，把豆包拉回前台即恢复 |
| 回复的**回执时间** | 模块侧 `com.tg.dbisland.SEND recv … / done Nms err=ok`（实测 **20ms**）；8s 内没回执不会再关卡片、不会清空推流 |
| **豆包退到后台后的 30s** | 自动保活：有回复就一直续期，**回答结束后重新计 30s**，这 30s 内始终没新内容就收手交回系统（界面日志有记录） |
| **发送/回复之后马上切走** | **发送即激活**：你一点发送，保活窗立刻开（不等第一条推流），回答写完后再重新计 30s |
| **豆包在后台没反应** | 自动用 root 把豆包进程唤醒（`am start-service`）并重投这条消息，同一条只发一次；进程已被系统**回收**时，会用豆包自己的通道补发（**会短暂显示豆包**） |
| 现场日志 | **「日志」页第二个标签**（底部 dock 中间那格）：上面是模块运行状态，下面是实时日志流（含 `uid: app=… doubao=…`） |
| **岛显示怎么调** | **「设置」页第一个标签**：正文一行字数 / 胶囊分组长（−/＋ 立刻生效）、回复输入条开关、在岛上预览 |
| **root / LSPosed 正常吗** | **「设置」页顶部「运行环境」**：三行状态（Root 权限 / LSPosed 框架 / 模块状态）+ 豆包进程 + root 管理器 |
| **看某条会话的完整对话** | **「聊天」页**：点标题左边 ☰ 打开左侧会话框选一条，右边就是那条会话的全部答复 |
| **同时有好几条会话在答** | 一会话一张卡：**先收到内容的占主岛**，后来者进副岛（日志 `新会话进副岛 …（先到者 … 仍占主岛）`）。把主岛那张收掉后，下一条还在线的会话用 `Priority.HIGH` 重新上岛（日志 `先到者已被收掉 → 副岛升级为主岛 …`）；全收完则 `主岛空出`。**主岛归属只看"卡片还在不在岛上"，回答结束了但没被收掉的卡仍然占着主岛** |

> 排查“回复失败”类问题时先看两处：App 日志里的
> `已递交豆包进程发送，等待回执…`，以及模块日志里的 `SEND recv … main=…`。
> 若只有 `rejected: sender uid=`，那就是发送方身份归因问题（见 `CHANGELOG.md` 第 13 条）。

---

## 行为披露（本 App 到底做了什么）

> 这一节是**如实说明**，不是营销文案。安装前请读完。

| 能力 | 具体行为 | 触发条件 |
|---|---|---|
| **以你的名义发送消息** | 在豆包进程内反射调用 `OmniMessageService.sendMessageV2` 把文本发到你当前会话 | 只在你**主动点击岛卡片上的「回复」（回答结束后才有）并在本应用的悬浮窗里提交文字**时 —— App 内已没有回复入口（第 48 条） |
| **永久删除会话** | 调用豆包 IM 的 `del_user_conv` 删除当前会话（不可恢复） | 只在你**主动点击岛卡片上的「删除会话」**时；删除前无二次确认，删除后无法撤销 |
| **读取/转发回复内容** | hook 豆包的流式接口，把回复文本推送给星河岛（**电脑端已删除，不再外发**） | 豆包产生回复时自动进行 |
| **后台唤醒豆包** | 通过 `startService` 拉起豆包的候选组件（不弹界面），保证豆包进程存活、能继续抓流 | 每 20 秒一次，仅在「保活」开关范围内 |
| ~~**root 常驻服务**~~ | **已删除**（第 34 条）：那套 `/data/adb/modules/islandbridge/` 中继/监听脚本只服务电脑端，App 已不再安装、不再管理 | —— |
| ~~**监听网络端口**~~ | **已删除**（第 34 条）：`listen.sh` 是给电脑端灌帧用的，随电脑端一并移除；App 现在不监听任何端口 | —— |
| **退后台保活豆包（30s 窗）** | 豆包离开前台后：持一个部分唚醒锁，并每 3s 向豆包进程发一次 ping（无任何业务动作，不读不写消息） | 仅在豆包退到后台后的空闲窗内；有回复则续期，**静默 30s 后停止并交回系统** |
| **root 唤醒（保留）** | 回复发出后 2.6s 没回执时，用 `su` 拉起豆包进程并把这条 SEND 重投一次（同一 id，模块去重） | 仅在你主动发送/回复、且豆包在后台没接的时候 |
| ~~**联网**~~ | **已删除**（第 34 条）：App 不再发起任何网络请求，也不再申请 `INTERNET` / `ACCESS_NETWORK_STATE` | —— |

**不会做的事**：不上传任何数据到第三方服务器；**不联网**（无网络权限）；不读取
通讯录/相册/短信/位置；没有开屏广告与统计 SDK；没有远程配置或静默升级。

---

## 权限与隐私说明

### 申请的权限与用途

| 权限 | 用途 | 说明 |
|---|---|---|
| `com.tg.dbisland.permission.CONTROL`（自定义，**signature** 级） | 保护豆包进程内的命令通道 | 只有与本 App **同一证书签名**的组件才可能持有；第三方应用定义同名权限会被系统拒绝安装 |
| `com.astraflow.tool.island.permission.PUBLISH_ACTIVITY` | 向星河岛投送内容 | 由星河岛 SDK 的 AAR 自动合并，**普通权限、安装即授予、不弹窗** |
| `com.tg.dbisland.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`（AndroidX 自动生成） | 保护动态注册的非导出接收器 | 由 AndroidX 按当前包名声明 |
| `FOREGROUND_SERVICE`(+`_DATA_SYNC`) / `POST_NOTIFICATIONS` | 前台保活服务与常驻通知 | 用于维持与星河岛之间的连接、以及在豆包后台时保活 |
| `RECEIVE_BOOT_COMPLETED` / `WAKE_LOCK` | 开机自启与短时唤醒 | 可选；App 内可关 |
| ~~`INTERNET` / `ACCESS_NETWORK_STATE`~~ | **已删除**（第 34 条） | 电脑端分离后没有任何网络请求，App **不联网** |
| root（`su`） | **只用于唤醒豆包进程并重投一条 SEND**（`am start-service` + `am broadcast`），以及环境自检读 `/data/adb/lspd` | 发消息/回复时若豆包没接才会用到；App **不再向 `/data/adb` 写任何东西** |

> `usesCleartextTraffic` **已删除**（旧版是为了连局域网里的电脑端）。

### 数据流向

```
豆包 App ──(LSPosed hook，本机内存)──► 豆包岛桥 App ──(Binder)──► 星河岛（设备本机的系统级卡片宿主）
```

- 数据**只在本机内存与本机私有目录之间**流转：没有任何网络请求、没有中转服务器。
- 应用内「日志」页与聊天页的数据**固化在本 App 的私有目录**（第 41 条）：
  `files/logs/`（一行一条，约 6 MB 一片，**总占用上限 100 MB**，超出从最旧的片
  开始删）与 `files/chat/<cid>.jsonl`（一行一条 JSON，**全局上限 100 MB**，
  超出删最旧的会话整份）；日志可能包含回复正文与会话名 —— **卸载即消失**。
  （旧版还会写 `/data/adb/islandbridge/*.log`，该目录随电脑端一起不再被 App 使用。）
  **诚实边界**：LSPosed 模块住在豆包进程里，它自己的日志**只进 logcat**
  （`adb logcat -s IslandBridge`），本 App 无法代写。
- 回复正文属于聊天内容，因此上岛时**锁屏可见性设为 `TITLE_ONLY`**
  （锁屏只显示标题与头像，不显示正文），这是星河岛官方对个人信息的建议做法。
  **回复入口只有一个**：回答结束后岛卡上的「回复」按钮 → 本应用的**悬浮窗**输入框
  （`TYPE_APPLICATION_OVERLAY`，可聚焦、贴输入法上沿、锁屏不弹、30s 无操作自动关）；
  **App 内的聊天页是只读的**（第 48 条去掉了底部回复输入行），所以「以你的名义发送」
  这件事只可能由你点岛上那个按钮触发。

### 卸载后剩什么

| 位置 | 内容 | 卸载 App 后 |
|---|---|---|
| `/data/adb/modules/islandbridge/` | **历史遗留**的 root 中继模块（新版 App 已不再安装/管理它） | **不会自动删除**。如果你装过 v1.2 之前的版本，请用下面「root 组件残留清理」一节手动清掉 |
| `/data/adb/islandbridge/` | 同上（历史遗留的运行期数据：token、配置、日志） | 同上 |
| 应用私有目录 `files/`、`shared_prefs/` | **固化日志 `files/logs/`、聊天记录 `files/chat/`**、调参文件（`ib_lyric.txt` 等） | 随 App 卸载自动删除（卸载即消失，不会留在设备上） |
| 旧包 `com.islandbridge` | **改名前的旧版本 App**（另一个 applicationId） | **不会自动删除**，需要在系统设置里手动卸载 |

---

## root 组件残留清理（只针对历史版本）

**新版（`com.tg.dbisland`）没有 root 组件**：第 34 条把安装/卸载/监听那套整体删掉了，
App 不再向 `/data/adb` 写任何东西，也不再监听端口。

如果你装过 v1.2 之前的版本（`com.islandbridge`），设备上可能还留着：

```
/data/adb/modules/islandbridge/     module.prop / service.sh / uninstall.sh / relay.sh / listen.sh / launcher.sh
/data/adb/islandbridge/             token / ib_listen.conf / *.log
/data/adb/service.d/islandbridge.sh 1.0 遗留的裸启动脚本
```

清理方式（三选一，效果相同）：

1. **管理器里**：KernelSU / Magisk 的模块列表里移除 `IslandBridge`，触发模块自带的 `uninstall.sh`。
2. **手动**：
   ```sh
   su -c 'pkill -f /data/adb/islandbridge; rm -rf /data/adb/islandbridge \
          /data/adb/modules/islandbridge /data/adb/service.d/islandbridge.sh'
   ```
3. **卸载旧包**：把旧版 App `com.islandbridge` 卸掉（它已经没有用了）。

---

## 前提条件

本方案依赖 **LSPosed 注入豆包进程**抓取流式数据，不是普通 App 能实现的能力。
以下三层缺一不可：

| 层级 | 要求 | 说明 |
|---|---|---|
| 1. 系统 | **已 root + Zygisk + LSPosed** | 没有 LSPosed 则完全无法抓取 |
| 2. 宿主 | **星河岛 / AstraFlow**，设备 **Android 15+**，已启用星流（OPPO/一加/realme 还需启用星流官方插件「流体云事件接入」） | 承载卡片的宿主，SDK 走**通信版本 7**（星河岛 SDK 0.1.0） |
| 3. 目标 | **豆包 `com.larus.nova`** | 当前适配版本 **15.1.0** |

> **宿主的品牌适配现状（照抄星河岛官方口径，非本模块能力）**：
> **ColorOS 16 已支持 · ColorOS 17 可用 · ColorOS 15 基本可用 · 其他品牌逐步适配中**。
> 星河岛是**独立的显示界面，不属于任何手机品牌的系统功能**；在不被支持的机型上，
> 本模块抓取/发送功能仍可用，但**卡片不会显示**。

装好后还需在 LSPosed 管理器里**启用本模块并勾选作用域**：

```
com.larus.nova    抓豆包流式数据
android           保活（system_server 侧定时唤醒）
```

> ⚠️ 改完作用域必须**强制停止并重启豆包**；`android` 作用域变更需重启手机。

---


> **ColorOS 用户注意**：安装/更新 APK 后请**手动打开一次「豆包岛桥」**（或在 设置→应用→自启动 里允许）。否则系统的启动管理会拦住本应用进程，模块的事件全部投递失败（模块侧只会看到 `call fail`），岛上就什么都不显示。

## 安装

1. 安装 `doubaodao-v1.2-release.apk`（**release 签名**，非 debug 包）
2. 打开 App，顶部「运行环境」应显示全绿：
   ```
   运行环境                          正常
   ● Root 权限     已 root · uid=0 · KernelSU
   ● LSPosed 框架  LSPosed 已运行 · v2.2.0 · 已登记本模块
   ● 模块状态      模块已生效 · 最近心跳 0s 前
   ```
   （只有三行：第 34 条删掉了「Root 组件」那一行，因为那套组件已随电脑端删除。）
3. 在 LSPosed 里确认模块已启用、作用域已勾选（一般**安装时就会自动登记**）
   > ✅ **改名不用手动折腾（第 35 条 + 第 37 条实测更正）**：包名从 `com.islandbridge`
   > 改成 `com.tg.dbisland` 之后，LSPosed 会在安装时依据清单里的 `xposedscope`
   > **自动登记并启用**新模块。实测库里是：
   > `modules_state(com.tg.dbisland, 0, enabled=1, scope_request_blocked=0)`，
   > `scope` = `system` + `com.larus.nova`，旧包在三张活跃表里已无行。
   > 只有在**你的设备上没生效**时，才需要进 LSPosed 手动启用并勾选这两个作用域
   > （那时自检会显示 `未登记本模块`、`模块状态` 是红点）。
4. 打开豆包发一条消息，即可看到卡片上岛，App 的「聊天」页也会同时出现这条会话

> 「模块状态」需要**豆包活着且心跳到达**才会转绿（心跳 60s 一次）。
> 刚打开 App 若显示「尚未收到心跳」，稍等或点「重新检测」。
> 这是刻意的严格设计：静态文件检查只能证明「装过 LSPosed」，
> 心跳才能证明**模块真的注入进了豆包**。

---

## 环境自检说明

`/data/adb` 对普通 App 是**内核 SELinux 拒绝**（不是权限没申请），
因此静态检测统一走一次 `su -c`，一次取回：

```
id / su 路径 / /data/adb/{ksu,magisk,ap} /
/data/adb/lspd / zygisk_lsposed/module.prop /
pidof lspd / 模块登记（grep dbisland）/ pidof com.larus.nova
```

6 秒超时强杀，避免 root 授权框无人点击导致卡死。

**模块生效用的是心跳证据**：保活 ping 有两份（豆包进程内 + system_server），
system_server 那份在豆包死掉时也会发。所以 ping 带上来源标记，
**只有 `src=doubao` 才算数**——避免作用域没勾也显示「已生效」的假绿。

> 第 34 条起不再检测「Root 组件」、也不再读 `/data/adb/islandbridge/{ib_listen.conf,token}`
> ——那套 root 中继/局域网监听是电脑端专用，已整体删除。

---

## 电脑端工具（`pc/`）—— **已冻结留档**

电脑端已从本项目分离：安卓端**不再连接、不再显示、不再向电脑端发送任何东西**，
`pc/` 目录本身**保留但冻结**（不改代码），说明见 [`pc/FROZEN.md`](pc/FROZEN.md)。
`pc/simulate_doubao.py` / `pc/sse_server.py` / `pc/dsh-plugin/` 等仍可作为**历史实现参考**读，
但它们的投递通道（8799 局域网监听、root `relay.sh` 队列）**在安卓侧已经不存在了**，
按本文档旧描述去跑不会通。

**安卓侧诊断仍可用**：多会话 / 副岛 / 「主岛归先到者」的模拟脚本已移到
[`tools/sim_multi.py`](tools/sim_multi.py)（只走 adb + root，不依赖电脑端进程）：

```powershell
cd D:\aiwork\doubaoni
python tools/sim_multi.py run         # 造两个 cid → 看日志 → 收掉先到者 → 再看日志
python tools/sim_multi.py start       # 只造两个不同 cid 的会话
python tools/sim_multi.py ack sim-a   # 收掉某个会话（等价于点「我知道了」）
python tools/sim_multi.py show        # 只看相关日志
python tools/sim_multi.py clear       # 清空模拟文件
```

它的原理是往 App 私有目录的 `files/ib_sim.txt` 写一行一个 JSON 事件
（`chat.start` / `chat.delta` / `chat.end` / `sim.action`），App 每秒轮询一次，
走**和真实推流完全相同**的分发路径。旧版那种 `pc on`（模拟「电脑在线」）已删除。

---

## 构建

```bash
cd android
# 正式包（需要 android/keystore.properties，见下）
./gradlew :app:distRelease
# 产物：dist/doubaodao-v1.2-release.apk（附带 .sha256）

# 本地开发用 debug 包
./gradlew :app:assembleDebug
```

要求 JDK 17。`app/libs/` 下的两个本地库：

- `astraisland-sdk-0.1.0.aar` —— 星河岛 SDK 0.1.0（通信版本 7）。
  官方下载页 <https://astraflow.cc/island/download.html>；
  旧接入库 `astraisland-client`（协议 5/6）**已被上游停止提供**，新版星流不再接受其连接。
  该 SDK 依 **PolyForm Noncommercial License 1.0.0** 授权，仅限非商业用途，
  分发须保留 `Copyright 2026 MuYuanXing / AstraIsland`（见 [`NOTICE-ASTRAISLAND.md`](NOTICE-ASTRAISLAND.md)）。
- `api-82.jar` —— classic Xposed API stub

### 正式签名

```properties
# android/keystore.properties（**不要入库**，已在 .gitignore）
storeFile=keystore/islandbridge-release.jks
storePassword=……
keyAlias=islandbridge
keyPassword=……
```

密钥库请自行离线备份；丢失后无法再以同一身份更新已安装的 App。

---

## 目录结构

```
android/     手机端（Kotlin）：岛 SDK 投送 + Compose 界面 + LSPosed 模块（**无网络层**）
  app/src/main/java/com/tg/dbisland/
    MainActivity.kt          很薄的一层：edge-to-edge + setContent + debug 入口
    EnvCheck.kt              运行环境自检（root / LSPosed / 模块心跳）
    BridgeSecurity.kt        发送方 uid 白名单 + 签名权限常量 + 广播准入
    IslandBridge.kt          事件 → 岛内容项映射（SDK 0.1.0 类型化 API）
    BridgeApp.kt             应用入口，装配 island/bridge；事件同时喂 BridgeHub
    BridgeService.kt         前台保活服务（不连电脑端、不装 root 组件）
    EventProvider.kt         Binder 投递端点（绕开 ColorOS 冻结）
    Receivers.kt             保活 / 事件 / 开机自启接收器（全部带发送方校验）
    ui/                      第 38 条的 Compose 界面
      AppShell.kt            背景 + 底部 dock 三格 + 页面切换
      Theme.kt / Common.kt   配色、圆角、玻璃卡、状态行、无涟漪点击
      BridgeHub.kt           UI 唯一数据源（按 cid 归档会话/消息 + 日志 + 运行状态）
      ChatPage.kt            聊天页：左可折叠会话框 + 右具体聊天页（只读对话，无回复输入；第 48 条）
      LogsPage.kt            日志页：模块运行情况 + 级别筛选 + 实时日志流
      SettingsPage.kt        设置页：岛显示配置 + root/LSPosed 检测 + 调试
      glass/GlassSurface.kt  玻璃/磨砂面板的绘制（底色+渐变反光+AGSL 内阴影+亮边）
      glass/GlassShader.kt   圆角矩形 SDF 折射 shader（移植自 Kyant0，留作真折射入口）
    xposed/
      DoubaoHookEntry.kt     LSPosed 入口：hook 豆包 HTTP 流 + 命令广播（签名权限保护）
      MessageSender.kt       发送 / 删除会话（真实 IM 调用）
      MobileFeedParser.kt    流式数据解析
  app/src/main/assets/       xposed_init（root 资产与 chat.html 均已删除）
pc/          电脑端（Python，**已冻结留档**，安卓端不再引用）：见 pc/FROZEN.md
dev/         开发期的 adb/root 探针脚本、实测日志与界面截图（**历史留档**：脚本里引用的
             `/data/adb/islandbridge`、`ib_relay`、8799 监听、旧包名 `com.islandbridge`
             都对应已删除的电脑端/root 组件，现在跑不通了；`sim_multi.py` 已移到 tools/）
tools/       逆向与调试脚本 + 安卓侧多会话模拟 tools/sim_multi.py
pylibs/      Python 依赖（websocket-client）
```

---

## 已知限制

- **豆包没有「停止」功能**：其 UI 无停止按钮，`OmniBreakReason_CLICK_BREAK_BUTTON`
  从 UI 侧不可达。因此本模块**不提供停止动作**，相关代码已移除。
- **豆包升级可能失效**：深度依赖豆包内部混淆类名（`OmniMessageService`、
  `NativeMessageServiceImpl` 等），版本升级可能改名导致 hook 失效。
- ~~**电脑端（`pc/`）8787 端口本身没有鉴权**~~ **已移出本项目**：电脑端冻结在 `pc/`
  留档（[`pc/FROZEN.md`](pc/FROZEN.md)），安卓端不再连它，App 也不再申请网络权限。
  那段风险只存在于「单独把 `pc/` 里的 daemon 跑起来」这种情况（见
  [`SECURITY.md`](SECURITY.md) 的 R1）。
- **「删除会话」不可恢复，且没有二次确认**（岛侧交互限制）：
  误触后只能在豆包侧重新建会话。
- **校验的兜底**：manifest 里必须导出的接收器（要接受豆包进程 / system_server 的广播）
  采用**发送方 uid 白名单 + 正向识别**策略。若系统未能给出
  发送方 uid（部分厂商 ROM 上 `getSendingUid()` 返回 -1），出于「宁可不拦
  也不能把正常功能拦死」的取舍会**放行并记录警告**；日志里会写明。
- **回复卡可能让位副岛**：星河岛 0.1.0 里内容种类由卡片类决定，「消息卡片」
  按新消息排位、停留后让位副岛。旧版靠 `kind=LIVE_UPDATE` 绕过这条规则的做法
  已不可用；需要「永不退副岛」时把 `IslandBridge.USE_MESSAGE_CARD` 改成 `false`
  （改用通用卡片，属「实时活动」，代价是失去岛上的回复输入条）。- **App 在前台时岛不显示本 App 的内容**（星河岛规则，非本模块可控）。
- ~~**电脑端连接配置暂时隐藏**~~ **配置块已删除**（第 34 条）：App 里不再有
  「电脑IP / 端口 + 连接 / 断开」，也没有 `SHOW_PC_CONFIG` 开关。
- **旧包需要自己卸载**（第 35 条）：`com.islandbridge` 是另一个 applicationId，
  不会自动消失，也不会被新包覆盖。`pm path` 会同时列出两个包。
- ~~LSPosed 需要为 `com.tg.dbisland` 重新启用并勾选作用域~~
  **实测不需要**（第 37 条）：`xposedscope` 让 LSPosed 在安装时自动登记并启用。
- **界面（第 38 条）的已知取舍**：面板的「磨砂」是观感（高不透明度底 + 渐变反光 +
  内阴影 + 亮边），**不做真实背景折射**（要自建 backdrop 捕获管线，未做，见
  `SECURITY.md` R7）；~~聊天记录只在内存（进程被杀即清空，R9）~~ **第 41 条已修**：
  聊天记录与日志都固化在 App 私有目录（各 100 MB 上限，超出删最旧的），
  进程被杀后重启会从磁盘恢复（`files/chat/<cid>.jsonl` + `files/logs/`）；
  构建时有若干条 `R8: … kotlin metadata` 告警（AGP 8.9.3 的 R8 比 Kotlin 2.2.10 旧，R8）。
- **~~岛上的回复框是「展开才出现」的（第 40 条）~~ 已被第 45、48 条取代**：
  第 45 条起「回复」不再用宿主输入框，而是弹**本应用自己的悬浮窗**；第 48 条起
  卡片形态固定为通用卡、按钮组按状态给（回答中 `知道了 / 删除`，回答结束才多一个
  「回复」），**App 内也去掉了回复输入**。设置页的「回复输入条（MessageCard）」开关
  现在只是**形态对照**用（打开 = 永远消息卡，代价是按星河岛规则约 6s 可能退副岛）。
- 本项目仅供学习研究，请遵守相关软件的服务条款。

---

## 修复记录

详见 [`STOP_DELETE_FIX.md`](STOP_DELETE_FIX.md) 与 [`SECURITY.md`](SECURITY.md)。
其中记录了三个真机实测确认的 bug：

1. **删除后岛里残留内容** —— `island.end()` 早于 session 绑定发出被静默丢弃，
   旧代码却照清账面，导致岛仍渲染旧卡片直到到期。已用 `pendingEnds` 队列 + 补发修复。
2. **发送自我投毒** —— 捕获 hook 连自己的出站调用一起抓，
   首次被服务端拒的废包成为模板，污染后续所有发送。已用 `asSelfSend` 隔离。
3. **心跳来源混淆** —— system_server 的 ping 被误当模块生效证据，
   会造成「假绿」。已按来源标记区分。
