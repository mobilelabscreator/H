# Agent Lab

**Agent Lab** is a standalone, trainable Android AI runtime and Termux-style workspace built with Kotlin, Jetpack Compose (Material 3 Dark, tablet & landscape adaptive), a pure-Kotlin backpropagation neural engine (`TrainableNeuralNet`), Room + local semantic RAG embeddings, a sandboxed QuickJS JavaScript skill runtime, and a GitHub PR + Actions APK self-upgrade loop.

## Features

1. **Termux AI Shell (Chat)**: Run slash commands (`/run`, `/train`, `/repo`, `/sync`, `/upgrade`, `/ls`, `/cat`, `/grep`, `/skill`, `/status`, `/help`) or chat normally with real-time RAG citations, Markdown, and fenced code blocks with one-tap Copy.
2. **Trainable On-Device Neural Core (`TrainableNeuralNet`)**: Complete pure-Kotlin training pipeline built into the app—dynamic subword/code vocabulary, token embeddings, positional context encoder, hidden feed-forward residual layer, softmax cross-entropy loss, full analytical backpropagation with Adam optimizer, and binary checkpoint persistence (`agent_neural_weights.bin`) + Room epoch tracking.
3. **GitHub Source Workspace & Self-Upgrade Loop**: Give the AI your GitHub repository (`owner/repo` or URL via Settings or `/repo owner/repo`). Agent Lab syncs the source code into its local sandboxed `workspace/`, trains its neural weights on the codebase, edits its own source code on a feature branch (`agent-lab/...`), opens a Pull Request, monitors GitHub Actions APK builds, and retries failed builds up to 3 times—never pushing directly to `main`.
4. **Sources**: Add, edit, toggle, and read web pages, GitHub repositories & READMEs, documentation sites, YouTube transcripts, PDFs (via PDFBox), topics, and search queries.
5. **Knowledge**: Browse, semantically search, edit, and delete learned entries stored locally with embeddings for RAG retrieval.
6. **Skills**: Live `.js` files executed in a strict QuickJS sandbox (`fetch_url`, `web_search`, `read_knowledge`, `save_knowledge`, `call_llm`, `run_skill`) with a line-numbered code editor, version history, and automatic crash rollback.
7. **Agent**: Autonomous loop (`SYNC` → `RESEARCH` → `TRAIN` → `EVALUATE` → `IMPROVE` → `TEST` → `UPGRADE_PR`) triggered by **RUN** or `/run`, with live training loss checkpoints, before/after accuracy scores, color-coded diffs for Approve/Reject, and GitHub PR build tracking.

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
2. Open the latest **Build Agent Lab Debug APK** workflow run and wait for **Assemble Debug APK** to turn green.
3. Under **Artifacts**, download **`agent-lab-debug-apk`** and install `app-debug.apk` on your Android tablet.

### 3. Connect Your Source Repo & Run Self-Upgrades
1. **Zero API Keys Needed**: Agent Lab Core trains and runs completely on-device out of the box. *(Optional: connect a local Ollama/OpenAI-compatible endpoint in Settings if desired.)*
2. **Attach GitHub Source Code**:
   - Type `/repo <YOUR_USERNAME>/<YOUR_REPO>` in the Shell tab (or enter it in **Settings → GitHub Source Code & Self-Upgrade Loop**).
   - To let the AI push self-upgrade Pull Requests on `/run` or `/upgrade`, create a GitHub Fine-Grained Personal Access Token with **Contents (Read & Write)**, **Pull requests (Read & Write)**, and **Actions (Read)** permissions and save it in Settings.
3. Tap **RUN** in the **Agent** tab or type **`/run`** in the Shell tab!
