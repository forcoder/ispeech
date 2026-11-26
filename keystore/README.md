# 签名材料（原型测试用）

- `ispeech-release.jks` — 智语 iSpeech 原型自签名证书（RSA 2048，SHA-256 指纹 `70:B4:F4:A2:BE:35:CC:D9:A6:73:D3:1D:33:A1:E4:BF:C1:6A:20:8E:43:BD:D5:D8:BF:5D:1C:AB:10:CF:2A:C7`）。**仅限内部测试，正式发布前必须轮换**（见下文）。
- `keystore.properties.example` — 签名配置模板；真实口令不再入库（VOCA-477：仓库曾公开提交明文口令与私钥文件，证书视为已泄露）。

## 配置签名凭据（三选一，构建脚本按此优先级读取）

1. **环境变量**（CI / 远端构建机推荐）：`ISPEECH_STORE_FILE`（绝对路径）、`ISPEECH_STORE_PASSWORD`、`ISPEECH_KEY_ALIAS`、`ISPEECH_KEY_PASSWORD`；
2. **仓库内文件**：复制 `keystore.properties.example` → `keystore/keystore.properties` 并填入真实值（该文件被 `.gitignore` 忽略，不会提交）；
3. **机器级文件** `~/.gradle/ispeech-signing.properties`：格式同 example，适合长期构建机（`git pull` 不删除；远端构建机 `cookie@43.136.44.72` 已配置此项）。

`app/build.gradle.kts` 按 1→2→3 顺序查找；全部缺失时 release 产出**未签名 APK** 并打印告警，debug 构建不受影响。

## 轮换（正式发布前必做）

仓库曾公开提交本文件明文口令与 `ispeech-release.jks` 私钥（git 历史，2026-10-17 起），现有证书视为已泄露：

1. 生成新密钥对（新 keystore 只放构建机 / 密钥托管，**不进仓库**）：
   `keytool -genkeypair -v -keystore ispeech-release.jks -alias ispeech -keyalg RSA -keysize 2048 -validity 10000`
2. 按上文三种方式之一更新凭据，重新打包。
3. 影响面：旧证书签名的已装包无法覆盖安装（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`），需先卸载再装 —— 属**预期**行为；卸载会丢失本地数据（输入统计、登录态、自学习词库）。当前测试设备（模拟器 + FMR 真机）可接受。
4. 仅换口令（`keytool -storepasswd`）**不能**替代重签：旧口令在公开 git 历史中，旧私钥仍可被解出。

## 校验已发布包

`apksigner verify --print-certs <apk>` 输出的 SHA-256 digest 与上表指纹一致，即由本证书签名。
