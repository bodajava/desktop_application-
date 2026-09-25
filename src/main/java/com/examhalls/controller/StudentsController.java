package com.examhalls.controller;

import com.examhalls.model.Student;
import com.examhalls.service.MasterDataService;
import com.examhalls.service.ServiceRegistry;
import com.examhalls.ui.BusyOverlay;
import com.examhalls.ui.CrudPanel;
import com.examhalls.ui.FormDialog;
import com.examhalls.ui.Navigator;
import com.examhalls.util.Messages;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Student roster: search, grade filter, special-needs flag. */
public class StudentsController {

    @FXML private VBox container;
    @FXML private BusyOverlay busy;

    private final MasterDataService data = ServiceRegistry.get().masterDataService();
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
        VBox.setVgrow(students, Priority.ALWAYS);
        container.getChildren().add(students);
        students.reload();
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
                .checkbox("special", "master.field.specialNeeds", !isNew && s.hasSpecialNeeds())
                .note(Messages.get("master.student.rule"));
        f.onSave(() -> data.saveStudent(new Student(isNew ? null : s.studentId(), f.text("code"), f.text("name"),
                f.text("grade"), f.text("section"), f.bool("special"))));
        if (f.showAndWait(Navigator.get().stage())) {
            Navigator.get().shell().toast(Messages.get("master.toast.saved"));
            students.reload();
        }
    }
}
