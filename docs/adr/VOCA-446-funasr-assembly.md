# ADR-0003：FunASR 语音引擎装配口径裁决 —— 主干断线（回归），非文档超期（VOCA-446）

- **状态**：判定部分 **Accepted**（事实与裁决已坐实，见 §0–§3）；恢复动作部分 **Proposed**（§4 会把默认引擎改回 FunASR，属发布行为变更，按 issue 边界与人类规程第五条，**需人类确认后才执行**）
- **决策日期**：2026-11-17
- **决策人**：架构师（agent `e7ea905a-48f9-4dc9-86d5-32346dc237da`）
- **关联 issue**：VOCA-446（本 ADR）← VOCA-441（巡检发起，台账 `docs/CODE-ASSET-LEDGER.md` 仅存在于分支 `agent/agent/voca-441`，未合入 main）；上游 VOCA-183（交付计划，仅存在于分支 `agent/agent/voca-183`）、VOCA-190（接线恢复，PR #6 未合）、VOCA-186 / 188 / 231 / 159
- **关联既有文档**：`docs/adr/VOCA-0001/0002`（格式前例）、`docs/voice-input.md`、`docs/model-host-runbook.md`、`docs/asr-model-source-migration-log.md`、`release/voca-186-v1.6:dist/README.md`
- **本文基线口径**：代码结论与行号一律以 **`main` = `fa57a07`（versionCode 5 / versionName 1.5）** 为准；分支结论以 §2 表内 SHA 为准。
- **版本**：**v2**（2026-11-17，回应质检 FAIL 的 6 条打回项；v1 = `c442e7a` + 行号修正 `435a1e1`）。逐条修订与复验命令见 **§10**；A/B 判定本身未变。
- **范围**：只做定位、判定、留痕与派工契约。**不写实现、不删代码、不改 main、不改发布行为。**
- **复审条件**（任一满足即重审）：
  ① 人类改口「当前语音方案不是 FunASR」（则本 ADR 翻转为 B，§4 作废、走回收 issue）；
  ② 统一合并窗口（原排 11-19）取消或无限期顺延；
  ③ `AsrEngine` 再次被替换（任何把默认引擎从 `FUNASR` 挪走的提交）；
  ④ 出现第五个「同 versionCode、不同引擎」的包。

---

## 0. 结论先行

1. **裁决 = A（接线缺失 / 回归），不是 B（文档超期）。** 人类说的「FunASR 是当前在用的方案」**为真**，但它为真的是**发布分支 `release/voca-186-v1.6` 打出的包**，不是 main。main 上确实没有入口，也确实曾经有过。
2. **断线来源可指认，但成因不是「删了一半」**：`07214d6`（VOCA-voice-offline-1114，2026-11-14 16:52）是一次**完整移除** —— 枚举 `FUNASR`、默认值、`parse` 回落、`IspeechImeService` 三处接线、两份 `strings.xml` 的 `asr_engine_funasr{,_desc}`、两个依赖坐标与 `ASR_MODEL_URL` 全部一起删。今天 main 上这些**资产又回来了、接线没回来**，是 8 分钟后的两条「补齐 / 迁移修复」提交选择性回灌的结果：`53fa6be`（17:00:48，回灌依赖 + `ASR_MODEL_URL`）与 `3edfc83`（17:01:04，「补齐 squash 时未覆盖的 6 个文件到 main」，回灌两份 `strings.xml`）——**这两条都没碰 `AsrSettings.kt` 与 `IspeechImeService.kt`**。⇒ 要防的事故类别是「补齐 pass 漏配平」，不是「替换式提交留残留」（§3.1、§6 F-7）。
3. **「曾经有过」有硬证据**：`release/voca-r1`（= `76d178c`）**是 main 的祖先**，那棵树的 `AsrSettings.kt:24/:29/:57` 是 `FUNASR` 枚举 + `FUNASR` 回落 + 默认 `FUNASR`。所以不是「从没接过」，是**接过、进了 main 的血脉、被 `07214d6` 删掉**。
4. **恢复补丁能干净落在今天的 main**：`e49f23f`（6 文件 `+208/−21`）对 `fa57a07` `git apply --3way` 全部 cleanly，叠加后静态门禁 **153/153 PASS**（main 现为 151/151），且**不删任何既有 VOSK 通路**（补丁后枚举 = `SYSTEM / CUSTOM / FUNASR / VOSK`，service 里 VOSK 分支 4 处原样保留）。
5. **可落地候选有两个且内容等价，origin 侧干净**：`e49f23f` 与 **PR #6（head `76491b9`，base main，`MERGEABLE`，文件集恰好那 6 个）** —— 两者树内容只差一条 worktree 元数据删除。真正**禁止**的是**本地未推送的 tip `188ca3d`**：它比 `e49f23f` 多 **222 个文件 / +25301 行** agent 运行时产物，且经逐 commit `git ls-tree -r` + 全 `origin` ref `--is-ancestor` 核验**从未离开本机**（详见 §4.2 与 §7.2，严重度为内部暴露 + 一次误推风险，不需要重写共享历史）。
6. **给清理侧的口径**：§5 那 6 行 = **4 行 `装配缺口待修` + 2 行 `active`**（测试与两份运维手册本来就在跑 / 在被消费），**一个都不许记 `已废弃待回收`，也不许删**；台账侧的具体动作是「拆 1 行 + 净增 5 行 + 状态词表补一条」，不是整行替换（§5）。
7. **本轮新坐实两件 VOCA-441 当时标「未验证 / 不可得」的事**（§3.3）：主干出的包**确实把 FunASR 的 native 库打了进去**，实测 **81.1 MB**（4 ABI × `libsherpa-onnx-jni.so` + `libonnxruntime.so`，APK 内未压缩存储），却不可达 —— 这是纯体积占用，不是死代码无害。

---

## 1. 真实装配路径：四个假设逐一排除

issue 要求逐项排除后给确定答案。四项中**只有第三项成立**。

### 1.1 服务端 / SBC 侧承担识别（app 只做音频上行）？—— **否**

| 判据 | 证据（main） |
|---|---|
| 网络层无语音端点 | `app/src/main/java/com/ispeech/data/remote/` 只有 `AiApiService` / `AuthApiService` / `ChatApiService` / `SyncApiService` 四个 Retrofit 接口（9 个端点，全为文本 / 认证 / 同步用途），对 `asr\|recogni\|transcrib\|voice` **零命中** |
| 无音频上行通路 | 全仓 `AudioRecord` 只有两个消费者：`input/FunAsrSpeechEngine.kt`、`input/VoskSpeechEngine.kt`，均为**端侧自采自识别**，无 socket / multipart 上传 |
| 网关只有文本用途 | `app/build.gradle.kts:36` `API_BASE_URL = "http://api.agentai0.com/"`，由 `data/CsBabyClient.kt` / `data/DirectLlmClient.kt` 用于快捷短语与 AI 回复，不参与识别 |
| app 不是识别服务提供方 | `app/src/main/AndroidManifest.xml:10-16` 的 `android.speech.RecognitionService` 在 **`<queries>`** 内（targetSdk 34 包可见性声明），不是 `<service>` 导出；main 上没有任何 `RecognitionService` 实现类 |

> 复现：`grep -rn "AudioRecord" --include="*.kt" app/src/main/java`、`grep -rnE "@(GET|POST|PUT|DELETE|PATCH)\(" app/src/main/java/com/ispeech/data/remote/`（9 个端点，逐条看均为文本 / 认证 / 同步）、`grep -rniE "asr|recogni|transcrib|voice" app/src/main/java/com/ispeech/data/remote/`（**0 命中**）、`sed -n '10,16p' app/src/main/AndroidManifest.xml`
>
> 口径提醒：不要用 `grep -rn "speech\|audio"` 作为本条判据 —— 它在 `data/remote/` 会跑出 **7 条命中**，全部是包名 `com.ispeech` 里的子串，看起来像「有语音端点」而实际没有。

**结论**：识别 100% 在端侧。「FunASR 在用」不可能指服务端版本，B 判定里「当前方案 = 服务端」这一支柱不成立。

### 1.2 反射 / DI / ServiceLoader / flavor sourceSet / 子模块装配？—— **否**

| 机制 | 结论 | 证据 |
|---|---|---|
| 反射 | 无 | 全仓 `reflect` 仅 1 处：`data/RemoteKeywordRule.kt:8 import java.lang.reflect.Type`（Gson `TypeToken` 用途）；无 `Class.forName` |
| DI 框架 | 无 | 无 Hilt / Koin / `@Inject`；装配走 `di/AppContainer.kt` 手写，其中**无 FunASR 相关字段** |
| ServiceLoader | 无 | 全仓零命中 |
| flavor / sourceSets | 无 | `app/build.gradle.kts`、`settings.gradle.kts` 对 `flavor\|productChannels\|sourceSets` 零命中（只有 buildTypes 的 debug/release） |
| 子模块 | 无 | 单模块 `:app`，无 `engine-asr` 之类子工程 |

**结论**：不存在「main 上看不见但运行时装得上」的暗路径。main 的语音入口只有 `IspeechImeService.kt:480 / :496 / :1468` 三处 `if (asr.engine == AsrEngine.VOSK)` 与系统识别回退。

### 1.3 在途分支或未合并 worktree？—— **是，这是唯一成立的路径**

三条分支各持有一部分，性质不同：

| 分支 | 关键 SHA | 持有什么 | 相对 main |
|---|---|---|---|
| `release/voca-186-v1.6` | `eba7a61`（tip）/ `199b972`（出包）/ `a9d1dae`（版本号） | **完整 FunASR 接线**：`AsrSettings.kt:24` `FUNASR`、`:29` 回落 `FUNASR`、`:57` 默认 `FUNASR`、`IspeechImeService` 37 处 funasr 引用、`AsrSettingsScreen` 4 处；versionCode **6** / 1.6 | 分叉于 `76d178c`，**不含 `07214d6`**（`git merge-base --is-ancestor 07214d6 release/voca-186-v1.6` → NO），也**不含** main 之后 10 条提交 |
| `release/voca-r1` | `76d178c` | 同样含完整接线，versionCode **5** / 1.5 | **是 main 的祖先**（`--is-ancestor ... main` → YES）→ 这就是「曾经接进 main 血脉」的铁证 |
| `agent/agent/voca-190` | `e49f23f`（补丁本体）／ **origin tip `76491b9` = PR #6，干净** ／ **本地 tip `188ca3d`，含运行时产物、未推送** | 恢复接线的**可执行补丁**，另补 `AsrEngineTest.kt`（53 行，覆盖默认值 / parse 回落 / 老用户升级保留 / 大小写）与门禁扩展 | 基线 `f728231`，落后 main 2 条（`e0dddb2`、`fa57a07`）；`e49f23f` 不在任何 release/main 上；`188ca3d` 不在 origin 任何 ref 上（§4.2） |
| `origin/agent/agent/voca-231` | `d5f8cbe` / `c72ab39` | 给 `FunAsrSpeechEngine.kt` **加了 264 行 AsrTrace 埋点与超时收敛**，但该分支的 `AsrSettings` 枚举仍**只有 `SYSTEM/CUSTOM/VOSK`** | 未合 main |

> VOCA-231 那条要点：埋点加在一条不可达的链路上，等于**给死代码装仪表**。它一旦合 main，会加深「FunASR 看起来被维护、实际没有入口」的错觉。派工时必须与 §4 的接线恢复同窗口考虑（§4.3）。

### 1.4 远程配置 / 灰度开关打开后才注册入口？—— **否**

全仓对 `remoteConfig` / `featureFlag` / `grayscale` / `灰度` / `rollout` / `abTest` 在 `app/src/main/java` 与 `docs/` 下**零命中**。`BuildConfig` 只有 `API_BASE_URL`（`:36`）与 `ASR_MODEL_URL`（`:22 → :39`）两个编译期常量，后者唯一消费者是 `FunAsrModelStore.kt:52`。**没有任何运行时开关能让 main 的包出现 FunASR 入口。**

---

## 2. 根因（架构层面，比「某提交删了代码」更重要）

不是有人手滑删了 FunASR，而是**主干与发布线长期分叉、双向都不回灌**：

```
                    76d178c  ← release/voca-r1 (FunASR 已接线, vc5/1.5)
                      │      ← 也是 main 的祖先
        ┌─────────────┴───────────────┐
        │                             │
   07214d6 …fa57a07            bc81c30 … eba7a61
   = main (vc5/1.5)             = release/voca-186-v1.6 (vc6/1.6)
   枚举: SYSTEM/CUSTOM/VOSK      枚举: SYSTEM/CUSTOM/FUNASR
   默认 SYSTEM，FunASR 类打进包   默认 FUNASR，**没有 VOSK**
   但不可达                      FunASR = 装机在用方案
        ↑                             ↑
   任务分支都从这里长            真机现在装的是这条的包
```

- **两条线的引擎集合互斥，且「合回发布线」救不了主干**（实测枚举成员：main `fa57a07` = `SYSTEM/CUSTOM/VOSK`，`release/voca-186-v1.6` `eba7a61` = `SYSTEM/CUSTOM/FUNASR`，`76d178c` = `SYSTEM/CUSTOM/FUNASR`）。发布线自分叉后**没改过任何 `app/src` 文件**（`git diff --stat 76d178c release/voca-186-v1.6 -- app/` 只有 `app/build.gradle.kts` 的版本号 2 行），FunASR 对它不是「变更」而是「未被动过的原状」⇒ **把 `release/voca-186-v1.6` 合回 main 恢复不了接线**：`git merge-tree --write-tree main release/voca-186-v1.6` 产出的 merged tree（`9736c9a`）里 `AsrSettings.kt` 的 blob 仍是 `8224a06` —— 正是 `07214d6` 删除后的那份 main 文件，且在 `README.md` 上报 CONFLICT。
- 因此**唯一能恢复主干接线的已知补丁就是 `agent/agent/voca-190` 的 `e49f23f` 这一条**（它在 origin 上的可合并形态就是 PR #6，两者是同一棵树 —— §4.2），它是四条引用里唯一的并集（`SYSTEM/CUSTOM/FUNASR/VOSK`），其设计意图就是「补回 `FUNASR` 并保留 `VOSK`」，符合人类规程第三条「main 必须是全部修复的并集」。

- **main 丢了能力，发布线没丢**：`07214d6` 只进了 main，发布线在分叉点之前长大，天然不受影响。
- **main 上的断线是「补齐 pass」造成的，不是删除提交造成的**：`07214d6` 删干净之后，`53fa6be`（17:00:48）与 `3edfc83`（17:01:04）把资产回灌了一半，接线一行没回灌 —— 实测两条提交的 `--name-only` 均不含 `AsrSettings.kt` / `IspeechImeService.kt`，且 `git log 07214d6..main -- AsrSettings.kt` 为空、`git log -S "AsrEngine.FUNASR" 07214d6..main -- IspeechImeService.kt` 为 0 条。⇒ **根因是「删除提交与补齐提交之间没有做文件集比对」**，这是可门禁化的流程缺陷（§6 F-7），比「某人删了 FunASR」重要得多。
- **发布线也没有回灌 main**：`release/voca-186-v1.6` 的独有提交只有版本号与 dist 文档 7 条，不含任何功能修复。
- **两边同叫 1.5**：`release/voca-r1` 与 `main` 都是 versionCode 5 / versionName 1.5，引擎却不同。**versionCode 已无法反推设备上跑的是哪条引擎**——这正是 VOCA-231 只能写「分支归属是推断、非坐实」的原因（§6 失败模式 F-1）。
- **计划早已知道、只是没执行**：`release/voca-186-v1.6:dist/README.md` 白纸黑字写「最终包门禁 = **VOCA-190（FunASR 接线）** + VOCA-170 + VOCA-121 + VOCA-166 四条都合入 main 后重打」。VOCA-183 计划（仅存于 `agent/agent/voca-183:docs/plans/VOCA-183-asr-model-delivery.md`）已把它排成里程碑 **M0，11-19 执行**。

> 四条门禁目前**全部不在 main**（逐条 `git merge-base --is-ancestor <PR tip> main` → NO）。所以本单不是「发现一个新问题」，而是**给已排期的 M0 补上架构裁决与台账口径，让清理侧停止把 FunASR 当腐化资产**。

---

## 3. 判定依据与证据

### 3.1 为什么是 A 而不是 B

B 要求「FunASR 已不是本版本能力」。三个支柱逐一打掉：

| B 的支柱 | 判否证据 |
|---|---|
| 主干无引用 = 已下线 | 现状是**「移除完整、补齐 pass 选择性回灌」**留下的不平衡：`07214d6` 把接线与资产一起删干净，随后 `53fa6be` 回灌了 `build.gradle.kts:122-125` 两个依赖 + `ASR_MODEL_URL`、`3edfc83` 回灌了 `strings.xml:180-181` / `values-en:176-177` 两条文案，两份文档与 `FunAsrModelStoreTest`（236 行）本来就没被动过 —— 唯独接线没回灌。**真下线不会让资产回来、接线不回来**，所以这不是「已下线」的形态 |
| 被 Vosk 取代 | `07214d6` 在 main 上把默认引擎改为 `SYSTEM`（`AsrSettings.kt:54`）并让 `parse` 回落 `SYSTEM`（`:25-27`）—— 若 FunASR 真被取代，不会同时把默认从「内置离线」降成「依赖设备识别服务」 |
| 在用指服务端 | §1.1 已排除 |

### 3.2 门禁不挡恢复，真正的缺陷是**覆盖缺口**（所以断线能无声出包）

`tools/verify/check_crosscut.py`（main 版）把 VOSK / 系统两条路径当成了唯一规范，**对 FunASR 一条断言都没有**：

- `:854-855` 注释：「引擎已由 FunASR 换成 VOSK（07214d6）」
- `:859-865` `down_tokens` / `up_tokens` 只查 `if (asr.engine == AsrEngine.VOSK)` / `toggleVoskVoice()` / `voskVoice?.finish()`
- `:937-939` 只要求 `{"SYSTEM","CUSTOM"} ⊆ AsrEngine`（不要求 FUNASR，也不要求 VOSK）
- `:1004` 设置页文案断言只列 `asr_engine_system` / `asr_engine_custom`

⇒ 断线**没有**被门禁挡住，而是**被门禁放过去**的：main 现在跑 `151/151 PASS`，一个把默认引擎从内置离线悄悄降成设备识别服务的提交照样绿。这才是问题。

三组对照实测（临时 detached worktree，基线 `fa57a07`，跑完已清理）：

| 组合 | 结果 | 说明 |
|---|---|---|
| main 代码 + main 门禁 | `passed=151 failed=0` | 现状：断线状态下门禁全绿 |
| main 代码 + `e49f23f` 补丁代码 + 补丁门禁 | `passed=153 failed=0` | 恢复的完整形态 |
| **`e49f23f` 补丁代码 + main 门禁** | `passed=151 failed=0` | **门禁不挡恢复**，代码可独立先行验证 |
| **main 代码 + 只打门禁补丁（`--include=tools/verify/check_crosscut.py`）** | `passed=150 failed=3` | 扩了断言才第一次报出缺项：`按下缺 if (asr.engine == AsrEngine.FUNASR)` / `AsrEngine 缺 FUNASR / VOSK` / `默认引擎未设为 FUNASR` |

结论（也与 §4.1 第 5 行的理由绑定）：门禁扩展是**补覆盖、防复发**，不是恢复的前置条件 —— **代码与门禁可以分步提交、分步验证**，M0 窗口不必把两者当原子提交才能起步。反方向上它确实会挡：只 revert 代码而不回退门禁 = `150 failed=3`（§7.3）。

### 3.3 构建产物取证（补 VOCA-441 台账的两处空白）

台账原写「构建产物：未验证是否打入 release bundle」「运行时：不可得」。本轮实测（口径全部写明，便于复算）：

```
$ shasum -a 256 dist/ispeech-release-1.5-20261115-1004.apk
0ab26b9b140a0494e262462c696f6a96afa7527fe096fffdfc6d10d09cd68b30   139,171,786 B

# 体积口径 = APK 内 native 库未压缩 file_size 求和（4 ABI × 每条库；APK 内 compress_size == file_size）
$ unzip -l <apk> | grep -E 'sherpa-onnx-jni|libonnxruntime' | awk '{s+=$1} END {printf "%d\n", s}'
81119556            # = 18,531,568 (libsherpa-onnx-jni ×4) + 62,587,988 (libonnxruntime ×4) → 81.1 MB
$ unzip -l <apk> | grep 'libvosk.so' | awk '{s+=$1} END {printf "%d\n", s}'
39761100            # VOSK 侧 39.8 MB（与 §4.5 的「42 MB」不是同一口径，见结论 2）

# 入口口径 = dex 内枚举常量，用 apkanalyzer 直读，不靠字符串猜
$ apkanalyzer dex code --class com.ispeech.settings.store.AsrEngine <apk>
.field public static final enum CUSTOM / SYSTEM / VOSK      # ← 无 FUNASR
$ # 而 FunAsrSpeechEngine 类在 classes3.dex 内命中 21 次、asr_engine_funasr 资源名 ×2、
$ #   startFunAsrListening / funasrVoice / FUNASR(常量名) 均 0 命中
```

三条结论：
1. FunASR 的类与 **81.1 MB** native 库**确实进了主干产物**（4 ABI 齐），但产物里的 `AsrEngine` 只有 `CUSTOM / SYSTEM / VOSK` ⇒ 该包由 `07214d6` 之后的 main 构建，**FunASR 在包里但选不到、走不通**。
2. **口径别混**：81.1 MB / 39.8 MB 是 **APK 内 `.so` 占用**；`strings.xml` 与 runbook 里的「约 74MB」「约 42MB」是**首次下载的模型体积**（74 MB ≈ AC-3 实测 `77,920,048 B`；模型不打进 APK）。两个数字不可相加、也不可互相校准。
3. 「死代码零成本」不成立：主干每出一个包都为不可达引擎背 **81.1 MB**（占这个 139 MB 包的近 58%）。release 构建 `isMinifyEnabled = false`（`app/build.gradle.kts:58`），所以这类占用不会因为 shrinking 消失。
4. 该 APK **既不在 `dist/SHA256SUMS`（清单 5 条，均无语音）**，**也不在任何 git ref 上**（`git cat-file -e main:dist/ispeech-release-1.5-20261115-1004.apk` 与 `release/voca-186-v1.6:…` 均失败）⇒ 它是**未纳入版本控制的本地构建产物**，只是躺在 `dist/` 目录里、且被本任务的 baseline 提交在本地捕获过一次（§5 附带资产、§6 F-1 据此定性）。

---

## 4. A 的恢复方案（派工契约，非实现）

### 4.1 改动范围

| # | 文件 | 动作 | 规模（`git show --numstat e49f23f`，插入/删除） | 归属 |
|---|---|---|---|---|
| 1 | `settings/store/AsrSettings.kt` | 枚举补回 `FUNASR`（置于 `VOSK` 之前）；`parse` 回落改 `FUNASR`；默认值改 `FUNASR` | `+19 / −3` | 后端 |
| 2 | `input/IspeechImeService.kt` | `funasrVoice` 字段 + 三处 `AsrEngine.FUNASR` 分支（开始 / 结束 / 取消）+ 模型未就绪复用 `FunAsrModelStore` 下载与进度文案 | `+104 / −13` | 后端 |
| 3 | `settings/asr/AsrSettingsScreen.kt` | 引擎单选加第 4 项，复用现成 `asr_engine_funasr*` 文案 | `+6 / −0` | 前端 |
| 4 | `input/SettingsPanel.kt` | 快捷循环项与 label 映射补 `FUNASR` | `+4 / −1` | 前端 |
| 5 | `tools/verify/check_crosscut.py` | 语音链路断言扩到 FUNASR / VOSK / 系统 三条（**不放宽、不删断言**）—— 理由是**补覆盖、防复发**，不是「不解会挡路」（§3.2） | `+22 / −4` | 测试 |
| 6 | `settings/store/AsrEngineTest.kt` | 新增：默认值 / parse 回落 / 老用户升级保留 / 大小写不敏感 | `+53 / −0`（新文件） | 后端 + 测试 |

合计 **`+208 / −21`**（6 文件）。口径统一为 `git show --numstat` 的插入数 / 删除数；此前把 `git apply --stat` 的合计数（如 AsrSettings 的 22 = 19+3）标成「+N」是同一件事的两种数法，容易和 numstat 对不上，已在列头注明。

**零新增依赖、零 schema 变更、零对外协议变更**；`FunAsrSpeechEngine.kt` / `FunAsrModelStore.kt` **一行不改**（main 与 voca-190 上这两个文件的 diff 为空，实现本就是完整的）。

### 4.2 落地方式：**两条等价候选（PR #6 或 cherry-pick `e49f23f`），禁止的是本地 tip `188ca3d`**

| 候选 | 事实 | 可用性 |
|---|---|---|
| **PR #6**（head `76491b9`，base main） | `gh pr view 6` → `state=OPEN`、`mergeable=MERGEABLE`；`gh pr diff 6 --name-only` **恰好那 6 个文件**、`+208/−21` | 可作为落地候选。origin 侧逐 commit `git ls-tree -r` 扫描：28 条可达提交中 **0 条**含任何运行时产物路径 |
| **`e49f23f`**（cherry-pick 单提交） | `git apply --3way` 对 `fa57a07` 六个文件全部 cleanly；门禁 151 → **153/153 PASS** | 与 PR #6 内容等价（`git diff --stat e49f23f 76491b9` = 仅 `.claude/worktrees/voca-74` 1 行删除，即一条 worktree 元数据清理提交） |
| **本地 tip `188ca3d`**（`agent/agent/voca-190`，11-14 21:01） | 相对 `e49f23f` 多 **222 文件 / +25301 行 / −1**：221 个新增文件全在仓库根一个字面名为 `$HOME` 的目录下（`.qoder-de/de-fe/…`，shell 变量未展开被当成路径提交），另 1 条是 `.claude/worktrees/voca-74` 的删除 | **禁止推送、禁止用于合并**。**注意：它不在 origin 任何 ref 上**（`git merge-base --is-ancestor 188ca3d origin/agent/agent/voca-190` → NO；对 `refs/remotes/origin/*` 全量扫同样为否），所以风险是「一次误推」而非「已外泄」（§7.2） |

> PR #6 的 base 是 `f728231`（落后 main 两条 `e0dddb2` / `fa57a07`），GitHub 计算为可干净合并；M0 窗口若走 squash merge，产物与 cherry-pick 一致。两条路径任选其一，**不要第三条**。

### 4.3 与在途分支的两处必须收口

1. **`voca-231` 与恢复同源**：它对 `FunAsrSpeechEngine.kt` 加了 264 行埋点。两个选择——（推荐）**接线恢复先行，231 随后 rebase 到含 FUNASR 的 main**，让埋点第一次真正被走到；（不推荐）先合 231，则 main 上出现「有仪表、没发动机」。此项需 @测试 与 VOCA-231 owner 对齐先后。
2. **`release/voca-186-v1.6` 的去向**：M0 落 main 后，该发布线应停止接受功能提交，只作为 v1.6 试包的历史出处保留；最终包按 `dist/README.md` 门禁四条齐了再基于 main 重打。**不要在发布线上继续长新能力**，否则 §2 的分叉会再来一次。

### 4.4 回归用例（验收判据，直接接 VOCA-183 的 AC-1…AC-10）

| 用例 | 判据 | 覆盖的失败模式 |
|---|---|---|
| R-1 全新安装（清数据） | 设置页默认且选中 FunASR；不再出现「系统识别」为默认（AC-1） | 默认值 |
| R-2 老用户升级（曾选 VOSK） | 引擎**保持 VOSK**，不被静默改成 FunASR | 迁移覆盖 |
| R-3 老用户升级（曾选 FUNASR、被 main 回落成 SYSTEM） | 恢复后**重新解析回 FUNASR**（`parse` 回落已改），或至少在设置页可见并可选中 FUNASR；不允许出现「选了 FUNASR 但下次启动变 SYSTEM」的抖动 | §6 F-2 数据面 |
| R-4 大小写与脏值 | `parse("funasr"/"" /null/垃圾串)` 均为 FUNASR；`"system"` 仍 SYSTEM（`AsrEngineTest`） | 持久化兼容 |
| R-5 模型未就绪 | 首次点麦克风进下载 + 进度文案；失败走 `voice_model_failed`；源为 `build.gradle.kts:22` 的 OSS HTTPS（AC-3 复验 HEAD 200 / 77,920,048 B / Range 206） | 外部依赖（§6 F-4） |
| R-6 按住说话主链路 | 按下收音、松手上屏、上滑取消，与 VOSK / 系统识别三选一互不串（AC-2 / AC-4 / AC-5） | 引擎分派 |
| R-7 门禁 | `python3 tools/verify/check_crosscut.py` → **153/153**，断言数不得回落到 151。判据来自 §3.2 的对照实验：main 版门禁在断线状态下 **151/0 全绿**（放过了断线），补上断言后对同一份 main 代码报 **150/3** ⇒ 「回落到 151」等价于把无声出包的口子重新打开 | 覆盖缺口（§6 F-3） |
| R-8 产物 | `apkanalyzer dex code --class …AsrEngine <apk>` 出现 `.field … enum FUNASR`（本轮主干包实测只有 CUSTOM/SYSTEM/VOSK，故当前必 FAIL —— 这正是它该做的）；`libsherpa-onnx-jni.so` + `libonnxruntime.so` 4 ABI = 81.1 MB；sha256 与 ABI 清单登记进 `dist/SHA256SUMS` | §6 F-1 版本可追溯 |
| R-9 单测 | `AsrEngineTest` + `FunAsrModelStoreTest`（236 行，现成）全绿 | 质量门禁 |
| R-10 不回归 | 同一窗口内 VOCA-170（AC-9）/ 121（AC-10）/ 166 三条不因子合并回退 | §2 并集原则 |

### 4.5 发布影响

- **是发布行为变更**：默认引擎 `SYSTEM → FUNASR` 改变全新安装用户的语音通路（不再依赖设备是否预置可用识别服务；「识别语言」设置对 FUNASR 不生效）。⇒ **按 issue 边界与人类规程第五条，需人类确认后才执行合并与重打。**
- 包体积：**APK 内 `.so` 口径** —— 主干产物本已含 **81.1 MB**（4 ABI × sherpa-onnx-jni 18.5 MB + onnxruntime 62.6 MB，未压缩），恢复**不增加**任何体积，反而让这 81.1 MB 第一次物尽其用。
- 模型下载：**首次下载口径**（与上一行不是一回事，不可相加）—— FunASR 首次使用下载约 74 MB（AC-3 实测 `77,920,048 B`），VOSK 约 42 MB（`strings.xml` 文案口径；其 `.so` 侧是另一组数 39.8 MB）。二者按引擎二选一，不会同时下载。
- 已发出的 v1.6 试包（`9cddcec5…`，vc6/1.6）**本就带完整接线**，不受本方案影响，也不需要 OTA 修正。

---

## 5. 台账口径（可直接抄进 `docs/CODE-ASSET-LEDGER.md`）

> **不是「整行替换」，是「拆 1 行 + 新增 5 行」**（对齐 voca-441 现状后再给的口径）：`agent/agent/voca-441` 的 `docs/CODE-ASSET-LEDGER.md` 里 FunASR 只有 **1 条合并行**（该文件第 **28** 行，把 `FunAsrSpeechEngine` + `FunAsrModelStore` 写在一起，状态 `疑似腐化`，关联「本轮巡检 #P1」）；`strings.xml` / `docs/` 在模块清单里**没有对应行**。所以下表第 1、2 行 = 把第 28 行**一拆二**（1 行改造 + 1 行新增），第 3–6 行 = 全新增 ⇒ 台账模块清单**净增 5 行**（13 行 → 18 行）。
>
> 其余口径：列序沿用 voca-441 v2 的 8 列（模块 / Owner / 对外入口 / 上下游依赖 / 最后被修改 / 最后被调用 / 状态 / 关联 issue）；「最后被修改」为 `git log -1 main -- <path>` 实测值；`FunAsrSpeechEngine.kt` 行数 **358（`wc -l`，文件无末行换行）／359（逻辑行，voca-441 采用）** —— 本表沿用 voca-441 的 359，写 358 的人不是算错，是量法不同，台账里注明一次即可。
>
> **状态词表需先扩一条**：voca-441 的状态约定表目前只有 `active / 疑似腐化 / 待回收 / 已删除未合并 / 已回收`，**没有** `装配缺口待修`。清理侧要么在词表加这一行（含义建议写作「能力在用、主干缺入口：只报告、不删除，修复归属指向具体 issue」），要么退一步用 `active·装配缺口` 的组合标注；不要在既无定义又不在词表的情况下直接刷状态。「撤销原 `疑似腐化`、6 项一个都不许记已废弃待回收、都不许删」不变。

| 模块 | Owner | 对外入口 | 上下游依赖 | 最后被修改 | 最后被调用 | 状态 | 关联 issue |
|---|---|---|---|---|---|---|---|
| `input/FunAsrSpeechEngine`（359 行·逻辑行口径，见上方说明） | 后端 | **待恢复**：`IspeechImeService` 按 `AsrEngine.FUNASR` 分支接线（补丁 `e49f23f` / PR #6，落在 `fa57a07` 已验证 cleanly） | sherpa-onnx / `FunAsrModelStore` | main `904a98d` 2026-11-09；在途增强 `origin/agent/agent/voca-231` `d5f8cbe`（+264 行 AsrTrace，**未合且该分支枚举亦未接**） | main 不可得；**发布线在用**（`release/voca-186-v1.6` v1.6 试包） | **装配缺口待修**（能力在用，主干丢入口；**不删**） | VOCA-446 裁决 A → VOCA-183 M0 / VOCA-190 |
| `data/FunAsrModelStore`（442 行，含 `AsrDownloadPolicy`） | 后端 | 被 `FunAsrSpeechEngine` 消费；`BuildConfig.ASR_MODEL_URL` **唯一消费者**（`FunAsrModelStore.kt:52`） | okhttp / commons-compress / OSS 源 | main `3d62d9d` 2026-11-10（VOCA-34 下载校验与失败自愈） | 同上 | **装配缺口待修**（与引擎同批处置；连带收敛 `app/build.gradle.kts:22/:39` 配置口径） | VOCA-446 / VOCA-33 / VOCA-34 |
| `test/FunAsrModelStoreTest`（236 行） | 测试 | 覆盖判定分支（纯 JVM，抽出理由见 `FunAsrModelStore.kt:305`） | JUnit | main `3d62d9d` 2026-11-10 | 单测在跑 | **active**（当前唯一在跑的 FunASR 侧资产，恢复后须仍全绿；**缺 `AsrEngineTest`**，待随 M0 补，见 §4.1 第 6 行） | VOCA-446 |
| `res/values{,-en}/strings.xml` 的 `asr_engine_funasr{,_desc}`（`values:180-181` / `values-en:176-177`） | 前端 | **无代码引用**（`07214d6` 本已把它们一起删除，是 `3edfc83` 17:01 补齐 pass 回灌了文案、未连带回灌接线 —— 不是删除提交留的残留） | `AsrSettingsScreen` / `SettingsPanel`（恢复后接回） | main `af66733` 2026-11-14 | 运行时不展示（枚举无 FUNASR） | **装配缺口待修**（文案是**正确的**，缺的是把它接上；**不得反过来按文案删代码**） | VOCA-446 |
| `docs/voice-input.md`（`:24` `startFunAsrListening()/funasrVoice.finish()`、`:57` 系统/FunASR 同形态） | 后端/文档 | 语音链路说明 | `IspeechImeService` | main `2a1883e` 2026-11-10 | 文档，无调用 | **装配缺口待修**（描述与**发布线**一致、与 main 不一致；M0 落 main 后自动转正，其间须加一行「main 断线修复中，见 `docs/adr/VOCA-446-funasr-assembly.md`」） | VOCA-446 |
| `docs/model-host-runbook.md`（281 行）+ `docs/asr-model-source-migration-log.md`（116 行） | SRE/后端 | 模型源运维手册与迁移流水（OSS 换源、502 拦截、兼容期） | `FunAsrModelStore` / `network_security_config` | main `61eb974` / `d3316a0` 2026-11-10 | 运维执行时消费 | **active**（服务在用能力，非待回收；runbook `:11-13` 已自述「当前无已发布用户受影响，受影响的是内部测试机与后续出包」，与本 ADR 判定一致） | VOCA-446 / VOCA-33 / VOCA-34 |

**三个附带资产，台账需另起条目，否则下轮巡检还会误判**：

- 依赖坐标 `libs.sherpa.onnx` + `libs.commons.compress`（`app/build.gradle.kts:122-125`、`gradle/libs.versions.toml:43`）→ **active·装配缺口**，唯一用途是 FunASR；FunASR 若不接线则它们仍是「打包但不可达」。
- 配置 `ASR_MODEL_URL`（`app/build.gradle.kts:22` 默认 OSS → `:39` BuildConfig）→ **active·装配缺口**。
- 本地构建产物 `dist/ispeech-release-1.5-20261115-1004.apk`（`0ab26b9b…`，139 MB，含 81.1 MB FunASR 运行时、不含 FunASR 入口）→ **身份不明待处置**（§6 F-1）。**定性依据要写准**：它既不在 `dist/SHA256SUMS`（5 条清单）内，**也不在任何 git ref 上**（`git cat-file -e main:…` 与 `git cat-file -e release/voca-186-v1.6:…` 均失败），根 `.gitignore:40-44` 只排除 `dist/deploy-*.log` / `dist/screenshots/` / `dist/ispeech-debug-*.apk` / `dist/app-debug.apk`，release 包是**按惯例应入库**的（main 上现有 5 个 release/debug 包均为 tracked blob，最大 22.8 MB）—— 所以这个包的问题是**「未纳入版本控制的本地构建产物」**，不是「躺在 `dist/` 却没登记」。谁把它提交进任何分支，都会撞上 GitHub 单文件 100 MB 硬限（132.7 MiB），只能走 LFS 或外部存储；本任务分支的 baseline 提交在**本机**捕获过它一次，处置见 §10。

---

## 6. 失败模式清单（本轮新识别）

| ID | 模式 | 现状 | 处置 |
|---|---|---|---|
| F-1 | **产物身份不可追溯**：同 versionCode/versionName 覆盖多种引擎组合（vc5/1.5 至少三种：v1.5 发布包无语音、`voca-r1` 有 FunASR、main 只有 VOSK），vc6/1.6 另有未登记包 | 已发生（VOCA-231 只能写「推断非坐实」） | 出包门禁加「dex 内 `AsrEngine` 常量 + `.so` 清单 + sha256 进 `SHA256SUMS`」三件套（R-8）；versionCode 与发布线一一绑定，禁止跨线复用 |
| F-2 | **静默降级**：老用户持久化 `asr_engine="FUNASR"` 在 main 上被 `parse` 回落成 `SYSTEM`，语音从「离线内置」变「依赖设备识别服务」，无提示 | main 现为 `AsrSettings.kt:25-27` → `:54` | M0 恢复回落目标为 `FUNASR`；R-3 专项验证不抖动；迁移语义由 `AsrEngineTest` 锁死 |
| F-3 | **门禁覆盖缺口**：`check_crosscut.py:859-865` 的 `down_tokens`/`up_tokens` 只列 VOSK、`:937` 只要求 `SYSTEM/CUSTOM`、`:1004` 文案只列 system/custom ⇒ 门禁**从不为 FunASR 说话，也不拦它的消失**。实测：断线状态下 main **151/151 全绿**；把断言补上后同一份 main 代码 **150/3**。注意方向 —— 门禁**不挡恢复**（补丁代码 + main 门禁 = 151/0），它挡的是「无声出包」 | 已坐实（§3.2 四组对照） | 门禁扩展按 §4.1 第 5 行与代码**同批或分步均可**，断言数只增不减（151→153）；R-7 以「回落到 151 即失败」为判据 |
| F-4 | **外部依赖三问**：模型源挂了 / 限流 / 涨价 —— 旧 `cvm.agentai0.com` 源已被备案拦截（502），现默认走 OSS HTTPS；下载失败已有 `AsrDownloadPolicy` 校验 + 自愈（VOCA-34） | 已缓解 | 出包门禁保留 AC-3（HEAD 200 / Content-Length / Range 206）；runbook §79 的用户侧信号继续有效 |
| F-5 | **给死代码装仪表**：VOCA-231 的 AsrTrace 落在不可达链路，制造「FunASR 在被维护」的错觉 | 分支上，未合 | 按 §4.3 排序：接线先于埋点 |
| F-6 | **分叉复发**：只要允许在 release 线上继续长功能、且任务分支仍从 main 拉，下一次「主干无入口 / 发布线在用」会重演 | 结构性 | 合并窗口后 `release/voca-186-v1.6` 冻结（§4.3）；人类规程第三条「main 必须是所有修复的并集」按 F-1 的门禁机械化执行 |
| F-7 | **补齐 / 迁移修复类提交不与删除提交做文件集比对**（本轮断线的真实机制，也是最可能再来一次的一类）：删除提交本身是完整的（`07214d6` 一次删净接线 + 文案 + 依赖 + 配置），但随后 8 分钟内的两条「含迁移损坏修复」「补齐 squash 时未覆盖的 6 个文件」提交**按文件清单**而非**按依赖闭环**回灌，只回灌了资产、没回灌消费方 —— 于是留下「资产齐全、入口为零」的假活状态。同类形态还包括把 agent 运行时产物当「未提交改动」一起收进来的 baseline 捕获（§10） | 已发生（main 现状即其结果），且**这类提交还会再来** | 门禁建议（新增，非本文实现）：凡提交**重新加入**了「其唯一消费者已被删除」的资产 —— `libs.sherpa.onnx` / `libs.commons.compress` 坐标、`BuildConfig.ASR_MODEL_URL`、`asr_engine_*` 文案 key —— 而 `AsrEngine` 枚举里没有对应成员，就 **FAIL 并报出该提交**，要求同时恢复消费方接线或一并撤回资产。判据可由 `check_crosscut.py` 直接实现（它已具备读枚举成员与 gradle 依赖的能力，见 `enum_members()`）。落点：@测试 与 @重构清理大师 |

---

## 7. 代价与回滚条件

### 7.1 保留代价（不接线也不动的成本）
- 主干每个包背 **81.1 MB** 不可达 native 库（4 ABI × sherpa-onnx-jni + onnxruntime，占该 139 MB 包的 58.3%）；
- 6 份资产持续被静态扫描判为「疑似未使用」，每轮巡检都要重跑一遍本 ADR 的取证（VOCA-441 → VOCA-446 已是第二次）；
- 文档 / 文案 / 依赖与主干行为长期不一致，联调与验收口径漂移。

### 7.2 一处安全事项：**内部暴露 + 一次误推风险**，不是外泄（严重度已按实测下调）

**事实（本轮独立复跑）**：带运行时产物的是 `agent/agent/voca-190` 的**本地 tip `188ca3d`**（11-14 21:01 `chore(agent): uncommitted changes from task`），相对 `e49f23f` 多 221 个文件 / +25301 行，路径全在仓库根一个字面名为 `$HOME` 的目录下（`.qoder-de/de-fe/` —— shell 变量未展开被当作相对路径提交了）。**它从未被推送**：`git merge-base --is-ancestor 188ca3d origin/agent/agent/voca-190` → NO；对 `refs/remotes/origin/*` 全量扫同样为否；`origin` 侧 tip `76491b9` 的 28 条可达提交逐 commit `git ls-tree -r` 扫描，命中 **0 条**。

**暴露面（只看 size，未读内容）**：`.auth/.credential-transaction` 实测 **0 字节空文件**；真正有值的只有 `machine_id` 与 `installation_id` 两个 **36 字节**标识符，外加 1,026,912 B `runtime-info` 二进制、132 B `dns-cache.json`、46,289 B CLI 日志。

**因此处置力度应相应下调**：**不需要**重写共享分支历史，**不需要**轮换标识（没有任何东西离开过本机）；需要的是（1）**禁止把本地 tip `188ca3d` 推送或用于合并**，落地走 PR #6 或 cherry-pick `e49f23f`（§4.2）；（2）一条 SRE 待办：清理各机工作目录里的 `$HOME` 字面目录，并把「任务 baseline 捕获未提交改动」这类自动提交**限定在仓库跟踪路径内**（同类事故本单已发生第二次，见 §10）。具体值不在本文复述（harness 第 8 条脱敏）。

### 7.3 回滚条件
- **恢复动作可回滚**：`e49f23f` / PR #6 是纯增量（实测：补丁后 `AsrEngine` = `SYSTEM / CUSTOM / FUNASR / VOSK`，`IspeechImeService` 里 VOSK 分支 **4 处原样保留**、新增 FUNASR 分支 4 处），回滚 = `git revert` 该提交，`parse` 回落与默认值一并退回 `SYSTEM`。**但回滚必须连门禁一起退**：只退代码不退门禁 = `150 failed=3`（§3.2 第四组实测），回滚提交会自己把 CI 打红。恢复侧则相反 —— 只上代码不上门禁也是 `151/0` 全绿，这正是覆盖缺口该由 R-7 兜住的原因。
- **触发回滚的判据**：R-2 / R-3 任一失败（老用户被静默换引擎）、R-5 在真机上出现模型下载不可恢复失败、或 v1.6 最终包 AC-1…AC-10 任一不过 ⇒ 按人类规程第四条退回对应 issue 分支重做，主线不前进。
- **不可回滚项**：无 schema、无对外协议、无数据迁移，故不存在需要数据回滚的点。

---

## 8. 待人类确认（**2 项**，不带假设往下做）

1. **确认「当前在用」= 端侧 FunASR**（非服务端）。§1.1 已排除服务端；若人类本意指别的，本 ADR 整体翻转为 B。
2. **确认默认引擎 `SYSTEM → FUNASR` 是期望的发布行为**（影响全新安装用户；§4.5）。补丁已把「已明确存过 `SYSTEM`/`CUSTOM`/`VOSK` 的老用户保留原值」写进注释并由 `AsrEngineTest` 锁死，所以这一项只关全新安装与脏值回落，不关老用户迁移。

> 原列在本节的第 3 项「`voca-190` 与 `voca-231` 的合并先后是否需要人类决策」**已取消**：§4.2 修正后 PR #6 本身已核为干净、可直接落地，不再需要为「分支有毒所以禁止合并」做裁决。剩余的排序建议（接线先于埋点，§4.3）作为 M0 窗口的执行建议保留，属项目经理权限，不占人类决策位。§7.2 那条凭据事项同步降级为 **SRE 待办**，不再列为人类安全裁决项。

## 9. 派工

- **§4.1 全部 6 项实现** → @后端（第 1/2/6 项）+ @前端（第 3/4 项）；落地方式按 §4.2（PR #6 或 cherry-pick `e49f23f` 二选一，**不碰本地 tip `188ca3d`**）。
- **§4.4 的 R-7 / R-8 门禁与产物断言** → @测试（与第 5 项同批，亦可分步验证 —— §3.2 已证明二者不构成原子依赖）。
- **§6 F-7 的「补齐 pass 文件集比对」断言** → @测试 设计、@重构清理大师 在巡检里作为检索式执行（同一判据两处分派，避免只做一半）。
- **§6 F-1 出包三件套 + §7.2 SRE 待办（本机 `$HOME` 字面目录清理 + baseline 捕获范围收敛）+ `dist/…1004.apk` 的归档/处置** → @SRE。
- **§5 台账：`docs/CODE-ASSET-LEDGER.md:28` 拆 2 行 + 新增 4 行 + 状态词表补 `装配缺口待修`** → @重构清理大师 下一轮巡检对齐，并撤销 FunASR 的 `疑似腐化` 标记。
- **§4.3 发布线冻结与合并窗口排序（含「接线先于埋点」建议）** → @项目经理（VOCA-183 的 M0 仍是唯一执行载体，本 ADR 不新开窗口）。

---

## 10. v2 修订记录（逐条对应质检打回项）

A/B 判定、四项假设排除、`e49f23f` 唯一性、「合回发布线无效」、APK 纯占体积、§5 状态口径（`装配缺口待修`、不删）**均未改动**。以下 6 条为重写项，每条附本轮独立复跑的命令与结果。

| # | 打回项 | 本文改动 | 复验命令（实测输出） |
|---|---|---|---|
| ① | 「凭据已 push 到 origin」证伪 | §0.5、§4.2、§7.2、§8 全改；严重度降为内部暴露 + 误推风险 | `git merge-base --is-ancestor 188ca3d origin/agent/agent/voca-190` → **NO**；逐 commit `git ls-tree -r` 扫 28 条 → 命中 **0**；`gh pr view 6` head=`76491b9` base=main `MERGEABLE`；`gh pr diff 6 --name-only` → **6 个文件** |
| ② | 门禁因果方向反了 | §3.2 重写为四组对照；§4.1 第 5 行理由改「补覆盖、防复发」；R-7、F-3、§7.3 同步 | 临时 worktree（基线 `fa57a07`）：main `passed=151 failed=0` → `e49f23f` 全打 `153/0` → 代码打了、门禁回 `fa57a07` 版 **`151/0`（不挡）** → 代码回 main、只打门禁补丁（`--include=tools/verify/check_crosscut.py`）**`150/3`** |
| ③ | 成因不是「只删一半」 | §0.2、§2 新增一条、§3.1 支柱一改写、§5 文案行改写、新增 **F-7** | `git show 07214d6` 同时删 `-FUNASR;` + 两份 strings 的 `asr_engine_funasr{,_desc}` + `libs.sherpa.onnx`/`libs.commons.compress` + `ASR_MODEL_URL`；`53fa6be`(17:00:48) 回灌依赖与配置、`3edfc83`(17:01:04) 回灌文案；两条的 `--name-only` 均 **0 次**命中 `AsrSettings.kt`/`IspeechImeService.kt`；`git log 07214d6..main -- AsrSettings.kt` 空、`git log -S "AsrEngine.FUNASR" 07214d6..main -- IspeechImeService.kt` **0 条** |
| ④ | 「约 44 MB」数值错误 | §0.7、§3.3、§4.5、§7.1 改 **81.1 MB**，并把 `.so` 口径与模型下载口径分开 | `unzip -l <apk> \| grep -E 'sherpa-onnx-jni\|libonnxruntime' \| awk '{s+=$1} END{print s}'` = **81,119,556**（18,531,568 + 62,587,988）；`libvosk.so` ×4 = **39,761,100**；44.1 MB 恰为 arm64-v8a + x86_64 两个 64 位 ABI 之和 |
| ⑤ | ADR 未落库 | 本版推送并开 PR（base main），产物指向 PR head | 见 issue 评论：`gh pr view --json headRefOid,baseRefName`、`gh pr diff --name-only` = 1 个文件（本 ADR） |
| ⑥ | 台账与口径小错 | §5 改为「拆 1 行 + 新增 5 行」+ 状态词表缺 `装配缺口待修` + 358/359 口径；§4.1 规模列统一 numstat；§1.1 换掉自打脸的 grep 命令 | voca-441 `docs/CODE-ASSET-LEDGER.md` 第 **28** 行是唯一 FunASR 合并行；`FunAsrSpeechEngine.kt` `wc -l`=**358**、末字节 `}` 无换行 ⇒ 逻辑行 **359**；`git show --numstat e49f23f` = 104/13、4/1、6/0、19/3、53/0、22/4；`grep -rn "speech\|audio" data/remote/` = **7 命中**（全是 `com.ispeech` 子串），`grep -rniE "asr\|recogni\|transcrib\|voice"` = **0** |

**一处需要如实登记的自我发现**：本任务分支的 baseline 提交 `09d7979` 把 `dist/ispeech-release-1.5-20261115-1004.apk`（139,171,786 B = **132.7 MiB**，本机工作目录里的未跟踪产物）一并捕获进了本地历史 —— 正是 §6 F-7 说的「baseline 无差别捕获未提交改动」在本单身上的复发。处置：v2 在同一分支上追加一条提交把它**移出跟踪并补 `.gitignore` 规则**（文件仍留在磁盘，不删用户的构建产物），且**不把它推上 origin** —— 单文件超 100 MB 会被 GitHub 直接拒收，直推这条分支必然失败。因此 §8/PR 走的是只含 **2 个文件**（本 ADR + 一条 `.gitignore` 预防规则）的干净 ref，不含任何产物（见 issue 评论里的两个 ref 与理由）。这没有实测直推（不愿为验证把 132 MB 对象推上 origin 并触发 CI），若需直推回执，请人类明确指示。
