# ASR 模型源迁移验证记录（VOCA-33）

记录「语音模型下载失败」这次换源的决策、独立复跑的验收证据、端到端自愈验证，
以及合并前必须清掉的两个阻塞。运维侧知识（探测命令、桶配置、回滚步骤）见
`docs/model-host-runbook.md`，本文只记验证结论与日期，不重复操作手册。

## 1. 决策

| 项 | 结论 | 依据 |
|---|---|---|
| 换源方案 | **对象存储**（阿里云 OSS，默认域名 + HTTPS） | 人类 2026-11-09 在 VOCA-33 选定「B｜先走对象存储」 |
| bucket | `delivery-service-smzk`（cn-guangzhou，`public-read`） | 人类 2026-11-09 12:39 回复「按前者走」（相对于备选 `baigong-file`） |
| 是否绑自定义域名 | **不绑** | 默认域名 `*.aliyuncs.com` 走 HTTPS 不需 ICP 备案，证书由阿里云兜底 → 本轮零备案工作量 |
| 对象 | `models/asr/sherpa-onnx-paraformer-zh-small-2024-03-09.tar.bz2` | 77,920,048 B，`application/octet-stream` |

## 2. 验收证据（2026-11-10 独立复跑，非引用执行人日志）

新源 = `https://delivery-service-smzk.oss-cn-guangzhou.aliyuncs.com/models/asr/sherpa-onnx-paraformer-zh-small-2024-03-09.tar.bz2`

| 断言 | 实测 | 结果 |
|---|---|---|
| `curl -I` 状态码 | `200`，`Server: AliyunOSS` | 通过 |
| `Content-Length` | `77920048` | 通过 |
| `Content-Type` | `application/octet-stream`（非 `text/*`） | 通过 |
| `Accept-Ranges` | `bytes` | 通过 |
| 包体魔数 | `BZh`（bzip2） | 通过 |
| `Range: bytes=16783-` | `206` + `Content-Range` 总长 77920048 | 通过 |
| `Range: bytes=77920048-`（等于总长） | **`200` 整包**，不是 416 | 与 Runbook §6 一致 |
| `Range: bytes=99999999-`（越界） | **`200` 整包**，不是 416 | 与 Runbook §6 一致 |
| 整包 sha256 | `da92b3db…c6b1450` | 通过 |
| `check_model_host.py --sha256` | 9 passed / 0 failed，exit 0（3.4s，22 MB/s） | 通过 |
| `check_model_host.py`（无参数，读默认源） | 7 passed / 0 failed，exit 0 | 通过 |
| `check_static.py` | 6/6（含第 6 项「默认源走 HTTPS」） | 通过 |
| `check_crosscut.py` | 138/138 | 通过 |
| `:app:generateDebugBuildConfig` | `ASR_MODEL_URL` 产出即 OSS 地址（配置真的生效，不只是改了文本） | 通过 |
| `:app:compileDebugKotlin` | BUILD SUCCESSFUL | 通过 |
| `:app:testDebugUnitTest --tests "com.ispeech.data.*"` | 16 个用例全过 | 通过 |

旧源同时复测（4 次采样）：`302 → dnspod 拦截页` ×3、`502` ×1 —— 故障形态未变，502 仍是间歇噪声。
**判断源可用性必须多次采样取多数**，单次 502 会误判。

## 3. 端到端自愈验证（用户原始故障场景）

镜像 `FunAsrModelStore` 的 `AsrDownloadPolicy` 判定逻辑，对真实 OSS 端点跑重试循环：

| 场景 | 结果 | 实际流量 |
|---|---|---|
| A. 全新安装（无 `.part`） | 第 1 次即成功，sha256 一致 | 1 次下载 / 77.9 MB |
| B. 遗留 16,783 字节旧拦截页 HTML 的坏 `.part`（= 用户当前状态） | 自愈成功，**无需卸载重装** | 2 次下载 / **155.8 MB** |

场景 B 的机制：坏 `.part` 触发 `Range: 16783-` → 服务端回 `206`（OSS 对任意前缀都满足 Range）
→ 追加写入凑满 77,920,048 字节 → `DIGEST_MISMATCH` → 删 `.part` → 整包重下 → 通过。

**结论：自愈成立，但代价是一次多余的完整下载（+77.9 MB）。**

### 待评估的优化

`.part` 存在时，先读前 3 字节判断是否为 bzip2 魔数 `BZh` 再决定是否续传。拦截页 HTML 以
`<!DOCTYPE` 开头，3 字节本地检查即可直接丢弃，把场景 B 从 155.8 MB 降到 77.9 MB。
纯本地判断、零网络成本。属 VOCA-34 范围（`FunAsrModelStore.kt`），单独评估。

## 4. 合并阻塞（必须先清）

### 4.1 `build-voca30` 是 gitlink，合并两个方向都冲突

`agent/sre/voca-35` 上该路径是子模块指针（`160000 commit c753b47…`），而 `agent/agent/voca-34`
上是真实目录（`040000 tree a7f948f…`，231 个文件），`main` 上不存在。仓库没有 `.gitmodules`，
所以是悬空指针。

实测：

```
main ← v34 ← v35 : CONFLICT (file/directory): directory in the way of build-voca30
main ← v35 ← v34 : CONFLICT (file/directory): directory in the way of build-voca30
```

根因：本地 `build-voca30` 是一个带 `.git` 的嵌套克隆，baseline 快照提交 `18e15aa` 把它记成了 gitlink。

修复（已实测：撤掉后合并无冲突、`build-voca30` 恢复 231 个真实文件、编译与 16 个单测通过）：

```bash
git rm --cached -r build-voca30
git commit -m "chore(repo): 移除误提交的 build-voca30 子模块指针"
```

**已于 2026-11-10 修复**：SRE 提交 `f916c4b`（`chore(repo): 移除误提交的 build-voca30 子模块指针`）。
复跑验证：`main ← v35 ← v34` 合并**无冲突**，`build-voca30` 落地为 `040000 tree`（231 个真实文件），
合并后 `:app:compileDebugKotlin` 通过、`:app:testDebugUnitTest --tests "com.ispeech.data.*"` BUILD SUCCESSFUL、
`check_static` 6/6、`check_crosscut` 138/138、`check_model_host` 7/7 全绿。

### 4.2 两条子分支都未推到 origin —— 已闭合（2026-11-18 更正）

> 本节原文（2026-11-10 写）：「`agent/agent/voca-34`、`agent/sre/voca-35` 在 `origin` 上都不存在，
> 也没有 PR。合并前需推送并开 PR（标题带 `VOCA-33`）。截至 2026-11-10 仍未推送。」

**该结论已过期，照它判断现状会得出错误结论。** 两条分支随后都已推到 `origin`
（`origin/agent/agent/voca-34`、`origin/agent/sre/voca-35` 现在都在），改动也已在
2026-11-10 23:08 CST 经统一合并窗口 squash 进 `main`。合并结论见 §4.3，引擎变更后的现状见 §8。

### 4.3 合并结论（2026-11-18 补，VOCA-475）

| squash commit | 时间（+0800） | 内容 | 执行者 |
|---|---|---|---|
| `d3316a0` | 2026-11-10 23:07:58 | VOCA-33：本文件的验收记录 | Lin（项目经理主导的统一合并窗口，见项目规则 §三） |
| `3d62d9d` | 2026-11-10 23:08:02 | VOCA-34：下载校验 + `.part` 自愈，元数据收敛到 `AsrDownloadPolicy` | 同上 |
| `61eb974` | 2026-11-10 23:08:08 | VOCA-35：默认源切 OSS + 探测脚本 + Runbook | 同上 |

复跑验证（2026-11-18，境内机器）：

```bash
git fetch origin
git log -1 --format='%H %ci' origin/main        # fa57a076…  2026-11-15 10:02:24 +0800
for c in d3316a0 3d62d9d 61eb974; do
  git merge-base --is-ancestor $c origin/main && echo "$c 在 main 上"   # 三条全部命中
done
git branch -r | grep voca-3                     # origin/agent/agent/voca-34、origin/agent/sre/voca-35 均在
```

## 5. 其他发现（不属本 issue 范围）

- `FunAsrModelStore.kt` 类注释仍写「默认指向自有服务器的 nginx 静态目录」，换源后已不准确。
  该文件归 VOCA-34，等其合并后单独改一行，**不要**在本 issue 里动它以免制造冲突。
  → 2026-11-18 更新：VOCA-34 已 `done`，这一行没跟着改，已另立 VOCA-478 承接（含坏 `.part` 的 `BZh` 魔数预检优化）。
- `build-voca30/keystore/keystore.properties` 以明文提交了 `storePassword` / `keyPassword`
  （自签测试证书）。虽非本次变更引入，但属凭据入库，建议单独开 issue 处理（转用环境变量或
  从版本库移除并轮换）。
  → 2026-11-18 更新：已立 VOCA-477；当前该文件在 `main` 上的路径是 `keystore/keystore.properties`。
- `通用短语.csv`（61 KB）在仓库根与 `build-voca30/` 下各有一份同内容副本，属导入源文件残留。

## 6. 状态

> 2026-11-18 按 Multica 平台核对后重写；同日二次核对又抓到一行脱节（VOCA-474 记成 `qa_be`，
> 平台早已 `done`）。原表还把 VOCA-34 记成 `in_review`、VOCA-35 记成 `in_progress`，两者早已 `done`。
> 状态是快照，**权威值以平台为准**，不要拿本节当实时看板；改这张表前先跑一次下面这条命令逐行对：
>
> ```bash
> multica issue children 01a1bfa9-9508-7999-bbba-0bba096cc903 --output json \
>   | jq -r '.. | objects | select(has("identifier") and has("status")) | "\(.identifier) \(.status) \(.updated_at)"' | sort
> # 2026-11-18 二次核对输出（UTC）：34/35/474 done、475 in_progress、476/477/478 backlog
> ```

| Issue | 状态（2026-11-18 快照） | 说明 |
|---|---|---|
| VOCA-34 | `done` | 下载校验与 `.part` 自愈已进 `main`（`3d62d9d`）。注：`done` 早于项目规则 §四 要求的真机回归，该缺口现由 VOCA-476 承接 |
| VOCA-35 | `done` | 换源 + 探测脚本 + Runbook 已进 `main`（`61eb974`） |
| VOCA-33 | `in_progress` | 父单。验收口径 A1–A4：A1 达成；A2 待 PR #6 合并；**A3（VOCA-474）已闭合**、A4（VOCA-476）仍未闭合 —— 详见 §8 |
| VOCA-474 | `done` | `main` 活链路 `VoskModelStore` 下载零校验 + zip-slip，平台 2026-11-17 19:24Z 置 `done`。**注意**：`done` 是单子状态，不代表代码已落地 —— 承载修复的 PR #31（head `fix/voca-474-vosk-download-hardening`）此刻仍 `OPEN`、`mergedAt=null`，`origin/main` 仍是 `fa57a07`。合 main 属生产操作，要人类确认；在合并前从 `main` 出包仍无该校验 |
| VOCA-475 | `in_progress` | 本文件所在的收口单：文档状态同步 + 定时探测 + OSS 对象异地留档 |
| VOCA-476 | `backlog` | 真机回归证据，前置 = PR #6 合并出包 + 人类接上设备 |
| VOCA-477 | `backlog` | `keystore/keystore.properties` 明文凭据 |
| VOCA-478 | `backlog` | `FunAsrModelStore` 类注释 + 坏 `.part` 魔数预检 |

## 7. 待人类决策

> 11-09 提出后无人应答；2026-11-18 在 VOCA-33 / VOCA-475 再次升级，**限期 2026-11-20**。
> 沉默不再当作「默认不接」——两条都要一句原文答复（接 / 不接 + 决定人 + 理由）。

1. **月外网流出预算上限**：建议先按 ¥100/月（约 2,500 次下载）设流量告警，用到 70% 预警。
   未答复期间阿里云控制台没有任何流量告警，账单是唯一的事后信号。
2. **定时探测落在哪**：探测要跑在**境内出口**的常驻机器上，境外探测不代表用户链路
   （VOCA-33 用一次误判换来的教训，详见 `docs/model-host-runbook.md` §2.2）。
   - 可选：远端构建机 crontab（境内机房、常驻，建议项）；阿里云函数计算定时触发（境内 region、常驻、成本近零）。
   - **两个候选都是机房出口**，与用户住宅/移动出口不同链路，所以落点定了之后**还要在本机（住宅/移动）或办公网各跑一次做对照**，
     两边结论一致才算数。理由同下面「不可选」那条：出口不等于用户链路，只是方向反过来 —— 境内机房「绿」也不保证用户侧「绿」。
   - **不可选**：GitHub Actions —— runner 在境外，链路结论不成立，本条已从候选里划掉，不是等人二选一。
   - 未答复期间脚本不接任何调度 → 见 §8.3。

## 8. 合并之后的引擎变更与现状（2026-11-18 补，VOCA-475）

前面各节记录的是 11-09~11-10 的换源事实，**没有被 11-14 之后的变更作废，但生效范围变了**。
这一节只讲现状，避免后来者照 §4.2 的旧结论或 §6 的旧表判断。

### 8.1 时间线

| 时间（+0800） | 事件 |
|---|---|
| 2026-11-10 23:08 | 三条 squash（`d3316a0` / `3d62d9d` / `61eb974`）进 `main`，见 §4.3 |
| 2026-11-14 16:52 | `07214d6` 引入 Vosk 离线引擎：`main` 的 `AsrEngine` 变成 `SYSTEM / CUSTOM / VOSK`（无 `FUNASR`），麦克风链路改走 `IspeechImeService.kt:1630 → VoskModelStore.ensureModel`；`FunAsrSpeechEngine` 在 `main` 上除自身声明外零调用点 |
| 2026-11-15 10:02 | `main` 到 `fa57a07`（当前 tip） |
| 2026-11-15 11:09 | 发布线 `release/voca-186-v1.6`（tip `eba7a61`）仍是 FunASR 接线：`IspeechImeService.kt:284 funasrVoice = FunAsrSpeechEngine(...)`，`app/build.gradle.kts:22` 默认源为 OSS，该分支上没有 `VoskModelStore.kt` |
| 2026-11-17 | VOCA-33 质检 FAIL（打回项 3「监控仍为零」/ 4「文档与事实矛盾」/ 残余风险 3「OSS 对象只有一份」）→ 拆 VOCA-474 / 475 / 476 |
| 待发生 | PR #6（VOCA-190，base `main`、head `76491b9`、`OPEN` / `MERGEABLE`、6 files +208−21）恢复 FunASR 接线，排在 11-19 统一合并窗口；写 `main` 属生产操作，需人类确认，agent 不自行合并 |

复跑判据（2026-11-18 实测，全部本地 `git` 断言；每条都已逐条跑通、输出非空）：

```bash
git show origin/main:app/src/main/java/com/ispeech/settings/store/AsrSettings.kt | grep -n "SYSTEM\|CUSTOM\|VOSK"
#   :6 SYSTEM、:14 CUSTOM、:22 VOSK —— 没有 FUNASR

git grep -n "FunAsrSpeechEngine" origin/main -- app/src
#   只有 FunAsrSpeechEngine.kt:44 类声明 + :344 TAG 两处自身引用，无调用点

git grep -n "VoskModelStore.ensureModel" origin/main -- app/src
#   origin/main:.../com/ispeech/input/IspeechImeService.kt:1630 —— 这就是 main 的活链路

git grep -n "FunAsrSpeechEngine(" origin/release/voca-186-v1.6 -- app/src/main/java/com/ispeech/input/IspeechImeService.kt
#   :284 funasrVoice = FunAsrSpeechEngine(applicationContext) —— 发布线接了

git ls-tree -r --name-only origin/release/voca-186-v1.6 | grep -c VoskModelStore.kt
#   0 —— 该分支没有这个文件；注意 grep -c 计数为 0 时退出码是 1，不是断言失败
```

> 路径备忘：`IspeechImeService.kt` 在 `app/src/main/java/com/ispeech/input/`，**不是** `app/src/input/`。
> 上一版这里写的源相对路径 `app/src/input/IspeechImeService.kt` 不存在，`git grep` 直接 exit 1 零输出，
> 判据跑不通（VOCA-475 质检打回项 2）。写判据时先跑一遍再贴。

### 8.2 准确定性

- **装上 v1.6 内部包的人**：本单的换源与下载器加固是活的（发布线接线 + OSS 默认源都在包里）。
- **今天从 `main` 出包的人**：走 Vosk，本单能力一项都不覆盖 —— 那是 VOCA-474 的范围，不是换源失败。
- 人类 2026-11-17 已裁 FunASR 为 `active（在用）`，`main` 上断线定性为**回归**（VOCA-446 / ADR-0003），
  PR #6 合并即闭合 A2，不需要重开换源讨论。

### 8.3 本单（VOCA-475）交付的收口证据

1. **境内探测首次实跑**（2026-11-18 03:18~03:27 +0800，探测机出口经 `ipinfo.io` 判为 Guangzhou / CN，
   即境内链路，见 Runbook §1 复核行与 §2.2）：默认源 `--samples 3`
   → `MAJORITY: 3/3 次可用`、`SUMMARY: passed=21 failed=0`、**exit 0**；旧源 `cvm.agentai0.com`
   同样 3 次采样 → `3/3` 被重定向到 dnspod 拦截页、**exit 1**。脚本已加 `--samples N` 多数采样
   （平局判不可用），`--self-test` 11 项全过。
2. **OSS 对象异地留档**：已在同账号、另一 region 的既有备份桶落一份副本并逐字节复验一致
   （ETag / MD5 / CRC64 与主源逐项相同，重下载后 sha256 等于权威值），命令与结果见
   `docs/model-host-runbook.md` §5.4 与 §7.1。

**仍未闭合的一条**：定时探测没有调度器 —— 卡在 §7.2 的人类答复（限期 11-20），不卡脚本。
落点定了之后闭环还差一步：crontab 顶部要显式配 `ALERT_WEBHOOK`（cron 不继承登录 shell 环境，
空值时 curl exit 2、只有日志痕迹，Runbook §2.2 前置条件 1），并按 §7.2 的要求与住宅或办公网出口
做一次对照。答复到达前，本单覆盖的是「脚本 + 采样口径 + 定稿接法 + 境内一次实跑证据」，不是「已有告警」。
