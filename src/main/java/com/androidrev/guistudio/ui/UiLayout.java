package com.androidrev.guistudio.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.io.File;

public final class UiLayout {
    public static final double GAP = 8;
    public static final Insets PADDING = new Insets(12);
    public static final Insets COMPACT_PADDING = new Insets(8);

    private UiLayout() {
    }

    public static void fillWidth(Region region) {
        if (region == null) {
            return;
        }
        region.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(region, Priority.ALWAYS);
        VBox.setVgrow(region, Priority.SOMETIMES);
        GridPane.setHgrow(region, Priority.ALWAYS);
    }

    public static void fillHeight(Region region) {
        if (region == null) {
            return;
        }
        region.setMaxHeight(Double.MAX_VALUE);
        VBox.setVgrow(region, Priority.ALWAYS);
    }

    public static GridPane formGrid() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(GAP);
        grid.getStyleClass().add("form-grid");
        ColumnConstraints labelCol = new ColumnConstraints();
        labelCol.setMinWidth(100);
        labelCol.setPrefWidth(140);
        ColumnConstraints fieldCol = new ColumnConstraints();
        fieldCol.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(labelCol, fieldCol);
        return grid;
    }

    public static int addRow(GridPane grid, int row, String label, Region field) {
        if (label != null && !label.isBlank()) {
            Label labelNode = new Label(label);
            labelNode.getStyleClass().add("form-label");
            grid.add(labelNode, 0, row);
        } else {
            GridPane.setColumnSpan(field, 2);
        }
        fillWidth(field);
        grid.add(field, label == null || label.isBlank() ? 0 : 1, row);
        return row + 1;
    }

    public static int addSection(GridPane grid, int row, String title) {
        Label header = new Label(title);
        header.getStyleClass().add("section-header");
        grid.add(header, 0, row, 2, 1);
        return row + 1;
    }

    public static HBox toolbar(Node... items) {
        HBox bar = new HBox(GAP, items);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("tool-bar");
        return bar;
    }

    public static HBox pathField(AppContext app, TextField field, boolean file) {
        Button browse = new Button("浏览");
        browse.setOnAction(e -> {
            if (file) {
                javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
                chooser.setTitle("选择文件");
                initialDirectory(field).ifPresent(chooser::setInitialDirectory);
                File selected = chooser.showOpenDialog(app.getStage());
                if (selected != null) {
                    field.setText(selected.getAbsolutePath());
                }
            } else {
                javafx.stage.DirectoryChooser chooser = new javafx.stage.DirectoryChooser();
                chooser.setTitle("选择目录");
                initialDirectory(field).ifPresent(chooser::setInitialDirectory);
                File selected = chooser.showDialog(app.getStage());
                if (selected != null) {
                    field.setText(selected.getAbsolutePath());
                }
            }
        });
        fillWidth(field);
        HBox row = new HBox(GAP, field, browse);
        fillWidth(row);
        return row;
    }

    public static ScrollPane scroll(Node content) {
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("content-scroll");
        return scroll;
    }

    public static VBox panel(Node... children) {
        VBox box = new VBox(GAP, children);
        box.setPadding(PADDING);
        box.setFillWidth(true);
        box.getStyleClass().add("content-panel");
        return box;
    }

    public static void compact(Control control) {
        if (control != null) {
            control.setMinHeight(Region.USE_PREF_SIZE);
        }
    }

    private static java.util.Optional<File> initialDirectory(TextField field) {
        if (field.getText().isBlank()) {
            return java.util.Optional.empty();
        }
        File initial = new File(field.getText().trim());
        if (initial.isDirectory()) {
            return java.util.Optional.of(initial);
        }
        if (initial.getParentFile() != null && initial.getParentFile().isDirectory()) {
            return java.util.Optional.of(initial.getParentFile());
        }
        return java.util.Optional.empty();
    }
}
