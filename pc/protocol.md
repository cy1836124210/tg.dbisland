# IslandBridge 协议（v2 — SSE）

手机 App 订阅 `http://<电脑IP>:<port>/events`（默认端口 8787，可在 `config.json` 改），
`Accept: text/event-stream`，与星岛通道同构。每条 `data:` 帧是一个 JSON，字段 `t` 为类型；
`:` 开头为心跳注释行，每 15s 一条。

事件帧也可由 LSPosed 模块在手机本地产生：模块在 com.larus.nova 进程内抓
`OmniHttpCallByNative.writeChunkData` 的 SSE 字节，用同一套事件协议
`sendBroadcast` 给 `com.islandbridge/.BridgeEventReceiver`（extra `v`=1, `ev`=json）。

## 监听地址与安全（默认只绑本机）

| 配置 | 效果 |
|---|---|
| 默认（不配 / `"bind": "127.0.0.1"`） | **只监听回环**，只有本机能连；启动日志打印 `listening on 127.0.0.1:8787 (loopback only)` |
| `config.json` 写 `"bind": "0.0.0.0"`，或 `python bridge_daemon.py --bind 0.0.0.0` | 暴露到局域网，**必须显式配置**；启动时打印 `WARNING: … NO authentication`，`GET /health` 里 `"lan": true` |

这个端口**没有任何鉴权**：事件流、`/reply`、`/delete` 谁都能调。所以默认不开局域网；
手机 App 跨机使用时才由使用者显式打开，并且只应在可信网段内使用。
`pc/server.py`（旧 WebSocket 服务）同样默认 `127.0.0.1`，需要时传 `host="0.0.0.0"`。

## HTTP 接口

| 方法 | 路径 | 请求 | 响应 |
|---|---|---|---|
| GET | `/events` | — | `text/event-stream`，帧格式 `data: {json}\n\n`，15s 心跳 `: hb` |
| GET | `/health` | — | `{"ok":true,"proto":"islandbridge-sse/1","clients":N,"bind":"127.0.0.1","port":8787,"lan":false}` |
| POST | `/reply` | `{"text":"...","cid":"..."}` | `{"ok":bool,"detail":"..."}`，成功 200，失败 502，参数错 400 |
| POST | `/delete` | `{"cid":"...","botId":"..."}` | `{"ok":bool,"detail":"...","cid":"..."}`，同上 |
| GET | `/`、`/monitor` | — | 内置监控页面 |

`cid`/`botId` 缺省时不会乱猜：`/delete` 无 `cid` 直接 400。

## 上行（发送 / 删除）

| 方向 | 通道 | 说明 |
|---|---|---|
| 手机→PC豆包 发送 | `POST /reply`，body `{"text":"...", "cid":"..."}` → `{"ok":bool,"detail":...}` | **首选**：页面内重放真实上行请求（island_hook.js `__ibSend`：克隆 body→换 cid/text/新 uuid→经 `window.fetch` 重发；cookie 自动带，签名层在下层 fetch 自动补 msToken/a_bogus）。PC 桌面端真实发送接口是 `/chat/completion`（capture/hook.jsonl 中 120 次真实请求），模板按 cid 存 `send_templates.json` 并在注入时回填；页面若走 IM 发送路径（`/im/sse/send/message`）同样可重放。**兜底**：CDP `Input.insertText`+回车注入 |
| 手机→PC豆包 删除 | `POST /delete`，body `{"cid":"...","botId":"..."}` → `{"ok":bool,...}` | 页面内 POST 真实 IM 接口，按候选顺序尝试：① `/im/conversation/del_user_conv`（cmd 1121，`{"conversation_id","mode":2,"clear_message_index":0,"conversation_type":0,"bot_id"}`）② `/im/conversation/batch_operate`（cmd 1125，`{"operate_type":8,"conversation_id_list":[cid]}`——这是桌面端**实测成功**的删除调用）。判定成功：HTTP<400 且 `status_code==0` 且 `has_failure!=true`。删除没有 CDP 兜底（桌面 UI 是鼠标右键菜单，协议侧不可靠驱动） |
| 手机→手机豆包 | 广播 `com.islandbridge.SEND`（`setPackage("com.larus.nova")`，extras `text`,`cid`） | 模块在豆包进程内反射调用 `OmniMessageService.sendMessageV2` |
| 手机→手机删除 | 广播 `com.islandbridge.DELETE`（extras `cid`） | 模块走 IM 删除接口 |
| 回执 | 事件 `{"t":"send.result","ok":bool,"err":str,"act":"com.islandbridge.SEND"\|"com.islandbridge.DELETE"}` | PC 侧发出的回执额外带 `"src":"pc"`，方便手机区分「本机操作」与「PC 代发」；字段是新增的，老逻辑忽略未知字段不受影响 |

停止生成：PC 侧**不提供**该动作。当前协议里没有 `STOP`，手机端也已移除 `ACT_STOP`
（见 `STOP_DELETE_FIX.md`：UI 12 次 dump 无停止节点、`interruptMessage` 返回
`IMError(code=-1)`、停止后仍继续收到 255 个增量帧）。真要恢复时，可用的接口是
IM `BREAK_MSG = 2240`，body key `break_stream_msg_uplink_body`，字段
`{reply_msg_id,message_id,conversation_id,conversation_type,break_reason}`
（`pc/bridge_daemon.py` 里以 `CMD_BREAK_MSG` 常量保留，未接线）。

## 事件类型

| t | 含义 | 主要字段 | 手机端动作 |
|---|---|---|---|
| `chat.start` | 新回复开始 | `mid`, 可选 `cid`,`cname` | 上岛：MEDIA 歌词项（icon MESSAGE + waveform） |
| `chat.delta` | 内容增量 | `mid`, `kind`, `text`, 可选 `cid`,`cname` | `kind=think/tool` → 歌词行滚动；`kind=text` → 回复页追加 |
| `chat.end` | 回复流结束 | `mid`, 可选 `cid`,`cname` | 歌词项 `end` + outro |
| `chat.reply` | 回复全量文本 | `mid`, `text` | **不上岛**，推给回复页渲染 |
| `chat.async` | 转后台异步任务 | `mid`, `task_id` | 歌词行提示"已转后台任务" |
| `chat.conv` | 会话标题揭晓 | `cid`, `cname` | 更新岛 MEDIA 标题 / 回复页会话名 |
| `plan.start` | 计划任务开始 | `tid`, `title`, `kind` | 上岛：LIVE_UPDATE + PROGRESS（下载样式，ring=0） |
| `plan.progress` | 计划进度 | `done`, `total`, `name` | 更新 ring 与进度条，`name`=当前子任务 |
| `plan.end` | 计划全部完成 | `success`, `text` | `end` + outro（绿勾/红叉） |
| `send.result` | 上行结果回执 | `ok`, `err`, `act`, `src` | 失败时提示并回退；`act` 决定文案 |
| `ping` | 心跳 | — | 忽略 |

所有 `chat.*` 事件可能带 `cid`（会话 id）和 `cname`（会话标题）。`cid` 来自
`STREAM_MSG_NOTIFY.meta.conversation_id`，也可从 `SSE_ACK.ack_client_meta` 学到；
`cname` 目前唯一可靠来源是 `SSE_ACK.ack_client_meta.conversation_info.name`
（PC 桌面端不发 `/im/conversation/info`），早于它出现的增量事件不带 `cname`，
标题揭晓时由 `chat.conv` 补发一次。用于区分消息属于哪个对话。

## 节流约定

`chat.delta` 可能很密；手机端对歌词更新按 ~300ms 合并（岛侧 250ms 内只上屏最后一次，全源 10 次/秒上限）。

## 字段上限（岛侧）

歌词行 ≤512 字、标题 ≤128、副标题 ≤256；超长按岛规则截断。

## 用 `simulate_doubao.py` 造流量

`pc/simulate_doubao.py` 在没有真豆包对话的情况下，用**真实链路**往手机灌上述事件帧。
它有两条投递方式，**在岛上显示的「来源」不同**：

| 方式 | 通道 | 线格式 / 落点 | 岛上来源 |
|---|---|---|---|
| `--via tcp`（**默认**） | 电脑 → 手机 `http://127.0.0.1:8799`（root 的 `listen.sh`） | 每行一帧：`<TOKEN>` + **一个字面 TAB** + `<JSON>` + `\n` | `listen.sh` 转投时带 `--extra src:s:pc` → 显示「**电脑**」 |
| `--via queue` | 直接往豆包进程的队列文件 `/data/data/com.larus.nova/files/ibq.log` 追加 **base64 行**，再由 root worker `relay.sh` 用 Binder 送进 App | 每行一个 `base64(JSON)` | 不带 `src` → 显示「**手机**」（模拟豆包 hook 自己写的队列） |

要点：

- **分隔符必须是字面的 TAB（`\t`，0x09）**，不是空格、也不是 `\t` 这两个字符。
  手机端按「令牌 + TAB + `{` 开头的 JSON」判定：令牌不足 16 位直接拒启，
  不匹配或非 JSON 一律丢弃并记一条 `dropped`。
- **`src=pc` 与「手机」的区别**：走 8799 TCP 进来的帧由 `listen.sh` 统一补 `src=pc`
  （注释原文：*anything arriving over the LAN listener is a computer-side frame*），
  岛卡片显示「电脑」；`--via queue` 走的是豆包 hook 那条路，**不带 `src`**，显示「手机」。
  这个字段只影响卡片上的来源文案与手机端「是不是 PC 代发」的判定，
  事件类型与字段与本文的表格完全一致。
- 默认连 `127.0.0.1:8799`；手机端默认只绑回环，跨机请用 `--adb-forward`
  （内部即 `adb forward tcp:18799 tcp:8799`）走 USB 隧道，**不要**为了测试把 `BIND` 改成 `0.0.0.0`。
- `--dry-run` 只打印帧、不发送，用于离线校对协议字段；`--scenario all --dry-run` 一次看全。
- `--verify`（或 `--wait`）发完会 `tail /data/local/tmp/islandbridge_relay.log`，
  用来核对手机侧回执：正常是 `tcp->binder ok` 与岛侧 `ev ok chat.*`，被拦是 `dropped`。

```powershell
cd D:\aiwork\doubaoni
python pc/simulate_doubao.py --scenario all --dry-run            # 只看帧，不发送
python pc/simulate_doubao.py --adb-forward --scenario full        # USB 隧道推一整段回答
python pc/simulate_doubao.py --via queue --scenario plan          # 走队列，岛上显示「手机」
python pc/simulate_doubao.py --adb-forward --no-token             # 安全回归：应被 dropped
```

场景 `--scenario` 取 `full|short|stream|think|plan|async|spam|all`（可逗号组合）；
常用参数 `--text`、`--interval`（默认 0.3s，与本文「~300ms 合并」一致）、`--total`、`--repeat`、
`--seed`、`--host`/`--port`、`--token`/`--token-file`、`--adb`/`--serial`、
`--no-token`/`--bad-token`/`--garbage`。完整说明见脚本文件头 docstring 与
[`README.md`](../README.md) 的「电脑端工具（`pc/`）」一节。
