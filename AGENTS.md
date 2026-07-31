# Repository Agent Entry Point

These instructions apply to every AI agent and IDE working in this repository.

Before repository work:

1. Read `docs/handoffs/MASTER_HANDOFF.md`.
2. Read `docs/handoffs/REPOSITORY.md`.
3. Read `docs/handoffs/MULTI_AGENT.md` if any other agent may work in this repo in parallel.
4. Read `docs/handoffs/README.md`.
5. Read the current branch handoff and adjacent `.history.md`.
6. Verify their claims against git before relying on them.
7. Confirm this workspace folder is the intended checkout (primary clone or a dedicated worktree) for the branch you will edit.

**Parallel agents:** never share one working directory across concurrent agents. Use Git worktrees — one folder and branch per agent — as described in `docs/handoffs/MULTI_AGENT.md`.

After every agent change, update the affected active handoff and index before finishing. Append the branch history only for meaningful milestones. Keep every handoff/history Markdown file strictly below 42,000 characters.

Before every PR, propose automated/manual checks and ask the owner to approve or revise them.

After merge and pull, append closure history, archive the branch handoff/history, update the global ledger/index, and delete merged git branches only after reachability verification. Never delete branch documentation.

Further agent-guide documentation: [docs/ai_agent_guides/README.md](docs/ai_agent_guides/README.md).
