# 第三方依赖与模型

核查日期：2026-09-23。项目自有代码MIT不覆盖下列上游内容。固定下载URL和SHA256以 [models.lock.json](scripts/models.lock.json) 为准；Python完整安装版本以 [requirements-lock.txt](ai-service/requirements-lock.txt) 为准，Android版本以各工程Gradle文件为准。

| 组件 | 版本/用途 | 上游许可与来源 |
| --- | --- | --- |
| Kotlin、Compose、AndroidX、CameraX | Gradle锁定版本；手机UI/生命周期/相机 | Apache-2.0；[AndroidX](https://android.googlesource.com/platform/frameworks/support/)、[Kotlin](https://github.com/JetBrains/kotlin) |
| OkHttp | 4.12.0，家庭HTTPS | Apache-2.0；[上游](https://github.com/square/okhttp/tree/parent-4.12.0) |
| ZXing | 3.5.3，二维码 | Apache-2.0；[上游](https://github.com/zxing/zxing/tree/zxing-3.5.3) |
| sherpa-onnx / sherpa-onnx-core | 1.13.8，KWS/ASR/TTS | Apache-2.0；[上游许可](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/LICENSE)；ONNX Runtime等传递依赖独立保留许可 |
| Silero VAD 端侧模型 | sherpa 643854字节16kHz导出，SHA256见锁文件 `vad`；本地断句 | 原始模型MIT，[Silero许可](https://github.com/snakers4/silero-vad/blob/master/LICENSE)；[sherpa导出说明](https://k2-fsa.github.io/sherpa/onnx/vad/silero-vad.html)，完整许可见 `third_party/licenses/Silero-VAD-MIT.txt` |
| 中文KWS wenetspeech-3.3M | 2024-01-01，Zipformer2，端侧唤醒 | 实际归档README标明Apache-2.0；[模型说明](https://k2-fsa.github.io/sherpa/onnx/kws/pretrained_models/index.html) |
| Whisper base / small | 多语言，int8 ONNX；均只保留比较证据，当前不用于服务识别 | 原作者代码及权重MIT；[OpenAI许可](https://github.com/openai/whisper/blob/main/LICENSE)；转换见sherpa-onnx |
| faster-whisper / CTranslate2 | 1.2.1 / 4.8.2，当前CPU识别后端；Systran/faster-whisper-small 固定commit 536b0662742c02347bc0e980a01041f333bce120 | MIT；[引擎](https://github.com/SYSTRAN/faster-whisper)、[模型](https://huggingface.co/Systran/faster-whisper-small)；PyAV/FFmpeg等传递依赖各自许可 |
| MLX / mlx-whisper | 0.32.2 / 0.4.3，Apple Silicon 可选 ASR，版本见 requirements-apple-lock.txt | MIT；[官方引擎](https://github.com/ml-explore/mlx-examples/tree/main/whisper)；PyTorch、Numba、SciPy等传递依赖各自许可 |
| MLX Whisper large-v3-turbo 8bit | 固定commit 62103fc276a35fdc76e318f314d8ff47987fba89；Silero VAD先过滤静音 | 转换仓标Apache-2.0，[模型卡](https://huggingface.co/mlx-community/whisper-large-v3-turbo-8bit/tree/62103fc276a35fdc76e318f314d8ff47987fba89)；原始Whisper MIT；VAD随faster-whisper，MIT |
| Kokoro多语言v1.0 | 英语/中文候选，54个音色 | 权重Apache-2.0；[原模型](https://huggingface.co/hexgrad/Kokoro-82M)；归档LICENSE保留 |
| Qwen3-TTS 1.7B CustomVoice / MLX 8bit | 新语音后端，预设音色及语气控制；转换仓固定commit `41d3337e8b7f2843a75841595fc14e4b9a7a4b96`，文件校验见 `scripts/tts-model.lock.json` | 原作者模型卡与转换仓README均声明Apache-2.0；[原模型](https://huggingface.co/Qwen/Qwen3-TTS-12Hz-1.7B-CustomVoice)、[转换版本](https://huggingface.co/mlx-community/Qwen3-TTS-12Hz-1.7B-CustomVoice-8bit/tree/41d3337e8b7f2843a75841595fc14e4b9a7a4b96)、[上游许可](https://github.com/QwenLM/Qwen3-TTS/blob/main/LICENSE)；不含VoiceDesign或声音克隆模型 |
| mlx-audio / Transformers | 0.5.5 / 5.17.0，独立 `.venv-tts` 中运行，完整版本见 `ai-service/requirements-tts-lock.txt` | mlx-audio MIT，[v0.5.5许可](https://github.com/Blaizzy/mlx-audio/blob/v0.5.5/LICENSE)，完整随包文本保存在 `third_party/licenses/mlx-audio-MIT.txt`；Transformers Apache-2.0，随包许可证已单独清点；各传递依赖仍独立核对 |
| espeak-ng / phonemizer / jieba与词典 | TTS配套资源 | espeak-ng GPL-3.0；[许可](https://github.com/espeak-ng/espeak-ng/blob/master/COPYING)；相关数据/分词库可能各自许可，必须随具体构建核查 |
| Ollama | 0.34.2，本机文本/视觉推理 | MIT；[许可](https://github.com/ollama/ollama/blob/v0.34.2/LICENSE) |
| Qwen3.5-2B | `qwen3.5:2b`，固定清单/权重哈希 | Apache-2.0；[原作者模型卡](https://huggingface.co/Qwen/Qwen3.5-2B)；原仓commit15852e8c16360a2fea060d615a32b45270f8a8fc |
| Qwen3.5-4B | `qwen3.5:4b`，视觉专用，清单/权重哈希见 `scripts/models.lock.json` 的 `vlm` | Apache-2.0；[原作者模型卡](https://huggingface.co/Qwen/Qwen3.5-4B)；Ollama清单及全部层已核对，不随Git打包 |
| FastAPI / Starlette / Uvicorn / Pydantic / HTTPX | 服务API与验证 | 各上游MIT/BSD许可；发行时从安装包dist-info提取完整LICENSE并检查传递依赖 |
| Pillow / pypdf / pypdfium2 | 原稿图像和PDF | Pillow HPND、pypdf BSD-3-Clause、pypdfium2 Apache-2.0/BSD-3-Clause与PDFium依赖；见发行包许可证集合 |
| cryptography | TLS身份初始化 | Apache-2.0/BSD-3-Clause，另含OpenSSL等依赖；从发行包保留许可 |
| pypinyin | 0.55.0，昵称拼音 | MIT；[上游](https://github.com/mozillazg/python-pinyin) |
| Apple Vision | macOS本地OCR | macOS系统框架，遵循Apple平台许可，不作为本项目MIT代码再分发 |
| Tesseract | Linux OCR，可选安装 | Apache-2.0；本仓不打包其二进制 |
| PyAV / FFmpeg | PyAV 18.1.0，语音解码及MP3/M4A导入验证 | PyAV BSD-3-Clause；FFmpeg及编解码依赖按具体wheel保留许可；[PyAV](https://github.com/PyAV-Org/PyAV)、[FFmpeg许可](https://ffmpeg.org/legal.html)，不以本项目MIT覆盖 |

本仓不提交下载的模型、AAR或个人书稿。`ai-service/robot_service/data/kws-tokens.txt` 是KWS词表拷贝，来源与权重同上、Apache-2.0，仅供验证输入音素。

## 二进制发行门槛

sherpa预编译AAR可能合并多个原生功能，不能因为仅调用KWS就推定APK中没有TTS及其GPL组件。正式发布APK前，必须确认实际链接内容；需要时改为仅KWS的裁剪构建，或完整履行所含依赖的对应源码提供与其他分发条件。TTS运行包的espeak-ng、词典同样不能被项目MIT替代。本次调试APK用于本地开发验证，不当作已完成许可审计的公共发行包。

不要把上游二进制依赖与自有源码的许可混为一谈。维护者发布时附实际构建依赖清单和完整许可证文件；有不明确许可的模型不进入默认安装/发布流程。当前不采用许可未明确的2025中英KWS派生包或未核清的SenseVoice派生权重。

`android-app/app/src/main/assets/prompts/*.wav` 为项目原创短句及提示音，源文字及生成脚本见 `scripts/generate_prompts.py`。旧版语音由Kokoro生成；切换Qwen后，以随素材的 `provenance.json` 为准记录实际后端、模型与哈希，不能将旧素材按新模型署名。纯提示音无需语音模型；不含第三方歌曲或家庭录音。

## 可重复清点

使用实际运行环境的Python执行 `scripts/dependency_inventory.py`，在私有 `.artifacts/dependency-audit/` 生成版本/哈希清单及随包许可文件副本。可选TTS虚拟环境需单独运行，不能用基础环境清单代替。工具列出的缺失项与原生字符串只用于定位后续审查，不能由字符串缺失推定没有链接某组件。

服务源码发行包通过 `ai-service/pyproject.toml` 的 `license-files` 携带自有MIT、`ai-service/NOTICE` 及KWS词表对应Apache-2.0全文。依赖wheel、模型与APK的完整分发核对仍使用上面的独立门槛。

### 随包缺失许可证的补充

当前安装包清点发现部分发行包未携带完整许可文件。以下副本从对应上游版本获取，未改变已安装环境或原始清点结果。发布依赖时应一并携带；补齐文本不代表传递依赖已全部审查。

| 包与版本 | 上游版本文件 | 仓库内副本 | SHA256 |
| --- | --- | --- | --- |
| tokenizers 0.23.2 | [v0.23.2 LICENSE](https://github.com/huggingface/tokenizers/blob/v0.23.2/LICENSE) | [Apache-2.0](third_party/licenses/tokenizers-0.23.2-APACHE-2.0.txt) | `c71d239df91726fc519c6eb72d318ec65820627232b2f796219e87dcf35d0ab4` |
| CTranslate2 4.8.2 | [v4.8.2 LICENSE](https://github.com/OpenNMT/CTranslate2/blob/v4.8.2/LICENSE) | [MIT](third_party/licenses/CTranslate2-4.8.2-MIT.txt) | `54aa79d9fe3c09e67a16dcd95b9e88676405a6ec174efda31036983cf7672ecb` |
| flatbuffers 25.12.19 | [v25.12.19 LICENSE](https://github.com/google/flatbuffers/blob/v25.12.19/LICENSE) | [Apache-2.0](third_party/licenses/flatbuffers-25.12.19-APACHE-2.0.txt) | `cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30` |

原生AAR的实际链接内容、mlx-whisper具体发行物与其依赖仍需继续核对，不据以上副本放行公共二进制。
