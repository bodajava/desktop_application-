package com.examhalls.controller;

import com.examhalls.exception.AppException;
import com.examhalls.model.Student;
import com.examhalls.security.Permission;
import com.examhalls.security.UserSession;
import com.examhalls.service.MasterDataService;
import com.examhalls.service.ServiceRegistry;
import com.examhalls.service.StudentImportService;
import com.examhalls.ui.Alerts;
import com.examhalls.ui.BusyOverlay;
import com.examhalls.ui.CrudPanel;
import com.examhalls.ui.FormDialog;
import com.examhalls.ui.FxAsync;
import com.examhalls.ui.Navigator;
import com.examhalls.util.Messages;
import com.examhalls.util.StudentSheetReader;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** Student roster: search, grade filter, special-needs flag. */
public class StudentsController {

    @FXML private VBox container;
    @FXML private BusyOverlay busy;

    private final MasterDataService data = ServiceRegistry.get().masterDataService();
    private final StudentImportService importService = ServiceRegistry.get().studentImportService();
    private final ComboBox<String> gradeFilter = new ComboBox<>();
    private final CheckBox specialOnly = new CheckBox(Messages.get("master.student.specialOnly"));
    private CrudPanel<Student> students;
    private List<String> grades = List.of();

    @FXML
    private void initialize() {
        String all = Messages.get("master.student.allGrades");
        gradeFilter.setPrefWidth(170);
        students = new CrudPanel<Student>(busy)
                .column("ops.col.studentCode", Student::studentCode, 130)
                .column("ops.col.student", Student::fullName, 280)
                .badgeColumn("master.field.gradeLevel", Student::gradeLevel, s -> "badge-grade", 120)
                .column("master.field.section", s -> s.section() == null ? "—" : s.section(), 90)
                .badgeColumn("ops.col.needs", s -> s.hasSpecialNeeds() ? Messages.get("ops.specialNeeds") : null,
                        s -> "badge-special", 150)
                .searchable(CrudPanel.anyOf(Student::studentCode, Student::fullName))
                .loader(() -> {
                    grades = data.gradeLevels();
                    return data.students();
                })
                .afterLoad(list -> {
                    String keep = gradeFilter.getValue();
                    List<String> items = new ArrayList<>();
                    items.add(all);
                    items.addAll(grades);
                    gradeFilter.setItems(FXCollections.observableArrayList(items));
                    gradeFilter.setValue(keep != null && items.contains(keep) ? keep : all);
                    applyFilters(all);
                })
                .toolbarNode(specialOnly)
                .toolbarNode(gradeFilter)
                .onNew(() -> edit(null))
                .onEdit(this::edit)
                .onDelete(s -> Messages.get("master.student.delete", s.fullName()), "master.student.deleteText",
                        s -> data.deleteStudent(s.studentId()), s -> Messages.get("master.toast.deleted", s.studentCode()));
        gradeFilter.valueProperty().addListener((o, a, b) -> applyFilters(all));
        specialOnly.selectedProperty().addListener((o, a, b) -> applyFilters(all));
        if (UserSession.get().currentUser().map(u -> u.can(Permission.MANAGE_USERS)).orElse(false)) {
            Button importButton = new Button(Messages.get("master.student.import"));
            importButton.getStyleClass().addAll("button-secondary", "toolbar-button");
            importButton.setOnAction(e -> importFromSheet());
            students.toolbarNode(importButton);
        }
        VBox.setVgrow(students, Priority.ALWAYS);
        container.getChildren().add(students);
        students.reload();
    }

    private void importFromSheet() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(Messages.get("master.student.importTitle"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Excel (*.xlsx)", "*.xlsx"));
        File file = chooser.showOpenDialog(Navigator.get().stage());
        if (file == null) {
            return;
        }
        busy.show(Messages.get("busy.saving"));
        FxAsync.run(() -> importService.importRows(StudentSheetReader.read(file)), results -> {
            long created = results.stream().filter(r -> r.outcome() == StudentImportService.Outcome.CREATED_WITH_LOGIN).count();
            long errors = results.stream().filter(r -> r.outcome() == StudentImportService.Outcome.ERROR).count();
            long updated = results.size() - created - errors;
            String detail = results.stream().filter(r -> r.outcome() == StudentImportService.Outcome.ERROR)
                    .map(r -> "#" + r.rowNumber() + " " + r.studentCode() + ": " + r.message())
                    .collect(Collectors.joining("\n"));
            Alerts.info(Navigator.get().stage(), Messages.get("master.student.importDone", created, updated, errors),
                    detail.isEmpty() ? Messages.get("master.student.importAllOk") : detail);
            students.reload();
        }, (AppException ex) -> Alerts.error(Navigator.get().stage(), ex), busy::hide);
    }

    private void applyFilters(String all) {
        String g = gradeFilter.getValue();
        boolean sn = specialOnly.isSelected();
        students.setExtraFilter(s -> (g == null || g.equals(all) || Objects.equals(g, s.gradeLevel()))
                && (!sn || s.hasSpecialNeeds()));
    }

    private void edit(Student s) {
        boolean isNew = s == null;
        FormDialog f = new FormDialog(isNew ? "master.student.new" : "master.student.edit")
                .text("code", "master.field.studentCode", isNew ? "" : s.studentCode(), true)
                .text("name", "master.field.fullName", isNew ? "" : s.fullName(), true)
                .editableCombo("grade", "master.field.gradeLevel", grades, isNew ? null : s.gradeLevel(), true)
                .text("section", "master.field.section", isNew ? "" : s.section(), false)
                .text("email", "master.field.email", isNew ? "" : s.email(), false)
                .checkbox("special", "master.field.specialNeeds", !isNew && s.hasSpecialNeeds())
                .note(Messages.get("master.student.rule"));
        f.onSave(() -> data.saveStudent(new Student(isNew ? null : s.studentId(), f.text("code"), f.text("name"),
                f.text("grade"), f.text("section"), f.bool("special"), f.text("email"))));
        if (f.showAndWait(Navigator.get().stage())) {
            Navigator.get().shell().toast(Messages.get("master.toast.saved"));
            students.reload();
        }
    }
}
