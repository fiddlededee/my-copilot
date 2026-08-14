# Reasoning Content

Let me analyze the project structure and code to understand what this project is about.

From the directory listing:
- `.git/` - Git repository
- `.idea/` - IntelliJ IDEA project
- `monitoring-output/` - Contains a markdown file with monitoring data
- `.env` - Environment variables (contains DSKEY which is redacted)
- `.gitignore`
- `README.md` - User said not to read this
- `copilot-v2.main.kts` - Main Kotlin script (25 KB) - this is the core file
- `iterate.sh` - Shell script

From the `.env` file:
- `DSKEY=[REDACTED:OpenRouterKey]` - An API key (redacted)

From `iterate.sh`:
- `kotlin copilot-v2.main.kts README.md` - Runs the Kotlin script with README.md as argument

From `copilot-v2.main.kts` (the main file):
- It's a Kotlin script that uses the Koog AI agent framework
- Dependencies include:
  - `kotlinx-serialization-json` - JSON serialization
  - `prompt-executor-dashscope-client-jvm` - DashScope LLM client (Alibaba Cloud)
  - `prompt-executor-openrouter-client-jvm` - OpenRouter LLM client
  - `prompt-executor-openai-client-jvm` - OpenAI LLM client
  - `agents-core-jvm` - Core AI agent functionality
  - `jsoup` - HTML parsing
  - `angryscan` - Data redaction/matching (PII detection)
  - `dotenv-kotlin` - Environment variable management

Key components:
1. **LlmCommon** - Manages LLM clients (OpenRouter, DashScope)
2. **Monitoring** - Tracks and logs LLM responses (reasoning, text, tool calls) to markdown files


3. **Redaction** - Uses AngryScan to detect and redact sensitive information like passports, phone numbers, emails, and credit card details
4. **MainAgentTools** - Provides file reading capabilities with automatic PII redaction and directory listing
5. **mainAgent()** - Creates an AI agent that processes prompts from files, supports tool calling, runs up to 30 iterations, and writes responses back to the prompt file

The project is essentially an AI-powered coding assistant that reads files and directories while automatically protecting sensitive data.

The system leverages Qwen3 models through DashScope (Alibaba Cloud), supports reasoning mode with thinking enabled, and uses a custom prompt format with markers to distinguish between assistant and user messages.

# Text Content

## Project Overview: my-copilot

This is a **Kotlin-based AI coding assistant** (similar to GitHub Copilot or Cursor) implemented as a single Kotlin script (`copilot-v2.main.kts`).

### What it does

It's an **agentic AI tool** that helps IT professionals work with code and files by:

1. **Reading files and directories** — The agent has two tools:
   - `readFile` — reads file contents with line numbers
   - `readDirectory` — displays directory structure as an ASCII tree

2. **Automatic PII redaction** — Before returning file contents, it scans for and redacts sensitive data using the AngryScan library:
   - Passports, SNILS, INN, phone numbers, emails
   - Credit card numbers, bank accounts
   - Crypto wallets, seed phrases
   - API keys (OpenRouter, etc.)

3. **Multi-provider LLM support** — Currently configured to use:
   - **DashScope** (Alibaba Cloud) with Qwen3 models
   - Also supports OpenRouter client
   - Has "thinking/reasoning" mode enabled

4. **Monitoring** — Logs all LLM interactions (reasoning, text output, tool calls) to markdown files in `monitoring-output/`

### How an IT professional can use it

```bash
# Run the assistant on any markdown/text file
kotlin copilot-v2.main.kts your-prompt.md
```

The agent will:
- Read your prompt file
- Use AI to understand what you need
- Read relevant files/directories as needed
- Append its response back to the file using `# As` / `# Us` markers (conversation format)

### Key features for IT pros

- **Secure by default** — Sensitive data is automatically redacted before being sent to LLMs
- **Iterative** — Can make up to 30 tool-calling iterations to solve complex tasks
- **Configurable** — Easy to switch between LLM providers and models
- **Lightweight** — Single script, no complex build system needed

This is essentially a **self-hosted, privacy-focused coding assistant** that you can run locally with your preferred LLM provider.