package com.examhalls.controller;

import com.examhalls.model.StudentExamRow;
import com.examhalls.security.AuthenticatedUser;
import com.examhalls.security.UserSession;
import com.examhalls.service.ServiceRegistry;
import com.examhalls.ui.Alerts;
import com.examhalls.ui.BusyOverlay;
import com.examhalls.ui.FxAsync;
import com.examhalls.ui.Navigator;
import com.examhalls.util.Formats;
import com.examhalls.util.Messages;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;

import java.util.List;

/** A student's own upcoming exams: date, room, seat and attendance. */
public class MyExamsController {

    @FXML private Label headline;
    @FXML private Label subline;
    @FXML private Label emptyLabel;
    @FXML private TableView<StudentExamRow> examTable;
    @FXML private TableColumn<StudentExamRow, String> dateCol;
    @FXML private TableColumn<StudentExamRow, String> periodCol;
    @FXML private TableColumn<StudentExamRow, String> courseCol;
    @FXML private TableColumn<StudentExamRow, String> roomCol;
    @FXML private TableColumn<StudentExamRow, String> seatCol;
    @FXML private TableColumn<StudentExamRow, String> attendanceCol;
    @FXML private BusyOverlay busy;

    @FXML
    private void initialize() {
        dateCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(Formats.date(c.getValue().examDate())));
        periodCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().periodName()));
        courseCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(
                c.getValue().courseName() + " (" + c.getValue().courseCode() + ")"));
        roomCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(
                c.getValue().roomCode() + (c.getValue().building() == null ? "" : " · " + c.getValue().building())));
        seatCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().seatNumber()));
        attendanceCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(
                Formats.enumLabel("attendance", c.getValue().attendanceStatus())));

        AuthenticatedUser me = UserSession.get().requireUser();
        headline.setText(Messages.get("myExams.headline", me.fullName()));
        if (me.studentId() == null) {
            subline.setText(Messages.get("myExams.notLinked"));
            emptyLabel.setText(Messages.get("myExams.notLinked"));
            return;
        }
        FxAsync.run(() -> ServiceRegistry.get().examManagementService().getMyExams(),
                (List<StudentExamRow> exams) -> {
                    examTable.setItems(FXCollections.observableArrayList(exams));
                    subline.setText(Messages.get("myExams.count", exams.size()));
                },
                e -> Alerts.error(Navigator.get().stage(), e));
    }
}
