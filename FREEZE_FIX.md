# 冻结与投递问题：根因与修复（真机验证）

> ⚠️ **历史文档（v1.2 起结论仍有效，但包名与部分组件已变）**
> 本文里的 `com.islandbridge` 是**当时的包名**，v1.2 第 35 条已改为 `com.tg.dbisland`
> （含 `com.islandbridge.events` → `com.tg.dbisland.events`、
> `com.islandbridge.BridgeEventReceiver` → `com.tg.dbisland.BridgeEventReceiver`）。
> 另外第 34 条删除了电脑端与整套 root 中继组件，文末提到的
> `/data/data/…/files/ib_listen.conf`、`listen.sh` 局域网监听已不存在。
> 为不改写当时的实测证据，正文保留旧写法。
> 详见 [`CHANGELOG.md`](CHANGELOG.md) 第 34、35 条。

设备：OnePlus 13 / ColorOS，KernelSU 4.2.0 + Zygisk LSPosed
包名：`com.islandbridge`（uid 10370）  ·  目标：`com.larus.nova`（豆包）  ·  岛：`com.astraflow.tool` 注入在 `com.android.systemui`

---

## 一、根因：不是"保活没做到"，是**信道错了**

最初的现象是"App 一离开前台就收不到消息"。定位过程推翻了两个常见假设。

### 1. 微信也是冻结的 —— 保活不是关键

```
pid 16334  do_freezer_trap  com.tencent.mm
pid 19290  do_freezer_trap  com.tencent.mm:push
```

微信同样挂在 `do_freezer_trap` 上，memory cgroup 也和我们一样。**所以问题不是"活着"，而是"活着的时候消息能不能进来"。**

### 2. 前台服务 + 最高优先级都无效

实测本 App 已经满足：`mHasForegroundServices=true`、前台通知 id=42、`oom adj: max=1001 curRaw=200`。把优先级手段全部拉满后**依然冻结**：

| 手段 | 结果 |
|---|---|
| `cmd deviceidle whitelist +` | ❌ 仍 `freeze=1` |
| `am set-standby-bucket active` | ❌ 仍 `freeze=1` |
| `cmd appops set RUN_ANY_IN_BACKGROUND allow` | ❌ 仍 `freeze=1` |
| `oom_score_adj = -1000` | ❌ 仍 `freeze=1` |
| 已在 `not_restrict.xml` 白名单内 | ❌ 仍 `freeze=1` |

ColorOS 的 `oplus_sys_hans` 不读这些。**"设最高优先级"这条路是死的。**

### 3. 真正的元凶：广播被 `DEFER_BY_OPLUS` 且解冻后**不补发**

```
9030:com.islandbridge/u0a370 not runnable because DEFER_BY_OPLUS SUB_REASON: FROZEN
DEFERRED for manifest com.islandbridge.BridgeEventReceiver   ×14
LOG: BroadcastQueue: setDeferPolicy processName: {com.islandbridge} uid: 10370 policy: 1 defer: true
```

信道实测（全部在**强制冻结**状态下投递）：

| 信道 | 冻结 → 投递后 | 能否穿透 |
|---|---|---|
| **广播**（原实现） | `freeze=1` 仍 `do_freezer_trap` | ❌ |
| `startService` | `freeze=1` | ❌ |
| `start-foreground-service` | `freeze=1` | ❌ |
| **Binder（跨进程调用）** | `freeze=1` → **`freeze=0`** | ✅ |
| **Activity start** | `freeze=1` → **`freeze=0`** | ✅ |

而且**解冻后积压的广播不会补发**——手动 `echo 0 > cgroup.freeze` 解冻，`deferred` 计数 6→6 不动，receiver 一条日志都没有。

> 对应 AOSP 常量：广播走 `UNFREEZE_REASON_START_RECEIVER` 路径被 OPLUS 拦截；
> Binder 走 `UNFREEZE_REASON_BINDER_TXNS` / `_GET_PROVIDER`，会主动解冻。

**结论：把广播换成 Binder 事务。**

---

## 二、修复

### 1. 新增 Binder 投递端点（核心修复）

`app/src/main/java/com/islandbridge/EventProvider.kt` —— 一个导出的 `ContentProvider`，用 `call()` 接收事件：

```
content://com.islandbridge.events --method event --extra evb:s:<base64 json>
```

- **uid 白名单**：只放行 root(0) / system_server(1000) / shell(2000) / 自己 / 豆包(10375)，其余抛 `SecurityException`。
- 实测穿透效果：强制冻结 → 一次 `content call` → `freeze=1 wchan=do_freezer_trap` 变成 `freeze=0 wchan=do_epoll_wait`，**约 1.1 秒**。

`AndroidManifest.xml` 注册 provider。

### 2. 投递顺序：Binder 优先，广播兜底

`DoubaoHookEntry.sendTo()` / `relayToApp()`：先 `callProvider()`，成功即返回；失败才退回旧的广播路径（保证老版本 APK 不丢事件）。system_server 侧中继也改走 Binder。

### 3. 冷启动竞态修复（否则第一帧必丢）

冷启时 `ContentProvider` 会**先于** `Application.onCreate` 被实例化并可调用，此时 `BridgeApp.instance` 还是 null，第一帧被静默丢弃。

`EventSink` 因此分两级缓冲：

- **pre-init 缓冲**：`BridgeApp.instance` 未就绪时先入队，`BridgeApp.onCreate` 末尾 `onAppReady()` 回放。
- **island 未就绪缓冲**：进程起来了但岛会话还在 `WAITING` 时暂存，`onReadyChanged` 后补发（原来直接 `已丢弃`）。

同时 `deliver()` 在 `!ready` 时**直接从 binder 线程入队**，避免"post 到主线程再等 2 秒"在冷启时死锁并假报 miss。

修复前后日志对比：

```
修复前: ev ok plan.start → 岛未就绪(WAITING)，已丢弃 plan      ← 卡片不出现
修复后: ev ok plan.start → 岛未就绪(WAITING)，暂存 plan
        replayed 1 pre-init → 岛就绪，补发 1 条                ← 卡片出现
```

### 4. root 常驻运行时（因为 App 自己会被冻结）

App 自身被冻结时循环会停，所以监听/搬运必须放在 **uid 0（永不被冻结）**。规范布局：

```
/data/adb/service.d/islandbridge.sh     ← service.d 里唯一的 .sh（KernelSU 会执行目录下每一个 .sh）
/data/adb/islandbridge/relay.sh         ← 队列 → Binder 搬运
/data/adb/islandbridge/listen.sh        ← LAN 监听
/data/adb/islandbridge/handler.sh       ← 每连接处理器（由 listen.sh 生成）
```

`BridgeService.ensureRootRelay()` 只做 bootstrap：把 assets 里的脚本按内容差异刷新到上述路径，再调 launcher（launcher 幂等，不会重复拉起）。

踩过的坑，均已修复：

| 问题 | 现象 | 修复 |
|---|---|---|
| `service.d` 执行**所有** `.sh` | relay/listen 各被拉起两次 | worker 移出 `service.d`，只留一个 launcher |
| service.d 的 PATH 没有 coreutils | `/system/bin/sleep: No such file or directory`，两个 worker 死循环刷日志 | launcher 与 worker 均 `export PATH=/data/adb/ksu/bin:...` |
| service.d 启动过早 | `/data/data/<pkg>: nonexistent directory` | `wait_ready()` 等 `sys.boot_completed=1` 且两个包目录出现 |
| launcher 并发竞态 | boot 与 App 同时调用 → 重复进程 | `mkdir` 原子锁 + 30s 过期打破 |

### 5. LAN 监听（"监听配置的 IP 发过来的内容"）

配置 `/data/data/com.islandbridge/files/ib_listen.conf`：

```sh
BIND=0.0.0.0
PORT=8799
TOKEN=zqtok123
```

线协议（一行一个事件）：

```
<TOKEN>\t<JSON>\n        设置 TOKEN 时
<JSON>\n                 TOKEN 为空时
```

实测：错误 token 被丢弃（`dropped: bad token`），正确 token 通过（`tcp->binder ok`）。

**关键坑**：普通 `nc -l -p` **只接受一个连接就退出**，循环重启之间有约 1 秒空窗——这个空窗实测丢掉了 10 条连续 delta 中的 9 条（只有相隔数秒的 `chat.start`/`chat.end` 幸存）。改用 BusyBox 的**持久分支式服务器** `nc -lk -p PORT -e handler`（每连接 fork、持续监听）后 **6/6、10/10 全中**。

---

## 三、验证结果

### A. 合成流（App 进程已杀死）

```
app dead: pid=[]
send: chat.start, 10×chat.delta, chat.end
→ chat.start : 1
→ chat.delta : 10 / 10      ← 修复前为 0/10
→ chat.end   : 1
岛胶囊宽度: 467px → 1142px
```

### B. 真实豆包（决定性验证）

App 未运行（`pid=[]`），在豆包里实际发一条消息：

```
t=3s   app_pid=[]      pill=467px        ← 还没醒
t=6s   app_pid=29904   pill=1138px       ← 被真实豆包事件唤醒，卡片出现
t=18s  freeze=1        pill=1138px       ← 重新冻结，卡片仍在
```

App 侧流水：

```
ev ok chat.delta 56752752653466882 mobile=true   ×4
ev ok chat.end   56752752653466882 mobile=true
ev ok chat.reply 56752752653466882 mobile=true
```

**真实豆包流式回复 → 穿透冻结 → 岛渲染成功。**

### C. 冻结不会丢岛会话

连续冻结 20 秒后 `onSessionLost` 计数 = **0**，pid 稳定。卡片能持续更新、按钮回调通道不受影响。

### D. 像素级证据

同屏 A/B（基线 vs 卡片）：
`differingPixels = 94599`，**100% 集中在 y=0–396**（岛胶囊区域），画面其余部分零差异。

---

## 四、仍未完成

1. **PC ↔ 手机 SSE 链路未通** —— 手机侧没有到 `10.0.0.197:8787` 的 ESTAB 连接，`pc/` 那套（`bridge_daemon.py` / `sse_server.py`）目前是空转的。现在有了 LAN 监听，PC 侧可以直接往 8799 灌 JSON，SSE 那条路可以考虑退役。
2. **系统任务身份未做伪装** —— 用户原目标里的"让 ColorOS 认为我们是系统任务"，当前是用**框架层绕过**（Binder + root 常驻）达成的等效效果，并未真的伪装成系统进程。真伪装需要动 system_server 的冻结/延迟决策（`setDeferPolicy`、`ProcessCachedOptimizerRecord.setFreezeExempt`），未实施。
   > **v1.2 更新**：这一条已经**部分落地**了 —— 见下面第五节，现在是在 system_server 里
   > 直接拦下 HANS 的冻结执行点（`HansCGroup.hansFreezeLocked`），属于"动 system_server 的冻结决策"，
   > 但仍是**拦截**而非"伪装成系统进程"。

3. **豆包 `OmniHttpCallByNative` 的 SSE 路径** 未在真实长回复中单独验证（本次走的是 `OmniMessageDispatcher`）。
4. 发送方向（岛 → 豆包）的 `chat.reply` 回写只在结构上闭环，未做真机交互验证。

---

## 五、v1.2 补充：在 system_server 里拦下 HANS 冻结的执行点（真机已验证）

> 包名等已按文首说明变化（`com.tg.dbisland`）。

**与第一节的区别**：第一节解决的是"**我们自己的 App** 收不到事件"（换 Binder 事务）。
本节解决的是**豆包自己被冻住**：

```
OplusHansManager : freeze uid: 10375 com.larus.nova pids: [18616, 22793, 22926] scene: |StrictMode-3|LcdOn
```

豆包一进后台就被 HANS 冻住 → 进程内没有 Omni/SSE 回调 → 内容停住；
解冻瞬间积压的帧一次性涌出，在 App 侧表现为**"突然给一大串然后卡住"**，
以及结束事件迟迟不到导致的**"回答结束却仍显示回答进行"**。

### 1. 前面的做法为什么全部失败

- 第一节的 ❌ 表格（`deviceidle whitelist` / `set-standby-bucket active` /
  `RUN_ANY_IN_BACKGROUND allow` / `oom_score_adj=-1000` / `not_restrict.xml`）
  在 v1.2 上**依然全部无效**：HANS 不读 AOSP 那些字段。
- 随后**猜实现路径**共三轮，全部落空（钩子装上了，真机上一次都没触发）：
  拦 `OplusHansProcessFreeze.freezeProcess` → 拦 `OplusHansManager.hansFreeze`
  → 拦 `HansAppStateMachine.forceEnterFrozen`。
- 也试过厂商自带的保活名单 API `OplusHansManager.addHansKeepAliveApp(uid, pkg)`：
  调用**没有报错但也没有阻止**这条 StrictMode 路径的冻结。

### 2. 定位方法：按日志文案反查方法（可复现）

不再猜，用新增的 `dev/coloros_framework/dexfindstr.py`：在 `oplus-services.jar` 的常量池里
定位 `'freeze uid: '` 的下标，再扫描每个 `code_item` 的原始字节，找引用该下标的
`const-string`（format 21c，即 `1a AA | BBBB`）。**结果唯一**：

```
常量池命中: [53084] 'cgroup failed, freeze uid: '  [61771] 'freeze uid: '
HIT Lcom/android/server/hans/freeze/HansCGroup;->hansFreezeLocked  code=0x4f5498
    （方法体内写 '/dev/freezer/frozen/cgroup.procs'）
```

结论：无论上层走 StrictMode / cached / fast freezer 哪条**决策**路径，
最后都必须经过 `HansCGroup.hansFreezeLocked` 才能冻住某个 uid —— 这才是汇聚点，
前面那三个都只是其中某几条路径的支路。

### 3. 实现（`DoubaoHookEntry.kt` 的 HANS 段）

- 在 system_server 里解析目标 uid 清单：**豆包 + 本 App 自己**
  （App 被冻时它自己的静默判定定时器也会停）。
- 挂 `HansCGroup.hansFreezeLocked` 的**两个重载**：
  `(int uid, String, String)` 与 `(OplusHansPackage, String)`
  —— 后者用"对象图里字段值等于目标 uid"来识别，**不写死厂商字段名**
  （实测这类超大类的字段表解析并不可靠）。
- 命中目标 uid 且处于**豁免窗口**内 → `result = false`（如实返回"没冻成"）。
- 豁免窗口由 App 侧在 `chat.start` / `chat.delta` / `chat.end` 广播续期
  （60s，上限 180s）；**不在轮次里时窗口自然过期**，不常驻。
- 兜底：窗口内每 1.5s 调一次 `hansUnFreeze(uid, pkg, reason)`（实测可用），
  拦漏了也能很快解冻。
- 纵深防御：另外保留 `OplusHansProcessFreeze.freezeProcess` /
  `OplusHansManager.hansFreeze` / `HansAppStateMachine.forceEnterFrozen` 三处拦截
  （真机上不是必经点，但无副作用）。

### 4. 真机证据

关豆包到后台持续 75s：

| 信号 | 改前 | 改后 |
|---|---|---|
| `OplusHansManager : freeze uid: 10375` | 每 75s 2–3 行 | **0 行** |
| 模块 `HANS 已拦下冻结 … via=HansCGroup.hansFreezeLocked(pkg)` | — | 有 |
| 模块 `hans thaw … → true`（表示确有进程被冻） | — | **0 行** |

产品路径（App 自动续期，**不是**手动 `am broadcast`）完整一轮：

```
20:53:25  HANS 豁免窗口 60s why=chat.start    uids=10375,10045
20:53:35  HANS 豁免窗口 60s why=chat.delta
20:53:49  HANS 豁免窗口 60s why=chat.end
期间 freeze uid: 10375 = 0 次，unfreeze uid: 10375 = 0 次
```

**豆包全程在后台**，回答照样流式投递到 `chat.end`；会话文件
`files/chat/716712230022402.jsonl` 的结束记录 `ended=true`、1449 字（含末尾余量，
字面 `...` 0 次）；岛卡 `card=generic st=回答进行` → `card=msg st=回答结束`。

### 5. 已知边界（不要当成"永久解决"）

1. 这是**绕过**厂商冻结决策，不是加入厂商白名单；`addHansKeepAliveApp` 实测无效（见上）。
2. 依赖 `oplus-services.jar` 里的类名/方法名不变 —— **系统 OTA 后必须重新验证**
   （重跑一次 `dexfindstr.py` 即可）。
3. 只在**轮次进行中**豁免（有界窗口），非常驻保活；长期功耗与长会话表现仍需继续观察。
4. 本节所有"改后"结论都来自**真机 logcat / 模块日志**。
   "编译通过"不等于"真机生效"，未实测的部分不写进本节。

