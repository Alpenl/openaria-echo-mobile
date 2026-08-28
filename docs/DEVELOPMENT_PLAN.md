# Open Aria Echo Mobile 下一步开发计划

> 状态：执行基线；实施时随权威契约变更更新
> 日期：2026-08-28
> 范围：Android 客户端从高保真 Canvas 原型升级为可真实控制 OpenAria 机身的生产应用
> 不可变约束：保留现有配色；默认简体中文；第一阶段只支持简体中文和英文；Device API v4 是唯一设备契约

## 1. 目标与工程判断

当前应用不是在一个“已有功能、局部错位”的产品上修补，而是一个 1089 行 `MainActivity.java` 承载的 Canvas 交互原型。它准确表达了一部分视觉方向，但设备发现、鉴权、预览、录制、对焦、网络和会话大多没有真实实现。后续开发应把它当作视觉参考，而不是继续在坐标绘制层叠加业务逻辑。

本计划的最终目标是交付一个具备以下属性的 Android 应用：

- 所有设备、录制、存储和网络事实都来自 Device API v4，不再展示伪造状态或固定指标。
- 能发现、手动添加、鉴权并连接真实机身，断线后能够明确提示和自动对账。
- 能稳定显示真实双目预览，支持双目、左眼、右眼、网格、对焦峰值和 IMU 叠加层。
- 能开始和停止普通录制及能力允许的标定录制，并正确呈现封存、校验、失败和安全换盘状态。
- 能查看会话、区分生产方结果与独立可用性判定、下载和校验制品。
- 能查看和变更网络，正确处理切换过程中控制链路断开、结果未知和救援热点恢复。
- 顶部、底部、弹层、横竖屏和大字体布局稳定，不与状态栏、摄像头开孔、手势区重叠。
- 中文是首次安装默认语言，英文可在应用内切换；普通界面不再中英混排。
- TalkBack、键盘/开关控制、动态字体和颜色对比达到可用标准。
- 保留目前得到认可的黑色工作台、青色实时态、洋红峰值、红色录制、黄色警告和绿色许可配色。

完整问题证据见 [界面与功能审查](../dogfood-output/report.md)。

## 2. 不可妥协的产品原则

### 2.1 设备事实优先

- 移动端只投影机身权威快照，不创建本地“正在录制”“已经安全换盘”等乐观事实。
- 点击快门后可以显示“正在发送命令”，但只有设备返回的快照进入 `recording` 后，界面才能显示“正在录制”。
- SSE 事件只负责加速更新，不是唯一真相来源。缺事件、修订号跳跃、恢复前台或重新联网时必须重新读取 HTTP 权威快照。
- 同一 `authority_epoch` 内只接受不倒退的 `source_revision`；切换 epoch 时清理所有与旧权威绑定的暂态回执和推断。
- SSE 的 `sse_delivery_id` 只用于传输续接，不能替代 `source_revision`。

### 2.2 预览与录制严格分离

- 预览是可丢帧、可短暂不可用的最新 JPEG 画面，不是录制成功的证据。
- 预览断流时不改变权威录制状态；录制仍在进行时要同时显示“设备仍在录制”和“预览不可用”。
- 预览客户端采用单槽覆盖策略：始终只保留最新一帧，旧帧丢弃，绝不向机身采集链路施加背压。
- 相机未接入、暂时无帧、网络中断和鉴权失败是四种不同状态，文案和恢复动作不能混为一谈。

### 2.3 危险操作必须可证明

- 安全换盘只由合法回执驱动，不能从“已经停止”“设备待机”或“会话出现在列表”推断。
- 回执至少校验 schema、authority epoch、source revision、volume、generation、session、release state 和 `open_handle_count = 0`。
- 网络切换可能主动断开当前链路；回执前断线时，结果必须显示为“待重新连接后确认”，不能显示成功或失败。
- 应用退出、切后台、旋转屏幕和进程被杀都不能隐式发送停止录制命令。

### 2.4 完成度优先于控件数量

- 任何看起来可点击的控件都必须有真实行为、禁用原因、进行中状态、成功结果和错误恢复路径。
- 尚未接入的功能在生产构建中不显示，不保留当前这种无响应的 `Retry`、`Probe`、`Edit` 或 `Join` 胶囊。
- 原始错误码可以作为诊断详情保留，但主文案必须是可理解的中文或英文，不直接把 `connection refused` 混进中文界面。

### 2.5 保留配色，重建布局

以下色值冻结为设计令牌，不在本轮重设计中改变：

| 令牌 | 当前色值 | 用途 |
|---|---:|---|
| `void` | `#000000` | 取景背景、沉浸区域 |
| `deck` | `#07090A` | 工作台底色 |
| `ink` | `#F0F3F4` | 主要文字 |
| `inkSecondary` | `#AAB3B8` | 次要文字 |
| `inkMuted` | `#7D878C` | 辅助信息 |
| `record` | `#FF3B2D` | 录制与危险停止 |
| `caution` | `#E0A020` | 警告、过热、待确认 |
| `permit` | `#46C98A` | 可写、已验证、可安全移除 |
| `live` | `#7FE3F5` | 实时连接、选中态、同步良好 |
| `peak` | `#E858FF` | 对焦峰值 |

玻璃层、描边和下沉态继续使用当前白色透明度体系。可以调整间距、层级、圆角和字体尺寸以改善可读性，但不得把整体换成其他主色、渐变主题或常见 Material 默认蓝紫色。

## 3. 已确认的权威契约

### 3.1 来源顺序

实现时按以下优先级判定真相：

1. `mirrorbloom/RP-YLX` 发布或构建产出的 Device API v4 OpenAPI 契约。
2. `Alpenl/openaria-echo-web` 的 v4 consumer support 清单、wire 类型、验证器、fixtures 和行为测试。
3. 旧的 Device API v3 文档只用于理解历史背景，禁止用它填补 v4 的未知字段。
4. 当前移动端 Canvas 文案和本地布尔值只作为视觉意图，不作为协议或状态依据。

### 3.2 当前已知约束

- API 根路径是 `/api/v4`。
- 只支持 major 4；未知 major 和未知关键 schema 必须 fail closed，不能静默降级到 v3。
- 已确认的能力包括 `capture`、`preview`、`range_download`、`network_mutation` 和 `calibration_capture`。
- 已确认的资源包括设备描述、采集状态、采集 SSE、安全换盘、JPEG 预览、相机对焦、网络状态/扫描/凭据/事务/SSE、会话列表/详情/未成功结果和 Range 制品。
- start、stop 和网络变更命令要求幂等键；重试同一用户意图时必须复用同一键，不能重复创建会话或事务。
- Bearer token 只能放在请求头，不进入 URL、日志、全局 UI 状态或崩溃报告。

### 3.3 Phase 0 必须解除的契约风险

移动端已从 `openaria-score/contracts/openapi/ylx-device-v4.openapi.yaml` 接入当前 Device API v4 OpenAPI，路径为 `openapi/ylx-device-v4.openapi.yaml`，SHA-256 为 `f1185da08f50857d1f231701d14dfc42ab5cf3f6abce65d5d6d5c90510a52210`，大小为 120760 字节，`info.version` 为 `4.0.0`。移动端不得从 TypeScript 类型反向拼出契约。

当前需要注意：移动端已切到包含网络状态、扫描、credential_ref、apply/retry/forget、事务 SSE、mDNS `_ylx-capture._tcp` 和 `calibration_capture` 的中央契约。Web support manifest 若仍记录旧 SHA，需要在 Web 仓库单独同步。移动端生产 Adapter 只打开已由 DTO、fixture 和 validator 覆盖的 v4 操作。

## 4. 产品信息架构

### 4.1 未连接区

首次启动和主动断开后进入“连接机身”页面，而不是直接进入伪造取景器。

页面只包含三种真实来源：

- **附近机身**：使用 Android NSD/mDNS 获取候选地址；候选出现不代表兼容或可用。
- **历史记录**：仅显示以前成功验证过的机身，包含最后连接时间和上次地址；点击后仍要重新探测。
- **手动连接**：输入 `http://10.42.0.1:8080`、`https://rp-ylx.local` 等 origin；校验协议、主机和端口，不接受带凭据、任意路径或查询参数的 URL。

交互顺序固定为：候选出现 -> 探测 `/api/v4/device` -> 校验 major/schema/capabilities -> 需要时请求令牌 -> 获取初始快照 -> 建立事件与预览连接 -> 进入已连接工作台。

候选卡允许显示“正在探测、需要令牌、不兼容、无法访问、已验证”五类结果。连接失败必须留在当前页面，保留可编辑地址和明确的重试动作。

### 4.2 已连接工作台

底部导航固定为四个等宽入口，中文默认标签为：

| 中文 | 英文 | 内容 |
|---|---|---|
| 取景 | Viewfinder | 实时预览、录制命令、画面辅助 |
| 会话 | Sessions | 台账、筛选、详情、制品 |
| 机身 | Body | 身份、相机、存储、能力、更新与应用设置 |
| 网络 | Network | 当前链路、扫描、切换事务与恢复 |

底栏高度为稳定的内容高度加 `navigationBars`/`mandatorySystemGestures` 底部 inset。切换页面时，四个入口的中心点和文字基线必须保持 0dp 位移。

取景页的录制命令坞位于底栏上方，不能改变底栏位置。其他页面隐藏命令坞后，仅内容区域增大，底栏仍停留在同一安全基线。

### 4.3 顶部状态区

- 背景和真实预览可以绘制到屏幕边缘。
- 所有文字和控件从 `max(statusBars, displayCutout, waterfall)` 的安全顶部之后开始布局。
- 顶部只常驻机身名称、连接状态、采集状态，以及最值得持续观察的 2 至 3 个指标；温度、剩余空间和链路可根据宽度折叠。
- 小屏不横向塞满所有事实。次要信息进入“机身”页，避免当前 HUD 成为信息墙。
- 状态色只作为补充，状态文本和无障碍描述必须独立表达含义。

### 4.4 返回与退出

系统返回动作按以下顺序消费：

1. 关闭菜单、对话框、详情页或底部弹层。
2. 从会话详情返回会话列表，从次级设置返回所属主页面。
3. 已连接根页面处于录制中时，显示“退出应用不会停止机身录制”的确认，不自动停止、不自动断开。
4. 已连接且空闲时，返回可以把应用置于后台；“断开机身”是机身页中的明确动作。
5. 未连接页面再返回才退出应用。

恢复前台后必须重新读取权威快照，即使 SSE 看起来仍连接。

## 5. 各页面功能与优雅界面标准

### 5.1 连接机身

功能范围：

- 启动/停止附近扫描，显示真实发现时间和探测结果。
- 连接历史去重、重试、删除单条记录；不把令牌显示在历史记录中。
- 手动地址输入、格式校验、探测、取消探测。
- 令牌输入、显示/隐藏、保存到系统安全存储、仅本次使用和删除保存令牌。
- v4 不兼容、401、403、超时、DNS 失败、TLS 失败和普通不可达的独立错误文案。
- 连接过程中禁用重复提交；用户取消后必须终止 HTTP、SSE 和预览任务。

界面标准：

- 顶部直接显示产品名和当前连接阶段，不使用营销式大标题或说明卡。
- 附近机身和历史记录是重复项卡片；手动连接与令牌用原生表单和底部弹层。
- 主要按钮用“连接”，不再用 `Mount`；`Device API v4`、mDNS、IP 地址保持英文。
- 不展示虚构的存储、温度或相机 ready 状态。只有成功探测后的字段才出现。

### 5.2 取景

功能范围：

- 获取 `/api/v4/preview` 最新 JPEG，首帧前显示“正在等待画面”。
- `image/jpeg` 之外的响应失败关闭；503 `preview_unavailable` 与 `camera_not_connected` 分别处理。
- 双目画面始终完整 `contain`；窄屏首次连接默认左眼，用户选择持久化到本机设置。
- 左眼/右眼默认铺满，可切换“铺满/全画幅”，明确说明裁切状态。
- 网格、对焦峰值和 IMU 是本地显示设置，不写入机身权威状态。
- 对焦值和自动对焦开关调用真实 API，按 capability 和 camera 状态禁用。
- 预览失败以半透明状态层覆盖最后一帧；相机断开时清掉最后一帧，防止把旧画面误认为实时。

录制控制：

- 可选录制名称，最大长度和字符规则以 v4 契约为准。
- 快门按钮至少 64dp，周围触控目标至少 72dp，TalkBack 名称随状态变化。
- 开始录制准入同时要求：已验证连接、相机连接、capture capability、存储可写、设备空闲、无在途命令。
- 停止录制只在权威状态为 recording 且链路可用时开放。
- 命令发送阶段显示小型进度环和“正在发送”；按钮不抢先变成录制态。
- 录制中显示会话名称、时长、帧数、写入量；本地只允许对权威 elapsed 做单调插值，不能编造其他指标。
- finalizing、encoding（若 v4 契约仍保留）、verifying 各有明确中文状态，快门保持禁用。
- recoverable、failed、abandoned 显示诊断和下一步动作，不自动把历史失败反复弹成新告警。
- “安全换盘”是独立危险操作，触发 stop reason `safe_swap`，之后持续等待合法回执。
- 标定录制只在 `calibration_capture.enabled = true` 时显示为可用；禁用时显示设备给出的具体原因。

视觉标准：

- 真实画面是第一视觉层，不再用渐变占位伪装视频。
- 控件分布在稳定的顶部状态区、两侧轻量工具栏和底部命令坞；不在卡片里再嵌卡片。
- 网格、峰值和 IMU 只覆盖画面，不参与测量布局，开启/关闭不能让其他控件跳动。
- 画面工具优先用图标、分段控件、开关和滑杆；陌生图标有长按 tooltip 和内容描述。

### 5.3 会话

功能范围：

- 首次进入读取分页列表，支持下拉刷新和游标加载更多。
- 搜索只针对已加载字段时明确为本地过滤；若 v4 提供服务端查询则以契约为准，不偷偷混用。
- 筛选项为“全部、可用、未成功”，用稳定分段控件呈现。
- 新录制封存后轮询到会话可见，避免用户停止后列表长时间缺项。
- 会话摘要显示名称、时间、时长、大小、生产方结果和可用性判定；两个结果永远分开展示。
- 未成功会话读取只读 outcome，不把查询动作解释为恢复。
- 详情展示 capture mode、设备、take 关系、相机/视频/音频/帧/IMU、完整性和诊断。
- 制品按 `artifact_id` 操作，不从 path 或文件名猜角色。
- 下载支持 HEAD/Range、暂停后续传、进度通知、取消和 SHA-256 校验；大文件通过前台 WorkManager 类任务执行。
- 已下载的受支持媒体可以用系统播放器或应用内 Media3 播放；不支持的媒体提供“打开方式”和“分享”。
- 下载失败保留临时进度和明确重试；哈希不一致时删除不可用结果并显示完整性错误。

界面标准：

- 列表使用紧凑行或单层卡片，优先扫描和比较，不做装饰性大卡片。
- 状态、日期、大小各占稳定列位；长名称最多两行，ID 用等宽小字并支持复制。
- 详情采用全宽分组，不把每一个字段都包成卡片。
- 空列表、加载、分页失败、详情失败和制品不可用均有独立状态。

### 5.4 机身

功能范围：

- 设备 ID、标签、hardware fingerprint、package version、commit、build ID、security profile。
- capability 列表及不可用原因。
- 相机连接、对焦状态、温度、连接方式和 IMU 同步质量。
- 存储卷 ID、总量、剩余量、可写状态和安全换盘许可。
- 手动刷新、断开机身、删除已保存令牌和清除连接历史。
- 保留现有签名 Android release manifest 更新能力：检查更新、版本说明、下载、SHA-256/签名校验、调用系统安装确认。
- 应用语言设置只提供“中文”和 `English` 两项；首次安装明确写入中文默认值。

界面标准：

- 首屏先展示“是否可拍、是否可写、相机是否接入”三项决策信息，再展示版本和诊断详情。
- 危险动作放在页面底部，断开和清除凭据需要确认，但不使用夸张警示卡。
- API、SHA-256、ID、commit 等专有名词保留英文，普通标签全部本地化。

### 5.5 网络

功能范围：

- 同时展示 desired、observed、saved、verified，避免只用一个“已连接”掩盖不一致。
- 展示 AP、Wi-Fi client、有线接口、默认路由、mDNS 地址和当前控制链路。
- 扫描网络，按信号排序，标识隐藏网络和安全类型。
- Wi-Fi 密码仅存在于表单局部内存；先换成短期 `credential_ref`，再提交 desired state；离开表单立即清除字符数组/引用。
- 支持热点、Wi-Fi client、有线 DHCP 和有线静态 IPv4 四种期望状态。
- 支持 apply、retry、forget，严格遵循 `mutation_capability`、idle-only 和单事务并发限制。
- 展示 accepted、prepared、ap ready、activating、verifying、committed、falling back、rescued、failed 等事务阶段的中文解释。
- 若提交后链路断开，进入“结果待确认”，按照 recovery action 引导重新连接目标 LAN 或 Rescue AP，连接恢复后从权威网络状态对账。
- 录制中或 rescue AP 未验证时禁用变更，并展示设备提供的 disabled reason。

界面标准：

- 当前链路和切换事务优先，附近网络在其下方；修复当前固定坐标导致的重叠。
- Wi-Fi 条目整行可点击，右侧只显示锁和信号图标，不堆叠多个文字胶囊。
- 密码、静态 IP 和确认操作使用底部弹层；事务进度保持在原页面，避免用户迷失。

## 6. 状态模型

### 6.1 连接状态

| 状态 | 含义 | 允许动作 |
|---|---|---|
| `idle` | 没有目标机身 | 扫描、手动输入、选择历史 |
| `discovering` | 正在收集候选 | 取消、选择已有候选 |
| `probing` | 正在验证 v4 identity/capability | 取消 |
| `credentialsRequired` | 机身要求令牌 | 输入、仅本次使用、保存、取消 |
| `connected` | 初始快照有效且控制链路已建立 | 全部按 capability 开放的操作 |
| `reconnecting` | 曾连接成功，事件或 HTTP 暂时断开 | 查看最后权威状态，封锁新命令，手动重试 |
| `incompatible` | major/schema/capability 不受支持 | 查看诊断、返回候选列表 |
| `disconnected` | 无法恢复或用户主动断开 | 重连、忘记机身、返回列表 |

`connected` 不能仅凭 socket 建立或收到一张预览帧判定；必须有通过校验的设备描述和采集快照。

### 6.2 预览状态

| 状态 | 画面行为 | 文案 |
|---|---|---|
| `waiting` | 不显示旧帧，显示轻量加载 | 正在等待画面 |
| `live` | 显示最新帧 | 实时 |
| `unavailable` | 可短暂保留最后帧并加遮罩 | 画面暂不可用，正在重试 |
| `cameraDisconnected` | 清空最后帧 | 相机未接入 |
| `unauthorized` | 清空帧并终止循环 | 访问令牌已失效 |
| `stopped` | 页面不在前台或用户断开 | 不显示网络错误 |

### 6.3 采集状态

UI 必须按 v4 snapshot 原值映射，不能因为 Web 类型当前允许某个字符串就假设设备一定会发出。Phase 0 根据 OpenAPI 固定完整枚举。已确认需要覆盖的用户语义为：

| 权威语义 | 中文显示 | 命令行为 |
|---|---|---|
| idle | 待机 | 可按准入条件开始 |
| recording | 录制中 | 可停止；不可网络变更 |
| finalizing | 正在封存 | 禁止重复停止和开始 |
| encoding | 正在编码 | 仅在 v4 正式枚举存在时显示 |
| verifying | 正在校验 | 禁止开始，显示步骤进度 |
| recoverable | 可恢复 | 展示诊断和契约允许的恢复动作 |
| failed | 录制失败 | 保留结果，禁止伪装成待机成功 |
| abandoned | 已放弃 | 保留结果并进入会话台账 |

“会话已经 sealed 并进入台账”与“设备回到 idle”是相关但不同的事实。停止后要分别对账采集状态、设备/存储、安全换盘回执和会话列表。

### 6.4 命令暂态

命令暂态只表达客户端请求过程，不覆盖权威状态：

- `ready`：可以提交。
- `submitting`：请求尚未得到响应，禁用重复点击。
- `accepted`：收到了命令响应，等待权威快照收敛。
- `indeterminate`：提交可能已经到达设备，但响应前断线；保留幂等键并在重连后对账。
- `settled`：权威状态已经确认结果。
- `failed`：设备明确拒绝或请求确认未提交。

start、stop、network apply/retry/forget 各自维护用户意图和幂等键。进程重建时不盲目重放 POST，只读取权威状态。

## 7. 目标技术架构

### 7.1 UI 技术选择

建议把 UI 迁移到 Kotlin + Jetpack Compose，而不是继续扩展单个自绘 View，原因是：

- 当前 Canvas 没有可访问性节点、内容测量、滚动、文本换行或系统 inset 支持，修补成本已经高于重建。
- Compose 可以直接表达固定底栏、响应式布局、动态字体、语义树、中文/英文资源、状态驱动 UI 和截图测试。
- 预览仍可由一个专用原生渲染 View 或 Compose Canvas 承担；业务按钮、列表、表单和弹层不再画进媒体 Canvas。
- 当前业务代码几乎为空，不存在为了保留 Java/Canvas 而必须兼容的大量生产逻辑。

迁移边界：

- 保持现有 `applicationId`、minSdk 26、target/compile SDK 和发布签名方式。
- 保持单一 Gradle `:app` module。先用 package 形成深模块，不创建 `:domain`、`:data` 等只有转发代码的空模块。
- `AppUpdateManager.java` 先通过 Adapter 复用，功能验收后再决定是否迁移 Kotlin，避免无关重写。
- 依赖版本在实施时从官方稳定渠道确认并集中进 version catalog，不在源码各处散写版本。

### 7.2 Module、Interface、Seam 和 Adapter

本计划使用以下术语：Module 是封装决策的单元；Interface 是外部可见且必须测试的表面；Seam 是替换远端或平台依赖的位置；Adapter 是 Seam 的具体实现。

| Module | 对外 Interface | 隐藏的复杂性 |
|---|---|---|
| `BodySession` | 连接、断开、权威 `StateFlow`、采集/对焦/网络命令 | v4 校验、鉴权头、SSE、修订号、重连、幂等、HTTP 对账 |
| `PreviewFeed` | `StateFlow<PreviewState>` 与最新帧流 | JPEG 请求循环、取消、限速、单槽丢帧、解码、内存回收 |
| `SessionCatalog` | 分页列表、详情、未成功结果 | 游标、封存后重试、artifact identity、错误映射 |
| `ArtifactTransfer` | 下载、取消、恢复、校验、打开 | Range、临时文件、前台任务、SHA-256、MediaStore/SAF |
| `BodyDiscovery` | 候选流、手动目标校验、历史 | Android NSD、去重、TTL、地址规范化、持久化 |
| `CredentialVault` | 读取、保存、删除机身令牌 | Android Keystore、密文存储、迁移、日志脱敏 |
| `AppUpdate` | 检查、下载、验证、安装状态 | 现有 release manifest、签名、FileProvider、系统安装器 |
| `EchoUi` | Compose screens 和语义事件 | insets、导航、响应式、i18n、可访问性、视觉令牌 |

远端依赖必须经过 Seam：

| Seam | 生产 Adapter | 测试 Adapter |
|---|---|---|
| `DeviceApiPort` | `HttpDeviceApiAdapter`（OkHttp + JSON 校验） | `FakeDeviceApiAdapter` / MockWebServer |
| `CaptureEventPort` | `SseCaptureEventAdapter` | 可编程事件流 |
| `NetworkEventPort` | `SseNetworkEventAdapter` | 可编程事件流 |
| `PreviewSource` | `HttpJpegPreviewAdapter` | 固定帧、慢帧、503、断线源 |
| `DiscoveryPort` | `AndroidNsdAdapter` | 内存候选源 |
| `CredentialStorePort` | `AndroidKeystoreAdapter` | 内存凭据仓库 |
| `ArtifactSinkPort` | `SafWorkManagerAdapter` | 临时目录 sink |

UI 测试不 mock `ViewModel` 内部细节，而是通过这些公开 Interface 注入测试 Adapter。Interface 就是测试表面。

### 7.3 建议目录

```text
app/src/main/java/com/openaria/openaria_echo_mobile/
  MainActivity.kt
  app/
    EchoApplication.kt
    AppGraph.kt
  body/
    BodySession.kt
    BodySessionState.kt
    CaptureProjection.kt
    NetworkProjection.kt
    api/
      DeviceApiPort.kt
      HttpDeviceApiAdapter.kt
      DeviceApiModels.kt
      DeviceApiValidators.kt
      SseAdapters.kt
  preview/
    PreviewFeed.kt
    HttpJpegPreviewAdapter.kt
    PreviewRenderer.kt
    FocusPeakingProcessor.kt
  discovery/
    BodyDiscovery.kt
    AndroidNsdAdapter.kt
    BodyHistoryStore.kt
  sessions/
    SessionCatalog.kt
    ArtifactTransfer.kt
  security/
    CredentialVault.kt
    AndroidKeystoreAdapter.kt
    EndpointPolicy.kt
  update/
    AppUpdateAdapter.kt
  ui/
    EchoApp.kt
    theme/
    connection/
    viewfinder/
    sessions/
    body/
    network/
    components/
  i18n/
    ErrorMessageResolver.kt
```

这只是 package 边界，不要求每个目录都有 repository/use-case/mapper 三层。能由一个深 Module 隐藏的复杂性，不再拆成无意义转发类。

### 7.4 数据流

连接后的启动顺序必须可预测：

1. `GET /device`，严格校验 v4 major、schema 和 capability 形状。
2. `GET /capture/status`，建立 `authority_epoch + source_revision` 基线。
3. 并行读取可选的 focus、network、safe-swap 和首屏 sessions。
4. 建立 capture/network SSE，并使用 `Last-Event-ID` 续接传输。
5. 启动 preview latest-frame loop。
6. SSE snapshot 只有 revision 严格 `current + 1` 时可以快路径投影；gap、epoch 变化、非法 envelope 一律 HTTP refetch。
7. 前台每 2 秒对账 capture，打开的次级页每 5 秒对账；实际频率在真机功耗测试后可调整。
8. 应用回到前台、网络恢复、SSE 重连和命令完成后立即对账。

### 7.5 预览实现细节

- OkHttp 请求 `Accept: image/jpeg`，任何其他 Content-Type 都作为契约错误。
- 任一时刻最多一个网络请求、一个待解码帧和一个已显示帧；使用 conflated channel/drop-oldest。
- JPEG 在后台 dispatcher 解码，主线程只交换最终 bitmap/image handle。
- 成功帧后约 40ms 再拉取；预期 503 或相机断开后约 500ms 重试；退避参数集中配置并可测试。
- 页面不可见、应用切后台、用户断开和 token 失效时立即 cancel，关闭 response body 并释放 bitmap。
- 对焦峰值按不超过约 512K 像素的下采样帧在后台处理；处理跟不上时跳过帧，绝不排队。
- 真机 spike 若证明 CPU 峰值处理无法满足预算，再把 `FocusPeakingProcessor` 的 Adapter 换为 OpenGL 实现，不改 UI Interface。

## 8. i18n 与文案规范

### 8.1 资源结构

- `res/values/strings.xml`：简体中文默认资源。
- `res/values-en/strings.xml`：英文资源。
- 首次安装将应用 locale 明确设为 `zh-CN`，不因系统语言是英文而跳过中文默认要求。
- 机身页提供“中文 / English”分段控件，切换后立即重建当前界面并持久化。
- 升级现有版本的用户若没有语言记录，也默认写入中文。
- 所有可见文案、TalkBack 描述、tooltip、通知、更新流程和错误映射都从资源读取。

### 8.2 用户术语

| 中文 | 英文 | 备注 |
|---|---|---|
| 连接机身 | Connect body | 不使用 Mount/挂载 |
| 取景 | Viewfinder | 底部导航 |
| 会话 | Sessions | 不叫 Roll |
| 机身 | Body | 产品词 |
| 网络 | Network | 普通中文标签 |
| 开始录制 | Start recording | 用户动作；代码领域仍用 Capture |
| 结束录制 | Stop recording | 不暗示已经封存 |
| 正在封存 | Finalizing | 与停止请求分离 |
| 正在校验 | Verifying | 显示真实进度 |
| 安全换盘 | Safe swap | 专用操作 |
| 双目 / 左眼 / 右眼 | Both / Left / Right | 检视模式 |
| 对焦峰值 | Focus peaking | 摄影术语本地化 |
| 访问令牌 | Access token | token 不直接写中文音译 |

以下内容保留英文：Open Aria Echo、Device API、API v4、Wi-Fi、mDNS、RAW IMU、AP、Rescue AP、SHA-256、HTTP/HTTPS、JPEG、SSE、ID、commit、codec、文件扩展名、IP 地址和设备原始诊断 code。

### 8.3 错误策略

- 稳定 error code 映射到本地化标题、解释和主要恢复动作。
- 原始 message 放在可展开“诊断详情”中；默认不把英文底层错误直接拼进中文句子。
- retryable 决定是否显示“重试”，不是所有错误都给同一个按钮。
- 未知错误显示本地化兜底、request ID 和复制诊断动作，不能吞掉。
- 使用 Android plurals、数字和时间格式化，不用字符串拼接构造句子。
- CI 检查两种语言键集合一致、无空翻译、无生产 Kotlin/Java 可见硬编码字符串。

## 9. 安全设计

### 9.1 令牌和密码

- 机身 access token 使用 Android Keystore 支持的密钥加密后持久化；存储 key 以稳定 device ID 关联，不以易变化的 IP 地址关联。
- token 不进入 `SavedStateHandle`、导航参数、日志、analytics、异常 message、剪贴板自动建议或截图测试 fixture。
- Bearer token 只放 `Authorization` 请求头；POST 按 v4/Web 既有行为补 `X-CSRF-Token`，以最终 OpenAPI 为准。
- Wi-Fi passphrase 只在局部表单存活，用完即清空；全局 `AppState` 中禁止出现 `password`、`psk`、`secret`、`token` 或 network draft。
- debug 日志统一经脱敏 interceptor，release 禁止 HTTP body 日志。

### 9.2 Endpoint 与明文策略

- HTTPS 对任何合法主机开放，并使用系统信任链，不提供“忽略证书错误”。
- HTTP 只允许明确的私网、链路本地地址或 `.local` 机身；连接页显示“本地未加密连接”状态。
- Phase 0 先确认真机是否能全面使用 HTTPS，以及 HTTP 是否只出现在固定 AP 地址或 `.local`。若固定目标足够，`networkSecurityConfig` 只放行这些目标；若必须连接动态私网 HTTP，Android XML 无法表达完整 RFC1918/链路本地范围，平台 cleartext 开关可能必须放宽，此时 `EndpointPolicy` 必须在发起任何请求前 fail closed 拒绝公网 HTTP，并用自动测试覆盖所有地址分类。
- 发布安全门禁检查的是“没有不受 `EndpointPolicy` 限制的明文请求路径”，不能用一个看似严格但实际让真机不可连接的 XML 配置替代运行时校验。
- URL 规范化后只保存 origin，不接受 user-info、fragment、任意 API path 或 token query。
- 手动地址变更后必须重新验证 device identity，防止把旧 token 和旧状态错误关联到新机身。

### 9.3 下载与更新

- 制品下载写入临时文件，完成 SHA-256 后再原子发布到用户目标。
- content URI 通过 FileProvider/SAF 暴露，不使用宽泛文件权限。
- 应用更新继续验证已签名 manifest、包哈希和 Android 签名；校验失败不得启动安装器。
- Release 构建禁止 debug fake body、固定 token、测试证书和任意 cleartext host。

## 10. 响应式、可访问性与视觉验收

### 10.1 布局矩阵

至少覆盖以下组合：

| 维度 | 必测值 |
|---|---|
| 宽度 | 360dp、393dp、411dp、600dp |
| 方向 | 竖屏、横屏 |
| 顶部形态 | 无开孔、居中开孔、左侧开孔 |
| 底部导航 | 三键、手势 |
| 字体缩放 | 1.0、1.3、1.5、2.0 |
| 显示缩放 | 默认、较大 |
| 语言 | 中文、英文 |
| 系统版本 | API 26、当前 target 对应 API、至少一台真机 |

禁止锁死竖屏来回避布局。横屏可以采用双栏信息布局，但底部/侧边导航切换必须有稳定规则。

### 10.2 可访问性

- 每个按钮、分段项、开关、滑杆、列表项和状态有独立语义节点。
- 图标按钮有 content description；装饰图形不进入语义树。
- 快门读作“开始录制 / 结束录制 / 正在发送”，并暴露 disabled 状态。
- 连接、录制、错误和安全换盘状态通过适度 live region 播报；每帧预览和每 50ms 计时绝不播报。
- 触控目标至少 48dp；快门和危险操作更大。
- 焦点顺序遵循顶部状态 -> 页面内容 -> 主要命令 -> 底部导航。
- 不能只靠颜色表达录制、警告、可写或可移除。
- 2.0 字体缩放下允许换行和纵向滚动，文字不被按钮遮挡、不被截断。

### 10.3 视觉细节

- 统一 4/8dp 间距网格，卡片圆角不超过 8dp；只给重复项和真正的工具/弹层使用卡片。
- 页面 section 是无框全宽分组，不把卡片嵌套在卡片中。
- 数字读数使用稳定宽度或等宽数字，状态变化不能推动相邻控件。
- 顶部事实、底栏入口、快门和预览控制使用稳定尺寸约束，加载环或长英文不会改变布局。
- 不按 viewport 宽度缩放字体；使用语义字号和响应式重排。
- 真实预览必须可辨认，不做暗化、模糊或装饰性裁切；状态遮罩保持最小必要透明度。

## 11. 测试策略

### 11.1 单元测试

必须覆盖：

- 同 epoch 旧 revision 被丢弃；新 epoch 清除旧 receipt 和暂态。
- SSE 严格 `+1` 快路径、gap HTTP refetch、非法 envelope 拒绝。
- 断线期间 start/stop 不产生本地 recording；命令结果未知后的对账。
- start/stop 幂等键在一次用户意图内稳定，不跨意图复用。
- safe-swap 全部身份和 release 条件校验。
- retained unsuccessful 只在本页观察到新终态时播报，不重放历史告警。
- 网络 snapshot 修订号、credential receipt、transaction receipt 和跨字段约束。
- preview 单槽、取消、503 分类、Content-Type 校验和资源释放。
- URL 规范化、HTTP 本地范围、token 脱敏和凭据不进入全局状态。
- 中文/英文 error code 映射完整。

### 11.2 契约与集成测试

- 将 v4 OpenAPI 固定为测试输入；对 DTO/validator 做正反 fixture 测试。
- 使用 MockWebServer 模拟 `/device`、capture status/SSE、preview、sessions、artifacts、focus 和 network。
- 从 Web fixture server 等价场景移植行为，不复制 Preact UI 细节。
- 测试未知 major、未知关键字段、401、403、429、5xx、慢响应、半包 SSE、重复事件、断流和 epoch 切换。
- 预览测试验证最大 in-flight 为 1、慢客户端不积压、旧 bitmap 被释放。
- 制品测试覆盖 Range 续传、服务端忽略 Range、长度变化、哈希错误、取消和恢复。

### 11.3 UI 与截图测试

- Compose UI tests 验证导航、弹层、禁用原因、返回顺序、语言切换和语义节点。
- 使用稳定的截图测试工具覆盖布局矩阵中的关键组合，颜色基准以冻结令牌为准。
- 保留并升级 `dogfood-output/check_bottom_nav.sh` 的意图：四个 tab 中心基线差必须是 0dp。
- API 35/36 开孔模拟器验证顶部控件 bounds 不与 cutout/status bar 相交。
- 语义树中不再出现整个应用只有一个 `NAF=true` View 的情况。

### 11.4 真机与性能测试

- 至少一台 RDK X5/OpenAria 机身完成 30 分钟普通录制、标定录制、停止、校验、会话下载和安全换盘闭环。
- 热插拔相机：控制面保持可用，预览清空，录制准入锁定；重新接入后自动恢复。
- Wi-Fi 切换导致控制链路断开：应用进入结果待确认，能通过目标 LAN 或 Rescue AP 恢复并对账。
- 预览健康网络首帧目标不超过 3 秒；等待期间始终有明确状态。
- 连续预览 30 分钟堆内存无持续增长，最多一个请求、一个待解码帧、一个显示帧。
- 主线程不做网络、JPEG 解码、SHA-256 或峰值处理；无 ANR。
- 录制命令从点击到收到设备响应期间有即时本地反馈，但录制态只由设备确认。

### 11.5 CI 门禁

每个 PR 至少执行：

1. Debug assemble。
2. Android Lint，新增 warning 视为失败；逐步清理当前 6 个 warning。
3. JVM unit tests，若 `NO-SOURCE` 则失败。
4. Contract/MockWebServer tests。
5. Compose UI tests 或可重复的 emulator shard。
6. 中文/英文资源一致性与 visible hardcoded string 扫描。
7. Release 配置静态检查：无 debug endpoint、无测试 token、无绕过 `EndpointPolicy` 的 cleartext 请求路径。

## 12. 分阶段开发路线

工期按一名熟悉 Android 和协议状态机的工程师估算为 8 至 11 周，包含真机联调和发布缓冲。两名工程师可在 Phase 2 以后并行预览与会话/网络工作，预计 5 至 7 周，但契约和状态模型仍必须先共同锁定。

### Phase 0：契约锁定与真机探针（2 至 4 天）

任务：

- `M0-001` 获取 v4 OpenAPI 原文件，验证 75767 bytes 和 SHA-256 `5808b4449201ce4657a3d0b80d018466c6294c81732d3b93a9f2b575c5e0d905`；同步 Web support manifest 的旧哈希。
- `M0-002` 在 mobile 新增 consumer support 清单，consumer 标识为 `openaria-echo-mobile`，仅支持 major 4、unknown fail closed。
- `M0-003` 从 Web fixture 和真机响应建立最小正反 fixture 集，不手写“看起来合理”的 JSON。
- `M0-004` 真机确认 preview 的 Content-Type、典型 JPEG 尺寸/FPS/延迟、401/503 error body 和取消行为。
- `M0-005` 真机确认 capture lifecycle 的完整枚举，尤其 v4 是否仍包含 `encoding`。
- `M0-006` 记录 ADR：Compose 重建、设备权威投影、中文默认、HTTP 本地范围和颜色冻结。

退出标准：

- 契约文件可追溯、哈希可验证、测试可以读取。
- 一组 smoke fixture 覆盖 device/capture/preview/events/session/network。
- 所有未知枚举和 endpoint 已形成明确清单，没有继续依赖 v3 猜测。

### Phase 1：应用骨架、主题、i18n 与测试底座（4 至 6 天）

任务：

- `M1-001` 引入 Kotlin、Compose、Lifecycle、Coroutines、OkHttp/serialization、测试依赖和 version catalog。
- `M1-002` 建立 `MainActivity.kt`、edge-to-edge 窗口和安全 inset scaffold。
- `M1-003` 将冻结颜色、字体、透明度、4/8dp spacing 和 8dp radius 做成主题令牌。
- `M1-004` 建立四入口固定导航和可滚动页面容器；录制命令坞独立位于导航上方。
- `M1-005` 建立中文默认与英文资源、应用内 locale 存储和切换骨架。
- `M1-006` 使用 debug/test-only fake state 渲染各主要状态；release source set 不包含假机身。
- `M1-007` 加入第一批 Compose 语义测试和中文/英文、小屏/大字体截图基线。

退出标准：

- 开孔、状态栏、手势区不遮挡控件。
- 四个底栏入口在所有页面坐标完全一致。
- 中文/英文切换覆盖导航和公共状态；没有 Canvas 整体点击层。
- release 构建不会显示任何 fake body 或 fake recording。

### Phase 2：发现、连接、鉴权与安全存储（5 至 7 天）

任务：

- `M2-001` 实现 `EndpointPolicy` 和规范化 BodyTarget。
- `M2-002` 实现 Android NSD/mDNS discovery、候选去重、过期和生命周期取消。
- `M2-003` 实现历史记录与手动连接 UI。
- `M2-004` 实现 v4 `/device` probe、major/schema/capability fail-closed validators。
- `M2-005` 实现 credential prompt、Android Keystore Adapter、401 重新鉴权和删除令牌。
- `M2-006` 实现初始 capture/network/focus/safe-swap 加载、连接状态机和取消。
- `M2-007` 接入 capture/network SSE，完成 Last-Event-ID、重连和 HTTP reconciliation。
- `M2-008` 覆盖超时、TLS、无网络、未知 major、schema 错误和 token 失效测试。

退出标准：

- 能从附近、历史和手动地址连接真机。
- 未完成验证前绝不进入工作台或显示 ready。
- token 重启后可恢复、删除后不可恢复，日志和全局状态无明文。
- 断线时新命令全部封锁，重连后权威状态收敛。

### Phase 3：真实预览与画面工具（6 至 9 天）

任务：

- `M3-001` 实现 `PreviewFeed`、HTTP JPEG Adapter、单槽 frame pipeline 和生命周期取消。
- `M3-002` 实现 waiting/live/unavailable/camera disconnected/unauthorized UI。
- `M3-003` 实现双目 contain、单眼 crop/full-frame 和窄屏默认策略。
- `M3-004` 实现稳定网格覆盖层和本地设置持久化。
- `M3-005` 移植有界像素预算的对焦峰值处理并做真机性能基准。
- `M3-006` 用真实 runtime 数据实现 IMU overlay；无数据时显示不可用，不显示固定数值。
- `M3-007` 实现真实 focus slider/auto toggle、capability 和相机状态禁用原因。
- `M3-008` 完成 30 分钟内存、后台/前台、相机热插拔和慢帧测试。

退出标准：

- 真机首帧可见，截图像素证明不是渐变占位。
- 断流和相机断开状态清晰，恢复后自动出帧。
- 最大 in-flight 请求为 1，堆内存无持续增长，UI 无明显卡顿。
- 眼位、网格、峰值、IMU 和对焦控件都有真实行为及无障碍语义。

### Phase 4：录制、状态对账和安全换盘（5 至 8 天）

任务：

- `M4-001` 实现纯函数 `CaptureProjection` 和 revision/epoch 规则。
- `M4-002` 实现 start/stop payload、幂等键、命令暂态和错误映射。
- `M4-003` 实现录制准入矩阵与全部禁用原因。
- `M4-004` 呈现权威时长、帧数、写入、编码/校验步骤和诊断。
- `M4-005` 实现停止后的快速/退避对账和封存会话刷新。
- `M4-006` 实现 safe-swap 请求、typed receipt 全项校验和“可移除”许可。
- `M4-007` 实现 capability-gated calibration capture。
- `M4-008` 覆盖重复点击、超时、请求后断线、SSE gap、epoch restart、failed/recoverable/abandoned。

退出标准：

- 无机身或命令失败时不会出现 recording、固定帧数或固定写入量。
- 设备已经开始但响应丢失时，应用最终通过对账显示真实状态且不重复创建 session。
- 停止、封存、校验、失败和安全换盘每一步均可区分。
- 只有合法回执能显示“可以安全移除”。

### Phase 5：会话、制品与下载（5 至 8 天）

任务：

- `M5-001` 实现 cursor paging、refresh、local query/filter 和列表诊断。
- `M5-002` 实现 session detail、unsuccessful outcome 和 producer/usability 双结果。
- `M5-003` 实现 sealed session 最终可见的退避刷新。
- `M5-004` 实现 artifact identity、HEAD/Range 下载、取消、续传和 SHA-256。
- `M5-005` 实现前台下载通知、MediaStore/SAF 发布、打开和分享。
- `M5-006` 对支持格式接入 Media3 本地播放；不支持格式交给系统应用。
- `M5-007` 覆盖分页重复项、详情竞态、Range 被忽略、哈希错误、空间不足和进程重建。

退出标准：

- 停止后的新会话最终自动出现在列表，不需杀进程刷新。
- 未成功会话不会伪装成普通成功会话，也不会重复弹旧诊断。
- 下载结果经 SHA-256 验证，失败或取消不会留下看似完整的文件。

### Phase 6：机身、网络、更新与设置（6 至 9 天）

任务：

- `M6-001` 完成机身 identity/build/security/capability/runtime/storage 页面。
- `M6-002` 完成网络 observed/desired/current transaction 投影和 revision 规则。
- `M6-003` 完成扫描、Wi-Fi secret -> credential ref、hotspot、Ethernet DHCP/static 表单。
- `M6-004` 完成 apply/retry/forget、disabled reason 和单事务封锁。
- `M6-005` 完成 outcome indeterminate、recovery action、目标 LAN/Rescue AP 重连引导和对账。
- `M6-006` 把现有 AppUpdateManager 接入真实 Compose UI，覆盖检查、下载、验证、安装和错误。
- `M6-007` 完成断开、忘记令牌、清理历史、语言与应用信息设置。
- `M6-008` 真机执行网络切换、失败回退、录制中禁用和更新 smoke test。

退出标准：

- 当前所有可见网络控件均有真实行为，没有 dead Join/Retry/Edit。
- Wi-Fi 密码不进入全局状态或日志。
- 网络切换断链不会被误报为失败或成功，恢复后能对账。
- App update 从检查到系统安装确认形成闭环。

### Phase 7：可访问性、响应式与视觉精修（4 至 7 天）

任务：

- `M7-001` 完成布局矩阵所有截图并消除 overflow/overlap。
- `M7-002` 完成 TalkBack 走查、焦点顺序、live region、角色和状态。
- `M7-003` 完成 2.0 字体缩放、横屏、平板/折叠宽度和 Android 16 edge-to-edge。
- `M7-004` 统一加载、空、断线、错误、禁用、危险确认和成功反馈。
- `M7-005` 审核所有中文/英文文案、专有名词、数字、日期、单位和截断。
- `M7-006` 逐屏检查颜色令牌未偏移、卡片未嵌套、读数未跳动、触控目标达标。
- `M7-007` 修复所有 Android Lint warning 和 accessibility scanner 严重项。

退出标准：

- 顶部和底部不与任何系统区域重叠，导航位移为 0dp。
- 中英文、2.0 字体缩放、最小手机和横屏均无文字遮挡。
- TalkBack 可独立操作完整主流程。
- 与当前设计对比时配色一致，信息密度更清晰而非更花哨。

### Phase 8：发布硬化与验收（5 至 7 天）

任务：

- `M8-001` 完成真机 30 分钟采集、异常断电/断网、恢复、下载和安全换盘验收。
- `M8-002` 完成性能 profile、内存泄漏、StrictMode、ANR、后台恢复和冷启动检查。
- `M8-003` 完成 release network/security/signing/update 审核。
- `M8-004` 完成升级迁移：旧版本首次启动默认中文、无假历史/假 token、设置不崩溃。
- `M8-005` 运行完整 dogfood，所有 critical/high issue 必须关闭并保留新证据。
- `M8-006` 更新 README、用户操作说明、故障诊断和发布清单。
- `M8-007` 生成签名候选 APK/AAB，执行安装、升级和回滚 smoke test。

退出标准：

- 本文 Definition of Done 全部满足。
- 没有已知 Critical/High；Medium 必须有明确发布决策和负责人。
- CI 全绿且包含真实测试，不再出现绿色 `NO-SOURCE`。

## 13. 建议提交序列

以下序列用于保持每次改动可审查、可回退。单个提交应只完成一个可验证行为：

1. `docs: pin mobile domain language and v4 support decision`
2. `test: add v4 contract fixtures and consumer manifest check`
3. `build: add Kotlin Compose and centralized dependency versions`
4. `ui: add frozen Aperture color and typography tokens`
5. `ui: add inset-aware app shell and stable bottom navigation`
6. `i18n: add default Chinese and English resource sets`
7. `test: add locale inset and navigation screenshot baselines`
8. `domain: add authoritative body session state projection`
9. `data: add Device API v4 port and fake adapter`
10. `data: add strict HTTP device descriptor adapter`
11. `security: add endpoint policy and keystore credential vault`
12. `feature: add mDNS manual and history body discovery`
13. `feature: add verified connection and authentication flow`
14. `data: add capture and network SSE reconciliation`
15. `preview: add latest JPEG single-slot pipeline`
16. `ui: add stereo preview states and inspect modes`
17. `ui: add grid focus peaking IMU and focus controls`
18. `capture: add idempotent start stop and admission rules`
19. `capture: add terminal reconciliation and diagnostics`
20. `capture: add typed safe-swap receipt validation`
21. `capture: add calibration recording capability flow`
22. `sessions: add paging filters detail and outcomes`
23. `sessions: add resumable verified artifact downloads`
24. `network: add status scan and credential submission`
25. `network: add transition recovery and authoritative reconciliation`
26. `body: add device storage capability and app update screens`
27. `accessibility: complete semantics back behavior and large text`
28. `test: add full emulator real-body and release acceptance gates`
29. `refactor: remove legacy Canvas prototype after parity`
30. `release: prepare first production-capable mobile candidate`

不得在实现完成前先删除 `AppUpdateManager` 的可用发布路径；Legacy Canvas 应在新工作台达到功能和视觉 parity 后一次性移除，避免两套 UI 长期并存。

## 14. 第一轮两周冲刺

第一轮冲刺不追求把所有页面“画出来”，目标是建立一个不会撒谎的垂直切片。

### 第 1 至 2 天：契约与 fixtures

- 获取并验证 v4 OpenAPI。
- 写 mobile consumer support manifest。
- 建立 device/capture/preview/401/unsupported major fixtures。
- 用真机记录 preview headers、首帧时间和错误响应。

### 第 3 至 5 天：可测试应用壳

- 接入 Kotlin/Compose。
- 完成冻结主题、edge-to-edge、安全顶部和固定底栏。
- 完成中文/英文公共资源和语言切换骨架。
- 添加 360dp、开孔、1.5 字体和英文长文案截图测试。

### 第 6 至 8 天：真实连接

- 完成 manual URL -> `/device` probe -> v4 validation。
- 完成 401 token sheet 和 Keystore。
- 完成 initial capture snapshot 和 connected/disconnected 状态。
- 未连接时仅显示连接页，连接成功后才进入空的真实工作台。

### 第 9 至 10 天：第一帧闭环

- 接入最新 JPEG 单槽循环。
- 显示 waiting/live/unavailable/camera disconnected。
- 真机验证首帧、断流、恢复、后台取消和内存释放。
- 更新 dogfood 证据：顶部不撞开孔、底栏不跳、画面为真实帧、无假 recording。

冲刺验收演示必须按以下顺序完成：冷启动中文 -> 手动连接 -> token 鉴权 -> 显示真实机身名称 -> 显示真实首帧 -> 断开相机 -> 明确提示 -> 热插拔恢复 -> 切换四个底栏入口无位移 -> 切英文无残留中文。

## 15. 风险与决策门

| 风险 | 影响 | 决策门/缓解 |
|---|---|---|
| v4 OpenAPI consumer 不一致 | DTO 和状态可能实现错误 | 移动端已接入中央 `openaria-score` 契约；Web support manifest 若旧需同步；移动端通过 pinned SHA、validator 和 fixtures 防漂移 |
| 真机 preview FPS/分辨率未知 | JPEG 解码可能过热或卡顿 | Phase 0 测量；单槽丢帧；Phase 3 设 CPU/内存预算 |
| focus peaking CPU 开销过高 | 预览掉帧和耗电 | 有界 512K 像素、降低处理频率；必要时替换为 GPU Adapter |
| HTTP 本地设备与安全要求冲突 | token 在局域网明文传输 | HTTPS 优先；HTTP 限本地并警示；推动设备 TLS 是独立后续工作 |
| 网络切换主动断开控制链路 | 用户误判结果、重复提交 | 幂等键、indeterminate 状态、Rescue AP 和重连后权威对账 |
| 设备端状态枚举继续演进 | 客户端错误显示或错误降级 | v4 consumer manifest + fail closed + contract fixture CI |
| Android 26 到 36 行为跨度大 | insets、后台和通知差异 | 双端 API 测试；平台 Adapter 集中兼容逻辑 |
| Compose 重建与旧更新模块并存 | 迁移期间重复入口 | 更新功能只保留一个 Adapter；parity 后删除 Canvas |
| 大文件下载和手机空间不足 | 半文件被误当完成 | 临时文件、Range、空间预检、SHA-256、原子发布 |
| 没有稳定 RDK X5 测试机 | 真功能只能用 fixture 推断 | Phase 0 就把真机可用性设为阻断条件，不拖到发布前 |

## 16. Definition of Done

只有同时满足以下条件，才能把这一轮称为“功能完善、界面优雅”：

- 冷启动没有真实机身时，不出现 ready、recording、固定温度、固定容量或固定 IMU。
- 能通过附近、历史或手动地址连接 Device API v4；未知 major/schema 明确失败关闭。
- token 安全存储，Wi-Fi 密码不持久化，不在 URL、日志、状态或测试截图中泄漏。
- 预览显示真实 JPEG 帧；等待、暂不可用、相机断开、鉴权失败和网络断开均可区分和恢复。
- 普通录制和能力允许的标定录制形成开始、进行、停止、封存、校验、会话可见的真实闭环。
- 命令、SSE、HTTP snapshot、revision、epoch、重连和幂等规则均有自动测试。
- 安全换盘只由完全匹配的回执开放。
- 会话列表、详情、未成功结果、制品下载、续传和 SHA-256 校验可用。
- 网络扫描、加入、热点、以太网、重试、忘记和恢复路径可用，录制中按 capability 禁用。
- App 更新能力保留并通过签名/哈希验证。
- 顶部避让状态栏和所有 cutout，底部避让系统导航，四个 tab 切换位移为 0dp。
- 中文首次安装默认；切换英文后所有普通 UI、通知、错误和无障碍文案切换完整。
- 专有名词保持英文，普通动作和状态不再中英混排。
- 2.0 字体缩放、最小手机、横屏和平板宽度无重叠、截断或不可达内容。
- TalkBack 可完成连接、开始/停止录制、查看会话、网络操作和断开流程。
- 所有可见控件有真实行为；未完成的生产功能不显示假按钮。
- 冻结色值与当前视觉一致，真实画面成为主视觉，界面层级清楚且不堆叠卡片。
- CI 实际运行 unit、contract、UI 和 lint tests，不允许 `NO-SOURCE` 假绿。
- 至少一台真实 OpenAria 机身完成 30 分钟端到端验收，且 dogfood 报告无 Critical/High 遗留。

## 17. 明确不做的事情

- 不兼容 Device API v3，不为未知 major 做“尽量可用”的猜测性 fallback。
- 不在手机上复制设备录制状态机或把本地状态作为恢复依据。
- 不为了形式上的 Clean Architecture 创建大量一行转发的 repository/use-case/module。
- 不改变现有配色，不引入默认 Material 蓝紫主题、装饰渐变或营销式首页。
- 不把预览当成录制，不把停止当成封存，不把封存当成可安全移除。
- 不在 release 构建保留 demo body、假指标、假成功或无行为按钮。
- 不在没有 Device API 契约支持的情况下发明云账户、远程控制或设备固件升级接口。
