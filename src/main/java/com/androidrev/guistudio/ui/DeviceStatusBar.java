package com.androidrev.guistudio.ui;

import com.androidrev.guistudio.adb.AdbDevice;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.util.Callback;

import java.util.List;
import java.util.function.Consumer;

public final class DeviceStatusBar extends HBox {
    public enum Status {
        IDLE("#e6a700"),
        CONNECTED("#2ecc71"),
        ERROR("#e74c3c");

        private final String color;

        Status(String color) {
            this.color = color;
        }

        String color() {
            return color;
        }
    }

    private final Label dot = new Label("●");
    private final Label serialLabel = new Label();
    private final ComboBox<AdbDevice> deviceCombo = new ComboBox<>();
    private Consumer<String> onDeviceSelected;
    private boolean suppressSelection;
    private String shownSerial = "";

    public DeviceStatusBar() {
        super(6);
        setAlignment(Pos.CENTER_LEFT);
        dot.setStyle("-fx-font-size: 14px; -fx-text-fill: #e6a700;");
        serialLabel.setStyle("-fx-text-fill: #333;");
        deviceCombo.setPrefWidth(280);
        deviceCombo.setMaxWidth(400);
        deviceCombo.setVisible(false);
        deviceCombo.setManaged(false);
        deviceCombo.setCellFactory(deviceCellFactory());
        deviceCombo.setButtonCell(deviceCellFactory().call(null));
        deviceCombo.setOnAction(e -> {
            if (suppressSelection) {
                return;
            }
            AdbDevice selected = deviceCombo.getSelectionModel().getSelectedItem();
            if (selected != null && onDeviceSelected != null) {
                onDeviceSelected.accept(selected.serial());
            }
        });
        getChildren().addAll(dot, serialLabel, deviceCombo);
    }

    public void setOnDeviceSelected(Consumer<String> handler) {
        this.onDeviceSelected = handler;
    }

    public void update(Status status, List<AdbDevice> devices, String selectedSerial) {
        Platform.runLater(() -> apply(status, devices, selectedSerial));
    }

    private void apply(Status status, List<AdbDevice> devices, String selectedSerial) {
        dot.setStyle("-fx-font-size: 14px; -fx-text-fill: " + status.color() + ";");

        if (status == Status.IDLE || devices.isEmpty()) {
            serialLabel.setText("");
            serialLabel.setVisible(true);
            serialLabel.setManaged(true);
            deviceCombo.setVisible(false);
            deviceCombo.setManaged(false);
            deviceCombo.getItems().clear();
            shownSerial = "";
            return;
        }

        AdbDevice current = devices.stream()
                .filter(d -> d.serial().equals(selectedSerial))
                .findFirst()
                .orElse(devices.get(0));

        if (devices.size() > 1) {
            serialLabel.setVisible(false);
            serialLabel.setManaged(false);
            deviceCombo.setVisible(true);
            deviceCombo.setManaged(true);
            suppressSelection = true;
            deviceCombo.setItems(FXCollections.observableArrayList(devices));
            deviceCombo.getSelectionModel().select(current);
            suppressSelection = false;
            shownSerial = current.serial();
        } else {
            deviceCombo.setVisible(false);
            deviceCombo.setManaged(false);
            deviceCombo.getItems().clear();
            serialLabel.setVisible(true);
            serialLabel.setManaged(true);
            if (!current.serial().equals(shownSerial)) {
                serialLabel.setText(current.serial());
                shownSerial = current.serial();
            }
        }
    }

    private static Callback<ListView<AdbDevice>, ListCell<AdbDevice>> deviceCellFactory() {
        return list -> new ListCell<>() {
            @Override
            protected void updateItem(AdbDevice item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item.displayModel());
                }
            }
        };
    }
}
