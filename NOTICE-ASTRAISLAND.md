# 第三方组件与许可

## 星河岛 SDK 0.1.0（`com.astraisland.sdk`）

- 来源：<https://astraflow.cc/island/download.html>
- 文件：`android/app/libs/astraisland-sdk-0.1.0.aar`
  （SHA-256 `7C7E86AB3DECD3F2D939283D72AEA303400335A99C4B3AA58EAC5F9A9F144B43`）
- **Required Notice: Copyright 2026 MuYuanXing / AstraIsland**
- 许可：**PolyForm Noncommercial License 1.0.0**（全文见
  [`third_party/astraisland-sdk-LICENSE.txt`](third_party/astraisland-sdk-LICENSE.txt)，
  原始声明见 [`third_party/astraisland-sdk-NOTICE.md`](third_party/astraisland-sdk-NOTICE.md)）
  —— **仅限非商业用途**；用于商业用途须另行取得授权。
- 分发本 App 时**必须保留**上述版权与许可声明，因此该文件随仓库一并保留。

## 旧接入库已停止服务

`astraisland-client`（1.3.0，协议 5/6）已由上游停止提供，新版星流不再接受
其连接。本项目 v1.1 起改用 SDK 0.1.0（通信版本 7），旧的
`android/app/libs/astraisland-client.aar` 已删除。

## Xposed API

`android/app/libs/api-82.jar` —— classic Xposed API stub
（<https://api.xposed.info/>），仅编译期 `compileOnly` 使用，不随 APK 分发。

## 其他依赖

| 组件 | 许可 |
|---|---|
| AndroidX Core KTX 1.17.0 | Apache-2.0 |
| OkHttp 4.12.0 | Apache-2.0 |
| org.json 20240303 | JSON License |
