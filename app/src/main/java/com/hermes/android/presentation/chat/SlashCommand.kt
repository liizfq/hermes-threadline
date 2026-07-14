package com.hermes.android.presentation.chat

data class SlashCommand(
    val name: String,
    val description: String,
    val category: String,
    val aliases: List<String> = emptyList(),
    val argsHint: String = ""
)

object SlashCommandRegistry {
    /** Approximate usage frequency ordering — higher index = more frequent. */
    private val frequencyOrder = listOf(
        // Most frequent
        "new", "model", "retry", "undo", "status", "sessions", "steer", "stop",
        // Frequent
        "help", "commands", "topic", "branch", "compress", "rollback",
        "memory", "cron", "voice", "reasoning", "yolo", "fast",
        // Less frequent
        "start", "title", "approve", "deny", "background", "agents",
        "queue", "goal", "subgoal", "sethome", "resume", "restart",
        "codex-runtime", "personality", "footer",
        "bundles", "suggestions", "blueprint", "curator", "kanban",
        "reload-mcp", "reload-skills", "browser",
        // Least frequent
        "usage", "credits", "billing", "insights", "platform",
        "update", "version", "debug", "whoami", "profile"
    )

    val commands: List<SlashCommand> = listOf(
        // Session
        SlashCommand("start", "Acknowledge platform start pings", "Session"),
        SlashCommand("new", "Start a new session", "Session", aliases = listOf("reset"), argsHint = "[name]"),
        SlashCommand("topic", "Enable or inspect topic sessions", "Session", argsHint = "[off|help|session-id]"),
        SlashCommand("retry", "Retry the last message", "Session"),
        SlashCommand("undo", "Back up N user turns", "Session", argsHint = "[N]"),
        SlashCommand("title", "Set a title for the current session", "Session", argsHint = "[name]"),
        SlashCommand("branch", "Branch the current session", "Session", aliases = listOf("fork"), argsHint = "[name]"),
        SlashCommand("compress", "Compress conversation context", "Session", argsHint = "[here [N] | focus topic]"),
        SlashCommand("rollback", "List or restore filesystem checkpoints", "Session", argsHint = "[number]"),
        SlashCommand("stop", "Kill all running background processes", "Session"),
        SlashCommand("approve", "Approve a pending dangerous command", "Session", argsHint = "[session|always]"),
        SlashCommand("deny", "Deny a pending dangerous command", "Session"),
        SlashCommand("background", "Run a prompt in the background", "Session", aliases = listOf("bg", "btw"), argsHint = "<prompt>"),
        SlashCommand("agents", "Show active agents and running tasks", "Session", aliases = listOf("tasks")),
        SlashCommand("queue", "Queue a prompt for the next turn", "Session", aliases = listOf("q"), argsHint = "<prompt>"),
        SlashCommand("steer", "Inject a message after the next tool call", "Session", argsHint = "<prompt>"),
        SlashCommand("goal", "Set a standing goal", "Session", argsHint = "[text | pause | resume | clear | status]"),
        SlashCommand("subgoal", "Add or manage extra criteria on the active goal", "Session", argsHint = "[text | remove N | clear]"),
        SlashCommand("status", "Show session, model, token, and context info", "Session"),
        SlashCommand("sethome", "Set this chat as the home channel", "Session", aliases = listOf("set-home")),
        SlashCommand("resume", "Resume a previously-named session", "Session", argsHint = "[name]"),
        SlashCommand("sessions", "Browse and resume previous sessions", "Session"),
        SlashCommand("restart", "Gracefully restart the gateway", "Session"),

        // Configuration
        SlashCommand("model", "Switch model", "Configuration", argsHint = "[model] [--provider name]"),
        SlashCommand("codex-runtime", "Toggle codex runtime", "Configuration", aliases = listOf("codex_runtime"), argsHint = "[auto|codex_app_server]"),
        SlashCommand("personality", "Set a predefined personality", "Configuration", argsHint = "[name]"),
        SlashCommand("footer", "Toggle gateway metadata footer", "Configuration", argsHint = "[on|off|status]"),
        SlashCommand("yolo", "Toggle YOLO mode (skip approvals)", "Configuration"),
        SlashCommand("reasoning", "Manage reasoning effort and display", "Configuration", argsHint = "[level|show|hide]"),
        SlashCommand("fast", "Toggle fast mode", "Configuration", argsHint = "[normal|fast|status]"),
        SlashCommand("voice", "Toggle voice mode", "Configuration", argsHint = "[on|off|tts|status]"),

        // Tools & Skills
        SlashCommand("memory", "Review pending memory writes", "Tools", argsHint = "[pending|approve|reject|approval]"),
        SlashCommand("bundles", "List skill bundles", "Tools"),
        SlashCommand("cron", "Manage scheduled tasks", "Tools", argsHint = "[list|add|create|edit|pause|resume|run|remove]"),
        SlashCommand("suggestions", "Review suggested automations", "Tools", aliases = listOf("suggest"), argsHint = "[accept|dismiss N | catalog]"),
        SlashCommand("blueprint", "Set up automation from blueprint", "Tools", aliases = listOf("bp"), argsHint = "[name] [slot=value ...]"),
        SlashCommand("curator", "Background skill maintenance", "Tools", argsHint = "[status|run|pause|resume|pin|unpin|restore]"),
        SlashCommand("kanban", "Multi-profile collaboration board", "Tools", argsHint = "[init|boards|create|list|show|assign|complete]"),
        SlashCommand("reload-mcp", "Reload MCP servers from config", "Tools", aliases = listOf("reload_mcp")),
        SlashCommand("reload-skills", "Re-scan skills directory", "Tools", aliases = listOf("reload_skills")),
        SlashCommand("browser", "Connect browser via CDP", "Tools", argsHint = "[connect|disconnect|status]"),

        // Info
        SlashCommand("commands", "Browse all commands and skills", "Info", argsHint = "[page]"),
        SlashCommand("help", "Show available commands", "Info"),
        SlashCommand("usage", "Show token usage and rate limits", "Info"),
        SlashCommand("credits", "Show Nous credit balance", "Info"),
        SlashCommand("billing", "Manage Nous terminal billing", "Info"),
        SlashCommand("insights", "Show usage insights and analytics", "Info", argsHint = "[days]"),
        SlashCommand("platform", "Pause or resume a platform", "Info", argsHint = "<pause|resume|list> [name]"),
        SlashCommand("update", "Update Hermes Agent", "Info"),
        SlashCommand("version", "Show version", "Info", aliases = listOf("v")),
        SlashCommand("debug", "Upload debug report", "Info"),
        SlashCommand("whoami", "Show slash command access level", "Info"),
        SlashCommand("profile", "Show active profile name", "Info"),
    )

    /**
     * Filter commands by prefix match (case-insensitive).
     * Matches against both name and aliases.
     * Results are sorted by approximate usage frequency.
     */
    fun filterByPrefix(query: String): List<SlashCommand> {
        val filtered = if (query.isEmpty()) {
            commands
        } else {
            val lower = query.lowercase()
            commands.filter { cmd ->
                cmd.name.lowercase().startsWith(lower) ||
                cmd.aliases.any { it.lowercase().startsWith(lower) }
            }
        }
        return filtered.sortedBy { cmd ->
            val idx = frequencyOrder.indexOf(cmd.name)
            if (idx == -1) frequencyOrder.size else idx
        }
    }
}
