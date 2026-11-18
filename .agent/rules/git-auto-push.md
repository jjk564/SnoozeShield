---
description: Ensure all code and configuration changes are committed and pushed to the GitHub repository at the end of every turn.
---

# Git Auto-Commit & Push Rule

At the end of every turn in the **Alarm Project** workspace where any files are created, modified, or deleted:
1. Stage all relevant changes (`git add -A`), ensuring temporary databases, build outputs, and secrets remain excluded via `.gitignore`.
2. Commit with a clear, descriptive commit message summarizing the turn's changes.
3. Push the commit to `origin main` (`git push origin main`).
4. If changes also touch **Nexus Master OS** (`C:\Users\jkilp\OneDrive\Desktop\Master Dashboard`), update its `INFRASTRUCTURE_LOG.md` and commit/push that repository as well.
