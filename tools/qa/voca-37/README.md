# VOCA-37 · Stage 3 回归工具与证据

报告：`docs/qa/voca-37-stage3-regression.md`（被测构建 `c524351`，PR #18 head）。

## 常量在哪

`drive.py` 顶部集中了本机常量：`ADB`（platform-tools 路径）、`S`（serial，默认 `emulator-5554`）、`IME`、`DEN=2.75`（440dpi 的 px/dp）。换机器只改这里；其余脚本 `import drive as D` 复用。

## 脚本

| 文件 | 用途 |
|---|---|
| `drive.py` | 测量底座：ime inset frame、键帽行检测、候选带列剖面互相关位移、宿主文本真值、软键坐标表、联系人/设置宿主拉起 |
| `fs115.py` | 恒高 × font_scale 取证（含安装身份校验、候选带墨迹上下留白 → 判截字） |
| `paths.py` | §9.8 路径 ①④⑤ + 双击空格 + 快捷短语字母进搜索框 |
| `paths2.py` | 键帽行/列检测辅助（被 `paths3.py` 复用） |
| `paths3.py` | 带宿主守卫（`-`/`=` 必须进 IME）与联系人弹窗自关的重试版 |
| `qa_rows.py` | 单图工具：`python3 qa_rows.py <png> <y0> <y1> <dpi>` 出行带与间距 |
| `qa_kb.py` | 早期手工探针集合（frame/host/text/bands/shift/ink） |

## 测量纪律（违反就不可比）

1. 测前 `settings put secure autofill_service null`，否则自动填充会多报一个 `type=ime` InsetsSource。
2. 位移一律用硬件 `-`/`=`（198px=72dp 一档，可复现）；触摸 fling 位移不可复现，禁止作为判据。
3. residual > 5 的相关结果视为不可判（多半是两帧候选内容不同，不是滚动）。
4. 每个采信窗口都要有安装身份双端校验：逐条目比对设备上 `base.apk` 与本地构建的 `classes*.dex` + `resources.arsc` SHA256；整包 APK 哈希不可比。

## evidence/

`ev_*.txt` 为当场 stdout 原文（含时间戳与身份校验行），`gradle_test.log` 为单测运行日志。其中 `ev_headline.txt` 的 fs1.15=833px 段、`ev_fontscale*.txt`、`ev_sogou440.txt`、`ev_520.txt` 是共享模拟器被他人 run 换包期间的污染值，**已作废**，仅留作环境争用的证据。
