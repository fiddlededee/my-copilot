package ru.fiddlededee.mycopilot.models

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable

@Serializable
enum class ModificationType { APPEND, DELETE, REPLACE, REPLACE_IN_LINE }

@Serializable
@LLMDescription(
    """
    A single modification instruction for a file.
    Exactly one of four operation types must be specified via the 'type' field.
    Fields that are not relevant for the chosen type must be null.

    - APPEND: Insert new content after a specific line.
      Required: startLine (the line AFTER which to insert), contents (text to insert).
      endLine, searchText, replacementText must be null.

    - DELETE: Remove a contiguous range of lines.
      Required: startLine (first line to delete), endLine (last line to delete, inclusive).
      contents, searchText, replacementText must be null.

    - REPLACE: Replace a contiguous range of lines with new content.
      Required: startLine (first line to replace), endLine (last line to replace, inclusive),
                contents (replacement text).
      searchText, replacementText must be null.

    - REPLACE_IN_LINE: Find-and-replace a substring within a single line.
      Required: startLine (the line number), searchText (substring to find),
                replacementText (substring to replace with).
      endLine, contents must be null.
    """
)
data class Modification(
    @property:LLMDescription("The type of modification to perform.")
    val type: ModificationType,

    @property:LLMDescription("Line number (1-based) where the operation starts or applies.")
    val startLine: Int?,

    @property:LLMDescription("Line number (1-based, inclusive) where the operation ends. Used only for DELETE and REPLACE.")
    val endLine: Int?,

    @property:LLMDescription("Text content to insert or use as replacement. Used for APPEND and REPLACE.")
    val contents: String?,

    @property:LLMDescription("Substring to search for within a single line. Used only for REPLACE_IN_LINE.")
    val searchText: String?,

    @property:LLMDescription("Replacement substring for find-and-replace. Used only for REPLACE_IN_LINE.")
    val replacementText: String?
)

@Serializable
@LLMDescription("A wrapper for a list of file modifications")
data class ModificationList(
    @property:LLMDescription("List of modifications sorted by startLine descending (highest line numbers first)")
    val modifications: List<Modification>
)
