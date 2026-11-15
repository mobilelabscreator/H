# Agent Lab

**Agent Lab** is a standalone, self-improving Android AI workspace built with Kotlin, Jetpack Compose (Material 3 Dark, tablet & landscape adaptive), Room + local semantic embeddings (RAG), an original on-device neural brain (`Agent Lab Core`) requiring **zero API keys**, a sandboxed QuickJS JavaScript skill runtime, and a GitHub Actions self-rewriting PR loop.

## Features

1. **Chat**: Real-time streaming responses powered out of the box by **Agent Lab Core** (zero API keys, trains locally on every learned source & conversation), saved multi-session history, Markdown & fenced code block rendering with one-tap Copy, and inline clickable Knowledge Base source citations.
2. **Sources**: Manage URLs, Topics, and Search Queries. Reads full webpages, GitHub repositories & READMEs, documentation sites, YouTube transcripts, and PDFs (via PDFBox). Tracks last read time, entry count, and errors.
3. **Knowledge**: Browse, semantically search, edit, and delete learned entries stored locally with embeddings for RAG retrieval on every chat message.
4. **Skills**: Live `.js` skill files stored in app storage and executed in a strict QuickJS sandbox with line-numbered monospace code editor, hot-reloading, version history, one-tap rollback, and automatic crash rollback. Built-in sandbox tools: `fetch_url`, `web_search`, `read_knowledge`, `save_knowledge`, `call_llm`, and `run_skill`.
5. **Settings**: Default **Agent Lab Core (Built-in, No Key)** AI engine, plus optional swappable Local/OpenAI-compatible server (e.g., Ollama on LAN) or Custom Cloud API, AES-256-GCM AndroidKeyStore encrypted secret storage, temperature, max tokens, editable system prompt, web search provider (DuckDuckGo no-key default, Tavily, Serper, Brave), GitHub token & repo, and daily token budget / crawl limits.
6. **Agent**: Autonomous 6-phase loop (`RESEARCH` → `LEARN` → `EVALUATE` → `IMPROVE` → `TEST CHANGES` → `APPROVAL`) with RUN/STOP controls, live telemetry log, trained weight counter, editable evaluation test set, before/after accuracy scores, color-coded diffs with manual Approve/Reject (and optional auto-approve toggle, off by default), and a **GitHub Self-Rewrite Loop** that opens PRs on feature branches, monitors GitHub Actions builds, auto-fixes build errors (up to 3 attempts), and surfaces a link to download the built APK.

---

## Setup & Sideload Guide

### 1. Create a GitHub Repository and Push
```bash
git init
git add .
git commit -m "Initial Agent Lab commit"
git branch -M main
git remote add origin https://github.com/<YOUR_USERNAME>/<YOUR_REPO>.git
git push -u origin main
```

### 2. Download the Built APK from GitHub Actions
1. Open your repository on GitHub and click the **Actions** tab.
2. Open the latest **Build Agent Lab Debug APK** workflow run.
3. Wait for the **Assemble Debug APK** job to turn green.
4. Scroll down to **Artifacts** and download **`agent-lab-debug-apk`** (a ZIP containing `app-debug.apk`).

### 3. Sideload onto Your Android Tablet
1. Extract `app-debug.apk` from the downloaded ZIP on your tablet (or transfer it via USB / Drive).
2. Tap `app-debug.apk` in your tablet's **Files** app.
3. When prompted, allow **Install unknown apps** for your file manager.
4. Tap **Install** and launch **Agent Lab**.

### 4. Start Immediately (Zero API Keys Required)
1. **Agent Lab Core** is active immediately on install—no AI API key is required to chat, read sources, run QuickJS skills, or run the autonomous learning loop.
2. *(Optional)* In **Settings**, you can connect a local Ollama/OpenAI-compatible endpoint or custom REST API if desired.
3. *(Optional)* To enable the **GitHub Self-Rewrite Loop** in Settings:
   - Enter your repo as `<YOUR_USERNAME>/<YOUR_REPO>` (base branch `main`).
   - Create a GitHub Fine-Grained Personal Access Token with **Contents (Read & Write)**, **Pull requests (Read & Write)**, and **Actions (Read)** permissions for that repo, and paste it into the encrypted token field.
   - Tap **Save Encrypted Settings**.

You're ready to add sources, chat with RAG citations, edit QuickJS skills, and tap **RUN** in the **Agent** tab!
