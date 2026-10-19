# Project rules

- Always follow the approved plan in `PLAN.md`. If diverting, raise it first.
- Whenever a decision is needed, ask before acting.
- Highlight risks the user may not be seeing.
- Database schema goes in `src/main/resources/schema.sql`, and only what the current task needs. When modifying, drop `./data/navan.db` and let the app recreate it. Document this in the README.
- Add unit **and** integration tests for the whole functionality in scope — not just the 10 required brief scenarios.
- Keep README updated with what's needed, don't over-document.
- No code comments unless the code is genuinely not self-explanatory (non-obvious *why*, gotcha, constraint). One line when needed; no doc blocks.
- Keep `curls.md` current. Every time an endpoint is added, changed, or removed, update the matching section (happy path + the main rejections) before marking the task done.
