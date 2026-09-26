# Exam Halls & Proctoring Allocation Management System

JavaFX 21 desktop application on Oracle Database (PL/SQL) for exam seating, fair proctor allocation,
conflict-of-interest prevention and emergency substitution — English and Arabic (RTL).

Full technical documentation, algorithms and the role-based user guide: **[DOCUMENTATION.md](DOCUMENTATION.md)**
(print version: `DOCUMENTATION.pdf`, regenerated with `packaging/build-docs-pdf.sh`).

## Quick start

```bash
# 1. Oracle Database Free in Docker (first run downloads ~1.5 GB)
docker run -d --name examhalls-oracle -p 1521:1521 \
  -e ORACLE_PASSWORD=<sys-password> -e APP_USER=EXAM_ADMIN -e APP_USER_PASSWORD=<app-password> \
  -v examhalls-oradata:/opt/oracle/oradata gvenzl/oracle-free:23-slim

# 2. Schema, PL/SQL packages and seed data (after the container log says DATABASE IS READY TO USE!)
APP_USER_PASSWORD=<app-password> database/install-docker.sh

# 3. Connection settings
cp config/application.properties.example config/application.properties   # then set db.password
# Optional: mail.smtp.username / mail.smtp.appPassword (Gmail App Password) to email each
# imported student their login credentials. Leave unset to skip that feature entirely.

# 4. Run
mvn javafx:run                       # development
mvn -Pdist clean package             # lab bundle: target/exam-halls-proctoring-1.0.0-dist.zip
packaging/build-windows-portable.sh  # Windows x64 edition with embedded Java 21 (no install needed)
./run-app.sh   |   run-app.bat       # start from the bundle (checks the database first)
```

Seed logins: `admin / Admin@2026`, `control / Control@2026`, `head / Head@2026`, `teacher / Teacher@2026`.
