# VOCA-443 质量报告：T9 态 -/= 横滚等价性与上屏后偏移复位（规格 §9.8 ③④）

- 被测基线：main `fa57a07`（任务分支 HEAD `aa4e236` 仅加 QA 工装，产品代码零改动）
- 包：app-debug.apk sha256 `48ee0d3d…32eb330`，IDENT 首尾 match=True
- 环境：emulator-5554，1080×2340@440dpi，API34；两次全量独立 run（run8/run9）判定一致
- 证据：`evidence/ev_all_run8.txt`、`evidence/ev_all_run9.txt`、`evidence/shots/`（run9 帧）

## 结论

| AC | 腿 | verdict | 关键读数 |
|---|---|---|---|
| AC-a1 横滚等价性 | T9（digits=74，池=34≥8） | **PASS** | '='×3 = [+198,+198,+198]，cum=594=3×单档；'-'×3 镜像 [-198,-198,-198]；最大 err 0.35px ≤5；宿主文本 '74' 全程未变（滚动不上屏） |
| AC-a2 上屏后偏移复位 | T9 | **PASS** | 滚 990px 后点首个可见候选上屏 '日'；联想态左边界探针 at_left=True；下一字 '2' 首屏 at_left=True，回读 '日2' |
| AC-a2 上屏后偏移复位 | 字母腿（硬件 'g'，池=34≥8） | **PASS** | 滚 990px 上屏 '到'/'来'（跨 run 池序异步，见观察 2）；上屏后与下一字 'h' 首屏均 at_left=True |

**issue 前提不成立（好消息）**：描述假设「t9Digits 分支在 -/= 之前吞键」，但 main fa57a07
的 onKeyDown 无该分支（IspeechImeService.kt:601-605 直达 scrollCandidates），实测键未被吞。
**R18 不触发，无需另立 T9 -/= 缺陷单。**

## AC-a2 的可判定判据（交付物①）

字面 Then「候选带偏移=0」依赖 scrollX 读数，但 IME 窗口不可 dump（规格 §9.9，NODEPROBE
实测证实），无直接读数。本工装给出像素等价判据并被两次 run 执行：

- 候选 ≥2：**左边界探针** = '-' 按下像素静止（shift=0）**且** '=' 走出 +198±6 → 视口在左端；
- 候选 ≤1（'-' 会放行宿主，:602）：静态判据 = 首选高亮盒左沿 == paddingStart 8dp。

该判据与「scrollX==0」在「内容可滚」前提下等价；**UX 对 Then 的裁定仍未回**，裁定前
本 PASS 按「判据已交付、口径待 UX 确认」挂账，不替 UX 拍板。

## PM 挂账四项的处置

| 项 | 处置 |
|---|---|
| §9-11 挂载层运行时检查 | 已做前置取证：NODEPROBE ime_window_dumpable=False、scrollable_nodes=0 → uiautomator 路线走不通（与 §9.9 一致）；挂载层判定需另立可注入/可 dump 的工装，**待补测** |
| §9-12 dy=20 键盘收起成因 | **待补测**：需收起场景的 inset 时序采样，不在 ③④ 范围，建议随 VOCA-47 缺陷线走 |
| §9-7 AC-9 45ms（logcat） | **待补测**：需 logcat 时间戳基线与触发链路打点核对，不在 ③④ 范围 |
| T9 用户占比 | **采集不到**：代码与设备均无该遥测维度，不构造假数 |

## 顺带观察（不在本 issue 范围，建议新开 issue，不顺手改）

1. **硬件 DEL/ESC 清不掉 T9 缓冲**：T9 态 buffer 恒空，硬件 DEL 在 :568 放行宿主（只删宿主
   组合文本），硬件 ESC 在 :575 早退（条件不含 t9Digits）→ t9Digits 残留，宿主文本与候选
   脱节，再敲数字会拼接（工装 run7 实测 '7474'）。软键 del 正常（:674-677）。影响面=外接
   键盘 + T9 用户。建议新缺陷单 @前端 评估，严重级建议 P2（有软键替代路径）。
2. 字母腿候选池序跨 run 不稳定（同码 'g' 同偏移上屏词 run8='到' run9='来'），疑似异步查词
   重排时序；不影响本 AC，建议并入候选排序相关 issue 观察。

## 风险与下一步

1. AC-a2 判据口径待 UX 裁定（G2 同类：用户可见后果=上屏后候选带停在中间，下一字首屏错位）；
   裁定若改 Then，重跑 `--leg all` 即可，工装判据集中在 origin_probe()。
2. 本轮不承接 VOCA-47 go/no-go；合并窗口由 leader 主导，本分支只加 `tools/qa/voca-443/`。
3. 环境无 gh CLI，PR 无法由 agent 创建：分支已推 origin，MR 由合并窗口补建（如实报缺口）。