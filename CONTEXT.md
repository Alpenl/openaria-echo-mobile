# Open Aria Echo Mobile 领域上下文

Open Aria Echo Mobile 是 OpenAria 双目采集机身的 Android 控制客户端。这份词汇表用于统一产品语言与 Device API 语义，并防止把客户端本地 UI 状态误当成机身事实。

## 机身与权威状态

**机身 (Body)**:
拥有相机、存储、录制生命周期和 Device API 权威状态的 OpenAria 采集单元。
_Avoid_: 摄像机、服务端、节点

**候选机身 (Candidate Body)**:
通过 mDNS、历史记录或手动输入得到，但尚未通过身份、兼容性和鉴权检查的地址。
_Avoid_: 已发现设备、可用机身

**已验证连接 (Verified Connection)**:
已经确认机身身份与能力，并完成鉴权的兼容控制链路。
_Avoid_: 挂载、Mount、选中设备

**Device API**:
机身用于公开权威状态、命令、事件、预览帧、会话和制品的版本化契约。
_Avoid_: 后端接口、普通 HTTP 接口

**权威状态 (Authoritative State)**:
机身在一个权威纪元和源修订号下发布的最新事实集合。客户端按钮状态永远不是权威采集状态。
_Avoid_: 本地状态、乐观状态

**权威纪元 (Authority Epoch)**:
一次连续权威生命周期的身份；源修订号只能在同一纪元内比较。
_Avoid_: 会话 ID、连接 ID

**源修订号 (Source Revision)**:
权威快照在同一权威纪元内单调递增的版本号。
_Avoid_: SSE ID、时间戳

## 采集与媒体

**采集 (Capture)**:
由机身拥有、从准入到终态对账的普通或标定录制操作。
_Avoid_: 本地录制、快门状态

**会话 (Session)**:
一次采集尝试的持久记录，包含身份、结果、时间、元数据和制品。
_Avoid_: 视频、文件夹、录制按钮

**未成功会话 (Retained Unsuccessful Session)**:
机身为检查而明确保留的失败、可恢复或已放弃采集尝试。
_Avoid_: 新告警、临时错误

**生产方结果 (Producer Outcome)**:
采集生产方对一个会话给出的终态声明。
_Avoid_: 可用性判定、校验结果

**可用性判定 (Usability Verdict)**:
消费方对已封存会话是否可用作出的独立判定。
_Avoid_: 生产方结果、录制状态

**制品 (Artifact)**:
按制品身份寻址的不可变会话输出，例如视频、音频、帧、IMU 数据或日志。
_Avoid_: 路径、对象键、任意文件

**预览 (Preview)**:
最新相机帧的低延迟、可丢弃视图；它可以丢帧，也永远不能证明采集正在进行或已经持久化。
_Avoid_: 视频录制、采集状态

**检视模式 (Inspect Mode)**:
在不改变机身采集布局的前提下，本地选择查看双目、左眼或右眼。
_Avoid_: 录制模式、相机模式

**标定采集 (Calibration Capture)**:
受机身能力约束、用于标定工作流的原始双目采集。
_Avoid_: 普通录制、双目预览

## 存储与网络

**固定机身存储 (Fixed Body Storage)**:
由部署配置的固定 `/data`，承载机身录制会话；Android 应用只通过会话与制品 API 读取数据，不管理存储介质。
_Avoid_: 可移除存储、换盘、手机存储、会话目录

`Volume` 与 `Safe-swap Receipt` 只允许出现在冻结协议兼容解析中，不是当前产品领域词汇、UI 功能或验收要求。

**网络切换事务 (Network Transition)**:
由机身拥有的正常网络配置应用、重试或忘记尝试，同时保留明确的救援路径。
_Avoid_: Wi-Fi 按钮状态、连接动画

**救援热点 (Rescue AP)**:
网络切换无法提交时，用于恢复控制的已验证回退热点。
_Avoid_: 当前 Wi-Fi、普通热点
