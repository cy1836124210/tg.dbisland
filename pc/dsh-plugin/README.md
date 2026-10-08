# dsh-islandbridge

豆包(Doubao) → 星河岛(Island) 桥接的 **DSH host 插件**（「组合包」/bundle 形态）。
它把原来 `pc/` 里那个独立 Python 进程的 SSE 事件面搬进 DSH 宿主进程，
复用 DSH 自己的 `webServer` 服务，不再需要一个自建的 `0.0.0.0` HTTP 服务器。

> 结论、与本机版本的差异、未验证项，都在仓库根目录的
> **[`docs/DSH_PLUGIN.md`](../../docs/DSH_PLUGIN.md)**。本文件只讲怎么用。

## 它提供什么

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/islandbridge/events` | SSE。帧格式 `data: {json}\n\n`，与 `pc/protocol.md` 同构；心跳 `: heartbeat\n\n` |
| `POST` | `/islandbridge/event` | 推一条事件帧，fan-out 给所有 SSE 订阅者；返回 `{"ok":true,"delivered":N}` |
| `GET` | `/islandbridge/health` | `{"ok":true,"proto":"islandbridge-sse/1","clients":N,...}` |
| `GET` | `/islandbridge/` | 极简人工监控页（令牌走 `?token=`） |

**所有**端点都要求令牌，包括 `/health`：

```
Authorization: Bearer <token>        # 或
GET /islandbridge/events?token=<token>
```

浏览器 `EventSource` 不能自定义请求头，所以监控页用 `?token=`。

## 装到 DSH profile

```powershell
# 从本机目录装（开发用）
dsh plugin --profile desktop add D:\aiwork\doubaoni\pc\dsh-plugin

# 或先把包发到 registry，再按名字装
dsh plugin --profile desktop add dsh-islandbridge
```

装完后在 **profile** 的 `cordis.patch.yml`（`%USERPROFILE%\.dsh\profiles\desktop\cordis.patch.yml`）
里按 id 覆盖配置 —— 不要改本包内的 `cordis.patch.yml`：

```yaml
- id: islandbridge
  name: dsh-islandbridge
  config:
    token: '<你的固定令牌，务必设置>'
    basePath: /islandbridge
```

令牌的建议来源：环境变量或本机密码库。**没有**内置的「从环境变量读 token」逻辑，
因为配置层已经能写 `!!js` 表达式（本机 profile patch 里就在用），例如：

```yaml
    token: !!js process.env.ISLANDBRIDGE_TOKEN
```

没配 token 时插件会 `crypto.randomBytes(32)` 生成一个**临时**令牌并在日志里打印一次；
重启就换新的，所以生产上应当显式配置。

## 配置项

| 字段 | 默认 | 含义 |
|---|---|---|
| `token` | `''` | 共享令牌。留空 = 每次启动随机生成并打印一次 |
| `basePath` | `/islandbridge` | 所有路由的前缀。不能是 `/`（会吞掉 SPA fallback） |
| `maxFrameBytes` | `65536` | `POST /event` 单帧字节上限（超出 413） |
| `maxFramesPerSecond` | `60` | 注入速率（令牌桶） |
| `maxFrameBurst` | `120` | 令牌桶容量（突发额度） |
| `heartbeatSeconds` | `15` | SSE 心跳间隔 |
| `maxClients` | `32` | 并发 SSE 订阅者上限（超出 503） |
| `maxClientBufferBytes` | `1048576` | 单订阅者 socket 缓冲上限，超出丢帧而不拖垮全局 |
| `allowedTypes` | `[]` | 非空时只放行这些 `t` 值 |
| `allowNonLoopback` | `false` | 见下 |
| `allowCors` | `false` | 是否发 `Access-Control-Allow-Origin: *` |

## ⚠️ 监听地址：本插件不能改绑定

端口和 host 属于 `@deepseek-ai/dsh-host-webserver` 的配置，插件只是往那个服务上**注册路由**。
所以：

- 路由与 DSH Web GUI **同端口、同接口**（本机默认 `127.0.0.1:3080`，本会话是 `127.0.0.1:19387`）。
- 本机 DSH 明确**拒绝** `--host 0.0.0.0`：
  `dsh-web-app/lib/startup.js` 里写死
  `error: --host 0.0.0.0 is intentionally not supported yet for safety: it would expose remote code execution to the network`。
- 因此本插件默认**拒绝**在非回环 host 上挂载，并打 `error` 日志；
  要用局域网暴露必须显式 `allowNonLoopback: true` —— 而那会同时把整个 DSH Web GUI
  暴露到同一张网卡上。手机侧接入的推荐做法见 `docs/DSH_PLUGIN.md`。

## 开发/验证

不需要 `pnpm install`，测试脚本零依赖（真 YAML/schemastery 直接从本机 DSH 的
`app.asar` 里提取）：

```powershell
node --run test
```

五个套件（**94 条断言，0 失败**）：清单/打包形态（真 js-yaml）、真 schemastery 下的
Config、假 ctx + 真 HTTP 的行为、裸 socket 的线级 SSE 字节，以及
**真加载器验证** —— 从 asar 抽出约 1.5 MB 的真依赖闭包（真 `@deepseek-ai/cordis` +
真 `@deepseek-ai/dsh-host-webserver`），把插件按真包布局装进 `node_modules` 后由真
`Context` apply，验证鉴权/SSE/404 与**真生命周期卸载**（`fiber.dispose()` 是异步的，
必须 await）。本机没装 DSH 时该套件自动 SKIP（不会假装通过）。
