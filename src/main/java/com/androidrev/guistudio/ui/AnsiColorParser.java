package com.androidrev.guistudio.ui;

import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import java.util.regex.Pattern;

final class AnsiColorParser {
    private static final Color DEFAULT = Color.web("#d4d4d4");
    private static final Pattern ANSI_PATTERN = Pattern.compile("\u001B\\[[0-9;]*m");

    private AnsiColorParser() {
    }

    static String stripAnsi(String line) {
        if (line == null || line.indexOf('\u001B') < 0) {
            return line;
        }
        return ANSI_PATTERN.matcher(line).replaceAll("");
    }

    static Color colorForLogLevel(String line) {
        if (line == null || line.isEmpty()) {
            return DEFAULT;
        }
        return switch (line.charAt(0)) {
            case 'E', 'F' -> Color.web("#f44747");
            case 'W' -> Color.web("#cca700");
            case 'I' -> Color.web("#6a9955");
            case 'D' -> Color.web("#569cd6");
            case 'V' -> Color.web("#808080");
            default -> DEFAULT;
        };
    }

    static void appendLine(TextFlow flow, String line) {
        if (line == null || line.isEmpty()) {
            Text nl = new Text("\n");
            nl.setFill(DEFAULT);
            flow.getChildren().add(nl);
            return;
        }
        if (line.indexOf('\u001B') >= 0) {
            appendAnsiLine(flow, line);
            return;
        }
        Text text = new Text(line + "\n");
        text.setFill(colorForLogLevel(line));
        flow.getChildren().add(text);
    }

    private static void appendAnsiLine(TextFlow flow, String line) {
        Color color = DEFAULT;
        StringBuilder buf = new StringBuilder();

        int i = 0;
        while (i < line.length()) {
            if (line.charAt(i) == '\u001B' && i + 1 < line.length() && line.charAt(i + 1) == '[') {
                flushSegment(flow, buf, color);
                int end = line.indexOf('m', i + 2);
                if (end < 0) {
                    buf.append(line.charAt(i));
                    i++;
                    continue;
                }
                color = applySgr(color, line.substring(i + 2, end));
                i = end + 1;
            } else {
                buf.append(line.charAt(i));
                i++;
            }
        }
        flushSegment(flow, buf, color);
        Text nl = new Text("\n");
        nl.setFill(color);
        flow.getChildren().add(nl);
    }

    private static void flushSegment(TextFlow flow, StringBuilder buf, Color color) {
        if (buf.isEmpty()) {
            return;
        }
        Text text = new Text(buf.toString());
        text.setFill(color);
        flow.getChildren().add(text);
        buf.setLength(0);
    }

    private static Color applySgr(Color current, String codes) {
        Color color = current;
        for (String part : codes.split(";")) {
            if (part.isEmpty()) {
                continue;
            }
            try {
                color = mapCode(Integer.parseInt(part), color);
            } catch (NumberFormatException ignored) {
            }
        }
        return color;
    }

    private static Color mapCode(int code, Color current) {
        return switch (code) {
            case 0 -> DEFAULT;
            case 1 -> current; // bold — keep color
            case 30 -> Color.web("#808080");
            case 31 -> Color.web("#f44747");
            case 32 -> Color.web("#6a9955");
            case 33 -> Color.web("#cca700");
            case 34 -> Color.web("#569cd6");
            case 35 -> Color.web("#c586c0");
            case 36 -> Color.web("#4ec9b0");
            case 37 -> Color.web("#d4d4d4");
            case 90 -> Color.web("#808080");
            case 91 -> Color.web("#f44747");
            case 92 -> Color.web("#6a9955");
            case 93 -> Color.web("#dcdcaa");
            case 94 -> Color.web("#569cd6");
            case 95 -> Color.web("#c586c0");
            case 96 -> Color.web("#4ec9b0");
            case 97 -> Color.web("#ffffff");
            default -> current;
        };
    }
}
