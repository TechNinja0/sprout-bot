# Android 陪伴机器人：开源复用调研记录

调研日期：2026-09-21。范围为公开 GitHub 仓库、作者文档、源码及 GitHub REST API；未编译 APK、未接入服务、未做手机实机验证。下述“支持”表示作者说明或源码存在实现，不表示已在用户手机上验收。提交日期采用 GitHub API 返回的 UTC 日期；star 数不作为成熟度判断依据。

## 建议结论

不需要把语音推理、视觉推理、通信协议全部重写，但建议新建自己的 Kotlin/Compose 产品 App，复用经过许可和实机验证的库。原因是已发现的整机项目分别解决“语音聊天”或“手机控制小车”，没有一个已查验仓库同时覆盖本项目的桌面陪伴交互、记忆管理、长期运行、移动安全和未来扩展。

- 桌面版本：自研表情、会话状态机、用户设置、记忆确认、权限与运行管理；本地语音优先验证 sherpa-onnx 的 Android demo/AAR。
- 如果采用云端音频：可把小智协议及许可清晰的 Android 客户端作为快速验证候选，但先确认音频上传的产品边界。若沿用本工作区既有“音视频留本地、云端只接收文字”约束，则不能直接沿用小智音频流架构。
- 移动版本：OpenBot 是相关性最高的参考实现。单独 fork 做手机、底盘和跟随验证；正式 App 通过 MotionController 接口复用/改造通信与跟随模块，不建议把 OpenBot 的整套调试界面改成陪伴机器人主页。
- 初版不要承诺“后台永不被杀”“任意旧手机流畅运行”“基于人形检测就能稳定识别主人”。这些都需要单独的设备和算法验收。

## 1. OpenBot：移动阶段优先参考

原地址 [isl-org/OpenBot](https://github.com/isl-org/OpenBot) 当前重定向至 [ob-f/OpenBot](https://github.com/ob-f/OpenBot)。它把 Android 手机作为机器人大脑，提供底盘设计、固件、机器人 App、遥控端、数据采集和策略训练。主仓库声明支持人物跟随和自主导航，但不能据此把它视作已经完成家用全屋导航的消费产品。

[Robot App 文档](https://github.com/ob-f/OpenBot/blob/master/android/robot/README.md)明确列出 USB、BLE、物体跟踪、模型管理和目标点导航。跟踪速度会利用检测框面积缩放，这不是精确测距；目标点导航依赖 ARCore，旧手机未必支持。文档提醒 App 仍在开发、可能因手机型号和系统出现异常。因此后续仍需加入目标丢失停车、传感器硬停、通信超时、物理急停和限速验收。

[固件文档](https://github.com/ob-f/OpenBot/blob/master/firmware/README.md)提供 Arduino Nano 与 ESP32 路线：MCU 负责电机 PWM、轮速、电压及可选超声波等传感器。这里“手机 + MCU”的分工可以直接用于产品架构。

- 代码许可证：[MIT LICENSE](https://github.com/ob-f/OpenBot/blob/master/LICENSE)，文件标题为 `MIT License`；复制相关代码须保留原声明。模型、依赖及外部资产仍需逐项核验。
- 维护证据：[2026-08-26 提交 0679c46](https://github.com/ob-f/OpenBot/commit/0679c46187811d588553cab3bccc0e3bd3c5c803)更新 Android 包名、目标 API/Gradle 和本地化；API 查询时未归档。[最近公开 Release v0.8.0](https://github.com/ob-f/OpenBot/releases/tag/v0.8.0)发布于 2025-03-03。代码更新与 release 频率应分开看。
- 采用方式：**移动 PoC 可整仓 fork，产品采用模块复用**。不要把原底盘固件与新自定义协议视为天然兼容；要么保留协议写适配层，要么同步修改两端。

## 2. sherpa-onnx：本地语音的首选验证组件

[仓库](https://github.com/k2-fsa/sherpa-onnx)提供离线语音识别、语音合成、VAD、关键词检测等能力。[Android 示例目录](https://github.com/k2-fsa/sherpa-onnx/blob/master/android/README.md)实际包含 `SherpaOnnx`、`SherpaOnnxKws`、`SherpaOnnxVad`、`SherpaOnnxVadAsr`、`SherpaOnnxTts` 等示例，也有 [AAR 工程](https://github.com/k2-fsa/sherpa-onnx/tree/master/android/SherpaOnnxAar)。

- 边界：这是推理框架与示例，不是完整对话产品；需要自己实现录放音调度、AEC 适配、打断、会话、表情和模型下载管理。不能把 VAD 当成唤醒词，也不能把普通语音增强当成播放回声消除。
- 代码许可证：[Apache-2.0 LICENSE](https://github.com/k2-fsa/sherpa-onnx/blob/master/LICENSE)，文件开头为 `Apache License / Version 2.0`。
- 模型许可必须单独检查：[官方 KWS APK 文档](https://k2-fsa.github.io/sherpa/onnx/kws/apk.html)明确写道：`Please check the license of your selected model.` 框架 Apache-2.0 不等于每个模型、声音、词表、语料来源都自动继承同一许可证。产品发布前为选定模型登记 model card、模型版本/哈希、许可证、再分发条件与来源；许可未确认时不得写“全部可商用”。
- 维护证据：[2026-09-21 提交 9f474b8](https://github.com/k2-fsa/sherpa-onnx/commit/9f474b820e29ad8ba271c07c229ab4d9ed46a527)补充 Android AAR 可复现构建文档；[v1.13.8](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.8)发布于 2026-09-10。查询时未归档。
- 采用方式：**集成 AAR/原生库并借鉴 demo，不 fork 整个推理仓库维护产品**。先拿目标旧手机分别测试中文 ASR、TTS、唤醒词与持续运行温升，再定模型。

## 3. jerrygugu/xiaozhi-android：有明确许可的第三方语音客户端候选

[仓库](https://github.com/jerrygugu/xiaozhi-android)是 Kotlin/Compose 客户端，作者说明它与小智官方无隶属关系。README 描述按住说话、持续对话、文字消息、设备激活与重连。代码中确实有 `AudioRecord`、Opus 编解码及 Oboe 播放实现，见 [AudioService.kt](https://github.com/jerrygugu/xiaozhi-android/blob/e2a026401247f8313262d8fc1e7400dd53fb8e4d/app/src/main/kotlin/com/xiaozhi/simple/service/AudioService.kt)。

发现文档与代码不一致：README 把 Opus 编解码列为路线图、写 Android SDK 24+，而 [build.gradle.kts](https://github.com/jerrygugu/xiaozhi-android/blob/e2a026401247f8313262d8fc1e7400dd53fb8e4d/app/build.gradle.kts)实际为 `minSdk = 26`、`targetSdk = 34`；README 克隆地址还指向另一个 Kimi-Lou 仓库。应以固定提交源码和构建结果为准，不能仅据 README 定兼容性。

- 代码许可证：[MIT LICENSE](https://github.com/jerrygugu/xiaozhi-android/blob/main/LICENSE)，文件标题为 `MIT License`。
- 维护证据：[2026-06-07 提交 e2a0264](https://github.com/jerrygugu/xiaozhi-android/commit/e2a026401247f8313262d8fc1e7400dd53fb8e4d)升级 Compose 并修复崩溃；查询时未归档，release API 返回空列表。
- 边界：它是远端语音服务客户端；已有音频代码不等于本地 ASR/TTS，不含已核实的底盘安全、人物跟随或陪伴产品完整状态机。
- 采用方式：**条件性 PoC/模块参考**。需要先构建、连接自建测试服务，验证音频格式协商、重连、打断、线程退出及依赖授权；不直接承诺整仓 fork 即可交付。

## 4. douo/xiaozhi-android 与 xiaoniu/xiaozhi-ai-android：参考思路，暂不复制代码

两者均是真实公开仓库，不能仅因名字相近视为同一个官方 Android 项目。

| 候选 | 已查验能力和状态 | 采用判断 |
|---|---|---|
| [douo/xiaozhi-android](https://github.com/douo/xiaozhi-android) | README 称 Kotlin/C++、官方或自托管服务、MQTT/WebSocket、AEC/NS；同时明确主要用于学习调试、代码不完善。最新默认分支提交为 [2025-03-13](https://github.com/douo/xiaozhi-android/commit/27ab13fe9ebae0cf07af4174efc2242a0f619a1a)，[v0.0.1](https://github.com/douo/xiaozhi-android/releases/tag/v0.0.1) 于 2025-03-03 发布 | 可参考能力与问题清单，不作为已可发布的产品基础 |
| [xiaoniu/xiaozhi-ai-android](https://github.com/xiaoniu/xiaozhi-ai-android) | README 描述 Compose 语音聊天 UI、WebSocket 与对话状态管理；最新默认分支提交为 [2026-05-08](https://github.com/xiaoniu/xiaozhi-ai-android/commit/79eed7843629d22cf31ec38dee9bd3d6c6c9e82a)，[v2.0.0](https://github.com/xiaoniu/xiaozhi-ai-android/releases/tag/v2.0.0) 同日发布 | 可研究交互，不直接搬运代码、视觉资产 |

许可核查：两仓库在本次 GitHub metadata 查询中均为 `license: null`，`/license` API 均返回 404；递归文件树没有发现文件名含 LICENSE/COPYING 的文件。**这只说明本次未找到可依赖的授权文件，不应写成“已确认 MIT”或“绝对没有任何许可”。** 在作者补充明确许可证或单独授权前，不列为正式代码复用来源。[douo 文件树](https://github.com/douo/xiaozhi-android/tree/main)、[xiaoniu 文件树](https://github.com/xiaoniu/xiaozhi-ai-android/tree/main)。

## 5. xiaozhi-esp32-server：可选自托管后端

[xinnan-tech/xiaozhi-esp32-server](https://github.com/xinnan-tech/xiaozhi-esp32-server)提供小智兼容后端，覆盖 VAD、ASR、LLM、TTS、视觉与管理端，支持本地组件和多个 API 提供者。它不是 Android App，也不会自动把 ASR/TTS 放到手机本地。“自托管”描述服务部署位置，不表示所有音视频天然留在手机。

- 代码许可证：[MIT LICENSE](https://github.com/xinnan-tech/xiaozhi-esp32-server/blob/main/LICENSE)，文件标题为 `MIT License`。外部模型/API/数字人资产另行核验。
- 维护证据：[2026-09-21 提交 788f530](https://github.com/xinnan-tech/xiaozhi-esp32-server/commit/788f5301fdd60cc3a8ef74025bfeece9b82b94ce)；[v0.9.6](https://github.com/xinnan-tech/xiaozhi-esp32-server/releases/tag/v0.9.6)于 2026-07-24 发布，查询时未归档。
- 作者 README 明确说明功能未完善、未通过安全测评，并要求不要用于生产环境。因此适合个人原型/局域网实验；若后续成为公开服务，需单独做鉴权、依赖、接口与运维工程，不能因为 MIT 就判断可直接上线。
- 采用方式：**个人 PoC 可部署现有后端；手机本地音视频路线则优先更小的文字网关**。不要为了单设备原型先部署完整控制台、数据库和数字人服务。

## 6. 78/xiaozhi-esp32：协议与状态设计来源

[78/xiaozhi-esp32](https://github.com/78/xiaozhi-esp32)是 ESP32 固件，不是 Android 工程。值得参考其 [WebSocket 协议](https://github.com/78/xiaozhi-esp32/blob/main/docs/websocket.md)、语音流程、表情反馈及工具调用机制；ESP-SR 唤醒和 ESP-IDF 代码不能直接编译为 Android App。

- 代码许可证：[MIT LICENSE](https://github.com/78/xiaozhi-esp32/blob/main/LICENSE)。
- 维护证据：[2026-09-20 提交 4632dc5](https://github.com/78/xiaozhi-esp32/commit/4632dc51f0a5ad26e08542e131e6e48da41e4ff3)；[v2.5.0](https://github.com/78/xiaozhi-esp32/releases/tag/v2.5.0)于 2026-09-10 发布，查询时未归档。
- 采用方式：**协议和交互参考**；底盘 ESP32 使用独立、可验证的控制固件。LLM/MCP 的任务请求必须经过手机/MCU 的本地限速和安全检查，不能让云端直接写电机 PWM。

## 复用和自研边界表

| 层 | 建议 | 需要补齐的工作 |
|---|---|---|
| 产品 App | 自建 Kotlin/Compose | 机器人脸、语音会话状态机、双端设置、权限引导、可删除记忆 |
| 本地语音 | sherpa-onnx AAR/示例 | 模型选择、许可证、录放音、音频焦点、回声、打断、性能降级 |
| 云端文字/多模态 | 接口适配层 | 服务鉴权、超时重试、配额、隐私边界、结构化回复校验 |
| 小智音频服务 | 用户明确选择后才作为可选适配器 | 音频上传说明、协议协商、自托管与云服务条款 |
| 移动跟随 | OpenBot fork 做实验，选取模块 | 摄像头朝向、目标选择、测距、失踪停车、MCU 断连停车 |
| 固件 | OpenBot 对应固件改造或自建小固件 | 与采购电机/编码器/驱动器匹配，协议版本化与急停 |

## 查验方法及未完成项

已通过 GitHub REST `repos/{owner}/{repo}`、`commits?per_page=1`、`releases?per_page=1`、`license`、`git/trees/HEAD?recursive=1` 定向查询元数据和文件，实际读取上述 README、LICENSE 及指定源码。没有进行 APK 编译、自动化功能测试、第三方服务账号注册、硬件下单或手机 benchmark。正式选型前必须用具体手机确认 Android 版本、ABI、内存、摄像头朝向与持续运行表现，并固定代码提交和模型版本。
