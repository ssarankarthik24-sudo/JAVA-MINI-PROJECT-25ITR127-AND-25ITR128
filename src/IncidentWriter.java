import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * IncidentWriter.java
 * ─────────────────────────────────────────────────────────────
 * Handles all File I/O for the ATC Safety System:
 *   • Read radar stream feed from radar_feed.txt
 *   • Parse and validate each record (call-sign regex, field
 *     count, numeric ranges)
 *   • Export incident audit logs to CSV
 *   • Export emergency vector orders to a separate CSV
 *   • Format ATC telemetry log entries with String.format()
 * ─────────────────────────────────────────────────────────────
 */
public class IncidentWriter {

    // ── Default I/O paths ─────────────────────────────────────
    private static final String RADAR_FEED_PATH  = "data/radar_feed.txt";
    private static final String OUTPUT_DIR       = "output/";

    private static final DateTimeFormatter DT_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // ── Call-sign validation pattern: [A-Z]{3}\d{1,4} ────────
    private static final String CALL_SIGN_REGEX  = "^[A-Z]{3}[0-9]{1,4}$";

    // ── Aircraft type code mapping ────────────────────────────
    public static String expandTypeCode(String code) {
        return switch (code.trim().toUpperCase()) {
            case "CPJ" -> "COMMERCIAL_PASSENGER_JET";
            case "CFA" -> "CARGO_FREIGHT_AIRCRAFT";
            case "UCD" -> "UNMANNED_COMMERCIAL_DRONE";
            default    -> code.trim().toUpperCase();
        };
    }

    // ══════════════════════════════════════════════════════════
    // Inner data model for a parsed radar record
    // ══════════════════════════════════════════════════════════
    public static class RadarRecord {
        public final String  callSign;
        public final String  aircraftType;
        public final String  corridor;
        public final int     altitudeCeiling;
        public final int     realTimeAltFt;
        public final double  groundSpeedKt;
        public final double  headingDeg;
        public final double  lat;
        public final double  lon;
        public final int     sepViolations;
        public final double  radioDelayS;
        public final double  descentScore;

        public RadarRecord(String cs, String type, String corr, int ceil,
                           int alt, double spd, double hdg, double lat, double lon,
                           int sepV, double delay, double descent) {
            this.callSign        = cs;
            this.aircraftType    = type;
            this.corridor        = corr;
            this.altitudeCeiling = ceil;
            this.realTimeAltFt   = alt;
            this.groundSpeedKt   = spd;
            this.headingDeg      = hdg;
            this.lat             = lat;
            this.lon             = lon;
            this.sepViolations   = sepV;
            this.radioDelayS     = delay;
            this.descentScore    = descent;
        }

        @Override
        public String toString() {
            // String.format() per spec — structured ATC telemetry log
            return String.format(
                "TELEMETRY | %-8s | %-30s | Corr:%-16s | Ceil:%5dft | "
                + "Alt:%5dft | Spd:%6.1fkt | Hdg:%5.1f° | (%.6f,%.6f) | "
                + "Viol:%d | Delay:%.1fs | Descent:%.1f",
                callSign, aircraftType, corridor, altitudeCeiling,
                realTimeAltFt, groundSpeedKt, headingDeg, lat, lon,
                sepViolations, radioDelayS, descentScore
            );
        }
    }

    // ══════════════════════════════════════════════════════════
    // READ: Parse radar_feed.txt
    // ══════════════════════════════════════════════════════════

    /**
     * Reads and parses the radar stream feed file.
     * Lines starting with '#' or blank lines are skipped.
     * Each valid line is split on '|' and validated.
     *
     * @param filePath path to radar_feed.txt
     * @return list of validated {@link RadarRecord}
     * @throws IOException on file access failure
     */
    public List<RadarRecord> readRadarFeed(String filePath) throws IOException {
        List<RadarRecord> records = new java.util.ArrayList<>();
        Path path = Paths.get(filePath);

        if (!Files.exists(path)) {
            throw new FileNotFoundException("[IncidentWriter] Radar feed not found: " + filePath);
        }

        System.out.println("[IncidentWriter] Reading radar feed: " + filePath);
        int lineNo = 0, skipped = 0;

        try (BufferedReader br = new BufferedReader(new FileReader(path.toFile()))) {
            String line;
            while ((line = br.readLine()) != null) {
                lineNo++;
                line = line.trim();                       // .trim() per spec
                if (line.isEmpty() || line.startsWith("#")) continue;

                String[] fields = line.split("\\|");
                if (fields.length != 12) {
                    System.err.printf("[IncidentWriter] Line %d: expected 12 fields, got %d — SKIPPED%n",
                            lineNo, fields.length);
                    skipped++;
                    continue;
                }

                // ── String processing: validate call sign ────
                String cs = fields[0].trim().toUpperCase();
                if (!cs.matches(CALL_SIGN_REGEX)) {
                    System.err.printf("[IncidentWriter] Line %d: invalid call sign '%s' — SKIPPED%n",
                            lineNo, cs);
                    skipped++;
                    continue;
                }

                // ── Extract squawk code from call sign ───────
                // Squawk is the numeric suffix (last 4 digits) — .substring() per spec
                String squawk = extractSquawk(cs);

                try {
                    RadarRecord rec = new RadarRecord(
                        cs,
                        expandTypeCode(fields[1].trim()),
                        fields[2].trim(),           // corridor — cleaned via .trim()
                        Integer.parseInt(fields[3].trim()),
                        Integer.parseInt(fields[4].trim()),
                        Double.parseDouble(fields[5].trim()),
                        Double.parseDouble(fields[6].trim()),
                        Double.parseDouble(fields[7].trim()),
                        Double.parseDouble(fields[8].trim()),
                        Integer.parseInt(fields[9].trim()),
                        Double.parseDouble(fields[10].trim()),
                        Double.parseDouble(fields[11].trim())
                    );
                    records.add(rec);
                    System.out.printf("[IncidentWriter] Parsed %-8s (squawk:%s) → %s%n",
                            cs, squawk, rec.aircraftType);
                } catch (NumberFormatException e) {
                    System.err.printf("[IncidentWriter] Line %d: numeric parse error — SKIPPED (%s)%n",
                            lineNo, e.getMessage());
                    skipped++;
                }
            }
        }

        System.out.printf("[IncidentWriter] Feed parsed: %d records loaded, %d skipped.%n",
                records.size(), skipped);
        return records;
    }

    /**
     * Extracts the squawk code (numeric suffix) from a call sign.
     * Uses {@code .substring()} as required by spec.
     *
     * @param callSign validated call sign like "AAL101"
     * @return numeric squawk string, e.g. "101"
     */
    public static String extractSquawk(String callSign) {
        // Find where the digit part begins
        int digitStart = 0;
        for (int i = 0; i < callSign.length(); i++) {
            if (Character.isDigit(callSign.charAt(i))) {
                digitStart = i;
                break;
            }
        }
        return callSign.substring(digitStart);  // .substring() per spec
    }

    // ══════════════════════════════════════════════════════════
    // WRITE: Incident Audit Log CSV
    // ══════════════════════════════════════════════════════════

    /**
     * Exports all ATC incidents to a timestamped CSV file.
     *
     * @param incidents list of incidents from AtcDAO
     * @return path of the written file
     * @throws IOException on write failure
     */
    public String exportIncidentAuditLog(List<AtcDAO.AtcIncident> incidents)
            throws IOException {
        Files.createDirectories(Paths.get(OUTPUT_DIR));
        String ts   = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String file = OUTPUT_DIR + "incident_audit_" + ts + ".csv";

        try (PrintWriter pw = new PrintWriter(new BufferedWriter(new FileWriter(file)))) {
            // Header
            pw.println("incident_id,flight_id,sep_violations,radio_delay_sec,"
                     + "descent_score,airspace_safety_factor,overall_safety_score,"
                     + "safety_band,clearance_decision,collision_alert,generated_at");

            for (AtcDAO.AtcIncident inc : incidents) {
                pw.println(String.format(
                    "%d,%d,%d,%.2f,%.2f,%.4f,%.2f,%s,%s,%b,%s",
                    inc.incidentId, inc.flightId,
                    inc.sepProximityViolations, inc.radioContactDelaySec,
                    inc.descentProfileScore,   inc.airspaceSafetyFactor,
                    inc.overallSafetyScore,    inc.safetyBand,
                    "\"" + inc.clearanceDecision + "\"",
                    inc.collisionAlertFlagged,
                    LocalDateTime.now().format(DT_FMT)
                ));
            }
        }

        System.out.println("[IncidentWriter] Incident audit log exported → " + file);
        return file;
    }

    // ══════════════════════════════════════════════════════════
    // WRITE: Emergency Vector Orders CSV
    // ══════════════════════════════════════════════════════════

    /**
     * Exports emergency and TCAS vector orders to a separate CSV.
     *
     * @param results list of separation results from worker threads
     * @return path of the written file
     * @throws IOException on write failure
     */
    public String exportEmergencyVectorOrders(
            List<SeparationWorker.SeparationResult> results) throws IOException {

        Files.createDirectories(Paths.get(OUTPUT_DIR));
        String ts   = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String file = OUTPUT_DIR + "emergency_vector_orders_" + ts + ".csv";

        try (PrintWriter pw = new PrintWriter(new BufferedWriter(new FileWriter(file)))) {
            pw.println("call_sign_a,call_sign_b,horizontal_dist_nm,vertical_dist_ft,"
                     + "collision_risk_index,asf_pct,overall_score,safety_band,"
                     + "clearance,tcas_alert,computed_at");

            for (SeparationWorker.SeparationResult r : results) {
                if (r.safetyBandA.equals("EMERGENCY_VECTOR") || r.tcasAlert) {
                    pw.println(String.format(
                        "%s,%s,%.4f,%d,%.6f,%.2f,%.2f,%s,%s,%b,%s",
                        r.callSignA, r.callSignB,
                        r.horizontalDistNm, r.verticalDistFt,
                        r.collisionRiskIndex, r.asfA,
                        r.overallScoreA, r.safetyBandA,
                        "\"" + r.clearanceA + "\"",
                        r.tcasAlert,
                        LocalDateTime.now().format(DT_FMT)
                    ));
                }
            }
        }

        System.out.println("[IncidentWriter] Emergency vector orders exported → " + file);
        return file;
    }

    // ══════════════════════════════════════════════════════════
    // WRITE: Full parallel vector calculation log
    // ══════════════════════════════════════════════════════════

    /**
     * Writes the full multi-threaded separation calculation log.
     * This serves as the execution proof for parallel vector calculations.
     *
     * @param results all results from the ExecutorService
     * @throws IOException on write failure
     */
    public void writeParallelCalculationLog(
            List<SeparationWorker.SeparationResult> results) throws IOException {

        Files.createDirectories(Paths.get(OUTPUT_DIR));
        String ts   = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String file = OUTPUT_DIR + "parallel_vector_calc_" + ts + ".log";

        try (PrintWriter pw = new PrintWriter(new BufferedWriter(new FileWriter(file)))) {
            pw.printf("=== ATC Parallel Vector Calculation Log — %s ===%n%n",
                    LocalDateTime.now().format(DT_FMT));

            pw.printf("%-20s %-10s %-10s %-12s %-12s %-14s %-8s %-8s %-24s %-36s%n",
                    "Thread", "AircraftA", "AircraftB", "H_Dist(NM)",
                    "V_Dist(ft)", "CRI", "ASF%", "Score", "SafetyBand", "Clearance");
            pw.println("-".repeat(140));

            for (SeparationWorker.SeparationResult r : results) {
                pw.printf("%-20s %-10s %-10s %-12.4f %-12d %-14.6f %-8.2f %-8.2f %-24s %-36s%s%n",
                        r.threadName, r.callSignA, r.callSignB,
                        r.horizontalDistNm, r.verticalDistFt,
                        r.collisionRiskIndex, r.asfA, r.overallScoreA,
                        r.safetyBandA, r.clearanceA,
                        r.tcasAlert ? " <<< TCAS_COLLISION_ALERT >>>" : "");
            }

            pw.printf("%n=== Total pairs evaluated: %d ===%n", results.size());
        }

        System.out.println("[IncidentWriter] Parallel calculation log written → " + file);
    }
}
