# 签名材料（原型测试用）

- `ispeech-release.jks` — 智语 iSpeech 原型自签名证书（RSA 2048，SHA-256 指纹 `70:B4:F4:A2:BE:35:CC:D9:A6:73:D3:1D:33:A1:E4:BF:C1:6A:20:8E:43:BD:D5:D8:BF:5D:1C:AB:10:CF:2A:C7`）
- `keystore.properties` — Gradle 签名配置（明文口令，与证书同目录，仅测试用途）

`app/build.gradle.kts` 检测到 `keystore/keystore.properties` 时为 release 自动签名；文件缺失则产出未签名 APK（原行为）。
正式发布请替换为自己的证书。
