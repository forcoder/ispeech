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

> **2026-11-18 复核（VOCA-475，探测机出口经 `ipinfo.io` 判为 Guangzhou / Guangdong / CN，境内）**：默认源 `curl -sI` 仍是
> `200 / application/octet-stream / Content-Length 77920048 / Accept-Ranges: bytes`，
> `check_model_host.py --sha256` 9/9 全绿（整包 2.9s / 26.6 MB/s），`--samples 3` 3/3 可用 exit 0；
> 旧源 `cvm.agentai0.com` 3 次采样仍**全部** 302 → dnspod 拦截页，exit 1 —— 故障形态未变，不作恢复候选。

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

# 不联网验证判定逻辑本身（本地伪造 6 类故障 + 5 条多数采样用例）
python3 tools/verify/check_model_host.py --self-test

# 多次采样取多数——定时任务必须带这个参数，理由见下方「采样纪律」
python3 tools/verify/check_model_host.py --samples 3 --sample-interval 5
```

退出码：`0` 全绿；`1` 任一断言失败（含 DNS/TCP/TLS 失败）。可直接接 CI / cron。

**采样纪律（2026-11-18 定死，VOCA-475）**：判断源可用性**必须多次采样取多数**，不能拿单次结果
下结论。旧默认源 `cvm.agentai0.com` 在被拦截期间有 **10~17% 请求返回 502**（migration log §2 的
复测记录）—— 单次采样会把「抖一下」读成「源挂了」，也会把「运气好的一跳」读成「源恢复了」。
规则：`--samples N` 取**严格多数**（`ok*2 > N` 才判可用），平局（偶数样本）判不可用 ——
宁可多一条告警，不漏一次真故障。出现 `2/3` 这类结果时脚本额外打 `WARN: 采样结果不一致`，
那是**降级（P3）**信号，不是「已经恢复」。

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
| 定时探测（**未接 · 卡在人类答复**） | 每 30 分钟（目标） | 脚本可用且已在境内出口实跑（见下方「首次实跑记录」）；**没有调度器**。问过的记录：2026-11-09 提出 → 未答；2026-11-18 在 VOCA-33 / VOCA-475 再问 → 限期 2026-11-20。答复「不接」就在本行改写为「决定不接 · 决定人 · 日期 · 理由」，不接受继续挂着 |
| 用户侧信号 | 持续 | logcat `FunAsrModelStore` 出现 `HTTP_4xx` / `NOT_MODEL_PAYLOAD`；源站日志 404 突增；`R.string.voice_model_failed` 相关反馈 |

**接法候选（执行环境需人类确认后再接）**：

```bash
# ① 境内常驻机器 crontab —— 建议项。改动最小，探测链路即国内用户链路。
#    注意 crontab 里 `%` 必须写成 `\%`（否则被解析成换行）；用 `;` 而非 `&&` 串接，
#    这样 `cd` 失败时 python 也会失败并触发告警，而不是静默跳过。
*/30 * * * * cd ~/workspace/ispeech; { date '+\%F \%T \%z'; python3 tools/verify/check_model_host.py --samples 3 --sample-interval 5 --quiet; } >> ~/logs/model-host.log 2>&1; RC=$?; [ $RC -eq 0 ] || curl -s "$ALERT_WEBHOOK" -d '{"text":"ASR 模型源不可用（3 次采样多数判红）"}'
```

② 阿里云函数计算定时触发：境内 region、常驻、按量成本近零（一次探测只有 3 个请求头 + 16 字节），
不依赖任何一台机器开着。需要人类确认是否允许为此开函数 —— 属新付费资源，未确认前不接。

③ ~~GitHub Actions `on: schedule`~~ —— **已排除，不再作为候选**（原片段在此文件里保留过，2026-11-18 删除）：
runner 在境外，探测结论不代表国内用户链路；VOCA-33 那次「境外看着正常、境内被备案拦截」的误判就是这么来的。
它同时依赖第三方 CI 与境外出口，作为可用性告警源是**假阴性发生器**。真要用 Actions，只适合做
「包内容完整性」对照（`--sha256` 对官方 Release），不承担链路可用性判定。

**监控自身的盲区**（接上调度器后必须一起处理）：cron 挂了不会有告警。判据是日志里
**超过 1 小时没有新行** —— 恢复探测前先确认探测器在跑，否则「一直没红」可能只是「一直没测」。

**首次实跑记录（2026-11-18 03:18~03:27 +0800，探测机出口经 `ipinfo.io` 判为 Guangzhou / CN —— 境内，不是境外链路）**：

| 目标 | 命令 | 结果 | 退出码 |
|---|---|---|---|
| 默认源（OSS） | `check_model_host.py`（单次，×3 轮） | `SUMMARY: passed=7 failed=0 warned=0` ×3 | `0` |
| 默认源（OSS） | `check_model_host.py --samples 3` | `MAJORITY: 3/3 次可用`、`passed=21 failed=0` | `0` |
| 默认源（OSS） | `check_model_host.py --sha256` | 整包 77,920,048 B / `da92b3db…c6b1450` 一致，`passed=9 failed=0` | `0` |
| 旧源 `cvm.agentai0.com` | `check_model_host.py <url> --samples 3` | `3/3` 均 `302 → dnspod webblock` 拦截页，`MAJORITY: 0/3` | `1` |
| 判定逻辑本身 | `check_model_host.py --self-test` | `cases=11 unexpected=0` | `0` |

绿红两路都跑通过，说明「接上就会响」；缺的只是那个调度器。

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
| `ModelHostProbeFailed` | **单次** `--samples 3` 多数判红（退出码 ≠ 0）即告警 | P1（无备源）/ P2（有备源） | on-call（qiaozhou）+ 本 issue 评论 | §3 |
| `ModelHostProbeFlaky` | 单次 `--samples 3` 出现 `MAJORITY: 2/3`（采样不一致） | P3 | 值班邮件 | §2.1 采样纪律 |
| `ModelHostProbeSilent` | 探测日志超过 1 小时没有新行（探测器本身挂了） | P2 | on-call | §2.2「监控自身的盲区」 |
| `ModelHostCertExpiring` | 证书剩余 < 14 天 | P2 | on-call | 仅适用自建源；OSS 默认域名的证书由阿里云签发/续期，**默认源不再需要本条** |
| `ModelHostSlow` | 首字节 > 5s 或 < 500KB/s 持续 10 分钟 | P3 | 值班邮件 | §7 |

> **原阈值「连续 2 次探测退出码 ≠ 0」已于 2026-11-18 收紧为「单次多数判红」**：去噪已经由
> `--samples 3` 在单次运行内做完，再叠一层「连续 2 次」会把发现时间推到 60 分钟，
> 而源挂了是分钟级用户可见故障（§2.3 给 P1 的首响只有 30 分钟）。
> 平局与全红都判红 —— 宁可多一条误报，不漏一次真故障。

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
| 异地留档（**不是下载源**） | `oss://baigong-code-backup/ispeech/asr-model/sherpa-onnx-paraformer-zh-small-2024-03-09.tar.bz2` | cn-shenzhen / IA / **private**，见 §5.4。私有桶 + 签名 URL 会过期，**不能填进 `-PasrModelUrl`**；它的用途是「OSS 主对象被删/被覆盖后不必依赖官方 Release 就能恢复」，恢复步骤见 §7.1 |
| 兼容期保留 | `http://cvm.agentai0.com/funasr/…` | 域名仍被备案拦截，但 `network_security_config.xml` 的明文放行条目**保留**，旧包切回时不必重编 |

回滚（≤10 分钟）：`./gradlew assembleRelease -PasrModelUrl=<备用源>` 重新出包分发；**OSS 对象不删**。
对象无版本控制 → 误覆盖/误删只能从异地留档（§5.4）或官方 Release 取包、校验 sha256 后重传（步骤见 §7.1）。

### 5.3 依赖与已知风险

| 风险 | 影响 | 现有缓解 |
|---|---|---|
| bucket 属**另一个业务**（`delivery-service-smzk` 是交付服务的公共读桶），对方调整 ACL / 清理对象不受本组控制 | 默认源 403 / 404，新包装不上模型 | 30 分钟一次探测（§2.2）；对象清单可在 `ossutil ls oss://delivery-service-smzk/models/` 核对；恢复见 §7.1 |
| 对象**无版本控制**，覆盖/删除不可逆 | 只能重传 | §7.1 恢复步骤；替换前先在本地留档 + sha256。**2026-11-18 起已有异地留档**（§5.4，另一 region 的私有桶，逐字节复验一致），恢复不再依赖官方 Release |
| 阿里云 OSS 默认域名无 SLA 承诺的加速能力（未上 CDN） | 弱网用户体验差于 CDN | 探测记录吞吐；P3 阈值 500KB/s（§2.3）触发时评估 CDN |
| 默认域名不支持自定义缓存策略 | 每次下载都回源 OSS | 本轮量级可接受（§7）；量大时再上 CDN + 自定义域名（需备案） |
| 定时探测仍未接（§2.2） | 源挂了仍只能靠用户投诉发现 | 脚本与 cron 片段已就绪并境内实跑过；卡在人类答复，限期 2026-11-20 |

### 5.4 异地留档（2026-11-18 新增，VOCA-475 残余风险 3 的闭合）

质检专员·项目经理 11-17 的残余风险 3 是「OSS 上那个 74 MB 对象只有它自己一份」。已落一份异地副本：

| 项 | 值 |
|---|---|
| 对象 | `oss://baigong-code-backup/ispeech/asr-model/sherpa-onnx-paraformer-zh-small-2024-03-09.tar.bz2` |
| Region | `oss-cn-shenzhen`（主源在 `oss-cn-guangzhou`）—— 跨 region，才算异地 |
| 桶 | `baigong-code-backup`：与主源**同一账号**的既有备份桶（2026-08-27 建，桶内已有仓库 bundle 备份），ACL `private`、StorageClass `IA`、ZRS；**未新建任何付费资源，未改任何 ACL / policy / 生命周期规则** |
| 上传时间 | 2026-11-18 03:19:29 +0800，`aliyun oss cp` 2.35s / 33 MB/s |
| 对象元数据 | `Content-Type: application/octet-stream`、`X-Oss-Meta-Sha256`、`X-Oss-Meta-Origin`、`X-Oss-Meta-Issue: VOCA-475` |
| 大小 | `77920048`（与主源逐字节一致） |
| ETag / Content-Md5 | `A60C3BF0709FA20DB40313028CC7DC53` / `pgw78HCfog20AxMCjMfcUw==` —— 与主源**完全相同** |
| CRC64 | `4639922393290880809` —— 与主源**完全相同**（服务端逐对象校验和，最强的一致性证据） |
| sha256 | `da92b3db5218c5be53aad53e57d1b6e63e7fc98a0e054fbdd6dbe18e9c6b1450` —— 从备份对象**重新整包下载**后本地计算，与权威值逐字符相同 |

复跑验证命令（签名 URL 含凭据，不要贴进日志或评论）：

```bash
aliyun oss stat oss://baigong-code-backup/ispeech/asr-model/sherpa-onnx-paraformer-zh-small-2024-03-09.tar.bz2 \
  -e oss-cn-shenzhen.aliyuncs.com --region cn-shenzhen

U=$(aliyun oss sign oss://baigong-code-backup/ispeech/asr-model/sherpa-onnx-paraformer-zh-small-2024-03-09.tar.bz2 \
     -e oss-cn-shenzhen.aliyuncs.com --region cn-shenzhen --timeout 600 | grep '^https://')
curl -s -o backup.tar.bz2 -w 'http_code=%{http_code} size=%{size_download}\n' "$U"   # 200 / 77920048
curl -s -H 'Range: bytes=16780-' -o /dev/null -w 'http_code=%{http_code}\n' "$U"     # 206（备份也可续传）
shasum -a 256 backup.tar.bz2                                                          # da92b3db…c6b1450
```

实测结果（2026-11-18 03:19~03:21 +0800，对象 `Last-Modified: 2026-11-18 03:19:29 +0800`）：`GET 200 / 77920048 B / 2.79s`、`Range 206` +
`Content-Range: bytes 16780-77920047/77920048`、`sha256` 与权威值一致。

成本与回滚：常态成本只有存储 74.3 MB IA ≈ **¥0.003/月**；本次建档 + 三次复验下载产生的一次性外网流量合计约 **¥0.2**。
回滚 = 删这一个对象（精确 key，不用 `-r`，桶内其余数据属别的业务）：

```bash
# 删除属不可逆操作：需人类确认后再执行，SRE 不单独承担
aliyun oss rm oss://baigong-code-backup/ispeech/asr-model/sherpa-onnx-paraformer-zh-small-2024-03-09.tar.bz2 \
  -e oss-cn-shenzhen.aliyuncs.com --region cn-shenzhen
```

**留档只有一份的现状已被解掉，但它不是自动的**：桶未开版本控制、未开跨区域复制（CRR），
主源将来被替换时需手工同步这一份 —— 已写进 §7.1 与 §8 检查表。

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
- 异地留档（§5.4）：常态成本只有 74.3MB IA ≈ **¥0.003/月**（取回时另有 IA 数据取回费，量级 ¥0.002/次）；
  一次性成本 = 建档 + 复验下载的外网流出合计 ≈ **¥0.2**。**留档不改变上面的流量结论**：
  它是私有对象、只用于恢复，用户下载不经过它。
- **成本上限建议**：月外网流出预算先按 **¥100（≈ 2,500 次下载/月）** 定，用到 70% 时在阿里云控制台加流量告警；
  超过上限的处置是「切 CDN + 压缩分片」或「按需限流」，不是直接停服。
- 对比自建单机（方案 A/B）：量小时 OSS 更贵但不需要带宽预留；量大时自建更省但要考虑与 `api` 业务抢带宽。
  **本轮未启用 CDN**，默认域名直连 OSS。

### 7.1 OSS 对象误删 / 误覆盖的恢复

**先取异地留档（§5.4，境内、不依赖官方 Release）**：

```bash
# 1) 拉回留档（约 3s，境内到境内）
aliyun oss cp oss://baigong-code-backup/ispeech/asr-model/sherpa-onnx-paraformer-zh-small-2024-03-09.tar.bz2 . \
  -e oss-cn-shenzhen.aliyuncs.com --region cn-shenzhen

# 2) 摘要必须等于 da92b3db…c6b1450；不符就是备份也坏了 → 立即转官方 Release 路线并升级 P1
shasum -a 256 sherpa-onnx-paraformer-zh-small-2024-03-09.tar.bz2

# 3) 传回主源桶
aliyun oss cp sherpa-onnx-paraformer-zh-small-2024-03-09.tar.bz2 oss://delivery-service-smzk/models/asr/ \
  --content-type application/octet-stream -e oss-cn-guangzhou.aliyuncs.com --region cn-guangzhou

# 4) 两条都全绿（exit 0）才算事件结束
python3 tools/verify/check_model_host.py --sha256
python3 tools/verify/check_model_host.py --samples 3
```

5. 事后把「谁 / 何时 / 用了哪份留档 / 探测输出」写进 issue 评论，并确认 §5.4 的留档仍是同一份内容。

留档不可用（被一起清掉、或内容不符）时的兜底：从官方 Release 重新下载（地址见 §5.2），
`shasum -a 256` 必须等于权威值，再走上面第 3–5 步。

两个桶都未开版本控制 → **没有「一键还原」**。恢复时长估算：境内到境内约 3s（2026-11-18 实测 33 MB/s），
按流程 + 复核取 **5–15 分钟**；走官方 Release 取决于境外链路，历史上从 5.4s 到超时都出现过。

## 8. 发布与回滚检查表（换源类变更）

- [ ] 新源 `check_model_host.py <url> --sha256` 全绿（含 sha256）；默认源 `check_model_host.py`（无参数）也全绿
- [ ] 与 `FunAsrModelStore.EXPECTED_SHA256`（VOCA-34 交付）一致；两处常量必须相同
- [ ] 若为 `http://`：`network_security_config.xml` 已放行该域名，且 `check_static.py`（第 6 项）/ `check_crosscut.py` 全绿
- [ ] 若为 OSS：`curl -I` 的 `Content-Length` 与 `ossutil stat` 一致；`Range: bytes=16780-` 回 206（§6）
- [ ] 真机走通「下载 100% → 解压 → 识别一句话」
- [ ] 旧源保留 ≥ 30 天：OSS 对象不删、`cvm` 明文放行条目不删（回滚只需换回 URL）
- [ ] **异地留档已同步到新一份**（§5.4）：包内容一旦变化，先改留档再切流量；不得出现「主源是新的、备份是旧的」
- [ ] 探测器在跑：接了调度器就看日志最后一行的时间戳是否在 1 小时内；没接就在评论里写明「本次变更期间无定时探测」
- [ ] 本次实测数据已回填 §1 表格，并把「谁 / 何时 / 旧 URL → 新 URL」写进 issue 评论

## 9. 责任与升级

| 事项 | Owner |
|---|---|
| 探测脚本、告警规则、Runbook 维护 | SRE |
| 换源方案决策、费用/账号审批 | qiaozhou（人类） |
| **OSS 对象生命周期**（上传/替换/删除、内容校验） | SRE（每次替换前后跑探测；**不得覆盖未留档的对象**，留档见 §5.4） |
| **异地留档副本**（`baigong-code-backup`，cn-shenzhen） | SRE（内容变更时同步；不删桶内其他备份数据，不改该桶 ACL / 生命周期） |
| **OSS bucket 归属与权限**（`delivery-service-smzk` 属交付业务桶） | 云账号管理员（人类）；SRE 只在该桶加 `models/asr/` 前缀，不改 ACL |
| 定时探测的**执行环境**（crontab / 函数计算） | qiaozhou（人类）批准落点 → SRE 接；未批准前探测不进任何调度器 |
| 服务器 / 域名 / 证书操作（自建源、备案） | 持有运维权限者（人类确认后执行） |
| 客户端下载器校验与自愈 | 后端（VOCA-34） |
| 远端配置下发（若要做「不发版换源」） | 架构师（需新 issue） |

## 变更记录

| 日期 | 变更 | 证据 |
|---|---|---|
| 2026-11-09 | 新建；实测 `cvm` 被备案拦截、官方源可用、探测脚本上线 | 见 §1 与 issue VOCA-35 评论 |
| 2026-11-09 | **默认源切到阿里云 OSS**；§1 复测 `cvm`（302 → dnspod 拦截页后 502 / 166 B）；新增 §5.1 落地配置、§6 OSS 行为差异、§7 成本与恢复、§8 检查表 OSS 项 | `check_model_host.py --sha256` 9/9 全绿、无参数探测 7/7 全绿、`check_static` 6/6、`check_crosscut` 138/138；见 VOCA-35 评论 |
| 2026-11-18 | VOCA-475 收口：§1 复核行；§2.1 采样纪律 + `--samples`；§2.2 「待接」改为「未接 · 卡在人类答复（限期 11-20）」并给出首次实跑记录、排除 GitHub Actions、补监控盲区；§2.4 阈值收紧为「单次多数判红」+ 新增 Flaky/Silent 两条告警；§5.2/§5.3/§5.4 异地留档落地与复验；§7.1 恢复改为先取留档；§8/§9 补留档与探测落点 | 默认源 `--samples 3` → 3/3 可用 exit 0、`--sha256` 9/9；旧源 3/3 拦截页 exit 1；`--self-test` 11 项 unexpected=0；备份对象 `GET 200/77920048`、`Range 206`、重下载后 sha256 与权威值一致、ETag/MD5/CRC64 与主源逐项相同 |