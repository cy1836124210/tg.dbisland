# pc/ 适配说明（REVERSE_NOTES ↔ 代码对照）

本文件回答三个问题：**REVERSE_NOTES 的每条接口/字段在 pc/ 里落到哪一行**、
**改了什么**、**哪些故意没改以及为什么**。证据来自三处真实抓包：

| 样本 | 内容 | 状态 |
|---|---|---|
| `capture/hook.jsonl`（272 MB，27138 行） | PC 桌面端全量 XHR/Fetch/WS：请求 url/body/headers + 响应 body | 结构完好，**中文字符被写成 U+FFFD**（抓取工具编码缺陷） |
| `pc/raw_cap.jsonl`（39 条） | daemon 抓的一次 `/chat/completion` SSE 全流 | 完好 UTF-8 |
| `capture/last_stream.txt`、`capture/chunk_stream_all.txt` | 两次完整 SSE 响应体 | 完好 UTF-8 |

判定口径：只用抓包里**实际出现过**的字段与取值；没出现的一律标注「未观测」，
不凭接口名猜协议。所有断言都落在 `pc/tests/` 里可复跑。

---

## 1. REVERSE_NOTES 条目 → pc 代码位置 → 改动

| REVERSE_NOTES 条目 | pc 代码位置 | 改动 |
|---|---|---|
| §1 `POST /chat/completion`（native 侧聊天补全） | `bridge_daemon.py:85,301`；`island_hook.js:63,199` | **确认为 PC 桌面端的真实发送接口**（抓包 30 条 url 记录，含 `client_meta/messages/option/user_context/ext`，真实头 `content-type: application/json`）。`SEND_PATHS` 收录；`__ibSend` 克隆最后一次真实请求的 body → 换 cid/text/新 uuid → 经页面 `fetch` 重发；模板按 cid 落盘 `send_templates.json` |
| §1 `POST /im/sse/send/message`、`POST /im/send/message` | `island_hook.js:39-45,63`；`bridge_daemon.py:85`；`_tpl_cid()` `bridge_daemon.py:280` | 加入监听与可重放集合（IM 信封形态的 cid 提取同时支持 `client_meta` 与 `uplink_body.*`）。**PC 端 0 次调用**，属手机端路径；保留是为了同一份 hook 两端通用 |
| §1 `/chat/async/chunk_stream` | `bridge_daemon.py:153`（`WATCH_STREAM`） | 已在监听（抓包 12 条），异步分块流走同一 parser |
| §1 `/alice/message/stream`、`/chat/lite`、`/chat/lite/origin_pull`、`/alice/message/pre_handle_v2`、`/im/message/fix_regenerate` 等 | — | **未改**：PC 抓包 0 次，无字段可对齐（见 §2 表） |
| §2 `/im/chain/thread_message`、`/im/chain/single` | `bridge_daemon.py:154`（`WATCH_JSON` 用 `/im/chain/` 前缀）；`island_hook.js:52-53` | 已在监听（66 / 42 条） |
| §2 `/alice/job_cron/*`、`/alice/generaltask/terminate`、`/app_framework/task_center/*`、`/task/create_remote_task` | — | **未改**：PC 抓包 0 次。plan 进度当前由 `/im/thread/info` + `/im/conversation/abstract` 提供，够用 |
| §3 SSE 路径 `/message/content`、`/content/content`、`/text_block/text` | `feed_parser.py:387`（`_walk_blocks`，按 `block_type` + `content.*_block.text` 取值） | 统一由「内容键驱动」的 `_block_line()`（`feed_parser.py:68`）解析，不再按路径字符串前缀猜 |
| §3 `/message/thinking_content`、`/content/thinking_content`、`/thinking_block/summary` | `feed_parser.py:68`、`387` | thinking/generic_tool/search/file_operation/local_file/loading/text_loading 各类块产出 `kind="think"` 状态行（真实样本见 `last_stream.txt` 20 行、`chunk_stream_all.txt` 149 行） |
| §3 `/msg_finish_attr/brief` | `feed_parser.py:460`（`_on_reply_end`） | **仅作兜底**：服务端把 `brief` 截断（`raw_cap` 100 字 / `last_stream` 300 字 vs 正文 5531 字），正文一律用累积的 `chat.delta` 文本；只有什么都没流到时才用 brief |
| §3 `/queries/*`、`/text_card/{title,summary,url}` | `feed_parser.py:41`（`_search_line`） | 合成的状态行：`搜索 1 个关键词，参考 10 篇资料 · 「query」 · 首条标题`（两段真实样本逐字对齐） |
| §3 `/suggest_questions_v2/*/content` | `feed_parser.py:180`（`_sse_frame` 只识别 7 个 event 名） | **未解析**：该路径在两份 SSE 样本里 0 次；唯一相关信号是 `SSE_REPLY_END.end_type=2` 的 `answer_finish_attr.has_suggest`，目前**不发射事件**（手机端也没有承接字段） |
| §3 `/creation_block/creations/*/{prompt,neg_prompt,tips,description,placeholder}` | `feed_parser.py:68` | **未专项解析**（0 次观测）：作为普通块跳过，不猜结构 |
| §3 `/patch_value/content` | `feed_parser.py:359`（`_on_chunk`，`patch_object` 1/3） | 实测语义：`patch_object` 1/3 → `patch_value.content_block`，走 `_walk_blocks` 增量 |
| §3 `/patch_value/ext/sp_v2` | `feed_parser.py:438`（`_on_ext` 末尾） | **只记录不解析**：`self.sp_v2_seen`，两份样本 0 次（字段名对不上实际值，不臆造事件） |
| §3 `/loading_block/text`、`/text_loading/text` | `feed_parser.py:68` | 已实现状态行；`/text_loading/text` 与 10006「正在读取网页」两路都覆盖 |
| §3 `/im/conversation/info` → 会话标题 | `feed_parser.py:624`（`_scan_conv_names`）、`220`（`_on_ack`） | 两条来源都接：① `SSE_ACK.ack_client_meta.conversation_info.name`（**最早**，随发消息 ack 到达）② `/im/conversation/info` 的 `downlink_body.get_conv_info_downlink_body.conversation_info.name`（抓包 56 条，实测会把自动生成的标题回填）。标题学习/变化时发一次 `chat.conv`；消息形状对象被显式排除 |
| §3 `/im/thread/info` | `feed_parser.py:538`（`_on_thread_info`）、`577`（`_drain_json` 路由） | **行为修正**：原实现只在 `thread_status` 变化时发事件，而实测 `thread_status` 恒为 `"running"`（432 次，无一次 `completed`）→ `plan.*` 永远不会触发。改为**首次出现即发 `plan.start` + `plan.progress`**，其后只在状态/名字变化时更新 |
| §3 `/im/conversation/abstract` | `feed_parser.py:601`（`_scan_abstract`） | 只挖 `complex_task_block`（plan/线程信息）：该响应重放整段会话，若走通用块遍历会把历史消息当新回复推上岛 |
| §3 `/im/conversation/batch_get`、`/im/message/query_history` | — | **未改**：批量拉历史不是实时桥的需求 |
| §4 `/im/conversation/del_user_conv`、`batch_del_user_conv` | `bridge_daemon.py:95`（`delete_candidates[0]`）、`573`（`delete_conv`）；`sse_server.py:140`（`POST /delete`）；`island_hook.js:227`（`__ibDelete`） | **新接线**：`POST /delete {"cid","botId"}` → 页面内真实 IM 调用。信封按 DEX dump（`D:\aiwork\apk\d24.txt`）构造：`{"cmd":1121,"uplink_body":{"delete_user_conv_uplink_body":{"conversation_id","mode":2,"clear_message_index":0,"conversation_type":0,"bot_id"}},"sequence_id":"<uuid4>","channel":2,"version":"1"}`，`mode` 取 `ConversationDeleteMode.DeleteMode_RealDelete` |
| §4 `/im/conversation/batch_operate` | `bridge_daemon.py:95`（`delete_candidates[1]`，`im_envelope` `:88`） | 作为候选②：`{"cmd":1125,...,"batch_operate_conv_uplink_body":{"operate_type":8,"conversation_id_list":[cid]}}`，**与抓包里桌面端自己删除会话的请求逐字段一致**（`pc/tests/fixtures/batch_operate_delete.json`，响应 `{"status":8,...,"success":true,"has_failure":false}`） |
| §4 `/im/message/break_stream_msg`（停止生成） | `bridge_daemon.py:67`（`CMD_BREAK_MSG = 2240`，仅常量 + 文档） | **故意不实现**：见下 §4「没改的东西」 |
| §4 `/im/conversation/in_out`、`/im/message/mark_conv_read`、§5 `/im/conversation/create|main|modify|update_name|...`、`/im/project/*` | — | **未改**：与「弹窗桥」目标无关 |
| 概览：`api-normal.doubao.com`、`www.doubao.com` 等域名 | `island_hook.js:68`（`noteUrl` 记 `__ibOrigin`/`__ibQuery`）、`227`（`__ibDelete` 用 `__ibOrigin + path + __ibQuery`） | **不硬编码域名**：删除/发送请求的 origin 与查询串一律取自页面自己发过的同域请求 |
| LSPosed / Frida Hook 点（§Hook 点） | — | 属 `android/` 范围，本次未动 |

### 1.1 IM 信封的三个字段语义（抓包 + DEX 双证）

| 项 | 值 | 依据 |
|---|---|---|
| 顶层 | `cmd` / `uplink_body{<key>:body}` / `sequence_id`(uuid) / `channel:2` / `version:"1"` | 抓包 `batch_operate` 请求逐字段比对（测试 `test_batch_operate_matches_the_real_captured_request`） |
| 删除单会话 | `cmd 1121`、key `delete_user_conv_uplink_body`、`mode 2` | `d24.txt` `IMCMD` 枚举 + `DeleteUserConversationUplinkBody` 序列化字段名 + `DeleteMode_RealDelete=2`（**PC 抓包 0 次，无线上证据**） |
| 批量会话操作 | `cmd 1125`、key `batch_operate_conv_uplink_body`、`operate_type 8` | 抓包实测成功的那一次调用 |
| 打断流 | `cmd 2240`、key `break_stream_msg_uplink_body`、字段 `{reply_msg_id,message_id,conversation_id,conversation_type,break_reason}` | `d24.txt`；**未接线**（UI 层无可靠触发点） |

---

## 2. 抓包实测的接口出现次数（判定「PC 端到底走不走这条路」）

统计方式：`json.loads` 每行的 `url` 字段后按路径匹配（**不是整行子串**，
避免被 `resBody`/`_turl` 里的字符串污染），命令见 §6。同一个 HTTP 请求可能
留下多条记录（请求 + 回包捕获），所以数字是记录数；`0` 表示 PC 抓包里从未出现。

| 接口 | 记录数 | 结论 |
|---|---|---|
| `/chat/completion` | 30 | PC 真实发送接口（其中带请求体的发送请求 1 条，其余是该请求的流式回包记录）：`content-type: application/json`、`agw-js-conv: str, str` |
| `/chat/async/chunk_stream` | 12 | 异步分块流 |
| `/im/thread/info` | 258 | plan/线程进度主要来源 |
| `/im/conversation/abstract` | 386 | 会话重放（含 `complex_task_block`） |
| `/im/chain/thread_message` / `/im/chain/single` | 66 / 42 | 链式消息拉取 |
| `/im/conversation/info` | 56 | **会带 `conversation_info.name`**，标题的第二来源 |
| `/im/conversation/batch_get` | 16 | 批量会话信息 |
| `/im/conversation/batch_operate` | 6 | 含那次 `operate_type:8` 的成功删除 |
| `/im/conversation/modify` / `/im/project/*` | 14 / 4 | 与桥无关 |
| `/im/conversation/del_user_conv` / `batch_del_user_conv` | 0 / 0 | **PC 端从不调用**（REVERSE_NOTES §4 列了它，但桌面端删除走 batch_operate） |
| `/im/sse/send/message` / `/im/send/message` | 0 / 0 | Android 侧接口，PC 端不调用 |
| `/im/message/break_stream_msg` | 0 | 停止生成在 PC 端从未发生 |
| `/alice/generaltask/terminate`、`/alice/job_cron/*`、`/alice/message/stream`、`/chat/lite`、`/im/conversation/operate`、`/im/message/query_history`、`/im/conversation/create`、`/im/conversation/main` | 0 | 与 PC 桌面端实现无关 |

`complex_task_block` / `thread_status` 的取值分布（同样逐行统计）：

| 字符串 | 行数 | 说明 |
|---|---|---|
| `已开始工作` | 282 | `complex_task_block.status = 4` 的 organizer 卡片 |
| `已完成工作` | 6 | `complex_task_block.status = 2` 的 `agent_type=sub_agent` |
| `organizer` / `sub_agent` | 318 / 30 | `display_type` / `agent_type` |
| `thread_status` | 432 | **全部是 `"running"`，没有一次 `completed`**（正则取值统计结果：`{'running': 432}`） |

---

## 3. 发现的 REVERSE_NOTES ↔ 代码/抓包不一致

1. **发送接口两端不同。** REVERSE_NOTES §1 把 `/im/sse/send/message` 排在第一行，
   但 PC 桌面端 60 次发送全部走 `/chat/completion`，`/im/*/send/message` 0 次。
   文档据此已按「同协议、不同实现」写明（`pc/protocol.md` 上行表）。
2. **删除接口两端不同。** §4 指向 `/im/conversation/del_user_conv`，PC 端 0 次；
   桌面端真实删除是 `/im/conversation/batch_operate` + `cmd 1125` + `operate_type 8`。
   pc 侧两个都试（单会话接口优先），并把抓包证据固定成 fixture 测试。
3. **`patch_object` 语义未记录。** 实测：`1`/`3` → `patch_value.content_block`；
   `50` → `patch_value.ext`；`111` → `patch_value.tts_content`。REVERSE_NOTES 只写了
   `/patch_value/content`。tts 一帧一帧共 251 次，拼接后与正文**等长**（5531 字），
   因此**只记录不发射**，否则岛上文字翻倍（测试 `test_tts_content_never_double_emitted`）。
4. **`async_job` 是 JSON 字符串，不是对象。**
   `"async_job":"{\"job_id\":\"…\",\"status\":2,\"append_scene\":6}"`。
   老代码直接对它调 `.find('"status":2')`：服务端若改成对象就会 `AttributeError`。
   新增 `FeedParser.async_job_status()`（`feed_parser.py:416`）兼容字符串/对象两种，
   并区分 `status 1`（异步任务进行中，出现在 `ASYNC_CHUNK_SNAPSHOT`）与 `status 2`（收尾，出现在 `is_finish:"1"` 同帧）。
5. **`complex_task_block.status` 的取值含义与手机端判定相反。**
   实测：`4` = `header.summary "已开始工作"`（282 行，`display_type=organizer`）、
   `2` = `"已完成工作"`（6 行，`agent_type=sub_agent`）。
   手机 `MobileFeedParser.kt` 把 3/4 当「已完成」→ **同一个 payload，手机认为完成、
   PC 认为仍在跑**。pc 侧以 2/`completed` 为完成。这需要 android 侧确认（不在本次改动范围）。
6. **`plan.end` 在真实数据里从未出现。** `thread_status` 在抓包里 432 次**全是**
   `"running"`，没有任何线程真正结束。pc 侧按 `completed/done` 触发 `plan.end`，
   但只有手写帧覆盖（测试里明确标注 `synthetic`）。**线上是否会出现该字段仍未知。**
7. **标题来源被低估。** §3 只提到 `/im/conversation/info`；实测最早来源是
   `SSE_ACK.ack_client_meta.conversation_info.name`（随发送 ack 到达，且 PC 端
   `/im/conversation/info` 的响应里标题可能还是「新对话」，稍后才变成自动生成的标题）。
   两条都接，标题变化时补发 `chat.conv`。
8. **`brief` 不可信。** `msg_finish_attr.brief` 被服务端截断（100 / 300 字），
   与流式正文（5531 字）差一个数量级，不能当正文（仅作空回复兜底）。
9. **`sp_v2` / `suggest_questions_v2` / `creation_block` / `loading_block` / `text_loading`：
   两份 SSE 样本 0 次。** 前三个 pc 侧不解析（不臆造字段），后两个按 §3 实现但同样无线上证据。
10. **`fin_reason.async_task` 从未出现**（0 次）→ `chat.async` 事件只有手写帧覆盖。
11. **样本编码坑。** `capture/hook.jsonl` 里中文全是 U+FFFD（结构完好、值不可读）；
    `pc/raw_cap.jsonl`、`capture/*.txt` 是完好 UTF-8。另外 `pc/events.jsonl` 是
    daemon 的**输出**日志（`{"t":…}` 帧），不是原始抓包，喂给 `FeedParser` 得 0 事件。
12. **手机侧 PC 路径目前不调用 `/delete`。** `android/.../IslandBridge.kt` 的
    `onAction` 在删除分支只做 `dismissReply("已删除")`，没有 HTTP 调用；pc 侧新接口
    已就绪，需要 android 侧接线才能闭环。
13. **`send.result` 新增 `src:"pc"` 字段的副作用。** 手机 `onSendResult(ok=true)`
    会无条件下 `dismissReply(...)`；PC 代发时 HTTP 响应已经 dismiss 过一次，这个
    追加事件可能误关「期间新出现的卡片」。pc 侧按任务要求照发（并加 `src` 便于过滤），
    建议 android 侧按 `act`/`src` 过滤或加 `replyShown` 守卫。

---

## 4. 明确没有改动的东西，以及原因

| 没改的 | 原因 |
|---|---|
| **停止生成（`/im/message/break_stream_msg`）** | `STOP_DELETE_FIX.md` 已证死：12 次 UI dump 无停止节点；`interruptMessage` 返回 `IMError(code=-1)`；点了停止后仍继续收到 255 个增量帧。手机端也已删除 `ACT_STOP`。协议里加一个没人能触发的动作只会制造假接口。pc 侧只保留 `CMD_BREAK_MSG=2240` 常量与信封字段说明，供将来真要做时用 |
| **签名层（msToken / a_bogus / X-Bogus）** | 签名在 App 自身网络栈里、随请求生成，离线无法复现。pc 侧一律「在页面内用页面自己的 `fetch` 发包」，签名由 App 补齐；删除请求也只做「构造 envelope + 页面内 POST」，不自己算签名 |
| **`/alice/*`、`/im/project/*`、任务中心、定时任务、语音通话** | PC 抓包 0 次，没有可对齐的字段；不是弹窗桥的需求 |
| **`suggest_questions_v2` / `creation_block` / `sp_v2` 的解析** | 0 次观测，凭名字猜结构会引入假字段（本次任务的硬性要求） |
| **`chat.reply` 的语义** | 保持不变（不上岛、推给回复页）；只把正文来源从 `brief` 换成累积流式文本 |
| **手机端一切代码（`android/`）、`com/`、`README.md`、`pc/dsh-plugin/`** | 非本任务范围 |
| **`pc/monitor.py` 的默认地址** | 本来就是 `127.0.0.1:8787`，无需改（与新的默认绑定一致） |
| **`pc/server.py`（旧 WS 服务）的行为** | 只加了 `host` 参数与默认回环 + 告警，协议与帧格式一字未动 |

---

## 5. 未完成 / 未验证清单

1. **从未真机验证**：本次改动只跑了离线回归（真实抓包 + 单元测试），
   没有启动豆包、没有连 CDP。发送/删除接口的**线上成功路径未实测**（删除的
   cmd 1121 尤其如此：PC 端 0 次抓包，只有 DEX 依据）。
2. `plan.end`、`chat.async` 两个事件只有手写帧覆盖（原因见 §3.6、§3.10）。
3. `complex_task_block.status == 2`（已完成）在 pc 抓包里只出现 6 行（`sub_agent`），
   `_thread_seen` 的完成判定**没有跨会话端到端验证过**。
4. `send.result` 的 `src:"pc"` 字段手机端尚未消费（android 侧未改）。
5. `/delete` 没有任何一个真实调用方（android `onAction` 还没接线）。
6. pc 侧未实现「停止生成」，理由见 §4；如果产品坚持要，需要先解决 UI 触发点，
   单靠 IM 信封无法保证生效。
7. 手机 `MobileFeedParser.isDone` 与 pc 的完成判定冲突（§3.5），需 android 侧定夺。

---

## 6. 复跑命令与结果

环境（实测）：`python 3.9.12`（`D:\tool\miniconda\python.exe`）、`node v22.23.2`。
**没有 pytest，也没有安装任何新包**；测试全用标准库 `unittest`。

```powershell
# 1) 全部回归（50 个用例）
> python -m unittest discover -s pc/tests -v
Ran 50 tests in 8.7s
OK

# 2) JS 语法
> node --check pc/island_hook.js
（无输出 = 通过）

# 3) Python 语法
> python -m py_compile pc/feed_parser.py pc/bridge_daemon.py pc/sse_server.py pc/server.py
（无输出 = 通过）

# 4) 接口计数证据（§2 表即其输出）：一次性脚本逐行子串统计 capture/hook.jsonl
#    lines 27138 / /chat/completion 30 / /im/sse/send/message 0 ...
#    （按 json.url 字段的路径匹配，不是整行子串）
#    统计脚本与结论中的一次性探针（_probe_*.py）在落地后已全部删除，
#    保留下来的断言都在 pc/tests/test_*.py 里可复跑。
```

fixtures 由真实抓包生成（已随仓库提供，测试不依赖 272 MB 的 `capture/hook.jsonl`）：

```powershell
> python pc/tests/make_fixtures.py
thread_info.jsonl: 2 record(s) -> 2438 B
abstract_complex_task.jsonl: 2 record(s) -> 25290 B
conv_info.jsonl: 3 record(s) -> 88499 B
> python pc/tests/make_delete_fixture.py
wrote ...\fixtures\batch_operate_delete.json 1448 B
```

对照实现时用到的一次性探针脚本（`_probe_*.py`）在结论落地后已全部删除，
留下的断言都在 `pc/tests/test_*.py` 里。
