# Open Aria Echo Mobile

Open Aria Echo Mobile 是原生 Android 客户端。Flutter 版本保留在 `flutter-bak` 分支。

当前主线已从 Canvas 原型迁移到 Kotlin + Jetpack Compose 应用壳。界面默认中文，支持 English 切换；保留既有黑色工作台、青色实时态、洋红峰值、红色录制、黄色警告和绿色许可配色。

应用启动后先进入“连接机身”页。附近机身通过 Android NSD/mDNS 的 `_ylx-capture._tcp` 服务发现；手动地址、历史记录和发现候选都必须经过 Device API v4 `/api/v4/device` 探测、契约校验和鉴权后，才会进入已验证工作台。未连接时不会展示假机身、假 ready、假视频、假录制指标或无行为按钮。

当前移动端已锁定 Device API v4 OpenAPI，并对当前产品能力实现真实客户端路径：JPEG 最新帧预览、双目/左眼/右眼检视、网格与 RAW IMU 预览叠加、权威采集状态、SSE 对账、幂等 start/stop、能力门禁的标定采集、会话台账筛选、未成功结果、manifest、制品 HEAD/Range 下载、SHA-256 校验、打开/分享、机身身份、固定存储状态、运行时网络状态、相机连接、相机对焦和应用更新。按照 Score D-049，当前 Android 产品不提供可移除介质或换盘工作流；契约中遗留的 wire 解析只作为冻结兼容代码保留。

制品下载可以校验服务端 Range 响应，但不会跨用户尝试复用 `.part`：取消或失败会清理本次临时文件，普通重试从 byte 0 开始。

连接后的状态请求由连接代次和前台生命周期统一约束：capture/network SSE 健康时作为实时来源，HTTP 状态只做启动与每 30 秒一次的低频对账；SSE 明确不可用时使用有界退避轮询。预览仅在应用前台且“取景”页可见时串行获取，会话和对焦仅在启动、进入对应页面或相关事件后刷新，旧连接的晚响应不会覆盖新连接状态。

网络页使用 `/network`、`/network/scan`、`/network/credentials`、`/network/apply`、`/network/retry`、`/network/forget` 和 `/network/events`。apply 支持热点、Wi-Fi client、有线 DHCP 和有线静态 IPv4 四种期望状态。Wi-Fi 密码只用于换取一次性 `credential_ref`；apply 请求不会携带明文密码。网络切换、retry 和 forget 只在设备描述符与 `/network` mutation capability 同时允许、且采集处于 idle 时开放；只呈现设备明确返回的事务结果和正常回退，断链时显示错误且不恢复或重放中断事务。

## Build

```bash
./gradlew assembleDebug
```

The Android package name remains `com.openaria.openaria_echo_mobile`.

## Verify

```bash
./gradlew verifyUnitTestSources verifyReleaseSafety testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug
```

需要真机或已启动 emulator 时，可额外运行：

```bash
./gradlew connectedDebugAndroidTest
```

CI 会通过 `ReactiveCircus/android-emulator-runner@v2.38.0` 创建 Pixel 2 / API 35 emulator，并显式使用 `-gpu swiftshader` 执行 `connectedDebugAndroidTest`。不再使用 AGP managed device；当前 emulator 37 会拒绝 AGP 传入的 `auto-no-window` GPU 参数。

本地 JVM 测试覆盖 Device API v4 契约哈希、严格 JSON validator、EndpointPolicy、Keystore token 包装、连接历史、Device API 客户端、MockWebServer 集成路径、采集状态投影、网络事务/SSE、制品传输计划和中英文资源一致性。

## Development

- [下一步开发计划](docs/DEVELOPMENT_PLAN.md)
- [领域上下文与统一术语](CONTEXT.md)
- [界面与功能审查](dogfood-output/report.md)
