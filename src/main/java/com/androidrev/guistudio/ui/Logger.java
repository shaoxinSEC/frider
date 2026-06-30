package com.androidrev.guistudio.ui;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class Logger {
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int MAX_VISIBLE_LINES = 2_000;
    private static final int MAX_PENDING_LINES = 5_000;
    private static final int MAX_LINES_PER_FLUSH = 60;
    private static final long FLUSH_INTERVAL_MS = 100;
    private static final int SCROLL_EVERY_N_FLUSHES = 3;
    private static final String LOG_STYLE =
            "-fx-font-family: 'Consolas', 'Courier New', monospace; -fx-font-size: 12px;";
    private static final Set<String> SHOWN_DIALOG_MESSAGES = ConcurrentHashMap.newKeySet();

    private final ObservableList<String> lines = FXCollections.observableArrayList();
    private final ListView<String> listView = new ListView<>(lines);
    private final ThrottledLineBuffer buffer;
    private int flushCount;

    public Logger() {
        listView.setFixedCellSize(18);
        listView.setStyle(LOG_STYLE);
        listView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item);
            }
        });
        VBox.setVgrow(listView, Priority.ALWAYS);

        buffer = new ThrottledLineBuffer(this::appendBatch, MAX_PENDING_LINES, MAX_LINES_PER_FLUSH, FLUSH_INTERVAL_MS);

        MenuItem selectAllItem = new MenuItem("全选");
        selectAllItem.setOnAction(e -> listView.getSelectionModel().selectAll());

        MenuItem copyItem = new MenuItem("复制");
        copyItem.setOnAction(e -> copySelection());

        MenuItem clearItem = new MenuItem("清除日志");
        clearItem.setOnAction(e -> clear());

        ContextMenu menu = new ContextMenu(selectAllItem, copyItem, clearItem);
        listView.setContextMenu(menu);
    }

    private void copySelection() {
        var selection = listView.getSelectionModel().getSelectedItems();
        if (selection != null && !selection.isEmpty()) {
            ClipboardContent content = new ClipboardContent();
            content.putString(String.join("\n", selection));
            Clipboard.getSystemClipboard().setContent(content);
            return;
        }
        if (lines.isEmpty()) {
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(String.join("\n", lines));
        Clipboard.getSystemClipboard().setContent(content);
    }

    public ListView<String> getWidget() {
        return listView;
    }

    /** Thread-safe: may be called from any thread. */
    public void log(String source, String format, Object... args) {
        String msg = String.format(format, args);
        String line = String.format("[%s] [%s] %s", LocalTime.now().format(TIME_FMT), source, msg);
        buffer.append(line);
    }

    public void clear() {
        buffer.clearPending();
        flushCount = 0;
        Platform.runLater(lines::clear);
    }

    private void appendBatch(List<String> batch) {
        lines.addAll(batch);
        trimLines();
        if (++flushCount % SCROLL_EVERY_N_FLUSHES == 0 && !lines.isEmpty()) {
            listView.scrollTo(lines.size() - 1);
        }
    }

    private void trimLines() {
        int extra = lines.size() - MAX_VISIBLE_LINES;
        if (extra > 0) {
            lines.remove(0, extra);
        }
    }

    public static void clearShownDialogs() {
        SHOWN_DIALOG_MESSAGES.clear();
    }

    public static void showError(AppContext app, String source, Exception err) {
        if (err == null) {
            return;
        }
        String message = err.getMessage();
        if (message == null || message.isBlank()) {
            message = err.toString();
        }
        app.getLogger().log(source, "错误: %s", message);
        if (SHOWN_DIALOG_MESSAGES.add(message)) {
            String dialogMessage = message;
            Platform.runLater(() -> Dialogs.showError(app.getStage(), dialogMessage));
        }
    }
}
