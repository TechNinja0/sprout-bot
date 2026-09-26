# Android 工程

同一个 `org.familyrobot.app` APK 使用独立家长/机器人身份。`app` 管理Android界面、媒体和硬件；`core` 是纯JVM时间/会话/计划规则。模型推理与书庫事实由 `../ai-service` 提供。

```sh
# 在仓库根目录安装固定的AAR与KWS后
python3 scripts/install_models.py --only kws,vad,aar
android-app/gradlew -p android-app :core:test :app:assembleDebug :app:lintDebug
```

环境为JDK17、SDK36、minSdk29；构建使用标准debug签名。APK在`app/build/outputs/apk/debug/`，请勿把测试APK或debug签名作为公开正式发行。

- `MainActivity.kt`：身份、PIN管理、家长配置与资源审核。
- `RobotRuntime.kt`：会话代际、即时使用限制、命令ACK、下载/撤回与播放协调。
- `AudioInput.kt`：唯一AudioRecord，本地KWS和VAD；`CameraInput.kt`：无预览新帧采集。
- `RobotFace.kt` / `FaceSignals.kt`：原创表情、状态符号、中文提示和实际PCM音量包络；`Api.kt` / `Trust.kt`：受信TLS及加密凭据。

机器人状态通过眼睛姿态、独立符号、动效与简短中文共同表达，减少动态效果时仍能辨认。休眠闭眼带月亮、呼吸、伸缩小口水和上浮 Zzz，唤醒睁眼带光芒，聆听带麦克风和侧边声波，识别显示文字线条，思考显示环绕光点，准备声音显示沙漏，播放使用实际输出音量，暂停显示双竖线，失败显示害羞弯眼、红脸蛋和重试符号。请求等待 10 秒后显示耗时、30 秒后提示等待较久；音轨位置连续 10 秒不前进时提示播放暂未推进。这些提示不会自动中断故事，也不把慢请求判为失败。明确的对话/播放失败保留可见提示；本轮会话结束、新的会话或播放尝试会清除旧错误。

双击脸部立即暂停；右上角长按2秒进入PIN管理。管理页和后台释放采集；恢复可用不自动播放旧回答。运行数据、家庭素材、模型和凭据不能提交Git。部署与完整验收见[工程维护手册](../docs/engineering/工程维护手册.md)。
