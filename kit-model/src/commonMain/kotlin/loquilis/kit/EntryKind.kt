package loquilis.kit

import loquilis.model.AgentText
import loquilis.model.AppCommand
import loquilis.model.Artifact
import loquilis.model.EntryBody
import loquilis.model.Fork
import loquilis.model.Interrupt
import loquilis.model.Notice
import loquilis.model.PermissionDecision
import loquilis.model.PermissionRequest
import loquilis.model.Restart
import loquilis.model.SetModel
import loquilis.model.SetPermissionMode
import loquilis.model.SubAgent
import loquilis.model.Thinking
import loquilis.model.ToolCall
import loquilis.model.ToolResult
import loquilis.model.TurnResult
import loquilis.model.UserMessage

/**
 * The `oneof` of an [EntryBody] as a sealed type. Wire generates a oneof as nullable fields, one per
 * case; reading it through [EntryBody.kind] gives code an exhaustive `when`, so adding a kind to the
 * proto is a compile error at every place that has to decide what to do with it.
 *
 * [Unknown] is a real case, not an error: a body written by a newer schema decodes with no case set
 * here, and Wire keeps its bytes in `unknownFields`, so it survives being stored and written back.
 */
sealed interface EntryKind {
    data class User(val message: UserMessage) : EntryKind
    data class Text(val text: AgentText) : EntryKind
    data class Reasoning(val thinking: Thinking) : EntryKind
    data class Call(val call: ToolCall) : EntryKind
    data class Result(val result: ToolResult) : EntryKind
    data class Made(val artifact: Artifact) : EntryKind
    data class Sub(val subAgent: SubAgent) : EntryKind
    data class Command(val command: AppCommand) : EntryKind
    data class Permission(val request: PermissionRequest) : EntryKind
    data class TurnEnd(val result: TurnResult) : EntryKind
    data class Note(val notice: Notice) : EntryKind
    data object Unknown : EntryKind
}

val EntryBody.kind: EntryKind
    get() = when {
        user_message != null -> EntryKind.User(user_message)
        agent_text != null -> EntryKind.Text(agent_text)
        thinking != null -> EntryKind.Reasoning(thinking)
        tool_call != null -> EntryKind.Call(tool_call)
        tool_result != null -> EntryKind.Result(tool_result)
        artifact != null -> EntryKind.Made(artifact)
        sub_agent != null -> EntryKind.Sub(sub_agent)
        app_command != null -> EntryKind.Command(app_command)
        permission_request != null -> EntryKind.Permission(permission_request)
        turn_result != null -> EntryKind.TurnEnd(turn_result)
        notice != null -> EntryKind.Note(notice)
        else -> EntryKind.Unknown
    }

/** The `oneof` of an [AppCommand] as a sealed type, for the same reason as [EntryKind]. */
sealed interface CommandKind {
    data class Stop(val interrupt: Interrupt) : CommandKind
    data class Decide(val decision: PermissionDecision) : CommandKind
    data class Model(val setModel: SetModel) : CommandKind
    data class Mode(val setMode: SetPermissionMode) : CommandKind
    data class Reboot(val restart: Restart) : CommandKind
    data class Branch(val fork: Fork) : CommandKind
    data object Unknown : CommandKind
}

val AppCommand.kind: CommandKind
    get() = when {
        interrupt != null -> CommandKind.Stop(interrupt)
        permission_decision != null -> CommandKind.Decide(permission_decision)
        set_model != null -> CommandKind.Model(set_model)
        set_permission_mode != null -> CommandKind.Mode(set_permission_mode)
        restart != null -> CommandKind.Reboot(restart)
        fork != null -> CommandKind.Branch(fork)
        else -> CommandKind.Unknown
    }
