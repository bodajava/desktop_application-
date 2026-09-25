package com.examhalls.controller;

import com.examhalls.model.Room;
import com.examhalls.model.RoomStatus;
import com.examhalls.service.MasterDataService;
import com.examhalls.service.ServiceRegistry;
import com.examhalls.ui.BusyOverlay;
import com.examhalls.ui.CrudPanel;
import com.examhalls.ui.FormDialog;
import com.examhalls.ui.Navigator;
import com.examhalls.util.Formats;
import com.examhalls.util.Messages;
import javafx.fxml.FXML;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Optional;

/** Screen 3 — rooms & halls (soft delete; the trigger blocks changes that break upcoming exams). */
public class RoomsController {

    @FXML private VBox container;
    @FXML private BusyOverlay busy;

    private final MasterDataService data = ServiceRegistry.get().masterDataService();
    private CrudPanel<Room> rooms;

    @FXML
    private void initialize() {
        rooms = new CrudPanel<Room>(busy)
                .column("ops.col.room", Room::roomCode, 110)
                .column("ops.col.building", Room::building, 220)
                .column("master.field.regularCapacity", r -> String.valueOf(r.regularCapacity()), 140)
                .column("master.field.examCapacity", r -> String.valueOf(r.examCapacity()), 140)
                .column("master.field.spacing", r -> spacing(r), 120)
                .badgeColumn("master.field.status", r -> Formats.enumLabel("roomStatus", r.status()),
                        r -> r.status() == RoomStatus.AVAILABLE ? "badge-staffed" : "badge-blocked", 140)
                .searchable(CrudPanel.anyOf(Room::roomCode, Room::building))
                .loader(data::rooms)
                .onNew(() -> edit(null))
                .onEdit(this::edit)
                .onDelete(r -> Messages.get("master.room.delete", r.roomCode()), "master.room.deleteText",
                        r -> data.deleteRoom(r.roomId()), r -> Messages.get("master.toast.archived", r.roomCode()));
        VBox.setVgrow(rooms, Priority.ALWAYS);
        container.getChildren().add(rooms);
        rooms.reload();
    }

    private void edit(Room r) {
        boolean isNew = r == null;
        FormDialog f = new FormDialog(isNew ? "master.room.new" : "master.room.edit")
                .text("code", "master.field.roomCode", isNew ? "" : r.roomCode(), true)
                .text("building", "master.field.building", isNew ? "" : r.building(), true)
                .integer("regular", "master.field.regularCapacity", 1, 9999, isNew ? 40 : r.regularCapacity())
                .integer("exam", "master.field.examCapacity", 1, 9999, isNew ? 24 : r.examCapacity())
                .combo("status", "master.field.status", List.of(RoomStatus.values()),
                        s -> Formats.enumLabel("roomStatus", s), isNew ? RoomStatus.AVAILABLE : r.status(), true)
                .note(Messages.get("master.room.rule"));
        f.validator(x -> x.integer("exam") > x.integer("regular")
                        ? Optional.of(Messages.get("constraint.CK_ROOMS_EXAM_CAP")) : Optional.empty())
                .onSave(() -> data.saveRoom(new Room(isNew ? null : r.roomId(), f.text("code"), f.text("building"),
                        f.integer("regular"), f.integer("exam"), f.value("status"))));
        if (f.showAndWait(Navigator.get().stage())) {
            Navigator.get().shell().toast(Messages.get("master.toast.saved"));
            rooms.reload();
        }
    }

    /** Share of regular seats used in exams, e.g. "60%". */
    private static String spacing(Room r) {
        return r.regularCapacity() == 0 ? "" : Math.round(100.0 * r.examCapacity() / r.regularCapacity()) + "%";
    }
}
