# 观测手段工具箱

按目标形态挑一类手段，不要全上。每条观测都要留下**原始产物**（截图、报文、日志片段、导出文件），
产物路径写进行为矩阵的证据列。

## Android / APK

- 静态：`apktool d app.apk`（资源与清单）、`jadx -d out app.apk`（反编译 Java/Kotlin）、
  `unzip -l app.apk | grep assets`（内置数据文件）、`aapt2 dump badging app.apk`（包名 / 权限 / 版本）
- 内置数据：直接抽出 `assets/` 下的词典、规则表、配置，`file -I` 判编码（常见 GBK/GB18030 需转 UTF-8）
- 动态：`adb shell input text` 造输入、`adb shell screencap -p` 抓屏、`adb logcat` 抓行为日志、
  `adb shell dumpsys input_method` 看输入法状态、`uiautomator dump` 取控件树
- 键盘/输入类目标额外看：`method.xml` 的 subtype、候选条控件层级、按键长按/连击时序

## 桌面二进制 / 库

- `strings -a -n 6 bin | less`、`otool -L`（macOS 依赖）、`lipo -info`（架构）
- `frida` / `lldb` 挂载观察调用与返回值；macOS 上 `dtrace` 常被 SIP 限制，先确认可用性再承诺

## 网络行为

- `mitmproxy` / `tcpdump -A port 443` 抓请求；把设备代理指到本机
- 记录：URL、方法、请求头（脱敏）、请求体、响应体、HTTP 状态
- 只对**自己有权测试**的目标抓包；不要对第三方生产系统重放或压测

## 网页 / Web UI

- Chrome DevTools Protocol：`chrome --remote-debugging-port=9222` 后取 DOM 快照与网络日志
- 交互录制优先用可复现脚本（选择器 + 等待条件），不要只留截图

## 数据文件 / 私有格式

- `xxd` / `hexdump -C` 看头部与结构、`file -I` 判编码
- 结构推断：固定长度字段 vs 分隔符；先找「行数 × 字段数」能否自洽
- 提取后做完整性自检：行数、重复键、字段边界、最大/最小权重等分布统计

## 黄金样例与差分测试（最关键的一步）

1. 在目标产物上批量生成「输入 → 输出」对，存为 fixture（如 `fixtures/*.tsv`）
2. 在复刻实现上跑同一批输入，输出同样格式
3. `diff -u expected actual`，只看**首个分歧点**，修完再跑，避免同时改多处

## 环境缺失时

工具不存在（无 JDK、无设备、无网络、无 adb）时，直接报告缺什么、哪些行为因此无法观测，
不要用「应该会这样」替代证据。没有观测环境时至少可以：读官方文档并标注「文档待验证」、
用用户提供的截图/导出文件做证据、把行为矩阵的未观测项显式留空。
