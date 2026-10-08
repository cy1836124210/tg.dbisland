# pc/tests — 回归测试

纯标准库 `unittest`（**没有** pytest 依赖，也没有任何第三方包）。

**当前共 72 个用例，全部通过**（`test_feed_parser` 25 + `test_uplink_protocol` 15 +
`test_sse_server` 15 + `test_simulate_doubao` 17）。实测输出：

```
Ran 72 tests in 10.470s
OK
```

## 运行

```powershell
# 全量（在仓库根目录 D:\aiwork\doubaoni 下）
python -m unittest discover -s pc/tests -v

# 只跑某一块
python pc/tests/test_feed_parser.py
python pc/tests/test_simulate_doubao.py
python -m unittest discover -s pc/tests -p "test_uplink_protocol.py" -v
```

`-s pc/tests` 后面**不要**加 `-t .`：`pc/` 不是 Python 包（没有 `__init__.py`），
指定 top-level 目录会让 discover 报 `Start directory is not importable`。

## 覆盖内容

| 文件 | 被测对象 | 用到的真实样本 |
| --- | --- | --- |
| `test_feed_parser.py` | `pc/feed_parser.py` | `pc/raw_cap.jsonl`、`capture/last_stream.txt`、`capture/chunk_stream_all.txt`、`fixtures/*.jsonl` |
| `test_uplink_protocol.py` | `pc/bridge_daemon.py` 的 envelope 构造与 `send.result` 契约 | `capture/hook.jsonl` 抽出的 `fixtures/batch_operate_delete.json`、`pc/send_templates.json` |
| `test_sse_server.py` | `pc/sse_server.py` + `pc/server.py` | 自起临时端口，不需要豆包/CDP |
| `test_simulate_doubao.py` | `pc/simulate_doubao.py`（帧构造 + 8799 线格式） | **不需要设备**：自起 loopback socket 收线格式；帧构造与 `--dry-run` 全离线 |

`test_simulate_doubao.py` 的三组用例：

* `BuildFramesTest`（12）—— 各场景的帧序列、`mid`/`cid` 共用、切块可还原、字段上限截断、
  `plan.progress` 的 `done/total`、`async`、`repeat` 产生不同 `mid`、未知场景报错、JSON 可序列化；
* `WireFormatTest`（3）—— 真起一个 loopback socket 收字节，验证
  `<TOKEN>\t<JSON>\n` **一行一帧、TAB 分隔、令牌前缀**；`--no-token` 发裸 JSON（安全回归用，
  手机端必须丢弃）；`--garbage` 发非 JSON；
* `CliTest`（2）—— `--dry-run` 不发送；`--via queue` 找不到 adb 时必须**显式报错**而不是静默成功。

`capture/` 不在仓库里时，依赖它的测试会 `skipTest`（`pc/raw_cap.jsonl` 与
`fixtures/` 已随仓库提供，所以主链路测试始终会跑）。

## 交叉核对脚本（不是 unittest，手工跑）

`verify_complex_task_status.py` 直接解析 272 MB 的 `capture/hook.jsonl`，输出
`complex_task_block.status` 与它同一条记录里的人类可读文案的联合分布 ——
用来复核 `pc/feed_parser.py` 与手机 `MobileFeedParser.isDone` 的完成判定：
`4`+「已开始工作」+`organizer` 与 `2`+「已完成工作」+`supertask`。
（结论：**只有 2 算完成**；手机端原先把 3/4 当完成，已在 v1.1 修正。）

```powershell
$env:PYTHONIOENCODING='utf-8'; python pc/tests/verify_complex_task_status.py
```

## fixtures 从哪来

`fixtures/*.jsonl` 是把 `capture/hook.jsonl` 里真实的服务端响应体，按
daemon 的记录格式（`{"k":"req","ev":"body","url":…,"data":"<base64>"}`，
即 `bridge_daemon._grab_body` 的输出）原样转换过来的，仓库内可直接测。

需要重新生成时（可选，依赖 272 MB 的 `capture/hook.jsonl`）：

```powershell
python pc/tests/make_fixtures.py
python pc/tests/make_delete_fixture.py
```

## 明确标注为“合成帧”的用例

真实抓包里不存在的分支，用与文档词汇表一致的手写帧覆盖，并在用例 docstring
里注明：

* `FixtureTest.test_plan_end_synthetic` — 抓包中 `thread_status` 只有
  `running`（282 次），没有任何线程真正结束。
* `ChunkStreamTest.test_chat_async_synthetic` — 抓包中完全没有
  `fin_reason.async_task`。

## 断言口径

* `patch_object 111`（`tts_content`）只记录、不发射：`last_stream.txt` 里 251 帧
  拼起来与正文**等长**（5531 字符），若一起发射岛上文字会翻倍。
* `SSE_REPLY_END` 的 `end_type` 2/3 不关回复，只有 `end_type 1` 的
  `msg_finish_attr.msgid` 能定案（`raw_cap.jsonl`、`last_stream.txt` 两种样本都有）。
* `patch_value.ext` 的 `is_finish == "1"` 与 `async_job`（**字符串**形式的 JSON）
  的 `status == 2` 都会结束回复。
