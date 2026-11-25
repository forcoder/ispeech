# VOCA-657 条目 3 词组自学习 6 项补跑报告

- 设备：sdk_gphone64_arm64（模拟器层（不顶替真机））serial=EMULATOR37X2X12X0
- 包：versionName=1.5 sha256=156ac57969e5f33d85ae0ea27c5b77fb39e402df45e8684f503b1b4a6ed018e8
- 层：模拟器层（不顶替真机）
- 生成：2026-11-25T16:11:02Z

| # | 用例 | 判定 | 读数 | 证据 |
|---|---|---|---|---|
| 1 | AC-3.4① 连拼两字不动光标（同会话 10 次不清库） | **pass** | commit 10/10，interrupt(不带 count)=0，每轮词库各 +1 行=10/10，词库 1→22 行 | case1-*.log / user_dict-c1-*.txt |
| 2 | AC-3.4② 拼完首字移光标（等 ≥500ms） | **pass** | 5/5 出现 skipped reason=interrupt（不带 count）；commit=0/5；词库新增 10 行（不含本组期望码） | case2-*.log / user_dict-c2-*.txt |
| 3 | AC-3.4③ 拼首字后开合短语面板 | **pass** | 2/2 次自证面板真的打开（IME 窗口高度变化）；无 commit code=<本组期望码>；词库新增 4 行且不含本组期望码 | case3-*.log / case3-*-panel-open.png / user_dict-c3-*.txt |
| 4 | AC-3.7/3.12 学到词在候选 index 0，原两条仍在且顺序不变、项数 +1 | **pass** | index 0/1/2/3 干净重放——学习前 ['中国银行', '喂猪', 'klqt', 'klqt']，学习后 ['喂猫', '中国银行', '喂猪', 'klqt']（按上屏文本判定，原始编码 klqt 代表该 index 已越界） | case4-cand-before.png / case4-cand-after.png / user_dict-c4-*.txt / case4-replay-*.txt |
| 5 | case 5 | **fail** | 执行异常：RuntimeError('宿主输入框没起来（无 EditText）') | - |
| 6 | case 6 | **fail** | 执行异常：RuntimeError('宿主输入框没起来（无 EditText）') | - |
| f1 | case f1 | **fail** | 执行异常：RuntimeError('宿主输入框没起来（无 EditText）') | - |

> 本报告为模拟器空跑自证，**不作为真机验收凭据**；真机回线后用同一脚本（去掉 --emulator）重跑即为验收读数。
