家庭机器人 Android 正式签名材料
================================

文件（本目录随代码仓库维护，供家庭构建与迁移；建议同时保留离线备份）：
- release.jks          Android release 签名密钥库（PKCS12，RSA 3072，别名 familyrobot）
- keystore.properties  Gradle 构建读取的签名配置（store/key 密码见该文件）
- README.txt           本说明

证书 SHA256 指纹：
  3A:C9:FA:50:D4:B2:27:B6:F6:3A:44:D8:20:94:9D:29:26:7D:07:AB:E3:AC:10:EF:1A:B0:CD:C2:A5:7D:22:0B
有效期：自 2026-09-25 起 10950 天（约 30 年）。

用途与保管要求：
1. 发布正式版：bash scripts/release_app.sh --release "更新说明"（构建时自动读取本目录）。
2. 建议在仓库之外保留离线备份（加密 U 盘或密码管理器）；仓库与备份任一可用即可恢复。密钥全部丢失后，已安装用户将无法再收到任何升级包（签名不一致，系统拒绝覆盖安装），只能卸载重装。
3. 除本仓库外，不要复制到公开网盘、聊天群等第三方位置；不要使用 debug 签名或他人签名发布正式版。
4. 校验构建产物：apksigner verify --print-certs 输出的证书 SHA-256 应与上述指纹一致。

查看指纹：
  /opt/homebrew/opt/openjdk@17/bin/keytool -list -v \
    -keystore runtime/android-signing/release.jks -alias familyrobot
