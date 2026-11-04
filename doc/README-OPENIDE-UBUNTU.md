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
Draft a compact how to start guide for me, If I need to set up it in Ubuntu and OpenIDE

Suppose I need to use it in my `~/my-projects/my-some-great-project`

Use your knowledge, internet search and read the whole file, don't read any other files or directories

The start guide should contain information on Kotlin, Java and OS version
with which this documentation was built (since it is build with the
same copilot)

# As

Now I have all the information. Here's the guide:

---

# copilot-v2 — Quick Start Guide (Ubuntu + IntelliJ IDEA)

**Built with:**
| Component | Version |
|-----------|---------|
| **OS** | Ubuntu 25.04 |
| **Kotlin** | 2.3.21-release-298 |
| **Java (JRE)** | OpenJDK 21.0.9+10-Ubuntu |

---

## 1. Prerequisites

```bash
# Java 21 (the script targets JVM 17, but 21 is recommended)
sudo apt update && sudo apt install -y openjdk-21-jdk

# Kotlin (for running .kts scripts)
sudo snap install kotlin --classic
# — or via SDKMAN:
# curl -s "https://get.sdkman.io" | bash && sdk install kotlin

# Docker (needed only if you plan to use the runScript tool / sandbox)
sudo apt install -y docker.io
sudo usermod -aG docker $USER   # log out & back in after this

# Verify
kotlin -version    # expect 2.x
java -version      # expect 21.x
docker --version
```

## 2. Project Layout

Create the **assistant folder** inside your project:

```
~/my-projects/my-some-great-project/
├── assistant/
│   ├── copilot-v2.main.kts   ← the script (download from GitHub)
│   ├── Dockerfile             ← sandbox image (optional, for runScript)
│   ├── .env                   ← API keys
│   └── prompt.md              ← your dialogue file
├── src/...
└── ...
```

### Download the script

```bash
mkdir -p ~/my-projects/my-some-great-project/assistant
cd ~/my-projects/my-some-great-project/assistant

curl -LO https://raw.githubusercontent.com/fiddlededee/my-copilot/refs/heads/main/copilot-v2.main.kts
```

### Create the `.env` file

```bash
cat > .env << 'EOF'
# Pick ONE provider and fill in the key:

# For Kodik Router / OpenRouter (OpenAI-compatible):
ORKEY=your-api-key-here

# For Alibaba Dashscope (alternative, cheaper):
# DSKEY=your-dashscope-key-here
EOF
```

### Create a dialogue file

```bash
cat > prompt.md << 'EOF'
# Us

Hello! What can you help me with in this project?

# Us
EOF
```

> The dialogue uses `# Us` / `# As` markers to separate user and assistant turns.  
> The copilot appends its response after `# As` and adds a new `# Us` at the end.

## 3. Configure the Script (Tuning Section)

Open `copilot-v2.main.kts` and scroll to the **`// ========== Tuning agent =============`** section at the bottom (~line 1428). Key settings:

| Function | What it does | Default |
|----------|-------------|---------|
| `providerTuning()` | LLM provider for the main agent | Kodik Router (OpenRouter-compatible) |
| `searchProviderTuning()` | Provider for the `searchInternet` tool | Kodik Router with web plugin |
| `currentModel()` | Model name | `qwen/qwen3.7-plus` |
| `currentTools()` | Enabled tools | All except `runScript` |
| `directModificationPathList()` | Paths where files are modified directly (no `.proposal`) | Empty (all via proposals) |
| `dockerVolumes()` | Host → container volume mapping for sandbox | `..` → `/workspace` |

**To switch to Dashscope** (comment/uncomment as indicated by `Doc hint` comments):
```kotlin
// Uncomment this block:
fun providerTuning() = ProviderTuning(clientClass = DashscopeLLMClient::class) {
    it["enable_thinking"] = JsonPrimitive(true)
}
// And comment out the Kodik Router block below it.
```

## 4. Run from Terminal (test first)

```bash
cd ~/my-projects/my-some-great-project/assistant
kotlin copilot-v2.main.kts ./prompt.md
```

The first run will download all dependencies (Koog, serialization, etc.) — this takes a minute. Subsequent runs are faster.

## 5. IntelliJ IDEA — External Tool Setup

1. **Open** `~/my-projects/my-some-great-project` in IntelliJ IDEA.

2. **Settings → Tools → External Tools** → click **+**:

   | Field | Value |
   |-------|-------|
   | **Name** | `Copilot` |
   | **Program** | `kotlin` |
   | **Arguments** | `$FileDir$/copilot-v2.main.kts $FilePath$` |
   | **Working directory** | `$FileDir$` |

   > This assumes you right-click `prompt.md` in the `assistant/` folder to run it.  
   > `$FileDir$` = the folder containing the file, `$FilePath$` = full path to `prompt.md`.

3. **Assign a hotkey**: Settings → Keymap → search "Copilot" → add e.g. `Ctrl+Shift+P`.

4. **Usage**: Open `prompt.md`, type your question after the last `# Us`, press the hotkey. The response appears in-place.

## 6. How It Works (at a glance)

```
prompt.md  →  copilot-v2.main.kts  →  LLM API  →  tool calls  →  prompt.md
   (read)         (agent loop)        (Kodik/OR)    (files, etc)    (append)
```

- The script reads the dialogue from `prompt.md`, sends it to the LLM, processes tool calls iteratively (up to 30 iterations), then appends the assistant's final answer back to the same file.
- **File modifications** go through a *proposal* mechanism by default: changes are written to `<filename>.proposal` for your review. Add paths to `directModificationPathList()` to bypass this.
- **PII redaction** is applied automatically before sending the prompt to the LLM (passports, cards, API keys, etc.).
- **Session logs** are saved to `assistant/monitoring-output/`.

## 7. Optional: Dockerfile for Sandbox

If you enable `MainAgentTools::runScript` in `currentTools()`, create a `Dockerfile` in the `assistant/` folder:

```dockerfile
FROM ubuntu:24.04
RUN apt-get update && apt-get install -y python3 python3-pip nodejs npm && rm -rf /var/lib/apt/lists/*
WORKDIR /workspace
```

The sandbox mounts the parent directory (`~/my-projects/my-some-great-project/`) as `/workspace` inside the container.

# Us