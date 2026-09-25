package com.examhalls.ui;

import com.examhalls.security.Permission;

/** Pages shown inside the shell. The sidebar only lists pages the signed-in role may open. */
public enum View {
    DASHBOARD("DashboardView", Permission.VIEW_ALL_SCHEDULES, Section.OPERATIONS),
    EXAM_SCHEDULE("ExamScheduleView", Permission.VIEW_ALL_SCHEDULES, Section.OPERATIONS),
    OCCUPANCY_GRID("OccupancyGridView", Permission.VIEW_ALL_SCHEDULES, Section.OPERATIONS),
    EXAM_OPERATIONS("ExamOperationsView", Permission.VIEW_ALL_SCHEDULES, Section.OPERATIONS),
    REPORTS("ReportsView", Permission.VIEW_REPORTS, Section.OPERATIONS),
    TEACHERS("TeachersView", Permission.MANAGE_MASTER_DATA, Section.MANAGEMENT),
    ROOMS("RoomsView", Permission.MANAGE_MASTER_DATA, Section.MANAGEMENT),
    ACADEMICS("AcademicsView", Permission.MANAGE_MASTER_DATA, Section.MANAGEMENT),
    STUDENTS("StudentsView", Permission.MANAGE_MASTER_DATA, Section.MANAGEMENT),
    USERS("UsersView", Permission.MANAGE_USERS, Section.MANAGEMENT),
    MY_DUTIES("MyDutiesView", Permission.VIEW_OWN_SCHEDULE, Section.PERSONAL);

    /** Sidebar group headings. */
    public enum Section { OPERATIONS, MANAGEMENT, PERSONAL }

    private final String fxml;
    private final Permission permission;
    private final Section section;

    View(String fxml, Permission permission, Section section) {
        this.fxml = fxml;
        this.permission = permission;
        this.section = section;
    }

    public Section section() {
        return section;
    }

    public String fxml() {
        return fxml;
    }

    public Permission permission() {
        return permission;
    }

    public String titleKey() {
        return "nav." + name();
    }
}
