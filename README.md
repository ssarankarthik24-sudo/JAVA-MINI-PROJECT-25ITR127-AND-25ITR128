# Global ATC Flight Path Safety & Collision Avoidance System
## Project Assignment Set 23 — Java Mini Project

---

## Project Structure

```
java mini project/
├── src/
│   ├── DBConnection.java       ← JDBC singleton connection manager
│   ├── AtcDAO.java             ← Data Access Object (all DB CRUD)
│   ├── SeparationWorker.java   ← Callable thread: computes ASF, CRI, safety band
│   ├── IncidentWriter.java     ← File I/O: radar feed parser, CSV exporter
│   └── MainApp.java            ← Entry point, ExecutorService orchestration
├── sql/
│   └── atc_safety_schema.sql   ← MySQL schema (4 tables + seed data)
├── data/
│   └── radar_feed.txt          ← Radar stream feed (pipe-delimited)
├── output/                     ← Generated at runtime (CSV + logs)
├── lib/                        ← Place mysql-connector-j-8.x.jar here
├── run.ps1                     ← PowerShell compile & run script
└── README.md
```

---

## Setup Instructions

### 1. Prerequisites
- JDK 17 or later
- MySQL 8.x running on **OCI VM 2** (Student 2)
- `mysql-connector-j-8.0.33.jar` in `lib/`

### 2. Database Setup (OCI VM 2 — Student 2)
```bash
mysql -u root -p < sql/atc_safety_schema.sql
```
Create the ATC user:
```sql
CREATE USER 'atc_user'@'%' IDENTIFIED BY 'ATC@Secure123';
GRANT ALL PRIVILEGES ON atc_safety_db.* TO 'atc_user'@'%';
FLUSH PRIVILEGES;
```
Open OCI Ingress Rule: **TCP port 3306**, source **0.0.0.0/0** (or VM 1 IP).
```bash
sudo firewall-cmd --permanent --add-port=3306/tcp
sudo firewall-cmd --reload
```

### 3. Configure DB Host (Student 1)
In `src/DBConnection.java`, update:
```java
private static final String DB_HOST = "140.238.X.X";   // OCI VM 2 public IP
```

### 4. Compile & Run (OCI VM 1 — Student 1)
```powershell
.\run.ps1
```
Or manually:
```bash
javac -cp "lib/mysql-connector-j-8.0.33.jar" -d out src/*.java
java  -cp "out;lib/mysql-connector-j-8.0.33.jar" MainApp
```

---

## Aircraft Types & Scoring Weights

| Type | Sep Distance | Alt Drift | 3rd Metric | Min Vert Sep |
|------|-------------|-----------|------------|-------------|
| Commercial Passenger Jet | 50% | 30% | Speed 20% | 1000 ft |
| Cargo Freight Aircraft | 40% | 40% | Flight Plan 20% | 1000 ft |
| Unmanned Commercial Drone | 60% | 30% | Signal 10% | 500 ft |

---

## Safety Bands

| Score | Band | Clearance |
|-------|------|-----------|
| 90–100 | OPTIMAL_CORRIDOR | Unrestricted Flight Path |
| 75–89  | STANDARD_SEPARATION | Normal Sector Clearance |
| 60–74  | VECTORING_ADJUSTMENT | Altitude/Course Correction Ordered |
| <60 or TCAS Alert | EMERGENCY_VECTOR | Immediate Emergency Eviction |

---

## Formulas Implemented

**Airspace Safety Factor (ASF):**
```
ASF = (ActualSeparationDistance / MinimumRequiredSeparation) × 100
```

**Collision Risk Index (CRI):**
```
CRI = (1 / HorizontalDistance_NM) × (1 / VerticalDistance_ft) × 10^6
```

**TCAS Alert triggered when:**
- Horizontal distance < 5 NM **AND** Vertical separation < minimum threshold

---

## Output Files (generated in `output/`)

| File | Description |
|------|-------------|
| `incident_audit_YYYYMMDD_HHmmss.csv` | Full incident audit log |
| `emergency_vector_orders_YYYYMMDD_HHmmss.csv` | Emergency/TCAS flagged pairs |
| `parallel_vector_calc_YYYYMMDD_HHmmss.log` | Thread-by-thread vector calculation proof |

---

## Database Tables

| Table | Phase | Purpose |
|-------|-------|---------|
| `flights` | Phase 1 | Flight plan activation records |
| `radar_logs` | Phase 2 | Real-time telemetry snapshots |
| `separation_breaches` | Phase 3 | Violation events between aircraft pairs |
| `atc_incidents` | Phase 3 | Safety scores, bands, clearance decisions |
