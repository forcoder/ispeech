#!/usr/bin/env bash
# 把本目录下暂存的技能安装到 Codex 技能目录。
#
# 用法:
#   sh skills/install-skills.sh                    # 安装到 ${CODEX_HOME:-$HOME/.codex}/skills
#   CODEX_HOME=/path sh skills/install-skills.sh   # 指定 CODEX_HOME
#
# 目标目录不在当前沙箱可写范围内时（CI/受限环境），请由用户在本机执行本脚本。
set -euo pipefail

src_dir="$(cd "$(dirname "$0")" && pwd)"
dest_root="${CODEX_HOME:-$HOME/.codex}/skills"

mkdir -p "$dest_root"

for name in reverse-skill pentagi rea-loop; do
  src="$src_dir/$name"
  dest="$dest_root/$name"
  if [ ! -d "$src" ]; then
    echo "跳过：找不到 $src"
    continue
  fi
  if [ -e "$dest" ]; then
    echo "跳过：$dest 已存在（如需覆盖请先手动删除）"
    continue
  fi
  cp -R "$src" "$dest"
  echo "已安装 $name -> $dest"
done

echo "完成。新增技能在下一轮对话生效。"
