# `pc/` 已冻结（电脑端已从本项目分离）

**这个目录不再属于安卓端的运行时。它只是历史留档，冻结保存，不要在这里改代码。**

## 发生了什么

v1.2 的第 34 条改动把**电脑端整体从项目里切掉**了：安卓端不再连接、不再显示、
不再向电脑端发送任何东西。随之删除的安卓侧内容（全部是物理删除，不是注释掉）：

| 类别 | 删除的东西 |
|---|---|
| 传输 | `android/.../SseClient.kt`（HTTP SSE 客户端）、`BridgeApp.link`、`postReply` / `postDelete` |
| UI / 配置 | `MainActivity` 里「电脑端连接配置」整块（`SHOW_PC_CONFIG`、`电脑IP / 端口 / 连接 / 断开`） |
| 文案 | `BridgeService` 常驻通知「保持与电脑的连接」 |
| 语义 | `IslandBridge` 的 `PC_LIVE_MS` / `simPcLive()`（`files/ib_sim.txt` 里的 `pc on`）/ `电脑` 来源 / `电脑包` 显示名 / `deleteViaPc` / `sendViaPc`；`EventProvider` 的 `"pc","desktop","电脑" -> false` 映射；`Receivers` 的 `EVENT_PC` |
| root 中继 | `android/app/src/main/assets/root/`（`module.prop` / `service.sh` / `uninstall.sh` / `relay.sh` / `listen.sh` / `launcher.sh`）与 `BridgeService` 里的安装/卸载/令牌读取入口 —— 那套 `/data/adb/modules/islandbridge` 只做「局域网中继 + 电脑端帧注入」 |
| 权限 / 依赖 | `INTERNET`、`ACCESS_NETWORK_STATE`、`usesCleartextTraffic`、okhttp 依赖 |

保留下来、与电脑端无关的能力：豆包 hook 推流、星河岛卡片、多会话
（`reply:<cid>` + 先到者占主岛直到被收掉）、保活/唤醒、一分钟无操作通知+收起、
豆包图标、胶囊左=来源/右=状态、以及模拟通道的**多会话注入**
（`files/ib_sim.txt` 的 `chat.start/chat.delta/chat.end/sim.action` JSON 行）。

## 这个目录现在的地位

* **只读留档**：`bridge_daemon.py` / `sse_server.py` / `simulate_doubao.py` /
  `dsh-plugin/` / `installer/` 等，是电脑端当初的实现与工具，留作参考。
* **安卓端不再引用它**：仓库里没有任何安卓侧代码、配置、脚本会读 `pc/` 下的东西。
* **不要在这里改代码**。需要参考实现时读它，需要改安卓行为时改 `android/`。

## 唯一的例外：安卓侧诊断脚本搬走了

`pc/sim_multi.py`（多会话 / 副岛 / 「主岛归先到者」的 adb+root 模拟脚本，
**属于安卓侧诊断**，不依赖电脑端进程）已移到 [`tools/sim_multi.py`](../tools/sim_multi.py)。
用法不变，只是路径变了：

```
python tools/sim_multi.py run
python tools/sim_multi.py start / ack sim-a / show / clear
```

## 回滚 / 找回

这是**没有 git 的仓库**（删除不可恢复）。想恢复电脑端，只能：

1. 从 `doubaodao-src.zip` 之类的归档里找回当时的 `pc/` 与安卓侧文件；
2. 或者按 `CHANGELOG.md` 第 34 条列出的清单，反着手工重建（不推荐）。

历史记录里的哈希、字节数、真机验证证据都保留在 `CHANGELOG.md`（**只追加，不改写**）。
