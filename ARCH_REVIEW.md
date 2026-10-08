# 架构复盘与提升路线（基于实物证据）

> 本文只记录**有证据支撑**的结论。证据分级：
> - **实测** = 真机 logcat / dumpsys / 落盘文件里直接读到的
> - **静态** = APK 或 framework 里 DEX 解析出来的
> - **代码** = 当前仓库源码里读到的控制流
> - **待验** = 推断，尚未在真机确认（**不得当作已解决**）
>
> 设备取证时（本次撰写）真机 `d666858b` 处于离线状态，因此本文没有新增真机证据，
> 新发现全部来自代码链路核对。

---

## 一、GPT 逆向为什么成功：方法论拆解

### 1. 结论先摆：成功不在"工具强"，在**证据链闭合**

关键产出是一条可验证的静态事实（**静态**，`dev/doubao_apk_reverse/receive_end_dump.txt`）：

```text
METHOD onReceiveEnd virtual access 0x11 proto V (Ljava/lang/String;, Ljava/lang/String;) code 0x5ce684
  regs 4 ins 3 outs 3 units 21
  0000: op=1a string='replyMsgId'
  0002: op=71 call=Lcom/larus/karp.../r5;->a V (...)
  0005: op=1a string='endMsg'
  0007: op=71 call=Lcom/larus/karp.../r5;->a V (...)

METHOD onReceiveEnd$lambda$17 direct access 0x1a proto Lkotlin/Unit; (...)
```

这条证据同时回答了四个问题，而**四个都必须答对**才敢挂钩子：

| 问题 | 证据 | 判据意义 |
|---|---|---|
| 这个类真实存在吗 | 在 `classes24.dex` 的 class_defs 里命中 `Lcom/larus/im/internal/jni/observer/OmniMessageDispatcher;` | 不是猜的类名 |
| 结束方法叫什么、什么签名 | `onReceiveEnd` **virtual, acc=0x11, proto `V (String, String)`** | 参数个数/类型确定，Hook 不会挂错重载 |
| 参数语义是什么 | 字节码里 `const-string 'replyMsgId'` / `'endMsg'` | 参数**顺序**有据可依，不用试 |
| 哪个同名方法**不是**结束锚点 | `onReceiveEnd$lambda$17` 是 **direct, acc=0x1a, 返回 `Lkotlin/Unit;`** | 编译器生成的 lambda，**排除**掉，避免误当终态 |

### 2. 五个可复用的手法

**(a) 对实物逆向，不对记忆逆向。**
拉的是设备上真正在跑的包（**实测** `base.apk`，414,073,339 字节，versionName `15.1.0`，
versionCode `15010040`）。版本一旦对上，静态结论和运行时日志才能互相印证。

**(b) 不做全量反编译，做定点提取。**
环境里没有 jadx/apktool/androguard，于是写了 ~50 行脚本
（`dev/doubao_apk_reverse/dexmethods.py`、`dexdump_dispatcher.py`），
只回答一个问题："`OmniMessageDispatcher` 的真实方法表长什么样"。
手段是标准的 DEX 结构读取：

```text
头部固定偏移  0x38 string_ids / 0x40 type_ids / 0x48 proto_ids
             0x58 method_ids / 0x60 class_defs
ULEB128      解码 class_data_item 的 method 增量索引
筛选         class_defs[i].class_idx 反查 type → 字符串，命中目标类才展开
```

约束反而成了优势：脚本小、可复现、结论可被别人重跑验证。

**(c) 混淆杀不死"协议常量"。**
`Lcom/larus/karp.../r5;->a` 这种被混淆的调用目标毫无信息量，但
`replyMsgId` / `endMsg` / 通知 channel id 这类**线上协议常量几乎不会被混淆**，
因为改它们就改了跨端契约。**这是整套方法里最值钱的一条**：锚点选常量，不选名字。

**(d) 静态结论必须被运行时证据复核。**
静态给出签名 → 挂钩子打参数 → 真机 logcat 出现
`explicit stream end <name> reply=<tail6> end=<tail6>` → 与
`API_DOC.md:84-97`、`pc/tests/README.md:84-91` 的协议终态语义对齐。
静态 + 动态 + 协议文档三方一致，才算"锚点确认"。

**(e) 观测版必须只读。**
通知探针那一版（`installNotificationProbe`）只打日志、不改任何判定。
这是**测量有效性的前提**：改了行为的版本，测出来的现象无法归因。

### 3. 失败与教训（这部分更值钱）

| 教训 | 具体事实 | 后果 |
|---|---|---|
| 启发式当锚点 | 早期用"messageId 变化即结束"、"方法名匹配 `*Finish*` / `*lambda*`" | 提前结束、末尾被截断 |
| 自己写去重吃掉了结束事件 | `emitShared` 对已在 SSE 见过的 mid 一律 `return`，把 `chat.end` / `chat.reply` 一起丢了 | **文字全到、卡在"回答进行"**（CHANGELOG 第 59 条） |
| 猜测冒充证据 | `final snapshot candidate → finishOmni` 是启发式，不是锚点 | 需要后续再修 |
| 未验证就宣称完成 | 用户明确纠正过"编译通过 ≠ 真机通过" | 浪费真机往返 |
| 日志即瓶颈 | 每条事件同步 `XposedBridge.log(整段 JSON)` | 长回答时 I/O 拖慢监听线程（CHANGELOG 第 63 条） |

**教训归纳成一条纪律**：任何"结束 / 丢弃 / 合并"的判定，都必须能说清
**它依据哪个不会变的常量或哪个不可被伪造的调用点**；说不清就只能做成
"候选 + 表决"，不能做成"唯一判据"。

---

## 二、现在的问题（按证据分级）

### P0 · 上游进程被 ColorOS 冻结（一切症状的总源头）

> ✅ **已修复并真机验证**（本文撰写后的补充，细节见 [`FREEZE_FIX.md`](FREEZE_FIX.md) 第五节）：
> 做法是"改变它的决策"——在 system_server 侧拦下 HANS 的冻结**执行点**
> `HansCGroup.hansFreezeLocked`（两个重载），对目标 uid 在有界豁免窗口内返回"没冻成"。
> 定位靠按日志文案反查（`dev/coloros_framework/dexfindstr.py`），**猜路径的三轮全部落空**。
> 真机结果：豆包全程后台 75s，`freeze uid: 10375` **0 行**（改前每 75s 2–3 行）；
> 产品路径完整一轮 `chat.start` → `chat.delta` → `chat.end` 全部到达。
> 边界：属**拦截**而非厂商白名单（`addHansKeepAliveApp` 实测无效），系统 OTA 后需重验。
> 下面保留**修复前**的原始取证，作为判据与回归基线。

**实测**（logcat）：

```text
OplusHansManager : freeze uid: 10375 com.larus.nova pids: [16491, 25511, 16621] scene: LcdOn
OplusHansManager : unfreeze uid: 10375 ... reason: Packet scene: LcdOn
LocationFreezeProc: Executing freeze operation for com.larus.nova
OplusBinderProxy  : proxyBinder uid: 10375 ... calling: OFreezer
```

**实测**（`FREEZE_FIX.md` 第 34-42 行，手段全试过，全部无效）：

| 手段 | 结果 |
|---|---|
| `cmd deviceidle whitelist +` | ❌ 仍 `freeze=1` |
| `am set-standby-bucket active` | ❌ |
| `cmd appops set RUN_ANY_IN_BACKGROUND allow` | ❌ |
| `oom_score_adj = -1000` | ❌ |
| 已在 `not_restrict.xml` 白名单 | ❌ |
| **Binder 事务** | ✅ `freeze=1` → `freeze=0`，约 1.1s |
| **Activity start** | ✅ |
| 广播 | ❌ `DEFER_BY_OPLUS`，且解冻后**不补发** |

**推论**：这个冻结器不读 AOSP 的优先级字段，只认它自己的策略。
"提升优先级"这条路线是死的（已证伪），必须走**改变它的决策**或**不依赖它**。

### P1 · 结束判定：静默兜底与结束事件**共享同一失效域**

> 🔴 **新增证据（真机逐帧日志）—— 同一段回答被两条通道抱走，id 不一致**
>
> ```text
> 21:00:54.074  ev[sse]  t=chat.delta mid=19009538 cid=30022402 text=10
> 21:00:54.096  ev[omni] t=chat.delta mid=ending#0 cid=30022402 text=28   ← 占位号
> 21:01:00.015  ev[omni] t=chat.end   mid=18998018 cid=30022402
> 21:01:00.015  ev[omni] t=chat.reply mid=18998018 cid=30022402 text=1240
> ```
>
> `sse`（HTTP chunk 路径，`MobileFeedParser.feed`）拿到了真实消息号；
> `omni`（`OmniMessageDispatcher` 路径）**始终没有**，于是整轮挂着占位号开出去。
> 收尾的 `chat.end` 又是第三个 id。后果：会话文件里同一段正文分裂成
> 真实号那条 `ended=false` + 占位号那条 `ended=true`，冷启动重建历史时前者就是
> "回答已结束却仍显示回答进行"。
>
> **因此 P1 真正的根因不只是"共享失效域"，还有"双摄取通道 + id 不一致"。**
> 已落地的缓解（不共用 `m0`、本轮唯一 `pending#<n>`、迁移时给占位轮补 `chat.end`）
> 让用户可见结果恢复正常（岛卡状态切换、结束记录正文完整），
> 但**记录分裂没有根治**。下一步：做事源归并 —— 优先保留有真实 mid 的那条通道，
> 或在 App 侧按 `cid` 合并同轮记录。

**实测**：多轮落盘记录 `ended:false`；没有 `finish omni` 日志；
上一轮真机最终出现 `st=回答结束`，是靠新增的 4 秒静默兜底。

**代码**：静默兜底是 `MobileFeedParser` 里的 `Handler(Looper.getMainLooper())`
延时任务，**跑在豆包进程内**。

**这是设计缺陷**：进程被冻结时，`onReceiveEnd` 不会回调，
**而基于同一个 Handler 的 4 秒兜底同样不会触发**（冻结连定时器一起停）。
所以静默兜底与它要替代的机制**同生共死**，它治不了 P0 导致的场景，
只是让"能跑但没发结束事件"这种情况有了出口——**仍是有价值的，但层级错了**。

### P2 · 投递吞吐：主线程在**每一帧**的关键路径上（本次新发现）

**代码**（`EventProvider.kt:169-183` + `IslandBridge.kt:749-755`）：

```text
豆包事件泵线程
  → contentResolver.call()                        ← 同步 Binder
  → App Binder 线程: deliver()
  → main.post { EventSink.submit(json) }          ← 转到主线程
  → latch.await(2000ms)                           ← Binder 线程等最多 2 秒
  → 主线程: dispatch()
      → BridgeService.start(app)                  ← 每帧一次（失败后 60s 节流）
      → app.handleEvent(o)
          → BridgeHub.handle(o)                   ← 落盘 + UI 状态
          → bridge.handle(o)
              → BridgeService.activity(ctx)        ← ★ 每帧一次，且【无节流】
                  → startForegroundService()      ← 每帧一次 AMS 调用
```

三个叠加后果：

1. **吞吐上限由主线程决定，不由网络决定**。主线程若正忙于卡片重建（岛每次
   `startOrUpdate` 都要重新 inflate），事件就排队。
2. **每帧一次 `startForegroundService`（`BridgeService.activity` 无节流）**。
   一轮长回答可能有几百帧 → 几百次 AMS 往返，直接对应"卡顿 + 高耗电"。
3. **2 秒 latch 是硬等待**。单帧最坏 2s，而豆包侧事件泵是**串行**的
   → 最坏吞吐 < 0.5 帧/秒，而豆包能产 10~40 帧/秒 → 必然积压。

**这解释了用户描述的确切症状**：

```text
冻结期间：事件进不来（P0）
解冻瞬间：网络侧积压的帧一次性到达（"突然给一大串"）
投递侧：主线程 + 每帧 AMS 调用来不及消化 → 队列长满
        → drop-oldest（第 63 条新增）→ 文字被丢 → 观感"卡住"
```

**注意**：第 63 条的异步泵只解决了"豆包**监听线程**被阻塞"，
**没有解决"App 侧吞吐不足"**，因此 P2 依然存在。而且第 63 条引入的
**drop-oldest 对文本 delta 是危险的**——文本增量可以无损合并，不该丢弃。

### P3 · 状态栏通知锚点：**结论是不确定，不是否定**

**实测**（`dumpsys notification`）：`com.larus.nova` 有
`numEnqueuedByApp=4 / numPostedByApp=4 / numRemovedByApp=4`，
channel 有 `IM`、`call_channel`、`push`、`notification_id`、`Silent PUSH`。
→ 豆包**确实**提交过状态栏通知。

但 `notifyProbe` / `notification probe installed` 两行日志**都没有出现**。
不能据此说"豆包没走 Java `NotificationManager`"，因为至少还有两种解释：

1. **logcat 环形缓冲被冲掉**：那次 `logcat -d` 里混入了巨量无关刷屏
   （metis/场景 dump），探针那几行很可能已被挤出。
2. **探针没装到发通知的那个进程**：探针装在 `hookNova` 里，
   而通知可能由 `:push` 或另一个进程发。

**这条锚点值得继续追**，因为它有两层价值：
既是"回答完成"的**独立第二证据**，也可能是**最终全文的补偿来源**
（若通知带 `EXTRA_BIG_TEXT` / `MessagingStyle`，冻结期间丢掉的整段正文有机会补回）。

### P4 · 容器与显示（两行 / `...`）

**代码**：容器按 `ib_lyric.txt` 第 1 行容量（默认 10 字）切片投递，
`chat.end` 时强制 flush 余量。逻辑闭环。

**待验**：真机观感"两行 / `...`"。已知宿主约束是
MessageCard 正文上限 4096 UTF-16，且 MessageCard 会因"新消息规则"让位给副岛。
**"两行"和"`...`"很可能是宿主渲染/截断，不是我们切错**——
但这必须**测量**（记录我们投出去的字符串长度 vs 岛上实际可见字符数），
不能靠猜。

### P5 · 功耗

**代码**：`KA_PING_MS=3s`、`REPLY_STALL_MS=5s`、静默收尾 `4s`、
歌词 tick `1s`、外加**每帧一次** `startForegroundService`（P2）。
这些在**空闲时也在跑**，且每次 ping 都是一次 Binder 事务（= 一次解冻）。

---

## 三、可落地的提升方案

按"收益 / 风险"排序。**A、B 收益最大且不依赖 framework 取证。**

### A. 把"结束"的权威判定搬到**不会被冻结**的一侧 ★最高价值

现状：判定器住在豆包进程（会被冻结）。
改造：App 侧（有前台服务，且每次 Binder 都被系统解冻）维护每会话的
`lastFrameAt`；超时即在 App 侧生成 `ended=true`。

```text
模块（豆包进程）          只负责：把每帧 + 单调时间戳转发出去（纯传递）
App（不可长期冻结）        负责：lastFrameAt、静默判定、容器 flush、结束态投递
system_server / root      可选：第三重兜底心跳
```

这样 P1 的"共享失效域"被彻底打破：即使豆包被冻 30 秒，
App 侧仍能正确判定"这一轮停滞了"，并给出**准确的状态**（不是假结束）。
判定语义要区分两种，别混：

```text
chat.end（真结束）    = 有权威证据（见 F）
chat.stalled（停滞）  = 无证据但没有新帧 → 状态显示"内容已暂停"，不等于结束
```

**当前代码把两者混成了"4 秒后直接结束"，这是把不确定性伪装成确定性**，
应恢复区分：静默只能产出 `stalled`，`ended` 必须有证据。

### B. 合帧 + 背压 + **无损合并**（解决 P2 与"卡住"）★最高价值

三件事一起做：

1. **合帧（coalescing）**：事件泵把 120ms 内的连续 `chat.delta`
   （同一 `mid`）**合并成一帧**再投递。文本增量是"增长型"的，
   合并 = 拼接新尾巴，**零信息损失**，Binder 事务数下降 1~2 个数量级。
2. **无损背压**：队列满时**不允许 drop 文本 delta**——
   `chat.delta` 应与下一条同 mid 的 delta **合并**（append）。
   只有 `chat.start` / `chat.end` / `chat.reply` 这类状态帧才允许
   "绝不丢、队首插队"。当前 drop-oldest 会静默丢字，必须改。
3. **去主线程化**：`EventProvider.call` 收到即入 App 侧队列后**立即返回**
   （不要 latch 等主线程）；`BridgeService.activity()` 加节流
   （例如同一会话活动窗口内最多 1 次 / 15s，或完全改由 `chat.start` 触发一次）。

### C. 冻结豁免：**只在"有进行中回答"期间**动态豁免 ★根治 P0

两个层次，先易后难：

**C1（AOSP 层，先试）**：`system_server` 里对 uid 10375 设置
`ProcessCachedOptimizerRecord.setFreezeExempt(true)` /
`CachedAppOptimizer` 的冻结判定。这是 AOSP 语义内的"豁免"，最正规。

**C2（厂商层，必要时）**：设备上是 `OplusHansManager` / `OFreezer`（**实测**标签）。
我们已经**已经注入 system_server**（`hookSystem`，`DoubaoHookEntry.kt:1218`），
只要拿到它的冻结决策方法，就能对 uid 10375 直接放行。

拿方法的路径**复用我们已验证的逆向工具链**（不再是猜）：

```text
1. 从设备拉 framework 包
   /system/framework/oplus-services*.jar
   /system_ext/framework/*.jar
   /my_product/framework/*.jar
2. 用 dexmethods.py 同一套 DEX 解析（jar 内是 dex）扫字符串池
   锚点：'OplusHansManager' / 'OFreezer' / 'freeze' / 'unfreeze' / 'Hans'
3. 命中类 → 打印 class_data_item 的方法表 + proto
   （判据同第 1 节：access flags + proto + 字节码里的字段/常量引用）
4. 只 hook 决策点，不改状态机；先打日志验证"这里确实是决策点"
```

**设计要点（省电与安全的平衡）**：
**不要永久豁免**。只在"这一轮回答进行中"豁免，`chat.end` 后**立即撤销**。
这样豆包平时仍被正常冻结，功耗回到系统默认；
只在真正需要连续推流的窗口内保持活跃。这也顺带缓解 P5。

**C3（root 兜底，最后手段）**：KernelSU `/data/adb/service.d` 下放一个
uid 0 的看门狗（uid 0 永不被冻），周期把目标 pid 的
cgroup 冻结位写回 0。`FREEZE_FIX.md` 已实测
`echo 0 > cgroup.freeze` 可行。**缺点**：轮询本身耗电、路径可能随版本变。
因此只当作 C1/C2 失败时的兜底，且间隔拉长（如 3~5s），**不与 A/B 争优先级**。

### D. system_server 通知锚点：次级证据 + **全文补偿** ★高价值

`NotificationManagerService.enqueueNotificationInternal` 是所有应用通知的
唯一入队口，在 system_server 里，**永不被冻结**。按 `pkg == com.larus.nova`
（或 uid 10375）+ channel 过滤即可。

两条用法：

1. **结束的第二证据**：同一会话出现"回答完成"通知 → 与流证据**表决**（见 F）。
2. **最终全文补偿**：若通知携带 `EXTRA_TEXT` / `EXTRA_BIG_TEXT` /
   `MessagingStyle`，则在冻结丢帧的情况下用它补齐正文——
   这直接覆盖"末尾丢失"和"整轮丢失"两种最坏情况。

**执行要点**（避免重蹈 P3 的误判）：
- 探针**同时装到豆包所有进程 + system_server**；
- 探针结果**落盘**（写文件）而不只打 logcat；
- 同时装一个**"已安装"自证记录**，区分"没装上"和"装上没触发"。

### E. 逆向工具链升级（从"能用"到"可复用"）

我们已经证明手工 DEX 解析可行，补两块能力就能覆盖后续所有定位需求：

1. **字符串锚点扫描**：全量扫 string pool，
   对 `replyMsgId` / `endMsg` / `IM` / `call_channel` 等**稳定常量**反查所属类。
   （混淆改不了协议常量——第 1 节(c)。）
2. **invoke 反查调用方（x-ref）**：现有 `dexdump_dispatcher.py` 已经有
   指令宽度表（`width` 字典），稍加改动即可扫描全部 `invoke-*`，
   找出**谁调用了 `onReceiveEnd`**。调用方极可能就是
   "判定回答结束 + 发通知"的那个类——**一次就把 P1 和 P3 同时定位**。

### F. 结束判定改为**多证据表决**

把"唯一判据"改成"投票 + 置信度"，并把"谁投的票"写进日志：

| 证据 | 强度 | 来源 |
|---|---|---|
| `onReceiveEnd(replyMsgId, endMsg)` | 强 | DEX 已验证（静态）+ 运行时 |
| `SSE_REPLY_END` 且 `end_type==1` 且 msgid 匹配 | 强 | `API_DOC.md:84-97` |
| `patch_value.ext.is_finish == "1"` | 中 | 同上 |
| `async_job.status == 2` | 中 | 同上 |
| 完成通知入队（channel 匹配） | 中 | 方案 D |
| 不可冻结侧静默超时 | 弱 | 方案 A |
| 裸 `msg_finish_attr` | **弱·不单独结束** | 已证伪其可靠性 |

规则：`任一强` 或 `≥2 个中` 才置 `ended=true`；只有弱证据 → `stalled`。
**每次结束都记一行"本轮由哪条证据结束"**，真机验收一眼可见，也防回归。

### G. 验证纪律自动化（针对"过度声称"）

把验收变成脚本断言，而不是靠自觉：

```text
每轮回答结束后自动校验：
1. chat/<cid>.jsonl 最后一条 role=bot 记录 ended == true
2. 落盘全文长度 == 流式累加长度（无丢字）
3. 岛日志出现 st=回答结束（且此前出现过回答进行）
4. 无 "event queue full" / dropped 计数为 0
5. 容器余量已 flush（末帧长度 ≤ 容量）
```

并在交付说明里**强制三态标注**：`编译通过` / `已安装` / `真机已验证`。

---

## 四、优先级与验收

| 阶段 | 内容 | 依赖 | 风险 |
|---|---|---|---|
| **P1 立即** | A 判定搬到 App 侧（恢复 `stalled` ≠ `ended`） | 无 | 低 |
| **P1 立即** | B 合帧 + 无损背压 + 去掉每帧 AMS 调用 | 无 | 低 |
| **P1 立即** | 探针自证 + 落盘，重跑通知观测澄清 P3 | 真机在线 | 低 |
| **P2 根治** | C1/C2 冻结豁免（动态、仅回答期间） | framework DEX 取证 | 中 |
| **P2 高价值** | D 通知锚点（第二证据 + 全文补偿） | 真机在线 | 中 |
| **P3 质量** | E 工具链升级（字符串锚点 + x-ref 反查） | 无 | 低 |
| **P3 质量** | F 表决 + G 自动验收 | 无 | 低 |

**A + B 是本次分析里最该先做的两件事**：它们不依赖任何新的逆向成果，
就能同时改善"卡住"、"丢字"、"耗电"三个用户直接感知的问题，
并且把"结束判定"从错误的层级搬到正确的层级。

---

## 五、诚实的边界

1. **网络检索不可用**：本次会话的 web 搜索未配置密钥，因此
   关于"新生代 LSPosed 模块手艺与开源项目"的部分，我只能依据既有知识给出
   **能力分类**（运行时 DEX 查询如 DexKit 一类、Kotlin DSL 模块框架如
   YukiHookAPI 一类、类型安全反射/Hook 辅助如 KavaRef 一类、
   ART Hook 引擎如 LSPlant/Pine 一类、免 root 特权通道如 Shizuku 一类），
   **未能核对它们的当前版本、API 与可用性**。落地前应在本机核实后采用，
   不应直接照搬本文措辞。本项目的实际约束（已有 KernelSU root + LSPosed）
   决定了其中最相关的只有"运行时 DEX 查询"和"ART Hook 引擎"两类。
2. **设备离线**：`adb devices` 为空，本次**没有新增真机验证**。
   上文所有"实测"均引自既有日志/文档，未重新复现。
3. **P3 是不确定，不是否定**：通知探针未打日志这件事，
   目前无法区分"没装上"、"装上没触发"和"日志被冲掉"。
4. **C2 尚未取证**：`OplusHansManager` 的确切类名/方法名**还没有**从
   framework 里解析出来，方案 C2 的可行性尚未验证。
5. **本文不含任何网络传输设计**：D 方案的通知内容恢复是**本机进程内**读取，
   不引入 `INTERNET`、不恢复 PC 中继。
