# 模型托管源 Runbook（ASR 离线模型）

适用于 `ASR_MODEL_URL`（`app/build.gradle.kts` 的 `asrModelUrl`）指向的
`sherpa-onnx-paraformer-zh-small-2024-03-09.tar.bz2`（77,920,048 字节）托管源。
判定工具：`tools/verify/check_model_host.py`（退出码 0 = 可用，非 0 = 不可用）。

> 一句话：**探测红 = 换源**。默认源已于 2026-11-09 从被备案拦截的 `cvm.agentai0.com`
> 切到阿里云 OSS 公共读对象（`https://delivery-service-smzk.oss-cn-guangzhou.aliyuncs.com/models/asr/…`，
> 落地配置见 §5.1，行为差异见 §6）。
>
> 影响面：`dist/` 里的 5 个包**均不含 FunASR，且属预期**——`dist/README.md`「已知限制」写明 v1.5 不含语音，
> 且 `72bb547`（v1.5，2026-10-17）早于语音功能 `6fa6f5d`（2026-10-18）。
> 因此**当前没有已发布用户受影响**，受影响的是内部测试机与后续出包；一旦有含 FunASR 的包发出去，
> 旧 URL 就会变成必须恢复的兼容债务（`cvm` 的明文放行条目因此保留，见 §5.2）。

## 1. 现状（2026-11-09 实测）

| 目标 | 结果 | 判读 |
|---|---|---|
| `https://delivery-service-smzk.oss-cn-guangzhou.aliyuncs.com/models/asr/…tar.bz2`（**新默认源**） | `200` / `application/octet-stream` / `Content-Length 77920048` / `Accept-Ranges: bytes`；`Range: bytes=16780-` → `206`，`Content-Range: bytes 16780-77920047/77920048`；整包 sha256 一致（3.2s） | **可用**：HTTPS + Range，默认域名免备案（详见 §5、§6） |
| `http://cvm.agentai0.com/funasr/…tar.bz2`（旧默认源，**2026-11-09 复测**） | `302 -> https://dnspod.qcloud.com/static/webblock.html?d=cvm.agentai0.com`，跟随后 `502 Bad Gateway / text/html / 166 B` | **不可用**：腾讯云「未备案」拦截页；拦截页体积已从上一轮记录的 16,780 B 变为 166 B —— 任何断言都不要依赖具体字节数 |
| `http://cvm.agentai0.com/` | 同上 302 → 拦截页 | 整个 Host 被拦，不是单文件问题 |
| `https://cvm.agentai0.com:443` | 连接被拒（该子域没有 TLS） | — |
| DNS `cvm.agentai0.com` | `43.136.44.72`（与远端构建机同一台） | — |
| `http://` 直连 `43.136.44.72` + `Host: cvm.agentai0.com` | 仍 302 → 拦截页 | 拦截在网络层按 Host 生效，**改 nginx 配置无效** |
| `http://43.136.44.72/` + `Host: <任意未备案域名>` | `307 /auth/login`（nginx/1.18.0，真实应用） | 机器本身正常，只是该 Host 未备案 |
| `http://api.agentai0.com/funasr/…tar.bz2` | `404`（nginx/1.24.0） | 域名正常，但没挂静态目录 → **可改造** |
| `https://api.agentai0.com/` | 证书 `CN=www.agentai0.com`，主机名不匹配 | 443 无 api 的证书 → 要用 HTTPS 需先签证书 |
| `https://www.agentai0.com/funasr/…tar.bz2` | `404`（站点正常，未挂模型） | 已有 Let's Encrypt 证书，**可改造** |
| `https://github.com/k2-fsa/sherpa-onnx/releases/…` | 首次实测：200 / `application/octet-stream` / 77,920,048 B / `BZh` / Range 206 / sha256 一致；同日稍后复测曾出现 `github.com:443` TCP 超时；**换源当天再次复测：整包 5.4s / 14.5 MB/s 可达** | 官方源内容正确，但链路**时通时断** —— 只能做兜底/对照，不宜做默认源 |

关键结论：

0. **默认源已换成 OSS**（§5.1）。上面的 OSS 行就是当前 `app/build.gradle.kts` 的兜底值，
   `python3 tools/verify/check_model_host.py`（无参数）探测的就是它。
1. `cvm` 子域是**备案问题**，不是服务挂掉；机器和 nginx 都在，只是该 Host 被腾讯云前置拦截。
2. `api.agentai0.com`（`121.43.55.151`）与 `www.agentai0.com` 是另一台机器，域名正常、已备案，
   `api` 走明文 HTTP、`www` 有 Let's Encrypt 证书 —— 两者都具备托管静态文件的硬件条件。
3. 官方 GitHub Release **内容完全正确**（74.3MB、sha256 与官方值一致、Range 206），
   但本探测环境在同一天内既出现过 `github.com:443` 超时、也出现过 14.5 MB/s 的正常下载 —— 链路**时通时断**，
   只能当**兜底与对照**，不能当默认源（与 `FunAsrModelStore.kt` 注释里「GitHub 对国内链路过慢」的判断一致）。
   本次上传用的就是这份包（下载后校验 sha256 再上传，见 §5.1）。

## 2. 发现路径与告警

### 2.1 探测命令

```bash
# 探测当前默认源（从 app/build.gradle.kts 读取 asrModelUrl）
python3 tools/verify/check_model_host.py

# 探测指定源；--sha256 会整包下载并校验摘要（约 78MB，用于换源验收）
python3 tools/verify/check_model_host.py <url> --sha256

# 不联网验证判定逻辑本身（本地伪造 6 类故障）
python3 tools/verify/check_model_host.py --self-test
```

退出码：`0` 全绿；`1` 任一断言失败（含 DNS/TCP/TLS 失败）。可直接接 CI / cron。

断言清单（缺一不可）：

| # | 断言 | 防的是什么 |
|---|---|---|
| 1 | 重定向链上无 `dnspod.qcloud.com` / `webblock` | 备案拦截页（本次事故） |
| 2 | 最终状态码 200 | 4xx/5xx、拦截页 502 |
| 3 | `Content-Type` 不是 `text/*`、`json`、`xml` | **200 + HTML** 的隐性故障 |
| 4 | `Content-Length == 77920048` | 截断 / 源上是别的文件 |
| 5 | 响应体前 3 字节是 `BZh`（bzip2 魔数） | 头部撒谎、错误页伪装成 octet-stream |
| 6 | `Range: bytes=16780-` → 206 且总长一致 | 断点续传前提；回 200 会让客户端续传逻辑退化，回 416 就是本次死循环 |
| 7 | `--sha256` == `da92b3db…b1450` | 内容被替换 / 镜像不同步 |

### 2.2 三层发现

| 层 | 触发时机 | 落点 |
|---|---|---|
| 发布前门禁（**强制**） | 每次出包前 | 人工跑一次；红则 no-go，不许发版 |
| 定时探测（**待接**） | 每 30 分钟 | 见下方 cron / Actions 片段 |
| 用户侧信号 | 持续 | logcat `FunAsrModelStore` 出现 `funasr category=…`：源站类 `HTTP_4xx` / `NOT_MODEL_PAYLOAD` / `SIZE_MISMATCH` / `DIGEST_MISMATCH`，落盘类 `EXTRACT_STREAM_FAILED` / `EXTRACT_SHORT_WRITE` / `ARTIFACT_MISSING_ONNX` / `ARTIFACT_MISSING_TOKENS` / `RENAME_FAILED`，自愈类 `SELF_HEAL_RE_EXTRACT`（INFO，正常）；源站日志 404 突增；`R.string.voice_model_failed` 相关反馈 |

定时探测两种接法（执行环境需人类确认后再接，二者选一）：

```bash
# ① 远端构建机 crontab（43.136.44.72，与构建机同一台，出网到源站链路与用户侧不同，
#    因此还要在本机/办公网各跑一次作为对照）
*/30 * * * * cd ~/workspace/ispeech && python3 tools/verify/check_model_host.py \
  >> ~/logs/model-host.log 2>&1 || curl -s "$ALERT_WEBHOOK" -d '{"text":"ASR 模型源不可用"}'
```

```yaml
# ② GitHub Actions（仓库已有 origin: forcoder/ispeech；需人类确认是否允许在 CI 里跑）
# .github/workflows/model-host-probe.yml
on:
  schedule: [{ cron: "*/30 * * * *" }]
  workflow_dispatch:
jobs:
  probe:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - run: python3 tools/verify/check_model_host.py --quiet
```

### 2.3 分级与首响

| 级别 | 条件 | 首响 | 动作 |
|---|---|---|---|
| **P1** | 默认源探测红，且没有已验证的备用源 | 30 分钟 | 按 §3 换到已验证源；同步告知人类 |
| **P2** | 默认源红但备用源绿（可切）；或备源本身红 | 4 小时 | 切备用 + §5 里排期正解 |
| **P3** | 源可用但明文 / 慢（首字节 > 5s 或 < 500KB/s）/ 证书 < 14 天到期 | 1 周 | 排期整改，不阻塞发布 |

只有 P1/P2 才打扰人；P3 进待办。**没有处置动作的告警不接入**。

### 2.4 告警清单（接入监控系统后按此建，当前以脚本退出码为落点）

| 告警名 | 阈值 | 分级 | 通知 | Runbook |
|---|---|---|---|---|
| `ModelHostProbeFailed` | 连续 2 次探测退出码 ≠ 0 | P1（无备源）/ P2（有备源） | on-call（qiaozhou）+ 本 issue 评论 | §3 |
| `ModelHostCertExpiring` | 证书剩余 < 14 天 | P2 | on-call | 仅适用自建源；OSS 默认域名的证书由阿里云签发/续期，**默认源不再需要本条** |
| `ModelHostSlow` | 首字节 > 5s 或 < 500KB/s 持续 10 分钟 | P3 | 值班邮件 | §7 |

## 3. 换源处置 SOP（目标 30 分钟内恢复）

0. **确认不是本地网络**：换一台网络（或远端构建机）再跑一次探测；看输出里是
   `无法访问`（网络）还是 `拦截页` / `Content-Type`（源站）。
1. **选源**：对候选源跑 `--sha256`，必须全绿（含摘要一致）。主源与备用源见 §5.1 / §5.2。
2. **切源**（按影响面从小到大）：
   - 只救新包：`./gradlew assembleRelease -PasrModelUrl=https://…`（不改代码，见 §4）；
   - 改默认值（**需人类 @qiaozhou 确认**）：改 `app/build.gradle.kts` 的 `asrModelUrl` 兜底值，
     若改成明文 HTTP 还要确认 `network_security_config.xml` 放行该域名（`check_static.py` 第 6 项会拦住不一致；
     换成 HTTPS 则该项直接 PASS）。改完同步更新 `check_model_host.py` 的 `FALLBACK_URL`，否则解析失败时会探测到旧源。
3. **验证**：探测脚本全绿 → 装一版到真机 → 点麦克风走到「模型下载 100%」→ 说一句话上屏 →
   `logcat | grep FunAsrModelStore` 无告警。
4. **回滚**（≤10 分钟）：重新用旧 `-PasrModelUrl` 出包分发；服务端**保留旧文件 ≥ 30 天**，
   不要在新源验证通过前删除旧文件。
5. **留痕**：在本 issue 评论写「谁 / 何时 / 旧 URL → 新 URL / 探测与装机证据」，并更新本文 §1 表格。

**换源不是数据变更**：模型包是无状态文件，回滚只需换回 URL，不存在数据不可逆问题。

## 4. 为什么换源不用改代码，但多数情况仍要发版

- `-PasrModelUrl` 是 **Gradle 构建期**覆盖（`app/build.gradle.kts` 顶部），它避免的是
  「改业务代码 / 改常量」，**不改变已编译进 APK 的值**。
- 因此：**新装用户**换源后立刻生效；**已安装用户**的 URL 写死在 APK 里，只能靠
  `cvm.agentai0.com` 域名恢复（§5 方案 C）或重装新包。当前 `dist/` 里的包不含 FunASR，
  且这是**预期**（`dist/README.md`「已知限制」+ `72bb547` 早于 `6fa6f5d` 的提交顺序），
  所以这个风险现在体现在**内部测试机**上；一旦含 FunASR 的包发布，必须先想清楚旧 URL 的兼容期。
- 真要做到「不发版换源」，需要后端下发 URL（远端配置）+ 客户端兜底缓存，属功能变更，
  不在本 issue 范围 —— 建议另开 issue 给架构师评估。
- 客户端放行规则（`app/src/main/res/xml/network_security_config.xml`）：
  - `http://` 源必须在该文件的 `domain-config` 里（当前已放行 `api.agentai0.com`、`cvm.agentai0.com`）；
  - `https://` 源默认放行，不需要改该文件。

## 5. 托管方案对比与决策记录

> **决策：走对象存储 —— 已选定并落地（2026-11-09，人类 qiaozhou 在 VOCA-33 选定）。**
> 字母口径说明：人类看到的是 VOCA-33 评论里的表（「B｜先走对象存储：OSS/COS + CDN，备案与证书由云厂商兜底」）；
> 本表是 VOCA-35 上一轮自排的版本，其中 **D** 才对应父 issue 的 **B**。
> **以「对象存储」这个描述为准，不要拿字母对齐。**

| 方案 | 做法 | 成本 | 风险 | 状态（2026-11-09） |
|---|---|---|---|---|
| A. 复用已备案域名 | `api.agentai0.com` 的 nginx 加静态目录，默认 URL 改该域名 | 已有机器，无新增费用 | 明文 HTTP；与 `api` 业务共享单机带宽 | 未采用 |
| B. A + HTTPS | A 之上给 `api` 签证书 | 证书签发/续期约 1 小时 | 续期失败会中断下载 | 未采用 |
| C. 恢复 `cvm` 子域备案 | 走备案把 `cvm.agentai0.com` 恢复 | 数个工作日 + 主体资质 | 可能再次被拦；该机同时是构建机 | 未采用（仅当已有含 FunASR 的包发出才变必须项） |
| **D. 对象存储（阿里云 OSS）** | 公共读 bucket + 默认域名，`https` + Range 由云厂商兜底 | 按流量/存储计费（§7） | 依赖第三方可用性；对象无版本控制，误删需重传 | **已选定并落地** |

### 5.1 已落地配置（阿里云 OSS）

| 项 | 值 |
|---|---|
| bucket | `delivery-service-smzk`（cn-guangzhou，ACL `public-read`，创建于 2026-09-16） |
| prefix | `models/asr/` |
| object | `oss://delivery-service-smzk/models/asr/sherpa-onnx-paraformer-zh-small-2024-03-09.tar.bz2` |
| 默认下载 URL | `https://delivery-service-smzk.oss-cn-guangzhou.aliyuncs.com/models/asr/sherpa-onnx-paraformer-zh-small-2024-03-09.tar.bz2` |
| `Content-Type` | `application/octet-stream`（上传时显式指定，不让客户端按扩展名猜） |
| 大小 / sha256 | 77,920,048 B / `da92b3db5218c5be53aad53e57d1b6e63e7fc98a0e054fbdd6dbe18e9c6b1450` |
| ACL / 加密 / 存储类 | 继承 bucket `public-read`；SSE-AES256；Standard（LRS） |
| 上传源 | 官方 GitHub Release，本机下载后 `shasum -a 256` 校验一致再上传 |
| 上传时间 | 2026-11-09 19:43 +0800 |

**为什么选这个 bucket**：它已经是 `public-read`，加一个 `models/asr/` 前缀**不需要改任何 ACL / bucket policy /
生命周期规则** —— 变更面只有「多一个对象」。备选 `baigong-file` 是 private 且装着文档类资产，为放模型给它开公共读
（或加 prefix 级 policy）本身是一次权限变更；在已经是公共读的交付桶里加前缀，比给私有桶开口子的爆炸半径更小。
已核实 `delivery-service-smzk`：**无生命周期规则、无 bucket policy、未开版本控制**，不存在「过几天被自动清掉」的隐患。

**为什么不绑自定义域名**：
- OSS 默认域名 `*.aliyuncs.com` 走 HTTPS **不要求 ICP 备案**（bucket 在中国内地时，只有绑自定义域名才要求备案），
  证书由阿里云签发与续期 → 本轮**零备案、零证书运维**。
- 代价：默认域名会带 `Content-Disposition: attachment` 与 `x-oss-force-download: true`，对 OkHttp 无影响（见 §6）；
  浏览器直接打开会变成下载而不是预览 —— 对模型包正是期望行为。
- 将来若要上 CDN，需要自定义加速域名 CNAME 到 OSS，那时才回到备案问题（可复用 `agentai0.com` 的备案主体）。

### 5.2 备用源与回滚

| 用途 | 源 | 说明 |
|---|---|---|
| 主（默认值） | 上表的 OSS URL | `check_model_host.py` 无参数探测的就是它 |
| 兜底 / 对照 | `https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-paraformer-zh-small-2024-03-09.tar.bz2` | 内容权威、sha256 可核对；国内链路不稳，只做应急与取证 |
| 兼容期保留 | `http://cvm.agentai0.com/funasr/…` | 域名仍被备案拦截，但 `network_security_config.xml` 的明文放行条目**保留**，旧包切回时不必重编 |

回滚（≤10 分钟）：`./gradlew assembleRelease -PasrModelUrl=<备用源>` 重新出包分发；**OSS 对象不删**。
对象无版本控制 → 误覆盖/误删只能从官方 Release 重下、校验 sha256 后重传（步骤见 §7 恢复小节）。

### 5.3 依赖与已知风险

| 风险 | 影响 | 现有缓解 |
|---|---|---|
| bucket 属**另一个业务**（`delivery-service-smzk` 是交付服务的公共读桶），对方调整 ACL / 清理对象不受本组控制 | 默认源 403 / 404，新包装不上模型 | 30 分钟一次探测（§2.2）；对象清单可在 `ossutil ls oss://delivery-service-smzk/models/` 核对；恢复见 §7.1 |
| 对象**无版本控制**，覆盖/删除不可逆 | 只能重传 | §7.1 恢复步骤；替换前先在本地留档 + sha256。**当前这份包未额外留档**，恢复依赖官方 Release（sha256 可核，实测下载 5.4s）—— 记为已接受风险 |
| 阿里云 OSS 默认域名无 SLA 承诺的加速能力（未上 CDN） | 弱网用户体验差于 CDN | 探测记录吞吐；P3 阈值 500KB/s（§2.3）触发时评估 CDN |
| 默认域名不支持自定义缓存策略 | 每次下载都回源 OSS | 本轮量级可接受（§7）；量大时再上 CDN + 自定义域名（需备案） |

## 6. OSS 行为差异（客户端与探测脚本必须知道的点）

2026-11-09 在真实对象上实测（命令与输出见 VOCA-35 评论）：

| 行为 | 实测结果 | 对客户端的影响 |
|---|---|---|
| `Range: bytes=<总长>-`（`.part` 恰好等于整包） | **200 整包**，不是 416 | VOCA-34 的 `planForResponse` 走「200 + `Content-Length == EXPECTED_BYTES` → 从 0 重写」，**正确** |
| 越界 `Range: bytes=99999999-` | 同样 **200 整包**，不是 416 | 同上，落在 `WRITE_FROM_START` 分支 |
| `Range: bytes=16780-`（正常续传） | `206` + `Content-Range: bytes 16780-77920047/77920048` | 续传正常 |
| `Accept-Ranges` | `bytes` | — |
| `Content-Disposition: attachment` + `x-oss-force-download: true` | 默认域名对二进制对象自动加 | OkHttp 不解析这两个头；字节流不受影响（已整包下载校验 sha256 一致） |
| 匿名 `List`（列目录） | `403` | 符合预期：不能枚举 bucket |
| `GET` 不存在的 key | `404` + `application/xml`（OSS 错误体） | 探测脚本的 `Content-Type` 断言会判红，不会把错误 XML 当模型 |

**结论：OSS 不会产生 416** —— VOCA-34 的 416 自愈分支在这个源上是死代码，**但保留是对的**（切回自建 nginx 源仍需要）。
排查时不要因为「没看到 416」就怀疑自愈逻辑没生效。

## 7. 容量与成本（OSS 默认域名，2026-11-09 实测）

- 单次下载 **77,920,048 B ≈ 74.3 MiB**；首装/清数据后各触发一次，之后走**完成标记**（`files/funasr/model/.ready-b77920048-sda92b3db5218`，由 `AsrModelInstall` 在校验通过后最后写）不再下载。
- 标记缺失而本地整包（`files/funasr/model.tar.bz2`）仍在且大小 / 摘要判得过 ⇒ **只重新解压，零流量**（VOCA-496 自愈）；整包缺失或摘要不符才重下，所以真实下载次数的上界是「首装 + 清数据 + 包体损坏」。
- 吞吐（本机 → cn-guangzhou，单连接）：整包 74.3 MiB **3.2s ≈ 23.5 MB/s**（探测脚本 `--sha256` 实测）；
  项目组另一轮单连接实测 **7.6 MB/s**、ttfb 0.10s。**容量规划取保守值 7.6 MB/s**：
  74MB 一个包约 **10 秒/连接**，瓶颈通常在客户端带宽而不是 OSS。
- OSS 侧无需预置带宽（按量计费）；对象 Standard（LRS），无生命周期规则、无版本控制。
- **流量费用量级**（外网流出，公开价约 **忙时 0.5 元/GB、闲时 0.25 元/GB**，折扣与最终金额以账单为准）：
  - 1,000 次下载 = 74.3 GiB ≈ **79.8 GB** → 忙时约 **¥40**，闲时约 **¥20**；
  - 10,000 次 ≈ **¥200–400**；
  - 若并发集中在忙时，按忙时单价估算。
- 存储与请求费：74.3MB Standard LRS ≈ ¥0.12/GB/月 → **≈ ¥0.01/月**；千次 GET 也在分级内，可忽略。
- **成本上限建议**：月外网流出预算先按 **¥100（≈ 2,500 次下载/月）** 定，用到 70% 时在阿里云控制台加流量告警；
  超过上限的处置是「切 CDN + 压缩分片」或「按需限流」，不是直接停服。
- 对比自建单机（方案 A/B）：量小时 OSS 更贵但不需要带宽预留；量大时自建更省但要考虑与 `api` 业务抢带宽。
  **本轮未启用 CDN**，默认域名直连 OSS。

### 7.1 OSS 对象误删 / 误覆盖的恢复

1. 从官方 Release 重新下载（地址见 §5.2），`shasum -a 256` 必须等于 `da92b3db…c6b1450`；
2. `ossutil cp <file> oss://delivery-service-smzk/models/asr/… --content-type application/octet-stream --region cn-guangzhou -e oss-cn-guangzhou.aliyuncs.com`；
3. `python3 tools/verify/check_model_host.py --sha256` 全绿后再结束事件。
   对象未开版本控制 → **没有「一键还原」**，恢复时间取决于下载速度（官方源实测 5.4s，可按 5–15 分钟计）。

## 8. 发布与回滚检查表（换源类变更）

- [ ] 新源 `check_model_host.py <url> --sha256` 全绿（含 sha256）；默认源 `check_model_host.py`（无参数）也全绿
- [ ] 与 `FunAsrModelStore.EXPECTED_SHA256`（VOCA-34 交付）一致；两处常量必须相同
- [ ] 若为 `http://`：`network_security_config.xml` 已放行该域名，且 `check_static.py`（第 6 项）/ `check_crosscut.py` 全绿
- [ ] 若为 OSS：`curl -I` 的 `Content-Length` 与 `ossutil stat` 一致；`Range: bytes=16780-` 回 206（§6）
- [ ] 真机走通「下载 100% → 解压 → 识别一句话」
- [ ] 旧源保留 ≥ 30 天：OSS 对象不删、`cvm` 明文放行条目不删（回滚只需换回 URL）
- [ ] 本次实测数据已回填 §1 表格，并把「谁 / 何时 / 旧 URL → 新 URL」写进 issue 评论

## 9. 责任与升级

| 事项 | Owner |
|---|---|
| 探测脚本、告警规则、Runbook 维护 | SRE |
| 换源方案决策、费用/账号审批 | qiaozhou（人类） |
| **OSS 对象生命周期**（上传/替换/删除、内容校验） | SRE（每次替换前后跑探测；不得覆盖未留档的对象） |
| **OSS bucket 归属与权限**（`delivery-service-smzk` 属交付业务桶） | 云账号管理员（人类）；SRE 只在该桶加 `models/asr/` 前缀，不改 ACL |
| 服务器 / 域名 / 证书操作（自建源、备案） | 持有运维权限者（人类确认后执行） |
| 客户端下载器校验与自愈 | 后端（VOCA-34） |
| 远端配置下发（若要做「不发版换源」） | 架构师（需新 issue） |

## 10. 第二条模型源：端侧 Vosk（`VOSK_MODEL_URL`）

`main` 上当前**唯一在跑**的下载链路是 `VoskModelStore`（`IspeechImeService` 首次按麦克风时触发），
它的源与上面的 FunASR 包是两个独立配置项，换源/校验互不影响：

| 项 | 值 |
|---|---|
| 配置项 | `app/build.gradle.kts` 的 `voskModelUrl` → `BuildConfig.VOSK_MODEL_URL`（`-PvoskModelUrl=https://…` 可覆盖，机制同 §4） |
| 默认源 | `https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip`（Vosk 官方，境外单源） |
| 实测元数据 | 2026-11-18 首测、**2026-11-23 复测一致**：`200` / `application/zip` / `Content-Length 43898754` / `Accept-Ranges: bytes`；整包 sha256 `3af8b0e7e0f835ae9d414ce5df580237a3cfb08d586c9fbbb0f7ff29ad5b14ba`（`unzip -t` 无错误后固定） |
| Range 行为（与 §6 相反） | 2026-11-23 复测 `Range: bytes=1048576-` → `206` + `Content-Length 42850178`；`Range: bytes=43898754-`（断点恰好在整包处）→ **`416` + `text/html`**(213 B) —— 该源**会**回 416，`.part` 走 `RETRY_FROM_START` 自愈（整包重下一次，不会循环）；OSS 同情形回 200，两侧都在 `AsrDownloadPolicy` 里有对应用例 |
| 客户端校验 | 与 FunASR 共用 `AsrDownloadPolicy` / `AsrModelDownloader`（`.part` + `Range` + 类型 / 长度 / 摘要），解压带 zip-slip 防护 —— 见 `VoskModelStoreTest` |
| 权威值落点 | `VoskModelStore.EXPECTED_BYTES` / `EXPECTED_SHA256`（改源必须同时改这两处与 `VoskModelStoreTest` 的断言） |

> **镜像约束（2026-11-23 补）**：`EXPECTED_BYTES` / `EXPECTED_SHA256` 是写死的常量，`-PvoskModelUrl` 只换地址。
> 镜像到自有 OSS 时包体必须**逐字节同源**（同一次下载的产物直接上传、禁止重新打包），否则字节数或摘要
> 必然变化，客户端 3 次全部失败。要「同源换包」得把包长 / 摘要也做成配置项，属新 issue。

> 未做（属外部资源变更，需人类拍板后由 SRE 执行）：把该 zip 镜像到 `delivery-service-smzk`
> 以摆脱境外单源；`check_model_host.py` 目前只探测 FunASR 的 `asrModelUrl`，不覆盖本条。

## 11. AVD 实测验证记录（R-1 / R-2 / R-4 / R-5）

> VOCA-476 的取证留痕。**本节全部为 AVD（`emulator-5554`）实测，不是真机**：可作为下载 / 自愈
> 链路的证据，但不能替代 §3 第 3 步要求的真机验收。
>
> **真机项待执行（未做，不得据此判定可发布）**：
> - **R-3 语音上屏**：需真机麦克风（序列号后 4 位 `2917`），AVD 无音频输入；
> - §8 检查表第 5 项「真机走通下载 100% → 解压 → 识别一句话」**仍未达成**。
>
> 收口口径与到期默认见 **§11.3**。

### 2026-11-24/25 · `main d9690f9` 本树重建 · AVD 覆盖（R-1 / R-2 / R-4 / R-5）

> **为什么不是 dist 里的 v1.6 包**：`dist/ispeech-internal-v1.6.apk` 经独立复核，dex 内无
> `AsrModelInstall` / `VoskModelStore` / `AsrModelDownloader`，构建窗口落在 11-09~11-14，
> **不含本单要验的 VOCA-496（零流量自愈）与 VOCA-474（下载加固）**。在它上面跑只会验到旧实现，
> 故本轮改为按 `main` 重新出包。旧包那轮读数降级为 §11.1「旧实现基线」。

| 项 | 值 |
|---|---|
| 源码基线 | `main` @ `d9690f9c7d22141013b904643b83b584c0381799` |
| 构建 | `./gradlew assembleRelease -x lintVitalAnalyzeRelease -x lintVitalRelease`（lint 需联网拉 kotlin-compiler，本环境超时故跳过，不影响产物） |
| 包 sha256 | `15541ef73db88d9bab21ec869703a278b70846d0af8ca2162b0b0187ca8c1eb4`（139,229,278 B） |
| 三方一致 | 本地构建产物 / 设备内 `base.apk` / `adb pull` 后主机侧，**三处 sha256 完全相同** |
| 应用版本 | `versionName=1.5` / `versionCode=5`（`main` 的 `build.gradle.kts` 未随功能前进，仅版本标签）/ uid `10196` |
| dex 自证 | `AsrModelInstall` 11 / `AsrModelDownloader` 5 / `funasr category=` 1 / `期望完成标记` 1 / `PREPARE_FAILED` 1 / `VoskModelStore` 9 / `AsrDownloadPolicy` 6 ⇒ **本单要验的代码确在包内** |
| 设备 | AVD `ispeech_test` / `emulator-5554` / `sdk_gphone64_arm64` / Android 14 |

| 用例 | 结果 | 本轮实测证据 |
|---|---|---|
| **R-1** 全新状态（`pm clear`/`rm -rf` 后 `files/funasr` 不存在）触发 | 行为通过、**字面判据不满足** | 第 5 轮实测（`FUNASR` 引擎）：日志 `funasr category=ARCHIVE_MISSING …（预期 77920048 字节）`；`files/funasr/model` 落盘 **8 条目**（含就绪哨兵 `.ready-b77920048-sda92b3db5218`、`model.int8.onnx` 81,828,675 B），完整 `ls -laR` 见 `R1-lsR-full.txt`；`netstats` 本段 **净增 80,777,337 B ≈ 1.037× 整包**（45,567,856 → 126,345,193） |
| **R-2** 预置坏 `.part`（**33 B** HTML 假包）后重触发 | ✅ 自愈成功，**未卸载重装** | `断点续传：从 0KB 继续，共 76093KB` → `下载失败（第 1/3 次）: DIGEST_MISMATCH: sha256=664f15e169c9…，预期 da92b3db5218…` → 删 `.part` 重下 → `model` 重建 80 MB；`netstats` Δ=**161,538,786 B ≈ 154.06 MB（2.073× 整包）** |
| **R-5** 断网（飞行模式，`NET_FAIL`）触发 | ✅ 失败可读、不留脏 `.part` | `下载失败（第 1/3 次）: timeout` → `Unable to resolve host "delivery-service-smzk…"` ×2 → `funasr category=PREPARE_FAILED … 期望完成标记=.ready-b77920048-sda92b3db5218`；随后 `files/funasr` 目录为空 |

**日志格式与源码逐字符对账**（这是上一轮被判 FAIL 的要害）：本轮 `PREPARE_FAILED` 实测行

- 有 `funasr category=` 前缀 ✅
- 有 ` 期望完成标记=` 后缀 ✅
- 「失败」后字符为 `0xff1a`（**全角冒号**）✅

三项均与 `AsrModelInstall.render` / `ensureModel` 的模板一致。旧包三项全无 —— 证明当时「格式对不上」
是**包的问题**，不是誊写。

**R-1 判据口径（待人类裁决，不由交付方改写）**：本单原文写「logcat 有 `AsrDownload` 起始终止打点」，
但全仓 `const val TAG` 中不存在 `AsrDownload` 这个 tag（只有 `FunAsrModelStore` / `VoskModelStore`），
所以该字面判据在任何构建上都不可满足。本轮实际取到的等价证据是：

| 原文要求的语义 | 本轮实测对应项 |
|---|---|
| 「起始」打点 | `funasr category=ARCHIVE_MISSING …（预期 77920048 字节）` |
| 「终止」打点 | 下载完成后 `files/funasr/model` 80 MB 落盘 + `netstats` Δ≈77.04 MiB |
| 摘要一致 | `DIGEST_MISMATCH` 用例证明 sha256 校验真实生效（`664f15e1…` vs `da92b3db…`） |

⇒ 语义已覆盖，**字面 tag 名不成立**。是把判据改成按 `FunAsrModelStore` 取，还是要求后端新增
`AsrDownload` tag，需人类/后端定；**在裁决前本表 R-1 记「行为通过、字面判据不满足」**。

**R-2 的 Δ 为何是 2.073×（按代码归因，两次传输都属 R-2 自身）**

坏 `.part` 只有 33 B，续传请求是 `Range: bytes=33-`，但响应的 33 字节内容其实是 HTML 拦截页，
摘要校验必然失败（`DIGEST_MISMATCH`）→ 触发 `RETRY_FROM_START` 整包重下。所以 R-2 一次用例里
有**两次完整量级的传输**：

| 段 | 字节 | 说明 |
|---|---|---|
| ① 续传残段 | 77,920,015 | `77,920,048 − 33`，服务端回剩余部分 |
| ② 整包重下 | 77,920,048 | 摘要失配后 `RETRY_FROM_START` |
| 合计 payload | 155,840,063（148.62 MiB） | |
| 实测 Δ | 161,538,786（154.06 MiB） | |
| 差额 | 5,698,723（**+3.66%**） | TCP/IP + TLS/HTTP 头 + 重传 |

⇒ **不是来源不明的第二次传输**：`161,538,786` 就是「续传一遍 + 整包一遍」的实测成本，
`MAX_ATTEMPTS=3` 的失败重试在本例中未触发（日志只出现「第 1/3 次」即成功）。因此该数字
**可以作为「坏 `.part` 自愈的实际下行成本」结论**：约为单包的 2.07 倍。

**下行字节口径与留档实况（按证据包逐项核对）**：统一用 `dumpsys netstats --uid 10196`
在触发前后各取一次 `rb` 原始读数，差值即该用例的实际下行。

| 用例 | 留档文件 | 状态 |
|---|---|---|
| **R-1**（第 5 轮） | `R1-netstats-before.txt`(261 B, `rb=45,567,856`) / `R1-netstats-after.txt`(`rb=126,345,193`) | **两份均非空，随 round5 包提交**；净增 80,777,337 B |
| **R-4**（第 5 轮） | `R4-netstats-before.txt` / `R4-netstats-after.txt` | 两份均非空，随 round5 包提交；`rb` 45,567,856 |
| **R-2**（第 3 轮） | `R2-netstats-before.txt`(`rb=80,777,445`) / `R2-netstats-after.txt`(`rb=242,316,231`)，随 `voca476-round3-evidence.tar.gz` 提交 | **不在 round5 包内**——R-2 第 5 轮未重跑；Δ=161,538,786 B 已按该两文件复算核对一致 |

> 更正说明：本段曾写「`R1-netstats-before.txt` 本轮为空」与「R2 netstats 已随包提交」，
> 与 round5 包实际内容不符（前者 261 B 非空、后者根本不在该包内），已按上表订正。

### 11.1 旧实现基线（`dist/ispeech-internal-v1.6.apk`，11-09~11-14 构建）——**不构成本单验收证据**

该包 `sha256 9cddcec5…`（98,416,118 B），dex 内 `AsrModelInstall` / `PREPARE_FAILED` /
`funasr category=` / `期望完成标记` **均为 0 命中**，而 `断点续传` 1、`FunAsrModelStore` 18、
`AsrDownloadPolicy` 6 ⇒ 它接的是 VOCA-34 时期的旧实现。

同一批用例在它上面的读数（保留作对照，**不得当作「修复后行为」引用**）：

| 用例 | 旧包读数 |
|---|---|
| R-1 | `model` 80 MB / 8 文件，无 `.part`；日志无 `funasr category=` |
| R-2 | `DIGEST_MISMATCH: sha256=664f15e1…`；`netstats` Δ=161,538,010 B |
| R-5 | `FunASR 模型准备失败: Unable to resolve host…`（**无前缀、无后缀、半角冒号**） |

**未解决（转 VOCA-186 线，不在本单闭合）**：谁用哪棵树构建了 `dist/ispeech-internal-v1.6.apk`、
为何与 `release/voca-186-v1.6` 源码不一致。`origin/agent/sre/voca-186-rebuild` 仍把它登记为 v1.6 出包。

### 2026-11-24 · `main d9690f9` 同一被测包 · AVD 覆盖（R-4）

> 上一版此处记的是 `bb1bf2b` 构建的 `ba37b578…`，但该 apk **未留原件、任何分支与证据包里都不存在**，
> 第三方无法复算（第 4 次质检打回项 4）。本轮改为在**上面那张同一被测包**上重跑，原件可复算。

| 项 | 值 |
|---|---|
| 源码基线 | `main` @ `d9690f9c7d22141013b904643b83b584c0381799`（同 §11 首节） |
| 被测包 | **同一棵 `d9690f9` 的另一次构建**（非 §11 首节那个产物）。第 5 轮重建 sha256 `41f0659435fad741fb21cd0a1a6339ae161e9420382b42a2900857dc983e079a`（139,229,278 B）；第 4 轮为 `f02f3eeb…`、第 3 轮为 `15541ef7…` |
| **为何不登记进 `dist/SHA256SUMS`** | 该 apk **139,229,278 B > GitHub 单文件 100 MB 硬限**，未跟踪时仓库自带门禁 `tools/verify/check_dist_manifest.py` 直接判 **FAIL**（原话「先进不了仓库，清单记了就是假答案」）。故不写入清单，改用下方 dex 口径复算 |
| **可复算性口径（dex）** | `classes.dex` sha256 `199ffc59ff365d48abbc101915992f971ca62c86a28a6a2939118ca08ccc611d`(30,886,992 B) / `classes2.dex` `630bc14d8bde5d84a6fae615eb401f65e9ba52c2521f7651df3c61fb110e28ad`(7,906,032 B) / `classes3.dex` `d276f6188ec900ab6f0ac168d3415317776acd28be8a51fe205fdd0a60f432d1`(8,420,872 B)。按 `d9690f9` 重跑同一命令后比这三个 sha，即知是否同一份被测代码 |
| **可复算性说明** | 同一棵树三次 `assembleRelease` 得到**三个不同 sha256**（`15541ef7…` / `f02f3eeb…` / `41f0659…`），但每次 **`classes*.dex` 逐字节相同**（47,213,896 B，符号计数 11/5/1/1/1/9/6 全同）⇒ APK 整体**不可**字节级复现（签名/zip 元数据带时间），**dex 可复现**。复核口径：`d9690f9` 重跑同一命令后比 dex 计数，不要追 apk sha |
| 包内取证 | `VoskModelStore` 9 / `AsrModelDownloader` 5 / `AsrModelInstall` 11 / `AsrDownloadPolicy` 6 ⇒ 加固代码确在包内 |
| 设备 | AVD `emulator-5554` / Android 14 / `sdk_gphone64_arm64` / uid `10196`；`versionName=1.5`（`main` 的 `build.gradle.kts` 未前进，仅版本标签） |
| 引擎 | `asr_engine=VOSK`（写入 `files/datastore/ispeech_settings.preferences_pb`，因 ASR 设置页在 UI 上无入口） |

| 用例 | 结果 | 关键证据 |
|---|---|---|
| **R-4** 切到 VOSK 引擎，走 `VoskModelStore` 下载 | ✅ 通过 | 第 5 轮实测：触发 16:03:57 → `model.zip.part` 出现（2,277,123 B@20s）→ 16:04:55 落成 `model`，**耗时 ≈58 s**；完整 `ls -laR` 见 `R4-vosk-lsR-full.txt`（54 行、14 个常规文件、**逐项求和 68,292,271 B**，与 `du -sb` 68,320,943 B 一致）；含 `model/am/final.mdl`(15,901,896)、`model/graph/Gr.fst`(26,082,259)、`graph/phones/word_boundary.int`(7,161)、`model/ivector/`（`final.dubm`/`final.ie`/`final.mat`/`global_cmvn.stats`/`online_cmvn.conf`）、`model/conf/model.conf`(289 B，就绪哨兵)；**无 `model.zip`、无 `.part` 残留**。`netstats` 本 bucket `rb` 45,567,856 ≈ 1.038× `EXPECTED_BYTES`(43,898,754) |

**R-4 的判据已知差异**：§10 记载该源在「断点恰在整包长度处」会回 `416` + `text/html`，走
`RETRY_FROM_START` 自愈。本轮是全新下载（无历史 `.part`），未复现该分支；该分支由
`VoskModelStoreTest` 单测覆盖，不在本轮 AVD 证据内。

**未覆盖（如实声明）**：
- **R-3 语音上屏**：需真机麦克风（AVD 无音频输入），本轮未跑，**不得据此判定语音链路可用**。
- **R-5 的 UI 文案**：仅取到 logcat 与文件系统证据，候选条提示文案未截图取证。
- **VOSK 下载耗时**：第 5 轮实测 **≈58 s**（43.9 MB，境外单源；从触发到 `model` 落成）。早前版本写的「约 6 分钟」属 `bb1bf2b` 那轮，**不是本轮**，已更正。未做带宽归因。

## 变更记录

| 日期 | 变更 | 证据 |
|---|---|---|
| 2026-11-09 | 新建；实测 `cvm` 被备案拦截、官方源可用、探测脚本上线 | 见 §1 与 issue VOCA-35 评论 |
| 2026-11-09 | **默认源切到阿里云 OSS**；§1 复测 `cvm`（302 → dnspod 拦截页后 502 / 166 B）；新增 §5.1 落地配置、§6 OSS 行为差异、§7 成本与恢复、§8 检查表 OSS 项 | `check_model_host.py --sha256` 9/9 全绿、无参数探测 7/7 全绿、`check_static` 6/6、`check_crosscut` 138/138；见 VOCA-35 评论 |
| 2026-11-18 | 新增 §10：Vosk 活链路（`voskModelUrl`）成为第二个模型源，客户端下载器与 FunASR 合一 | VOCA-474：`./gradlew :app:testDebugUnitTest --tests "com.ispeech.data.*"` 69/69（AsrModelInstall 19 + FunAsr 16 + PhraseCsvImport 13 + Vosk 21）、`check_crosscut` 183/183、`check_static` 6/6（2026-11-23 复跑） |
| 2026-11-24 | 新增 §11 实测验证记录：v1.6 在 AVD 跑通 R-1/R-2/R-5；main `bb1bf2b`（PR #31）在 AVD 跑通 **R-4**（VOSK 43.9 MB 下载 → 65 MB `model` 目录）。R-3 需真机麦克风，仍未覆盖 | 见 §11 与 VOCA-476 评论 |
| 2026-11-24 | **§11 换包重记**：原 `dist/ispeech-internal-v1.6.apk` 经复核 dex 无 `AsrModelInstall`/`VoskModelStore`（11-09~11-14 旧构建），降级为 §11.1 基线；改用 `main d9690f9` 重建包（`15541ef7…`，三方 sha 一致）重跑 R-1/R-2/R-5，日志出现 `funasr category=` 成对打点、全角冒号与 `期望完成标记=` 后缀，与源码一致 | 见 §11 与 VOCA-476 评论 |
| 2026-11-25 | 新增 §11.3 收口口径与 §11.2 交付状态：PR #45（`99a10e5`，open/clean）；明确 R-3 为**唯一人类门**并给两条走法（真机窗口 / 书面豁免）与 11-27 12:00Z 到期默认（降范围，R-3 移交 VOCA-33 人类门） | 见 §11.2/§11.3 与 VOCA-476 评论 |
### 11.2 交付与合并状态

- **PR：[#45](https://github.com/forcoder/ispeech/pull/45)**，base `main`、head `agent/voca-476-r4` @ `99a10e5`，
  `state=open` / `mergeable_state=clean` / `changed_files=1`（仅本文件）。由督导员于 2026-11-24 16:30Z 代建
  （本 test-engineer 运行时无 gh 凭据，`~/.config/gh` 与 keychain 均无，匿名 `POST /pulls` 401）。
- **合并窗口只取 `agent/voca-476-r4`**。`origin/agent/voca-476`（`7c58832`）作废：它新增的是
  `## 10. 实测验证记录`，与本文件 §10（Vosk 源）编号冲突；两分支同时合入 `git merge-tree` rc=1。
- **合入 `main` 属生产写入**，按项目规则 §二/§五 由人类在统一合并窗口执行，本单不自行合并。

### 11.3 收口口径（2026-11-25 定，督导员 13:20Z 派）

本单**除 R-3 外全部按 AVD 收口**，证据见 §11 首节与 §11.1 基线对照：

| 项 | 状态 | 依据 |
|---|---|---|
| R-1 下载 + 产品落盘 | 行为通过（字面判据见下） | `funasr category=ARCHIVE_MISSING`、`model` 8 条目含就绪哨兵、`netstats` 净增 80,777,337 B |
| R-2 坏 `.part` 自愈 | 通过 | `DIGEST_MISMATCH` → `RETRY_FROM_START` 整包重下；Δ=161,538,786 B（=续传 77,920,015 + 整包 77,920,048，+3.66% 协议开销） |
| R-4 VOSK 下载 | 通过 | 65 MB `model`、`conf/model.conf` 就绪哨兵、`ls -laR` 54 行原件 |
| R-5 断网失败可读 | 通过 | `funasr category=PREPARE_FAILED … 期望完成标记=…`，无脏 `.part` |
| **R-3 语音上屏** | **未做 —— 唯一人类门** | AVD 无音频输入 |

**R-1 字面判据**：原文要求 logcat 有 `AsrDownload` 起始终止打点，但全仓 `const val TAG` 中**不存在**
该 tag（只有 `FunAsrModelStore` / `VoskModelStore`），任何构建都无法满足。本单按等价语义取证并如实
标注「行为通过、字面判据不满足」，**未自行改写判据**。

**唯一的未闭环项：R-3**。需人类提供以下任一：
1. **真机窗口**：序列号后 4 位 `2917` 那台接入 runtime（或由持有者在真机上点一次麦克风说一句话，录屏 + logcat）；
2. **书面豁免**：明确接受「R-3 留作残余风险」，本单按 AVD 收口。

**到期默认（11-27 12:00Z）**：无真机窗口即按第 2 项降范围——R-3 单列移交 VOCA-33 的人类门，
本单以 AVD 证据收口，PR #45 由人类在统一合并窗口合入。
