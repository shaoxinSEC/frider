package com.androidrev.guistudio.ui;

import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import com.androidrev.guistudio.AppMetadata;

public final class Dialogs {
    private Dialogs() {
    }

    private static void initOwnerIfReady(Dialog<?> dialog, Stage owner) {
        if (owner != null && owner.getScene() != null) {
            dialog.initOwner(owner);
        }
    }

    public static void showError(Stage owner, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
        initOwnerIfReady(alert, owner);
        alert.setHeaderText(null);
        alert.showAndWait();
    }

    public static record TextFormResult(boolean ok, String name, String content) {
    }

    public static TextFormResult showNewScriptDialog(Stage owner) {
        Dialog<TextFormResult> dialog = new Dialog<>();
        dialog.setTitle("新建Frida脚本");
        initOwnerIfReady(dialog, owner);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        TextField nameField = new TextField();
        nameField.setPromptText("example.js");
        TextArea contentArea = new TextArea();
        contentArea.setPromptText("// 脚本描述\nJava.perform(function() {\n});");
        contentArea.setPrefRowCount(10);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.addRow(0, new javafx.scene.control.Label("文件名"), nameField);
        grid.addRow(1, new javafx.scene.control.Label("内容"), contentArea);
        GridPane.setVgrow(contentArea, Priority.ALWAYS);
        GridPane.setHgrow(nameField, Priority.ALWAYS);
        GridPane.setHgrow(contentArea, Priority.ALWAYS);
        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(button -> {
            if (button != ButtonType.OK) {
                return new TextFormResult(false, "", "");
            }
            return new TextFormResult(true, nameField.getText(), contentArea.getText());
        });
        return dialog.showAndWait().orElse(new TextFormResult(false, "", ""));
    }

    public static TextFormResult showEditScriptDialog(Stage owner, String name, String content) {
        Dialog<TextFormResult> dialog = new Dialog<>();
        dialog.setTitle("编辑Frida脚本");
        initOwnerIfReady(dialog, owner);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        TextField nameField = new TextField(name);
        nameField.setEditable(false);
        TextArea contentArea = new TextArea(content);
        contentArea.setPrefRowCount(14);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.addRow(0, new javafx.scene.control.Label("文件名"), nameField);
        grid.addRow(1, new javafx.scene.control.Label("内容"), contentArea);
        GridPane.setVgrow(contentArea, Priority.ALWAYS);
        GridPane.setHgrow(nameField, Priority.ALWAYS);
        GridPane.setHgrow(contentArea, Priority.ALWAYS);
        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(button -> {
            if (button != ButtonType.OK) {
                return new TextFormResult(false, "", "");
            }
            return new TextFormResult(true, nameField.getText(), contentArea.getText());
        });
        return dialog.showAndWait().orElse(new TextFormResult(false, "", ""));
    }

    public static boolean showConfirm(Stage owner, String title, String message) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.OK, ButtonType.CANCEL);
        alert.setTitle(title);
        initOwnerIfReady(alert, owner);
        alert.setHeaderText(null);
        return alert.showAndWait().filter(ButtonType.OK::equals).isPresent();
    }

    public static void showAboutDialog(Stage owner) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("关于 " + AppMetadata.NAME);
        initOwnerIfReady(dialog, owner);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        Label titleLabel = new Label(AppMetadata.NAME);
        titleLabel.setStyle("-fx-font-size: 22px; -fx-font-weight: bold;");
        Label taglineLabel = new Label(AppMetadata.TAGLINE);
        taglineLabel.setStyle("-fx-text-fill: #666;");
        Label versionLabel = new Label("当前版本：" + AppMetadata.VERSION);
        Label authorLabel = new Label("开发作者：" + AppMetadata.AUTHOR);

        VBox titleBox = new VBox(4, titleLabel, taglineLabel, versionLabel, authorLabel);

        HBox header = new HBox(16, titleBox);
        header.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        var logoUrl = Dialogs.class.getResource("/logo.jpg");
        if (logoUrl != null) {
            ImageView logoView = new ImageView(new Image(logoUrl.toExternalForm(), 64, 64, true, true));
            header.getChildren().add(0, logoView);
        }

        VBox content = new VBox(12,
                header,
                section("应用场景", AppMetadata.SCENARIOS),
                section("主要功能", AppMetadata.FEATURES),
                section("更新日志", AppMetadata.UPDATE_LOG));
        content.setPadding(new Insets(8, 4, 4, 4));
        content.setPrefWidth(480);
        dialog.getDialogPane().setContent(content);
        dialog.showAndWait();
    }

    private static VBox section(String heading, String body) {
        Label headingLabel = new Label(heading);
        headingLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");
        Label bodyLabel = new Label(body.trim());
        bodyLabel.setWrapText(true);
        bodyLabel.setStyle("-fx-text-fill: #333;");
        return new VBox(6, headingLabel, bodyLabel);
    }
}
