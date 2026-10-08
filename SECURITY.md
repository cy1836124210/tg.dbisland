# 安全整改说明（v1.0 → v1.2）

> **v1.2 为真机联调修复版，安全模型未变。** 下面第 0 节列出 v1.2 里
> 与安全/权限边界相关的改动，第 1 节起是 v1.1 的 5 条预审整改原文（仍然有效）。
>
> **v1.2 后续两处改动会改变「现状」，先读这里**（详见 [`CHANGELOG.md`](CHANGELOG.md) 第 34、35 条）：
> 1. **第 34 条：电脑端整体移除。** 下面 0.1 / 0.2 / 0.4 以及第 1 节里与
>    `listen.sh` / `relay.sh` / `/data/adb/modules/islandbridge` / 8799 监听 /
>    `uninstall.sh` 相关的内容**都属于已删除的 root 中继组件**——本节与第 1 节的
>    这些文字是**历史事实记录**（不改写），但**代码已经不存在了**：App 不再安装、
>    不再管理、不再向 `/data/adb` 写任何东西，也不再监听端口。
>    `INTERNET` / `ACCESS_NETWORK_STATE` / `usesCleartextTraffic` 也一并删除。
> 2. **第 35 条：包名 `com.islandbridge` → `com.tg.dbisland`。** 本节正文里出现的
>    `com.islandbridge.*`（权限名、action、authority、组件名）**现在都叫
>    `com.tg.dbisland.*`**；为了不改写历史记录，正文保留旧写法，只在这里点明对应关系。

## 0. v1.2 与安全相关的改动

### 0.0 电脑端移除 + 包名迁移（v1.2 收尾，先看这条）
- **删掉的攻击面（这是增强，不是削弱）**：
  | 删掉的东西 | 原来的风险 |
  |---|---|
  | `SseClient.kt` + okhttp + `INTERNET`/`ACCESS_NETWORK_STATE` + `usesCleartextTraffic` | 明文 HTTP 长连接、局域网可嗅探；App 有出网能力 |
  | App 内「电脑IP / 端口 + 连接 / 断开」 | 用户填错就把事件流发给别的设备 |
  | `listen.sh` 的 `127.0.0.1:8799` 监听 + 令牌 | 见第 1 节第 2 条（已随组件删除） |
  | `relay.sh` 的队列 → Binder 中继 + `/data/adb` 落盘 | 见第 1 节第 3 条（已随组件删除） |
  | `EventProvider` 的 `src=pc` 来源判定、`EVENT_PC` 调试广播 | 少了一条「伪装成电脑端帧」的注入面 |
  现在的安卓端**没有任何网络请求、不监听任何端口、不向 `/data/adb` 写文件**；
  跨进程入口只剩三条，且全部有 uid 白名单或签名级权限把关：
  `EventProvider`（Binder，uid 白名单）、`BridgeEventReceiver`/`KeepAliveReceiver`/
  `OpenDoubaoReceiver`（广播，uid 白名单）、豆包进程内的 `SEND`/`DELETE`/`PING`
  动态接收器（**签名级权限** `com.tg.dbisland.permission.CONTROL`）。
- **包名迁移的安全要点**：`BridgeSecurity.bridgeUidOf()` 的
  `getPackageUid("com.tg.dbisland")` 是命令通道第二道闸的判据，必须与新包名一致
  （否则合法命令会被自己的白名单拒掉，真机表现是「回复失败、等 8s 没回执」）。
  签名级权限名、provider authority、全部 action、LSPosed 入口类名同步改名；
  旧包 `com.islandbridge` 与新包是**两个不同的应用**（并存），
  权限名也不通用 —— 这顺带避免了「旧包残留仍持有 CONTROL 权限」的情况。
- **用户可见后果**：旧包需要自己卸载；LSPosed 需要为新包重新启用并勾选作用域。

### 0.1 卸载时连 `nc` 子进程一起收（收得更干净）
> ⚠️ **已删除**（第 34 条）：这一条讲的是已删掉的 root 中继组件，保留为历史记录。

旧版只杀 `listen.sh`，会留下它的 `nc -lk -s 127.0.0.1 -p 8799` 子进程继续占着端口；
`uninstall.sh` 现在按 argv 末参识别并一并清理，8799 在卸载后立即释放。

### 0.2 `nsenter -t 1 -m` 是**功能正确性**修复，不构成提权
> ⚠️ **已删除**（第 34 条）：launcher / relay / uninstall 三个脚本已不在仓库里。

launcher / relay / uninstall 会从 App 的 mount namespace 重入 init 的 namespace，
否则看不见 `/data/data/com.larus.nova`。脚本本来就以 root 运行，重入的是**同一 uid**
的另一个挂载视图，不新增任何权限；`IB_NS_ENTERED` 防重入。

### 0.3 前台服务被拒是系统行为，限流不改鉴权
`ForegroundServiceStartNotAllowedException` 是 Android 12+ 对后台起前台服务的限制。
改成「60s 内只记一条 info、不重试」只影响日志噪声与重试频率，不改任何鉴权、
绑定地址或令牌校验。

### 0.4 新增一个**卸载后仍留下的标记文件**（如实披露）
> ⚠️ **已删除**（第 34 条）：App 里已没有「安装/重装 root 组件」入口，
> 也不再写 `K_ROOT_DISABLED`；`/data/adb/islandbridge.disabled` 只会作为旧版本
> 的历史残留出现在设备上（清理方式见 [`README.md`](README.md) 的
> 「root 组件残留清理」一节）。

为了让「root 管理器里移除模块 → 重启后 App 不再自动装回」，`uninstall.sh` 会写
`/data/adb/islandbridge.disabled`（0 字节，0644）。它**刻意放在 `$DIR` 之外**，
所以能活过 `rm -rf $DIR` —— 也就是说，**卸载后会留下这一个文件**，属于可见残留。
删除方式：在 App 里点「安装/重装 root 组件」（会清掉它并重装），或
`su -c 'rm -f /data/adb/islandbridge.disabled'`。除此之外不留任何东西：
两个目录、token、pid、日志、豆包侧队列文件都会被删掉。

### 0.5 APK 里实际有三个权限（**当前状态见下**）
除 `com.tg.dbisland.permission.CONTROL` 与 SDK 合并进来的
`com.astraflow.tool.island.permission.PUBLISH_ACTIVITY`，还有 AndroidX 自动合并的
`com.tg.dbisland.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`（供 API 33+ 动态注册
接收器使用）。**这 3 个都是 signature 级**，第三方应用拿不到。
另外 release APK 声明了 **5 个普通系统权限**（`aapt2 dump badging` 可逐条核对）：
FOREGROUND_SERVICE、FOREGROUND_SERVICE_DATA_SYNC、POST_NOTIFICATIONS、
RECEIVE_BOOT_COMPLETED、WAKE_LOCK。
**旧的 v1.2 中间版本还申请过 `INTERNET` / `ACCESS_NETWORK_STATE`**（共 7 个），
已随电脑端删除 —— 当前包里**没有**网络权限。

---

### 0.6 广播发送方 uid 被误判 → 命令通道自己把自己挡了（v1.2 真机修复）
- **问题**：Android 14+ 起，发送方 `targetSdk ≥ 34` 且未开身份共享时，
  接收器 `getSentFromUid()` 返回 `INVALID_UID`；模块退到
  `Binder.getCallingUid()`，而 `onReceive` 在**主线程 Handler** 里回调、
  **不在 Binder 事务中**，该 API 返回的是**接收方自己**的 uid。
  真机实测：模块在豆包进程（uid 10375）里把 App（uid 10430）发来的合法命令
  判成「uid 10375 不可信」而拒绝 —— 安全校验误伤功能，且因为拒绝点在
  `recv` 日志之前，表面看像「广播根本没到」。
- **修法**：① App 发 SEND/DELETE 时
  `BroadcastOptions.makeBasic().setShareIdentityEnabled(true)`，
  让接收器能拿到真实发送方 uid；② 模块侧**删除** `Binder.getCallingUid()`
  兜底，并把「取到的 uid == 接收方自己」统一当作**无法归因(-1)**，
  由各通道既定策略处理。
- **安全影响：不降低强度。** 命令通道的第一道闸始终是**系统强制的签名级权限**
  `com.islandbridge.permission.CONTROL`；uid 白名单只是第二道。改完后
  第二道闸反而**真正生效**了（改之前它对合法命令永久拒绝、形同虚设）。
  「无法归因」时的策略与整改文档一致：命令通道靠签名权限兜底，事件通道放行但告警。

- **实测补充**：`adb shell`(uid 2000) 与 root(uid 0) 能投递到该接收器 —— 这与 `allowCommandSender` 既定的可信来源一致（root/shell 本就可信）；第三方应用两条都不满足：拿不到签名级权限、也不在 uid 白名单里。
### 0.10 发送失败时的 root 唤醒 / 冷启动补发（新增行为，如实披露）
「在模块里提问」如果 2.6s 拿不到回执，说明豆包进程被冻住或已被回收，App 会：
- **借 root 唤醒**：执行 `am start-service` 把豆包进程拉起（不改动它的任何数据），
  并由 root 直接投递这条 `SEND`（root 不受后台启动限制约束；与既有 relay.sh
  同一依据）。root 脚本连投 3 次、每次隔 1.5s 兜住冷启动注册延迟。
- **等真实回执再报结果**：模块不再“发完就报成功”，要等豆包的回调（2s 内）；2s 无回执按“已发出”处理（宁可不补救，也不重复发消息）。
- **去重**：同一条用户消息带同一个随机 id，模块只处理第一次 —— **用户消息最多
  被发送一次**，不会因为重试/迟到而重复发给豆包。
- **模块明确报拒时的补发**（**不再限于息屏**）：若豆包拒绝我们构造/重放的请求，App 会用 `am start` 拉起豆包自己的分享直投活动`OuterShareDeliverActivity` 把这条文本交给豆包发送。**代价：豆包界面会短暂切到前台**（已如实说明）；不想要这个行为可以去掉。
  我们构造的请求），**仅在息屏时**用 `am start` 拉起豆包自己的分享直投活动
  `OuterShareDeliverActivity` 把这条文本交给豆包发送（息屏下拉起活动不会亮屏、
  不打断用户）。亮屏时不做这件事，只写一行日志。
- **边界**：`su` 不可用（未授权 root）时这些都不执行，退化为 App 自己重发一次；
  所有动作都会写进 App 的日志界面，不静默。

---

### 0.7 调试后门只存在于 debuggable 包
为真机定位「命令通道」问题，App 里加过一个 `am start … -e reply <text>` 入口。
任何应用都能 start 别人的 Activity，所以这个入口**只在 `FLAG_DEBUGGABLE` 的包里
生效**；提交用的 release 包（`aapt2 dump badging` 无 `application-debuggable`）
里它不会执行。请以此为准复核：release APK 无法被第三方用来借壳发消息。

### 0.8 事件通道「拿不到发送方」的告警改为只打一次
原先每收到一条事件广播就 `W` 一行（一轮回答十几条），只影响日志噪声，
不改任何鉴权逻辑；现在只记第一条。

---

### 0.9 退后台保活窗（新增行为，如实披露）
豆包退到后台后，模块会在**一个 30s 空闲窗**内维持它不被系统冻结：持一个
`PARTIAL_WAKE_LOCK`，并每 3s 做一次「豆包 → 本 App provider」的 binder 往返；
App 侧同样每 3s 反向 ping 一次豆包进程（广播走 CONTROL 签名权限那道闸，
第三方发不进来）。
- **只做"维持活性"**：ping 不带任何业务语义，不读消息、不改会话、不发送；
  豆包回到前台、或窗内连续 30s 没有新内容 → 立刻释放唤醒锁、停止 ping，
  **把豆包交回 ColorOS 处理**（实测收手后 3s 系统即冻结它）。
- **不扩大攻击面**：ping 通道复用已有签名级权限接收器，没有新增导出组件、
  没有新增权限、没有新增网络行为。
- **「发送」也会激活**：App 交出一条消息（`SEND`）时同样开窗 —— 回答一定会来，不能等第一条推流（那时可能已被冻）。
- **没有降低系统的省电策略**：这是一个**有上限的窗口**（最长只在有推流时
  持续，静默 30s 必停），而不是常驻保活。

---

# 安全整改说明（v1.1 原文，仍然有效）

本文逐条对应上一轮预审提出的 5 个问题，说明**改了什么、为什么这样改、
怎么验证**。对应代码位置以 v1.1 为准。

| # | 预审问题 | 结论 | 主要落点 |
|---|---|---|---|
| 1 | `com.islandbridge.SEND` / `DELETE` 接收器导出且不校验发送方 | 已修 | `BridgeSecurity.kt`、`DoubaoHookEntry.kt`、`Receivers.kt`、`AndroidManifest.xml` |
| 2 | root 监听默认 `0.0.0.0:8799` 且无鉴权 | 已修 → **整改对象已被删除**（v1.2 第 34 条：root 监听随电脑端整体移除） | `assets/root/listen.sh`（**已删除**）、`BridgeService.kt`、`EnvCheck.kt` |
| 3 | root 写入 `/data/adb` 的开机服务无法卸载 | 已修 → **整改对象已被删除**（v1.2 第 34 条：App 不再向 `/data/adb` 写任何东西） | `assets/root/{module.prop,service.sh,uninstall.sh,launcher.sh}`（**已删除**）、`BridgeService.kt`、`MainActivity.kt`、`README.md` |
| 4 | debug 证书 + `debuggable` | 已修 | `app/build.gradle.kts`、`android/keystore/` |
| 5 | 简介未披露发送消息/删除会话/root 常驻/网络监听/后台唤醒 | 已补（**「root 常驻 / 网络监听」两项已随第 34 条消失**，README 里相应改成「已删除」） | `README.md`、`SUBMIT.md` |

> 下表与下文正文里的 `com.islandbridge.*` 是**当时的名字**；第 35 条改名后，
> 权限名 / action / authority / 组件名一律为 `com.tg.dbisland.*`（对应关系见第 0.0 节）。

---

## 1. 命令通道：签名级权限 + 发送方校验

### 问题

`com.islandbridge.SEND` / `com.islandbridge.DELETE` 是在**豆包进程内**动态注册的
广播接收器，且注册时是 `RECEIVER_EXPORTED`、不校验发送方。任意应用只要知道
action 名，就能以用户名义发消息、永久删除会话。

### 整改

**(a) 主闸门 —— 操作系统强制的签名级权限。**
`AndroidManifest.xml` 声明：

```xml
<permission android:name="com.islandbridge.permission.CONTROL"
            android:protectionLevel="signature"
            android:label="豆包岛桥控制通道"
            android:description="@string/perm_control_desc" />
<uses-permission android:name="com.islandbridge.permission.CONTROL" />
```

模块在豆包进程内注册这两个接收器时，把它作为 `registerReceiver` 的
`broadcastPermission` 传入（`DoubaoHookEntry.kt`）：

- API 33+：`registerReceiver(rx, filter, PERM_CONTROL, null, Context.RECEIVER_EXPORTED)`
- 更低版本：`registerReceiver(rx, filter, PERM_CONTROL, null)`

投递前由 AMS 强制校验**发送方是否持有该权限**。签名权限只有与本 App
同一证书的包才可能被授予，第三方即使知道 action 名也发不进来。

*为什么防得住抢注*：Android 不允许两个不同签名的包定义同名权限
（后装者直接安装失败），所以这个权限名无法被第三方降级成普通权限。
本 App 自己持有它是系统行为（`compareSignatures(权限定义包, 使用包)` 命中
`SIGNATURE_MATCH`）。

**(b) 第二道闸门 —— 发送方 uid 白名单。**
`BridgeSecurity.allowCommandSender(ctx, uid)`：只放行
`uid == 0`（root）、`uid == 2000`（shell）与本 App 自己的 uid。
`DoubaoHookEntry.kt` 在 `onReceive` 开头取 `BridgeSecurity.senderUid(this)`
（API 34+ 用 `BroadcastReceiver.getSendingUid()`，否则 `Binder.getCallingUid()`），
不通过直接丢弃。

**(c) manifest 里导出的接收器一并加固。**
`Receivers.kt` 的 `KeepAliveReceiver` / `BootReceiver` / `BridgeEventReceiver` /
`OpenDoubaoReceiver` 每个 `onReceive` 第一行都是
`if (!BridgeSecurity.allowBroadcast(ctx, this)) return`。

`OpenDoubaoReceiver` 从 v1.2 第 32 条起多带一个 `cid` extra（每条会话一个
`PendingIntent`，见 `IslandBridge.openIntentFor`）。它**只用来选"收掉哪一张卡"**
—— 能通过白名单的发送方（root / shell / 本应用 / 星河岛宿主）本来就能触发
「打开豆包 + 收卡」，多一个 `cid` 不新增任何写操作路径；未知 / 空 `cid` 只打开
豆包、不动卡片。

这几个接收器**必须保持导出**（发送方是豆包进程、system_server、root，
它们不可能持有我们的签名权限），因此这里的策略是**正向识别 + 白名单**：
只有能确认身份的 uid（0 / 1000 / 2000 / 豆包 / 本 App）才放行。
事件中继通道另用更严的 `allowEventRelaySender`（只允许 0/2000/豆包），
堵掉了原先 `uid == 1000`（system_server）也能伪造 `src=doubao` 心跳的旁路。

### 已知取舍（如实记录）

部分厂商 ROM / 低版本系统上拿不到发送方 uid（返回 `-1`）。此时
**放行并记录警告**，而不是拒绝 —— 拒绝会把「开机自启」「保活」「事件中继」
整条链路拦死。这是明确的取舍，日志里会写明 `senderUid=-1 无法核实，放行`。
命令通道（发送/删除）不受此取舍影响：它的主闸门是系统强制的签名权限。

---

## 2. root 监听：默认仅本机 + 强制令牌

> ⚠️ **本条整改的对象已不存在**（v1.2 第 34 条）：`listen.sh` 及整个 root 中继组件
> 随电脑端一起删除，App 现在**不监听任何端口**。下面保留当时的整改过程作为历史记录。

### 问题

`listen.sh` 默认 `0.0.0.0:8799` 且无 token，同一局域网内任意设备都能注入内容。

### 整改（`app/src/main/assets/root/listen.sh`，**该文件已删除**）

| 项 | 1.0 | 1.1 |
|---|---|---|
| 默认绑定 | `0.0.0.0:8799` | `127.0.0.1:8799`（`BIND` 可改，改错了日志会 WARN） |
| 鉴权 | 无 | **强制令牌**，无法关闭 |
| 令牌生成 | —— | 首次运行从 `/dev/urandom` 取 32 位十六进制，`tr -cd 'A-Za-z0-9_-'` 净化 |
| 令牌存放 | —— | `$DIR/token`（0600）、`$DIR/ib_listen.conf`（0600）；`$DIR` 本身 `chmod 700` |
| 请求处理 | 直接执行 | 必须先匹配 `"$TOKEN"[[:space:]]*` 再要求 JSON `{` 开头，否则 403 |
| 启动保护 | —— | 令牌长度 < 16 位**拒绝启动**；指定绑定失败**不再回退**到通配地址 |
| 提示 | 无 | `BIND=0.0.0.0` 时日志打印 WARN |

App 侧（**这些入口已删除**）：`EnvCheck.kt` 曾新增 `Root 组件` 一行，直接读
`ib_listen.conf` 与 `token` 长度，显示「已安装 · 仅本机 127.0.0.1 · 已强制令牌」或
「全**网卡** · 已强制令牌」；`MainActivity` 曾提供「查看局域网监听令牌」
把 token 显示给用户填到电脑端。第 34 条把这一行、这个按钮、以及对应的
`readListenToken()` 全部删掉了。

---

## 3. root 常驻服务：标准模块布局 + 三路卸载

> ⚠️ **本条整改的对象已不存在**（v1.2 第 34 条）：`assets/root/` 6 个文件整体删除，
> `BridgeService` 的 `installRoot / uninstallRoot / reinstallRoot / readListenToken`
> 与 `MainActivity` 的三个按钮一并删除。App **不再向 `/data/adb` 写任何东西**。
> 设备上历史遗留的 `/data/adb/modules/islandbridge` 需要用户在 root 管理器里移除
> （清理命令见 [`README.md`](README.md) 的「root 组件残留清理」一节）。
> 下面保留当时的布局与卸载路径作为历史记录。

### 问题

1.0 把裸脚本写进 `/data/adb/service.d/islandbridge.sh`。该目录只有
「开机执行」语义，**没有任何卸载回调**，所以模块被删掉后开机服务仍在，
构建产物里也没有清理入口。

### 整改

改为 KernelSU / Magisk 标准模块布局，安装到
`/data/adb/modules/islandbridge/`：

```
module.prop     id=islandbridge，管理器里可见、可停用
service.sh      开机由管理器调用（setsid 拉起 launcher.sh）
uninstall.sh    模块被移除时由管理器执行，清理进程/数据/日志/遗留脚本
relay.sh listen.sh launcher.sh
```

`BridgeService.kt` 的 `ASSETS` 表把源文件分流：`relay.sh / listen.sh /
launcher.sh → /data/adb/islandbridge`，`module.prop / service.sh /
uninstall.sh → /data/adb/modules/islandbridge`；安装末尾执行
`rm -f /data/adb/service.d/islandbridge.sh` 清掉 1.0 遗留；`launcher.sh`
自身也带同样的清理与 `[ -f "$MODDIR/module.prop" ] || exit 0` 守卫。

三条卸载路径（效果相同）：

1. App 内「卸载 root 组件」（`BridgeService.uninstallRoot`）；
2. KernelSU / Magisk 模块列表里移除（触发模块自带 `uninstall.sh`）；
3. 手动 `su -c 'pkill -f /data/adb/islandbridge; rm -rf ...'`
   （**卸载 App 之后唯一可行的一条**）。

README 的「root 组件的安装与卸载（必读）」一节给出了完整命令。

---

## 4. 正式签名 + 关闭可调试

### 问题

提交的是 debug 构建：调试证书签名 + `android:debuggable="true"`。

### 整改

`app/build.gradle.kts`：

- `signingConfigs.release` 从 `android/keystore.properties` 读取自建密钥库；
- `buildTypes.release { isDebuggable = false; signingConfig = ... }`；
- `versionCode = 2` / `versionName = "1.1"`；
- 新增 `distRelease` 任务：产出 `dist/doubaodao-v1.1-release.apk` 并附 SHA-256。

密钥库 `android/keystore/islandbridge-release.jks`：PKCS12、RSA 4096、
`SHA256withRSA`、有效期 30 年。密码只写在 `android/keystore.properties`
（已加入 `.gitignore`，连同 `android/keystore/`、`*.jks`），**不入库**。

### 验证

```powershell
$env:JAVA_HOME='D:\tool\java\jdk17'; $env:ANDROID_HOME='D:\tool\android-sdk'
cd D:\aiwork\doubaoni\android
.\gradlew.bat :app:distRelease

$bt='D:\tool\android-sdk\build-tools\36.0.0'
# 1) 签名者必须不是 Android Debug
& "$bt\apksigner.bat" verify --print-certs --verbose `
    ..\dist\doubaodao-v1.1-release.apk
# 2) 不得出现 application-debuggable
& "$bt\aapt2.exe" dump badging ..\dist\doubaodao-v1.1-release.apk |
    Select-String 'package:|debuggable|uses-permission'
```

预期：`Signer #1 certificate DN: CN=IslandBridge Release, ...`（**不含**
`Android Debug`），`aapt2` 输出中**没有** `application-debuggable`。

---

## 5. 简介如实披露

`README.md` 新增三节：

- **行为披露**：表格逐项列出「以你的名义发送消息」「永久删除会话」
  「读取/转发回复内容」「后台唤醒豆包」「root 常驻服务」「监听网络端口」
  「联网」，每项都写清具体行为与**触发条件**，并明确列出**不会做的事**。
  （第 34 条之后，「root 常驻服务」「监听网络端口」「联网」三项在表格里已标注
  **已删除**，并补了保留的「root 唤醒」一项。）
- **权限与隐私说明**：逐条权限用途、数据流向图、日志说明
  （旧版还写明文 HTTP —— 现在没有网络权限了，这一句已删除）。
- **卸载后剩什么** + **root 组件残留清理（只针对历史版本）**：
  明确写出卸载 App **不会**自动清 `/data/adb` 的历史残留，并给出清理路径。

`SUBMIT.md` 是可直接粘贴到商店「简介 / 描述」字段的版本。

---

## 星河岛侧说明

星河岛**不是插件市场**：`astraflow.cc/island` 上只有 SDK 与文档，没有账号、
没有上架表单、没有「简介」输入框。所以这一轮的整改落点是
**代码安全加固 + 仓库内文档披露**，而「重新提交」实际指的是
**用正式签名重新打包分发**。

顺带把 SDK 从已停止提供的 `astraisland-client`（协议 5/6）迁到
**星河岛 SDK 0.1.0（通信版本 7）** —— 旧接入库新版星流已不再接受连接，
不迁移的话装上也收不到卡片：

- 依赖换成 `libs/astraisland-sdk-0.1.0.aar`；
- `IslandBridge.kt` 全部改用 `com.astraisland.sdk` 的类型化 API
  （`IslandActivity.Builder(id, Capsule, IslandCard)`、`ProgressCard`、
  `MessageCard`、`IslandResult` 枚举）；
- manifest 里手写的 3 条 `PUBLISH_ACTIVITY` 与 3 条岛 `<queries>`
  **删除**，交给 AAR 构建时自动合并（只留 hook 豆包用的 `com.larus.nova`）；
- 所有投送调用移到单线程后台执行 —— 0.1.0 的 `start/update/end` 会等星河岛
  处理完才返回，主线程调用会 ANR；
- 所有 Builder 调用包了 `try/catch(IllegalArgumentException)` ——
  0.1.0 的参数校验是**构造即抛**，不接住会打死投送线程；
- 回复文本（聊天内容）按官方建议设 `LockScreenVisibility.TITLE_ONLY`。

---

## 剩余风险与已知取舍（未修 / 未验证，如实列出）

### R1. ~~电脑端 SSE 服务（`pc/` 8787）本身没有鉴权~~ **已移出本项目**

> ⚠️ **v1.2 第 34 条：安卓端不再连接电脑端，`SseClient`、`INTERNET` 权限、
> App 里的电脑端配置块全部删除。** 电脑端代码冻结在 `pc/` 留档
> （[`pc/FROZEN.md`](pc/FROZEN.md)），风险只存在于「有人单独把那个留档目录里的
> daemon 跑起来」的情况。下面保留当时的风险描述作为历史记录。

手机是**主动连**电脑端的（`GET /events` + 上行 `POST /reply`、`POST /delete`），
这一侧与手机侧监听是两条独立的链路。`pc/sse_server.py` 与 `pc/config.json` 现在：

- 默认 `bind = 127.0.0.1`（手机连不上，但也不会暴露）；
- 你要让手机连上就必须改成 `0.0.0.0`，此时**同网段任何设备**都能读事件流
  （= 你的豆包对话内容）、并调用 `/reply`、`/delete`（= 以你的名义发消息 / 删会话）。
  代码里会在绑定非回环地址时打印醒目告警。

**本轮没有给它加令牌**，原因：这需要**手机端与电脑端同时改**（okhttp 请求头 +
服务端校验）并做两端联调。设备现在可用（见 R6），但当轮的重心是商店审核的 5 条整改，
把一个跨设备鉴权改动塞进来会让「已整改项」与「新增未验证项」混在一起 ——
建议作为下一轮独立改动，改完后按 `SUBMIT.md` 第四节一起复测。

**建议的下一步（按优先级）**：

1. 电脑端只绑回环，用 SSH 隧道 / VPN（WireGuard、Tailscale）让手机访问；
2. 或照手机侧同一套方案给电脑端加 `Authorization: Bearer <token>`，
   并在手机 `SseClient` 里带上同一个令牌（两端一起改、一起测）；
3. 至少不要在家用 Wi-Fi 之外的网络（咖啡厅、公司）开 `0.0.0.0`。

### R2. ~~局域网链路是明文 HTTP，没有 TLS~~ **已连同电脑端一起消失**

> 第 34 条删除了 `INTERNET` / `ACCESS_NETWORK_STATE` 与 `usesCleartextTraffic`，
> 安卓端不再有任何网络链路（既不连电脑端，也不再监听 8799）。
> 留档的 `pc/` 若被单独跑起来，仍是明文 HTTP。

手机↔电脑、root 中继↔App 都在明文 HTTP 上。局域网内可被抓包看到回复正文
与令牌。缓解：只在可信网络使用；或按 R1 建议走隧道。

### R3. 发送方 uid 无法归因时（`-1`）放行

见 §1「已知取舍」。命令通道有签名权限兜底，事件通道（只影响卡片显示）会放行。

### R4. DSH 插件与 DSH Web GUI 同端口

`ctx.webServer` 是单例服务，插件注册的路由与 GUI 共用 `host:port`；而 DSH 明确
拒绝 `--host 0.0.0.0`（本机 `startup.js` 原文：*it would expose remote code execution
to the network*）。所以把插件直接暴露到局域网 = 同时暴露 GUI。
详见 [`docs/DSH_PLUGIN.md`](docs/DSH_PLUGIN.md) §4.5 与 §7。
（这条属于留档的电脑端/DSH 侧，安卓端不再涉及。）

### R5. 回复卡可能让位副岛 / App 在前台时不上岛

星河岛 0.1.0 的规则（消息卡片按新消息排位、前台不显示本应用内容），
不可由应用覆盖。见 README 已知限制。

### R7. v1.2 收尾（第 34、35 条）后的新残留面（**新增，如实列出**）

| # | 项 | 现状 / 建议 |
|---|---|---|
| R7.1 | 旧包 `com.islandbridge` 仍在设备上 | `pm path` 实测两个包并存。旧包不会自动卸载、也不会被新包覆盖。**建议**：确认新版可用后，在系统设置里卸载旧包（旧包带着 `com.islandbridge.permission.CONTROL` 权限与旧的 LSPosed 条目） |
| R7.2 | `/data/adb/modules/islandbridge` 等历史残留 | 新版 App 不再管理它，也不会自动清除。**建议**：在 KernelSU/Magisk 管理器里移除模块（触发自带 `uninstall.sh`），或按 README 的手动命令清理 |
| R7.3 | LSPosed 里的旧模块条目 / 新包未启用 | 改名后 LSPosed 把 `com.tg.dbisland` 当**新模块**，需要重新启用 + 勾作用域。**在完成之前，岛上端到端是不通的**（本次未验证） |
| R7.4 | root 唤醒脚本的路径 | `IslandBridge.wakeScript()` 用 `su` 读 `/data/data/com.tg.dbisland/files/ib_send.req` 并 `am broadcast -a com.tg.dbisland.SEND`。包名已同步，但**这条路径在本次改名后没有再做真机验证**（早前版本验证过同一机制） |

### R6. 真机验证到了哪一步（**已不是「完全没上机」**）

**2026-10-04 补记**：设备已连上（PJZ110 / Android 16 / KernelSU / LSPosed 1.9.x），
完成并复核了下面这些**装机类**验证：

- 旧版 v1.0（debug 签名、DEBUGGABLE、versionCode 1）与早期试验包
  `com.tgdsh.xingdao`（「星岛监工」）已卸载；
- v1.0 的 `/data/adb` 残留**清理前是活的**：`service.d/islandbridge.sh` 开机项、
  `/data/adb/islandbridge/{launcher,listen,relay}.sh`、三个 worker 进程、
  以及 **`[::]:8799` 全网卡监听**（正是第 2 条的现场）；已全部清除；
  **重启后未再出现，8799 仍无监听** —— 这是第 3 条（卸载/开机残留）的真机证据；
- v1.1 **release** 已装机并校验：versionCode 2 / versionName 1.1 / `flags` **无 DEBUGGABLE**、
  拉回 APK 验签为 `CN=IslandBridge, OU=Doubaodao, O=IslandBridge, C=CN`
  （证书 SHA-256 `738c3ae2…`）、`com.islandbridge.permission.CONTROL` 在、
  `assets/root` 6 个、362697 B（与仓库产物同尺寸）。

**2026-10-05 v1.2 收尾补记（第 34、35 条）**：

- `aapt2 dump badging`：`package: name='com.tg.dbisland' versionCode='3' versionName='1.2'`、
  `application: label='豆包岛桥' icon='res/BW.xml'`、
  `application-icon-160/240/320/640/65534` 均有值、
  `uses-permission: name='com.tg.dbisland.permission.CONTROL'` +
  `com.tg.dbisland.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`；
  **`INTERNET` / `ACCESS_NETWORK_STATE` 已不在**（只剩 5 个普通系统权限）。
- `adb -s d666858b install -r android\dist\doubaodao-v1.2-release.apk` → `Success`；
  `pm path com.tg.dbisland` → `/data/app/~~_FXoqrFDfeXJF51mqM162A==/com.tg.dbisland-…/base.apk`，
  同一命令同时列出旧包 `/data/app/…/com.islandbridge-…/base.apk`（**旧包未卸载**）。
- `su -c 'am start -n com.tg.dbisland/com.tg.dbisland.MainActivity'` 成功；
  logcat `NFW_findFocusedWindowIfNeeded:Window{… u0 com.tg.dbisland/com.tg.dbisland.MainActivity}
  mCurrentFocus:Window{…}` + `updateForegroundInfo … [com.tg.dbisland]=1, uidPidMapInf(1)|[10045]=16852`，
  `pidof com.tg.dbisland` = `16852`，**无 `FATAL EXCEPTION` / `AndroidRuntime`**。
- App 首页截图（`dev/ib_v12_cut.png`）：**没有电脑端配置块**，环境自检只有
  `Root 权限 / LSPosed 框架 / 模块状态` 三行（无「Root 组件」），也没有三个 root 组件按钮；
  自检显示 `LSPosed 已运行 · v2.2.0 (7854) · 未登记本模块`、`模块状态` 红点 ——
  **这正是「LSPosed 还没启用新包」的直接证据**。
- `python -m unittest discover -s pc/tests` → `Ran 72 tests in 10.509s` / `OK`。

**仍未在真机上验证的（功能层）**：**改名后的这个包，岛上端到端一条都没跑过** ——
豆包出卡片、第三方 App 发 `com.tg.dbisland.SEND` 被拒、消息/删除全链路、
一分钟无操作通知、多会话主岛/副岛。原因：LSPosed 里 `com.tg.dbisland` 尚未启用
（旧包的 `modules_state.enabled=0` 也不适用于新包），模块没被注入 `com.larus.nova`。
需要先在 LSPosed 里为新包启用 + 勾选 `com.larus.nova` 与 `android` 作用域。

在此之前已完成的离线验证：

- 编译期：`:app:assembleRelease` / `:app:lintVitalRelease` 通过；
- 产物：`apksigner verify --print-certs` 签名者为自建证书（非 Android Debug）、
  `aapt2 dump badging` 无 `application-debuggable`、versionCode=3/versionName=1.2；
- 权限：`protectionLevel=0x2`（signature）、`com.tg.dbisland.permission.CONTROL`
  与 SDK 自动合并的 `com.astraflow.tool.island.permission.PUBLISH_ACTIVITY` 均在；
- 资产：**root 资产已删除**（第 34 条），`assets/` 只剩 `xposed_init` 与 `chat/chat.html`，
  `xposed_init` 内容为新入口 `com.tg.dbisland.xposed.DoubaoHookEntry`；
- PC 侧与 DSH 插件：各自测试套件通过（见 `pc/ADAPTATION.md`、
  `docs/DSH_PLUGIN.md` §6）—— `pc/` 已冻结，测试未改。

**装机复测清单见 [`SUBMIT.md`](SUBMIT.md) 第四节；真机清理/安装记录见
[`CHANGELOG.md`](CHANGELOG.md) 第七节。**

