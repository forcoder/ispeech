# 仓库级硬规则（worktree 开发 / 出包部署）

完整 SOP 见 `docs/dev-worktree-deploy.md`，脚本为 `tools/dev/worktree.sh` 与 `tools/deploy/deploy.sh`。

1. **禁止在 `main` 工作区直接改代码。** 任何改动都必须在任务分支的独立 worktree 内完成。
2. **每个任务必须先用 `tools/dev/worktree.sh new <task-id> [slug]` 建分支与 worktree**，
   分支名 `task/<task-id>[-slug]`，worktree 在 `$ISPEECH_WT_ROOT`（默认 `~/workspace/_wt/ispeech`）。
3. **只从干净的主干 `main` 出包与部署**：先 `tools/dev/worktree.sh land <task-id>` 落地，
   再 `tools/deploy/deploy.sh`；绝不从任务分支或脏工作区打包。