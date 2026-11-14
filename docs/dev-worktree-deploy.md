# 开发与部署流程（worktree）

> 一句话：**一个任务 = 一个任务分支 + 一个独立 worktree；只有干净的 main 才能出包部署。**
> 脚本：`tools/dev/worktree.sh`（工作树）、`tools/deploy/deploy.sh`（出包 + 装机）。

## 0. 硬规则

1. 禁止在 `main` 工作区直接改代码。
2. 每个任务必须先 `tools/dev/worktree.sh new <task-id> [slug]`。
3. 只从**干净的主干 main** 出包部署，绝不从任务分支或脏工作区打包。

## 1. 流程图

```
new ──► 在 worktree 里开发 + 自测 ──► 提交到 task/<id> ──► land（squash 进 main）
                                                              │
                                                    deploy.sh（从 main 出包）
                                                              │
                                          ┌───────────────────┴───────────────┐
                                     模拟器 emulator-5554               真机 USB / 无线 adb
```

## 2. 任务开始：建 worktree

```bash
cd /Users/cookie/workspace/iSpeech
tools/dev/worktree.sh new VOCA-1234 quick-phrase-search
# → 分支 task/VOCA-1234-quick-phrase-search
# → worktree /Users/cookie/workspace/_wt/ispeech/VOCA-1234
cd /Users/cookie/workspace/_wt/ispeech/VOCA-1234
```

- 基准分支：`origin/main`；fetch 失败退回本地 `main`；**本地 main 领先 origin/main（已 land 未 push）时以本地 main 为准**，避免新任务丢改动。
- 脚本会自动写入该 worktree 的 `local.properties`（沿用主仓 `sdk.dir` → `$ANDROID_HOME` → 常见路径 → 报错）。
- `task/<id>` 分支若已存在，脚本**复用**而不是报错；已有 worktree 时直接打印路径。
- 自定义根目录：`ISPEECH_WT_ROOT=/path/to/wt tools/dev/worktree.sh new ...`

查看全局状态：

```bash
tools/dev/worktree.sh status
# BRANCH / DIRTY(已跟踪改动数) / AHEAD / BEHIND / WORKTREE
```

## 3. 开发与自测

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
python3 tools/verify/check_static.py
python3 tools/verify/check_crosscut.py
# …按需跑 check_input_logic / t9 / stats / phrase_usage / pinyin / quick_trigger
./gradlew assembleDebug          # 只验证能编过，不上机
```

自测通过后在 worktree 内提交（小颗粒提交即可，land 时会 squash）：

```bash
git add -A
git commit -m "VOCA-1234: 一句话说明"
```

## 4. 落地到 main（squash）

```bash
cd /Users/cookie/workspace/iSpeech        # 必须在 main 工作区
tools/dev/worktree.sh land VOCA-1234
git log --oneline -3
```

- 要求：当前在 main、**已跟踪文件无未提交改动**、`task/<id>` 有相对 main 的提交。
- 合并前用 `git merge-tree` 预检冲突；有冲突直接失败且不改动 main，按提示回 worktree `git rebase main` 后重试。
- 提交信息格式：`<task-id>: <摘要>`，body 带 Claude Code 署名行。
- `land-all` 按创建时间顺序合并所有未合并的 `task/*`：
  ```bash
  tools/dev/worktree.sh land-all
  ```

## 5. 出包 + 部署

```bash
cd /Users/cookie/workspace/iSpeech
export JAVA_HOME=/opt/homebrew/opt/openjdk@17     # 脚本也会自动设置
tools/deploy/deploy.sh --target emulator          # 装模拟器
tools/deploy/deploy.sh --target phone             # 装真机
tools/deploy/deploy.sh --target all               # 模拟器 + 所有真机
tools/deploy/deploy.sh --target serial:R58M123ABC # 指定序列号
tools/deploy/deploy.sh --merge-all --target auto  # 先 land-all 再部署
```

脚本依次做：

1. 校验分支是 `main` 且工作区干净（不干净直接失败，提示去 worktree）；
2. `--merge-all` 时先 `tools/dev/worktree.sh land-all`；
3. 跑 `check_static / input_logic / t9 / stats / phrase_usage / pinyin / crosscut / quick_trigger`
   （失败即停；`--skip-verify` 可跳过但会打印醒目的风险告警）；
4. `export JAVA_HOME=/opt/homebrew/opt/openjdk@17` 后 `./gradlew assembleDebug | assembleRelease`；
5. 复制到 `dist/ispeech-<debug|release>-<versionName>-<yyyyMMdd-HHmm>.apk`，打印路径、大小、sha256；
6. 解析设备：`emulator` 取 `emulator-*`；`phone` 取非 emulator 序列号；模拟器没起时自动
   `emulator -avd ispeech_test -no-snapshot -no-audio -no-boot-anim`（可用 `ISPEECH_AVD` 覆盖）并等 `sys.boot_completed=1`；
   无设备时打印 `adb devices` 与接真机命令后失败退出；
7. `adb -s <serial> install -r <apk>`；
8. `ime enable com.ispeech.ime/com.ispeech.input.IspeechImeService` → `ime set` → 回读 `settings get secure default_input_method`；
9. 截图到 `dist/screenshots/deploy-<ts>-<serial>.png`；
10. 全流程日志写 `dist/deploy-<ts>.log`。

其他参数：`--release`、`--no-build`（复用 `app/build/outputs/apk/...` 现有产物）、`--skip-verify`。

## 6. 装真机

USB：

```bash
adb devices                       # 看到非 emulator-* 序列号
tools/deploy/deploy.sh --target phone
```

无线 adb（同一 Wi-Fi）：

```bash
adb -s <serial> tcpip 5555                       # 首次需 USB 连着
adb connect <手机IP>:5555                         # 之后可拔线
adb devices
tools/deploy/deploy.sh --target serial:<手机IP>:5555
```

## 7. 清理与回滚

```bash
tools/dev/worktree.sh remove VOCA-1234            # 仅允许已合并的分支
tools/dev/worktree.sh remove VOCA-1234 --force    # 明确丢弃未合并的改动
```

回滚部署：

```bash
adb -s <serial> uninstall com.ispeech.ime         # 卸载
adb -s <serial> install -r dist/ispeech-debug-v1.5-<旧时间戳>.apk   # 回装旧包
```

回滚代码（**不推荐** `reset --hard`）：在 main 上用 `git revert <land 提交>` 生成反向提交，
再走一次 `deploy.sh` 出包；这样历史可追溯、不影响其它 worktree。

## 8. 常见坑

| 现象 | 原因 / 修法 |
| --- | --- |
| `./gradlew` 报找不到 JDK / 工具链 | 每台机的 JDK 位置不同；先 `export JAVA_HOME=/opt/homebrew/opt/openjdk@17`（Intel/Mac 或 Linux 换成对应路径） |
| `SDK location not found` | 每个 worktree 都需要自己的 `local.properties`（脚本已自动写）；或 `export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools` |
| `local.properties` 指向不存在的路径 | 该文件 gitignore、每机不同；`deploy.sh` 会自动按 `ANDROID_HOME`/常见路径修正 |
| 多设备时 `adb` 报 `more than one device` | 一律带 `-s <serial>`；本仓脚本已处理 |
| 输入法装好了但没生效 | **必须先 `ime enable` 再 `ime set`**，顺序反了会失败；之后回读 `settings get secure default_input_method` |
| `ime enable` 报 `Unknown input method` | 包没装成功或 service 名不对（`com.ispeech.ime/com.ispeech.input.IspeechImeService`） |
| 模拟器起不来 / 卡在启动 | `emulator -list-avds` 确认 AVD 名；`-no-snapshot` 冷启动；看 `dist/emulator-<ts>.log` |
| land 报冲突 | 回任务 worktree：`git rebase main` 解决后重新 land（脚本不会污染 main） |
| 新任务分支缺少已 land 的改动 | 本地 main 领先 origin/main 时会以本地为基准；若仍异常，手动 `git rebase main` |
| `deploy.sh` 拒绝执行 | 当前不是 main 或 main 有未提交改动——去 worktree 干活，别在 main 改 |
| release 包装不上 | 未配置 `keystore/keystore.properties` 时 release 不签名，只能用 debug 装机 |