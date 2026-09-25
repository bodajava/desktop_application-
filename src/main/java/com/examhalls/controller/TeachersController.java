package com.examhalls.controller;

import com.examhalls.model.Department;
import com.examhalls.model.Student;
import com.examhalls.model.Teacher;
import com.examhalls.model.TeacherStudentRelation;
import com.examhalls.service.MasterDataService;
import com.examhalls.service.ServiceRegistry;
import com.examhalls.ui.Alerts;
import com.examhalls.ui.BusyOverlay;
import com.examhalls.ui.CrudPanel;
import com.examhalls.ui.FormDialog;
import com.examhalls.ui.Navigator;
import com.examhalls.util.Formats;
import com.examhalls.util.Messages;
import javafx.fxml.FXML;
import javafx.geometry.Orientation;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/** Screen 2 — teachers (soft delete) and their relatives (conflict-of-interest register). */
public class TeachersController {

    @FXML private VBox container;
    @FXML private BusyOverlay busy;

    private final MasterDataService data = ServiceRegistry.get().masterDataService();
    private CrudPanel<Teacher> teachers;
    private CrudPanel<TeacherStudentRelation> relatives;
    private List<Department> departments = List.of();
    private List<Student> students = List.of();

    @FXML
    private void initialize() {
        teachers = new CrudPanel<Teacher>(busy)
                .column("ops.col.code", Teacher::teacherCode, 110)
                .column("ops.col.teacher", Teacher::fullName, 220)
                .column("master.field.department", Teacher::deptName, 170)
                .column("master.field.dailyLoad", t -> String.valueOf(t.maxDailyLoad()), 100)
                .column("master.field.weeklyLoad", t -> String.valueOf(t.maxWeeklyLoad()), 110)
                .column("master.field.hoursBalance", t -> Formats.hours(t.hoursBalance()), 110)
                .searchable(CrudPanel.anyOf(Teacher::teacherCode, Teacher::fullName, Teacher::deptName))
                .loader(() -> {
                    departments = data.departments();
                    students = data.students();
                    return data.teachers();
                })
                .onNew(() -> editTeacher(null))
                .onEdit(this::editTeacher)
                .onDelete(t -> Messages.get("master.teacher.delete", t.fullName()), "master.teacher.deleteText",
                        t -> data.deleteTeacher(t.teacherId()), t -> Messages.get("master.toast.archived", t.fullName()));

        relatives = new CrudPanel<TeacherStudentRelation>(busy)
                .title(Messages.get("master.relatives.none"))
                .badgeColumn("master.field.degree", r -> Messages.get("master.degree." + r.relationDegree()),
                        r -> "badge-degree", 150)
                .column("ops.col.studentCode", TeacherStudentRelation::studentCode, 120)
                .column("ops.col.student", TeacherStudentRelation::studentName, 220)
                .column("master.field.gradeLevel", TeacherStudentRelation::gradeLevel, 100)
                .column("master.field.notes", r -> r.notes() == null ? "" : r.notes(), 200)
                .searchable(CrudPanel.anyOf(TeacherStudentRelation::studentCode, TeacherStudentRelation::studentName))
                .loader(() -> {
                    Teacher t = teachers.selected();
                    return t == null ? List.of() : data.relations(t.teacherId());
                })
                .onNew(this::addRelative)
                .onDelete(r -> Messages.get("master.relative.delete", r.studentName()), "master.relative.deleteText",
                        r -> data.removeRelation(r.relationId()), r -> Messages.get("master.toast.relativeRemoved"))
                .hideEditButton();

        teachers.selectedItemProperty().addListener((o, a, t) -> {
            relatives.title(t == null ? Messages.get("master.relatives.none")
                    : Messages.get("master.relatives.of", t.fullName()));
            relatives.setActionsEnabled(t != null);
            relatives.reload();
        });
        relatives.setActionsEnabled(false);

        SplitPane split = new SplitPane(teachers, relatives);
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.58);
        split.getStyleClass().add("plain-split");
        VBox.setVgrow(split, Priority.ALWAYS);
        container.getChildren().add(split);
        teachers.reload();
    }

    private void editTeacher(Teacher t) {
        boolean isNew = t == null;
        Department dept = isNew ? null : departments.stream().filter(d -> d.deptId().equals(t.deptId())).findFirst().orElse(null);
        FormDialog f = new FormDialog(isNew ? "master.teacher.new" : "master.teacher.edit")
                .subtitle(Messages.get("master.teacher.hint"))
                .text("code", "master.field.teacherCode", isNew ? "" : t.teacherCode(), true)
                .text("name", "master.field.fullName", isNew ? "" : t.fullName(), true)
                .combo("dept", "master.field.department", departments, Department::deptName, dept, true)
                .integer("daily", "master.field.dailyLoad", 0, 10, isNew ? 2 : t.maxDailyLoad())
                .integer("weekly", "master.field.weeklyLoad", 0, 99, isNew ? 8 : t.maxWeeklyLoad());
        if (!isNew) {
            f.info("master.field.hoursBalance", Formats.hours(t.hoursBalance()));
        }
        f.validator(x -> x.integer("weekly") < x.integer("daily")
                        ? Optional.of(Messages.get("constraint.CK_TEACHERS_WEEKLY")) : Optional.empty())
                .onSave(() -> {
                    Department d = f.value("dept");
                    data.saveTeacher(new Teacher(isNew ? null : t.teacherId(), f.text("name"), f.text("code"),
                            d.deptId(), null, f.integer("daily"), f.integer("weekly"), null));
                });
        if (f.showAndWait(Navigator.get().stage())) {
            Navigator.get().shell().toast(Messages.get("master.toast.saved"));
            teachers.reload();
        }
    }

    private void addRelative() {
        Teacher t = teachers.selected();
        if (t == null) {
            return;
        }
        AtomicInteger conflicts = new AtomicInteger();
        FormDialog f = new FormDialog("master.relative.new")
                .subtitle(t.fullName() + " · " + t.teacherCode())
                .combo("student", "ops.col.student", students,
                        s -> s.studentCode() + " — " + s.fullName() + " (" + s.gradeLevel() + ")", null, true)
                .integer("degree", "master.field.degree", 1, 4, 1)
                .textArea("notes", "master.field.notes", "")
                .note(Messages.get("master.relative.rule"));
        f.onSave(() -> {
            Student s = f.value("student");
            conflicts.set(data.addRelation(t.teacherId(), s.studentId(), f.integer("degree"), f.text("notes")));
        });
        if (f.showAndWait(Navigator.get().stage())) {
            relatives.reload();
            if (conflicts.get() > 0) {
                Alerts.info(Navigator.get().stage(), Messages.get("master.relative.conflict.header"),
                        Messages.get("master.relative.conflict.text", conflicts.get()));
            } else {
                Navigator.get().shell().toast(Messages.get("master.toast.saved"));
            }
        }
    }
}
