---
name: pentagi
description: Drive a self-hosted PentAGI autonomous security-agent deployment (or an equivalent multi-agent recon-to-report pipeline) for authorized security testing, and bootstrap it when it is missing. Use for authorized pentest runs against owned or explicitly permitted targets.
---

# PentAGI

PentAGI 是**自托管**的自主渗透测试多智能体平台（Docker/Go 服务 + Web UI + 自己的 LLM 配置），
不是一个能直接 `pip install` 的库。别假设它已经装好：先探测，再决定是「驱动它」还是「部署它」，两者都做不到就如实说明。

## 何时适用

- 用户要在自己的 PentAGI 实例上跑一次授权安全测试（或查看已完成的 flow / 报告）
- 用户要部署一套 PentAGI 风格的多智能体流程：`recon → plan → exploit → report`
- 用户要复刻 PentAGI 那套「自主代理 + 工具执行 + 人工介入」的编排方式来做其他自动化任务

## 先确认授权与范围（不可跳过）

动手前必须有：目标清单（域名 / IP / 应用）、授权方、时间窗口、允许的动作级别（只做信息收集？允许验证性利用？）、
禁止事项（不得 DoS、不得碰生产数据、不得横向移动到范围外主机）。
范围不明或目标不属于用户时，只做非侵入式的信息收集，并把限制写进报告。

## 探测现状

1. 部署形态：`docker compose ps` / `docker ps`（容器）、`PENTAGI_URL` 是否可达、Web UI 是否响应
2. 凭据来源：只从环境变量或用户显式提供读取（如 `PENTAGI_URL`、`PENTAGI_TOKEN`），**不要**把密钥写进仓库或日志
3. 接口形态：用 `--help`、OpenAPI/Swagger、`/health` 之类端点确认实际接口与字段，**不要凭记忆编造请求体**
4. 结果落盘：flow id、任务 id、日志与报告下载路径

探测不到就停下来报告：缺什么（Docker / 镜像 / LLM key / 网络）、可以由用户补齐什么。

## 驱动一次任务

1. 建 flow：目标、范围、动作级别、（可选）已有凭证；输入只填探测确认过的字段
2. 观察而非放任：盯 agent 的每一步工具调用与输出，出现越界目标、破坏性动作、循环打转时立即介入或终止
3. 人工闸门：任何「验证性利用」以上的动作都要单独确认
4. 收尾：汇总为报告 —— 发现项、复现步骤、影响、修复建议、未覆盖范围与限制

## 部署（仅当用户明确要求且环境允许）

- 需要 Docker + docker compose、LLM API key、足够磁盘与出口网络
- 有网络：按上游仓库 README 的 compose 流程走；无网络 / 无 Docker：报告阻塞点，不要伪造「已部署」
- 部署后立刻收权限：默认口令、管理端口暴露、只监听内网

## 硬性约束

- 只测用户拥有或明确授权的目标；不确定就停
- 不在报告或日志里落明文密钥；不把扫描流量打到范围外
- 不编造工具输出：命令没跑、结果没拿到就写「未执行 / 未获取」
- 破坏性动作（DoS、数据删除、持久化）默认禁止，除非用户对该目标明确书面授权

## 反模式

- 假装已经部署/已经跑过 → 报告不可信
- 一次性把多个目标丢给自主 agent 无人盯守 → 容易越界
- 用「自主代理说成功了」当结论 → 必须回到原始输出与复现步骤
