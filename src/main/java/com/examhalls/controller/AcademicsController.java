package com.examhalls.controller;

import com.examhalls.model.Course;
import com.examhalls.model.Department;
import com.examhalls.service.MasterDataService;
import com.examhalls.service.ServiceRegistry;
import com.examhalls.ui.BusyOverlay;
import com.examhalls.ui.CrudPanel;
import com.examhalls.ui.FormDialog;
import com.examhalls.ui.Navigator;
import com.examhalls.util.Messages;
import javafx.fxml.FXML;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;

/** Departments (used for the subject-conflict rule) and courses mapped to grade levels. */
public class AcademicsController {

    @FXML private VBox container;
    @FXML private BusyOverlay busy;

    private final MasterDataService data = ServiceRegistry.get().masterDataService();
    private CrudPanel<Course> courses;
    private CrudPanel<Department> departments;
    private List<Department> deptList = List.of();
    private List<String> grades = List.of();

    @FXML
    private void initialize() {
        courses = new CrudPanel<Course>(busy)
                .column("ops.col.code", Course::courseCode, 120)
                .column("master.field.courseName", Course::courseName, 260)
                .column("master.field.department", Course::deptName, 200)
                .badgeColumn("master.field.gradeLevel", Course::gradeLevel, c -> "badge-grade", 130)
                .searchable(CrudPanel.anyOf(Course::courseCode, Course::courseName, Course::deptName, Course::gradeLevel))
                .loader(() -> {
                    deptList = data.departments();
                    grades = data.gradeLevels();
                    return data.courses();
                })
                .onNew(() -> editCourse(null))
                .onEdit(this::editCourse)
                .onDelete(c -> Messages.get("master.course.delete", c.courseCode()), "master.course.deleteText",
                        c -> data.deleteCourse(c.courseId()), c -> Messages.get("master.toast.deleted", c.courseCode()));

        departments = new CrudPanel<Department>(busy)
                .column("master.field.deptName", Department::deptName, 400)
                .searchable(CrudPanel.anyOf(Department::deptName))
                .loader(data::departments)
                .onNew(() -> editDepartment(null))
                .onEdit(this::editDepartment)
                .onDelete(d -> Messages.get("master.dept.delete", d.deptName()), "master.dept.deleteText",
                        d -> data.deleteDepartment(d.deptId()), d -> Messages.get("master.toast.deleted", d.deptName()));

        Tab courseTab = new Tab(Messages.get("master.tab.courses"), courses);
        Tab deptTab = new Tab(Messages.get("master.tab.departments"), departments);
        TabPane tabs = new TabPane(courseTab, deptTab);
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> (b == courseTab ? courses : departments).reload());
        VBox.setVgrow(tabs, Priority.ALWAYS);
        container.getChildren().add(tabs);
        courses.reload();
    }

    private void editCourse(Course c) {
        boolean isNew = c == null;
        Department dept = isNew ? null : deptList.stream().filter(d -> d.deptId().equals(c.deptId())).findFirst().orElse(null);
        FormDialog f = new FormDialog(isNew ? "master.course.new" : "master.course.edit")
                .text("code", "master.field.courseCode", isNew ? "" : c.courseCode(), true)
                .text("name", "master.field.courseName", isNew ? "" : c.courseName(), true)
                .combo("dept", "master.field.department", deptList, Department::deptName, dept, true)
                .editableCombo("grade", "master.field.gradeLevel", grades, isNew ? null : c.gradeLevel(), true)
                .note(Messages.get("master.course.rule"));
        f.onSave(() -> {
            Department d = f.value("dept");
            data.saveCourse(new Course(isNew ? null : c.courseId(), f.text("name"), f.text("code"), d.deptId(), null,
                    f.text("grade")));
        });
        if (f.showAndWait(Navigator.get().stage())) {
            Navigator.get().shell().toast(Messages.get("master.toast.saved"));
            courses.reload();
        }
    }

    private void editDepartment(Department d) {
        boolean isNew = d == null;
        FormDialog f = new FormDialog(isNew ? "master.dept.new" : "master.dept.edit")
                .text("name", "master.field.deptName", isNew ? "" : d.deptName(), true)
                .note(Messages.get("master.dept.rule"));
        f.onSave(() -> data.saveDepartment(new Department(isNew ? null : d.deptId(), f.text("name"))));
        if (f.showAndWait(Navigator.get().stage())) {
            Navigator.get().shell().toast(Messages.get("master.toast.saved"));
            departments.reload();
        }
    }
}
