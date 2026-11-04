package loquilis.core

import com.squareup.wire.ofEpochSecond
import loquilis.model.AgentText
import loquilis.model.AppCommand
import loquilis.model.Artifact
import loquilis.model.ArtifactChange
import loquilis.model.ArtifactKind
import loquilis.model.Attachment
import loquilis.model.Author
import loquilis.model.BlobRef
import loquilis.model.Decision
import loquilis.model.Entry
import loquilis.model.EntryBody
import loquilis.model.EntryState
import loquilis.model.Fork
import loquilis.model.ForkMethod
import loquilis.model.Interrupt
import loquilis.model.Notice
import loquilis.model.NoticeLevel
import loquilis.model.PermissionDecision
import loquilis.model.PermissionRequest
import loquilis.model.RawRange
import loquilis.model.Restart
import loquilis.model.SetModel
import loquilis.model.SetPermissionMode
import loquilis.model.SubAgent
import loquilis.model.TextRole
import loquilis.model.Thinking
import loquilis.model.ToolCall
import loquilis.model.ToolKind
import loquilis.model.ToolResult
import loquilis.model.TurnResult
import loquilis.model.Usage
import loquilis.model.UserMessage
import loquilis.model.Visibility

val blob = BlobRef(sha256 = "ab".repeat(32), size = 1_048_576, media_type = "text/plain; charset=utf-8")

/** One body of every kind, so a kind added to the proto has to be added here to be tested. */
val everyBody: List<EntryBody> = listOf(
    EntryBody(user_message = UserMessage(inlineContent("Run the tests"), listOf(Attachment("plan.json", blob, "/tmp/plan.json")))),
    EntryBody(agent_text = AgentText(inlineContent("Running them now."), TextRole.TEXT_ROLE_NARRATION)),
    EntryBody(thinking = Thinking(inlineContent("The suite is slow, so start with one module."))),
    EntryBody(tool_call = ToolCall("call-1", "Bash", ToolKind.TOOL_KIND_SHELL, inlineContent("./gradlew check"), "")),
    EntryBody(tool_result = ToolResult("call-1", storedContent(blob, "BUILD SUCCESSFUL"), false)),
    EntryBody(artifact = Artifact(ArtifactKind.ARTIFACT_KIND_IMAGE, "Coverage", blob, "out/coverage.png", "call-1", ArtifactChange.ARTIFACT_CHANGE_ADDED)),
    EntryBody(sub_agent = SubAgent("Search the code", "call-2")),
    EntryBody(app_command = AppCommand(interrupt = Interrupt())),
    EntryBody(permission_request = PermissionRequest("req-1", "call-3", "Delete build/")),
    EntryBody(turn_result = TurnResult(usage = Usage(input_tokens = 1200, output_tokens = 300, cost_micro_usd = 41_500), agent_turns = 3)),
    EntryBody(notice = Notice(NoticeLevel.NOTICE_LEVEL_WARNING, "Rate limited, retrying", "rate_limit")),
)

val everyCommand: List<AppCommand> = listOf(
    AppCommand(interrupt = Interrupt()),
    AppCommand(permission_decision = PermissionDecision("req-1", Decision.DECISION_ALLOW_ONCE, "just this once")),
    AppCommand(set_model = SetModel("a-model")),
    AppCommand(set_permission_mode = SetPermissionMode("acceptEdits")),
    AppCommand(restart = Restart("the process exited")),
    AppCommand(fork = Fork("01JC0000000000000000000000", "01JC0000000000000000000001", ForkMethod.FORK_METHOD_REPLAY)),
)

fun entry(body: EntryBody, seq: Long = 1): Entry = Entry(
    id = "01JC" + seq.toString().padStart(22, '0'),
    conversation_id = "01JC0000000000000000000000",
    seq = seq,
    parent_id = "",
    at = ofEpochSecond(1_793_000_000, 123_000_000),
    author = Author.AUTHOR_AGENT,
    state = EntryState.ENTRY_STATE_COMPLETE,
    visibility = Visibility.VISIBILITY_NORMAL,
    raw = RawRange(first_seq = seq, last_seq = seq),
    body = body,
)
