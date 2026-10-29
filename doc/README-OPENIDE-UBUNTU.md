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
Draft a compact how to start guide for me, If I need to set up it in Ubuntu and OpenIDE

Suppose I need to use it in my `~/my-projects/my-some-great-project`

Use your knowledge, internet search and read the whole file, don't read any other files or directories

The start guide should contain information on Kotlin, Java and OS version
with which this documentation was built (since it is build with the
same copilot)

# As

Now I have all the data. Here's the guide:

---

# Copilot v2 — Quick Start Guide (Ubuntu + IntelliJ-based IDE)

## Environment this guide was built with

| Component | Version |
|-----------|---------|
| **OS** | Ubuntu (OpenJDK package `21.0.9+10-Ubuntu-125.04`) |
| **Kotlin** | 2.3.21-release-298 |
| **JRE / JDK** | 21.0.9 (OpenJDK) |
| **Copilot script** | v1.2.0 |
| **my-copilot-models** | 1.2.0 |

> The script targets JVM 17 (`@file:CompilerOptions("-jvm-target", "17")`), so **JDK 17+ is required**. JDK 21 is recommended.

---

## 1. Install prerequisites

```bash
# JDK 21
sudo apt update && sudo apt install -y openjdk-21-jdk

# Kotlin CLI (needed to run .main.kts scripts with dependency resolution)
sudo snap install --classic kotlin
# — or via SDKMAN:  sdk install kotlin

# Docker (for the sandboxed runScript tool — optional but recommended)
sudo apt install -y docker.io
sudo usermod -aG docker $USER   # log out/in afterwards

# Verify
kotlin -version     # expect 2.x
java -version       # expect 21.x
docker --version
```

---

## 2. Create the project assistant folder

The script is designed to live in a **subfolder** of your project. The parent directory (`..`) becomes the project root that the copilot sees and (optionally) mounts into Docker.

```bash
mkdir -p ~/my-projects/my-some-great-project/.assistant
cd ~/my-projects/my-some-great-project/.assistant
```

Place these files inside `.assistant/`:

| File | Purpose |
|------|---------|
| `copilot-v2.main.kts` | The copilot script itself ([download](https://raw.githubusercontent.com/fiddlededee/my-copilot/refs/heads/main/copilot-v2.main.kts)) |
| `.env` | API keys (see below) |
| `Dockerfile` | Sandbox image definition (optional — only needed if `runScript` tool is enabled) |
| `prompt.md` | Dialogue file — your conversation with the copilot |

---

## 3. Configure `.env`

The script reads API keys via `dotenv-kotlin`. Create `.env` in the `.assistant/` folder:

```bash
# Kodik Router (primary LLM provider — OpenRouter-compatible API)
ORKEY=your-kodik-router-api-key

# Dashscope / Alibaba Cloud (alternative cheap provider)
DSKEY=your-dashscope-api-key
```

> **Note:** `ORKEY` is used regardless of which OpenRouter-compatible provider you configure in the script (Kodik Router, Selectel, OpenRouter, etc.). The script currently defaults to Kodik Router (`api.kodikrouter.ru`).

---

## 4. Create a minimal `prompt.md`

```markdown
# Us

Hello! Help me with my project at ~/my-projects/my-some-great-project.
```

The copilot reads this file, appends its answer as `# As`, and leaves a trailing `# Us` marker so you can continue the conversation.

---

## 5. Test from the command line

```bash
cd ~/my-projects/my-some-great-project/.assistant
kotlin copilot-v2.main.kts ./prompt.md
```

First run will download all dependencies (Koog, Ktor, etc.) — this may take a minute. Subsequent runs are faster.

---

## 6. Set up IntelliJ / OpenIDE External Tool + Hotkey

This is the author's recommended workflow: **one hotkey → copilot runs on your dialogue file**.

### 6a. Add External Tool

1. Open **Settings** (`Ctrl+Alt+S`) → **Tools → External Tools**
2. Click **+** and fill in:

| Field | Value |
|-------|-------|
| **Name** | `Run Copilot` |
| **Program** | `kotlin` |
| **Arguments** | `copilot-v2.main.kts $FilePath$` |
| **Working directory** | `$FileDir$` |

> `$FilePath$` passes the full path of the currently open `prompt.md`; `$FileDir$` sets CWD to the `.assistant/` folder (which is exactly what the script expects).

### 6b. Assign a hotkey

1. Still in **Settings** → **Keymap**
2. Expand **External Tools** → find **Run Copilot**
3. Right-click → **Add Keyboard Shortcut** → press e.g. `Ctrl+Shift+F1`

### 6c. Use it

1. Open `prompt.md` in the editor
2. Write your question after the last `# Us` marker
3. Press your hotkey
4. Wait — the copilot appends its answer as `# As` right in the same file
5. Write your next question and repeat

---

## 7. Tuning the agent (optional)

At the bottom of `copilot-v2.main.kts` you'll find the **Tuning section** (look for `// ========== Tuning agent =============`). Key knobs:

- **`providerTuning()`** — switch LLM provider. Uncomment the Dashscope block for Alibaba Cloud, or keep Kodik Router (default).
- **`currentModel()`** — change the model. Default is `qwen/qwen3.7-plus`.
- **`currentTools()`** — enable/disable tools. Uncomment `MainAgentTools::runScript` to enable Docker sandbox execution.
- **`directModificationPathList()`** — add paths that the copilot can modify *directly* (without creating `.proposal` files). By default all modifications go through the proposal workflow.

---

## Directory structure summary

```
~/my-projects/my-some-great-project/       ← project root (mounted as /workspace in Docker)
├── src/...                                 ← your actual project files
├── build.gradle.kts
└── .assistant/                             ← copilot folder (CWD when running)
    ├── copilot-v2.main.kts                 ← the script
    ├── .env                                ← API keys
    ├── Dockerfile                          ← sandbox (optional)
    ├── prompt.md                           ← dialogue file
    ├── prompt.md.current.md                ← auto-saved state (created at runtime)
    └── monitoring-output/                  ← reasoning logs (created at runtime)
```

# Us