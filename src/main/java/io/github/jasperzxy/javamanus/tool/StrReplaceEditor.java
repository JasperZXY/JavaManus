package io.github.jasperzxy.javamanus.tool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.jasperzxy.javamanus.exception.ToolError;

/**
 * 文件查看/创建/编辑工具。
 * 对应 OpenManus 的 StrReplaceEditor，支持 view/create/str_replace/insert/undo_edit。
 */
public class StrReplaceEditor extends BaseTool {

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

    private final Map<Path, Deque<String>> fileHistory = new HashMap<>();

    public StrReplaceEditor() {
        super("str_replace_editor", DESCRIPTION, Map.of(
                "type", "object",
                "properties", Map.of(
                        "command", Map.of(
                                "description", "The commands to run. Allowed options are: `view`, `create`, `str_replace`, `insert`, `undo_edit`.",
                                "enum", List.of("view", "create", "str_replace", "insert", "undo_edit"),
                                "type", "string"
                        ),
                        "path", Map.of(
                                "description", "Absolute path to file or directory.",
                                "type", "string"
                        ),
                        "file_text", Map.of(
                                "description", "Required parameter of `create` command, with the content of the file to be created.",
                                "type", "string"
                        ),
                        "old_str", Map.of(
                                "description", "Required parameter of `str_replace` command containing the string in `path` to replace.",
                                "type", "string"
                        ),
                        "new_str", Map.of(
                                "description", "Optional parameter of `str_replace` command containing the new string. " +
                                        "Required parameter of `insert` command containing the string to insert.",
                                "type", "string"
                        ),
                        "insert_line", Map.of(
                                "description", "Required parameter of `insert` command. The `new_str` will be inserted AFTER the line `insert_line` of `path`.",
                                "type", "integer"
                        ),
                        "view_range", Map.of(
                                "description", "Optional parameter of `view` command. If provided, the file will be shown in the indicated line number range, e.g. [11, 12].",
                                "type", "array"
                        )
                ),
                "required", List.of("command", "path")
        ));
    }

    @Override
    public String execute(Map<String, Object> args) {
        String command = strArg(args, "command");
        String pathStr = strArg(args, "path");
        if (command == null || pathStr == null) {
            throw new ToolError("Parameters 'command' and 'path' are required");
        }

        Path path = Path.of(pathStr);
        if (!path.isAbsolute()) {
            throw new ToolError("The path " + path + " is not an absolute path");
        }

        return switch (command) {
            case "view" -> view(path, args);
            case "create" -> create(path, args);
            case "str_replace" -> strReplace(path, args);
            case "insert" -> insert(path, args);
            case "undo_edit" -> undoEdit(path);
            default -> throw new ToolError("Unrecognized command: " + command);
        };
    }

    private String view(Path path, Map<String, Object> args) {
        if (!Files.exists(path)) {
            throw new ToolError("The path " + path + " does not exist.");
        }
        try {
            if (Files.isDirectory(path)) {
                return viewDirectory(path);
            }
            List<Integer> range = rangeArg(args, "view_range");
            return viewFile(path, range);
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

    private String create(Path path, Map<String, Object> args) {
        String fileText = strArg(args, "file_text");
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

    private String strReplace(Path path, Map<String, Object> args) {
        String oldStr = strArg(args, "old_str");
        if (oldStr == null) {
            throw new ToolError("Parameter `old_str` is required for command: str_replace");
        }
        String newStr = strArg(args, "new_str");
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

    private String insert(Path path, Map<String, Object> args) {
        Object insertLineObj = args.get("insert_line");
        if (insertLineObj == null) {
            throw new ToolError("Parameter `insert_line` is required for command: insert");
        }
        int insertLine = ((Number) insertLineObj).intValue();
        String newStr = strArg(args, "new_str");
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

    private static String strArg(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v != null ? v.toString() : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Integer> rangeArg(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v instanceof List<?> list) {
            return list.stream().map(o -> ((Number) o).intValue()).toList();
        }
        return null;
    }
}
