package io.github.jasperzxy.javamanus.tool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import io.github.jasperzxy.javamanus.exception.ToolError;

/**
 * 文件查看/创建/编辑工具。
 * 对应 OpenManus 的 StrReplaceEditor，支持 view/create/str_replace/insert/undo_edit。
 * <p>
 * 安全：所有操作路径被限制在 {@code workspaceRoot} 目录内，禁止越权访问。
 */
public class StrReplaceEditor {

    private static final String DESCRIPTION = """
            Custom editing tool for viewing, creating and editing files
            * State is persistent across command calls and discussions with the user
            * If `path` is a file, `view` displays the result of applying `cat -n`. \
            If `path` is a directory, `view` lists non-hidden files and directories up to 2 levels deep
            * The `create` command cannot be used if the specified `path` already exists as a file
            * If a `command` generates a long output, it will be truncated and marked with `<response clipped>`
            * The `undo_edit` command will revert the last edit made to the file at `path`

            Notes for using the `str_replace` command:
            * The `old_str` parameter should match EXACTLY one or more consecutive lines from the original file. \
            Be mindful of whitespaces!
            * If the `old_str` parameter is not unique in the file, the replacement will not be performed. \
            Make sure to include enough context in `old_str` to make it unique
            * The `new_str` parameter should contain the edited lines that should replace the `old_str`
            """;

    private static final int SNIPPET_LINES = 4;
    private static final int MAX_RESPONSE_LEN = 16000;
    private static final String TRUNCATED_MESSAGE =
            "<response clipped><NOTE>To save on context only part of this file has been shown to you. " +
            "You should retry this tool after you have searched inside the file with `grep -n` " +
            "in order to find the line numbers of what you are looking for.</NOTE>";

    private final Map<Path, Deque<String>> fileHistory = new ConcurrentHashMap<>();
    private final Path workspaceRoot;

    public StrReplaceEditor() {
        this(null);
    }

    public StrReplaceEditor(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot != null ? workspaceRoot.toAbsolutePath().normalize() : null;
    }

    @Tool(name = "str_replace_editor", description = DESCRIPTION)
    public String strReplaceEditor(
            @ToolParam(description = "The commands to run. Allowed options are: `view`, `create`, `str_replace`, `insert`, `undo_edit`.", required = true) String command,
            @ToolParam(description = "Absolute path to file or directory.", required = true) String path,
            @ToolParam(description = "Required parameter of `create` command, with the content of the file to be created.", required = false) String fileText,
            @ToolParam(description = "Required parameter of `str_replace` command containing the string in `path` to replace.", required = false) String oldStr,
            @ToolParam(description = "Optional parameter of `str_replace` command containing the new string. Required parameter of `insert` command containing the string to insert.", required = false) String newStr,
            @ToolParam(description = "Required parameter of `insert` command. The `new_str` will be inserted AFTER the line `insert_line` of `path`.", required = false) Integer insertLine,
            @ToolParam(description = "Optional parameter of `view` command. If provided, the file will be shown in the indicated line number range, e.g. [11, 12].", required = false) List<Integer> viewRange) {

        if (command == null || path == null) {
            throw new ToolError("Parameters 'command' and 'path' are required");
        }

        Path filePath = Path.of(path);
        if (!filePath.isAbsolute()) {
            throw new ToolError("The path " + filePath + " is not an absolute path");
        }
        checkWithinWorkspace(filePath);

        return switch (command) {
            case "view" -> view(filePath, viewRange);
            case "create" -> create(filePath, fileText);
            case "str_replace" -> strReplace(filePath, oldStr, newStr);
            case "insert" -> insert(filePath, insertLine, newStr);
            case "undo_edit" -> undoEdit(filePath);
            default -> throw new ToolError("Unrecognized command: " + command);
        };
    }

    /**
     * 校验路径是否在 workspaceRoot 内。若 workspaceRoot 为 null 则不限制。
     */
    private void checkWithinWorkspace(Path path) {
        if (workspaceRoot == null) {
            return;
        }
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(workspaceRoot)) {
            throw new ToolError("Access denied: path " + normalized
                    + " is outside the workspace " + workspaceRoot);
        }
    }

    private String view(Path path, List<Integer> viewRange) {
        if (!Files.exists(path)) {
            throw new ToolError("The path " + path + " does not exist.");
        }
        try {
            if (Files.isDirectory(path)) {
                return viewDirectory(path);
            }
            return viewFile(path, viewRange);
        } catch (IOException e) {
            throw new ToolError("Failed to view " + path + ": " + e.getMessage(), e);
        }
    }

    private String viewDirectory(Path dir) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("Here's the files and directories up to 2 levels deep in ")
                .append(dir).append(", excluding hidden items:\n");
        listDirectory(dir, dir, 0, sb);
        return sb.toString();
    }

    private void listDirectory(Path root, Path current, int depth, StringBuilder sb) throws IOException {
        if (depth > 2) return;
        try (var stream = Files.list(current)) {
            List<Path> entries = stream
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .sorted()
                    .toList();
            for (Path entry : entries) {
                sb.append(root.relativize(entry)).append("\n");
                if (Files.isDirectory(entry)) {
                    listDirectory(root, entry, depth + 1, sb);
                }
            }
        }
    }

    private String viewFile(Path file, List<Integer> range) throws IOException {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        int initLine = 1;
        if (range != null && range.size() == 2) {
            int start = range.get(0);
            int end = range.get(1);
            String[] lines = content.split("\n", -1);
            if (start < 1 || start > lines.length) {
                throw new ToolError("Invalid view_range start: " + start);
            }
            if (end != -1 && (end > lines.length || end < start)) {
                throw new ToolError("Invalid view_range end: " + end);
            }
            initLine = start;
            content = end == -1
                    ? String.join("\n", java.util.Arrays.copyOfRange(lines, start - 1, lines.length))
                    : String.join("\n", java.util.Arrays.copyOfRange(lines, start - 1, end));
        }
        return makeOutput(content, file.toString(), initLine);
    }

    private String create(Path path, String fileText) {
        if (fileText == null) {
            throw new ToolError("Parameter `file_text` is required for command: create");
        }
        if (Files.exists(path)) {
            throw new ToolError("File already exists at: " + path + ". Cannot overwrite files using command `create`.");
        }
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, fileText, StandardCharsets.UTF_8);
            pushHistory(path, fileText);
            return "File created successfully at: " + path;
        } catch (IOException e) {
            throw new ToolError("Failed to create file " + path + ": " + e.getMessage(), e);
        }
    }

    private String strReplace(Path path, String oldStr, String newStr) {
        if (oldStr == null) {
            throw new ToolError("Parameter `old_str` is required for command: str_replace");
        }
        if (newStr == null) newStr = "";

        if (!Files.exists(path)) {
            throw new ToolError("The path " + path + " does not exist.");
        }
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            int occurrences = countOccurrences(content, oldStr);
            if (occurrences == 0) {
                throw new ToolError("No replacement was performed, old_str did not appear verbatim in " + path + ".");
            }
            if (occurrences > 1) {
                throw new ToolError("No replacement was performed. Multiple occurrences of old_str in " + path +
                        ". Please ensure it is unique.");
            }
            String newContent = content.replace(oldStr, newStr);
            Files.writeString(path, newContent, StandardCharsets.UTF_8);
            pushHistory(path, content);

            int replacementLine = content.split(oldStr, 2)[0].split("\n", -1).length - 1;
            int startLine = Math.max(0, replacementLine - SNIPPET_LINES);
            String[] newLines = newContent.split("\n", -1);
            int endLine = Math.min(newLines.length, replacementLine + SNIPPET_LINES + newStr.split("\n", -1).length);
            String snippet = String.join("\n", java.util.Arrays.copyOfRange(newLines, startLine, endLine));

            return "The file " + path + " has been edited. " +
                    makeOutput(snippet, "a snippet of " + path, startLine + 1) +
                    "Review the changes and make sure they are as expected. Edit the file again if necessary.";
        } catch (IOException e) {
            throw new ToolError("Failed to edit " + path + ": " + e.getMessage(), e);
        }
    }

    private String insert(Path path, Integer insertLine, String newStr) {
        if (insertLine == null) {
            throw new ToolError("Parameter `insert_line` is required for command: insert");
        }
        if (newStr == null) {
            throw new ToolError("Parameter `new_str` is required for command: insert");
        }

        if (!Files.exists(path)) {
            throw new ToolError("The path " + path + " does not exist.");
        }
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            String[] lines = content.split("\n", -1);
            if (insertLine < 0 || insertLine > lines.length) {
                throw new ToolError("Invalid `insert_line` parameter: " + insertLine);
            }
            String[] newStrLines = newStr.split("\n", -1);
            String[] newLines = new String[lines.length + newStrLines.length];
            System.arraycopy(lines, 0, newLines, 0, insertLine);
            System.arraycopy(newStrLines, 0, newLines, insertLine, newStrLines.length);
            System.arraycopy(lines, insertLine, newLines, insertLine + newStrLines.length, lines.length - insertLine);

            String newContent = String.join("\n", newLines);
            Files.writeString(path, newContent, StandardCharsets.UTF_8);
            pushHistory(path, content);

            return "The file " + path + " has been edited.\n" +
                    "Review the changes and make sure they are as expected (correct indentation, no duplicate lines, etc).";
        } catch (IOException e) {
            throw new ToolError("Failed to insert into " + path + ": " + e.getMessage(), e);
        }
    }

    private String undoEdit(Path path) {
        Deque<String> history = fileHistory.get(path);
        if (history == null || history.isEmpty()) {
            throw new ToolError("No edit history found for " + path + ".");
        }
        String oldContent = history.pop();
        try {
            Files.writeString(path, oldContent, StandardCharsets.UTF_8);
            return "Last edit to " + path + " undone successfully.";
        } catch (IOException e) {
            throw new ToolError("Failed to undo edit on " + path + ": " + e.getMessage(), e);
        }
    }

    private void pushHistory(Path path, String content) {
        fileHistory.computeIfAbsent(path, k -> new ArrayDeque<>()).push(content);
    }

    private String makeOutput(String content, String descriptor, int initLine) {
        content = maybeTruncate(content);
        String[] lines = content.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            sb.append(String.format("%6d\t%s%n", i + initLine, lines[i]));
        }
        return "Here's the result of running `cat -n` on " + descriptor + ":\n" + sb;
    }

    private String maybeTruncate(String content) {
        if (content.length() <= MAX_RESPONSE_LEN) {
            return content;
        }
        return content.substring(0, MAX_RESPONSE_LEN) + TRUNCATED_MESSAGE;
    }

    private static int countOccurrences(String str, String sub) {
        int count = 0;
        int idx = 0;
        while ((idx = str.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }
}
