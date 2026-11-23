#!/usr/bin/env python3
"""AC-2/AC-3 判据依赖的两个短路开关：**运行时**取值取证（VOCA-47 打回项 4）。

旧报告的 AC-2/AC-3 用「拼音态」证据（组合缓冲 + 上屏结果）判 PASS，裁决允许这个口径，
但要求同时披露三件事：实测到的状态行、`double_space_period` 与 `chinese_punctuation`
的当场开关值（`chinese_punctuation` 前四轮一直没读）、判定所依据的代码位置。
本脚本补上中间那件：直接从应用私有目录把 DataStore 的 protobuf 解出来。

为什么不能只看代码默认值：`KeyboardSettings.kt:22` 的 `chinesePunctuation = true` 与
`SettingsStore.kt:136` 的 `?: true` 只在**该键从未写入过**时生效——用户在面板里关掉过一次，
持久化里就是 false，默认值读法会得出相反的结论。所以先读盘，读不到才回落到默认并明说。

退出码：0 拿到结论（无论是持久化值还是「从未写入 → 代码默认值」）/ 21 run-as 不可用，取不到任何信息。
"""
import os, re, subprocess, sys, time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import drive as D

BLOBS = ("files/datastore/ispeech_settings.preferences_pb",
         "datastore/ispeech_settings.preferences_pb")
# 代码默认值：KeyboardSettings.kt 的字段初值（SettingsStore.kt 读回时 `?:` 同一套）
DEFAULTS = {"chinese_punctuation": True, "double_space_period": False}
NEED = list(DEFAULTS)
KEYS = re.compile(rb"([a-z][a-z0-9_]{3,})\x12")


def blob():
    """run-as 偶发返回空（应用刚被 force-stop、数据目录还没挂载完），重试几次。"""
    for i in range(4):
        for p in BLOBS:
            r = subprocess.run([D.ADB, "-s", D.S, "shell", "run-as com.ispeech.ime cat " + p],
                               capture_output=True)
            if r.stdout:
                return r.stdout, p, i
        time.sleep(1.0)
    return b"", "-", 4


if __name__ == "__main__":
    b, used, tries = blob()
    if not b:
        # 「没文件」是一个**有效结论**（DataStore 只在首次写入时建文件 → 两个键都走代码默认值），
        # 但必须证明不是读错了地方：把整个私有目录列出来，同一次 run-as 能看到 cell_dicts /
        # user_dict.txt 说明权限与路径口径没问题，而 files/datastore/ 确实不存在。
        ls = D.shell("run-as com.ispeech.ime ls -R files").replace("\n", " ")
        if "cell_dicts" not in ls:      # 连已知文件都列不出来 = run-as 这条路本身不通，不是「没写入」
            D.log("DS", reason="run_as_unavailable", rc=21, out=ls[:200])
            raise SystemExit(21)
        D.log("DS", reason="no_persisted_blob", tried="|".join(BLOBS), retries=tries, files=ls[:300])
        for k in NEED:
            D.log("SWITCH", key=k, value=DEFAULTS[k], source="code_default(never_written)",
                  code_default=DEFAULTS[k])
        raise SystemExit(0)
    got = {}
    for m in KEYS.finditer(b):
        name = m.group(1).decode()
        tail = b[m.end(): m.end() + 16]
        j = tail.find(b"\x08")
        if j >= 0 and len(tail) > j + 1 and tail[j + 1] in (0x00, 0x01):
            got.setdefault(name, bool(tail[j + 1]))
    D.log("DS", path=used, retries=tries, bytes=len(b), persisted=len(got))
    for k in NEED:
        src = "datastore" if k in got else "default(never_written)"
        D.log("SWITCH", key=k, value=got.get(k, DEFAULTS[k]), source=src,
              code_default=DEFAULTS[k])
    D.log("OTHERS", **{k: v for k, v in sorted(got.items()) if k not in NEED})
