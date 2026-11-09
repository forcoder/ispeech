# 模型托管源 Runbook（ASR 离线模型）

适用于 `ASR_MODEL_URL`（`app/build.gradle.kts` 的 `asrModelUrl`）指向的
`sherpa-onnx-paraformer-zh-small-2024-03-09.tar.bz2`（77,920,048 字节）托管源。
判定工具：`tools/verify/check_model_host.py`（退出码 0 = 可用，非 0 = 不可用）。

> 一句话：**探测红 = 换源**。`cvm` 域名完全恢复（备案）只影响**已按当前源码出过包的设备**；
> `dist/ispeech-release-v1.5.apk` 里没有 FunASR 代码（VOCA-34 已记录该出包缺陷），
> 所以目前**没有已发布用户受影响**，受影响的是内部测试机与后续出包。
> 一旦有含 FunASR 的包发出去，旧 URL 就会变成必须恢复的兼容债务。

## 1. 现状（2026-11-09 实测）

| 目标 | 结果 | 判读 |
|---|---|---|
| `http://cvm.agentai0.com/funasr/…tar.bz2`（当前默认源） | `302 -> https://dnspod.qcloud.com/static/webblock.html?d=cvm.agentai0.com`，跟随后 `200 text/html 16,780 B` | **不可用**：腾讯云「未备案」拦截页 |
| `http://cvm.agentai0.com/` | 同上 302 → 拦截页 | 整个 Host 被拦，不是单文件问题 |
| `https://cvm.agentai0.com:443` | 连接被拒（该子域没有 TLS） | — |
| DNS `cvm.agentai0.com` | `43.136.44.72`（与远端构建机同一台） | — |
| `http://` 直连 `43.136.44.72` + `Host: cvm.agentai0.com` | 仍 302 → 拦截页 | 拦截在网络层按 Host 生效，**改 nginx 配置无效** |
| `http://43.136.44.72/` + `Host: <任意未备案域名>` | `307 /auth/login`（nginx/1.18.0，真实应用） | 机器本身正常，只是该 Host 未备案 |
| `http://api.agentai0.com/funasr/…tar.bz2` | `404`（nginx/1.24.0） | 域名正常，但没挂静态目录 → **可改造** |
| `https://api.agentai0.com/` | 证书 `CN=www.agentai0.com`，主机名不匹配 | 443 无 api 的证书 → 要用 HTTPS 需先签证书 |
| `https://www.agentai0.com/funasr/…tar.bz2` | `404`（站点正常，未挂模型） | 已有 Let's Encrypt 证书，**可改造** |
| `https://github.com/k2-fsa/sherpa-onnx/releases/…` | 首次实测：200 / `application/octet-stream` / 77,920,048 B / `BZh` / Range 206 / sha256 一致（整包 78MB 在 10s 内下完）；**同日稍后复测：本探测环境到 `github.com:443` TCP 超时（`release-assets` CDN 仍可达），脚本判红** | 官方源内容正确，但链路不稳定 —— 只能做兜底/对照，不宜做默认源 |

关键结论：

1. `cvm` 子域是**备案问题**，不是服务挂掉；机器和 nginx 都在，只是该 Host 被腾讯云前置拦截。
2. `api.agentai0.com`（`121.43.55.151`）与 `www.agentai0.com` 是另一台机器，域名正常、已备案，
   `api` 走明文 HTTP、`www` 有 Let's Encrypt 证书 —— 两者都具备托管静态文件的硬件条件。
3. 官方 GitHub Release **内容完全正确**（74.3MB、sha256 与官方值一致、Range 206），
   但同日复测时本探测环境到 `github.com:443` 已超时 —— 官方源只能当**兜底与对照**，
   不能当默认源（与 `FunAsrModelStore.kt` 注释里「GitHub 对国内链路过慢」的判断一致）。

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
| `ModelHostCertExpiring` | 证书剩余 < 14 天 | P2 | on-call | §5 方案 B |
| `ModelHostSlow` | 首字节 > 5s 或 < 500KB/s 持续 10 分钟 | P3 | 值班邮件 | §6 |

## 3. 换源处置 SOP（目标 30 分钟内恢复）

0. **确认不是本地网络**：换一台网络（或远端构建机）再跑一次探测；看输出里是
   `无法访问`（网络）还是 `拦截页` / `Content-Type`（源站）。
1. **选源**：对候选源跑 `--sha256`，必须全绿（含摘要一致）。已知可用列表见 §5。
2. **切源**（按影响面从小到大）：
   - 只救新包：`./gradlew assembleRelease -PasrModelUrl=https://…`（不改代码，见 §4）；
   - 改默认值（**需人类 @qiaozhou 确认**）：改 `app/build.gradle.kts` 的 `asrModelUrl` 兜底值，
     若改成明文 HTTP 还要确认 `network_security_config.xml` 放行该域名（`check_static.py` 会拦住不一致）。
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
  `cvm.agentai0.com` 域名恢复（§5 方案 C）或重装新包。当前 `dist/` 里的包不含 FunASR
  （VOCA-34 记录的出包缺陷），所以这个风险现在体现在**内部测试机**上；一旦含 FunASR 的包发布，
  必须先想清楚旧 URL 的兼容期。
- 真要做到「不发版换源」，需要后端下发 URL（远端配置）+ 客户端兜底缓存，属功能变更，
  不在本 issue 范围 —— 建议另开 issue 给架构师评估。
- 客户端放行规则（`app/src/main/res/xml/network_security_config.xml`）：
  - `http://` 源必须在该文件的 `domain-config` 里（当前已放行 `api.agentai0.com`、`cvm.agentai0.com`）；
  - `https://` 源默认放行，不需要改该文件。

## 5. 托管方案对比（**需人类 @qiaozhou 决策，本 issue 未执行任何一项**）

| 方案 | 做法 | 成本 | 风险 | 救「已装包」设备 |
|---|---|---|---|---|
| **A. 复用已备案域名（推荐先做）** | 在 `121.43.55.151` 的 nginx 上给 `api.agentai0.com` 加 `location /funasr/` 静态目录，放上 74MB 包；默认 URL 改 `http://api.agentai0.com/funasr/sherpa-onnx-paraformer-zh-small-2024-03-09.tar.bz2` | 1 处 nginx 配置 + 上传文件；约 30 分钟；无新增费用 | 明文 HTTP（完整性靠 VOCA-34 的 sha256 兜底）；与业务后端共享带宽；单机单点 | ❌（需重装新包） |
| **B. A + HTTPS** | 给 `api.agentai0.com` 签 Let's Encrypt 证书（当前 443 回落的是 `www` 的证书，主机名不匹配），nginx 加 443 server 块，默认 URL 用 `https://` | 证书签发与续期自动化约 1 小时；无新增费用 | 证书续期失败会中断下载（需 §2.4 的到期告警）；其余同 A | ❌ |
| **C. 恢复 `cvm` 子域备案** | 走备案流程把 `cvm.agentai0.com` 恢复，静态目录放回 `43.136.44.72` 的 nginx | 备案审核通常数个工作日；需要主体资质与人类操作 | 可能再次被拦；该机同时是构建机（80 端口还跑着别的应用） | ✅ **唯一能救已装包设备** |
| **D. 对象存储 + CDN** | 腾讯云 COS / 阿里云 OSS + 已备案域名 CNAME，`https://` + Range 天然支持 | 需人类提供云账号；配置约 2 小时；按 74GB/1000 次下载量级约 ¥40–80/月（公开价量级估算，以账单为准） | 引入第三方依赖与账号权限；国内加速域名仍需备案（可复用现有域名 CNAME） | ❌ |

推荐顺序：**A 今天就能恢复（无需备案、无需证书）→ B 一周内收尾去掉明文**；
C 仅在「已经有含 FunASR 的包发出去」时才变成必须项，否则不急于走备案流程。
若下载量上来再评估 D（依赖人类提供云账号，不可自行开通）。

### 5.1 方案 A 的执行清单（给执行人，约 30 分钟）

待确认项（人类/运维执行前必须核实）：

- `121.43.55.151` 上是否仍有 `sherpa-onnx-paraformer-zh-small-2024-03-09.tar.bz2`，
  或需从官方 Release 重新下载后 `scp` 上去（下载地址见 §1 最后一行）。
- 该机的出网带宽余量（决定是否限速）。

步骤：

1. 放文件：`/var/www/models/funasr/sherpa-onnx-paraformer-zh-small-2024-03-09.tar.bz2`，属主可读。
2. 改 nginx（现有 `api.agentai0.com` server 块里加一个 location，业务 location 不动）：

   ```nginx
   location /funasr/ {
       alias /var/www/models/funasr/;
       autoindex off;
       limit_rate 2m;   # 防单连接打满带宽、拖垮 /api 业务接口
   }
   ```

   nginx 静态文件默认支持 `Range`，无需额外模块。
3. `nginx -t && nginx -s reload`。
4. 探测：`python3 tools/verify/check_model_host.py http://api.agentai0.com/funasr/sherpa-onnx-paraformer-zh-small-2024-03-09.tar.bz2 --sha256` 全绿。
5. 默认 URL 改一行（**需人类批准**）：

   ```diff
    val asrModelUrl: String = (project.findProperty("asrModelUrl") as String?)
   -    ?: "http://cvm.agentai0.com/funasr/sherpa-onnx-paraformer-zh-small-2024-03-09.tar.bz2"
   +    ?: "http://api.agentai0.com/funasr/sherpa-onnx-paraformer-zh-small-2024-03-09.tar.bz2"
   ```

   `network_security_config.xml` **不用改**：`api.agentai0.com` 已在明文放行名单里
   （`check_static.py` 第 6 项会自动核对这件事）。
6. 出包 → 真机冒烟（§7 检查表）→ 灰度：内测 1 人 → 10 人 → 全量。

### 5.2 方案 B 的增量（在 A 之上）

```bash
certbot --nginx -d api.agentai0.com      # 现有 443 回落的是 www 的证书，主机名不匹配
```

默认 URL 改 `https://api.agentai0.com/funasr/…`（HTTPS 默认放行，`network_security_config.xml` 无需改动）；
证书续期进 §2.4 的到期告警。

## 6. 容量与成本

- 单次下载 **77,920,048 B ≈ 74.3 MiB**；首装/清数据后各触发一次，之后走 `tokens.txt` 哨兵不再下载。
- 带宽：`121.43.55.151` 单机出网带宽是硬上限。按 10 并发 × 1MB/s ≈ 10MB/s 估算，
  74MB 一个包约 74 秒；建议 nginx `sendfile on; tcp_nopush on; limit_rate 2m;`
  （或 CDN）避免一次发布打满带宽拖垮 `api` 业务接口。
- 存储：每个源一份 74MB，保留 2 个版本 ≈ 150MB，可忽略。
- 成本上限：A/B/C 均为已有机器，无新增费用；D 按流量计费，需人类定预算上限。

## 7. 发布与回滚检查表（换源类变更）

- [ ] 新源 `check_model_host.py <url> --sha256` 全绿（含 sha256）
- [ ] 与 `FunAsrModelStore.EXPECTED_SHA256`（VOCA-34 交付）一致；两处常量必须相同
- [ ] 若为 `http://`：`network_security_config.xml` 已放行该域名，且 `check_static.py` / `check_crosscut.py` 全绿
- [ ] 真机走通「下载 100% → 解压 → 识别一句话」
- [ ] 旧源文件保留 ≥ 30 天（回滚只需要换回 URL）
- [ ] 本次实测数据已回填 §1 表格

## 8. 责任与升级

| 事项 | Owner |
|---|---|
| 探测脚本、告警规则、Runbook 维护 | SRE |
| 换源方案决策、费用/账号审批 | qiaozhou（人类） |
| 服务器 / 域名 / 证书操作 | 持有运维权限者（人类确认后执行） |
| 客户端下载器校验与自愈 | 后端（VOCA-34） |
| 远端配置下发（若要做「不发版换源」） | 架构师（需新 issue） |

## 变更记录

| 日期 | 变更 | 证据 |
|---|---|---|
| 2026-11-09 | 新建；实测 `cvm` 被备案拦截、官方源可用、探测脚本上线 | 见 §1 与 issue VOCA-35 评论 |