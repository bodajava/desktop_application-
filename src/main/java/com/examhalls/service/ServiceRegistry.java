package com.examhalls.service;

import com.examhalls.config.AppSettings;
import com.examhalls.config.TransactionManager;
import com.examhalls.dao.CourseDao;
import com.examhalls.dao.DepartmentDao;
import com.examhalls.dao.ExamPeriodDao;
import com.examhalls.dao.ExamScheduleDao;
import com.examhalls.dao.RoleDao;
import com.examhalls.dao.RoomDao;
import com.examhalls.dao.RelationDao;
import com.examhalls.dao.ReportDao;
import com.examhalls.dao.SeatingAllocationDao;
import com.examhalls.dao.StudentDao;
import com.examhalls.dao.SupervisionAuditDao;
import com.examhalls.dao.SupervisionRosterDao;
import com.examhalls.dao.TeacherDao;
import com.examhalls.dao.UserDao;
import com.examhalls.dao.impl.CourseDaoImpl;
import com.examhalls.dao.impl.DepartmentDaoImpl;
import com.examhalls.dao.impl.ExamPeriodDaoImpl;
import com.examhalls.dao.impl.ExamScheduleDaoImpl;
import com.examhalls.dao.impl.RoleDaoImpl;
import com.examhalls.dao.impl.RoomDaoImpl;
import com.examhalls.dao.impl.RelationDaoImpl;
import com.examhalls.dao.impl.ReportDaoImpl;
import com.examhalls.dao.impl.SeatingAllocationDaoImpl;
import com.examhalls.dao.impl.StudentDaoImpl;
import com.examhalls.dao.impl.SupervisionAuditDaoImpl;
import com.examhalls.dao.impl.SupervisionRosterDaoImpl;
import com.examhalls.dao.impl.TeacherDaoImpl;
import com.examhalls.dao.impl.UserDaoImpl;
import com.examhalls.security.LoginAttemptTracker;
import com.examhalls.security.PasswordHasher;
import com.examhalls.security.UserSession;

/**
 * Hand-wired object graph (no DI framework needed for a desktop app).
 * Controllers obtain services with {@code ServiceRegistry.get().authService()} etc.
 * Tests can build their own instance with {@link #create(TransactionManager)}.
 */
public final class ServiceRegistry {

    private static volatile ServiceRegistry instance;

    private final TransactionManager tx;
    private final RoleDao roleDao;
    private final UserDao userDao;
    private final DepartmentDao departmentDao;
    private final TeacherDao teacherDao;
    private final RoomDao roomDao;
    private final CourseDao courseDao;
    private final StudentDao studentDao;
    private final ExamPeriodDao examPeriodDao;
    private final ExamScheduleDao examScheduleDao;
    private final SeatingAllocationDao seatingDao;
    private final SupervisionRosterDao rosterDao;
    private final SupervisionAuditDao auditDao;
    private final ReportDao reportDao;
    private final RelationDao relationDao;
    private final AuthService authService;
    private final ExamManagementService examManagementService;
    private final DashboardService dashboardService;
    private final OccupancyService occupancyService;
    private final ReportService reportService;
    private final UserManagementService userManagementService;
    private final MasterDataService masterDataService;
    private final StudentImportService studentImportService;

    private ServiceRegistry(TransactionManager tx) {
        this.tx = tx;
        this.roleDao = new RoleDaoImpl(tx);
        this.userDao = new UserDaoImpl(tx);
        this.departmentDao = new DepartmentDaoImpl(tx);
        this.teacherDao = new TeacherDaoImpl(tx);
        this.roomDao = new RoomDaoImpl(tx);
        this.courseDao = new CourseDaoImpl(tx);
        this.studentDao = new StudentDaoImpl(tx);
        this.examPeriodDao = new ExamPeriodDaoImpl(tx);
        this.examScheduleDao = new ExamScheduleDaoImpl(tx);
        this.seatingDao = new SeatingAllocationDaoImpl(tx);
        this.rosterDao = new SupervisionRosterDaoImpl(tx);
        this.auditDao = new SupervisionAuditDaoImpl(tx);
        this.reportDao = new ReportDaoImpl(tx);
        this.relationDao = new RelationDaoImpl(tx);
        UserSession session = UserSession.get();
        PasswordHasher hasher = new PasswordHasher();
        LoginAttemptTracker lockout = new LoginAttemptTracker(
                Math.max(1, AppSettings.getInt("security.login.maxAttempts", 5)),
                java.time.Duration.ofMinutes(Math.max(1, AppSettings.getInt("security.login.lockMinutes", 5))),
                java.time.Clock.systemUTC());
        this.authService = new AuthService(userDao, hasher, lockout, session);
        this.userManagementService = new UserManagementService(tx, session, userDao, roleDao, teacherDao, hasher);
        this.masterDataService = new MasterDataService(tx, session, departmentDao, courseDao, teacherDao, relationDao,
                roomDao, studentDao, examPeriodDao, examScheduleDao, seatingDao);
        this.examManagementService = new ExamManagementService(tx, session, seatingDao, rosterDao, auditDao,
                examScheduleDao, roomDao);
        this.dashboardService = new DashboardService(session, roomDao, examScheduleDao, reportDao);
        this.occupancyService = new OccupancyService(session, examScheduleDao, examPeriodDao, roomDao, reportDao);
        this.reportService = new ReportService(session, examScheduleDao, seatingDao, reportDao,
                examManagementService, occupancyService, teacherDao);
        this.studentImportService = new StudentImportService(tx, session, studentDao, userDao, roleDao, hasher,
                new EmailService());
    }

    /** Application-wide instance on the HikariCP pool (created on first use). */
    public static ServiceRegistry get() {
        ServiceRegistry local = instance;
        if (local == null) {
            synchronized (ServiceRegistry.class) {
                local = instance;
                if (local == null) {
                    local = new ServiceRegistry(TransactionManager.fromPool());
                    instance = local;
                }
            }
        }
        return local;
    }

    public static ServiceRegistry create(TransactionManager tx) {
        return new ServiceRegistry(tx);
    }

    public TransactionManager transactions() { return tx; }
    public RoleDao roleDao() { return roleDao; }
    public UserDao userDao() { return userDao; }
    public DepartmentDao departmentDao() { return departmentDao; }
    public TeacherDao teacherDao() { return teacherDao; }
    public RoomDao roomDao() { return roomDao; }
    public CourseDao courseDao() { return courseDao; }
    public StudentDao studentDao() { return studentDao; }
    public ExamPeriodDao examPeriodDao() { return examPeriodDao; }
    public ExamScheduleDao examScheduleDao() { return examScheduleDao; }
    public SeatingAllocationDao seatingDao() { return seatingDao; }
    public SupervisionRosterDao rosterDao() { return rosterDao; }
    public SupervisionAuditDao auditDao() { return auditDao; }
    public AuthService authService() { return authService; }
    public ExamManagementService examManagementService() { return examManagementService; }
    public DashboardService dashboardService() { return dashboardService; }
    public OccupancyService occupancyService() { return occupancyService; }
    public ReportService reportService() { return reportService; }
    public ReportDao reportDao() { return reportDao; }
    public UserManagementService userManagementService() { return userManagementService; }
    public MasterDataService masterDataService() { return masterDataService; }
    public StudentImportService studentImportService() { return studentImportService; }
}
