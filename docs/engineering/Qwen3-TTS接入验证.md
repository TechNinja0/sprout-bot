# Qwen3-TTS 1.7B 接入验证

日期：2026-09-23。实测电脑 **Apple M4 / 32GB / macOS 26.2**，Python 3.12，MLX 0.32.2、mlx-audio 0.5.5。不能将本机结果当作 M1 16GB 实测。

## 实现范围

- `TTSProvider`、请求/结果契约和独立子进程；Qwen 与 Kokoro 两个可替换适配器。
- 默认 Qwen3-TTS 12Hz 1.7B CustomVoice MLX 8bit，固定 revision `41d3337e8b7f2843a75841595fc14e4b9a7a4b96`，全部下载文件通过锁文件哈希校验。
- 家长端动态音色、回答/故事独立语气、200字补充描述、分别试听；资源有独立朗读设置。
- 已发布版本固定 `ttsProfile`；切换引擎或模型 revision 后，缺失的旧版本片段不会混用新声音。已有离线资源换声需要重新发布并同步下载。
- 10条原创静态语音提示已用 Serena/gentle 重建，保留全部原文和裁静音处理；`chime` 保留算法生成方式。逐条来源见 `android-app/app/src/main/assets/prompts/provenance.json`。

## 实际检查

| 检查 | 实际结果 |
| --- | --- |
| `ai-service/.venv/bin/python -m pytest ai-service/tests -q` | 52 passed，2项 FastAPI/Starlette 弃用警告 |
| `ruff check` 本次 Python 修改文件、TTS 测试与安装/试听脚本 | 通过 |
| `bash -n scripts/bootstrap_tts.sh` | 通过 |
| `ai-service/.venv-tts/bin/python -m pip check` | No broken requirements found |
| `python3 scripts/install_tts.py` | 固定模型安装完成，全部文件内容校验通过；先使用镜像，随后从官方站续传 |
| `PYTHONPATH=ai-service ai-service/.venv/bin/python scripts/export_contract.py` | OpenAPI 已更新 |
| `./gradlew :core:test :app:assembleDebug :app:lintDebug --console=plain` | 成功；core 24项、0失败；Lint 0错误/0 Fatal、15警告 |

Gradle 使用本机 JBR 17.0.12 和 Android SDK，分别通过 `JAVA_HOME`、`ANDROID_HOME` 指向安装目录；其他维护者按自己的环境设置，不复用测试机器的绝对路径。Lint 警告主要为依赖版本提示、既有存储 API/证书信任管理实现，未纳入本次 TTS 修改。日志位于 `.artifacts/development/qwen-service-tests.log`、`qwen-android-final.log`。

## 禁网真实合成

以下命令由 macOS 沙箱禁止网络访问，不只是设置 offline 环境变量。预先用连接本机 Ollama 的测试确认沙箱确实返回 `PermissionError`，同时 asyncio 可正常运行。

```sh
PYTHONPATH=ai-service sandbox-exec -p '(version 1)(allow default)(deny network*)' \
  ai-service/.venv/bin/python scripts/tts_smoke.py
```

同一文本：小兔子抬起头，看见了圆圆的月亮。我们一起数一数，天上有多少颗星星？

| Serena语气 | 耗时 | 音频时长 | 耗时/音频时长 | 首次加载 |
| --- | ---: | ---: | ---: | --- |
| 温柔陪伴 | 13.630秒 | 6.800秒 | 2.004 | 是 |
| 轻快开心 | 5.008秒 | 6.640秒 | 0.754 | 否 |
| 睡前舒缓 | 6.802秒 | 9.360秒 | 0.727 | 否 |

样例与测量 JSON 在 `.artifacts/development/tts-samples/`。这只是三次测试，不是吞吐量或长时稳定性结论。合成结果均为24kHz单声道PCM16 WAV。

本地 ASR 回转检查覆盖三份样例，主体文本可识别，但出现“一起/一切”“颗/个”等差异；这不能区分是 ASR 误识别还是 TTS 发音偏差，更不能代替真人对中文发音和语气的试听验收。完整转写在同目录 `asr-roundtrip.json`。

同样在禁网沙箱内，用临时家庭库完成真实 API 测试（不修改真实家庭资源）：

```sh
PYTHONPATH=ai-service:ai-service/tests sandbox-exec -p '(version 1)(allow default)(deny network*)' \
  ai-service/.venv/bin/python .artifacts/development/qwen-api-smoke.py
```

`/speech/preview`、`/speech/reply`、资源片段/缓存均为200；分别验证 Serena温柔语气、Uncle_Fu故事语气和资源默认故事语气。三类请求共用同一子进程PID；重复获取资源片段的字节和哈希一致。结果与WAV在 `.artifacts/development/tts-api/`。

Kokoro回退另用 `ROBOT_TTS_BACKEND=kokoro PYTHONPATH=ai-service ai-service/.venv/bin/python scripts/tts_smoke.py --styles gentle --text '你好，小伙伴。' --output .artifacts/development/tts-kokoro-fallback` 验证，生成有效24kHz WAV，耗时2.480秒、音频2.135秒。

## 提示素材与交付状态

`PYTHONPATH=ai-service sandbox-exec -p '(version 1)(allow default)(deny network*)' ai-service/.venv/bin/python scripts/generate_prompts.py` 成功生成：wake511ms、wake_listen905ms、wake_here1931ms、wake_touch1471ms、pet439ms、tickle1184ms、thinking833ms、rest1155ms、offline4236ms、unclear2353ms，chime160ms。较长两条开场提示仍需按真机实际首句重叠表现验收，未修改对应文案/回声过滤规则。

本次固定APK：`.artifacts/tts-integration/app-debug-qwen-1.7b.apk`。

SHA256：`4381db3fe525b297db2fc16fd64e760e43589c5ae469154087edd3f82f6259cd`。

本地服务已重启为新代码，保留 MLX ASR 和模型预热，明确使用 `ROBOT_TTS_BACKEND=qwen3-mlx`。`curl --cacert runtime/server.pem -s https://127.0.0.1:8765/health` 返回 `ok=true`、`modelWarmup=ready`；服务PID52437，日志 `.artifacts/development/qwen-service.log`。首次重启误用解析后的基础Python路径失败，已修正为 `.venv/bin/python` 并完成上述健康验证。

本任务未占用或安装华为手机；最终APK和提示素材已交给同仓库的唤醒反馈/统一回归任务继续设备验收。部署与低成本换引擎步骤见[本地语音合成](本地语音合成.md)。
