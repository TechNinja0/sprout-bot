# Git 文件管理说明

本仓库发布 Sprout Bot 的源码、测试与维护文档。本地运行文件继续保留在原位置，由根目录 `.gitignore` 排除，不需要删除或搬移已有家庭数据。

## 纳入版本管理

- Android、Python 服务和实验性运动安全核心的源码与测试。
- 安装和验证脚本、依赖锁定文件、协议与 OpenSpec 设计记录。
- Markdown 文档、原创表情图、HTML 预览和第三方许可声明。
- Gradle Wrapper 脚本、配置与 `gradle-wrapper.jar`，用于固定构建工具版本。
- Android 内置提示音及其 `provenance.json`，用于启动和离线反馈；这些是项目合成资源，不是家庭录音。
- GitHub Actions 和协作模板。

## 仅保留在本地

| 类别 | 目录或文件 | 恢复或维护方式 |
| --- | --- | --- |
| 家庭数据与模型 | `runtime/`、`models/` | 家庭数据独立备份；模型按锁定清单安装 |
| 验证产物与临时资料 | `.artifacts/`、`ui-audit/artifacts/` | 按需重新执行验证；原始证据本地保管 |
| Python 环境与缓存 | `.venv/`、`.venv-*/`、`__pycache__/`、测试缓存 | 运行安装脚本重建 |
| 构建输出 | `build/`、`dist/`、`.gradle/`、APK、AAB | 重新构建 |
| Android 外部依赖 | AAR、`android-app/app/src/main/assets/models/` | `python3 scripts/install_models.py --only kws,vad,aar` |
| 本地 OCR 程序 | `ai-service/native/ocr` | 安装服务时由 `ocr.swift` 编译 |
| 本机配置与敏感文件 | `local.properties`、`.env*`、证书私钥、签名文件、数据库、日志 | 各部署环境独立配置；无凭据的 `.env.example` 可提交 |
| 文档导出件与历史包 | `docs/` 下的 DOCX、XLSX、PDF、ZIP | 本地归档，不作为公开文档来源；当前说明阅读 Markdown |
| 编辑器与系统状态 | `.idea/`、`.vscode/`、`.DS_Store` | 本机自行生成 |

文档中提及的 `.artifacts/` 验证结果、Word、Excel 和历史资料包属于本地记录，克隆仓库后不会包含这些文件。不存在导出件或本地证据，不代表对应验证已经执行。

## 提交前检查

```sh
git status --short
git diff --cached --stat
git diff --cached --check
# 查看一个文件由哪条规则排除
git check-ignore -v runtime/ android-app/local.properties
```

`.gitignore` 只阻止未跟踪文件被自动加入，不能移除已提交的内容。不要使用 `git add -f` 上传被排除的模型、家庭素材或凭据。新文件提交前仍需检查内容，不能仅依赖扩展名规则判断是否适合公开。
