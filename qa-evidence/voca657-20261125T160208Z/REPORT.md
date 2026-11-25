# VOCA-657 条目 3 词组自学习 6 项补跑报告

- 设备：sdk_gphone64_arm64（模拟器层（不顶替真机））serial=EMULATOR37X2X12X0
- 包：versionName=1.5 sha256=156ac57969e5f33d85ae0ea27c5b77fb39e402df45e8684f503b1b4a6ed018e8
- 层：模拟器层（不顶替真机）
- 生成：2026-11-25T16:03:25Z

| # | 用例 | 判定 | 读数 | 证据 |
|---|---|---|---|---|
| 3 | AC-3.4③ 拼首字后开合短语面板 | **pass** | 2/2 次自证面板真的打开（IME 窗口高度变化）；无 commit code=<本组期望码>；词库新增 4 行且不含本组期望码 | case3-*.log / case3-*-panel-open.png / user_dict-c3-*.txt |

> 本报告为模拟器空跑自证，**不作为真机验收凭据**；真机回线后用同一脚本（去掉 --emulator）重跑即为验收读数。
