package com.examhalls.controller;

import com.examhalls.model.SupervisionRoster;
import com.examhalls.security.AuthenticatedUser;
import com.examhalls.security.UserSession;
import com.examhalls.service.ServiceRegistry;
import com.examhalls.ui.Alerts;
import com.examhalls.ui.BusyOverlay;
import com.examhalls.ui.Exports;
import com.examhalls.util.Formats;
import com.examhalls.ui.FxAsync;
import com.examhalls.ui.Navigator;
import com.examhalls.util.Messages;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;

import java.time.LocalDate;
import java.util.List;

/** A teacher's own upcoming supervision duties. */
public class MyDutiesController {

    @FXML private Label headline;
    @FXML private Label subline;
    @FXML private Label emptyLabel;
    @FXML private TableView<SupervisionRoster> dutyTable;
    @FXML private TableColumn<SupervisionRoster, String> dateCol;
    @FXML private TableColumn<SupervisionRoster, String> periodCol;
    @FXML private TableColumn<SupervisionRoster, String> courseCol;
    @FXML private TableColumn<SupervisionRoster, String> roomCol;
    @FXML private TableColumn<SupervisionRoster, String> roleCol;
    @FXML private Button exportButton;
    @FXML private BusyOverlay busy;

    @FXML
    private void initialize() {
        dateCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(Formats.date(c.getValue().examDate())));
        periodCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().periodName()));
        courseCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().courseCode()));
        roomCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().roomCode()));
        roleCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(
                Formats.enumLabel("supervisionRole", c.getValue().roleType())));

        AuthenticatedUser me = UserSession.get().requireUser();
        headline.setText(Messages.get("duties.headline", me.fullName()));
        if (me.teacherId() == null) {
            exportButton.setDisable(true);
            subline.setText(Messages.get("duties.notLinked"));
            emptyLabel.setText(Messages.get("duties.notLinked"));
            return;
        }
        FxAsync.run(() -> ServiceRegistry.get().examManagementService().getMyUpcomingDuties(),
                (List<SupervisionRoster> duties) -> {
                    dutyTable.setItems(FXCollections.observableArrayList(duties));
                    subline.setText(Messages.get("duties.count", duties.size()));
                },
                e -> Alerts.error(Navigator.get().stage(), e));
    }

    /** The teacher's own schedule for the coming year as a PDF. */
    @FXML
    private void onExport() {
        AuthenticatedUser me = UserSession.get().requireUser();
        LocalDate from = LocalDate.now(), to = from.plusYears(1);
        Exports.run(Exports.Kind.PDF, "my_proctoring_schedule_" + from + ".pdf", busy,
                path -> ServiceRegistry.get().reportService().exportTeacherSchedule(me.teacherId(), from, to, path));
    }
}
