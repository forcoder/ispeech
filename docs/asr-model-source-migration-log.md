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

### 4.2 两条子分支都未推到 origin

`agent/agent/voca-34`、`agent/sre/voca-35` 在 `origin` 上都不存在，也没有 PR。
合并前需推送并开 PR（标题带 `VOCA-33`）。截至 2026-11-10 仍未推送。

## 5. 其他发现（不属本 issue 范围）

- `FunAsrModelStore.kt` 类注释仍写「默认指向自有服务器的 nginx 静态目录」，换源后已不准确。
  该文件归 VOCA-34，等其合并后单独改一行，**不要**在本 issue 里动它以免制造冲突。
- `build-voca30/keystore/keystore.properties` 以明文提交了 `storePassword` / `keyPassword`
  （自签测试证书）。虽非本次变更引入，但属凭据入库，建议单独开 issue 处理（转用环境变量或
  从版本库移除并轮换）。
- `通用短语.csv`（61 KB）在仓库根与 `build-voca30/` 下各有一份同内容副本，属导入源文件残留。

## 6. 状态

| Issue | 状态 | 说明 |
|---|---|---|
| VOCA-34 | `in_review` | 下载链路修复，16/16 单测通过，等人工评审 |
| VOCA-35 | `in_progress` | 换源已落地、验收全绿、gitlink 已由 `f916c4b` 清除；等 SRE 本轮自检后转 `in_review` |
| VOCA-33 | `in_progress` | 父 issue；链路已端到端验证，待两条子分支推送并开 PR 后转 `in_review` |

## 7. 待人类决策

1. **月外网流出预算上限**：建议先按 ¥100/月（约 2,500 次下载）设流量告警，用到 70% 预警。
2. **定时探测落在哪**：构建机 crontab（改动小、与现有构建机同机）vs GitHub Actions（异地探测、
   不依赖自有机器）。建议前者，因为探测要反映国内用户实际链路。