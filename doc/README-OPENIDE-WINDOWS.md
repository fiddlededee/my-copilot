Your role is IT professional.

What is this script for
copilot-v2.main.kts

I need the essence (in 2-3 short paragraphs)

To render answer for this and further questions read the whole file (limit -- 100000) and be especially attentive at Doc hint comments

# As

## What is `copilot-v2.main.kts`?

This script is a **self-contained AI coding assistant (copilot)** implemented as a Kotlin script. It uses the **Koog** framework (by JetBrains) to build an LLM-powered agent that can interact with your project: read files and directories, modify source code (via a structured JSON-based modification schema with line-level precision), search the internet, analyze images, and execute bash scripts inside a Docker sandbox. The agent operates in a loop — it reads a dialogue/prompt file (Markdown with `# Us` / `# As` markers), sends it to an LLM, executes any tool calls the model produces, feeds the results back, and repeats until the task is done (up to 30 iterations). The final answer is appended back to the prompt file, making it easy to continue the conversation.

The script includes several **safety and usability mechanisms**: a *proposal-based* file modification workflow (changes are written to `.proposal` files first, unless the target path is explicitly whitelisted for direct writes); a *read-before-modify* enforcement rule that prevents the agent from editing files it hasn't read; automatic **redaction of sensitive data** (API keys, passport numbers, credit cards, etc.) from prompts before they are sent to the LLM; and a monitoring subsystem that logs reasoning and tool-call traces to disk. Sub-agents handle web search and image analysis separately, keeping the main agent focused.

As the *Doc hint* comments explain, the script is designed to be launched from an IDE via a hotkey (IntelliJ external tools or VS Code tasks), with the copilot script, a `Dockerfile`, a `.env` file, and dialogue files placed together in a "project assistant" folder. LLM provider configuration is tuned for accessibility from Russia — it uses **Kodik Router** (`api.kodikrouter.ru`) as the primary endpoint (with the OpenRouter-compatible API adapter) and **Dashscope** (Alibaba Cloud) as a cheaper alternative, running the **Qwen 3.7-plus** model. Standard LLM providers are included as examples; any provider supported by Koog can be swapped in.

# Us
Draft a compact how to start guide for me, If I need to set up it in Windows and OpenIDE

Suppose I need to use it in my `D:\my-projects\my-some-great-project`

Use your knowledge, internet search and read the whole file, don't read any other files or directories

The start guide should contain information on Kotlin, Java and OS version
with which this documentation was built (since it is build with the
same copilot)

# As

Now I have everything. Here's the compact getting-started guide:

---

# 🚀 My-Copilot: Quick Start Guide (Windows + IntelliJ IDEA)

## Build Environment (this documentation was generated with)

| Component | Version |
|-----------|---------|
| **OS** | Ubuntu 25.04 (Linux) — the author's build host |
| **Kotlin** | 2.3.21-release-298 |
| **JRE / JDK** | 21.0.9+10 |
| **Koog framework** | 1.3.0 (requires Kotlin ≥ 2.3.10, JDK ≥ 17) |
| **my-copilot-models** | 1.2.0 |

---

## 1. Prerequisites — Install Once

| # | What | Why | Download |
|---|------|-----|----------|
| 1 | **JDK 17+** (e.g. Temurin 21) | Kotlin compiler & Koog runtime | [adoptium.net](https://adoptium.net/) |
| 2 | **Kotlin compiler** (≥ 2.3.10) | Runs `.main.kts` scripts | [github.com/JetBrains/kotlin/releases](https://github.com/JetBrains/kotlin/releases) — unzip, add `bin/` to `PATH` |
| 3 | **Git for Windows** | Provides `bash` (the script calls `bash -c` internally) | [git-scm.com](https://git-scm.com/download/win) |
| 4 | **Docker Desktop** | Sandbox for `runScript` tool (optional but recommended) | [docker.com](https://www.docker.com/products/docker-desktop/) |
| 5 | **IntelliJ IDEA CE** | IDE with Kotlin plugin (`.main.kts` supported out-of-the-box) | [jetbrains.com/idea](https://www.jetbrains.com/idea/download/) |

> **Verify in CMD/PowerShell after install:**
> ```
> java --version        → 21.x
> kotlinc.bat -version  → 2.3.x
> bash --version        → GNU bash ...
> docker --version      → Docker version 2x.x
> ```

---

## 2. Project Folder Layout

Create an **`assistant`** subfolder inside your project. The copilot, Dockerfile, `.env`, and dialogue files all live there. The **parent** of `assistant` (= your project root) is what gets mounted into the Docker sandbox and is the copilot's "working universe."

```
D:\my-projects\my-some-great-project\      ← project root (basePath)
│
├── src\                                    ← your actual code
├── build.gradle.kts
├── ...
│
└── assistant\                              ← "project assistant folder"
    ├── copilot-v2.main.kts                 ← the copilot script
    ├── Dockerfile                           ← sandbox image definition
    ├── .env                                ← API keys (never commit!)
    ├── prompt.md                           ← dialogue file (your conversation)
    └── monitoring-output\                  ← auto-created session logs
```

---

## 3. Create the Required Files

### 3a. `Dockerfile` (in `assistant\`)

A minimal sandbox — put whatever tools your project needs:

```dockerfile
FROM ubuntu:24.04
RUN apt-get update && apt-get install -y bash curl && rm -rf /var/lib/apt/lists/*
WORKDIR /workspace
```

### 3b. `.env` (in `assistant\`)

The script reads API keys via `dotenv`. The variable name `ORKEY` is used regardless of which LLM provider you actually connect to (Kodik Router, OpenRouter, etc.):

```env
ORKEY=sk-or-v1-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
# DSKEY=your-dashscope-key   ← uncomment if using Dashscope
```

> Get a key from [api.kodikrouter.ru](https://api.kodikrouter.ru) (recommended for Russia) or [openrouter.ai](https://openrouter.ai).

### 3c. `prompt.md` (in `assistant\`)

Your first dialogue message. The format uses `# Us` / `# As` markers:

```markdown
# Us

Help me refactor the database layer in my project.
```

The copilot appends its replies as `# As` blocks and re-adds `# Us` at the end, so the conversation grows naturally.

---

## 4. Configure IntelliJ IDEA — External Tool + Hotkey

This is the author's recommended approach (*Doc hint: "my approach for Open-IDE, IntelliJ — with external tools"*).

### Step 1: Register External Tool

1. **File → Settings → Tools → External Tools**
2. Click **+** and fill in:

| Field | Value |
|-------|-------|
| **Name** | `Run Copilot` |
| **Group** | `Copilot` |
| **Program** | `C:\path\to\kotlin\bin\kotlinc.bat` |
| **Arguments** | `-script copilot-v2.main.kts prompt.md` |
| **Working directory** | `D:\my-projects\my-some-great-project\assistant` |

> 💡 You can also use IDE macros:  
> **Program:** `$ProjectFileDir$\assistant\kotlinc.bat` (if you place Kotlin there)  
> **Working directory:** `$ProjectFileDir$\assistant`

### Step 2: Assign a Hotkey

1. **File → Settings → Keymap**
2. Search for `Run Copilot` (under *External Tools*)
3. Right-click → **Add Keyboard Shortcut** → press e.g. **Ctrl+Shift+C**

### Step 3: Run!

Open your project in IntelliJ, press **Ctrl+Shift+C** (or **Tools → External Tools → Run Copilot**). Watch the Run console for progress. The answer appears in `prompt.md` when done.

---

## 5. Alternative: Run from Command Line

```powershell
cd D:\my-projects\my-some-great-project\assistant
kotlinc.bat -script copilot-v2.main.kts prompt.md
```

---

## 6. Windows-Specific Gotchas

| Issue | Solution |
|-------|----------|
| **`bash` not found** | The script calls `ProcessBuilder("bash", "-c", ...)` internally. Make sure **Git for Windows** is installed and `bash.exe` is on your `PATH` (typically `C:\Program Files\Git\bin`). |
| **Docker volume paths** | Docker Desktop on Windows uses WSL2 backend. Paths like `D:\my-projects\...` are auto-shared. If you get mount errors, check **Docker Desktop → Settings → Resources → File sharing**. |
| **Line endings** | The Dockerfile and `.env` should use **LF** endings. In IntelliJ: bottom-right corner → click `CRLF` → `LF`. |
| **First run is slow** | Kotlin scripting resolves all `@file:DependsOn` Maven artifacts on first launch (~15 libraries). Subsequent runs are faster thanks to caching. |
| **`kotlin` vs `kotlinc.bat`** | On Windows, always use `kotlinc.bat -script`. The `kotlin` runner often doesn't work on Windows. |

---

## 7. Tuning the Copilot (Quick Reference)

All tuning is done by editing the `// ========== Tuning agent =============` section at the bottom of `copilot-v2.main.kts`:

| What to change | Where in the script |
|----------------|---------------------|
| **LLM provider** | `providerTuning()` — switch between Kodik Router, OpenRouter, Dashscope |
| **Model** | `currentModel()` — e.g. `"qwen/qwen3.7-plus"` |
| **Tools enabled** | `currentTools()` — comment/uncomment tools per dialogue step |
| **Direct-write paths** | `directModificationPathList()` — files modified without `.proposal` |
| **Search provider** | `searchProviderTuning()` — separate config for web search |

> **Doc hint:** *"good idea to uncomment necessary tools for even each step in a dialogue"* — you can trim the toolset mid-conversation to reduce token cost and focus the agent.

---

**That's it!** Place the 4 files in `assistant\`, configure the External Tool in IntelliJ, and press your hotkey. The copilot will read your project, make proposals, and answer right inside `prompt.md`.

# Us