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
| 用户侧信号 | 持续 | logcat `FunAsrModelStore` 出现 `HTTP_4xx` / `NOT_MODEL_PAYLOAD`；源站日志 404 突增；`R.string.voice_model_failed` 相关反馈 |

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

- 单次下载 **77,920,048 B ≈ 74.3 MiB**；首装/清数据后各触发一次，之后走 `tokens.txt` 哨兵不再下载。
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

## 变更记录

| 日期 | 变更 | 证据 |
|---|---|---|
| 2026-11-09 | 新建；实测 `cvm` 被备案拦截、官方源可用、探测脚本上线 | 见 §1 与 issue VOCA-35 评论 |
| 2026-11-09 | **默认源切到阿里云 OSS**；§1 复测 `cvm`（302 → dnspod 拦截页后 502 / 166 B）；新增 §5.1 落地配置、§6 OSS 行为差异、§7 成本与恢复、§8 检查表 OSS 项 | `check_model_host.py --sha256` 9/9 全绿、无参数探测 7/7 全绿、`check_static` 6/6、`check_crosscut` 138/138；见 VOCA-35 评论 |