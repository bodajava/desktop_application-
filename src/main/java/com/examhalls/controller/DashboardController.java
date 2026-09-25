package com.examhalls.controller;

import com.examhalls.model.AllocationState;
import com.examhalls.model.DashboardData;
import com.examhalls.model.DayCapacity;
import com.examhalls.model.DeptWorkload;
import com.examhalls.model.FocusExamRow;
import com.examhalls.model.SubstitutionFeedItem;
import com.examhalls.security.AuthenticatedUser;
import com.examhalls.security.Permission;
import com.examhalls.security.UserSession;
import com.examhalls.service.DashboardService;
import com.examhalls.service.ServiceRegistry;
import com.examhalls.ui.Alerts;
import com.examhalls.ui.BusyOverlay;
import com.examhalls.ui.Exports;
import com.examhalls.ui.FxAsync;
import com.examhalls.ui.Navigator;
import com.examhalls.ui.View;
import com.examhalls.util.Formats;
import com.examhalls.util.Messages;
import javafx.beans.binding.Bindings;
import javafx.beans.property.SimpleObjectProperty;
import javafx.fxml.FXML;
import javafx.geometry.NodeOrientation;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.PieChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.util.Duration;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Executive Analytics & Operations Center. Everything comes from one
 * {@link DashboardService#getDashboard()} call on a background thread; charts are rebuilt on
 * each refresh. Charts are always laid out left to right (axes and bars must not mirror in Arabic).
 */
public class DashboardController {

    /** Slice order and colours of the allocation donut (also used by the legend and badges). */
    private static final List<AllocationState> DONUT_ORDER = List.of(
            AllocationState.FULL, AllocationState.PARTIAL, AllocationState.UNASSIGNED, AllocationState.NOT_SEATED);
    private static final Map<AllocationState, String> STATE_COLOR = Map.of(
            AllocationState.FULL, "#16a34a", AllocationState.PARTIAL, "#f59e0b",
            AllocationState.UNASSIGNED, "#dc2626", AllocationState.NOT_SEATED, "#94a3b8");
    private static final Map<AllocationState, String> STATE_BADGE = Map.of(
            AllocationState.FULL, "badge-staffed", AllocationState.PARTIAL, "badge-seated",
            AllocationState.UNASSIGNED, "badge-blocked", AllocationState.NOT_SEATED, "badge-not-seated");

    @FXML private Label welcomeLabel;
    @FXML private Label focusLabel;
    @FXML private Label updatedLabel;

    @FXML private VBox examsCard;
    @FXML private Label examsValue;
    @FXML private Label examsHint;
    @FXML private VBox seatsCard;
    @FXML private Label seatsValue;
    @FXML private ProgressBar seatsBar;
    @FXML private Label seatsHint;
    @FXML private VBox coverageCard;
    @FXML private Label coverageValue;
    @FXML private ProgressBar coverageBar;
    @FXML private Label coverageHint;
    @FXML private VBox alertsCard;
    @FXML private Label alertsValue;
    @FXML private Label alertsHint;
    @FXML private VBox teachersCard;
    @FXML private Label teachersValue;
    @FXML private Label teachersHint;

    @FXML private StackPane capacityHolder;
    @FXML private StackPane donutHolder;
    @FXML private FlowPane donutLegend;
    @FXML private Label deptSubtitle;
    @FXML private StackPane deptHolder;

    @FXML private Label focusTitle;
    @FXML private Label focusBadge;
    @FXML private TableView<FocusExamRow> focusTable;
    @FXML private Label actionsHint;
    @FXML private Button jumpButton;
    @FXML private Button gridButton;
    @FXML private Button dailyButton;
    @FXML private VBox feedCard;
    @FXML private VBox feedBox;
    @FXML private BusyOverlay busy;

    private final DashboardService dashboard = ServiceRegistry.get().dashboardService();
    private DashboardData data;

    @FXML
    private void initialize() {
        AuthenticatedUser user = UserSession.get().currentUser().orElse(null);
        if (user != null) {
            welcomeLabel.setText(Messages.get("dashboard.welcome", user.fullName()));
            boolean reports = user.can(Permission.VIEW_REPORTS);
            dailyButton.setVisible(reports);
            dailyButton.setManaged(reports);
        }
        tip(examsCard, "dashboard.kpi.exams.tip");
        tip(seatsCard, "dashboard.kpi.seats.tip");
        tip(coverageCard, "dashboard.kpi.coverage.tip");
        tip(alertsCard, "dashboard.kpi.alerts.tip");
        tip(teachersCard, "dashboard.kpi.teachers.tip");
        setUpFocusTable();
        load();
    }

    @FXML
    private void onRefresh() {
        load();
    }

    private void load() {
        busy.show(Messages.get("busy.loading"));
        FxAsync.run(dashboard::getDashboard, this::show, e -> Alerts.error(Navigator.get().stage(), e), busy::hide);
    }

    // ================================================================== rendering

    private void show(DashboardData d) {
        this.data = d;
        LocalDate focus = d.focusDay();
        String dayText = focus == null ? "" : focus.equals(d.today()) ? Messages.get("dashboard.today")
                : Messages.get("dashboard.onDay", Formats.date(focus));
        if (focus == null) {
            focusLabel.setText(Messages.get("dashboard.focus.none"));
        } else if (focus.equals(d.today())) {
            focusLabel.setText(Messages.get("dashboard.focus.today", Formats.longDate(focus)));
        } else {
            focusLabel.setText(Messages.get("dashboard.focus.next", Formats.longDate(focus),
                    ChronoUnit.DAYS.between(d.today(), focus)));
        }
        updatedLabel.setText(Messages.get("dashboard.updated", LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))));

        // ---- KPI 1: active exams
        examsValue.setText(String.valueOf(d.activeExams()));
        examsHint.setText(focus == null ? Messages.get("dashboard.kpi.exams.none")
                : Messages.get("dashboard.kpi.exams.hint", d.examsOnFocusDay(), dayText));

        // ---- KPI 2: seated vs hall capacity
        seatsValue.setText(Formats.ratio(d.seated(), d.hallCapacity()));
        seatsBar.setProgress(d.utilisation());
        seatsHint.setText(Messages.get("dashboard.kpi.seats.hint", percent(d.utilisation()), d.unseatedStudents()));

        // ---- KPI 3: proctoring coverage
        if (d.requiredPositions() == 0) {
            coverageValue.setText("—");
            coverageBar.setProgress(0);
            coverageHint.setText(Messages.get("dashboard.kpi.coverage.none"));
        } else {
            coverageValue.setText(percent(d.coverage()));
            coverageBar.setProgress(d.coverage());
            coverageHint.setText(Messages.get("dashboard.kpi.coverage.hint", d.filledPositions(), d.requiredPositions()));
        }
        severity(coverageBar, d.requiredPositions() == 0 ? null
                : d.coverage() >= 1 ? "bar-ok" : d.coverage() >= 0.5 ? "bar-warn" : "bar-danger");

        // ---- KPI 4: under-staffed alerts
        alertsValue.setText(String.valueOf(d.understaffedRooms()));
        alertsHint.setText(switch (d.alertLevel()) {
            case NONE -> Messages.get("dashboard.kpi.alerts.none");
            case AMBER, RED -> Messages.get("dashboard.kpi.alerts.hint", d.unstaffedRooms(), d.partialRooms());
        });
        alertsCard.getStyleClass().removeAll("kpi-red", "kpi-amber", "kpi-green");
        alertsCard.getStyleClass().add(switch (d.alertLevel()) {
            case RED -> "kpi-red";
            case AMBER -> "kpi-amber";
            case NONE -> "kpi-green";
        });

        // ---- KPI 5: teachers on duty vs available
        teachersValue.setText(Formats.ratio(d.teachersOnDuty(), d.activeTeachers()));
        teachersHint.setText(focus == null ? Messages.get("dashboard.kpi.teachers.none", d.activeTeachers())
                : Messages.get("dashboard.kpi.teachers.hint", d.activeTeachers() - d.teachersOnDuty(), dayText));

        buildCapacityChart(d.days());
        buildDonut(d.allocation());
        buildDepartmentChart(d.departments());

        // ---- focus day panel + actions
        focusTitle.setText(focus == null ? Messages.get("dashboard.focus.titleNone")
                : Messages.get("dashboard.focus.title", dayText));
        focusTable.getItems().setAll(d.focusExams());
        focusTable.setPrefHeight(36 + 50 * Math.max(3, Math.min(8, d.focusExams().size())));
        long open = d.focusExams().stream().filter(r -> r.state() != AllocationState.FULL).count();
        focusBadge.setVisible(focus != null);
        focusBadge.setText(open == 0 ? Messages.get("dashboard.focus.ready") : Messages.get("dashboard.focus.open", open));
        focusBadge.getStyleClass().removeAll("badge-staffed", "badge-blocked");
        focusBadge.getStyleClass().add(open == 0 ? "badge-staffed" : "badge-blocked");

        actionsHint.setText(focus == null ? Messages.get("dashboard.actions.none")
                : Messages.get("dashboard.actions.hint", Formats.date(focus)));
        jumpButton.setDisable(d.focusExams().isEmpty());
        gridButton.setDisable(focus == null);
        dailyButton.setDisable(focus == null);

        feedCard.setVisible(d.auditVisible());
        feedCard.setManaged(d.auditVisible());
        buildFeed(d.recentSubstitutions());
    }

    // ------------------------------------------------------------------ capacity chart

    private void buildCapacityChart(List<DayCapacity> days) {
        if (days.isEmpty()) {
            capacityHolder.getChildren().setAll(empty("dashboard.chart.noDays"));
            return;
        }
        CategoryAxis x = new CategoryAxis();
        NumberAxis y = new NumberAxis();
        y.setForceZeroInRange(true);
        BarChart<String, Number> chart = new BarChart<>(x, y);
        chart.setAnimated(false);
        chart.setCategoryGap(24);
        chart.setBarGap(3);
        chart.setPrefHeight(300);
        chart.getStyleClass().addAll("dash-chart", "capacity-chart");
        chart.setNodeOrientation(NodeOrientation.LEFT_TO_RIGHT);

        XYChart.Series<String, Number> capacity = series("dashboard.chart.capacity");
        XYChart.Series<String, Number> enrolled = series("dashboard.chart.enrolled");
        XYChart.Series<String, Number> seated = series("dashboard.chart.seated");
        for (DayCapacity day : days) {
            String category = shortDate(day.date());
            capacity.getData().add(point(category, day.capacity(), day.date()));
            enrolled.getData().add(point(category, day.enrolled(), day.date()));
            seated.getData().add(point(category, day.seated(), day.date()));
        }
        chart.getData().setAll(List.of(capacity, enrolled, seated));
        for (XYChart.Series<String, Number> s : chart.getData()) {
            for (XYChart.Data<String, Number> p : s.getData()) {
                LocalDate date = (LocalDate) p.getExtraValue();
                Node bar = p.getNode();
                install(bar, s.getName() + " · " + p.getXValue() + ": " + p.getYValue());
                bar.setCursor(Cursor.HAND);
                bar.setOnMouseClicked(e -> openGrid(date));
            }
        }
        capacityHolder.getChildren().setAll(chart);
    }

    private static XYChart.Series<String, Number> series(String key) {
        XYChart.Series<String, Number> s = new XYChart.Series<>();
        s.setName(Messages.get(key));
        return s;
    }

    private static XYChart.Data<String, Number> point(String category, int value, LocalDate date) {
        XYChart.Data<String, Number> p = new XYChart.Data<>(category, value);
        p.setExtraValue(date);
        return p;
    }

    // ------------------------------------------------------------------ allocation donut

    private void buildDonut(Map<AllocationState, Integer> allocation) {
        int total = allocation.values().stream().mapToInt(Integer::intValue).sum();
        donutLegend.getChildren().clear();
        if (total == 0) {
            donutHolder.getChildren().setAll(empty("dashboard.chart.noExams"));
            return;
        }
        PieChart pie = new PieChart();
        pie.setAnimated(false);
        pie.setLabelsVisible(false);
        pie.setLegendVisible(false);
        pie.setStartAngle(90);
        pie.setPrefSize(260, 240);
        pie.getStyleClass().add("dash-donut");
        for (AllocationState state : DONUT_ORDER) {
            int count = allocation.getOrDefault(state, 0);
            String label = Formats.enumLabel("dashboard.state", state);
            String share = label + ": " + count + " (" + percent(count / (double) total) + ")";
            if (count > 0) {
                PieChart.Data slice = new PieChart.Data(label, count);
                pie.getData().add(slice);
                slice.getNode().setStyle("-fx-pie-color: " + STATE_COLOR.get(state) + ";");
                install(slice.getNode(), share);
            }
            Region swatch = new Region();
            swatch.getStyleClass().add("legend-swatch");
            swatch.setStyle("-fx-background-color: " + STATE_COLOR.get(state) + ";");
            Label name = new Label(label);
            Label value = new Label(String.valueOf(count));
            value.getStyleClass().add("dash-legend-count");
            HBox item = new HBox(6, swatch, name, value);
            item.setAlignment(Pos.CENTER_LEFT);
            item.getStyleClass().add("dash-legend-item");
            donutLegend.getChildren().add(item);
        }

        Circle hole = new Circle();
        hole.getStyleClass().add("donut-hole");
        hole.radiusProperty().bind(Bindings.min(pie.widthProperty(), pie.heightProperty()).multiply(0.27));
        hole.setMouseTransparent(true);
        Label number = new Label(String.valueOf(total));
        number.getStyleClass().add("donut-total");
        Label caption = new Label(Messages.get("dashboard.chart.exams"));
        caption.getStyleClass().add("muted");
        VBox centre = new VBox(number, caption);
        centre.getStyleClass().add("donut-centre");
        centre.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        centre.setMouseTransparent(true);
        donutHolder.getChildren().setAll(pie, hole, centre);
        donutHolder.setNodeOrientation(NodeOrientation.LEFT_TO_RIGHT);
    }

    // ------------------------------------------------------------------ department workload

    private void buildDepartmentChart(List<DeptWorkload> departments) {
        if (departments.isEmpty()) {
            deptSubtitle.setText("");
            deptHolder.getChildren().setAll(empty("dashboard.chart.noTeachers"));
            return;
        }
        int teachers = departments.stream().mapToInt(DeptWorkload::teachers).sum();
        BigDecimal total = departments.stream().map(DeptWorkload::totalHours).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal schoolAverage = total.divide(BigDecimal.valueOf(teachers), 2, RoundingMode.HALF_UP);
        deptSubtitle.setText(Messages.get("dashboard.chart.deptSubtitle", Formats.hours(schoolAverage), teachers));

        NumberAxis x = new NumberAxis();
        x.setForceZeroInRange(true);
        x.setLabel(Messages.get("dashboard.chart.avgHours"));
        CategoryAxis y = new CategoryAxis();
        BarChart<Number, String> chart = new BarChart<>(x, y);
        chart.setAnimated(false);
        chart.setLegendVisible(false);
        chart.setCategoryGap(10);
        chart.setPrefHeight(90 + 34 * departments.size());
        chart.getStyleClass().addAll("dash-chart", "dept-chart");
        chart.setNodeOrientation(NodeOrientation.LEFT_TO_RIGHT);

        XYChart.Series<Number, String> series = new XYChart.Series<>();
        for (int i = departments.size() - 1; i >= 0; i--) {     // busiest department on top
            DeptWorkload w = departments.get(i);
            String category = w.department() + "  (" + Messages.get("dashboard.hoursShort", Formats.hours(w.averageHours())) + ")";
            XYChart.Data<Number, String> p = new XYChart.Data<>(w.averageHours().doubleValue(), category);
            p.setExtraValue(w);
            series.getData().add(p);
        }
        chart.getData().setAll(List.of(series));
        for (XYChart.Data<Number, String> p : series.getData()) {
            DeptWorkload w = (DeptWorkload) p.getExtraValue();
            boolean above = w.averageHours().compareTo(schoolAverage) > 0;
            if (above) {
                p.getNode().getStyleClass().add("above-average");
            }
            install(p.getNode(), Messages.get("dashboard.chart.deptTip", w.department(), Formats.hours(w.averageHours()),
                    w.teachers(), Formats.hours(w.totalHours())));
        }
        deptHolder.getChildren().setAll(chart);
    }

    // ------------------------------------------------------------------ focus-day table

    private void setUpFocusTable() {
        focusTable.setPlaceholder(new Label(Messages.get("dashboard.focus.empty")));
        focusTable.setFixedCellSize(50);
        focusTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<FocusExamRow, FocusExamRow> time = twoLine("dashboard.col.time", 100,
                r -> Formats.timeRange(r.exam().slotStart(), r.exam().slotEnd()), r -> r.exam().periodName());
        TableColumn<FocusExamRow, FocusExamRow> course = twoLine("dashboard.col.course", 125,
                r -> r.exam().courseCode(), r -> r.exam().courseName());
        TableColumn<FocusExamRow, FocusExamRow> seated = twoLine("dashboard.col.seated", 80,
                r -> Formats.ratio(r.seated(), r.enrolled()),
                r -> r.rooms() == 0 ? "" : Messages.get("dashboard.col.roomsCount", r.rooms()));
        TableColumn<FocusExamRow, FocusExamRow> staff = twoLine("dashboard.col.staff", 80,
                r -> r.required() == 0 ? "—" : Formats.ratio(r.filled(), r.required()), r -> "");

        TableColumn<FocusExamRow, AllocationState> status = new TableColumn<>(Messages.get("dashboard.col.status"));
        status.setPrefWidth(140);
        status.setMinWidth(130);
        status.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue().state()));
        status.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(AllocationState state, boolean empty) {
                super.updateItem(state, empty);
                if (empty || state == null) {
                    setGraphic(null);
                    return;
                }
                Label badge = new Label(Formats.enumLabel("dashboard.state", state));
                badge.getStyleClass().addAll("badge", STATE_BADGE.get(state));
                setGraphic(badge);
            }
        });
        focusTable.getColumns().setAll(List.of(time, course, seated, staff, status));
        focusTable.setRowFactory(t -> {
            TableRow<FocusExamRow> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    openExam(row.getItem());
                }
            });
            return row;
        });
    }

    /** A column whose cells show a main value with a smaller, muted line under it. */
    private static TableColumn<FocusExamRow, FocusExamRow> twoLine(String key, double width,
                                                                  Function<FocusExamRow, String> main,
                                                                  Function<FocusExamRow, String> sub) {
        TableColumn<FocusExamRow, FocusExamRow> c = new TableColumn<>(Messages.get(key));
        c.setPrefWidth(width);
        c.setCellValueFactory(cell -> new SimpleObjectProperty<>(cell.getValue()));
        c.setCellFactory(col -> new TableCell<>() {
            private final Label top = new Label();
            private final Label bottom = new Label();
            private final VBox box = new VBox(1, top, bottom);

            {
                top.getStyleClass().add("cell-main");
                bottom.getStyleClass().add("cell-sub");
            }

            @Override
            protected void updateItem(FocusExamRow row, boolean empty) {
                super.updateItem(row, empty);
                if (empty || row == null) {
                    setGraphic(null);
                    return;
                }
                top.setText(main.apply(row));
                String second = sub.apply(row);
                bottom.setText(second);
                bottom.setVisible(!second.isEmpty());
                bottom.setManaged(!second.isEmpty());
                setGraphic(box);
            }
        });
        return c;
    }

    // ------------------------------------------------------------------ substitutions feed

    private void buildFeed(List<SubstitutionFeedItem> items) {
        feedBox.getChildren().clear();
        if (items.isEmpty()) {
            Label none = new Label(Messages.get("dashboard.feed.none"));
            none.getStyleClass().add("muted");
            feedBox.getChildren().add(none);
            return;
        }
        String arrow = Messages.isRightToLeft() ? "  ←  " : "  →  ";
        for (SubstitutionFeedItem s : items) {
            Label title = new Label(Formats.ltr(s.courseCode()) + " · " + Formats.ltr(s.roomCode()) + " · "
                    + Formats.date(s.examDate()) + " · " + s.periodName());
            title.getStyleClass().add("feed-title");
            title.setWrapText(true);
            Label swap = new Label(s.replacedName() + arrow + s.substituteName());
            swap.getStyleClass().add("feed-swap");
            swap.setWrapText(true);
            Label reason = new Label(Messages.get("dashboard.feed.reason", s.reason()));
            reason.getStyleClass().add("feed-reason");
            reason.setWrapText(true);
            Label meta = new Label(s.at() == null ? Messages.get("dashboard.feed.by", Formats.ltr(s.executedBy()))
                    : Messages.get("dashboard.feed.meta", Formats.time(s.at()), Formats.date(s.at().toLocalDate()),
                    Formats.ltr(s.executedBy())));
            meta.getStyleClass().add("muted");
            VBox item = new VBox(title, swap, reason, meta);
            item.getStyleClass().add("feed-item");
            feedBox.getChildren().add(item);
        }
    }

    // ================================================================== quick actions

    @FXML
    private void onJumpToSeating() {
        FocusExamRow row = focusTable.getSelectionModel().getSelectedItem();
        if (row == null && !focusTable.getItems().isEmpty()) {
            row = focusTable.getItems().stream().filter(r -> r.state() != AllocationState.FULL).findFirst()
                    .orElse(focusTable.getItems().get(0));
        }
        if (row != null) {
            openExam(row);
        }
    }

    private void openExam(FocusExamRow row) {
        long examId = row.exam().examId();
        Navigator.get().shell().<ExamOperationsController>navigate(View.EXAM_OPERATIONS, c -> c.openExam(examId));
    }

    @FXML
    private void onOpenGrid() {
        if (data != null && data.focusDay() != null) {
            openGrid(data.focusDay());
        }
    }

    private void openGrid(LocalDate date) {
        Navigator.get().shell().<OccupancyGridController>navigate(View.OCCUPANCY_GRID, c -> c.showDate(date));
    }

    @FXML
    private void onDailyReport() {
        if (data == null || data.focusDay() == null) {
            return;
        }
        LocalDate day = data.focusDay();
        Exports.run(Exports.Kind.PDF, "daily_control_" + day + ".pdf", busy,
                path -> ServiceRegistry.get().reportService().exportDailyControlSheet(day, path));
    }

    // ================================================================== helpers

    private static String percent(double share) {
        return Math.round(share * 100) + "%";
    }

    /** "Sun 10 Jan" (Arabic-Indic digits in Arabic, like every other date). */
    private static String shortDate(LocalDate d) {
        String full = Formats.date(d);                 // "EEE d MMM yyyy"
        int lastSpace = full.lastIndexOf(' ');
        return lastSpace > 0 ? full.substring(0, lastSpace) : full;
    }

    private static void severity(ProgressBar bar, String styleClass) {
        bar.getStyleClass().removeAll("bar-ok", "bar-warn", "bar-danger");
        if (styleClass != null) {
            bar.getStyleClass().add(styleClass);
        }
    }

    private static void tip(Node node, String key) {
        install(node, Messages.get(key));
    }

    private static void install(Node node, String text) {
        Tooltip t = new Tooltip(text);
        t.setShowDelay(Duration.millis(250));
        t.setWrapText(true);
        t.setMaxWidth(320);
        Tooltip.install(node, t);
    }

    private static Label empty(String key) {
        Label l = new Label(Messages.get(key));
        l.getStyleClass().add("empty-state");
        l.setWrapText(true);
        return l;
    }
}
