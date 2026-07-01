package com.androidrev.guistudio.ui;

import com.androidrev.guistudio.adb.AdbClient;
import com.androidrev.guistudio.adb.RemoteEntry;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/** Graphical browser for the connected device's filesystem via ADB shell. */
public final class DeviceFileBrowser extends BorderPane {
    @FunctionalInterface
    public interface AdbTaskRunner {
        void run(AdbTask task);
    }

    @FunctionalInterface
    public interface AdbTask {
        void run() throws Exception;
    }

    private final AppContext app;
    private final AdbTaskRunner taskRunner;
    private final TextField pathField = new TextField("/sdcard");
    private final Label statusLabel = new Label("未加载");
    private final ObservableList<RemoteEntry> entries = FXCollections.observableArrayList();
    private final TableView<RemoteEntry> table = new TableView<>(entries);

    private Consumer<String> actionLog;

    private DeviceFileBrowser(AppContext app, AdbTaskRunner taskRunner) {
        this.app = app;
        this.taskRunner = taskRunner;
        buildUi();
        app.getDeviceManager().addDeviceConnectListener(this::refresh);
    }

    public static DeviceFileBrowser create(AppContext app, AdbTaskRunner taskRunner) {
        return new DeviceFileBrowser(app, taskRunner);
    }

    public void setActionLog(Consumer<String> handler) {
        this.actionLog = handler;
    }

    public String getCurrentPath() {
        return AdbClient.normalizeRemotePath(pathField.getText());
    }

    public RemoteEntry getSelectedEntry() {
        return table.getSelectionModel().getSelectedItem();
    }

    public void refresh() {
        navigateTo(pathField.getText(), false);
    }

    private void buildUi() {
        statusLabel.setStyle("-fx-text-fill: #666;");

        Button upBtn = new Button("上级");
        Button refreshBtn = new Button("刷新");
        Button goBtn = new Button("前往");
        upBtn.setOnAction(e -> goUp());
        refreshBtn.setOnAction(e -> refresh());
        goBtn.setOnAction(e -> navigateTo(pathField.getText(), true));

        pathField.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                navigateTo(pathField.getText(), true);
            }
        });

        HBox pathBar = new HBox(6, upBtn, pathField, goBtn, refreshBtn);
        pathBar.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(pathField, Priority.ALWAYS);
        pathField.setMaxWidth(Double.MAX_VALUE);

        HBox shortcuts = new HBox(6,
                shortcutButton("/", () -> navigateTo("/", true)),
                shortcutButton("/sdcard", () -> navigateTo("/sdcard", true)),
                shortcutButton("/data/local/tmp", () -> navigateTo("/data/local/tmp", true)),
                shortcutButton("/storage", () -> navigateTo("/storage", true))
        );
        shortcuts.setAlignment(Pos.CENTER_LEFT);
        shortcuts.getChildren().forEach(node -> {
            HBox.setHgrow(node, Priority.ALWAYS);
            if (node instanceof Button btn) {
                btn.setMaxWidth(Double.MAX_VALUE);
                btn.setWrapText(true);
            }
        });

        TableColumn<RemoteEntry, String> nameCol = new TableColumn<>("名称");
        nameCol.setPrefWidth(220);
        nameCol.setCellValueFactory(data -> new SimpleStringProperty(formatName(data.getValue())));

        TableColumn<RemoteEntry, String> typeCol = new TableColumn<>("类型");
        typeCol.setPrefWidth(70);
        typeCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().typeLabel()));

        TableColumn<RemoteEntry, String> sizeCol = new TableColumn<>("大小");
        sizeCol.setPrefWidth(90);
        sizeCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().displaySize()));

        table.getColumns().setAll(nameCol, typeCol, sizeCol);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("目录为空或尚未加载"));

        MenuItem openItem = new MenuItem("打开");
        MenuItem copyPathItem = new MenuItem("复制路径");
        MenuItem uploadItem = new MenuItem("上传到此目录");
        MenuItem downloadItem = new MenuItem("下载");
        MenuItem mkdirItem = new MenuItem("新建文件夹");
        MenuItem deleteItem = new MenuItem("删除");
        MenuItem refreshItem = new MenuItem("刷新");
        ContextMenu contextMenu = new ContextMenu(openItem, copyPathItem, uploadItem, downloadItem,
                new SeparatorMenuItem(), mkdirItem, deleteItem, refreshItem);

        openItem.setOnAction(e -> {
            RemoteEntry selected = getSelectedEntry();
            if (selected != null && selected.isDirectory()) {
                navigateTo(selected.fullPath(getCurrentPath()), true);
            }
        });
        copyPathItem.setOnAction(e -> copySelectedPath());
        uploadItem.setOnAction(e -> uploadFiles());
        downloadItem.setOnAction(e -> downloadSelected());
        mkdirItem.setOnAction(e -> createDirectory());
        deleteItem.setOnAction(e -> deleteSelected());
        refreshItem.setOnAction(e -> refresh());

        table.setContextMenu(contextMenu);
        table.setOnContextMenuRequested(e -> {
            RemoteEntry selected = getSelectedEntry();
            boolean hasSelection = selected != null;
            openItem.setDisable(!hasSelection || !selected.isDirectory());
            copyPathItem.setDisable(!hasSelection);
            downloadItem.setDisable(!hasSelection || selected.isDirectory());
            deleteItem.setDisable(!hasSelection);
        });

        table.setRowFactory(tv -> {
            TableRow<RemoteEntry> row = new TableRow<>();
            row.setOnMouseClicked(ev -> {
                if (ev.getClickCount() == 2 && !row.isEmpty()) {
                    openEntry(row.getItem());
                }
            });
            return row;
        });

        HBox statusBar = new HBox(statusLabel);
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.setPadding(new Insets(4, 0, 0, 0));

        VBox top = new VBox(6, pathBar, shortcuts);
        top.setFillWidth(true);
        setTop(top);
        setCenter(table);
        setBottom(statusBar);
        BorderPane.setMargin(table, new Insets(6, 0, 0, 0));
        setPadding(new Insets(8));
        setMinWidth(300);
        setPrefHeight(360);
    }

    private static Button shortcutButton(String label, Runnable action) {
        Button btn = new Button(label);
        btn.setOnAction(e -> action.run());
        return btn;
    }

    private static String formatName(RemoteEntry entry) {
        if (entry == null) {
            return "";
        }
        return (entry.isDirectory() ? "[DIR] " : "[FILE] ") + entry.name();
    }

    private void goUp() {
        navigateTo(AdbClient.parentRemotePath(pathField.getText()), true);
    }

    private void navigateTo(String path, boolean userInitiated) {
        String target = AdbClient.normalizeRemotePath(path);
        pathField.setText(target);
        setStatus("加载中: " + target);
        entries.clear();

        taskRunner.run(() -> {
            try {
                var check = app.getAdb().hasDevice();
                if (!check.connected()) {
                    Platform.runLater(() -> {
                        setStatus("未连接设备");
                        if (userInitiated) {
                            app.getLogger().log(AppContext.SOURCE_ADB, "未检测到可用设备");
                        }
                    });
                    return;
                }
                var list = app.getAdb().listRemoteDirectory(target);
                Platform.runLater(() -> {
                    pathField.setText(target);
                    entries.setAll(list);
                    setStatus(String.format("%s — %d项", target, list.size()));
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    setStatus("加载失败: " + e.getMessage());
                    if (userInitiated) {
                        Logger.showError(app, AppContext.SOURCE_ADB, e);
                    }
                });
            }
        });
    }

    private void openEntry(RemoteEntry entry) {
        if (entry == null) {
            return;
        }
        if (entry.isDirectory()) {
            navigateTo(entry.fullPath(getCurrentPath()), true);
            return;
        }
        table.getSelectionModel().select(entry);
        downloadSelected();
    }

    private String resolveUploadTargetDir() {
        RemoteEntry selected = getSelectedEntry();
        if (selected != null && selected.isDirectory()) {
            return selected.fullPath(getCurrentPath());
        }
        return getCurrentPath();
    }

    private void copySelectedPath() {
        RemoteEntry selected = getSelectedEntry();
        if (selected == null) {
            app.getLogger().log(AppContext.SOURCE_ADB, "请先选择文件或文件夹");
            return;
        }
        String path = selected.fullPath(getCurrentPath());
        ClipboardContent content = new ClipboardContent();
        content.putString(path);
        Clipboard.getSystemClipboard().setContent(content);
    }

    private void uploadFiles() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择要上传的文件");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("所有文件", "*.*"));
        List<File> files = chooser.showOpenMultipleDialog(app.getStage());
        if (files == null || files.isEmpty()) {
            return;
        }

        String targetDir = resolveUploadTargetDir();
        setStatus("上传中: " + targetDir);

        taskRunner.run(() -> {
            int success = 0;
            StringBuilder log = new StringBuilder();
            for (File file : files) {
                String remote = joinRemotePath(targetDir, file.getName());
                try {
                    app.getAdb().push(file.getAbsolutePath(), remote);
                    success++;
                    log.append("$ adb push ").append(file.getAbsolutePath())
                            .append(" ").append(remote).append("\n上传成功: ")
                            .append(file.getName()).append('\n');
                } catch (Exception e) {
                    log.append("上传失败 ").append(file.getName())
                            .append(": ").append(e.getMessage()).append('\n');
                }
            }
            int finalSuccess = success;
            String message = log.toString().trim();
            Platform.runLater(() -> {
                if (!message.isBlank()) {
                    logAction(message);
                }
                app.getLogger().log(AppContext.SOURCE_ADB, "上传完成 (%d/%d)", finalSuccess, files.size());
                setStatus(String.format("上传完成 %d/%d → %s", finalSuccess, files.size(), targetDir));
                refresh();
            });
        });
    }

    private void downloadSelected() {
        RemoteEntry selected = getSelectedEntry();
        if (selected == null) {
            app.getLogger().log(AppContext.SOURCE_ADB, "请先选择要下载的文件");
            return;
        }
        if (selected.isDirectory()) {
            app.getLogger().log(AppContext.SOURCE_ADB, "暂不支持下载文件夹，请选择文件");
            return;
        }

        String remotePath = selected.fullPath(getCurrentPath());
        FileChooser chooser = new FileChooser();
        chooser.setTitle("保存到本地");
        chooser.setInitialFileName(selected.name());
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("所有文件", "*.*"));
        File dest = chooser.showSaveDialog(app.getStage());
        if (dest == null) {
            return;
        }

        Path localPath = dest.toPath();
        setStatus("下载中: " + selected.name());

        taskRunner.run(() -> {
            app.getAdb().pull(remotePath, localPath);
            Platform.runLater(() -> {
                String msg = "$ adb pull " + remotePath + " " + localPath.toAbsolutePath()
                        + "\n下载成功: " + selected.name();
                logAction(msg);
                app.getLogger().log(AppContext.SOURCE_ADB, "下载完成: %s", localPath.toAbsolutePath());
                setStatus("下载完成: " + localPath.toAbsolutePath());
            });
        });
    }

    private static String joinRemotePath(String dir, String name) {
        String base = AdbClient.normalizeRemotePath(dir);
        if ("/".equals(base)) {
            return "/" + name;
        }
        return base + "/" + name;
    }

    private void createDirectory() {
        TextInputDialog dialog = new TextInputDialog("NewFolder");
        dialog.setTitle("新建文件夹");
        dialog.setHeaderText("在当前目录下创建文件夹");
        dialog.setContentText("文件夹名称:");
        dialog.initOwner(app.getStage());
        dialog.showAndWait().ifPresent(name -> {
            name = name.trim();
            if (name.isEmpty()) {
                return;
            }
            if (name.contains("/")) {
                app.getLogger().log(AppContext.SOURCE_ADB, "文件夹名称不能包含 /");
                return;
            }
            String newPath = joinRemotePath(getCurrentPath(), name);
            taskRunner.run(() -> {
                app.getAdb().makeRemoteDirectory(newPath);
                Platform.runLater(() -> {
                    app.getLogger().log(AppContext.SOURCE_ADB, "已创建文件夹: %s", newPath);
                    refresh();
                });
            });
        });
    }

    private void deleteSelected() {
        RemoteEntry selected = getSelectedEntry();
        if (selected == null) {
            app.getLogger().log(AppContext.SOURCE_ADB, "请先选择要删除的文件或文件夹");
            return;
        }
        String path = selected.fullPath(getCurrentPath());
        if (!Dialogs.showConfirm(app.getStage(), "删除确认",
                "确定删除 " + path + " ?\n此操作不可撤销。")) {
            return;
        }
        taskRunner.run(() -> {
            app.getAdb().removeRemote(path);
            Platform.runLater(() -> {
                app.getLogger().log(AppContext.SOURCE_ADB, "已删除: %s", path);
                refresh();
            });
        });
    }

    private void logAction(String message) {
        if (actionLog != null) {
            actionLog.accept(message);
        }
    }

    private void setStatus(String text) {
        statusLabel.setText(text);
    }
}
