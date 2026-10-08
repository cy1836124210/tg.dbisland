# 豆包岛桥 · 更新日志

当前版本 **v1.2**（versionCode 3）。本文件倒序：v1.2 在最前，v1.1 原文原样保留在下方。

---

# 豆包岛桥 v1.2 —— 真机联调修复版

> 交付日期：2026-10-04 ｜ 上一版：v1.1（预审 5 条整改 + 星河岛 SDK 0.1.0 迁移）
> 内容主体是把 v1.1 装到真机上跑通的过程中挖出来的「装了但用不了 / 用起来不对」
> 的问题，逐条都有真机证据。**唯一一条按用户要求在联调过程中追加的功能改动是
> 第 32 条**（回复卡片从「单份会话」改为「按会话一份」+ 副岛 + 主岛归先到者）。
> **第 33 条**是交付后用户报的「会话的标题没有被显示」的修复（手机侧从来没学会过
> 会话名），产物校验值已随之更新为下表这一版。

## 一、产物与校验值

| 项 | 值 |
|---|---|
| APK | `android/dist/doubaodao-v1.2-release.apk` |
| 大小 | **1513629 字节** |
| SHA-256 | `fc7a36ebe8cafc50ab255f315814c440e176c4c9e3274e874568b652ef1b1d18` |
| 签名者 | `CN=IslandBridge, OU=Doubaodao, O=IslandBridge, C=CN`（自建证书，**非 Android Debug**） |
| 证书 SHA-256 | `738c3ae28cc70ad5349c589b2c08d650b42cf22a2d8aa5f5b178ea11e90c5614`（与 v1.1 同一把） |
| versionCode / Name | `3` / `1.2` |
| module.prop | `version=1.2.0` / `versionCode=3` |
| 包名 | `com.tg.dbisland`（`namespace` 与 `applicationId` 一致，见第 35 条；旧包名 `com.islandbridge` 只保留在历史条目里） |
| 权限 | `com.tg.dbisland.permission.CONTROL`、SDK 合并的 `com.astraflow.tool.island.permission.PUBLISH_ACTIVITY`、AndroidX 自动合并的 `com.tg.dbisland.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`（均为 signature 级） |
| 网络权限 | **无**（`INTERNET` / `ACCESS_NETWORK_STATE` / `usesCleartextTraffic` 已随电脑端删除，见第 34 条） |
| root 资产 | **无**（原 `assets/root/` 6 个文件已整目录删除，见第 34 条；`assets/` 现只剩 `xposed_init` 与 `chat/chat.html`） |
| SDK | compileSdk 36 · minSdk 26 · targetSdk 36 · AGP 8.9.3 · Kotlin 2.1.20 |
| debug 标志 | 无（`aapt2 dump badging` 无 `application-debuggable`） |
| 构建 | `cd android; .\gradlew.bat distRelease`（JDK 17 / ANDROID_HOME=android-sdk） |

私钥在 `android/keystore/islandbridge-release.jks`（已 gitignore），**请离线备份**：丢了就无法覆盖升级。
真机：PJZ110 / Android 16（SDK 36）/ KernelSU / LSPosed，豆包 `com.larus.nova` 15.1.0。

## 二、真机联调修复（现象 → 根因 → 修法 → 证据）

### 1. root 组件起不来：launcher 把自己误判成「worker 已在跑」
- **现象**：App 调起 root 组件后 `/data/adb/islandbridge` 里没有 token、8799 不监听，两个 worker 从未被拉起。
- **根因**：旧版用一条 `su -c "...cp relay.sh...; cp listen.sh...; sh launcher.sh"` 调起，这条 wrapper **自己的 cmdline 里字面含脚本路径**，而旧代码用**子串匹配** `/proc/*/cmdline` 判断 worker 是否在跑 → 把调用方自己当成了 worker。
- **修法**：改为 **argv 最后一个参数精确匹配** + 优先读 worker 自己写的 pidfile；App 侧也拆成两次 `su`。
- **证据**：修后 `root runtime ensured (stage rc=0, launch rc=0)`，耗时 316~478ms（旧版 `rc=137`）。

### 2. 逐个扫 /proc 太慢，遇到 D 状态进程会阻塞（rc=137）
- **现象**：真机 600+ 进程时一次要几十秒；一个 launcher 卡了 **4 分钟以上**还占着 `.lock`。
- **根因**：每个进程 fork 2~3 次读 cmdline；D 状态 / ColorOS 冻结进程会阻塞。
- **修法**：`grep -lF` **单进程批量**扫全部 `/proc/*/cmdline`。
- **证据**：全扫 **41ms**（逐进程扫 60 个进程要 406ms）。

### 3. mount namespace：App 的 su 看不见豆包数据（最隐蔽）
- **现象**：① `wait_ready()` 条件永不成立，空转 180×2s；② relay 读不到豆包写的队列文件，整条 relay 白跑。
- **根因**：App 的 `su -c` 继承 **App 自己的 mount namespace**（`mnt:[4026536004]` vs init `mnt:[4026533142]`），那里 `/data/data/com.larus.nova` **不可见**。
- **修法**：launcher / relay / uninstall 开头检测 ns 不同就 `nsenter -t 1 -m` 重入 init ns（`IB_NS_ENTERED` 防重入）；`wait_ready` 加 30s 封顶。
- **证据**：两个 worker 现在都在 `mnt:[4026533142]`，重启后 relay 能正常读到豆包队列。

### 4. 孤儿 nc 占着 8799，新 listen 永远 bind 失败
- **现象**：卸载/重启后新 listen 每秒 bind 失败刷屏。
- **根因**：只杀 `listen.sh` 会留下它的 `nc -lk -s 127.0.0.1 -p 8799` 子进程占着端口。
- **修法**：listen 记 `ib_listen.nc.pid`；launcher 起 listen 前按 argv 末参清孤儿 nc；卸载连 nc 一起收；重试退避 + 日志限流。
- **证据**：故意造孤儿 nc → 316ms 收掉，bind 失败 **0** 次。

### 5. 岛卡片一句都不渲染
- **现象**：卡片完全不出现，日志只有一行异常。
- **根因**：`IslandImage.picture(...)` + 呼吸动画被 SDK 拒：`IllegalArgumentException: 照片类图片不支持呼吸变化`。
- **修法**：胶囊改 `IslandImage.glyph(...)`（单色）→ 失败退 `BuiltinSymbol.MESSAGE`；`build()` 失败再用内置符号重试一次。
- **证据**：修后持续 `岛card ...`、无异常。

### 6. 保活服务告警刷屏
- **现象**：每帧一条 `W`：`ForegroundServiceStartNotAllowedException`。
- **根因**：Android 12+ 后台起前台服务被系统拒绝 —— **系统行为，功能不受影响**（事件与岛回调都能唤醒进程）。
- **修法**：失败后 60s 内不重试、只记一条 info；前台入口强制真起。
- **证据**：一轮 6 帧只出现 1 条 info。

### 7. 管理器移除模块后，App 又把 root 组件偷偷装回来
- **现象**：root 管理器里「移除」模块后重启，`/data/adb/modules/islandbridge` 又出现 —— 预审第 3 条说的「卸载后自启残留」会复现。
- **根因**：「用户已卸载」只记在 App 私有 SharedPreferences 里，而管理器移除**只跑 uninstall.sh**，App 不知情，随后按「首次安装」又装回去。
- **修法**：`uninstall.sh` 落一个标记 `/data/adb/islandbridge.disabled`（**刻意放在 `$DIR` 之外**，能活过脚本里的 `rm -rf $DIR`）；App 安装前统一查（私有 pref **或** `su` 查标记），命中就跳过；只有用户在前台点「安装/重装 root 组件」才带 force **清标记并安装**。
- **证据**：卸载 → 标记创建（0 字节）→ 唤醒 App 日志 `root component removed by user — skip reinstall`、两个目录都不存在；随后点「安装/重装 root 组件」→ 标记被清除 + `rc=0,0` + worker 全在 init ns。

### 8. 回复上行「失败」+ 点完后岛不再刷新（连锁 bug）
- **现象**：在岛上回复后卡住 → 日志 `已递交豆包进程发送，等待回执…` → 1.5s 后 `豆包进程无回执，回退剪贴板`；而且**此后这条会话的推流全部不再上岛**。
- **根因**：① `SEND_TIMEOUT_MS=1.5s` 太短（模块侧走 Omni SDK `sendMessageV2` + 回调，首次还要学模板）；② 超时后无条件走 `fallbackSend()`，而它调用的 `dismissReply()` 会 **end 掉卡片并把 `replySuppressed` 置真** → 后续 `chat.delta` 全被丢。
- **修法**：超时放宽到 **8s**；「只是没等到回执」**不做任何破坏性动作**（不回退剪贴板、不关卡片、不拉豆包），只有模块**明确报错**才回退；模块侧补 `recv/done Xms` 日志便于定位。
- **证据**：日志出现 `回复回执超时(8000ms)：未确认，卡片保留`，且后续推流继续上岛。

### 9. 卡片不常驻（用户明确要求「一直常驻，除非我自己点我知道了」）
- **根因**：① `DismissPolicy.afterMillis(60s)` —— 最后一次更新起 1 分钟自消；② 星河岛规则：**消息卡片按新消息排位，默认 6s 后让位副岛**（api-doc:248），MessageCard 结构上做不到常驻。
- **修法**：改 `DismissPolicy.untilEnded()`（直到我们 `end()`，上限 8 小时）；「我知道了」按钮**始终**存在（流式中也有），按钮组合按 [我知道了+删除会话] → [我知道了] → [] 逐级降级，避免按钮数超限把整张卡打回；卡片类型可在 App 首页切换：默认 **GenericCard（实时活动，不让位，真常驻）**，勾选后切回 MessageCard（岛上有回复条，可能退副岛）。
- **证据**：待人工确认（见第四节）。

### 10. 岛上只显示开头、不会滚动
- **根因**：两种卡片正文都**只渲染两行、不支持滚动**（api-doc:163/249）；旧代码结束后还给「完整正文从头读」，于是卡片永远停在回复开头。全文里**只有胶囊右侧文字会滚动**（api-doc:126）。
- **修法**：正文与推流期一致，统一取**尾部**（最新 48 字 ≈ 两行）；把更长的最新文字（160 字）放进**胶囊右侧**，利用它做「歌词式」实时滚动；状态（手机/电脑·正在回复）挪到胶囊左侧。
- **证据**：日志 `岛card ... body=N字 roll=M字` 随推流变化；目视滚动待人工确认。

### 11. 推流中断时卡片静静停住（豆包进程被冻结/回收）
- **背景**：捕获豆包推流的 LSPosed 钩子**住在豆包进程里**（`xposed/DoubaoHookEntry.kt`）。豆包进程被 ColorOS 冻结/回收、或它自己在后台断掉 SSE 后，就再也收不到事件，只能把豆包拉回前台才继续 —— 用户真机现象「手动回复之后马上退出会收不到内容，需要手动打开豆包」。这是**宿主进程的边界**，不是本 App 能代收的。
- **修法**：加看门狗 —— 推流中 15s 没有 `chat.delta` 就把胶囊状态改成「**内容已暂停**」并记一行日志，把原因说清楚，而不是让卡片静静停住。
- **证据**：待人工确认。

### 12. App 的「电脑IP / 端口」配置行不见了
- **现象**：用户报配置页面消失。
- **根因**：早前为了先跑通手机侧，代码里 `SHOW_PC_CONFIG = false` 把整行**隐藏**了；同时上半部分固定高度内容会占满整屏（真机 UI 层级 dump：WebView 被挤成 0 高度、测试按钮掉到 1891px 屏幕外），恢复显示后也够不到。
- **修法**：`SHOW_PC_CONFIG = true`；把标题/自检卡片/连接配置/岛状态/测试按钮整体放进 **ScrollView** 可滚动区，回复页与回复输入框留在下面固定区。
- **补记**：只放进可滚动区还不够 —— 真机 dump 显示配置行落在 y≈1980，而视口只到
  y≈1944，用户还是看不见。所以**把配置行直接挪到标题正下方**（第一屏 = 标题 +
  `电脑IP / 端口` + `连接 / 断开`）。
- **证据**：dump 实测配置行位于 y=342~715，无需滚动即可见；`192.168.1.2` / `8787`
  与「连接 / 断开」都在第一屏。

### 13. 回复上行的**真正**拦路虎：广播发送方 uid 被误判（用户报「模块内的发送依旧是失败的」）
- **现象**：App 侧把命令发出去、等 8s 也没回执；模块日志里每次都是
  `com.islandbridge.SEND rejected: sender uid=10375`。
- **根因**（真机 uid：App=`10430`、豆包=`10375`）：App `targetSdk 36`，Android 14+
  **默认不把广播发送方身份**带给接收器，`getSentFromUid()` 返回 `INVALID_UID`；
  模块于是退到 `Binder.getCallingUid()`，而 `onReceive` 是在**主线程 Handler** 里
  回调的、**不在 Binder 事务中** —— 这个 API 此时返回的是**接收方自己**的 uid
  （10375 = 豆包自己）。「只认本模块 App(10430)」的校验必然拒绝，
  **合法命令全部被自己的第二道闸挡住**。因为拒绝发生在 uid 闸之后、
  连 `recv` 日志都打不出来，所以只看模块日志会误以为「广播根本没到」。
- **修法**（两侧都改，缺一不可）：
  1. App 侧发 SEND/DELETE 时显式开身份共享：
     `sendBroadcast(i, null, BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle())`；
  2. 模块侧不再使用 `Binder.getCallingUid()` 兜底，并把「拿到的 uid 等于接收方自己」
     一律视为**无法归因**（返回 -1），交给各通道既定策略处理
     （命令通道有签名级权限兜底；事件通道放行并只告警一次）。
- **证据**：
  ```
  App : 回复[手机-豆包]: bridge_reply_test
  模块: com.islandbridge.SEND recv in=com.larus.nova text=17字 cid=716712230022402 main=true
  模块: send ok ret=null → com.islandbridge.SEND done 20ms err=ok
  App : ev ok send.result → 已发送到手机豆包
  钩子: ev[sse] chat.start mid=57528760378299906（豆包开始回答我们发进去的这句）
  ```
  并且豆包会话里**真的出现了**这条 `bridge_reply_test`，豆包回复
  「bridge_reply_test ✅ 收到测试信号，链路正常。」—— 端到端闭环。

### 14. 豆包退到后台就收不到内容 → 「30s 保活窗」（本轮新功能）
- **现象**（用户原话）：「豆包一离开前台我们就收不到内容了」——钩子住在豆包
  进程里，ColorOS 的冻结器会在它退后台后很快把它冻住，SSE 钩子随之停摆，
  正在写的那半截回答就永远收不到。
- **要求的规则**：退后台后保活 30s；**有回复就持续保**（每条推流续期）；
  **响应结束后重新计 30s**；这 30s 内始终没有新内容 → 放弃保活，交回系统。
- **实现（两侧镜像同一套计时）**：
  - **模块侧**（豆包进程内）：挂 `ActivityThread.handleResumeActivity` /
    `handleStopActivity` / `handleDestroyActivity` 追踪前后台（800ms 去抖，
    避免 Activity 之间切换被误判成退后台）；退后台即开窗 —— 拿
    `PARTIAL_WAKE_LOCK` + 每 3s 一次「豆包 → App provider」的 binder 往返
    ping；每条 `chat.*` / `plan.*` 推流续期；`chat.end` 之后同样从这里续期，
    于是「响应结束后重新计 30s」自然成立；静默 30s → 释放唤醒锁、停 ping、
    上报 `{"t":"keepalive","state":"stopped"}`。
  - **App 侧**：镜像同一个 30s 空闲窗，并在窗内每 3s 广播
    `com.islandbridge.PING`（同样走 CONTROL 签名权限那道闸）反向 ping 豆包
    进程。为什么 App 也必须独立计时：**豆包被冻住时模块的定时器也停了**，
    「该收手了」只能由不会被冻的一方说了算。
- **证据**（2026-10-04 真机日志时间线）：
  ```
  23:56:48 模块  豆包离开前台 → 开始保活（静默 30s 后放弃）
  23:56:54 模块  豆包回到前台 → 停止保活            （即时收手）
  23:57:00 模块  豆包离开前台 → 开始保活（静默 30s 后放弃）
  23:57:03 模块  ping from app (保活中=true 前台=false)
  23:57:00–23:58:04  保活续期 chat.delta ×207、app-ping ×8
  23:58:04 模块  保活结束（静默 30s 没有新内容）→ 豆包交回系统处理
  23:58:07 系统  Executing freeze operation for com.larus.nova, 10375
  ```
  App 侧同时在界面日志里给出：「豆包退到后台 → 保活 30s（有回复会持续续期）」
  「豆包保活：30s 没有新内容 → 放弃保活，交回系统处理（ping 8 次）」。
- **「发送」也激活（用户追加要求）**：光靠"退后台"开窗不够 —— 用户点完
  回复就切走时，豆包可能**先被冻住**，那第一条 `chat.start` 根本到不了，
  于是又变成"发完就走收不到内容"。所以 **App 交出 `SEND` 时立刻开窗**
  （模块侧收到 SEND 即 `armKeepAlive("app-send")`，App 侧在 `sendViaMobile`
  里也本地开窗并开始每 3s ping），不等第一条推流。
  真机证据（在豆包已被系统冻结的状态下投递 SEND）：
  ```
  00:03:58.357  模块  com.islandbridge.SEND recv text=15字 cid=716712230022402 main=true
  00:03:58.358  模块  App 发送消息 → 激活保活窗（前台=false）
  00:03:58.361  模块  保活续期（app-send）
  00:03:58.395  模块  send ok ret=null；SEND done 38ms err=ok
  00:04:00.229  模块  保活续期（chat.start）→ 保活续期（chat.delta）×N
  00:04:12.785  模块  第二次发送：保活续期（app-send）→ send ok 49ms
  ```
  验证手段：root / `adb shell` 直接投 `com.islandbridge.SEND`（接收器带
  CONTROL 签名权限 + uid 白名单，root/shell 属既定可信来源），
  与 App 里点「回复/发送」等价的同一入口。
- **诚实边界**：这是**尽力而为**。保活窗靠「频繁 binder 事务 + 唤醒锁」让豆包
  不被冻，覆盖得住正常长度的回答；但若 ColorOS 在窗内就强冻（极端省电场景），
  钩子仍会停 —— 那时模块连「收手」都发不出来，只有 App 侧的计时会照常到期。

### 15. 【自查自纠】一次把整个模块干掉的类初始化崩溃
- **现象**：新代码装上去后，模块在**所有进程**都注入失败，logcat 里连一条模块
  日志都没有，看起来像「模块没装 / 没生效」。
- **根因**：companion object 里写了
  `private val mainHandler = Handler(Looper.getMainLooper())`。LSPosed 是在
  `Zygote.specializeAppProcess()` 阶段加载模块类的，那一刻新进程的主线程还
  没 `Looper.prepareMainLooper()` → `getMainLooper()` 返回 null → `Handler`
  构造抛 NPE → `<clinit>` 失败 → **模块类加载不上，所有钩子一起消失**：
  ```
  E/LSPosedFramework  Failed to load class com.islandbridge.xposed.DoubaoHookEntry
  Caused by: java.lang.NullPointerException: Attempt to read from field
      'android.os.MessageQueue android.os.Looper.mQueue' on a null object reference
      at com.islandbridge.xposed.DoubaoHookEntry.<clinit>
  ```
- **修法**：companion 里**不再有任何触碰 Android 运行时的初始化**；主线程
  Handler 改为惰性获取（`mainHandler()` + `onMain()` / `cancelMain()`，
  拿不到主线程时退回当前线程尽力执行）。
- **教训（已写进代码注释）**：模块类的 `<clinit>` 抛异常是**静默**的 —— 没有
  模块日志，只有 LSPosed 的 verbose 日志留痕。排查「模块没反应」必须先看
  `/data/adb/lspd/log/verbose_*.log` 里的 `Failed to load class`。

### 16. 豆包在后台时「提问发不出去、要手动打开豆包才回复」
- **现象**（用户原话）：「豆包存在后台退出前台，去模块进行提问，豆包没有被激活
  回复我，我手动打开豆包前台他就回复我了」。
- **根因（真机复现）**：发送是投给**豆包进程里**的模块接收器的。豆包被 ColorOS
  冻结时这条广播会被**推迟投递**（进程若已被回收则根本没人收）——于是上一版
  「发送即激活保活」的代码自己先被冻住，形成死锁：不激活就发不出去，发不出去
  就永远不激活。**能破解的只有不会被冻的一方**（App，借 root），因为 root
  不受「后台启动限制 / Do not want to launch」约束（模块的 relay.sh 用的是
  同一依据）。
- **验证「确实会被冻住」**：手动把豆包冻上（`echo 1 > /sys/fs/cgroup/apps/
  uid_10375/cgroup.freeze`）后投一条普通 SEND —— 模块日志**一行都没有**
  （`recv in` 行数 30 → 30）。这就是用户看到的"没反应"。
- **修法（三层）**：
  1. **root 唤醒 + 重投**：发送后 2.6s 拿不到 `send.result`，App 就写一份请求
     文件，以 root 执行「没有进程就 `am start-service` 拉起（实测有效：进程
     21478 起、模块随之注入）→ 由 root 直接投这条 SEND」。实测冻住的豆包被
     唤醒后立刻处理：`SEND recv text=17字 cid=716712230022402` →
     `send ok ret=null`（模板层）→ `SEND done 21ms err=ok`。
  2. **按 id 去重**：App 首发 / App 重发 / root 重投共用同一个随机 id，模块
     只认第一次（`重复 SEND（id=…）已忽略`）。否则被推迟的那条随后又被投递，
     用户会看到同一条消息发两遍、豆包答两遍。root 脚本连投 3 次（每次隔 1.5s）
     也是靠它保证"只发一遍"——**为什么要连投 3 次**：豆包冷启动时 LSPosed
     注入 + 模块注册接收器要 1~2s，之前投的广播没有接收器，会直接丢掉
     （实测日志里只有 `cmd receiver registered`、没有 `SEND recv`）。
  3. **进程被回收时说实话 + 兜底**：豆包进程被杀后没有"会话模板"，模块手搓的
     请求会被服务器以 `iim fail IMError(tips=message list empty,
     error_stage=send_validate)` 拒掉——**而旧代码调完就 `return null`
     （=成功），异步失败从没传回来**，于是 App 显示"已发送"、用户永远等不到
     回答。现在这一层会等 1.5s 异步回执并如实上报：
     `SEND done 485ms err=null; 豆包冷启动无会话模板，构造发送被服务器拒绝`。
     App 收到这种硬失败后，**只在息屏时**改用豆包自己的发送通道
     （导出活动 `OuterShareDeliverActivity`）补投——已实测这条通道在进程被杀
     后仍能发出并拿到流式回答；息屏下拉起活动不会亮屏、不打扰用户，亮屏时
     宁可不做，只如实记一行。
- **诚实边界**：亮屏且豆包已被系统**回收**（不是冻结）时，第一条回复仍可能发不
  出去，需要你打开一次豆包；打开后这条真发送会让模块抓到模板，之后同一进程里
  的岛上回复恢复走模板层（正常）。
### 17. 岛里回复后「豆包什么都没回」——发送层的假成功
- **现象**（用户原话）：「我操作了，豆包没有回复任何内容」。
- **日志证据**（用户那次，cid=716712230022402）：
  ```
  00:25:31.658  SEND recv text=2字 cid=716712230022402 main=true
  00:25:31.668  send ok ret=null
  00:25:31.668  SEND done 10ms err=ok                 ← 模块说"发好了"
  00:25:31.669  cb onFailure OmniIMError(code=-1, tips=message list empty)  ← 服务器 +1ms 就拒了
  ```
  对比一次真的成功的发送（同一天 00:03:58）：
  ```
  00:03:58.395  send ok ret=null → SEND done 38ms err=ok
  00:03:58.718  cb onSuccess [OmniMessage(messageId=5752…)]   ← 服务器收了
  00:04:00.228  chat.start mid=57529271080686082             ← 新回答
  ```
  `cb onFailure` 只用了 **1ms** 回来 = **本地校验**直接拒（根本没上网）。
- **三个根因**：
  1. **模板层不等回执**：`cloneBeanSend`/`nativeSend` 调完 API 就 `return null`
     （=成功），异步 `onFailure` 从没传回来 → App 以为发好了，不会补救。
  2. **模板中毒**：捕获钩子把 `regenMessage`/`addMessageV2` 也当模板源，而这些
     调用的请求体**常常没有消息列表**，会把好的发送模板覆盖成"空消息列表"→
     此后每次重放都被本地 1ms 拒（正是上面那次）。
  3. **空列表照发**：模板的 `requestMessages` 是空列表时，`?:` 兜底不会触发
     （空列表 ≠ null），于是把"空消息列表"原样发出去。
- **三个根因**：
  - 模板层（bean 克隆 / 原生重放）**都等真实回执**再报结果：成功要 `cb
    onSuccess`（实测 `SEND done 432ms err=ok`），失败如实返回
    `模板重放被服务器拒绝（请求里没有有效消息体）`。
    **2s 没回执按「已发出」处理**——网络慢的正常发送不能被当成失败去补发，
    否则会重复发一条。
  - 捕获模板时**只认 `sendMessageV2`**；`regen/add` 仅在还没有模板时兜一次，
    不再覆盖好模板（治"中毒"）。
  - 克隆出的消息列表为空 → 换成自己构造的一条，不再发空列表。
  - 失败时把该条消息的 id 从**去重表撤掉**，否则 App 的重投会被当成"重复"丢掉。
- **失败的兜底（新行为）**：模块明确报拒（`被服务器拒绝` / `无模板`）时，App
  改用**豆包自己的发送通道**补投。实测（00:34:29）：
  ```
  am start -a SEND ... OuterShareDeliverActivity
  chat.start mid=57533029750712066 → chat.delta " escalation" "_pro" …  ← 送达并开始回答
  ```
  代价：豆包界面会短暂切到前台。附带好处：这一投是真发送，模块顺手抓到**好
  模板**，之后同一进程里的岛上回复恢复走模板层（实测 00:31:55 `cb onSuccess`
  + 完整回答流）。
- **诚实边界**：2s 内没回执时我们按"已发出"处理（宁可偶尔漏掉一次补救，也不
  重复发消息给豆包）。
### 18. 胶囊里的「…」——改成 15 字一组像歌词一样唱
- **现象**（用户原话）：「回复我的内容在歌词里的表现有很多的...」。
- **根因是我们自己加的两个省略号**，不是岛的距离不够：
  - `tailOf()` 把「整段尾巴」交给胶囊时在前面补 `"…"`；
  - 再超长时 `cut()` 又补一个 `"…"`。
  也就是说旧做法是**把整段塞进去再截断**，必然到处是省略号。
- **修法**（用户给的方案）：新增歌词播放器 `LYRIC_GROUP = 15` / `LYRIC_TICK_MS = 500`
  —— 回复正文按 15 字分组，**组内随推流逐字流式长出来，组满且后面还有内容就换下一组**，
  落后超过 6 组时一次跨两组追上；推流结束后把剩余分组唱完自动停。
  胶囊右侧只放**当前这一组**（≤15 字）→ 永远不需要截断 → 永远没有「…」。
  同时不再是"SSE 来一条就直接塞进胶囊"（避免疯狂刷新），节拍由播放器控制。
- **实测**：`岛card … roll=15字` / `roll=14字` 交替前进，无省略号
  （旧版 `roll=160字` 且带 `"…"`）。卡片正文（两行）仍取尾部，那两行本来就不滚动。

### 19. 在豆包里发完消息**立刻退出** → 回答一个字都收不到
- **现象**（用户原话）：「如果我在豆包app里发消息给豆包，豆包还没回复我我瞬间退出，
  我们的模块也会收不到内容」。
- **原因**：钩子住在豆包进程里，而交互式回答走的是豆包自己的 SSE 连接。
  用户一发完就退出，豆包在后台会把这条连接断掉 → 钩子拿不到任何 `chat.*`
  事件 → 推不出内容。我们造不出回答，但**可以把它叫回来继续推** ——
  这正是用户手动做的那个动作（「我手动打开豆包前台他就回复我了」）。
- **三个根因**：
  1. 模块的发送钩子发现「这是**豆包自己**发的消息」（不是我们代发的）时，
     立刻**开保活窗**（`保活续期（豆包内发送）`），用户马上退出也不会被冻；
  2. 同时起一个看门狗：**8s 内一条 `chat.*` 都没有**（说明连接断了）就通过
     provider 通知 App（`t=reply.stalled`）；任何一条推流到达即取消；
  3. App 收到后 `wakeDoubaoOnly()`：**只唤醒、绝不重发**（消息已经发出去了）——
     亮屏时用 monkey 起豆包启动页（等于帮你点一下图标），息屏时只
     `start-service`（不亮屏、不抢前台）。
- **诚实边界**：这条路径依赖真机上"发完立刻退出"的手指点按，adb 打不进豆包的
  输入框，所以**只验证到"唤醒脚本本身可执行 + 豆包回到前台继续推流"**；
  端到端（发完秒退 → 自动叫回 → 回答补上）需要用户手测一次。

### 20. ColorOS 拦自启动：装完 App 后岛上什么都不显示
- **现象**：`adb install -r` 之后岛上完全没有卡片，App 侧一条日志都没有。
- **logcat 证据**：
  ```
  W OplusAppStartupManager: prevent start com.islandbridge, cmp …EventProvider
  E ActivityThread: Failed to find provider info for com.islandbridge.events
  I IslandBridge call fail: …
  ```
  App 进程被安装动作杀掉后，ColorOS 的启动管理**禁止它自启动**，于是模块的
  provider 事件全部投递失败（模块侧只看到 `call fail`），岛上自然没有内容。
- **处理**：安装/更新后**手动打开一次「豆包岛桥」**，或在 ColorOS
  设置 → 应用 → 自启动 里允许它；本次排查中已用 root `am start` 拉起验证通过。
  已写进 README 的注意事项。
### 21. 省略号彻底清干净（正文那一行也是它在冒）+ 分组长度可实时调
- **现象**（用户追问）：「我还是看见了...在岛屿上显示，15 个字符还是太多了吗」。
- **两个残留来源**（都不是胶囊分组本身）：
  1. **卡片正文**（那两行）走的是另一套逻辑 `tailOf(body, EXPAND_MAX)`，它会在前面
     补一个 `"…"` —— 胶囊干净了，正文还在冒省略号。
  2. 正文如果超过岛实际能容纳的两行，**岛自己**还会再截一个 `"…"`。
- **三个根因**：
  - `tailOf()` **不再补 `"…"`**：它给的是"最新一段"的滑动窗口，内容随推流往后走，
    本来就看得出是被截过的，再加省略号只会和岛的截断叠在一起。
  - 正文上限 `EXPAND_MAX` 48 → **36 字**（两行 × 约 18 个汉字），留出余量，
    不让岛有机会自己截断。
  - 默认分组 `LYRIC_GROUP` 15 → **10 字**；并做成**可实时调**：
    `filesDir/ib_lyric.txt` 第一行写 4~40 的数字，3s 内生效，**不用重装 APK**
    （胶囊右侧实际宽度随机型/系统字号而变，真机看着调最快）。
- **实测**（长回答推流中，`ib_lyric.txt` 设成 12）：
  ```
  岛card src=手机 st=正在回复 body=36字 roll=12字
  岛card src=手机 st=正在回复 body=24字 roll=12字   ← 正文滑动、胶囊按组走
  ```
  `岛card.*…` 全量检索**零命中** —— 正文与胶囊都不再有省略号。
### 22. 推流中「卡住不刷新，直到回答完毕」+ 正文改成只占一行
- **现象**（用户原话）：「回复一定程度会卡住然后不会继续刷新回复的内容，
  直到最后回答完毕」。
- **先排除 App 侧**：复现时 App 每 **500ms 一帧**、内容每帧都在变、时间戳连续
  没有断档 → 卡的不是我们，是**岛的渲染侧**。
- **岛框架日志给出真凶**：
  ```
  V WindowManager: Relayout Window{AstraIsland}: req=1440x2066
  V WindowManager: Relayout Window{AstraIsland}: req=1440x760
  W VRI[AstraIsland]: handleResized abandoned!
  D BLASTBufferQueue: producer disconnected before acquireNextBufferLocked
  ```
  两个高度**反复来回**：我们每 500ms 把整张卡片重发一次，而卡片正文长度在
  24~36 字之间**摆动**（因为按标点断句，切点会跳），窗口高度就跟着变 ——
  岛一直重排/改尺寸，撑不住就表现为"卡住"，直到最后那一帧才跳过去。
- **三个根因**：
  1. 推流中正文改为**等长**尾巴（`tailOf(..., boundary = false)`），不再按标点
     断句 → 高度恒定；只有收尾那一帧才断句；
  2. 节拍 `LYRIC_TICK_MS` 500 → **1000ms**（落后超过 6 组时播放器一次跨两组追）；
  3. **用户要求正文只占一行**：`EXPAND_MAX` 48 → 36 → **18 字**（一行放得下），
     超出部分靠滑动窗口滚，永远显示最新一段。
- **实测**：帧恒定 `body=18字 roll=12字`、无省略号；岛窗口 `Relayout` 从"反复
  来回"降到 **2 次**（`1440x2066 ×1` 展开 + `1440x760 ×1` 收起），
  `handleResized abandoned` 4 次 —— 刷新恢复连续。
### 23. 岛上出现 `**`（豆包的 markdown 记号）
- **现象**（用户原话）：「我看见实际回复没有 ** 但是岛里有」。
- **原因**：豆包推流原文就带 markdown 记号，豆包**自己的界面**把它渲染成粗体、
  把记号藏了起来；岛上只是纯文本，于是原样露出来。原样证据（模块日志）：
  `chat.delta ... "text":"字**吗？"`。
- **修法**：新增 `markdownToPlain()`，只做**最小清理**（去 `**`、`__`、行内
  `` ` ``），卡片正文与胶囊歌词都改读清理后的文本 `replyDisp`（两处用同一份，
  保证分组下标一致）。整理能力边界：岛的正文是纯文本，SDK 没有富文本 API，
  所以只能去掉记号、不能真渲染粗体。
- **实测**：原文含 `**` 的那条回答，App 日志 `去 markdown 记号 8字（原文带 ** 等…）`，
  岛帧里不再有记号。

### 24. 正文只占一行（真机实测放不下就继续调）+ App 内调参与预览
- **现象**（用户原话）：「奇怪，怎么还是两行，虽然第二行只有一点点的内容」→
  18 字仍折行，说明这一行大约只放得下 15 个汉字。
- **能不能"算"出来**：不行。卡片是**岛自己（SystemUI）渲染的**，我们的进程拿不到
  它的字体/内边距；实机 `uiautomator dump` 也**看不到岛**（dump 里只有豆包自己的
  节点，已实测），所以只能"看着数行数"。
- **修法**：把两个数字做成**可实时调**（`filesDir/ib_lyric.txt`：第 1 行胶囊分组、
  第 2 行正文一行字数，3s 生效、**不用重装**），并在 App 里加了
  「岛屿显示」区：两个 −/＋ 调节 + **「在岛上预览回复」**按钮（走和真实回复完全
  相同的路径推一段样例，直接在岛上数行数）。默认 正文 14 字 / 分组 10 字。
- **实测**：`ib_lyric.txt` 写 `12 / 14` → 岛帧 `body=14字 roll=12字`（读取生效）。
### 25. 回复卡片改为明细卡（DetailsCard）：一个 logo + 手机/电脑并列，按存在逻辑显示
- **用户要求**：明细卡可以并排显示 \u2192 铺「手机」「电脑」两块；**有电脑就显示电脑、
  手机就显示手机、两个都在就都显示**；去掉不需要的部分（tag、标题图标、大数值），
  简述放内容，按钮成排。
- **关键修正（用户补充）**：*电脑「在线」不等于「有响应」* —— 光有 SSE 连接不算，
  还必须**最近 20s 内真的收到过电脑推来的东西（含心跳行 `:`）**才在岛上列出电脑
  （`PC_LIVE_MS = 20_000`，`SseClient.lastFrameAt`），否则会出现一个「在线但什么都
  不干」的电脑，反而误导。
- **实测**：电脑端 daemon 未运行时 `srcs=手机`（电脑不显示，符合要求）；
  明细卡**没有被岛拒**（无「卡片按钮组合被拒」降级日志），岛帧仍为
  `body=14字 roll=12字 srcs=手机`。
### 26. 多来源卡片（电脑+手机）与 adb 模拟通道
- **需求**：明细卡里电脑/手机都"在响应"时要并列显示。
- **模拟通道**（为真机验证而做，用户要求"写一个模拟脚本，通过 adb 传入数据"）：
  root/adb 往 `filesDir/ib_sim.txt` 写 `pc on` → App 在 10 分钟内把电脑一并列出
  （写 `pc off` 取消）。放在 filesDir 的原因：那里只有本应用自己和 root 能写，
  第三方应用写不进去，所以 **release 包也能带**，不必先装 debug 包；并且它只影响
  "显示什么"，不参与发送/鉴权。
- **脚本**：`pc/sim_island.py both|off|show` —— 写模拟文件 + 走豆包分享入口触发一条
  真实回答 + 打印 App 卡片帧，全程只用 adb。
- **实测**：`both` \u2192 卡片帧 `srcs=电脑,手机`；`off` \u2192 `srcs=手机`。
add = (u"- **已按用户要求撤下**（原话「还是取消 DetailsCard 的版本」）：`useDetailsCard` 默认改为\
  false，回复卡片回到原来的通用卡（`GenericCard`；开启「回复输入条」时是消息卡，与本次改动前完全一致）；形态写进日志便于确认。
       u"  电脑/手机的 adb 模拟通道（`pc/sim_island.py`）保留，仅作诊断用。\
### 27. 收起状态只显示来源+状态（不再滚动正文）；停留时间拉满
- **用户要求**：「岛未展开的时候显示 安卓包/电脑包 回答进行/回答结束，不要滚动显示内容，
  展开时间拉满」。
- **改动**：① 胶囊右侧（卡片里唯一会滚动的区域）**不再放回复正文**，改成静态的
  `来源·状态`；② 状态用词改为 **回答进行 / 回答结束**（暂停时仍是「内容已暂停」）；
  ③ 来源显示名 **安卓包 / 电脑包**（`replySrc` 的语义值仍是 手机/电脑，两套名字分开，
  避免把发送路径判断改坏）；④ 自消时间 `DISMISS_AFTER_MS` 1 分钟 → **10 分钟**，
  展开后不会自己收掉（要收点「我知道了」）。
- **实测**：卡片帧 `st=回答进行` / `st=回答结束`，`roll` 长度全程恒为 8 字
  （`安卓包·回答进行`），即正文不再进胶囊。
### 28. 收起状态彻底不循环：右侧滚动区留空 + 关掉呼吸动画
- **用户反馈**：「收起的时候还是在循环」——把来源·状态放进 `setTrailing` 那一刻就注定了：
  那块区域（api-doc:126）**本身就是滚动区**，放什么都会滚。
- **改动**：① `setTrailing` **整句删掉**（右侧留空，不再有任何滚动内容）；
  ② 来源·状态改用 `setLabel`（左侧静态文字，只在状态真的变化时才变）；
  ③ 左侧头像的**呼吸动画也关掉**（`setBreathing(false)`）——它同样是个循环。
- 结果：收起状态是一块**完全静止**的胶囊（`安卓包·回答进行` → `安卓包·回答结束`）。
### 29. 胶囊一左一右显示不同内容（左=来源，右=状态）
- **用户提问**：「不可以一左一右显示不一样的内容吗」「内容无法穿越摄像头，我们没有办法控制岛的长度吗」。
- **长度答复（有据）**：`IslandActivity.Builder` 的全部 setter 里**没有**长度/宽度/尺寸/位置
  控制（只有 priority / alert / lockScreen / openIntent / notification / posted·stale /
  dismissPolicy / landscapeText / accentColor / contentDescription），所以胶囊长短是宿主
  按内容自己量的；挖孔是系统硬边界。App 唯一能左右长度的旋钮就是**往里放多少字**。
- **改动**：左槽 `setLabel` = 来源（安卓包/电脑包），右槽 `setTrailing` = 状态
  （回答进行/回答结束）。**之前循环的根因**：右侧是宿主唯一的滚动区，但**只有文字超宽
  才会滚** —— 我把 8 个字的整串放进去才滚；每边 3–4 个字是静止的。
- **实测**：卡片帧 `left=3字 right=4字`（安卓包 / 回答进行）。
### 30. 一分钟无操作：状态栏提示 + 岛收起来
- **用户要求**：「现状也要保留，但是额外添加一分钟的逻辑，如果触发了一分钟逻辑，就显示
  一个提示在状态栏里，说明 您的豆包消息未确认，然后折叠…可以展开」「一分钟之后要收起来」。
- **实现**：`IDLE_NOTICE_MS = 60_000`。卡片上岛开始计时；**正在回答时先不触发**（每个
  周期重新计时，等于"回答结束起 60s"），避免读到一半被收走。触发时：
  ① 发状态栏通知，标题就是 **「您的豆包消息未确认」**，正文用 BigText 放回复内容
  （可展开看全文，点它回本 App）；② 调 `dismissReply()` 把岛**收起来**。
  用户任何操作（点我知道了/删除/回复）都会走 `dismissReply` → `cancelIdleNotice()` 取消计时。
- **踩坑（已修）**：通知小图标一开始用了 App 的自适应启动图标，系统直接拒
  （`Invalid notification (no valid small icon)`），换框架 drawable 才发得出去 ——
  所以第一次实测只完成了「收起来」，通知是第二次才验证通过的。
### 31. 启动图标与来源 logo 都改成豆包 App 的图标
- **用户要求**：「补全我们 app 的 logo 还有信息来源的 logo，都直接拿豆包 app 的图标」。
- **事实核对**：卡片的来源 logo **本来就是**豆包真图标（`doubaoIcon` =
  `packageManager.getApplicationIcon("com.larus.nova")`，运行时取，不占包体）；
  真正缺的是**我们 App 自己的启动图标** —— 之前 `AndroidManifest` 里连
  `android:icon` 都没有、`res/` 下没有任何图标资源。
- **做法**：用设备上的 `unzip`（豆包 base.apk 414MB，不整包拉取）从
  `/data/app/.../com.larus.nova.../base.apk` 里精确取出默认皮肤的
  `res/mipmap-xxxhdpi-v4/larus_icon_{foreground,background}_12.png`，放进
  `app/src/main/res/mipmap-xxxhdpi/`（自适应图标用），另用 Pillow 合成一张
  512×512 的传统图标 `ic_launcher.png`；`AndroidManifest` 加
  `android:icon` / `android:roundIcon`。
- 另外把**胶囊左侧的来源图标**从内置符号 `BuiltinSymbol.MESSAGE` 换成豆包的
  真图标（`IslandImage.picture(doubaoIcon)`）——这才是"信息来源的 logo"。
- **版权说明**：图标取自用户本机已安装的豆包 App，按用户明确要求使用，仓库里
  不再另外分发第三方素材（本目录只存这几个 PNG）。
### 32. 多会话：卡片改成「一会话一张」+ 副岛，主岛归最先收到内容的那条会话
- **用户要求**（原话要点）：「把回复卡片从**单份会话**改成**按会话一份**，多会话
  时产生副岛，并且**主岛归最先收到内容的那条会话，直到它被收掉**」。
- **改动前的病灶**：整条回复链路只有**一份**全局状态 —— `replyCid / replyBuf /
  replyDisp / replyTitle / replySrc / replyShown / replyEnded / replySuppressed`
  + 歌词播放器（`lyricPos/lyricLast/lyricRunning`）+ 卡住看门狗 + 一分钟无操作
  计时器；岛事件 id 还是常量 `ID_REPLY = "reply"`。因此在真机上：
  ① 第二条会话的 `chat.start` 会把第一条**正在推的流**整份清掉（旧版一收到
  start 就直接重置那一份全局状态）；
  ② 两张卡共用一个活动 id，岛侧只会看到一个活动，后到的覆盖先到的；
  ③ 任一卡上的「我知道了 / 删除 / 回复」都会把**所有**会话的卡一起收掉；
  ④ 一分钟无操作计时器被任一会话续期/取消，彼此干扰。
- **改法**（`IslandBridge.kt`：单份状态 → 每会话一份 `Conv`）：
  - 新增 `private inner class Conv`，按 `cid` 建表：`convs: LinkedHashMap<String, Conv>`
    （插入序 = 先到序）+ `convOrder` 有序表（= 用户说的"先到次序表"）；
    岛事件 id 由常量改为 `reply:<cid>`（`ID_REPLY_PREFIX`），**每条会话一个
    `IslandActivity`**；无 cid 的旧调用（App 内测试按钮）归到 `reply:-` 一条，
    行为与旧版一致。
  - 旧字段一一搬进 `Conv`：`replyCid→cid`、`replyBuf→buf`、`replyDisp→disp`、
    `replyTitle/replySrc→title/src`、`replyStartedAt→startedAt`、`replyShown→shown`、
    `replySuppressed→suppressed`、`replyEnded→ended`、`replyBotId→botId`、
    `pendingBody/flush→Conv.*`；**歌词播放器**（每组一个 `lyricTick` Runnable）、
    **卡住看门狗**、**一分钟无操作计时器**、通知 id（`0x1B17 + slot`）也全部按会话
    各一份 —— 这是"互不干扰"的关键。
  - **主岛归属**：`chat.start` 时若主岛无人（`primaryCid` 空）→ 本条取
    `Priority.HIGH`（主岛；日志 `主岛归先到者 …`）；否则取 `Priority.DEFAULT`
    （副岛；日志 `新会话进副岛 …（先到者 … 仍占主岛）`）。**"在线"定义为卡片还在
    岛上（`shown`）** —— 回答已结束但没被收掉的卡**照样占着主岛**，正是用户要的
    「直到它被收掉」。
  - **收掉先到者才升级**：每条收起路径（点「我知道了」`ACT_ACK`、删除成功/超时、
    点卡片空白处、宿主 `onDismissed / onEnded`）都只作用于**它自己那条会话**，随后
    `afterConvGone()` 从有序表里挑下一条**还在线**的会话，用 `Priority.HIGH`
    **重新 post 一次**（日志 `先到者已被收掉 → 副岛升级为主岛 … prio=high`）；
    全收完则日志 `主岛空出（当前没有在线的会话）`。`effectivePrimary()` 会在
    `primaryCid` 失效时按有序表重新推导，避免留下"幽灵主岛"。
  - **按钮 / 回复 / 删除只影响自己那张卡**：新增 `convOfId(id)`，从活动 id
    `reply:<cid>` 反解会话；`onAction / onReplyText / onDismissed / onEnded` 一律
    先反解再操作。点卡片空白处的 `PendingIntent` 改成**每会话一个**
    （`openIntentFor(c)`，requestCode 用会话 key、extra 带 `cid`），
    `OpenDoubaoReceiver` 把 `cid` 原样透传给 `cardTapped(key)` → 只收掉被点的那张。
  - **上行也带会话号**：`sendViaMobile` 增发 `putExtra("cid", …)`、新增
    `pendingSendKey`，回执/超时/兜底按会话落账（不再"回错卡"）。
  - 会话表加上限 `MAX_CONVS = 8`（超出按先到序回收最老的且已下线的），长跑不泄漏。
- **日志格式**（新增字段，方便真机核对；**不打印回复正文**，只有长度/状态/cid 后 6 位）：
  `岛card id=reply:<cid> prio=high|default cid=<后6位> src=电脑|手机
  st=回答进行|回答结束|内容已暂停 body=N字 left=N字 right=N字 srcs=…`。
- **多会话测试通道**：`filesDir/ib_sim.txt`（只有 root/adb 可写）从"只认 `pc on/off`"
  扩展为可喂**多行 JSON 事件**（`chat.start / chat.delta / chat.end`，各自带 `cid`），
  外加 `{"t":"sim.action","cid":…,"a":"ack"}` 用来模拟岛上按钮回调；App 每秒轮询，
  然后**走与真实推流完全相同的分发路径**（`dispatchSim → handleEvent`），不是另写
  一套旁路。配套脚本 `pc/sim_multi.py`（`start` / `ack <cid>` / `show` / `run`）。
  - **踩坑（已修）**：模拟文件是"整份重写（原子 mv）"，新内容长度**经常和上一轮
    一样**，第一版用"已读字节数"比较会漏读（真机上表现为第二次 `ack` 不生效）；
    改成整读 + 用单调序号 `n` 作水位去重。
  - **另一个真机事实**：ColorOS 会把退到后台的 App **冻结**（进程在、线程全停），
    而模拟通道是 App **自己轮询**的 —— 不 poke 就永远不轮询。脚本等待期间每秒
    做一次 `content query --uri content://com.islandbridge.events`（纯解冻探针，
    模块在真实场景里也是每 3s `callProvider` 一次），真实推流到达时同样是这个唤醒。
  - **顺带收紧**：同一个文件还承载原来的"模拟电脑在线"（`pc on`），判定从
    `contains("pc on")` 改成**按整行**匹配 —— 否则 JSON 事件正文里只要出现
    `pc on` 字样，岛上就会凭空多出一个"电脑"来源（第 50 行有隔离实测）。
- **真机证据**：见下面第三节新增的 43~50 行（模拟通道两会话 + 豆包自己起的两条
  真实会话 + 点卡片只收自己 + 冷启动单会话照旧 + `pc on` 通道隔离实测）。
### 33. 会话标题不显示：卡片标题永远是兜底值「安卓包-豆包」
- **现象**（用户报的）：真机上岛卡片标题一直停在兜底值 `安卓包-豆包`（`cut("${srcWord(c)}-${c.title}", …)` 里 `c.title` 从没被赋过真名），豆包侧真实的会话名（如「测试」）用不上；胶囊左槽同理只有来源词。
- **根因**：手机侧回复走 **native IM 通道**（`writeChunkData` → `MobileFeedParser`），而模块学会话名的唯一来源是**桌面端 SSEx 协议**里的 `conversation_id + name`（`chat.conv`）——该帧在手机路径上根本不出现，`convNames` 一直空着，`beginConv()` 的 `if (nm.isNotBlank()) c.title = nm` 从不生效。
- **修法**（三个来源，全部走已有的 `chat.conv` 事件，App 侧拿名字后按需重推卡片）：
  1. **被动**：`OmniConversationDispatcher.notifyChange/d` 的入参列表里就有 `com.larus.im.internal.jni.bean.OmniConversation`（真机 `convList size=1 elem=…OmniConversation`，`component6='豆包'`）。**必须读字段，不能读 getter** —— 该 bean 的属性是 `@JvmField`（`idx24.tsv` 里只有 `getConversationId$annotations` / `getName$annotations`，**没有** `getConversationId()` / `getName()`）；第一版只用反射 getter，真机就空手而归（日志 `会话名查询失败 …（对象里没有名字字段，u99.e）`）。
  2. **主动**：`chat.start` 时用 `ConversationServiceImpl.getConversation(cid)` 查一次；真机回的是混淆过的 legacy 模型 `u99.e`（字段 `a`=会话 id、`c`=会话名，且没有可见 getter），所以按「值 == 请求的 cid」定位 id 字段、再挑一个像标题的字符串（排除纯数字/UUID/JSON/空串）。
  3. **回填**：`chat.end` 之后 4s 再查一次（应对服务端稍后才写自动标题）。
- **App 侧**：`chat.conv` 分支学到名字时打 `会话名 cid=… '名字'（N字）来源=chat.conv`；若该会话卡片已在岛上且标题变了，日志打 `会话标题更新 cid=… '旧' → '新'` 并用**同一个活动 id 重推**（同 id = 更新，不弹新卡）；`岛card` 日志新增 `title='…'` 字段，标题是否等于 `${来源}-${会话名}` 一眼可见。
- **踩坑（已修）**：模块的「同一 cid+名字只发一次」去重表是按**豆包进程**存的，而 App 可能在这之后重启（内存里的 `convNames` 清空）→ 会出现「App 重启后再也收不到会话名、标题永远是兜底值」。改成**主动查询那条路 `force=true`**（每条回复多发一次 binder 事件，App 侧自己按名字变化去重）。真机复现+修复记录见第三节 51 行。
- **真机证据**：见下面第三节 51、52 行。

### 34. 切割电脑端：安卓端彻底断开（PC 侧整体删除，`pc/` 目录冻结留档）
- **背景**（用户决定）：这个项目原来是「电脑端（Python，CDP 抓豆包桌面端）+ 安卓端（LSPosed 抓豆包 App）」两头并存的。用户明确要求**安卓端彻底切断电脑端**：不再连接、不再显示、不再向电脑端发送任何东西。`pc/` 目录**不删、不改代码**，只冻结留档。
- **删掉的东西（物理删除，不是注释掉 —— 全仓库 `grep` 可验证）**：
  | 类别 | 删除内容 |
  |---|---|
  | 传输 | `SseClient.kt`（okhttp HTTP/SSE 客户端，整文件删除）、`BridgeApp.link`、`BridgeApp.connectTo/disconnect/ensureConnected`、`BridgeApp.owner`（「手机优先于电脑」的帧归属仲裁表）、`SseClient.postReply/postDelete` |
  | 卡片语义 | `IslandBridge.PC_LIVE_MS`、`simPcLive()`（`files/ib_sim.txt` 里的 `pc on` / `pc off`，「模拟电脑在线」）、`sources()` 里的「电脑 · 已连接 / 模拟在线」分支、`srcWord()` 的 `"电脑" -> "电脑包"` 分支、`deleteViaPc()`、`sendViaPc()`、`onSendResult` 的 `src="pc"` 参数、`beginConv` 的 `fromMobile` 参数与 `c.src = if (fromMobile) "手机" else "电脑"` 二选一 |
  | 事件来源判定 | `EventProvider` 里按 extra `src` / 负载 `"src":"pc"` 判「电脑端帧」的整段映射（`"pc","desktop","电脑" -> false`）、`EventSink.submit(..., fromMobile)` 与 `preInit` 的 `Pair<String,Boolean>`、`BridgeEventReceiver` 的 `ACTION_EVENT_PC`（伪装成电脑 SSE 帧的调试入口）与清单里的 `com.tg.dbisland.EVENT_PC` |
  | UI / 配置 | `MainActivity` 里整块「电脑端连接配置」（`SHOW_PC_CONFIG` 开关、`电脑IP` / `端口` 输入框、「连接」/「断开」按钮、`prefs host/port`）、状态行绑定的 `app.link.onState` |
  | 文案 | `BridgeService` 常驻通知正文「保持与电脑的连接」→「保持岛上连接与豆包保活」 |
  | root 中继组件 | `android/app/src/main/assets/root/` **整个目录**（`module.prop` / `service.sh` / `uninstall.sh` / `relay.sh` / `listen.sh` / `launcher.sh` —— 它们只做「局域网 `listen.sh` 收电脑端帧 + 队列 `relay.sh` 重投」）、`BridgeService` 的 `installRoot / uninstallRoot / reinstallRoot / readListenToken / doInstall / ensureRootRelay / isRemoveMarked` 与 `ASSETS` / `DIR` / `MODDIR` / `LEGACY_SERVICE_D` / `DISABLED_MARKER` / `K_ROOT_DISABLED`、`MainActivity` 的「重启 root 组件 / 卸载 root 组件 / 查看局域网监听令牌」三个入口与 `confirmUninstallRoot()` / `showListenToken()`、`EnvCheck` 的 `rootModule` 检查与 `__ROOTMOD__`/`__LEGACY__`/`__LISTENBIND__`/`__TOKLEN__` 探针、`DoubaoHookEntry.sendTo` 里往 `ibq.log` 追加 base64 事件（那条链路的读者就是 root `relay.sh`，现在没人读了） |
  | 权限 / 依赖 | `INTERNET`、`ACCESS_NETWORK_STATE`、`usesCleartextTraffic="true"`（旧版就是为了连局域网电脑端）、`implementation("com.squareup.okhttp3:okhttp:4.12.0")` |
  | 脚本 | `tools/ib_relay.sh`（安卓侧 root 中继的裸脚本，引用已删的 `BridgeEventReceiver` 广播链路） |
- **为什么这么删**：这些内容的**唯一用途**就是电脑端。SSE 是「连电脑」，`电脑/电脑包` 是「显示电脑」，`pc on` 是「假装电脑在线」，root 的 `listen.sh`+`relay.sh` 是「替电脑端把帧塞进 App」——没有电脑端，它们全是死代码，留着只会让下一个人以为还有第二条数据来源。
- **保留了什么（`es` 之后仍要能用的部分，逐条对照过）**：豆包 hook 推流（`DoubaoHookEntry` + `MobileFeedParser` + `EventProvider` Binder 通道）、星河岛卡片（`IslandBridge` 全部卡片/歌词/正文逻辑）、多会话（`reply:<cid>` 每会话一张卡 + `convOrder` 先到者占主岛直到被收掉 `afterConvGone`）、保活/唤醒（`KA_IDLE_MS`/`KA_PING_MS` 保活窗、`SEND_WAKE_MS` root 唤醒豆包、`coldSendAsRoot` 分享直投）、一分钟无操作通知 + 收起（`IDLE_NOTICE_MS`）、豆包图标（`doubaoIcon`）、胶囊左=来源/右=状态。
  - **注意区分**：这里删的是 **root 中继组件**（常驻 `/data/adb/modules/islandbridge` 的 `relay/listen` worker），**不是** root 本身。「发送后豆包在后台没接 → 借 root 拉起豆包并重投」（`wakeScript`/`EnvCheck.sh`）是保活/唤醒能力，**保留**，只是路径跟着包名改成 `/data/data/com.tg.dbisland/files/ib_send.req`。
- **模拟通道保留多会话注入，删掉「模拟电脑在线」**：`files/ib_sim.txt` 仍支持一行一个 JSON（`chat.start` / `chat.delta` / `chat.end` / `sim.action`），App 每秒轮询、走和真实推流完全相同的分发路径；`pc on` / `pc off` 这种整行非 JSON 的「模拟电脑在线」写法删掉（`pollSim` 本来也只认 `{` 开头的行）。
- **`pc/` 目录冻结**：新增 [`pc/FROZEN.md`](pc/FROZEN.md) 说明「电脑端已从本项目分离，此目录冻结，安卓端不再引用它」；`pc/` 内代码**一行未改**（`grep` 仍能在里面看到 `com.islandbridge`，那是留档的历史代码，按用户要求不动）。原本放在 `pc/` 里的 `sim_multi.py` 属于**安卓侧诊断**（多会话/副岛/先到者占主岛的 adb+root 模拟，不依赖电脑端进程），已移到 [`tools/sim_multi.py`](tools/sim_multi.py)，路径引用同步改成新包名。
- **用户可见后果**：设备上原来由本 App 安装的 `/data/adb/modules/islandbridge`（以及 1.0 遗留的 `/data/adb/service.d/islandbridge.sh`）**不再由本 App 管理**——新版 App 里没有安装/卸载入口了，需要用户在 root 管理器里自行移除。App 从此不再向 `/data/adb` 写任何东西，也不再申请网络权限。
- **验证证据（原文）**：
  - `aapt2 dump badging` 里 `uses-permission` 已无 `INTERNET` / `ACCESS_NETWORK_STATE`（只剩 `CONTROL`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_DATA_SYNC`、`POST_NOTIFICATIONS`、`RECEIVE_BOOT_COMPLETED`、`WAKE_LOCK` + SDK 合并的 `PUBLISH_ACTIVITY`、`DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`）。
  - App 首页真机截图 `dev/ib_v12_cut.png`（12:35）：标题下方**直接就是「正文一行字数 / 胶囊分组长 / 在岛上预览回复」**，**没有电脑端配置块**；环境自检只剩 `Root 权限 / LSPosed 框架 / 模块状态` 三行（**没有「Root 组件」行**），也没有「重启/卸载 root 组件」「查看局域网监听令牌」按钮。
  - 源码级：`android/app/src/main/assets/` 下只剩 `xposed_init` 与 `chat/chat.html`；`grep -rn "assets/root\|installRoot\|uninstallRoot\|readListenToken\|simPcLive\|PC_LIVE_MS\|postReply\|postDelete\|SseClient\|okhttp" android/app/src android/app/build.gradle.kts` 无命中。
  - `python -m unittest discover -s pc/tests` → `Ran 72 tests ... OK`（`pc/` 只多了一个 `FROZEN.md`，测试未受影响；`pc/tests/test_uplink_protocol.py` 里那三处 `com.islandbridge.SEND/DELETE` 断言**照旧通过**，因为 `pc/` 冻结未改）。

### 35. 包名迁移：`com.islandbridge` → `com.tg.dbisland`（含代码 namespace）
- **为什么**：用户明确要求全量改名（含代码 `namespace`），不只是显示名。`com.islandbridge` 会与仓库里那套 root 模块 id / 历史文档混淆，改成 `com.tg.dbisland` 之后包名、authority、权限、广播 action、LSPosed 入口类名全部自洽。
- **改了什么（逐项）**：
  | 位置 | 旧 | 新 |
  |---|---|---|
  | `android/app/build.gradle.kts` | `namespace` / `applicationId` = `com.islandbridge` | `com.tg.dbisland` |
  | 源码目录 | `app/src/main/java/com/islandbridge/**` | `app/src/main/java/com/tg/dbisland/**`（11 个 `.kt` 全部搬迁） |
  | `package` / `import` | `package com.islandbridge[.xposed]`、`import com.islandbridge.BridgeSecurity` | `com.tg.dbisland[.xposed]` |
  | 签名级权限 | `com.islandbridge.permission.CONTROL` | `com.tg.dbisland.permission.CONTROL` |
  | Provider authority | `com.islandbridge.events` | `com.tg.dbisland.events` |
  | 自定义 action | `SEND` / `DELETE` / `PING` / `KEEPALIVE` / `EVENT` / `OPEN_DOUBAO` / `EVENT_RELAY` | 同名前缀换成 `com.tg.dbisland.*` |
  | LSPosed 入口 | `assets/xposed_init` = `com.islandbridge.xposed.DoubaoHookEntry` | `com.tg.dbisland.xposed.DoubaoHookEntry` |
  | 硬编码包名 | `getPackageUid("com.islandbridge")`（`BridgeSecurity` uid 白名单）、`Context.RECEIVER` 组件名 `PKG_SELF`、`/data/data/com.islandbridge/files/ib_send.req`（root 唤醒脚本）、`am broadcast -a com.islandbridge.SEND`（同一脚本） | 全部 `com.tg.dbisland` |
  | `proguard-rules.pro` | `-keep class com.islandbridge.xposed.**` / `com.islandbridge.*Receiver` | `com.tg.dbisland.*` |
- **`BridgeSecurity` 的 uid 白名单跟改过**：`bridgeUidOf()` 用 `packageManager.getPackageUid("com.tg.dbisland", 0)` —— 这条是命令通道（`SEND`/`DELETE`）第二道闸的判据，改包名后如果漏改，模块会把**所有**来自本 App 的合法命令拒掉（真机表现就是「回复失败、等 8s 没回执」）。同一文件里的 `PERM_CONTROL`、注释与本 App 自报 uid 的日志一并同步。
- **用户可见后果（重要，必须提前告诉用户）**：
  1. **旧包 `com.islandbridge` 需要卸载**：新包是另一个 `applicationId`，两者会**并存**（真机实测 `pm path` 同时列出两个：`/data/app/…/com.tg.dbisland-…` 与 `/data/app/…/com.islandbridge-…`）。旧包不会自动消失，也不会被新包覆盖。
  2. **LSPosed 会把新包当成一个全新的模块**：旧包在 LSPosed 里的「已启用 + 作用域勾选」**不会**跟着迁移。新的 `com.tg.dbisland` 需要在 LSPosed 管理器里**重新启用并勾选作用域**（`com.larus.nova` + `android`）。App 内环境自检现在如实显示 `LSPosed 已运行 · v2.2.0 (7854) · 未登记本模块`（旧包在 LSPosed 数据库里仍是 `enabled=0`，作用域表里也还没有新包）——**这一步由用户/Lead 在 LSPosed 里操作，本次改动不碰 LSPosed 数据库**。
- **验证证据（原文）**：
  - `aapt2 dump badging`：`package: name='com.tg.dbisland' versionCode='3' versionName='1.2' platformBuildVersionName='16' compileSdkVersion='36'`；`application: label='豆包岛桥' icon='res/BW.xml'`；`application-icon-160/240/320/640/65534:'res/BW.xml'`（图标仍在）；`launchable-activity: name='com.tg.dbisland.MainActivity'`；`uses-permission: name='com.tg.dbisland.permission.CONTROL'`、`uses-permission: name='com.tg.dbisland.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'`（AndroidX 按新包名自动生成）。
  - 装机：`adb -s d666858b install -r android\dist\doubaodao-v1.2-release.apk` → `Success`；`pm path com.tg.dbisland` → `package:/data/app/~~_FXoqrFDfeXJF51mqM162A==/com.tg.dbisland-J2o76KJnyxA5Ir8qA8D4wg==/base.apk`。
  - 启动（ColorOS 拦自启动，必须 root 拉起）：`adb shell su -c 'am start -n com.tg.dbisland/com.tg.dbisland.MainActivity'` → `Starting: Intent { cmp=com.tg.dbisland/.MainActivity }`；随后 logcat 出现 `WindowManager: NFW_findFocusedWindowIfNeeded:Window{1151e5b u0 com.tg.dbisland/com.tg.dbisland.MainActivity} mCurrentFocus:Window{…com.tg.dbisland.MainActivity}`、`AudioFlingerExtImpl: updateForegroundInfo … [com.tg.dbisland]=1, uidPidMapInf(1)|[10045]=16852`；`su -c pidof com.tg.dbisland` → `16852`；**无 `FATAL EXCEPTION` / `AndroidRuntime` 崩溃**。
  - App 首页真机截图 `dev/ib_v12_cut.png`（见第 34 条的同一张）：状态行 `岛状态: READY`，日志行 `uid: app=10045 doubao=10375` —— 证明新包的新 uid（10045）已被 App 自己识别，岛连接已就绪。
  - 全仓库核对命令与结果见下面第四节（`grep -rn "com\.islandbridge" …`，剩余命中只允许出现在 CHANGELOG 历史条目、`pc/` 冻结留档、`dev/` 历史日志三类上下文里）。
- **没做/做不到的**：**岛上的端到端验证暂时做不了** —— LSPosed 还没启用新包（`未登记本模块`），模块没有注入 `com.larus.nova`，所以「豆包推流 → 卡片上岛 → 岛上回复/删除」这条链路本次**没有**真机跑过，不能算已验证（见第五节）。另外，改名后 LSPosed 里的旧模块条目、以及旧包 `com.islandbridge` 的卸载，都留给用户在 LSPosed 管理器 / 系统设置里操作。

### 36. 设备侧收尾：电脑端根组件撤离 + 星流来源残留清理
- **触发**：用户报告"星流仍识别到 `tgdsh.xindao`、`tugou.dsh` 这些残留"，
  并要求切割电脑端。
- **定位过程（先说没找到的地方，避免下次重复找）**：
  - LSPosed 配置库 `module_configs` **339 行**，但 `key_name LIKE 'source_enabled%'`
    **0 条**、全表任何列都**不含** `tugou/tgdsh/xingdao/islandbridge/dbisland`
    字节 —— 上次那次"删死条目"的清理是有效的，没有回潮。
    （339 vs CHANGELOG 记的 336，多出来的 3 行是星流自己的 `astraflow_config`
    组新键，与本项目无关。）
  - `packages.xml` 里三个旧包名各有 1 行历史记录 —— Android 16 上是 ABX 二进制、
    系统托管，**未手改**（与第 7.3 条同样处理）。
  - **真正的位置**：星流自己的 `shared_prefs/island_app.xml` →
    `<set name="seen_sources">`，它把"见过的每个岛源包名"永久记着：
    `com.islandbridge` / `com.astraflow.tool` / `com.tugou.dsh` / `com.tgdsh.xingdao`。
    即使包早卸载了也不清 —— 这就是用户在星流界面上看到的"残留"。
- **处理**：`am force-stop com.astraflow.tool` → 备份 → 删掉 3 个死条目（保留
  星流自己）→ 按写回前现读到的 `u0_a50:u0_a50 660` 还原属主权限 → 重启 SystemUI
  （岛由星流注入 SystemUI 的模块渲染，那份 SharedPreferences 在 SystemUI 进程里
  有内存副本，不重启会继续用旧清单）。
  证据：`seen_sources` 1359 → 1235 字节，回读只剩 `com.astraflow.tool`。
- **踩坑（值得记）**：这份 XML 是 **CRLF 行尾且行尾混用**，第一版按
  `"        <string>X</string>\n"` 整行替换，**一条都没匹配上**，写回的文件与原文
  逐字节相同 —— 看起来"执行成功"其实什么都没改。改成不依赖行尾的
  `re.subn(r"[ \t]*<string>%s</string>\r?\n?", ...)` 并加写回后回读校验才生效。
  **教训：凡"改完要复核"的操作，断言必须落在结果上，不能只看命令返回值。**
- **电脑端根组件撤离（设备侧）**：`/data/adb/modules/islandbridge/uninstall.sh` →
  杀掉常驻 worker；复核 `relay.sh`/`listen.sh`/`nc -lk -p 8799` **进程已无**、
  `8799` **不再监听**、`/data/adb/islandbridge`（脚本+令牌+pid）与
  `/data/adb/modules/islandbridge` **目录均不存在**。
- **旧包卸载**：`com.islandbridge` 已卸载（复核 `pm list packages -u` 无），
  `/data/local/tmp` 里 5 个调试残留（`ib_p.txt`/`ib_send.req`/`ib_sim_in.txt`/
  `ib_wake.sh`/`islandbridge_relay.log`）一并删除。
- **未完成（依赖人工）**：新包 `com.tg.dbisland` 在 LSPosed 里是**新模块**，需要
  在 LSPosed 管理器里启用并勾选作用域（`com.larus.nova`），**在此之前岛上不会有
  任何卡片** —— 端到端未验证，不得当作已验证。

### 37. 新包端到端验证 + 一处事实更正（LSPosed 无需人工启用）
- **事实更正**：第 35 条写的是"需要在 LSPosed 管理器里为新包重新启用模块"。
  实测**不需要**：清单里的 `xposedscope`（`@array/xposed_scope`，含 `com.larus.nova`）
  让 LSPosed 在安装时就自动登记并启用，库里查得：
  `modules` = `com.tg.dbisland` + 新 apk 路径；
  `modules_state` = `(com.tg.dbisland, user 0, enabled=1, scope_request_blocked=0)`；
  `scope` = `system`、`com.larus.nova`。旧的 `com.islandbridge` 在三张活跃表里都已无行
  （原始文件里仍能 grep 到该字符串，是 WAL/空闲页里的旧字节，不是活跃记录）。
- **端到端验证（新包名）**：`logcat -c` 清缓冲后走分享路径触发一条真实豆包回答，
  新产生的 6 帧：
  ```
  (com.larus.nova)[com.tg.dbisland,XposedBridge,…] IslandBridge 保活续期（app-ping）
  岛card id=reply:716712230022402 prio=high cid=022402 src=手机 st=回答结束
         body=14字 left=3字 right=4字 srcs=手机 title='安卓包-豆包'
  ```
  即：新包名的模块确实注入进豆包、保活链路正常、卡片照常上岛。日志里
  `AIM: AppInfoCacheUpdater -> invalidating apps: [com.islandbridge]`、
  `STU: Failed to get storage stats for package 'com.islandbridge'`
  是系统在清理刚卸载旧包的缓存，属正常收尾，不是故障。
- **仍未人工确认**：主岛/副岛的视觉排布、点击交互（要用户看屏幕）。

### 38. 界面重做：液体玻璃 + 底部 dock + 三页（聊天 / 日志 / 设置）
- **用户要求（原话）**：「重新设计我们的app画面，可以去开源项目 sukisu 里看看他们的液体玻璃，
  dock 显示设置，聊天，日志，聊天的页面左边是会话框可以被折叠右侧是具体的聊天页面，
  日志是模块的运行情况，设置就是对岛的显示配置还有 root lsp 的正常检测」。
- **参考实现**：读的是 SukiSU-Ultra 管理器（`SukiSU-Ultra/SukiSU-Ultra`）的
  `manager/app/src/main/java/com/sukisu/ultra/ui/component/liquid/`（`Lens.kt`
  `CombinedBackdrop.kt` `InnerShadow.kt` `Vibrancy.kt`），那一套本身改编自
  **Kyant0/AndroidLiquidGlass**（Apache-2.0，文件头就是这么标的）。
- **为什么没有直接引它的依赖**：SukiSU 的玻璃依赖 `top.yukonga.miuix.kmp`（提供
  `BackdropEffectScope.runtimeShaderEffect` —— 把**背后已画好的内容**当 shader
  输入真正折射）。本机 Gradle 缓存里没有 miuix；`repo1.maven.org` 也**解析不到
  IPv4**，`plugins.gradle.org` 对 jar 是 303 重定向到 `repo.maven.apache.org`
  （同样不可达）—— 直接表现为 Gradle **静默挂住 20 分钟**（无任何缓存写入）。
  所以最终走 **androidx Compose + 自实现玻璃**，并把可用镜像（阿里云/华为）
  放到 pluginManagement/dependencyResolutionManagement 的第一位（见 settings.gradle.kts）。
- **改了哪些**：
  | 项 | 内容 |
  |---|---|
  | 构建 | Kotlin `2.1.20 → 2.2.10`（+ `org.jetbrains.kotlin.plugin.compose` 2.2.10，两者版本必须一致）；`buildFeatures.compose = true`；依赖钉在缓存里已有的那一组（compose ui/foundation/runtime/animation `1.10.4`、material3 `1.4.0`、activity-compose `1.8.2`、material-icons-extended `1.7.8`），**不引第三方 UI 库** |
  | 新增 | `ui/AppShell.kt`（背景 + dock 三格 + 页面切换）、`ui/Theme.kt`、`ui/Common.kt`（玻璃卡/状态行/无涟漪点击等）、`ui/BridgeHub.kt`（UI 数据源）、`ui/ChatPage.kt`、`ui/LogsPage.kt`、`ui/SettingsPage.kt`、`ui/glass/GlassSurface.kt`、`ui/glass/GlassShader.kt`（AGSL 折射 shader，按 Kyant0 算法移植并标注出处） |
  | 重写 | `MainActivity.kt`：从「LinearLayout 手搭 + `chat.html` WebView」变成 `ComponentActivity` + `setContent { IslandBridgeTheme { AppShell() } }`，只留 insets 设置与 debug 包的 `-e reply` 入口，**不再持有业务状态** |
  | 删除 | `ChatStore.kt`（给 WebView 用的**平铺**事件转发器，没有会话维度，撑不起「左列会话/右看会话」）、`assets/chat/chat.html`（整个 `assets/chat/` 目录） |
  | 保留 | `IslandBridge` 全部岛上逻辑、`BridgeService` 保活、`EventProvider`、`DoubaoHookEntry` —— 只把它的日志出口从 `ChatStore.log` 换成 `BridgeApp.logLine`（Logcat + Hub） |
  | 新增 API | `IslandBridge.sendReply(cid, text)`：按**会话号**回复（新界面右侧聊天页用；旧界面只有一个全局输入框，靠「最近一条」猜） |
- **三页内容**：
  - **聊天**：右侧是所选会话的完整对话（气泡 + think 条 + 「已生成」标记 + 底部回复输入框，发送走 `sendReply(cid, text)` 按会话回）；左侧是**可折叠会话框**，滑出式玻璃面板（宽度 0↔280dp 动画），列出所有会话（标题 + 摘要 + 答复数/推流中圆点），点一行切换右侧、关闭靠面板右上角 ✕ 或点右侧聊天区。
  - **日志**：顶部「模块在线 / 岛连接 / 推流帧 / 上行命令」四行运行状态（心跳判据与 `EnvCheck` 一致：只有豆包进程内的 `KEEPALIVE src=doubao` 算数），下面是级别筛选（全部/信息/提醒/错误）+ 实时日志流（`sys.log`，带时间戳，等宽字体，最多 800 条 FIFO）。
  - **设置**：运行环境（root / LSPosed / 模块注入三项 + 豆包进程 + root 管理器 + 未生效时的排查提示）、岛显示（正文一行字数、胶囊分组长、回复输入条开关、岛上预览——都直接读写 `filesDir/ib_lyric.txt` 与 prefs，3 秒内生效）、调试（三条测试事件）、关于（包名/版本/权限/提供者 + 清空内存记录）。
- **数据层**：`BridgeHub`（`ui/BridgeHub.kt`）按 `cid` 归档会话与消息，是 UI 的唯一数据源。`chat.delta` 的文本增量按 **120ms 合帧**发布（否则每 token 都触发重组），`chat.start/end/日志/心跳` 立即发布，所以「结束」永远不会被节流吞掉。`Edge`：会话上限 40、每会话消息上限 60、日志 800。
- **玻璃（liquid glass）实现与两次返工（都记下来避免再犯）**：
  1. **第一版**：只做半透明（白色 alpha 0.08）—— 用户指出「应该做成磨砂玻璃」。
  2. **第二版**：把 `Modifier.blur` 加在「面板背后的内容」上 —— 用户指出
     「你怎么把整个页面都磨砂了，只要弹出的选项框是磨砂」。**根因**：
     `Modifier.blur` 只模糊**它作用的那一层自己画的像素**，不会采样背后，
     所以糊内容 ≠ 面板磨砂。
  3. **第三版**：把 blur 加在**面板自己**上 —— 用户指出「模糊的位置多了、
     看不见内容了、层级没划分好」，并且 **dock 从底部跑到了屏幕中间**。
     **根因**：① 离屏模糊层会**向外溢出**（density 4.0 下 8dp 就溢出约 80px），
     在近黑背景上表现为「面板外面一圈灰雾 + 漏边角」；② 它同时把**面板自己的
     内容**压成一团灰（真机截图 `dev/ui_rail_ok.png` 里会话名基本不可读）；
     ③ 给 dock 加这个模糊会**推歪布局**（离屏层扩大绘制边界，而 dock 处在
     `align(BottomCenter)` + 外层 padding 的 Box 里）。
  4. **最终方案（已上机验证）**：**面板不用 `Modifier.blur`**，改成
     「高不透明度深色底（`GlassStyle.panelAlpha = 0.92`）+ 上亮下暗渐变 +
     斜向反光带 + AGSL 内阴影（API 33+，低版本退化成两层描边）+ 1dp 亮边」，
     即 `GlassBox(frosted = true)` → `Modifier.frostedGlassPanel(...)`。
     清晰、可读、不吃离屏渲染、不动布局。`Modifier.frostedBackdrop` 保留但
     **当前无人调用**，函数注释里写清了上面四条坑。
  5. 另删掉了**全屏遮罩（scrim）**：用户指出「有一层遮罩在上面」。它把整页压暗
     且在这个近黑背景上看不出提示作用，改用「点右侧聊天区收起」替代。
  6. `GlassShader.kt` 里按 Kyant0/AndroidLiquidGlass 算法移植的圆角矩形 SDF 折射
     shader（含 7 抽样色散）**保留为入口**：要真正实现「背后内容折射」需要自建
     backdrop 捕获管线，本次没做，已如实记在第五节。
  7. **标题居中返工**：用户报「打开会话的栏时，标题旁边的三个横杆会显示导致标题移动」。
     **根因**：旧标题栏是 `Row` + `Column(weight(1f))`，汉堡按钮的
     `AnimatedVisibility` 一出现/消失就改变可用宽度，标题被推着跳。
     **修法**：改成 `Box` + 三段定位 —— 左/右两个**定宽槽**（左侧 48dp 汉堡、
     右侧 72dp「最新」槽，无内容时也占位），中间标题 `fillMaxWidth` +
     `Alignment.Center` + `textAlign = Center`；汉堡改用 **alpha 隐去**而不是移除，
     宽度恒定。真机像素实测（`dev/ui_title_closed.png` 与 `dev/ui_title_open.png`
     两张截图里标题文字带完全一致）：`title=[538..887] center=712.5`，
     与屏幕中心 720 差 **-7.5px = 1.9dp**，**开/关两态完全同一位置、零位移**。
     这个 1.9dp 偏移是刻意接受的小妥协（右侧两个控件比左侧一个宽），
     优先级给了「位置固定不跳动」。
- **真机证据**（同一台 PJZ110，`aapt2`+截图原文见第三节 58~67 行）：
  dock 三格在底部正常、聊天页标题与消息气泡正常、会话框玻璃面板里的内容
  清晰可读（会话名 `模拟sim-b` / `模拟sim-a` + 摘要 + 答复数角标）、
  日志页四条运行状态 + 42 条日志正常、设置页四项配置正常、
  标题开/关两态零位移（上面的像素实测）。
  `python -m unittest discover -s pc/tests` → `Ran 72 tests ... OK`（`pc/` 未改）。
- **用户可见后果**：APK 体积 **1722540 字节**（旧版 572639；中间几版只差几个字节，
  最终产物以 §一 的校验值为准），因为 Compose +
  Material3 全量在包里（第 40、41 条又在同一版里追加了约 3 KB，第 45、46 条再 +3 KB）；R8 会打印若干条
  `R8: An error occurred when parsing kotlin metadata … newer version of kotlin`
  的 WARNING —— 这是 AGP 8.9.3 自带的 R8 比 Kotlin 2.2.10 旧导致的
  **元数据解析告警**，不影响产物（构建成功、装机运行正常），但**属于已知瑕疵**。

### 39. 模拟通道文件残留 → 幽灵会话占主岛（真机复测发现，已修）
- **现象**：界面重做后复测端到端，真实豆包会话的帧是 `prio=default`。
  只有一个真实会话时本应是 `prio=high`（第 32 条的规矩：主岛归先到者）。
- **根因**：子代理测多会话时留下的 `filesDir/ib_sim.txt`（775 字节，`sim-a`/`sim-b`
  两条合成会话）还在 App 私有目录里。模拟通道是**每次启动整读该文件**注入事件，
  而去重水位 `n` 只存在内存里 —— App 一重启就把两条幽灵会话重新灌进来，它们
  **先到**、拿到 `HIGH`，真实会话只能落副岛。
- **处理**：删掉 `filesDir/ib_sim.txt` + 重启 App，复测：
  ```
  修复前  id=reply:716712230022402 prio=default …
  修复后  id=reply:716712230022402 prio=high …   未收掉的会话只剩它一个
  ```
- **性质与残留风险**：模拟通道是诊断通道，`ib_sim.txt` 只有 root/adb 能写，普通
  用户碰不到 —— 属「测试残留」而非产品缺陷；但**它的重放语义是个坑**（水位不落盘）。
  ~~后续若还用这条通道，建议改成「处理完即清空文件」或把水位持久化。本次按
  「文档如实记录 + 清掉现场」处理，未为此改代码。~~
  → **已修（第 41 条）**：改成**处理完即清空**（只留最后那一段没有换行的残片），
  并在真机上复测（`模拟通道已投递 N 条并清空文件（防重启重放，第 39 条）`）；
  顺带修掉了 `su` 写的文件属主是 root、App 写不了导致每秒一次
  `FileNotFoundException` 的问题（脚本 `chmod 666` + App 启动时自愈 + 失败只记一次）。

### 40. 岛上直接回复：用官方回调做「展开才给回复框」（用户要求的核心功能）

- **背景**：`GenericCard` 常驻主岛但**没有回复输入条**；`MessageCard` 有输入条
  却按星河岛规则「按新消息排位、停留一段时间让位副岛」（第 9 条）。用户既要
  「常驻」又要「在岛上直接回」，之前的做法是设置页一个开关二选一（默认关，
  所以岛上一直没有回复框）。
- **官方能力**（`astraflow.cc/island/develop-api.html`，本版按官网事实实现）：
  · 只有 `MessageCard` 有回复框：`MessageCard.Builder(sender, text)` +
    `setReplyEnabled(true)`，锁屏不显示输入框；
  · `IslandCallback.onReply(activityId, text)` —— **宿主只把用户输入的文字交出来，
    实际发送由应用负责**，发完可以用同一个 id 更新卡片；
  · `IslandCallback.onExpanded(activityId)` / `onCollapsed(activityId)`；
  · `start(activity)` 对同一 `id` 是**整份替换**（所以能在同一个 id 上换模板）；
  · 消息卡片**不受 `setPriority` 管辖**（只有 `GenericCard` 受）。
- **修法（两全）**：
  1. 平时仍是 `GenericCard`（保住常驻与「主岛归先到者」第 32 条的已验收行为）；
  2. 收到 `onExpanded(reply:<cid>)` → **用同一个 id** 重投一张 `MessageCard`：
     头像=`doubaoIcon`、`sender`=`Conv.title`（没有就用 `srcWord(c)`）、
     `text`=当前正文（markdown 已清）、`setReplyEnabled(true)`；发送按钮是
     **宿主自带**的（开了回复框，输入框旁边就有），不需要自己画。按钮只保留
     纯文字两档（`我知道了/关闭` + `删除会话`）—— 第一版曾给消息卡配
     `BarIconButton` 的勾/叉小图标，用户反馈「只需要一个回复框 + 旁边一个
     发送按钮」，那些小图标已撤掉；
  3. 收到 `onCollapsed` → 同一 id 切回 `GenericCard`；
  4. `onReply(id, text)` → 反解 cid → 走原有 `sendReplyToConv()` 真正发回豆包
     （模块内 `sendMessageV2` 通道），并把这张卡的**副标题/状态位**改成
     「已交给豆包发送」（**标题一个字都不动**）；日志只打 cid 后 6 位与字数；
  5. `PREF_REPLY_BAR` 保留（打开=永远 MessageCard，用于对比），**默认改为新行为**。
- **顺带补齐**：`QUOTA_EXCEEDED`（同时 3 条上限）与 `RATE_LIMITED`（每秒 >10 次）
  不再静默，各给一行明确日志（含 `liveIds` / `pending` 计数）；`SOURCE_DISABLED` 同理。
- **模拟通道扩展**：`sim.expand` / `sim.collapse` / `sim.reply`（带 `cid` / `text`），
  走**和宿主回调完全相同**的函数（`BridgeApp.noteIslandExpanded/Collapsed/Reply`
  → `IslandBridge.onExpanded/onCollapsed/onReplyText`），所以没有真手指也能把这条
  链路跑完并留下日志。`tools/sim_multi.py` 新增 `expand / collapse / reply / replytest`。
- **真机实测（模拟通道，日志原文）**：
  ```
  模拟通道已投递 6 条并清空文件（防重启重放，第 39 条）
  模拟通道: sim.expand cid=sim-a len=0
  模拟通道展开 id=reply:sim-a（与宿主 onExpanded 同一个函数）
  岛展开 id=reply:sim-a cid=sim-a → 同 id 换 MessageCard（带回复输入条；锁屏不显示输入框）
  岛card id=reply:sim-a prio=high cid=sim-a src=手机 st=回答结束 body=14字 left=3字 right=4字 srcs=手机 card=msg title='安卓包-模拟sim-a'
  卡片形态 = msg
  模拟通道: sim.reply cid=sim-a 字数=8
  模拟通道回复 id=reply:sim-a 8字（与宿主 onReply 同一个函数）
  岛上回复 id=reply:sim-a cid=sim-a 8字 → 交本应用发送（官方语义：宿主只交出文字）
  岛card id=reply:sim-a prio=high cid=sim-a src=手机 st=已交给豆包发送 body=14字 left=3字 right=7字 srcs=手机 card=msg reply='已交给豆包发送' title='安卓包-模拟sim-a'
  回复[手机-模拟sim-a] id=reply:sim-a cid=sim-a: 8字
  已递交豆包进程发送（cid=sim-a），等待回执…
  模拟通道: sim.collapse cid=sim-a len=0
  模拟通道收起 id=reply:sim-a（与宿主 onCollapsed 同一个函数）
  岛收起 id=reply:sim-a cid=sim-a → 同 id 切回 GenericCard（保住常驻/主岛归属）
  岛card id=reply:sim-a prio=default cid=sim-a src=手机 st=已交给豆包发送 body=7字 left=3字 right=7字 srcs=手机 card=generic reply='已交给豆包发送' title='安卓包-模拟sim-a'
  ```
- **真机实测（真实豆包会话 `cid=716712230022402`，日志原文）**：
  ```
  模拟通道展开 id=reply:716712230022402（与宿主 onExpanded 同一个函数）
  岛展开 id=reply:716712230022402 cid=022402 → 同 id 换 MessageCard（带回复输入条；锁屏不显示输入框）
  岛card id=reply:716712230022402 prio=high cid=022402 src=手机 st=回答结束 … card=msg title='安卓包-豆包'
  卡片形态 = msg
  岛上回复 id=reply:716712230022402 cid=022402 9字 → 交本应用发送（官方语义：宿主只交出文字）
  岛card id=reply:716712230022402 … st=已交给豆包发送 … card=msg reply='已交给豆包发送' title='安卓包-豆包'
  回复[手机-豆包] id=reply:716712230022402 cid=022402: 9字
  已递交豆包进程发送（cid=022402），等待回执…
  已发送到手机豆包
  ```
  豆包侧真的收到了这句并在岛上回了话（同一张卡继续刷新）：
  ```
  chat.delta … text="明白，请把需要最终确认的内容发给我，我帮你复核一遍最终产物。"
  ```
- **同屏其它会话不受影响**：上面那条回复期间，另一条会话（`sim-a`）的卡片
  仍在自己的 id 上刷新，`主岛`归属没有变化 —— 与第 32 条一致。
- **未验证/不确定**（如实记在第四节）：真手指点岛上的回复框、锁屏不显示输入框、
  宿主对「同一 id 换模板」的实际排位处理，都还需要人眼看一次。

### 41. 日志与聊天记录固化到磁盘（各 100 MB 上限，超出删最旧的）

- **背景**：第 38 条的 `BridgeHub` 把会话/消息/日志都放在内存（R9），App 进程
  一被杀，聊天页历史清空、日志也只能回 logcat 翻。
- **修法一：日志固化**（新增 `LogStore.kt`，`filesDir/logs/`）
  · 与 logcat、界面同一份 `BridgeApp.logLine` 输出**同时**落盘，一行一条
    `时间\t级别\t正文`（制表符分隔、换行压平）；
  · 分片：每片 ~6 MB（文件名带时间，字典序即写入序），每写一条检查一次，
    超了就换新片 + 裁总量 → 总量上界 = 100 MB + 一片（≤ 106 MB）；
  · **上限 100 MB**：超过就**从最旧的片开始整片删**，并留一行说明
    （`日志已按上限裁掉最旧的 N 片（… KB），当前 … / 上限 100 MB`）；
  · 写盘在单线程 `log-store`（daemon）上排队，主线程只 submit，队列满就丢日志
    不阻塞；任何 IO 异常**只降级一次**（置 `degraded` + 一行 logcat），功能不受影响；
  · 「日志」页改成**读磁盘**（从最新的一片往旧读，最多 800 条，级别筛选保留），
    并显示 `已用 12.3 MB / 上限 100 MB · N 片`；设置页「调试」区上方新增
    「固化与清理」卡片：日志/聊天记录占用 + 两个「清空」按钮。
  · **诚实边界**：LSPosed 模块住在豆包进程里，它没有本 App 的写入上下文，
    **模块日志仍然只在 logcat**（`adb logcat -s IslandBridge`）；本版固化的是
    App 侧（岛桥自己）的日志。
- **修法二：聊天记录固化**（新增 `ChatStore.kt`，`filesDir/chat/<cid>.jsonl`）
  · 一行一条 JSON：`{"mid","role","text","think","ended","at"}` —— 只存用户
    在聊天页能看到的东西，**没有额外标记**；`JSONObject` 转义（引号/反斜杠/
    控制符/换行），正文里塞「换行 + 一行假 JSON」也不会破坏后面的记录；
  · `BridgeHub` 启动时从磁盘恢复（最近 40 份文件 / 每份最多 400 行 /
    最多 6 MB，读进内存模型），`chat.delta` 按 **1s/条消息**节流镜像、
    `chat.end` 强制落一次（进程被杀也不会丢掉半截回答）；
  · **全局上限 100 MB**：超过就**删最旧的会话整份**（排序键 = 文件最后修改时间），
    并留一行说明（`聊天记录已按上限裁掉最旧的 N 条（… KB，当前 … / 上限 …）`）；
    单会话内部超过 400 行先按行裁（只留最新的）；粒度选「整份会话」而不是
    「截半截对话」，因为用户看到只有后半段的对话比看不到更困惑；
  · 用户从 App / 岛上回过去的那句也记一条 `role=user`（界面上能看到的，
    磁盘上要有同一份），并且**同时进内存模型**，发完立刻就显示；
  · 「关于」里那条「聊天记录只在内存」的说明、
    `README.md` 的同一句都改成事实。
- **上限可临时调小用于验证**（真机不可能为验证裁剪去写满 100 MB）：
  `filesDir/ib_limits.txt` 写 `log=…` / `chat=…`（字节）即生效，删掉回 100 MB。
  新增 `tools/sim_limits.py` 把上限压到 8 KB / 6 KB 跑了一次真实裁剪，日志原文：
  ```
  聊天记录已按上限裁掉最旧的 1 条（2 KB，当前 4 KB / 上限 6 KB）      ← 连删 6 次
  日志已按上限裁掉最旧的 1 片（8 KB），当前 0 KB / 上限 8 KB
  （磁盘现状）logs/2026-10-05_14:51:43_832.log 7210B；chat/ 只剩 sim-04.jsonl、sim-05.jsonl
  ```
  验证后已删除 `ib_limits.txt`，冷启动日志复读为 **`上限: 日志 100 MB（单片 6 MB）
  · 聊天记录 100 MB（每会话最多 400 行）`**。
- **R9 修复实测（真实豆包会话，冷启动）**：
  ```
  启动 v1.2(3)
  已从磁盘恢复 1 条会话的聊天记录（共 3 条消息）
  固化: 日志目录=/data/user/0/com.tg.dbisland/files/logs（1 片 / 4215B）· 聊天记录=1 个会话文件 / 373B
  上限: 日志 100 MB（单片 6 MB）· 聊天记录 100 MB（每会话最多 400 行）—— 超额都从最旧的删起
  ```
  `cat files/chat/716712230022402.jsonl`：
  ```
  {"role":"bot","at":…,"ended":true,"mid":"57580010822186242","text":"岛上回复链路验证已收到，链路运行正常。"}
  {"role":"user","at":…,"ended":true,"mid":"u…","text":"请再确认一次：收到我的回复了吗"}
  {"role":"bot","at":…,"ended":true,"mid":"57582424819279874","text":"收到了你的消息。"}
  ```
  （进程重启后这两问两答都在 —— 用户报的「进程被杀即丢」就是这个。）
- **顺带修掉的两个真机坑**：
  1. `BridgeHub.publishLog` 与 `BridgeApp.logLine` 都往磁盘写 → **日志每行两份**，
     真机复测发现后改成只有一条路（`logLine`），复测磁盘日志每行一份；
  2. 模拟文件 `ib_sim.txt` 是 `su` 写的（属主 root / 644）→ App 能读不能写，
     「处理完即清空」每秒抛一次 `FileNotFoundException`；脚本改 `chmod 666`，
     App 启动时用一次 `su chmod` 自愈，写不了就只记一次日志不再刷屏。
- **占用实测（100 MB 上限下）**：`files/logs` 24.7 KB（1 片）、`files/chat` 433 B
  （1 个会话文件）—— 上限是上界，不是预留。

### 42. 消息卡上只留回复框：去掉所有自带按钮
- **用户反馈原文**：「出现了打勾打叉的东西，只需要适配一个回复框在岛上然后岛旁边带一个
  发送按钮就好了」。
- **根因**：`buttons(ended, msg=true)` 仍然返回 `[我知道了/删除会话]` → `[我知道了]` →
  空 三档，`buildReplyCard` 的消息卡分支又无条件 `bs.forEach { b.addButton(it) }`。
  这两个文字按钮在消息卡上被宿主画成了**勾/叉小图标**，就是用户看到的噪音。
- **改法**：`buttons()` 在 `msg=true` 时**直接返回 `listOf(emptyList())`** —— 消息卡
  一个按钮都不给。发送按钮是宿主自带的（`setReplyEnabled(true)` 之后输入框旁边就有），
  不需要也无法自己画。收掉卡片的路径不变：宿主划走（`onDismissed`），或收起后回到
  通用卡上的「我知道了」。
- **实测**（模拟通道走与宿主回调**同一个函数**，日志见 `dev/sim_final_reply2.txt`）：
  ```
  卡片形态 = generic → msg → generic
  岛展开 id=reply:716712230022402 → 同 id 换 MessageCard（带回复输入条；锁屏不显示输入框）
  岛上回复 id=reply:716712230022402 12字 → 交本应用发送（官方语义：宿主只交出文字）
  回复[手机-豆包] id=reply:716712230022402: 12字
  已发送到手机豆包
  岛card … st=已交给豆包发送 … card=msg … title='安卓包-豆包'
  按钮被拒/异常：(无)
  ```
  闭环证据：回复后豆包的聊天记录里出现「谢谢，举个例子说明一下。」并跟着它自己的长回答
  （含 JS 示例）——岛上回复确实发回豆包并触发新答复。
- **仍未人工确认**：真手指点开岛卡是否出现回复框与发送按钮、锁屏时是否不显示输入框
  （官方规范如此，需要用户的屏幕确认）。

### 43. 岛卡形态固定为一种：回复框全程在位
- **用户真机反馈原文**：「正在输出的时候出现了回复框但是没有看见我知道了还有删除的按钮，
  回复结束之后回复框不见了然后出现了按钮，但是好像还出现了两行一样的内容显示了然后
  突然收缩变成一行的」。
- **根因**：第 40 条的「展开才换 `MessageCard`」在同一个 id 上来回换模板 ——
  推流中（展开态）是消息卡（有回复框、按用户第 42 条要求没有按钮），回答结束后
  `setReplyEnabled(!ended)` 关掉回复框、形态又切回 `GenericCard`（于是冒出「我知道了/删除会话」），
  且消息卡正文两行、通用卡正文一行，切换瞬间看起来就是「两行重复内容突然收缩成一行」。
- **改法**：
  1. `msgMode()` 由「展开才用消息卡」改为**全程只用 `MessageCard`**（`= !useMessageCard(ctx)`
     —— 设置里那个开关的语义随之反转为「常驻优先（通用卡）」）；
  2. `setReplyEnabled(!ended)` → **`setReplyEnabled(true)`**：回答结束后回复框**不再消失**
     （实测对已结束的回答回复同样有效，会触发豆包新答复）；
  3. 消息卡按第 42 条**不给任何按钮**。
- **实测**（真实豆包会话，22 秒推流，`logcat` 全量统计）：
  ```
  卡片形态统计: {'msg': 9}          ← 全程只有一种形态，没有任何切换
  推流中 岛card … st=回答进行 … card=msg title='安卓包-豆包'
  结束后 岛card … st=回答结束 … card=msg title='安卓包-豆包'
  形态切换日志: (无切换)
  异常/被拒: (无)
  ```
- **仍未人工确认**：用户手指点开岛卡看到回复框与发送按钮的实际观感；
  以及消息卡按星河岛规则「停留一段时间后可能让位副岛」在真机上的具体时长。

### 44. 岛卡方案定稿（用户选定 A）：常驻优先 + 展开才有回复框
- **背景**：第 43 条把形态改成"全程 MessageCard"后，用户真机反馈：「发问题然后离开豆包，
  但是没有内容，再次进入豆包又有了」——离开豆包后我们应用在后台，岛上却**胶囊与卡片全无**
  （用户确认：停在其他应用/桌面时岛是空的）。这违反「来源应用在前台才隐藏」的规则，
  属真问题。
- **根因**（官方规则）：消息卡**不使用 `Priority`**，「第三方应用发送的消息按新消息处理：
  到达时先显示为主岛，经过『消息主岛停留时长』后让出主岛」——让出后在这台机器上表现为
  **整块消失**；而"再进豆包就有"是因为模块/应用在那一刻重投，被当成新消息。
  实测日志佐证：三种前台状态下我们这侧都在投帧（19/9/9 帧、`prio=high` 未断），**停投的不是我们**。
- **用户选择**：A 方案（我这侧给出的二选一，另一选项是"回复框一直在但接受让位/消失"）。
- **改法**：
  1. `msgMode()` 回退为 `c.expanded || useMessageCard(ctx)` —— 平时 `GenericCard`
     （受 `Priority` 管辖，常驻主岛不消失），**展开时**同一 id 换 `MessageCard`（回复框 + 发送按钮）；
  2. 回复框在展开期间**全程保留**（`setReplyEnabled(true)`，回答结束也能回）；
  3. **消除此前"两行内容突然收缩成一行"的观感**：消息卡正文改用与通用卡**同一份一行文本**
     （原来走 `msgBodyText()`，两行且与状态文案重复）；
  4. 设置页开关改回「回复框（展开时的消息卡）」：关=默认（常驻通用卡，展开才有回复框），
     开=永远消息卡（对比用）。
- **实测**：收起态 `card=generic`、展开后出现 `card=msg`、回复链路 `已发送到手机豆包`、
  无按钮、无异常；形态只在「展开/收起」时切换，不再随回答结束切换。
- **仍未人工确认**：真手指展开卡片看到回复框与发送按钮的观感；以及平时通用卡在真机上
  是否稳定常驻（这正是本次要恢复的行为）。

### 45. 岛上三按钮 + 悬浮窗回复面板（无人值守全链路真机复测）

- **用户要求**：岛上卡片要给「回复 / 知道了 / 删除」三个按钮；点「回复」不再去宿主
  的回复框，而是**弹本应用自己的悬浮窗面板**（可打字、有发送）；面板出现时**岛要缩回**，
  关掉面板**岛要补回**；面板要**避让键盘**、**30s 无操作自动关**。验证要在**没有真手指
  点岛**的情况下也能跑完（模拟通道 `sim.overlay`）。
- **按钮档位**（`buttons(ended, msg)`，被宿主拒绝时逐档降级）：
  `[回复,知道了,删除] → [知道了,删除] → [知道了] → []`，共 4 档。
  真机接受的那档原话（**验证 E 要求的原样一行**）：
  ```
  岛按钮组合被接受: [回复,知道了,删除]（第 1/4 档、通用卡）
  ```
- **悬浮窗回复链路**（`sim.overlay` 走的是和手指点岛上「回复」**完全同一个**
  `onAction("reply:<cid>", ACT_REPLY)`）：真机原话（节选，`cid` 后 6 位是日志里的
  显示形式 `022402`，**完整 cid 是 `716712230022402`**）：
  ```
  模拟通道悬浮窗回复 id=reply:716712230022402（与岛上「回复」按钮同一个 onAction）
  岛动作:回复 id=reply:716712230022402 cid=022402 → 弹**本应用自己定制的悬浮窗**回复面板
  悬浮窗回复：面板已弹出 cid=022402（TYPE_APPLICATION_OVERLAY，可聚焦、不在锁屏弹、30s 无操作自动关）
  悬浮窗出现 → 岛缩回：end(reply:716712230022402) cid=022402（官方无收起接口，只能 end；面板关掉后再 start 补回）
  悬浮窗回复：键盘高度 656px → 面板贴在输入法上沿
  悬浮窗回复：面板已关闭（30s 无操作）
  悬浮窗关闭 → 岛补回：start(reply:716712230022402) cid=022402 （第 45 条：面板开着期间不投岛）
  回复[手机-豆包] id=reply:716712230022402 cid=022402: 5字
  已递交豆包进程发送（cid=022402），等待回执…
  已发送到手机豆包
  ```
  之后豆包继续作答，同一张卡从 `st=回答进行` 走到 `st=回答结束`；磁盘侧
  `/data/data/com.tg.dbisland/files/chat/716712230022402.jsonl` 尾部能看到**自己发出去的那句
  和新回答各一条**：
  ```json
  {"role":"user","at":1791195398238,"ended":true,"mid":"u1791195398238","text":"hello"}
  {"role":"bot","at":1791195401070,"ended":true,"mid":"57594429185406978","text":"哈喽～👋\n我在呢！继续写作文还是闲聊？"}
  ```
- **本轮新修（真机复测才暴露的两个坑）**：
  1. **上行记录只有两条路在记，悬浮窗那条一条都没记**。`noteUserSend()` 原来分别写在
     聊天页输入框和岛上原生回复框里，`openReplyOverlay → sendReply → sendReplyToConv`
     这条**完全没记**（磁盘上查不到自己刚发出去的那句，违反第 41 条「磁盘与界面一致」）。
     改法：把记录**下沉到唯一漏斗 `sendReplyToConv()`**，三处调用点（聊天页 / 岛上回复框 /
     悬浮窗面板）共用一处，既不漏也不重；预览卡 `cid="-"` 由 `mirrorUser` 自己跳过。
  2. **`sim.overlay` 必须用完整 cid**。日志一贯只打 `tail6(cid)`（`022402`），
     拿日志里那 6 位去投 `sim.overlay` 会得到
     `岛动作:回复（未知内容 id=reply:022402）` —— 这正是上一轮「投了但什么都没发生」的原因。
- **无人值守脚本化的两个前提**（否则 `adb shell input text` 会静默失败，值得记下来）：
  - **输入法必须先关**：微信/搜狗输入法会把注入的硬件按键当**拼音合成**吃掉，实测
    `input text hello` 进到输入框变成 `o'he'l'l`（只落下 `ACTION_UP`、`ACTION_DOWN` 被输入法吞掉）；
    `ime disable` 到 `enabled_input_methods=null` 之后，`input text hello` 干净落成 **5 字**
    （日志 `…: 5字`）。跑完已把 4 个输入法按原顺序恢复（`enabled_input_methods` 与
    `default_input_method` 都逐字比对回原值）。
  - **悬浮窗会随键盘上移**：面板贴键盘上沿，键盘弹出时「发送」按钮跟着上移，脚本按
    固定坐标点会点空（表现为面板一直开着）；先 `input keyevent 111` 收起键盘、面板落回
    底部再点，稳定命中。
- **仍未人工确认**：真手指点岛上「回复」看到面板的手感；以及键盘弹出时面板与键盘的
  贴合观感（脚本验证只能证明避让逻辑在跑，证明不了好看）。

### 46. 设置页重新分组 + 主题进设置 + 浅色底部黑带 + Documents 日志镜像编号副本

- **一、设置页重新排布**（用户原话：「设置页的内容有些乱，安排一下」）。原来六块内容
  混在一起、`岛显示` 里塞着后台冻结、还有「常驻优先（通用卡）」这种和实际行为不符的旧文案。
  现在按**一眼能找到**的顺序排成六组，每组一个标题 + 一句时效提示，组内行样式统一：
  1. **外观**（`改了立刻生效，不需要重启`）—— 主题三选一；
  2. **岛显示**（`改完最多 3 秒生效`）—— 正文一行字数 / 胶囊分组长 / 在岛上预览样例文字 /
     岛上原生回复框（消息卡形态）；
  3. **运行环境** —— root / LSPosed / 模块注入 / 豆包进程 / root 管理器 / 自启动跳转与检测 /
     悬浮窗权限；
  4. **固化与清理** —— 日志与聊天记录占用（含条数）、清空、`Documents` 镜像与导出；
  5. **调试**；6. **关于**。
  纯信息架构与文案调整：**没有改任何行为、没有动任何 pref key**（`island_reply_bar` 原样保留，
  文案改回与行为一致的「关 = 固定通用卡」，之前那句「常驻优先（通用卡）」已删）。
  顺手修掉两处换行孤字：「卡片正文一行放几个字；放不下就调小」→「每行放几个字，放不下就调小」，
  「在岛上预览一段样例（看这两个数字合不合适）」→「在岛上预览样例文字」（都在一行内）。
- **二、黑白主题进设置**（用户原话：「黑白主题的按钮也要在设置里」）。`ui/Theme.kt` 新增
  `ThemeMode{SYSTEM,LIGHT,DARK}`，存 `SharedPreferences("bridge")` 的 `theme_mode`
  （`system|light|dark`）。**立刻全应用生效、不需要重启**：颜色全部是读
  `mutableStateOf` 的 `get()` 属性，改完所有调用点自动重组。真机日志：
  ```
  外观：主题模式 → 深色（生效=深色，系统=浅色）
  外观：主题模式 → 浅色（生效=浅色，系统=浅色）
  外观：主题模式 → 跟随系统（生效=浅色，系统=浅色）
  ```
  **下次启动保持**：`am force-stop` 后冷启动，`bridge.xml` 仍是 `theme_mode=dark`、
  界面仍是深色（当时系统是浅色，证明用的是固定值而不是系统值）。系统栏图标（时间/信号/返回）
  也随主题翻转（`isAppearanceLightStatusBars/NavigationBars`）。
- **三、浅色主题底部那条黑带（用户原话：「白主题底部还有黑色的背景不合适」）**。
  **根因不在 dock 也不在 `navigationBarColor`，而在 `ui/AppShell.kt` 页面渐变的第三个色标
  写死成了常量 `Color(0xFF080A0E)`** —— 浅色下页面从白渐变到近黑，底部（dock 底衬 +
  导航栏区域）就被染黑了。改成 `AppColor.bgBottom`（浅色 `0xFFE7EBF5`）。同时把散落的
  硬编码白/黑换成主题色 `AppColor.fill`：dock 选中高亮、`GlassSurface` 内阴影透明度、
  会话行、输入条、发送键、日志页筛选片与日志行、聊天气泡、`ReplyPanel` 取色
  （原来直接读 `Configuration.uiMode`，非 Compose 窗口也要跟着主题走）。
  对比截图（**含底部区域**）：`dev/theme_light_bottom.png`（浅色，底部无黑带）与
  `dev/theme_dark_bottom.png`（深色，整页到底都是深色）；设置页另有
  `dev/theme_light_settings.png` / `dev/theme_dark_settings.png`，修复前的问题现象留在
  `dev/before_light_home.png`。
- **四、`Documents` 日志镜像生成 `(1)(2)(3)` 编号副本**（用户原话：`…774.log (1).txt`
  `(2)` `(3)`）。三个原因叠加：① 私有分片名里带 `:`（`yyyy-MM-dd_HH:mm:ss`），
  MediaStore 在 `Documents` 下会把 `:` 换成 `_`，于是按原名精确查（`DISPLAY_NAME=?`）
  **永远查不到**，每次刷镜像都走 `create()`；② MediaProvider 对重名自动去重成 ` (N)`；
  ③ 它还按 MIME 追加了扩展名（`.log` → `.log.txt`）。改法：分片格式改成不含 `:`
  的 `yyyy-MM-dd_HH_mm_ss`；`DocStore.find()` 改成「归一化后精确匹配，否则归一化前缀匹配，
  取 `_id` 最小那条」，能容忍 `:`→`_` 与追加的 `.txt`；启动时 `repairDocMirror()`
  先 `dedupe()` 再按当前私有分片**同名覆盖**重写一遍。
  **清理结果（真机原话）**：
  ```
  清理了 6 个重复镜像（Documents/豆包岛桥/日志/ 只保留 1 份：2026-10-05_17_35_25_774.log.txt；编号副本已被同名覆盖取代）
  文档镜像已对齐：/sdcard/Documents/豆包岛桥/日志/2026-10-05_17_35_25_774.log（17 KB，与私有分片 2026-10-05_17_35_25_774.log 内容相同；同名覆盖，不再生成 (1)(2) 编号副本）
  ```
  复核：`ls -1 /sdcard/Documents/豆包岛桥/日志/` 只剩 **1 个文件**（装完新版、重启 App、
  再跑几条日志之后仍然是 1 个，没有再长出编号副本）。导出目录（`…/导出/`）查了同样的问题：
  两个文件 `豆包岛桥日志_20261005_172406.txt` / `_173456.txt` **名字本就不同**、没有编号，
  导出走的是「一次导出一个新时间戳名」，与镜像不是同一条路，**无需改**。

### 47. 中途再发一条时「第二次的回答只显示头部、不再流式显示下文」——mid 漂移把一条回答拆轮 / 接错消息

- **用户原话**（交付后报的第二个真机 bug）：「会话中，中途向豆包发送内容，第一次的内容正常
  显示，发送之后第二次的内容只显示头部的一些输出然后不会流式显示下文」。
- **复现**（真机 d666858b，`dev/repro3.py interrupt`；修前报告 `dev/rep_interrupt2.log`，修后
  `dev/rep_interrupt_fix3.log`）：
  1. `am force-stop com.larus.nova` → `am start-service …/Message.NotifyService` → 分享直投
     （`OuterShareDeliverActivity`，`android.intent.extra.TEXT` 只能走这条路，`input text` 打不出中文）
     发一篇 400 字秋天散文；
  2. 等第一轮**还在推流时**（日志里已经有 9~12 条 `chat.delta`）再从岛上回复链路投出
     「再写一篇约400字的关于冬天的散文，要分段落。」；
  3. 收 `adb logcat -v epoch`（模块侧 tag `LSPosedFramework` + App 侧 tag `IslandBridge`）与
     `/data/data/com.tg.dbisland/files/chat/<cid>.jsonl`。
  **这是稳定复现路径**：正常「第一轮答完再发第二轮」**永远复现不出来**，必须是
  **第一轮还在推流时插一条** —— 所以早期几轮 `share_reply` / `two_turn` 全绿也没抓到它。
- **根因 1（模块侧 `xposed/MobileFeedParser.kt`）：`mid` 是一个共享可变字段，一条回答会被拆到
  多个消息号上。** `react()` 只要在**任何**嵌套节点上看到 `message_id / msg_id / mid /
  server_message_id` 就改写它（`walk` 的遍历顺序还不保证），可整轮回答一直拿它当身份。
  用户中途插话时，同一条流里会夹带那条**用户消息**的回声帧（`meta.user_type == 1` →
  `userMids`），`mid` 于是改道：同一轮的增量被拆到两个消息号上；紧接着
  `isUser = mid in userMids` 判真，**后面的增量会被静默丢掉**。真机实锤三处：
  - 用户自己那台机器的落盘记录（`dev/chat_lens.txt`）：`#22 len=159 ended=False
    mid=57618048858522114` 与 `#24 len=524 mid=57619669191831810` —— 后者以前者为
    **严格前缀**，即**一条回答被拆成两条消息，前一条停在头部、`ended` 还是 false**，
    正是用户描述的形状（还有 `#14 len=1`、`#15 len=57` 两条同样卡住头的）；
  - 我这一轮抓到的 omni 通道串台（`dev/lc_int_mod2.txt` + `dev/rl_fix.txt`）：
    **新回答的 38 条增量、共 490 字，`mid` 却仍然是上一条回答的**
    （`omni kind=text mid=57619741664170242 条数=38 文本总长=490`）→ App 把新回答写进
    **上一条**消息，新那条只剩头部、永远不再长；
  - 修前那一轮的模块侧计数（`dev/lc_int_mod.txt`）：`sse/start x2 / sse/delta x74 /
    sse/end x2 / sse/reply x2`，第一轮 `chat.reply` 只有 **49 字**：
    `1791215079.699 chat.start mid=57614463949697282` →
    `1791215081.192 com.tg.dbisland.SEND recv … text=23字`（用户第二条插进来）→
    `1791215081.890 chat.end mid=57614463949697282` + `chat.reply`（49 字）。
- **根因 2（App 侧 `ui/BridgeHub.kt`）：`finishLastMessage` 把「最后一条消息」当成要收尾的回答。**
  `chat.reply` 的全量文本于是落到**用户自己刚发的那句话**上。修前实测
  （`dev/rep_interrupt2.log`）：`role=user mid=u1791215081189 ended=True len=49` 里装的是
  回答的正文，而回答自己那条是 `role=bot mid=57614463949697282 ended=False len=1`。
  用户会话里同样的形状出现过不止一次（`dev/chat_lens.txt`：`#16 role=user
  mid=u1791197933217 len=388`，正文是一整篇助手散文）。
- **根因 3（模块侧 `xposed/DoubaoHookEntry.kt` 的去重簿记）：SSE 已经答过的 mid 在 `chat.end`
  那一刻就被从 `sseMids` 里删掉了**，紧接着 omni 把**同一轮回答的全文**再推一遍时已经没东西
  去重，重复内容照进 App（就是根因 1 里那 38 条 490 字）。
- **修法（模块 3 处 + App 3 处，都是最小改动）**：
  1. `MobileFeedParser.startChat()`：开轮时把消息号**钉住**到 `curMid`，这一轮的
     `chat.start / chat.delta / chat.end / chat.reply` 全用它；同一个消息号**不跨轮复用**
     （真复用了就加 `#序号`），保证「一轮回答 = 一个消息号」这个 App 侧的前提成立。
  2. `MobileFeedParser.isUserEcho()`：用户回声只在本轮**开始之前**才用来丢帧 —— 回答一旦
     开始，增量不会再因为 `mid` 漂移被静默丢掉。
  3. `MobileFeedParser.feedOmniMessage()`：`messageId` 一变就先把上一轮**正常收尾**再开新轮，
     「换消息号 = 换一轮回答」。
  4. `DoubaoHookEntry.emitShared()`：`chat.end` 不再删 `sseMids` 里的 mid（只加了 4096 的
     上限），omni 对「SSE 已经答过的消息」一律不重复投。
  5. `BridgeHub.finishMessage()`（原 `finishLastMessage`）：按事件里的 `mid` 收尾，`mid` 对不上
     退到「最后一条**机器人**消息」，**绝不碰用户那条**。
  6. `IslandBridge`：`chat.reply` 的全量文本接入岛做**单向兜底**（只有比岛上已累积的更长才补，
     正常流式两者等长、这一支什么都不做），兜住「增量丢在路上的任何一种情形」。
  7. `IslandBridge.beginConv()`：**新一轮开始时把 `replyState`（「已交给豆包发送」那句反馈）
     清掉**。状态位优先显示发送反馈（用户要求点完回复立刻看到反馈），但它跨轮不清的话，
     用岛回复之后的**第二轮从头到尾都显示「已交给豆包发送」** —— 既看不到「回答进行」，
     答完也看不到「回答结束」。清掉之后顺序才是
     `已交给豆包发送`（发送瞬间）→ `回答进行`（本轮开始）→ `回答结束`（本轮结束）。
- **为什么第 1 轮不受影响**：钉 `curMid` 只是把「本轮用哪个消息号」从「每遇到一个 id 字段就改」
  改成「开轮时定一次」；第 1 轮本来就没有别的消息 id 来插队，行为逐字节不变。
  `isUserEcho` 只在 `started == false` 时生效，用户消息的回声过滤（不回显、不开轮）完全保留。
- **修后实测（同一台机器、同一场景）**：
  - **中途插话**（`dev/rep_interrupt_final.log` + `dev/lc_interrupt_final2.log`）：第 1 轮
    `mid=57614603055702018` 11 条增量、`chat.reply len=51`、落盘 `len=51 ended=True`
    （豆包在收到新消息 2.0s 后就把这一轮收了 —— **这是豆包自己的行为**，真机上第一条回答
    本来就被打断）；第 2 轮 `mid=57618571102626050` 61 条增量 494 字、落盘
    `len=494 ended=True`；用户那句 `mid=u1791216477005 len=23` **不再被回答正文覆盖**
    （修前是 51 字的回答正文写在用户那条上）。岛上状态序列：
    `回答结束 → 回答进行 → 已交给豆包发送 → 回答进行 → 回答结束`；
    兜底那一支也真的跑到了：`回答全文 51字 > 岛上已累积 0字 → 补全（第 47 条兜底）
    id=reply:716712230022402`。
  - **两轮顺发**（`dev/rep_share_reply_final.log` / `dev/lc_share_reply_final.log`，
    App 先重启让状态位复位）：第 1 轮 `mid=57620631939220226` 59 条增量 487 字、落盘
    `len=500 ended=True`（`chat.start 424.241 → chat.end 432.048`）；第 2 轮
    `mid=57616773493205250` 65 条增量 516 字、落盘 `len=516 ended=True`
    （`chat.start 447.665 → chat.end 456.961`）；岛帧计数两轮都在长；岛上状态序列
    `回答进行 → 回答结束 → 已交给豆包发送 → 回答进行 → 回答结束`；用户那句 `len=23` 原样保留。
  - **单轮回归**（`dev/rep_one_turn_final.txt`）：岛上依次出现 `st=回答进行 body=4` →
    `st=回答结束 body=14`；`岛按钮组合被接受: [回复,知道了,删除]（第 1/4 档、通用卡）`、
    `卡片形态 = generic`、`岛状态: READY`；这一轮一条回答一个消息号、落盘完整。
  - 岛上回复链路仍然通：`已发送到手机豆包` + `回复[手机-豆包] id=reply:716712230022402
    cid=022402: 23字`；`python -m unittest discover -s pc/tests` → `Ran 72 tests` / `OK`
    （`pc/` 一行未改）。
- **没动的东西**：markdown 清理、正文一行（`tailOf`）、胶囊左来源右状态、1 分钟未确认通知、
  通知小图标 `android.R.drawable.stat_notify_chat`、豆包图标、多会话 `reply:<cid>` 与主岛归
  先到者、三按钮四档降级与悬浮窗回复、日志/聊天记录 100 MB 上限、主题与设置页结构、
  电脑端（`pc/`）。
- **仍未人工确认 / 方法论交代（如实说明）**：
  1. `mid` 正漂在用户消息号上、恰好有一批增量路过而被丢掉的那**一瞬间**没抓到实锤 ——
     豆包在收到新消息 0.7~2.0s 后就把这一轮收了（`dev/lc_int_mod.txt`）。抓到的是同一个
     成因的前半段（拆轮、omni 挂错 mid）。修法是把这个分支彻底关掉（回答开始后不再看
     `mid`），不依赖复现那一瞬间。
  2. 岛的**卡片形态**仍固定为通用卡（第 43/44/46 条的既有行为），本次只改状态位与正文，
     没有动形态切换逻辑。
  3. `body=N字` 是「岛上正文一行」的滑动窗口（`tailOf`）长度，**不是增长指标**（会稳定在
     14 字）；所以证据给的是**累积帧数**、`chat.reply` 字数与落盘行长度。
     另外 logcat 会把超长行截断（有些 `chat.reply` 取不到结尾），这类行按「增量条数 +
     文本总长」统计，落盘长度以 `files/chat/<cid>.jsonl` 为准。

- **过程事故与教训（如实留痕）**：本轮同步校验值时，代理用 PowerShell `Set-Content -Encoding utf8`
  回写文档，把**无 BOM 的 UTF-8 当成 CP936 解码**，`CHANGELOG.md`（1293 行）、`SUBMIT.md`（210 行）
  当场变成乱码，且仓库无 git 历史。事后用会话转录 + 历史 write/edit 参数逐行重建，行数与原文件一致
  （1549 / 297），乱码原件留在 `dev/_corrupt_backup/`，重建稿在 `dev/CHANGELOG.final.md`、
  `dev/SUBMIT.final.md`。**教训**：改写本仓库任何 UTF-8 文档一律用 `io.open(..., encoding="utf-8")`
  或 `edit` 工具，**禁止**用 PowerShell 的 `Set-Content`/`Out-File` 回写；且重建后必须做结构自检
  （`U+FFFD`=0、无 BOM、`### 1..47` 齐全）——Lead 复核时正是靠这个自检抓到 `SUBMIT.md` 计数漏改一处。
### 48. 岛卡按状态换按钮组（回答中两个 / 回答结束三个）+ 回复只留悬浮窗 + 删除不再「收不掉、会复活、卡在后台」

**用户原话**（三条，按时间）：
1. 「改写规则，回答结束才可以显示回复按钮的模板，正在回答则使用两个按钮的模板，
   app 内取消可以回复的能力，只保留悬浮窗可以回复的能力」；
2. 「出现问题了，我点击删除之后，我们岛还有内容在输出，甚至还有流式显示」；
3. 「在豆包内的时候删除会很顺畅，但是离开豆包后在岛上选择删除会卡一会，进入豆包之后
   才看见被删除了，岛上还出现了结束回复才有的模板」。

#### 一、按钮组改成「按状态」，App 内回复入口移除

- `IslandBridge.buttons(st, msg)`：`st == 回答结束`（`ST_DONE`）→ `[回复,知道了,删除]`；
  其余状态（回答进行 / 已交给豆包发送 / 正在删除…）→ **`[知道了,删除]`，没有回复**。
  **同一张 `GenericCard`、同一个 id**，只在两个状态之间换按钮组，不做 msg↔generic 形态切换。
- 降级链按状态各一条（SDK `build()` 校验不过就是整张卡消失，所以逐档降级）：
  - 回答中：`[知道了,删除] → [知道了] → []`（3 档）
  - 回答结束：`[回复,知道了,删除] → [回复,知道了] → [回复] → []`（4 档）

  实际被接受的档位照第 45 条那行打日志，真机原文：
  `岛按钮组合被接受: [知道了,删除]（第 1/3 档、通用卡）`（流式中）、
  `岛按钮组合被接受: [回复,知道了,删除]（第 1/4 档、通用卡）`（回答结束后）。
- 「交出去」那一瞬间起算「正在回答」：`sendReplyToConv()` 是三条上行路径的唯一漏斗，
  它先置 `replyState = 已交给豆包发送` 再重投一帧 —— 悬浮窗关掉补回岛上的那一帧就是
  **两个**按钮，不会再多出一个「回复」（真机：`st=已交给豆包发送` 之后是
  `[知道了,删除]（第 1/3 档）`）。
- App 内回复入口取消：`ui/ChatPage.kt` 去掉底部回复输入行与发送按钮（记录改成只读可滚），
  只留一行提示「回复请用岛上的悬浮窗（回答结束后点岛卡上的「回复」）」；
  `sendReplyToConv()` **保留**（悬浮窗与模拟通道都走它），只是 App 里没有入口了。
  截图：`dev/item48_chatpage_noinput.png`（`uiautomator` 也确认聊天页 `EditText` 节点数 = 0）。
- 设置页文案同步（不再有「App 内回复」这类说法）；`island_reply_bar` 的语义不变
  （= 永远用消息卡做对照），默认的通用卡路径按本节第一条走。

#### 二、删除之后卡片继续流式（用户报的第 2 条）

- **根因**：`handle()` 的 `chat.delta` 分支顺序反了 —— `if (!c.shown) beginConv(c, cname)`
  写在 `if (c.suppressed) return` **之前**，而 `beginConv()` 会把 `suppressed` 清成 false、
  `shown` 置真。`dismissReply()`（删除 / 我知道了 / 点卡片空白处都走它）刚把卡收掉
  （`shown=false, suppressed=true`），**紧接着的下一帧 delta 就把卡片原地复活**并继续流式。
  回答已经结束时不再有 delta，所以只有「回答还没结束就点删除」才看得见。
  第二个口子是歌词播放器 tick 里那次 push（`chat.end` / `chat.reply` 都会无条件重启它），
  播放器内部**没有**抑制位判断。
- **模块侧原始证据**（`/data/adb/lspd/log/modules_*.log`）：`card action delete sent 0ms`
  → 508ms 后 `IslandBridge delete ok (high) cid=716712230022402`
  → 之后同一条 `mid` 仍送来 ~98 条 `chat.delta`（App 侧照样上岛）。
- **修法**：① `chat.delta` 里把 `suppressed` 判断提到 `beginConv` 兜底**之前**；
  ② `pushReplyItem()` 开头加**唯一漏斗**闸门（抑制位或墓碑 → 直接丢，3s 节流日志
  `卡片已被收掉（suppressed）→ 本条会话不再投岛 id=… cid=…`）；
  ③ 删除回执补一行日志（成功路径原来一条都没有，真机上根本判断不了「删没删掉」）：
  `删除回执：已删除 id=… cid=… → 收卡，本条会话的推流不再上岛（第 48 条）`。
- **修复前后对照**（同一个 harness、同一个真机动作 `sim.action a=delete`，与手指点岛上
  「删除」走同一个 `onAction`）：

  | | 收卡之后 25s 岛上帧数 | 同期模块仍在投的 chat.delta | 抑制位日志 |
  |---|---|---|---|
  | 修复前 | **56 帧**（继续流式） | 98 条 | 无（该日志第 48 条才加） |
  | 修复后 | **0 帧** | 109 条（全被丢掉） | 12 行 |

#### 三、豆包在后台时删除「卡一会」、还出现「结束回复才有的模板」（用户报的第 3 条）

- **根因**：DELETE 也是一条**广播**，必须由豆包进程里的模块收到才执行；豆包退到后台被
  ColorOS 冻结/回收后，广播要等它解冻才送达。App 侧原来既**看不出在等**（状态位不变），
  也没有任何唤醒手段（SEND 有 root 唤醒，DELETE 没有）。
  用户那次（App 日志原文）：`岛动作:删除会话` → 3.4s 无回执、卡片继续流式并停在
  「回答结束」→ `岛收起` → `主岛空出` → 回执 3.4s 后才到且已成
  `忽略无主 send.result[act=DELETE ok=true]`。
- **修法**：
  1. 新增状态位 `ST_DELETING = 正在删除…`：点删除立刻改状态位并重投一帧。状态位一变，
     按钮组按「非结束」= **两个**，不会再换成带「回复」的那套。
  2. `chat.end` / `chat.conv` 原来写死 `ST_DONE`，会把「正在删除…」「已交给豆包发送」
     这类更高优先级的状态顶掉 —— 改成统一走 `statusWord(c, ended)`（`replyState` 优先）。
  3. 删除也接上 root 唤醒（`armDeleteWake`，SEND 那套的镜像）：2.6s 没回执 → `su` 拉起
     豆包进程 + **重投三次 DELETE**（1.5s 一次；删除幂等，重复投只会等到一条「会话已不在」
     的回执，被 `onSendResult` 的「无主回执」闸门丢掉）；超时从 8s 放宽到 14s
     （`DELETE_TIMEOUT_MS`，唤醒脚本自己就要 5~6s）。
- **真机验证**（把豆包 `force-stop` 掉，等价于「DELETE 广播没有接收器」）：
  `岛动作:删除会话` → 5 帧 `st=正在删除…`（`left=3字 right=5字`）→
  `豆包在后台没接删除（2600ms 无回执）→ root 已拉起豆包并重投删除（第 1 次）` →
  **5.75s** 收到 `删除回执：已删除 …（第 48 条）` → 收卡；删除动作之后
  「含回复的三按钮」行数 = **0**。不装这套唤醒时只能等超时按「删除失败，已取消」收卡
  （会话其实已经删掉了）。
- **前台删除不受影响**：同一 harness 里豆包在前台时回执 5ms（`删得很顺畅` 保持原样）。

#### 四、删除之后卡片「又回来」：墓碑 + 无 cid 帧收口

- 用户那次删除的完整形状：回执 `ok=true` 但因卡片已被别的动作收掉而成「无主」→
  紧接着模块又推一帧 `chat.start` → `beginConv()` 把这张卡**原样复活** → 最后停在
  「回答结束」那套三按钮上 —— 这正是用户说的「岛上还出现了结束回复才有的模板」。
- 两处收口：
  1. **墓碑 `deadCids`**：删除回执 `ok=true` 时记下 cid（**包括「无主回执」分支**，
     那里用删除动作自己记的 `pendingDeleteCid`），`beginConv()` 与 `pushReplyItem()`
     双重拒绝 —— 会话在豆包那边已经没了，之后到达的都是死会话残余。上限 30 条。
  2. **空 cid 的 `chat.*` 帧不再凭空建卡**：豆包新起进程时模块最初几帧不带 cid
     （日志里紧跟 `nova ctx captured`），`keyOf("")` 会把它们落到 `-` 号卡上 ——
     真机日志里删除后 1.1s 出现 `主岛归先到者 id=reply:- cid=-`，紧接着 **53 帧**
     全从这张空 cid 的卡上出去（这就是「删了还在输出」在进程重启场景里的那一半）。
     现在空 cid 一律接到**最近一条还活着的会话**上（同一轮回答的尾巴本来就属于它）；
     没有活会话就整帧丢掉并记一行
     `忽略无 cid 的 chat.delta（最近一条会话已收掉/已删除，第 48 条）`；
     本进程还**没见过任何带 cid 的会话**时才退回老的 `-` 号卡，免得把第一轮回答丢了。
- **真机（`--stop` 那轮）**：删除回执之后 25s，App 侧岛帧数 **0**（修前 **53**），
  同期模块仍投了 **70 条 `chat.delta` + `chat.end` + `chat.reply`**，全被丢掉。

#### 五、顺带修掉的真机毛病：悬浮窗里「打不进字」

- 现象：窗口**收到了**按键（`PanelRoot.dispatchKeyEvent` 的 `按键 code=…` 日志有输出），
  但 `EditText` 是 `focused="false"` —— 外层 `FrameLayout` 抢了焦点（先
  `wrap.isFocusableInTouchMode = true; requestFocus()`），按键到视图层被丢掉；
  只有输入法「上屏」（composing → commit，走 InputConnection）能进字，
  所以真实用户用输入法打字看不出来。
- 修法：`descendantFocusability = FOCUS_AFTER_DESCENDANTS`（子 view 优先）+ 窗口挂上后
  200ms / 700ms 各补一次 `et.requestFocus()`，结果写进日志
  `悬浮窗回复：输入框 requestFocus=true hasFocus=true`。

#### 证据 / 验收（真机 d666858b，产物 `1513625` 字节 / `286b60dd…b0f0`）

- **状态模板**：流式中 `岛按钮组合被接受: [知道了,删除]（第 1/3 档、通用卡）`，
  同段日志里**含「回复」的按钮组行数 = 0**；`st=回答结束` 帧之后 0.1s 才出现
  `[回复,知道了,删除]（第 1/4 档、通用卡）`（`answer_end@3022086 btn@3022209`）。
  截图 `dev/item48_island_answering.png` / `dev/item48_island_done.png`。
- **悬浮窗端到端**（`sim.overlay` = 与手指点「回复」同一个 `onAction`）：
  `面板已弹出` → 面板输入框内容 `'hello'`（5 字）→ **真触摸点面板「发送」** →
  `悬浮窗回复：面板已关闭（已发送 5字（已振动））` → `回复[手机-豆包] …: 5字` ×1 →
  `已发送到手机豆包` → 状态位 `已交给豆包发送` → **回到 `回答进行` + 两个按钮** →
  豆包新回答（`Hello😊！你好呀…` 23 字）→ `st=回答结束`；
  `files/chat/716712230022402.jsonl` 尾部同时留下
  `role=user mid=u1791224316669 len=5 head=hello` 与 `role=bot … len=23 ended=True`。
- **删除**：见上两节（后台删除 5.75s 回执 + 0 帧复活；`--stop` 场景 0 帧 / 70 条被丢）。
- **第 47 条多轮不回归**（`dev/repro3.py interrupt`）：状态序列
  `回答进行 → 已交给豆包发送 → 回答进行 → 回答结束`，两轮都完整落盘（585 / 119 字），
  `回答全文 119字 > 岛上已累积 0字 → 补全（第 47 条兜底）`；
  单轮（`dev/one_turn.py`）：`st=回答进行 body=4 → st=回答结束 body=14`、
  `卡片形态 = generic`、`岛状态: READY`、落盘 544 字完整。
- **`pc/` 一行未改**：`python -m unittest discover -s pc/tests` → `Ran 72 tests` / `OK`
  （`dev/pc_test48.txt`）。
- 安装：`adb -s d666858b install -r …` → `Success`。

#### 没动的东西

markdown 清理、正文一行（`tailOf`）、胶囊左来源右状态、多会话 `reply:<cid>` 与主岛归先到者、
消息卡对照开关 `island_reply_bar`、1 分钟未确认通知、日志/聊天记录 100 MB 上限、
主题与设置页结构、模块侧（`xposed/` 一行未改）、`pc/`。

#### 如实说明（仍未人工确认的部分）

1. **「打字」这一步没有用真手指 + 真输入法验证**：真机上 `adb shell input text` /
   `input keyevent` 送进面板的按键**只有 `action=1`（UP）**（`ime list -s` 为空时也一样），
   而 Android 的 `TextView` **只在 ACTION_DOWN 上插入字符**，所以注入不进去 ——
   这是设备/框架侧的行为，不是面板的毛病（真人用输入法打字走 composing→commit）。
   为了把「打字 → 发送」跑完，本轮给模拟通道加了 `sim.type`（→ `ReplyPanel.typeIntoLive`，
   等价于用户把这几个字打进去），**点「发送」那一下是真触摸**。
   这条测试口子写在 `ReplyPanel.typeIntoLive` 的注释里，只服务自动化。
2. **删除「卡一会」的原始场景没有复现出来**：本轮后台删除只花了 0.34s（豆包进程还热），
   所以改用 **force-stop 豆包** 造出「广播没有接收器」的等价场景验证 root 唤醒
   （5.75s 回执）。用户那次 3.4s 的原始时长没有单独复刻。
3. 岛卡按钮文字本身**无法从 uiautomator/logcat 里读到**（岛是 SystemUI 画的），
   按钮组的证据是「被接受的档位日志 + 截图」，以及 `sim.action`/`sim.overlay`
   走的是与手指点击**同一个** `onAction`；没有一次真正的手指点击入参录像。
4. 第 48 条期间用户正在同一台设备上手动操作，出现过一次面板被用户点 ✕ 关掉导致的
   自动化失败（已重跑）；本轮所有结论都取自用户操作之后的干净重跑。



### 49. 低功耗保活窗口：空闲释放 wakelock、活动期降低 ping
- **真机审计结论**：ColorOS HANS 会冻结 `com.tg.dbisland`（UID 10045）与 `com.larus.nova`（UID 10375）；前台服务、partial wakelock、Doze 白名单和 `cached_apps_freezer=disabled` 都不能阻止 HANS。设备日志出现 `freeze uid`、`Ignoring startDeliveryTimeoutLocked for hans freeze manager`。
- **改动**：`BridgeService` 不再在服务创建时永久持有 `PARTIAL_WAKE_LOCK`，改为收到有效事件时取得 15 秒活动租约；空闲自动释放。豆包模块保活窗从 30 秒/3 秒 ping 改为 20 秒/8 秒 ping，仅在发送、推流、删除等活动窗口运行。
- **验证**：新 APK `1513631` 字节；App wakelock 在活动事件后出现，15 秒后出现 `REL islandbridge:island`；模块重启日志为「退后台保活 20s，有推流则续期」；PC 回归 72 OK。
- **限制**：低功耗改动不能从根本上解除 ColorOS HANS。root 每秒 `unfreeze` 临时实验仍会被 HANS 重新冻结，未纳入正式 APK。下一步如仍需持续后台流式，应做可开关的 system_server/HANS UID 豁免实验，不应继续提高 ping 频率。

### 50. 长回答流式结束判定与短消息模板
- **根因**：真机日志显示同一 `cid` 被重复切换为「回答结束」再回到「回答进行」，但岛投递线程仍每秒更新。这是 Android parser 把任意 `msg_finish_attr`、宽泛的 SSE `END` 名称和 `[DONE]` 当作最终结束，导致一轮回答被拆开；长文本随后看起来像停止。
- **修复**：只有 `msg_finish_attr.end_type=1`、明确 `SSE_REPLY_END` JSON 的 `end_type=1` 或非空 `fin_reason` 才结束；事件名不再使用宽泛 `contains("END")`，`[DONE]` 不再绕过 JSON 终止判定。
- **显示容器**：保留 markdown 过滤；歌词播放器继续使用固定长度分组，修正游标越界时回退到尾部而不是从头循环。官方限制为正文最多 4096 UTF-16 字符、每秒最多 10 次提交，本实现每秒 1 次。
- **结束模板**：回答中保持 `GenericCard` 与 `[知道了, 删除]`；真实结束后切换官方 `MessageCard`，保留 `[回复, 知道了, 删除]`，但关闭原生输入栏，回复仍只通过悬浮窗完成。符合官方消息模板的结束态语义，同时不恢复 App 内输入能力。
- **验证**：APK `1513627` 字节，SHA-256 `2b210c22e5d41bfdd8b1f5c8fbe7d1f2a9e366b78ee5758f78c79afd0c638bb2`；PC 回归 72 OK；真机已安装，LSPosed 日志确认新模块加载。

### 51. 结束态 MessageCard 回复栏
- 按最新星河岛官方模板：`MessageCard.Builder(sender, text).setAvatar(...).setReplyEnabled(true)`。
- 仅在回答真正结束后启用 MessageCard 与原生回复栏；回答进行中仍使用 GenericCard `[知道了, 删除]`，避免流式阶段过早让位。
- `IslandCallback.onReply(activityId, text)` 已接入现有 `BridgeApp.noteIslandReply()` → `IslandBridge.onReplyText()` 发送链路。结束态按钮仍保留 `[回复, 知道了, 删除]`，悬浮窗回复路径不变。
- APK `1513621` 字节，SHA-256 `a2406c5eac486ba71f229c2e3c9b6efa163022ba77022672ceded944830e37e0`；PC 回归 72 OK。

### 52. 结束态删除旧回复按钮与真机冻结边界
- **模板**：结束态 `MessageCard` 仅保留官方 `setReplyEnabled(true)` 原生回复栏，不再添加旧的文字「回复」按钮；回答中 `GenericCard` 仍使用 `[知道了, 删除]`。
- **真机日志结论**：卡住期间 App 仍每秒提交 `岛card`，但正文长度固定、没有新的 delta；同时系统反复记录 `OplusHansManager freeze uid: 10375 com.larus.nova`，并由保活 ping 短暂 unfreeze 后再次 freeze。因此这是豆包模块上游断流/ColorOS HANS 冻结，不是岛卡容器或文字长度导致。
- **现有降级**：15 秒无 delta 时显示「内容已暂停」，结束帧到达后恢复结束态。要根治后台持续流式，需要 HANS UID 豁免或其他系统级冻结策略，不能靠提高岛卡刷新频率解决。
- **验证**：APK `1513625` 字节，SHA-256 `d9896feea3d1fd682642be3455bb321fb747d41b3e6821a00fa64e6131204c4e`；已安装真机；PC 回归 72 OK。

### 53. 长文章分段流修复
- **真机证据**：文章「善待时间」在「慢慢再来」处被截断；`chat/716712230022402.jsonl` 显示 App 保存的 bot 文本只有 86 字且 `ended=true`，后半段未进入 App，因此不是岛正文 14 字容器造成的截断。
- **根因**：Omni 快照在同一回答内可能轮换 `messageId`/触发分段结束回调。旧逻辑把 messageId 变化和任意 `onReceiveEnd` 直接当作整轮结束。
- **修复**：messageId 变化不再结束回答；`finishOmni`、`[DONE]`、最终 JSON 结束标记统一进入 800ms 去抖窗口，窗口内收到新快照则取消结束，静默后才发 `chat.end`。
- **验证**：APK `1513626` 字节，SHA-256 `f63316bdc00da91685c47634c0fd28348ec760f726ab2bd4e702893f8532dd98`；已安装真机；PC 回归 72 OK。仍需真实发送同类长文章确认后半段完整进入 `chat/*.jsonl`。

### 54. 收紧 Omni 结束 Hook
- 真机回归显示上一版仍可能提前结束，原因是模块把 `onReceiveEnd$lambda$17`、所有 `*Finish` 方法都当作整轮结束。
- 现在只允许精确方法名 `onReceiveEnd` / `onMessageEnd` 触发 `finishOmni()`；内部 lambda 与泛化 Finish 方法不再截断回答。
- APK `1513625` 字节，SHA-256 `f7f9b9a99749dc5b2a8f8c2ae888f893a339c8e3af17a7643acbda08160e3053`；已安装真机；PC 回归 72 OK。尚未完成新的长文章端到端确认。

### 55. 按会话 ID 隔离 Omni 收录
- **架构修复**：心跳只表示链路健康，不参与正文接收。Omni 内容现在按 `conversationId` 建立独立 `MobileFeedParser`，各自维护正文、block 去重、回合状态和结束窗口。
- **首帧兼容**：首个 Omni 帧没有 `conversationId` 时先按 `messageId` 暂存；后续拿到真实会话 ID 后迁移到同一收录器，避免首段丢失。
- **结束路由**：显式结束只作用于当前活跃会话收录器，不会结束其他会话。心跳超时不会删除、暂停或丢弃正文。
- **验证**：APK `1513627` 字节，SHA-256 `175b3d4f2b4c6459cc8768106d29104e033dae9aad9126c282239756fd334eed`；已安装真机；PC 回归 72 OK。尚需重新发送长文确认完整收录。

### 56. 最终消息收录与设置项同步
- **最终消息**：豆包结束后发出的完整 Omni 消息，即使缺少 `replyId`，只要携带 `conversationId` 与 `content/brief`，也会进入对应会话收录器，作为流式中断时的全文补全。
- **会话分配**：Omni parser 按 `conversationId` 隔离；心跳超时不参与正文接收、结束或丢弃。最终消息仍沿用同一 cid，标题由会话名查询/chat.conv 更新。
- **设置页**：移除旧的“岛上原生回复框（消息卡形态）”开关，改为只读显示当前固定规则：回答中 GenericCard，结束后 MessageCard + 官方原生回复栏。
- **验证**：APK `1497247` 字节，SHA-256 `06ac0ef0618529aa17b07e80c9ef834c1b58112d9cd66dff7ad8168799797272`；已安装真机；PC 回归 72 OK。

### 57. 真机 DEX 确认结束 Hook 锚点
- **APK 证据**：真机豆包 `15.1.0`（versionCode `15010040`）的 `classes24.dex` 中，`OmniMessageDispatcher` 实际存在 `onReceiveEnd(String replyMsgId, String endMsg)`、`onStreamingMessage(String, OmniMessage, OmniMessage, int)`、`onReceiveMessage(String, OmniMessage, Map)`。
- `onReceiveEnd` DEX 指令明确取两个参数并关联字段名 `replyMsgId`、`endMsg`。因此它不是泛化方法名推测，是真实的结束回调；`onReceiveEnd$lambda$17(String,String,OmniMessageObserver)` 是另一个编译器生成方法，不能混用。
- **修复**：结束回调按第一个参数 `replyMsgId` 映射到对应 Omni parser 后结束，不再用最近活跃会话猜目标。已编译、安装真机。
- **验证状态**：PC 回归 72 OK；仍需真机回答确认日志中的 `replyMsgId` 与流式 `messageId` 对应，及岛卡转为结束态。
- APK `1497249` 字节，SHA-256 `c212716a22bbf52ce5ba2859a36679fbcd798902d7513839e5be357b4a33ee09`。

### 58. 固定字符容器与发送后保活
- **正文投递**：新增每会话固定字符容器。正文增量先进入容器，达到设置容量（`filesDir/ib_lyric.txt` 第 1 行，默认 10 字）才投递一组；收到 `chat.end` 时强制投递不足一组的余量。卡片不再把全文交给 SDK 再做 `tailOf` 截断，因此减少两行和省略号。
- **全文保留**：`Conv.buf/disp` 仍保存完整正文，容器只控制岛卡每次投递的显示片段。
- **发送后退出**：豆包后台保活 ping 从 8 秒缩短为 3 秒；模块无推流唤醒看门狗从 8 秒缩短为 5 秒，减少“发送后马上退出、豆包没有输出”的窗口。唤醒只恢复豆包，不重发消息。
- **验证**：APK `1497240` 字节，SHA-256 `9750a14fb709401462ee0a23af0ae1e00fc454283af6d9b05ed117f65c79a194`；已安装真机；PC 回归 72 OK。尚需真机验证容器视觉效果与后台发送恢复。

### 59. 修复结束事件被 Omni 去重层吞掉
- **真机根因**：`emitShared` 原先对 `src=omni && mid in sseMids` 的事件全部 return。SSE 已经收过正文后，Omni 的同一 `mid` 结束事件 `chat.end/chat.reply` 也被当成重复事件丢弃，导致岛卡一直停在「回答进行」。
- **修复**：只继续去重 Omni 的 `chat.start/chat.delta`；`chat.end/chat.reply` 必须放行。结束映射同时兼容原始 `replyMsgId`、去掉 `#回合号` 的基础 mid，并输出 `found=true/false` 诊断。
- **验证**：APK `1513628` 字节，SHA-256 `cf4417b298833f74393272e77626e12a547e3f6d09d1d5f2fc33681bbd16a6d7`；已安装真机；PC 回归 72 OK。需要实际回答确认日志出现 `finish omni ... found=true` 并切换结束态。

### 60. 修复末尾余量与停止事件收尾顺序
- **日志证据**：最新 `chat/*.jsonl` 正文已完整，但最后事件仍为 `ended=false`，说明末尾文字收录链路正常，`chat.end` 没有落到对应 Conv。
- **末尾修复**：`chat.end` 先设置 `c.ended=true`，再强制 flush 容器余量，最后投递结束态，避免最后不足一组的文字先以「回答进行」发送又被覆盖。
- **结束兜底**：`replyMsgId` 映射不到 parser 时，如果只有一个活跃 Omni parser，则结束该 parser；增加 `isStarted()` 和 `found=true/false` 诊断。
- **验证**：APK `1513629` 字节，SHA-256 `edf7f7fc057fa7e63d62af568efeeb1338bdf241b9d3b33baab7e9155816c041`；已安装真机；PC 回归 72 OK。需要新一轮真实回答确认 `chat.end` 与「回答结束」状态。

### 61. 无结束事件静默收尾
- **最新会话证据**：完整正文已经写入 `chat/38445866040926466.jsonl`，但最后事件仍为 `ended=false`；岛卡只进入「内容已暂停」，说明本轮没有可靠 `chat.end` 到达 App。
- **修复**：每个 `MobileFeedParser` 在收到 Omni 快照或正文增量后重置 4 秒静默计时；连续 4 秒无新增内容且本轮仍 active 时自动执行 `endChat()`。显式 `onReceiveEnd` 仍优先，静默只是缺失结束回调时的兜底。
- **末尾内容**：`chat.end` 先设置 `ended=true`，再强制 flush 容器余量，最后投递结束态，避免最后不足一组的字符停留在回答进行态。
- **验证**：APK `1513623` 字节，SHA-256 `a0c5e84129bdd36b267365c5451b776376361827c02f3d2096e6f6b502b5d05d`；已安装真机；PC 回归 72 OK。需真实新会话确认 4 秒后 `ended=true`。

### 62. 每次发送独立的后台冻结看门狗
- **真机证据**：回答流在 `com.larus.nova` 主进程产生，后台后 HANS 会冻结主进程和 `:push`；前台/解冻时流才能继续。保活日志显示后台 ping 只运行有限窗口，超时交还系统。
- **看门狗竞态修复**：`lastStreamAt` 是跨发送全局值，旧回答的近期流事件可能让新一轮 5s 无推流检查误以为本轮仍活跃，从而不唤醒豆包。现在每次 `noteInAppSend` 重置流时间并递增 generation，旧 Runnable 失效，检查严格绑定当前发送。
- **限制**：不能阻止 ColorOS 强制冻结，但修复旧事件挡住唤醒的逻辑缺陷；需真机“发送后立刻退出”确认出现 5s 无推流唤醒日志。
- **验证**：APK `1513630` 字节，SHA-256 `f81797a68979641dfb7e585e6f31c917dc565ecc2135376798a5631782573f93`；已安装真机；PC 回归 72 OK。

### 63. 解除监听线程同步阻塞
- **根因**：`emitShared` 原先在豆包的 SSE/Omni 监听线程中同步调用 `contentResolver.call(EventProvider)`；ColorOS 冻结/解冻或 App 主线程处理变慢时，该 Binder 调用阻塞监听线程，事件积压后一次性冲出并再次卡住。每条事件还同步写完整 JSON 日志，加重阻塞。
- **修复**：事件改为有序后台投递泵；监听线程只复制 JSON 并入队立即返回。队列上限 512，过载丢最旧事件并记录计数，避免无限堆积。Provider 失败时后台泵再尝试广播兜底。
- **日志**：逐条正文日志改为仅记录来源、事件类型、mid/cid 尾部和文本长度，避免长文本 I/O 阻塞。
- **验证**：APK `1513629` 字节，SHA-256 `7d33bbc14baf198d35bab2e382821c312a7fe0178921e33c52b4853c190d1240`；已安装真机；PC 回归 72 OK。需真机长回答确认不再“突然冲出一大串后卡住”。

## 三、真机验证记录（已实测）

| # | 验证项 | 结果 |
|---|---|---|
| 1 | App 内「安装/重装 root 组件」 | `root runtime ensured (stage rc=0, launch rc=0)`，316~478ms |
| 2 | 重启后 `service.sh` 自动拉起 | relay/listen/nc 都在 init ns；token 跨重启不变 |
| 3 | `pc/simulate_doubao.py` 推 6 帧 | 手机侧 6 条 `tcp->binder ok` + 岛侧 `ev ok chat.*` |
| 4 | 攻击变体（无 token / 错 token / 乱码） | 全部 `dropped` |
| 5 | 卸载脚本 `uninstall.sh` | 185~261ms 收掉 worker、释放 8799、删干净两个目录 |
| 6 | 管理器路径卸载后不被自动装回 | 唤醒 App：`root component removed by user — skip reinstall`，无残留；前台点重装 → 标记清除 + `rc=0,0` |
| 7 | 手机真实路径（豆包对话） | 钩子 `ev[sse] chat.delta` → `via provider` → App `ev ok ... mobile=true` → 卡片持续更新 |
| 8 | 岛卡片构建 | 持续 `岛card ...`，无异常 |
| 9 | 保活告警限流 | 一轮 6 帧只 1 条 info |
| 10 | 回复回执超时不再关卡片 | `回复回执超时(8000ms)：未确认，卡片保留`，后续推流继续 |
| 11 | 回复上行（App → 豆包进程 → 豆包收到） | `SEND recv … main=true` → `done 20ms err=ok` → App `send.result` 成功；豆包会话里出现该消息并作答 |
| 12 | 配置行可见性 | dump 实测 y=342~715，第一屏可见 |
| 13 | 豆包退后台保活窗 | 离开前台即开窗；chat.delta 续期 207 次、app-ping 8 次；静默 30s 后自动收手，3s 后系统冻结豆包 |
| 14 | 回到前台立即停止保活 | 23:56:48 开窗 → 23:56:54 收手 |
| 15 | 冻结后普通广播丢失 | 手动冻结后投 SEND：`recv in` 行数 30 → 30（零新增）——复现用户现象 |
| 16 | root 唤醒后送达并发出 | `SEND recv text=17字` → `send ok ret=null` → `SEND done 21ms err=ok` |
| 17 | 冷启动不再谎报成功 | `err=豆包冷启动无会话模板，构造发送被服务器拒绝`（旧版报：`err=ok`） |
| 18 | 重复投递去重 | `重复 SEND（id=…）已忽略`×2（连投 3 次只发 1 次） |
| 19 | 模板层等回执 | `SEND done 432ms err=ok` + `cb onSuccess`（旧版 10ms 就报 ok） |
| 20 | 被拒时诚实上报 | `err=模板重放被服务器拒绝（请求里没有有效消息体）` |
| 21 | 补发通道送达 | 豆包自己的通道：`chat.start` + delta 回显 `escalation_probe` |
| 22 | 歌词分组（无省略号） | `岛card … roll=15字` / `roll=14字` 交替前进（旧版 160 字 + `…`） |
| 23 | 豆包自发消息看门狗 | 新增 `noteInAppSend`：发送即开窗 + 8s 无推流则通知 App 唤醒（端到端待用户手测） |
| 24 | 省略号彻底清零 | `岛card.*…` 零命中；`body≤36字 roll=12字`（长回答推流中） |
| 25 | 分组长度实时可调 | `ib_lyric.txt` 写 12 → `roll=12字`（未重装） |
| 26 | 推流中卡住不刷新 | 正文等长不断句 + 节拍 1s；岛窗口 Relayout 从反复来回降到 2 次 |
| 27 | 正文只占一行 | `body=18字` 恒定（不再两行） |
| 28 | markdown 记号不上岛 | `去 markdown 记号 8字`（原文含 `**`） |
| 29 | 调参文件生效 | `ib_lyric.txt` = 12/14 → `body=14字 roll=12字` |
| 30 | 明细卡被岛接受 | 无「卡片按钮组合被拒」降级日志，岛帧正常 |
| 31 | 电脑无响应时不列出 | daemon 未跑 → `srcs=手机`（无电脑） |
| 32 | 电脑+手机同时显示 | 模拟通道 → 卡片帧 `srcs=电脑,手机` |
| 33 | 关掉模拟后恢复 | `pc off` → 卡片帧 `srcs=手机` |
| 34 | 明细卡已撤下 | 卡片形态日志 = `generic`（与改动前一致），无降级异常 |
| 35 | 胶囊不滚动正文 | `roll` 长度全程恒为 8 字（安卓包·回答进行） |
| 36 | 状态用词 | `回答进行` / `回答结束` |
| 37 | 收起态静止 | 右侧无 trailing、呼吸动画关闭（`setBreathing(false)`） |
| 38 | 一左一右 | 帧日志 `left=3字 right=4字`（左 安卓包 / 右 回答进行） |
| 39 | 岛长度不可控（SDK 事实） | Builder 无尺寸/位置 setter，长度由宿主按内容量 |
| 40 | 一分钟无操作 → 收起来 + 通知 | 日志 `一分钟无操作 → 发状态栏提示…` + `岛已收起（事件结束，内容在通知里）`；通知 `id=0x1b17` 在 `dumpsys notification` 里 |
| 41 | 通知小图标踩坑（已修） | 用 App 自适应启动图标 → `Invalid notification (no valid small icon)`；改 `android.R.drawable.stat_notify_chat` 后正常 |
| 42 | 启动图标写进包 | `aapt2 dump badging` 出 `application-icon-*`；合成图肉眼确认为豆包花标 |
| 43 | 多会话：两张卡同时在线（模拟通道两个 cid） | `岛card id=reply:sim-a prio=high cid=sim-a …` 与 `岛card id=reply:sim-b prio=default cid=sim-b …` 同一条时间线上交替刷新，两条 id 各自独立 |
| 44 | 主岛归先到者 | `主岛归先到者 id=reply:sim-a cid=sim-a seq=1`；后到的打 `新会话进副岛 id=reply:sim-b cid=sim-b prio=default （先到者 id=reply:sim-a 仍占主岛）` |
| 45 | 收掉先到者 → 副岛升级为主岛 | `模拟通道动作 ack id=reply:sim-a` → `岛动作:我知道了 id=reply:sim-a cid=sim-a` → `先到者已被收掉 → 副岛升级为主岛 id=reply:sim-b cid=sim-b prio=high（重新 post 一次）` + `岛card id=reply:sim-b prio=high cid=sim-b` |
| 46 | 全收完 → 主岛空出 | 再 ack sim-b → `岛动作:我知道了 id=reply:sim-b cid=sim-b` → `主岛空出（当前没有在线的会话）` |
| 47 | **真实**双会话（豆包自己起了两条对话） | 同屏交替：`岛card id=reply:716712230022402 prio=high cid=022402 src=手机 st=回答进行` 与 `岛card id=reply:38444393490299138 prio=default cid=299138 src=手机 st=回答进行`；宿主回调 `岛动作:我知道了 id=reply:38444393490299138 cid=299138` 只落到那一条，另一条继续 `prio=high` |
| 48 | 每会话一个 PendingIntent / 点卡片只收自己 | `am broadcast -n com.islandbridge/.OpenDoubaoReceiver -a com.islandbridge.OPEN_DOUBAO --es cid 38444393490299138` → `先到者已被收掉 → 副岛升级为主岛 id=reply:38445118828849922 cid=849922 prio=high（重新 post 一次）`（被点的那条收掉、另一条仍在）；最终包复验：`--es cid 716712230022402` → `主岛空出（当前没有在线的会话）` |
| 49 | 冷启动单会话照旧 | 强制停止后冷启动 → `主岛归先到者 id=reply:716712230022402 cid=022402 seq=0`，全程 `prio=high`；`chat.end` → `st=回答结束`；`去 markdown 记号 4字`；无 `新会话进副岛` |
| 50 | `pc on` 模拟通道在新文件格式下仍精确（改成整行匹配） | **关 Wi-Fi 让真实电脑链路掉线后**（否则这条通道永远走不到）：① JSON 正文里带 `pc on` 字样 → `srcs=手机`（不再误判成"电脑在线"）；② 文件里有一整行 `pc on` → `srcs=电脑,手机`；测完已恢复 Wi-Fi（`wifi_on=1`） |
| 51 | **会话标题**（用户报的「会话的标题没有被显示」；真实豆包会话 + 真实回复流） | 04:05:42 模块 `会话名(legacy) cid=849922 name='测试'` → `会话名[get] cid=849922 name='测试'（2字）` → App `ev ok chat.conv` + `会话名 cid=849922 '测试'（2字）来源=chat.conv` → `会话标题更新 cid=849922 '豆包' → '测试'` → `岛card id=reply:38445118828849922 … cid=849922 … title='安卓包-测试'`；**展开卡片截图肉眼确认标题行就是「安卓包-测试」**（截图 `dev/h2_card_c.png`）。同一最终包再按**用户规定的路径**（`ib_p.txt` + SEND 到 `OuterShareDeliverActivity`）复验：`会话名[get] cid=022402 name='豆包'（2字）` + `岛card … cid=022402 … title='安卓包-豆包'`（主对话在豆包侧的真名就是「豆包」，与兜底同值，所以那条看不出差别 —— 这正是不换一个真名会话就测不出来的原因） |
| 52 | 会话名为什么必须读字段 | `convList size=1 elem=com.larus.im.internal.jni.bean.OmniConversation` + `convList getters: component6='豆包' …`；只用反射 getter 时真机拿不到（`getConversationId$annotations`/`getName$annotations` 在、getter 本身不在 `idx24.tsv` 的方法表里），日志停在 `对象里没有名字字段（u99.e）`；改读字段后立刻命中 |
| 53 | 第 33 条改动后的**回归**（多会话/副岛/一分钟提醒，都在最终包上复测） | 模拟通道两个 cid：`会话名 cid=sim-a '模拟A'（3字）来源=chat.start` → `主岛归先到者 id=reply:sim-a` → `岛card id=reply:sim-a prio=high … title='电脑包-模拟A'`；后到者 `新会话进副岛 id=reply:sim-b prio=default（先到者 id=reply:sim-a 仍占主岛）` → `岛card id=reply:sim-b … title='电脑包-模拟B'`（**两条并发会话各自带自己的会话名标题**）；`sim.action ack sim-a` → `岛动作:我知道了 id=reply:sim-a` → `先到者已被收掉 → 副岛升级为主岛 id=reply:sim-b prio=high（重新 post 一次）`，与第 43~46 行行为一致；同一包 04:08:36 复测一分钟提醒：`一分钟无操作 → 发状态栏提示「您的豆包消息未确认」+ 岛折叠` + `岛已收起（事件结束，内容在通知里）`（第 40 行行为未破） |
| 54 | **第 34 条**切割电脑端：`aapt2 dump badging` 不再有网络权限 | `uses-permission` 只剩 `com.tg.dbisland.permission.CONTROL` / `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` / `POST_NOTIFICATIONS` / `RECEIVE_BOOT_COMPLETED` / `WAKE_LOCK` + SDK 合并的 `PUBLISH_ACTIVITY`、`DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`；`INTERNET` 与 `ACCESS_NETWORK_STATE` **已不在**（旧包同一命令里这两条都在） |
| 55 | **第 34 条**UI 不再有电脑端配置块 | 装机启动后真机截图 `dev/ib_v12_cut.png`（12:35）：标题「豆包岛桥」正下方直接是 `正文一行字数：14` / `胶囊分组长：12` / `在岛上预览回复`，**没有**「电脑IP / 端口 / 连接 / 断开」那一行；环境自检卡片只剩 `Root 权限（已 root · uid=0 · KernelSU）` / `LSPosed 框架（LSPosed 已运行 · v2.2.0 (7854) · 未登记本模块）` / `模块状态`，**没有「Root 组件」行**，也没有「重启 root 组件」「卸载 root 组件」「查看局域网监听令牌」按钮；日志行 `uid: app=10045 doubao=10375`、`岛状态: READY` |
| 56 | **第 35 条**包名迁移：badging + 新包可启动 | `aapt2 dump badging`：`package: name='com.tg.dbisland' versionCode='3' versionName='1.2'`、`application: label='豆包岛桥' icon='res/BW.xml'`、`application-icon-160/240/320/640/65534:'res/BW.xml'`、`launchable-activity: name='com.tg.dbisland.MainActivity'`、`uses-permission: name='com.tg.dbisland.permission.CONTROL'` + `com.tg.dbisland.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`；`adb install -r` → `Success`；`su -c am start -n com.tg.dbisland/com.tg.dbisland.MainActivity` → `Starting: Intent { cmp=com.tg.dbisland/.MainActivity }`，logcat `NFW_findFocusedWindowIfNeeded:Window{1151e5b u0 com.tg.dbisland/com.tg.dbisland.MainActivity} mCurrentFocus:Window{…}` + `updateForegroundInfo … [com.tg.dbisland]=1, uidPidMapInf(1)\|[10045]=16852`，`pidof com.tg.dbisland` = `16852`，**无 FATAL/AndroidRuntime 崩溃**。注意：`pm path` 同时列出旧包 `/data/app/…/com.islandbridge-…`（**旧包未卸载，也未删**），新包是独立 applicationId |
| 57 | 回归：`pc/` 冻结后测试仍全绿 | `D:\tool\miniconda\python.exe -m unittest discover -s pc/tests` → `Ran 72 tests in 10.509s` / `OK`（`pc/` 本次只新增 `FROZEN.md`，一行代码未改；`sim_multi.py` 移出到 `tools/`，它不属于测试套件） |
| 58 | 星流 seen_sources 残留清理 | 删 3 个死条目后回读只剩 `com.astraflow.tool`，1359→1235 字节，属主/权限 `u0_a50:u0_a50 660` 保持 |
| 59 | 电脑端根组件撤离（设备侧） | relay/listen/`nc -p 8799` 进程无、8799 不监听、`/data/adb/islandbridge` 与 `/data/adb/modules/islandbridge` 均不存在 |
| 60 | 旧包卸载 | `pm list packages -u com.islandbridge` → 无 |
| 61 | 新包端到端验证 | `logcat -c` 后新产生的 6 帧：`(com.larus.nova)[com.tg.dbisland,…] 保活续期（app-ping）` + `岛card id=reply:716712230022402 … srcs=手机 title='安卓包-豆包'` |
| 62 | LSPosed 自动登记（更正第 35 条） | `modules_state` 里 `(com.tg.dbisland, 0, enabled=1, scope_request_blocked=0)`，`scope` = system + com.larus.nova，无需人工启用 |
| 63 | **第 38 条**新界面：dock 在底部三格、点击切页 | 装机后截图 `dev/ui_final_logs.png`：底部一条玻璃 dock，三格 `聊天 / 日志 / 设置`，当前页 `日志` 一格有高亮胶囊（选中态是**一块滑动的玻璃**，不是换色图标）；点 x=240 切到聊天、点 x=720 切到日志、点 x=1200 切到设置均生效 |
| 64 | **第 38 条**聊天页：左侧会话框 + 右侧聊天页 | 模拟通道造两条会话后截图 `dev/ui_final_chat.png`（收起态）：标题 `模拟sim-b` + `共 1 条答复` + 汉堡按钮 + 气泡正文 `后到的会话同时在答，两条卡同时在线。` + `已生成` 绿标 + 底部 `回复豆包…` 输入框与发送圆钮；截图 `dev/ui_title_open.png`（展开态）：左侧玻璃面板标题 `会话` / `2 个会话` / ✕，两行 `模拟sim-b`、`模拟sim-a` 各自带摘要与答复数角标 `1`，**面板内容清晰可读**（这是三次返工后的结果：面板不再用 `Modifier.blur`） |
| 65 | **第 38 条**日志页：模块运行情况 | 截图 `dev/ui_final_logs.png`：`模块在线` + `最近心跳 24s 前 · 累计 2 次` + `空闲` 标签；四行 `豆包注入 / 岛连接（IslandClient 状态：READY）/ 推流帧（累计 16 帧 · 最近 23s 前（正在推流））/ 上行命令`；筛选条 `全部 信息 提醒 错误` + `42 条`；日志流带时间戳（如 `13:55:20 会话名 cid=sim-a '模拟sim-a'（7字） 来源=chat.start`） |
| 66 | **第 38 条**设置页：岛显示配置 + root/LSPosed 检测 | 截图 `dev/ui_settings.png`：`运行环境`（`Root 权限 已 root · uid=0 · KernelSU`、`LSPosed 框架 LSPosed 已运行 · v2.2.0 (7854)`、`模块状态`；底部 `root 管理器 KernelSU`）、`岛显示`（`正文一行字数 14` 与 `胶囊分组长 12` 各带 −/＋、`回复输入条（MessageCard）` 开关、`在岛上预览一条回复`）、`调试`（三条测试事件 + 岛规则提示）、`关于`（包名 `com.tg.dbisland` / 版本 `1.2 (3)` / 权限 / 提供者） |
| 67 | **第 38 条**回归：`pc/` 测试仍全绿 | `python -m unittest discover -s pc/tests` → `Ran 72 tests in 10.045s` / `OK`（界面重做只动 Android 侧） |
| 68 | **第 38 条 7**：标题居中且开关会话框时**零位移** | 像素实测两张截图（`dev/ui_title_closed.png` / `dev/ui_title_open.png`）里标题文字带**完全相同**：`title=[538..887] center=712.5`，屏幕中心 720，偏差 **-7.5px(1.9dp)**；汉堡（收起时 x≈76）用 alpha 隐去后不移除，所以宽度恒定、标题不被推 |
| 69 | 幽灵会话占主岛（修复） | 删 `filesDir/ib_sim.txt` 并重启后 `prio=default` → `prio=high`，未收掉会话只剩真实那条 |
| 70 | **第 40 条** 岛回复链路（模拟通道，`tools/sim_multi.py replytest`） | 一次投递 6 条：`模拟通道已投递 6 条并清空文件（防重启重放，第 39 条）` → `岛展开 id=reply:sim-a cid=sim-a → 同 id 换 MessageCard（带回复输入条；锁屏不显示输入框）` → `岛card id=reply:sim-a … card=msg` → `岛上回复 id=reply:sim-a cid=sim-a 8字 → 交本应用发送` → `岛card … st=已交给豆包发送 … reply='已交给豆包发送'` → `回复[手机-模拟sim-a] id=reply:sim-a cid=sim-a: 8字` → `已递交豆包进程发送（cid=sim-a）` → `岛收起 … 同 id 切回 GenericCard（保住常驻/主岛归属）`（`dev/sim_reply_40b.txt`） |
| 71 | **第 40 条** 岛回复链路（**真实**豆包会话 `cid=716712230022402`） | `岛展开 … → 同 id 换 MessageCard … card=msg` → `岛上回复 … 9字 → 交本应用发送` → `岛card … st=已交给豆包发送 … card=msg` → `已递交豆包进程发送（cid=022402）` → `已发送到手机豆包`；豆包随后在同一张卡上回话 `chat.delta … "明白，请把需要最终确认的内容发给我，我帮你复核一遍最终产物。"`（`dev/sim_final_expand2.txt`、`dev/sim_final_reply2.txt`） |
| 72 | **第 40 条** 官方配额/限流不再静默 | 8 条会话同屏时：`岛 start(reply:sim-03) = QUOTA_EXCEEDED：本应用同时在岛上的内容已达上限（官方：最多 3 条）；liveIds=8 pending=0`、`岛 start(reply:sim-06) = RATE_LIMITED：投送超过官方上限（每秒 >10 次）` |
| 73 | **第 41 条** 聊天记录固化（R9 修复） | 冷启动：`已从磁盘恢复 1 条会话的聊天记录（共 3 条消息）`；`cat files/chat/716712230022402.jsonl` 三行 = 豆包回答 / `role":"user"` 的「请再确认一次：收到我的回复了吗」/ 豆包回答「收到了你的消息。」 |
| 74 | **第 41 条** 日志固化 | `固化: 日志目录=/data/user/0/com.tg.dbisland/files/logs（1 片 / 4215B）`；`files/logs/2026-10-05_14:51:43_832.log` 里一行一条 `时间\t级别\t正文`；修掉「每行写两份」后复测每行一份 |
| 75 | **第 41 条** 上限落地 | 冷启动 `上限: 日志 100 MB（单片 6 MB）· 聊天记录 100 MB（每会话最多 400 行）—— 超额都从最旧的删起`；实测占用 `logs` 24.7 KB（1 片）、`chat` 433 B（1 个文件） |
| 76 | **第 41 条** 裁剪路径（临时把上限压到 8 KB / 6 KB，`tools/sim_limits.py`） | `聊天记录已按上限裁掉最旧的 1 条（2 KB，当前 4 KB / 上限 6 KB）`（连删 6 次）+ `日志已按上限裁掉最旧的 1 片（8 KB），当前 0 KB / 上限 8 KB`；磁盘现状：`chat/` 只剩 `sim-04.jsonl`、`sim-05.jsonl`（8 个会话里最旧的 6 个被整份删掉）。**验证后已删 `ib_limits.txt`**，冷启动复读上限 = 100 MB |
| 77 | **第 41 条** 模拟通道不再重放（第 39 条收口） | 处理完即清空：`模拟通道已投递 N 条并清空文件（防重启重放，第 39 条）`，`ib_sim.txt` 归 0 字节；`su` 写出的 root/644 文件导致的每秒 `FileNotFoundException` 也一并修掉（脚本 `chmod 666` + App 启动自愈 + 失败只记一次） |
| 78 | 回归：`pc/` 测试仍全绿 | `D:\tool\miniconda\python.exe -m unittest discover -s pc/tests` → `Ran 72 tests` / `OK`（`pc/` 未改一行） |
| 79 | 消息卡去按钮（第 42 条） | `按钮被拒/异常：(无)`；`卡片形态 = generic → msg → generic`；`岛card … st=已交给豆包发送`；聊天记录出现用户那句回复 + 豆包新回答 |
| 80 | 岛卡形态固定（第 43 条） | `卡片形态统计: {'msg': 9}`、`形态切换日志: (无切换)`、推流中与结束后都是 `card=msg`、`异常/被拒: (无)` |
| 81 | 岛卡方案 A（第 44 条） | 收起 `card=generic` / 展开 `card=msg` / `已发送到手机豆包` / `异常被拒:(无)`；形态只在展开收起时切换 |
| 82 | **第 64 条** 系统级 HANS 冻结豁免（P0 根因，真机验证） | 关豆包到后台 75s：`OplusHansManager : freeze uid: 10375` **0 行**（改前每 75s 2–3 次）；模块日志 `hans cgroup hooks installed (HansCGroup.hansFreezeLocked ×2)` + `HANS 已拦下冻结 uid=10375 via=HansCGroup.hansFreezeLocked(pkg)`；`hans thaw → true` 完全消失（无进程被冻，兜底循环空转） |
| 83 | **第 64 条** 反向定位方法（不再猜实现路径） | 新增 `dev/coloros_framework/dexfindstr.py`：在 `oplus-services.jar` 常量池定位 `'freeze uid: '` 下标 → 扫描每个 `code_item` 找引用该下标的 `const-string`（format 21c）→ **唯一**命中 `Lcom/android/server/hans/freeze/HansCGroup;->hansFreezeLocked`（方法体内写 `/dev/freezer/frozen/cgroup.procs`）。前几轮拦 `OplusHansProcessFreeze` / `OplusHansManager.hansFreeze` / 状态机**一次都没命中**，此文件是唯一可靠判据 |
| 84 | **第 65 条** 端到端真机验收（产品路径，非手动广播） | 豆包**全程后台**的一轮：`HANS 豁免窗口 60s why=chat.start`（20:53:25）→ `why=chat.delta`（20:53:35）→ `why=chat.end`（20:53:49）；期间 `freeze uid: 10375` **0 次**、`unfreeze uid: 10375` **0 次** |
| 85 | **第 65 条** 会话文件与岛卡状态 | `files/chat/716712230022402.jsonl` 结束记录 `ended=true` 共 1449 字 ⊃ 上一条 1419 字（末尾 30 字余量已补），结尾是完整句子；字面 `...` **0 次**、无两行伪影；岛卡 `card=generic st=回答进行` → `card=msg st=回答结束`（GenericCard → 官方 MessageCard） |
| 86 | **第 66 条** `m0` 占位消息号（真机数据发现 → 根因已定位，**未完全修复**） | 现象：结束记录的 `mid` 是占位号（改前 `"m0"`，改后 `pending#0`），与承载同一段正文的真实 mid 记录**分裂**成两条（真实号 `ended=false` + 占位号 `ended=true`）。**根因（真机逐帧日志，21:00:54–21:01:00）**：同一段回答被**两条并行摄取通道**抱走 —— `ev[sse] t=chat.delta mid=19009538`（HTTP chunk 路径，有真实 mid）与 `ev[omni] t=chat.delta mid=ending#0`（OmniMessageDispatcher 路径，拿不到真实 mid），收尾时 `chat.end` 又带第三个 id `18998018`；占位号那一轮由 omni 路径开、始终没等到真实号 |
| 87 | **第 66 条** 已落地的部分修复 + 回归 | 已改：`mid`/`curMid` 默认值不再共用字面量 `"m0"`（改空串）；占位号改为**本轮唯一** `pending#<turnSeq>`；`adoptMid()` 学到真实号时迁移整轮并给占位轮补 `chat.end`（不留 `ended=false` 半截记录）；未拿到真实号时有界攒帧（250ms）再开轮。**用户可见结果正常**（岛卡 `card=generic st=回答进行` → `card=msg st=回答结束`，会话文件结束记录 `ended=true`、正文 1440+ 字完整、字面 `...` 0 次）。**未解决**：双通道 id 不一致导致的记录分裂 —— 下一步应做事源归并（同一回答二选一或按 cid 合并），见 `ARCH_REVIEW.md` P1。回归：`python -m unittest discover -s pc/tests` → `Ran 72 tests in 9.978s` / `OK` |
| 88 | **第 67 条** 岛卡正文「只有结束时才冒一下」（真机逐帧定位 + 修复） | 症状：回答进行中岛上读不到内容，只有结束那一下冒出来。**根因是死代码遮蔽**：`chat.delta` 推岛时只传那 10 字分组（`pushReplyItem(c, c.containerBody, …)`），而 `pushReplyItem` 里 `val text = if (c.containerBody.isNotBlank()) c.containerBody else tailOf(…)` —— `containerBody` 只在 `flushContainer` 里赋成当前分组、**恒为非空**，于是注释里设计的「推流中取累积正文的等长滑动窗口」分支**从未执行过**；`chat.end` 那帧传进去的**完整正文也被直接丢弃**。真机日志证据：整轮 `岛card … st=回答进行 body=8~10字`（对照常量 `EXPAND_MAX=14`、`LYRIC_GROUP=10`） |
| 89 | **第 67 条** 修法与用户确认 | 改：`flushContainer` 推岛时传**累积正文** `c.disp`；`pushReplyItem` 去掉 `containerBody` 优先分支，统一走 `tailOf(clean, bodyMax(), boundary = ended)` —— 推流中取等长最新一段（高度稳定、不折行、**无省略号**），收尾那帧按句子边界断（给结论）。**已与用户确认维持"一行"策略**（`bodyMax()=14`，可用 `filesDir/ib_lyric.txt` 第 2 行实时调，不必重装）；岛上仍读不到全文（一行的硬上限），全文在本 App 聊天页（`chat.delta` → `applyDelta` → `publishThrottled()`，1Hz 级实时刷新） |
| 90 | **第 67 条** 真机验收（本轮） | 逐帧日志：整轮 `岛card … st=回答进行 body=14字`（改前恒为 8~10 字），正文长度稳定不再跳变；`chat.end` 那帧按句边界给结论。HANS 侧同步复核：`HANS 豁免窗口 60s why=chat.delta`（22:19:59）→ `why=chat.end`（22:20:19、22:20:34）、`HANS 已拦下冻结 uid=10045 via=HansCGroup.hansFreezeLocked(pkg)` |
| 91 | **第 68 条** 豁免窗口被过早清空（真机日志发现，已修） | 症状：轮次进行中窗口被反复清成"到期"，出现最长约一个续期间隔的无保护间隙。日志：`22:19:59.028 HANS 豁免窗口 60s why=chat.delta` → `22:19:59.046 hans keepAlive=false（窗口到期）`（**仅 18ms 后**）→ 之后每 10s 一次。**根因**：`hansArm` 每次 `Handler(Looper.getMainLooper())` **新建实例**再 `removeCallbacks`，而队列里 message 按 (Handler, Runnable) 配对，新实例移不掉旧实例 post 的那条；且无代际判断，旧回调会把**当前**窗口清掉。**修法**：同一个 `hansHandler` + `hansGen` 代际双保险（过期回调仅在代际未变时清窗口） |
| 92 | **第 68 条** 真机验收（修复后） | `22:25:59.310 HANS 豁免窗口 60s why=chat.start` + `keepAlive=true` → `22:26:01.206 已拦下冻结 uid=10045 剩余=58s` → `22:26:09.340 why=chat.delta`，**中间没有任何 `keepAlive=false（窗口到期）`**（改前窗口开出 18ms 就"到期"、此后每 10s 一次）；本轮 logcat `freeze uid: 10375` 与 `unfreeze uid: 10375` 均 **0 行**。同一轮岛上与落盘：推流中 `岛card … st=回答进行 body=14字`（改前 8~10 字，正文长度稳定不再跳变），收尾帧 `st=回答结束 body=11字`（按句子边界断给结论）；`ev[omni] t=chat.reply mid=24199682 text=859`；会话文件末两条 `ended=true`（3242 字 / 859 字，结尾均为完整句子），字面 `...` **0 次** |
| 93 | **第 69 条** 结束态模板没有按钮（用户报「结束事件的模板缺少了按钮」） | **根因**：`buttons(st, msg)` 里对消息卡直接 `if (msg) return listOf(emptyList())`（第 42 条"消息卡上一个按钮都不给"的旧取舍）→ 结束态 MessageCard 恒为空按钮组。**修法**：结束态改为 `[知道了,删除]` → `[知道了]` → `[]` 三档降级链（官方回复栏仍由 `setReplyEnabled(true)` 提供，不再加旧的「回复」文字按钮）。**真机日志**：同一次注入，改前 `卡片形态 = msg` → `岛按钮组合被接受: []（第 1/1 档、消息卡）`；改后 `[知道了,删除]（第 1/3 档、消息卡）` —— 宿主接受 |
| 94 | **第 69 条** 附带修掉一条会吞证据的日志 bug | `岛按钮组合被接受` 的去重键原来只比按钮名，而通用卡与消息卡现在都是 `[知道了,删除]` → 消息卡那行被吞掉，恰好把「结束态到底有没有按钮」的唯一直接证据藏了。去重键改为 `"$kind|$names"`（带卡片形态） |
| 95 | **第 70 条** 设置页文案精简（用户报「设置里文字赘述了」） | 逐段压缩：`外观`/`岛显示`/`固化与清理` 的分组副标题、两处步进器 hint、五段说明文字、两处操作按钮文案（`已授权（可去系统页收回）`→`已授权` 等）；删除三处与相邻控件重复的说明（「深浅两套配色…」「回答中 GenericCard…」）。`StartupCheck.kt` 的三条「推断依据…」（最长 5 行）各压成一句，并删掉紧邻它的重复灰字段落。**真机截图核对**：设置页四屏已确认（`dev/ui_set_A~E.png`） |
| 96 | **第 69/68 条** 真实回合真机验收 | 用户实机操作一次真实回合（22:47）。**结束态按钮**：用户确认岛上结束卡显示**「知道了 / 删除」两个文字按钮**（第 42 条当时担心的「消息卡文字按钮被画成勾/叉」没有复现）。**HANS**：`22:47:21.651 窗口 60s why=chat.start` → `22:47:24.265 已拦下冻结 uid=10375 via=HansCGroup.hansFreezeLocked(pkg) 剩余=57s`（**豆包自己的 uid 在真实回合里被拦下**）→ `22:47:31.688 why=chat.delta` 续期，中间无 `窗口到期`；`logcat` 里 `freeze uid: 10375` / `unfreeze uid: 10375` 均 **0 行**。另：前几轮合成注入的窗口现在都是**精确 60s** 到期（`22:41:52.732` → `22:42:52.734`），不再 18ms / 每 10s 抖动 |
| 97 | **第 71 条** 移除发布版里的测试 / 调试内容 | 删掉设置页「调试」分组（测试进度 / 测试歌词 / 结束测试三条伪造事件入口）、`SmallAction` 组件、`IslandBridge` 里的两个隐藏调试开关（`useMessageCard` + `PREF_REPLY_BAR` 对比开关、`useDetailsCard` 明细卡形态）与随之失效的 `DetailsCard` 分支 / import、以及设置页只在调试开关里用到的 `replyBar` 状态与 `prefs` / `JSONObject`。分组副标题里的「调试 · 」同步去掉，那句「本 App 在前台时岛不显示本 App 内容」挪到「岛显示」组（它是预览按钮的必要提示）。卡形态现在只由会话状态决定：回答中 `GenericCard`、结束 `MessageCard`。`MainActivity` 的 `-e reply` 调试入口**保留** —— 它由 `FLAG_DEBUGGABLE` 把关，release 包里根本不生效 |
| 98 | **第 71 条** 交付打包（桌面：源码包 + 源码目录 + APK） | 收录范围按项目自己的 `.gitignore`（= 项目认定的「源码」）：`android/`（含 `libs/` 的 AstraIsland SDK aar 与 Xposed API stub）、`pc/`、`tools/`、`pylibs/`（`bridge_daemon.py` 用 `sys.path` 引入，属运行期依赖）、`assets/`、`third_party/`（SDK LICENSE/NOTICE）、根部全部 `.md` 与 `.gitignore`；共 **200 文件 / 2.70 MB**。**排除**：`android/keystore/` 与 `keystore.properties`（**正式签名私钥，`.gitignore` 明令不入库**）、`local.properties`、各 `build/`、`.gradle/`、`__pycache__/`、`android/dist/`、`pc/{build,dist}/`、`dev/`（2.3 GB）、`Doubao/`（3.6 GB）、`capture/`（263 MB）、`com/`+`dao/`+`META-INF/`（解开的 SDK `.class` 逆向产物，构建实际用 `libs/*.aar`）、根部逆向记录 `*.txt/*.tsv/*.ps1`、运行期 `*.log`/`*.db`/`events.jsonl`/`raw_cap.jsonl` |
| 99 | **第 71 条** 源码包可编译性实测（发现并修掉一个交付缺陷） | 把交付的 zip 解到临时目录直接编译 → **BUILD FAILED**：AGP 拒绝路径含非 ASCII 字符的工程（`Your project path contains non-ASCII characters`），而交付目录名就是中文 —— 等于「交付的源码包在交付的路径下编译不了」。修法：`android/gradle.properties` 加 `android.overridePathCheck=true`（本工程无 native/NDK 代码）。复测：解包到中文目录执行 `gradlew.bat compileReleaseKotlin` → **BUILD SUCCESSFUL**。APK 逐字节未变（`gradle.properties` 不进包，Gradle 判定 `distRelease` UP-TO-DATE） |
| 101 | **换机复现「回答一会就不回答」→ 根因是"半新半旧"，不是代码** | 新设备 OnePlus OPD2404（Android 16 / ColorOS 16.0.5 / KernelSU / LSPosed）。症状：豆包输出几秒后推流彻底停住。排查链条：① 豆包已是 **15.2.0**（旧机 15.1.0）→ 先怀疑锚点，但日志显示 `OmniMessageDispatcher` 全套 + `writeChunkData/writeMetaInfo` + 通知探针**全部命中**，排除；② `IslandBridge relay fail`/`wake ... fail` 属于旧设计里的既有分支，不是新故障；③ system_server 里本模块**只有 12 行日志、HANS 相关 0 行**，而旧机有完整的 `hans cgroup hooks installed` —— 且日志里模块 id 是**旧包名 `com.islandbridge`**，豆包进程里却是 `com.tg.dbisland`；④ 时间线对上：**开机 00:22:35 → system_server 注入旧模块 00:22:39 → 新 APK 才在 00:28:06 装上**。结论：**`system_server` 只在开机时注入模块一次，装完模块不整机重启，就永远跑着开机时那份旧代码**，HANS 拦截根本没生效 → 豆包一退后台就被冻结 → 推流 4 秒后断。修法：**整机重启**。重启后复验：模块 id 变 `com.tg.dbisland`，`hans SM hooks installed` + `hans cgroup hooks installed (HansCGroup.hansFreezeLocked ×2)` + `hans receiver registered (第 1 次)` 全部就位 |
| 102 | 本机 ROM 的 HANS 点位签名逐个核对（换 ROM 必做） | 把本机 `/system/framework/oplus-services.jar`（26 MB，classes.dex+classes2.dex）拉下来，用 `dev/coloros_framework/dexclasses.py` 逐个核对：`OplusHansProcessFreeze.freezeProcess(II)V` ✓、`OplusHansManager.hansFreeze(ILjava/lang/String;Z)Z` ✓、`HansAppStateMachine.forceEnterFrozen()V` / `forceTransitionState(ILjava/lang/String;)V` ✓、`HansCGroup.hansFreezeLocked(ILjava/lang/String;Ljava/lang/String;)Z` **和** `(Lcom/android/server/hans/OplusHansPackage;Ljava/lang/String;)Z` **两个重载都在** ✓、`OplusHansPackage` 类在 ✓。**结论：四个 hook 目标在 ColorOS 16.0.5 上签名与模块完全一致，无需改点位** —— 这也把"是不是 ROM 变了"这条路彻底排除，锁定到注入时序 |
| 103 | 清掉旧 root 设计的设备残留（交付文档承诺「不写 /data/adb」） | 新机上发现旧设计残留不只是文件：`/data/adb/service.d/islandbridge.sh` 每次开机仍在执行，`/data/adb/islandbridge/{handler,relay,listen}.sh` 的三个进程**正在运行**，其中 `busybox nc -lk -p 8799 -e .../handler.sh` 是**活着的局域网监听**（即 CHANGELOG 第 4 条那个"孤儿 nc 占 8799"）。已留档到 `dev/old_root_leftover/` 后删除目录与开机脚本，并 kill 掉三个 PID；复查 8799 已无监听。提醒：**这是设备状态不是仓库代码**，交付文档里那句「不联网、不监听端口、不写 /data/adb」描述的是当前设计，旧装的机器需要手工清 |
| 104 | 交付文档修正：装完必须**整机重启**（本条的坑就是它造成的） | 原交付说明第 8 节写的是"启用后强行停止并重启豆包"——**远远不够**：HANS 冻结拦截在 `system_server` 里，而 `system_server` 只在开机时注入一次。已改写为「必须 `adb reboot` 整机重启一次」，并写清判断标准（重启后 LSPosed 日志里要有 `hans cgroup hooks installed (HansCGroup.hansFreezeLocked ×2)`）。作用域也由笼统的「`com.larus.nova` + `android`」改为点明 **`com.larus.nova`（豆包）+「系统框架」**。另补：换机复验一行（OPD2404 / ColorOS 16.0.5 / 豆包 15.2.0） |
| 105 | 修复后**端到端真机验证**（新机 OPD2404：发送 → 2 秒后退到桌面） | 实测时间线：`00:47:54` 点发送 → `00:47:57.133` system_server `HANS 豁免窗口 60s why=chat.start uids=10329,10351` → `00:47:57.142` `hans keepAlive=true uid=10329 pkg=com.larus.nova` → **`00:47:57` 按 Home 退到桌面**（故障场景的同一动作）→ `00:47:58.472` `豆包离开前台 → 开始保活` → **`00:48:02.848` `HANS 已拦下冻结 uid=10329 via=HansCGroup.hansFreezeLocked(pkg) 剩余=54s`** → `00:48:57.146` `hans keepAlive=false（窗口到期）`（距 chat.start **60.013 s**，窗口精度正常）。判定：退后台后豆包进程仍有 **69 行事件**（`00:47:58.061` → `00:48:06.049`），末条 `ev[omni] t=chat.reply mid=72423938 text=371` —— **整轮 371 字回答在后台上跑完**；logcat 里 `freeze uid: 10329` **0 行**。对照故障样本（同一台机、重启前）：`00:32:37.983 豆包离开前台` → `00:32:41.997` 之后**再无一条事件**。另注：本机豆包 uid 是 **10329**（旧机 10375），模块运行时解析 uid，不受装机顺序影响；开机时 `hans hooks installed ... uids=`（空）也在首个 `chat.start` 时被 `hansArm` 惰性重新解析成功（日志里的 `uids=10329,10351`）
| 106 | 遗留待确认项：`xposed_scope` 声明的是 `android`，而生效那组勾的是「系统框架」 | 模块 `arrays.xml` 里 `xposed_scope` = `com.larus.nova` + **`android`**，但 LSPosed 作用域表里 `system` 与 `android` 是**两行**；本次修复生效时勾的是「豆包」+**「系统框架」**，且旧包 `com.islandbridge` 在本机的 scope 表也正是 `{system, com.larus.nova}` 并确实进了 `system_server`——**即「系统框架」(`system`) 一定覆盖 system_server**。但**没有实验证明"只勾 `android` 一项会不会也进去"**（要改作用域+重启，代价高），所以文案里把「系统框架」写死、不改源码。若将来有可随意折腾的机器，单独验一次 `android` 是否等价于 `system` |

## 四、待人工确认（不要当成已完成）

| # | 事项 | 需要人做什么 |
|---|---|---|
| 1 | 岛卡片**目视**确认 | **注意星河岛规则：本 App 在前台时岛不显示本 App 的内容** —— 不能在豆包岛桥 App 里点测试按钮等动画，要切到豆包对话或按 Home 后由模块推流（电脑端已删除，第 34 条） |
| 2 | 卡片**常驻**（直到点「我知道了」） | 推一张卡后长时间不点，确认它一直在主岛 |
| 3 | 胶囊右侧**滚动词**是否像歌词 | 豆包回答较长时看胶囊右侧是否持续滚动刷新 |
| 4 | ~~卡片上「删除会话」上行到 PC daemon~~ | **已不适用**：电脑端已整体删除（第 34 条），「删除会话」现在只走豆包进程内的模块 |
| 5 | ~~App 内「卸载 root 组件」按钮路径~~ | **按钮已删除**（第 34 条）；留在设备上的 `/data/adb/modules/islandbridge` 需要用户在 root 管理器里手动移除 |
| 6 | 「内容已暂停」提示 | 推流中把豆包切后台/杀掉，15s 后看胶囊状态 |
| 7 | v1.1 → v1.2 覆盖升级 | 同证书 + versionCode 2→3 只是前提，本次未专门验证覆盖升级；**注意第 35 条：从 `com.islandbridge` 升到 `com.tg.dbisland` 不是覆盖升级，是并存的新包** |
| 8 | 多会话下**主岛/副岛在岛上的实际排布** | 本端能证明"两条会话各自一个活动 id、都在被持续刷新、宿主把其中一条的按钮回调送了回来、收掉先到者后本端把下一条改成 `Priority.HIGH` 重新 post"；但**"优先级的最终排位由宿主决定"**，主岛/副岛的**视觉**位置仍需人眼看一次（本 App 在前台时岛不显示本 App 内容，得按 Home 或切到豆包看） |
| 9 | 手点（而不是 adb 触发）每张卡上的按钮 | 「我知道了/点卡片」路径已用宿主回调 + `OPEN_DOUBAO` 广播（与 `PendingIntent` 同一接收器、同一 `cardTapped`）跑通；**真手指点击**没做，请顺手点一下确认 |
| 10 | 多会话下一分钟无操作 | 计时器已改成每会话一份，但"两条会话同时在线、只对其中一条触发 60s 无操作"的组合没有单独实测 |
| 11 | App 进程被杀后重启的"主岛归属" | 先到次序表在**内存**里，进程被杀就没了。重启后第一条会话会被当作"先到者"给 `HIGH`，而宿主那边可能还挂着重启前那张卡 —— 这种"宿主有卡、我们没有账"的错位只能靠用户点掉旧卡恢复，未做持久化（也不打算为此落盘会话内容） |
| 12 | 没有回复在跑时**改会话名**会不会立刻刷新标题 | 现在学名字的时机是「回复开始时主动查 + 回答结束后 4s 补查」（被动通道 `OmniConversationDispatcher` 在真机上入参常常是空列表，指望不上）。所以「在豆包侧给会话改名、当时那条会话没有回复在跑」→ 卡片标题什么时候跟上，没有单独实测（下次它一开口就一定会带上新名） |
| 13 | **第 35 条改名后必须人工做的事（已由第 37、38 条收尾）** | ~~① 在 LSPosed 里为新包 `com.tg.dbisland` 重新启用模块并勾选作用域~~ **实测不需要**（第 37 条：`xposedscope` 自动登记，`enabled=1`）；~~② 决定旧包 `com.islandbridge` 何时卸载~~ **已卸载**（第 36 条） |
| 14 | **第 38 条新界面的视觉与手感** | 已逐页截图确认（第三节 63~66 行）：dock 在底部三格、聊天页气泡与回复框、会话框面板内容清晰、日志页四条状态 + 日志流、设置页四块配置。**仍需你已经看过并认可的是**：会话框展开/收起的动画手感、玻璃面板的浓淡（`GlassStyle.panelAlpha = 0.92` / `panelFrostRadius` 现在是纯观感参数，随时可调）、dock 选中态滑动的阻尼。这些是主观项，截图证明不了 |
| 15 | **第 38 条**：真豆包会话下的聊天页 | 现在聊天页的数据是用**模拟通道**（`tools/sim_multi.py`）造的两条会话验证的。真实豆包推流（`chat.start/delta/end` + `chat.conv` 会话名）进 `BridgeHub` 的路径与模拟通道**完全相同**（同一 `handleEvent`），但**没有单独在真豆包对话上再抓一次界面截图** |
| 16 | **第 40 条**：真手指点岛上的回复框 | 本轮的岛回复链路是**用模拟通道**（`sim.expand` / `sim.reply`，走与宿主回调完全同一批函数）验证的，`PREF_REPLY_BAR` 也保持默认关。**「人用手指在岛上点开卡片、在输入框里打字、点发送」这一整套没有做过** —— 需要人工在豆包对话页/息屏下点一次：期望看到卡片展开后出现输入框，输入并发送后卡片状态变「已交给豆包发送」，随后豆包回答在同一张卡上刷新 |
| 17 | **第 40 条**：锁屏不显示回复框 | 官方文档写「锁屏不显示输入框」，代码里没做任何锁屏判断（`LockScreenVisibility.TITLE_ONLY` 只影响卡片可见性）。**未实测** |
| 18 | **第 40 条**：宿主对「同一个 id 换模板」的排位处理 | 官方文档只说 `start()` 对同一 id 是整份替换。真机上实测到 `onExpanded` 之后**宿主还回调了一次 `onCollapsed`**（可能是我在模拟通道里发起的展开被宿主当成「非用户操作」而立即收回），所以「展开 → 换消息卡 → 一直保持到用户收起」这个**时序**没有在真机上完整观察到；本端只能证明「换模板成功 + 状态/按钮按新形态生成」。需要人工点一次确认观感 |
| 19 | **第 41 条**：日志/聊天记录固化的长期行为 | 只验证了「上限被压到 8 KB / 6 KB 时确实按最旧的删」（第三节 76 行）。**真正的 100 MB 上限没有跑到过**（那需要写满 100 MB），所以「跑满 100 MB 之后的行为」是按同一套代码路径推断的；另外 `ib_limits.txt` 这个调参入口是给验证用的，普通用户不会创建它 |
| 20 | **第 45 条**：真手指点岛上「回复」→ 悬浮窗面板 | 链路是**用模拟通道 `sim.overlay`**（与手指点按钮走同一个 `onAction("reply:<cid>", ACT_REPLY)`）+ `adb shell input text` 打的字，全链路日志与磁盘都在。**「人手指点岛上按钮、人手指在面板里打字、人手指点发送」这三下没有做过**；面板与键盘的贴合观感（贴着输入法上沿、卡片位置是否跳动）也只能人眼看 |
| 21 | **第 46 条**：主题的观感与其它页面 | 浅色/深色两套都截图确认了「首页 + 底部 dock + 导航栏区域」（`dev/theme_light_bottom.png` / `dev/theme_dark_bottom.png`），系统栏图标翻转也实测了。**未逐页截图**：日志页、会话框展开态、悬浮窗回复面板在深色下的观感（配色代码是同一套 `AppColor`，但没有一页一页看过去）；另外「跟随系统」的自动切换只验证了「系统是浅色时跟随=浅色」，**没有把系统真的切成深色再让它自动翻**（那要改系统设置） |

## 五、仍未修的安全项（如实列出）

| # | 预审意见 | 现状 |
|---|---|---|
| R1 | ~~电脑端 `pc/` 8787 无鉴权~~ | **已移出本项目**：电脑端（含 `sse_server.py` / `config.json`）已冻结在 `pc/`，安卓端不再连它（第 34 条）。风险只存在于那个留档目录被单独运行的情况 |
| R2 | ~~电脑端无 TLS~~ | 同上，已移出；安卓端不再发起任何网络请求（网络权限已删除） |
| R3 | 广播 uid 拿不到时放行 | `getSendingUid()` 在个别 ROM 返回 -1，此时按「无法归因」放行并记警告（写操作有签名级权限兜底） |
| R4 | DSH 插件与 GUI 共用端口 | 见 `DSH.md`（电脑端留档内容） |
| R5 | MessageCard 让位副岛 | 星河岛规则，只能靠切 GenericCard 规避 |
| R6 | 遗留的旧包与 root 模块 | **已清理**（第 36 条）：旧包 `com.islandbridge` 已卸载、`/data/adb/modules/islandbridge` 与 `/data/adb/islandbridge` 已移除、8799 无监听。新版 App 也不再管理 root 组件（第 34 条删掉了入口），所以以后若再出现 `/data/adb` 残留，需要用户自己在 root 管理器里清。**第 35 条那句「LSPosed 需要为新包重新启用」已被第 37 条更正为不需要** |
| R7 | **第 38 条**：面板的「磨砂」是**观感**，不是真实折射 | 现在的会话框/dock 是「高不透明度深色底 + 渐变反光 + AGSL 内阴影 + 亮边」，**不会把背后的像素掰弯**（SukiSU 那套靠 miuix 的 backdrop 管线能做到，本机装不进来）。要做真折射需自建 backdrop 捕获 + `RenderEffect.createRuntimeShaderEffect`，`ui/glass/GlassShader.kt` 里的圆角矩形 SDF 折射 shader（含 7 抽样色散）已按 Kyant0/AndroidLiquidGlass 算法移植好留着当入口 |
| R8 | **第 38 条**：R8 元数据告警 | AGP 8.9.3 自带的 R8 比 Kotlin 2.2.10 旧，构建时打印若干条 `R8: An error occurred when parsing kotlin metadata …`。产物正常（装机运行无误），但属于已知瑕疵；彻底消除需要升 AGP 或把 Kotlin 降到 R8 认识的版本 |
| R9 | ~~**第 38 条**：聊天记录只在内存（进程被杀即清空）~~ **已修（第 41 条）** 聊天记录已落盘（`files/chat/<cid>.jsonl`，全局上限 100 MB，超出删最旧）。日志同样固化在 `filesDir/logs`（分片 + 100 MB 上限）。**残留面如实说明**：这些都是 App 私有目录，卸载即随 App 一起消失（`README.md` 的「卸载后剩什么」一节已同步）；LSPosed 模块跑在豆包进程里，**模块自己的日志仍然只在 logcat** |

## 六、自检命令与实测输出

```
$ cd android && ./gradlew.bat distRelease
release APK -> D:\aiwork\doubaoni\android\dist\doubaodao-v1.2-release.apk
sha256      -> 286b60ddc79f3acaa48def8a14d62fcf211f8d655e161c206d61a11e722db0f0
（第 45 条三按钮 + 悬浮窗回复、第 46 条设置页重排 + 主题进设置 + 浅色底部 + Documents
 编号副本、第 47 条中途插话不再丢增量、第 48 条按状态换按钮组 + App 内回复入口移除 +
 删除不再复活/不再卡在后台/不再凭空多一张 `reply:-` 卡 之后的最终产物：1513625 字节）

$ D:\tool\miniconda\python.exe -m unittest discover -s pc/tests
Ran 72 tests in 10.052s
OK                                     # feed_parser 25 / uplink_protocol 15 / sse_server 15 / simulate_doubao 17
                                       # 第 40、41 条只动 Android 侧，pc/ 一行未改

$ D:\tool\miniconda\python.exe tools/sim_multi.py replytest --out dev/sim_reply_40b.txt
（模拟通道：chat.start/delta/end → sim.expand → sim.reply；输出见第 40 条与第三节 70 行）

$ D:\tool\miniconda\python.exe tools/sim_limits.py run --out dev/sim_limits_41.txt
（先把上限压到 log=8192B / chat=6144B 跑出真实裁剪，再 reset 回 100 MB；见第三节 76 行）

$ adb -s d666858b install -r android/dist/doubaodao-v1.2-release.apk
Success                                # com.tg.dbisland / versionName=1.2 / 无 debuggable / 无网络权限

$ adb -s d666858b shell "su -c 'am start -n com.tg.dbisland/com.tg.dbisland.MainActivity'"
Starting: Intent { cmp=com.tg.dbisland/.MainActivity }
# logcat（第 41 条自检两行）:
#   固化: 日志目录=/data/user/0/com.tg.dbisland/files/logs（1 片 / 4215B）· 聊天记录=1 个会话文件 / 373B
#   上限: 日志 100 MB（单片 6 MB）· 聊天记录 100 MB（每会话最多 400 行）—— 超额都从最旧的删起

# 触发真实回答（用户规定的路径）:
$ adb -s d666858b shell "am force-stop com.larus.nova"
$ adb -s d666858b shell "su -c 'am start-service -n com.larus.nova/com.ss.android.message.NotifyService'"
$ adb -s d666858b shell 'su -c '"'"'am start -a android.intent.action.SEND -t text/plain \
    --es android.intent.extra.TEXT "$(cat /data/local/tmp/ib_p.txt)" \
    -n com.larus.nova/com.larus.home.impl.OuterShareDeliverActivity'"'"''
# App: 主岛归先到者 id=reply:716712230022402 cid=022402 seq=0 → 岛card … card=generic title='安卓包-豆包'
```

---

# 豆包岛桥 v1.1 —— 预审 5 条整改 + 星河岛 SDK 0.1.0 迁移 + 电脑端/DSH 重新适配

> 交付日期：2026-10-04 ｜ 上一版：v1.0（`astraisland-client` 协议 5/6、debug 签名）
> 逐条证据与命令见 [`SECURITY.md`](SECURITY.md)、可粘贴的商店简介见 [`SUBMIT.md`](SUBMIT.md)。

## 一、产物与校验值（最终构建）

| 项 | 值 |
|---|---|
| APK | `android/dist/doubaodao-v1.1-release.apk` |
| SHA-256 | `9be8e5e7a72ad3fb8c4dd715744bd354c9eb8c75d76101c2b40e4ce739ad3a38` |
| 签名者 | `CN=IslandBridge, OU=Doubaodao, O=IslandBridge, C=CN`（自建证书，**非 Android Debug**） |
| 证书 SHA-256 | `738c3ae28cc70ad5349c589b2c08d650b42cf22a2d8aa5f5b178ea11e90c5614` |
| versionCode / Name | `2` / `1.1` |
| SDK | compileSdk 36 · minSdk 26 · targetSdk 36 · AGP 8.9.3 · Kotlin 2.1.20 |
| debug 标志 | **无**（`aapt2 dump badging` 无 `application-debuggable`） |

私钥在 `android/keystore/islandbridge-release.jks`，口令在 `android/keystore.properties`
（两者都已在 `.gitignore` 里）。**请离线备份 keystore**：丢了就无法覆盖安装升级。

## 二、预审 5 条 → 整改落点 → 验证方式

| # | 预审意见 | 改动 | 怎么验证的 |
|---|---|---|---|
| 1 | `SEND`/`DELETE` 接收器导出且无校验，任意应用可代发/永久删除 | 新增签名级权限 `com.islandbridge.permission.CONTROL`（`protectionLevel=signature`）并 `registerReceiver(..., PERM_CONTROL, ...)`；`BridgeSecurity.kt` 再按 `sentFromUid`／反射 `getSendingUid`／`Binder.getCallingUid` 校验发送方 uid；四个 exported 接收器全部加 uid 白名单 | `aapt2 dump badging` 出现 `uses-permission: com.islandbridge.permission.CONTROL`；`aapt2 dump xmltree` 权限 `protectionLevel=0x2`；`Receivers.kt` 每个 `onReceive` 首行即 `allowBroadcast` |
| 2 | root 监听默认 `0.0.0.0:8799` 且无令牌，同网段可注入 | `listen.sh`：`BIND=127.0.0.1`、启动即生成 32 位十六进制令牌（`$DIR/token`、`ib_listen.conf` 0600）、**每一帧**必须 `<TOKEN>\t<JSON>` 前缀匹配、`${#TOKEN} < 16` 直接拒启、无通配回退；App 内新增「查看局域网监听令牌」 | APK 内 6 个 root 资产齐全；`EnvCheck` 读 `BIND`/令牌长度并在「Root 组件」行展示「仅本机 127.0.0.1 · 已强制令牌」 |
| 3 | `/data/adb` 开机常驻服务卸载后残留 | 改为标准模块布局 `/data/adb/modules/islandbridge/{module.prop,service.sh,uninstall.sh,launcher.sh,relay.sh,listen.sh}`；`uninstall.sh` 三路清理：杀 worker 进程 → 删 `/data/adb/service.d/islandbridge.sh`（老版本遗留）→ 删 `$DIR` 与日志（仅当 `$0` 不在 `$MOD` 下才 `rm -rf $MOD`，避免与管理器互斗）；App 内「卸载 root 组件」按钮 + 明确弹窗；README 新增「root 组件的安装与卸载（必读）」 | `EnvCheck` 三态：标准模块已装 / 发现遗留裸脚本（提示清理）/ 未装；简介如实写明卸载 App **不会**自动清 `/data/adb` |
| 4 | debug 证书 + `debuggable` | `signingConfigs.release`（v1/v2/v3）读 `keystore.properties`；`release { isDebuggable = false; isMinifyEnabled = true }`；`proguard-rules.pro` 保留 SDK 类 | 见上表「产物与校验值」；`apksigner verify` 退出码 0 |
| 5 | 简介未披露发消息/删会话/root 守护/监听端口/后台唤醒 | `README.md` 重写：行为披露表、权限与隐私、数据流向、卸载后剩什么、root 组件必读、已知限制；`SECURITY.md` 逐条说明；`SUBMIT.md` 为可直接粘贴的简介 | 三份文档均已写入仓库 |

## 三、顺带完成的其它工作

### 0. 规范核对（本轮的「参考 AstraIsland-Developers / astraflow.cc/island」落到实处）

| 核对项 | 结论 | 证据 |
|---|---|---|
| 当前 SDK 版本 | **0.1.0 公开测试**（不是旧的 `astraisland-client` 1.3.0/协议 6） | 官网 `astraflow.cc/island` 首屏「星河岛 SDK 0.1.0 公开测试」；`sdk-0.1.0/api-doc.txt`：`SDK_VERSION="0.1.0"`、`PROTOCOL_VERSION=7` |
| 我们打进去的 AAR | **与官方字节一致** | `android/app/libs/astraisland-sdk-0.1.0.aar` 与 `AstraIsland-Developers\sdk-0.1.0\astraisland-sdk-0.1.0.aar` 同为 `SHA-256 7c7e86ab3decd3f2d939283d72aea303400335a99c4b3aa58eac5f9a9f144b43`（160748 B） |
| 清单要怎么写 | 官网接入第 01 步原文「**清单声明自动合并**」；官方 sample 的 `AndroidManifest.xml` 里**没有任何** `PUBLISH_ACTIVITY` / `<queries>` | 官方 sample 原文 + 我们 APK 的 `aapt2 dump badging`（合并出 `com.astraflow.tool.island.permission.PUBLISH_ACTIVITY`） |
| 依赖写法 | 官方 sample 就是 `implementation(files("libs/astraisland-sdk-0.1.0.aar"))` | 官方 sample `app/build.gradle.kts` 原文 |
| 运行要求 | **Android 15+**，宿主要装星流并启用星河岛 | 官网「Android 15+ 系统要求」；我们 README 已同口径写明 |
| 宿主品牌适配 | ColorOS 16 已支持 / ColorOS 17 可用 / ColorOS 15 基本可用 / 其他品牌逐步适配中；星河岛**不属于任何品牌系统功能** | 官网原文，已写进 README 的系统要求下方（避免用户误以为是系统能力） |
| 卡片模板 | **九套**（通用/进度/强调/明细/状态/大图/音乐/消息/对称）；我们用进度 + 消息/通用 | 官网模板页 + `api-doc.txt` 的九个 Builder |
| 阻塞语义 | 「`start`/`update`/`end` 等待星河岛处理完毕后返回，示例均在后台线程调用；回调在主线程执行」 | 官方 sample `README.md` 原文 —— 与我们把所有投送放进单线程 worker 一致 |
| 同一事项只显示一次 / 副岛让位 | 官网「同一事项在屏幕顶端只显示一次」、副岛「次要事项以圆形显示在主岛两侧」 | 我们的按 id 去重 + README「已知限制」里的消息卡让位说明即源于此 |

### 1. 星河岛 SDK 迁移（旧库已停止提供，不迁就收不到卡片）

`astraisland-client`（协议 5/6）→ **星河岛 SDK 0.1.0**（`PROTOCOL_VERSION = 7`）：

- `IslandBridge.kt` 全面改用 `com.astraisland.sdk` 类型化 API
  （`IslandActivity.Builder(id, Capsule, IslandCard)`、`ProgressCard`、`MessageCard`、
  `IslandResult`、`Outro`、`Priority`、`DismissPolicy`）；
- 投送调用**全部移出主线程**（0.1.0 的 `start/update/end` 是阻塞调用，主线程会 ANR）；
- Builder 全部包 `try/catch(IllegalArgumentException)`（0.1.0 是构造即抛）；
- 回复文本按官方建议 `LockScreenVisibility.TITLE_ONLY`；
- manifest 里手写的 3 条 `PUBLISH_ACTIVITY` 与 3 条岛 `<queries>` 删除，交给 AAR 合并
  （只留 hook 豆包用的 `com.larus.nova`）；
- 重新加入 SDK 许可与出处：`NOTICE-ASTRAISLAND.md`、
  `third_party/astraisland-sdk-{LICENSE,NOTICE}`（PolyForm Noncommercial 1.0.0，
  保留 `Copyright 2026 MuYuanXing / AstraIsland`）。

### 2. 电脑端重新适配（`pc/`，详见 [`pc/ADAPTATION.md`](pc/ADAPTATION.md)）

按真实抓包（272 MB `capture/hook.jsonl`）与 DEX 反汇编逐条核对：

- 发送真实走 `/chat/completion`（REVERSE_NOTES 里的 `/im/sse/send/message` 在 PC 侧 0 次）；
  删除真实走 `batch_operate` + `cmd 1125` + `operate_type 8`（`del_user_conv` 0 次）；
- 抓包发现并修掉的真 bug：`plan.*` 因 `thread_status` 恒为 `running` 而**永不触发**、
  `tts_content` 与正文等长（重复发射会让岛上文字翻倍）、`async_job` 是 JSON 字符串
  （老代码 `.find()` 会 `AttributeError`）、用户回声被当成新回复；
- `pc/sse_server.py` 新增 `POST /delete`，默认绑定从 `0.0.0.0` 改为 `127.0.0.1` 并告警；
- 新增 `pc/tests/`（55 个用例，全部通过）。

### 3. DSH 插件组合包（详见 [`docs/DSH_PLUGIN.md`](docs/DSH_PLUGIN.md)、[`pc/dsh-plugin/`](pc/dsh-plugin/README.md)）

把桥接事件面做成 DSH **组合包**（`dsh.bundle.patch` + `cordis.patch.yml` + ESM 模块），
复用 DSH 的 `webServer`，强制 Bearer/`?token=` 鉴权、单帧与速率限制、非回环默认拒挂载。
报告里给出了本机版与官方文档的差异、以及一个决定性的架构事实：
**插件路由与 DSH Web GUI 同端口**，而本机 DSH 硬拒 `--host 0.0.0.0`
（`startup.js` 原文：*it would expose remote code execution to the network*）。

### 4. 跨端对齐（子代理报告里点名要 android 侧定夺的三处，已在 Android 侧改完）

| 问题 | 处理 |
|---|---|
| 手机「删除会话」对**电脑来源**的会话只关卡片、什么都没删 | 新增 `SseClient.postDelete()` → `POST /delete`；`IslandBridge.deleteViaPc()` 按 PC 回执收卡片（原实现会假报「已删除」） |
| PC daemon 也发 `send.result{src:"pc"}`，手机 `onSendResult(ok=true)` 会**无条件**收卡片，可能误关当前会话的卡 | `onSendResult` 增加守卫：**没有在等回执就忽略**；`dismissReply` 同时取消超时回调（避免事后补一条「失败」提示）；`act` 同时认 `com.islandbridge.DELETE` 与 `delete` |
| `MobileFeedParser.isDone` 把 `complex_task_block.status` 的 `3/4` 当完成，与 PC 判定相反 | 改为**只有 `2` 算完成**。已独立复核原始抓包（逐条 JSON 解析）：`status=4`+「已开始工作」+`organizer` 282 条、`status=2`+「已完成工作」+`supertask` 60 条 → 原实现会在计划刚铺开时就发假的 `plan.end` 并清空状态，导致卡片反复重开。复核脚本：`pc/tests/verify_complex_task_status.py` |

## 四、本轮真实验证（命令与结果）

```
# Android（JDK 17 / build-tools 36.0.0）
.\gradlew.bat :app:distRelease                      → BUILD SUCCESSFUL（含 lintVitalRelease）
apksigner verify --print-certs doubaodao-v1.1-release.apk
                                                   → CN=IslandBridge…（退出码 0，非 Debug）
aapt2 dump badging                                 → versionCode='2' versionName='1.1'，无 debuggable
aapt2 dump badging | grep permission               → CONTROL + 自动合并的 PUBLISH_ACTIVITY
解包 classes.dex 搜标记串                            → 命中「忽略无主 send.result」「向电脑端请求删除」「/delete」
解包 assets/root/*                                 → module.prop service.sh uninstall.sh relay.sh listen.sh launcher.sh

# 电脑端（Python 3.9.12）
python -m unittest discover -s pc/tests -v          → Ran 55 tests … OK
node --check pc/island_hook.js                      → 无输出（通过）
python -m py_compile pc/{feed_parser,bridge_daemon,sse_server,server}.py → 通过

# DSH 插件（本机 node，真 js-yaml / 真 schemastery 3.18.4 从 app.asar 提取）
node --run test（pc/dsh-plugin）                    → 20 + 7 + 38 + 7 + 22 = 94 断言，0 失败
                                                    （第五套是**真加载器验证**：真 Cordis Context
                                                     + 真 WebServer 类，含真生命周期卸载）
```

## 五、未验证 / 剩余风险（**必须知情**）

1. **功能层面尚未真机复测**：release 包已装到真机（见第七节），但「豆包出卡片、第三方 App
   发 `com.islandbridge.SEND` 被拒、消息/删除全链路」这类**功能**验证还没做 ——
   需要先在 LSPosed 里启用本模块（当前 `modules_state.enabled=0`）、再点 App 内
   「安装 root 组件」，然后按 `SUBMIT.md` 第四节逐项跑。装机/签名/残留清理
   这几项**已经**在真机上验证过（第七节）。
2. **签名权限的防护依赖 OS**：`uid == -1`（系统不给发送方身份）时事件通道会放行 ——
   只影响卡片显示；命令通道仍有签名权限兜底。取舍写在 `SECURITY.md` R3。
3. **电脑端 8787 端口本身没有鉴权**：默认只绑 `127.0.0.1`；为了让手机连上而改成
   `0.0.0.0` 后，同网段任何设备都能读事件流并以你的名义调 `/reply`、`/delete`。
   本轮**未**给它加令牌（需手机端+电脑端同时改且无法在本机联调验证）。建议与替代方案见
   `SECURITY.md` R1。
4. **局域网全程明文 HTTP**（无 TLS），见 R2。
5. **DSH 插件没有在真实 Cordis Loader 里装过**：`dsh plugin add` 会改用户正在使用的
   profile，未执行。**但已收窄到只剩「Loader 读 `cordis.patch.yml` 那一层」**：
   `pc/dsh-plugin/test/real-loader-test.mjs` 从 asar 抽出真依赖闭包，把插件按真包布局
   装进 `node_modules`，由**真 `Context` + 真 `WebServer` 类** apply 并跑通
   鉴权/SSE/404/卸载（22/22）；真安装布局下 `SCHEMA_SOURCE` 确实是
   `@deepseek-ai/schemastery`，所以「Config 普通对象降级」不是常态路径。
6. **PC daemon 需要重启**才生效（当前机器上跑的还是旧代码）；手机要连电脑需在
   `pc/config.json` 写 `"bind": "0.0.0.0"`。
7. **`pc/tests` 依赖 `capture/` 抓包**：缺失时相关用例会 `skipTest`（不会假通过）。

## 六、建议你接着做的三件事

1. **离线备份 `android/keystore/`**（丢了就无法升级已安装的 App）。
2. 在真机上按 `SUBMIT.md` 第四节跑一遍（尤其：第三方 App 发 `com.islandbridge.SEND`
   应当**收不到**、`/data/adb/modules/islandbridge` 卸载后应当**清干净**）。
3. 定下「手机怎么连电脑」的形态（回环+隧道／两端一起加令牌），再决定要不要把
   `pc/sse_server.py` 的鉴权补上。

## 七、真机清理与安装记录（2026-10-04）

设备：**PJZ110 / Android 16（SDK 36）/ KernelSU root / LSPosed 1.9.x**。

### 7.1 清掉的 v1.0 残留（清理前它们**正在运行**）

| 对象 | 处理 | 复核 |
|---|---|---|
| `/data/adb/service.d/islandbridge.sh`（开机自启入口） | 删除 | 重启后未再出现 |
| `/data/adb/islandbridge/{launcher,listen,relay}.sh` | 删除 | 目录不存在 |
| PID 18661 `relay.sh`、20983 `listen.sh`、21007 `nc -lk -p 8799` | `kill -9` | 无相关进程 |
| **`[::]:8799` 监听**（当时对全网卡开放 —— 正是预审第 2 条的现场） | 随进程关闭 | `ss -tln` 无 8799 |
| `/data/local/tmp` 内 19 个调试残留（`ib_*.sh`、`islandbridge_relay.log`、`island_*.png` 等） | 删除 | 无匹配文件 |
| `/data/adb/modules/islandbridge` | 本来就不存在（v1.0 只用了 `service.d`） | — |
| `.zn_cleanup.sh`（**不是**本模块的） | **保留** | — |

> 关键点：v1.0 的 APK 里仍带 `assets/root/*`，**只要再打开旧版 App 就会把上面这套装回去**。
> 因此「卸载残留」必须和「卸载旧版 App」一起做 —— 已一并完成。

### 7.2 装的包

| 包 | 动作 | 复核 |
|---|---|---|
| `com.islandbridge` v1.0（versionCode 1 / DEBUGGABLE / debug 证书 `fd961a05`） | 卸载 | `pm list` 无 |
| `com.tgdsh.xingdao`（「星岛监工」v1.1.0，同一个 debug 证书的早期试验包） | 卸载 | `pm list` 无 |
| `com.islandbridge` v1.1 **release** | 安装 | `dumpsys`：versionCode 2 / versionName 1.1 / `flags` **无 DEBUGGABLE**；拉回 APK 校验 `CN=IslandBridge…`、证书 SHA-256 `738c3ae2…`、`CONTROL` 权限在、`assets/root` 6 个、362697 B（与仓库产物同尺寸） |
| `com.astraflow.tool`（星流）、`com.astraflow.fluidcloud`、`com.larus.nova`（豆包） | **未动** | 均在 |

### 7.3 `com.tugou.dsh`（旧包名的残留）

`dumpsys package com.tugou.dsh` → *Unable to find package*，`pm list packages -u` 也没有，
`/data/data/com.tugou.dsh` 不存在 —— **不是已安装包**，只剩两处痕迹：

1. **LSPosed 配置库里星流的一条偏好行**：`module_configs(module=com.astraflow.tool,
   user=0, group=island, key=source_enabled_com.tugou.dsh)` —— 也就是星河岛/星流
   「已启用岛源」列表里那个死条目。处理：备份 → `am force-stop 星流` → `kill lspd`
   → 离线 `DELETE` + `VACUUM` → 原 inode 覆盖写回（保持
   `system:system 600` 与 `u:object_r:adb_data_file:s0`）→ 重启。
   复核：`module_configs` 337 → **336** 行、`integrity_check=ok`、
   `journal_mode=wal` 不变、文件内已无 `tugou` 字节、星流其余 23 个 `island` 组偏好键全部保留、
   重启后 `lspd` 正常打开该库（`lsof` 指向 192512 B 的新文件）。
2. `/data/system/packages.xml` 里的历史记录。Android 16 上该文件是 **ABX 二进制格式**，
   属系统托管数据，**未手改**（手改有搞坏 PackageManager 的风险；系统会在需要时自行清理）。


