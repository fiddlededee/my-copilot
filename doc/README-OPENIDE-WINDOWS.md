Your role is IT professional.

What is this script for
copilot-v2.main.kts

I need the essence (in 2-3 short paragraphs)

To render answer for this and further questions read the whole file (limit -- 100000) and be especially attentive at Doc hint comments

# As

## What `copilot-v2.main.kts` Is For

This script is a **self-contained AI coding assistant** (a "copilot") written as a Kotlin script. It spins up an LLM-powered agent (via the Koog framework) that reads a dialogue from a Markdown file, iteratively calls an LLM, executes tools on the user's behalf, and writes the assistant's response back into the same file. The intended workflow is to place the script alongside a `Dockerfile`, a `.env`, and a dialogue `.md` file in a project's assistant folder, then trigger it from an IDE hotkey (IntelliJ External Tools or VS Code Tasks) — essentially giving you a one-button "talk to your AI assistant" experience inside any project.

The agent is equipped with a toolkit for **file-system and code manipulation**: reading files (with numbered lines), browsing directories, modifying files via a structured JSON schema (with a "proposal" mechanism that writes changes to a `.proposal` copy first unless the target is on a trusted path), searching the internet through a dedicated sub-agent, analyzing images, and running bash scripts inside a Docker sandbox. It also enforces guardrails — e.g., a file must be read before it can be modified, and sensitive data (PII, API keys) is redacted from the prompt before it is sent to the LLM. Session reasoning and tool-call logs are persisted to a `monitoring-output` directory for observability.

From an infrastructure standpoint, the script is **provider-agnostic** but ships with tuned presets for working from Russia (Kodik Router as an OpenRouter-compatible proxy, and Alibaba Dashscope as a cheap alternative). The LLM provider, model, tool set, and even MCP tool servers can be swapped by editing a small "tuning" section at the bottom of the file, making it a flexible, single-file alternative to heavier agent frameworks.

# Us
Draft a compact how to start guide for me, If I need to set up it in Windows and OpenIDE

Suppose I need to use it in my `D:\my-projects\my-some-great-project`

Use your knowledge, internet search and read the whole file, don't read any other files or directories

The start guide should contain information on Kotlin, Java and OS version
with which this documentation was built (since it is build with the
same copilot)

# As

Now I have everything. Here's the compact how-to-start guide:

---

## How to Start `copilot-v2.main.kts` on Windows + OpenIDE

### Prerequisites

| Component | Version / Notes |
|-----------|----------------|
| **Java (JDK/JRE)** | **21** (this doc was built with JRE `21.0.9+10`). Minimum **17** (script targets JVM 17). |
| **Kotlin** | **2.3.21** (this doc was built with `2.3.21-release-298`). Install via [SDKMAN](https://sdkman.io/), [Kotlin plugin for IntelliJ](https://kotlinlang.org/docs/tutorials/command-line.html), or standalone installer. The `kotlin` command must be on your `PATH`. |
| **OS** | This doc was built on **Ubuntu (Linux)**. On **Windows** you additionally need **Git Bash** (or WSL) so that `bash` is available on `PATH` — the script shells out to `bash -c` for `runPredefinedCommand` and `runScript`. |
| **Docker Desktop** | Required if you enable the `runScript` tool (sandboxed bash execution). |
| **curl** | Pre-installed on Windows 10+; also available via Git Bash. |

---

### Step 1 — Create the `assistant` folder

```
D:\my-projects\my-some-great-project\
└── assistant\
    ├── copilot-v2.main.kts   ← download from GitHub
    ├── Dockerfile             ← minimal, e.g. "FROM ubuntu:22.04"
    ├── .env                   ← your API keys (see below)
    └── prompt.md              ← your dialogue file
```

Download the script:
```powershell
mkdir D:\my-projects\my-some-great-project\assistant
cd D:\my-projects\my-some-great-project\assistant
curl -O https://raw.githubusercontent.com/fiddlededee/my-copilot/refs/heads/main/copilot-v2.main.kts
```

---

### Step 2 — Create the `.env` file

The script reads API keys via `dotenv`. Create `assistant\.env`:

```env
# For Kodik Router / OpenRouter (used by default):
ORKEY=sk-or-v1-your-key-here

# For Dashscope (alternative, uncomment in script):
# DSKEY=your-dashscope-key
```

---

### Step 3 — Create `prompt.md`

This is your dialogue file. The script reads it, appends the assistant's response, and re-appends `# Us` at the end for the next round:

```markdown
# Us

Hello! Please help me with my project.
```

---

### Step 4 — Create `Dockerfile` (optional, only for `runScript` tool)

```dockerfile
FROM ubuntu:22.04
RUN apt-get update && apt-get install -y bash curl
WORKDIR /workspace
```

---

### Step 5 — Configure OpenIDE External Tool

In **IntelliJ / OpenIDE**:

1. **File → Settings → Tools → External Tools**
2. Click **+** to add a new tool:

| Field | Value |
|-------|-------|
| **Name** | `My Copilot` |
| **Program** | `kotlin` |
| **Arguments** | `copilot-v2.main.kts prompt.md` |
| **Working directory** | `D:\my-projects\my-some-great-project\assistant` |

3. Assign a **hotkey** (e.g. `Ctrl+Shift+C`) via **Settings → Keymap → search "My Copilot"**.

---

### Step 6 — Tune the script (optional)

At the bottom of `copilot-v2.main.kts`, adjust the tuning section:

- **LLM Provider**: By default it uses **Kodik Router** (OpenRouter-compatible API). Switch to **Dashscope** or **OpenRouter** by uncommenting the relevant `providerTuning()` block.
- **Model**: Default is `qwen/qwen3.7-plus`. Change `currentModel()` as needed.
- **Tools**: Uncomment `MainAgentTools::runScript` in `currentTools()` to enable Docker sandbox.
- **MCP Tools**: Uncomment the `McpToolRegistryProvider` block to connect IntelliJ MCP server tools.

---

### Step 7 — Run

Press your hotkey (or run manually):

```powershell
cd D:\my-projects\my-some-great-project\assistant
kotlin copilot-v2.main.kts prompt.md
```

The assistant's response will be appended to `prompt.md` after the `# As` marker. Session logs appear in `assistant\monitoring-output\`.

---

### Quick Troubleshooting

| Problem | Fix |
|---------|-----|
| `'bash' is not recognized` | Install [Git for Windows](https://git-scm.com/) and ensure `bash.exe` is on `PATH` |
| `'kotlin' is not recognized` | Install Kotlin compiler and add to `PATH` |
| `docker build failed` | Ensure Docker Desktop is running |
| `ORKEY not found` | Check `.env` file exists in the working directory |
| Script hangs on first run | First run downloads all Maven dependencies (~30 sec) |

# Us