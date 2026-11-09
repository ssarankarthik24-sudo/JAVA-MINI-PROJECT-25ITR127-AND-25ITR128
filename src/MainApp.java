import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/**
 * MainApp.java
 * ─────────────────────────────────────────────────────────────
 * Entry point for the Global ATC Flight Path Safety &
 * Collision Avoidance System.
 *
 * Execution flow:
 *   1.  Parse radar_feed.txt  (IncidentWriter)
 *   2.  Phase 1 — Insert / verify flight plans  (AtcDAO)
 *   3.  Phase 2 — Insert radar telemetry         (AtcDAO)
 *   4.  Phase 3 — Multi-threaded separation      (SeparationWorker × ExecutorService)
 *   5.  Persist incident records                  (AtcDAO)
 *   6.  Export CSVs + calculation log             (IncidentWriter)
 *   7.  Print summary dashboard to console
 * ─────────────────────────────────────────────────────────────
 */
public class MainApp {

    // ── Minimum required separation by aircraft type (NM) ────
    private static final Map<String, Double> MIN_SEP_NM = Map.of(
        "COMMERCIAL_PASSENGER_JET",    5.0,
        "CARGO_FREIGHT_AIRCRAFT",      5.0,
        "UNMANNED_COMMERCIAL_DRONE",   2.0
    );

    // ── Call-sign regex pattern ───────────────────────────────
    private static final Pattern CALL_SIGN_PATTERN =
            Pattern.compile("^[A-Z]{3}[0-9]{1,4}$");

    // ── Thread pool size (parallel radar workers) ─────────────
    private static final int THREAD_POOL_SIZE = 8;

    // ─────────────────────────────────────────────────────────
    public static void main(String[] args) {

        printBanner();

        AtcDAO          dao    = new AtcDAO();
        IncidentWriter  writer = new IncidentWriter();

        // ══════════════════════════════════════════════════════
        // STEP 1 — Read & parse radar_feed.txt
        // ══════════════════════════════════════════════════════
        List<IncidentWriter.RadarRecord> records;
        try {
            records = writer.readRadarFeed("data/radar_feed.txt");
        } catch (Exception e) {
            System.err.println("[MainApp] FATAL: Cannot read radar feed — " + e.getMessage());
            return;
        }

        if (records.isEmpty()) {
            System.err.println("[MainApp] No valid radar records found. Exiting.");
            return;
        }

        // Print telemetry table
        System.out.println("\n╔══════════════════════════════════════════════════════╗");
        System.out.println("║           RADAR TELEMETRY INGESTION LOG              ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");
        records.forEach(r -> System.out.println("  " + r));

        // ══════════════════════════════════════════════════════
        // STEP 2 & 3 — DB: Phase 1 + Phase 2  (best-effort)
        // ══════════════════════════════════════════════════════
        Map<String, Integer> flightIdMap = new LinkedHashMap<>();

        System.out.println("\n╔══════════════════════════════════════════════════════╗");
        System.out.println("║           PHASE 1 — FLIGHT PLAN ACTIVATION          ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");

        for (IncidentWriter.RadarRecord rec : records) {
            // String processing: validate call sign
            if (!validateCallSign(rec.callSign)) {
                System.err.printf("[MainApp] Invalid call sign '%s' — skipping DB insert%n",
                        rec.callSign);
                continue;
            }

            try {
                int fid;
                try {
                    fid = dao.insertFlight(rec.callSign, rec.aircraftType,
                                           rec.corridor, rec.altitudeCeiling);
                    if (fid <= 0) {
                        throw new SQLException("Flight insert did not return a valid flight ID");
                    }
                    System.out.printf("  ✔ Phase 1: %s inserted as flight_id=%d%n",
                            rec.callSign, fid);
                } catch (SQLException insertError) {
                    AtcDAO.Flight existingFlight;
                    try {
                        existingFlight = dao.getFlightByCallSign(rec.callSign);
                    } catch (SQLException lookupError) {
                        insertError.addSuppressed(lookupError);
                        throw insertError;
                    }
                    if (existingFlight == null) {
                        throw insertError;
                    }
                    fid = existingFlight.flightId;
                    System.out.printf("  ✔ Phase 1: %s already exists as flight_id=%d; reusing it%n",
                            rec.callSign, fid);
                }
                flightIdMap.put(rec.callSign, fid);

                try {
                    dao.insertRadarLog(fid, rec.realTimeAltFt, rec.groundSpeedKt,
                                       rec.headingDeg, rec.lat, rec.lon);
                    System.out.printf("  ✔ Phase 2: Radar log for %s recorded%n", rec.callSign);
                } catch (SQLException e) {
                    System.err.printf("[MainApp] Radar log error for %s: %s%n",
                            rec.callSign, e.getMessage());
                }

            } catch (SQLException e) {
                System.err.printf("[MainApp] DB error for %s; skipping DB persistence: %s%n",
                        rec.callSign, e.getMessage());
            }
        }

        // ══════════════════════════════════════════════════════
        // STEP 4 — Parallel separation tracking (Phase 3 engine)
        // ══════════════════════════════════════════════════════
        System.out.println("\n╔══════════════════════════════════════════════════════╗");
        System.out.println("║     PHASE 3 — MULTI-THREADED SEPARATION TRACKING     ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");

        // AtomicInteger counter for clean thread naming (avoids deprecated Thread.getId())
        java.util.concurrent.atomic.AtomicInteger threadCounter =
                new java.util.concurrent.atomic.AtomicInteger(1);
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE,
                r -> {
                    Thread t = new Thread(r);
                    t.setName("RadarWorker-" + threadCounter.getAndIncrement());
                    return t;
                });

        List<Future<SeparationWorker.SeparationResult>> futures = new ArrayList<>();

        // Submit one worker for every unique ordered pair (A, B)
        for (int i = 0; i < records.size(); i++) {
            for (int j = i + 1; j < records.size(); j++) {
                IncidentWriter.RadarRecord recA = records.get(i);
                IncidentWriter.RadarRecord recB = records.get(j);

                double minSep = MIN_SEP_NM.getOrDefault(recA.aircraftType, 5.0);

                // Actual separation = real Haversine distance between current positions
                double actualSepNm = SeparationWorker.haversineNm(
                        recA.lat, recA.lon, recB.lat, recB.lon);

                // Altitude drift sub-score: how close to ceiling is the aircraft (inverted)
                double altDriftPct = computeAltDriftScore(recA);

                // Third metric sub-score (speed compliance / flight plan / signal)
                double thirdMetric = computeThirdMetricScore(recA);

                SeparationWorker worker = new SeparationWorker(
                    recA.callSign, recA.aircraftType,
                    recA.realTimeAltFt, recA.lat, recA.lon,
                    actualSepNm, minSep,
                    altDriftPct, thirdMetric,
                    recB.callSign, recB.realTimeAltFt, recB.lat, recB.lon
                );

                futures.add(executor.submit(worker));
            }
        }

        // Collect results
        List<SeparationWorker.SeparationResult> results = new ArrayList<>();
        for (Future<SeparationWorker.SeparationResult> f : futures) {
            try {
                SeparationWorker.SeparationResult res = f.get(10, TimeUnit.SECONDS);
                results.add(res);
                System.out.println("  " + res);
            } catch (Exception e) {
                System.err.println("[MainApp] Worker error: " + e.getMessage());
            }
        }

        executor.shutdown();
        System.out.printf("%n[MainApp] Separation analysis complete: %d pairs evaluated by %d threads.%n",
                results.size(), THREAD_POOL_SIZE);

        // ══════════════════════════════════════════════════════
        // STEP 5 — Build per-flight safety scores & persist
        // ══════════════════════════════════════════════════════
        System.out.println("\n╔══════════════════════════════════════════════════════╗");
        System.out.println("║         AIRSPACE SAFETY SCORING DASHBOARD            ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");

        // Aggregate best (worst-case) scores per flight
        Map<String, SeparationWorker.SeparationResult> worstPerFlight = new LinkedHashMap<>();
        for (SeparationWorker.SeparationResult r : results) {
            worstPerFlight.merge(r.callSignA, r,
                (existing, newR) -> newR.overallScoreA < existing.overallScoreA ? newR : existing);
        }

        for (IncidentWriter.RadarRecord rec : records) {
            SeparationWorker.SeparationResult worst = worstPerFlight.get(rec.callSign);
            if (worst == null) continue;

            // Print safety score line
            String indicator = getSafetyIndicator(worst.safetyBandA);
            System.out.printf("  %s %-8s | ASF:%6.2f%% | Score:%5.1f | %-24s | %s%n",
                    indicator, rec.callSign,
                    worst.asfA, worst.overallScoreA,
                    worst.safetyBandA, worst.clearanceA);

            // Persist to DB
            int fid = flightIdMap.getOrDefault(rec.callSign, -1);
            if (fid > 0) {
                AtcDAO.AtcIncident inc = new AtcDAO.AtcIncident();
                inc.flightId               = fid;
                inc.sepProximityViolations = rec.sepViolations;
                inc.radioContactDelaySec   = rec.radioDelayS;
                inc.descentProfileScore    = rec.descentScore;
                inc.airspaceSafetyFactor   = worst.asfA;
                inc.overallSafetyScore     = worst.overallScoreA;
                inc.safetyBand             = worst.safetyBandA;
                inc.clearanceDecision      = worst.clearanceA;
                inc.collisionAlertFlagged  = worst.tcasAlert;

                try {
                    dao.upsertAtcIncident(inc);
                    if (worst.safetyBandA.equals("EMERGENCY_VECTOR")) {
                        dao.updateFlightStatus(fid, "EMERGENCY");
                    } else if (worst.safetyBandA.equals("OPTIMAL_CORRIDOR")) {
                        dao.updateFlightStatus(fid, "CLEARED");
                    }
                } catch (SQLException e) {
                    System.err.printf("[MainApp] DB persist error for %s: %s%n",
                            rec.callSign, e.getMessage());
                }
            }
        }

        // ══════════════════════════════════════════════════════
        // STEP 6 — Export CSV files & parallel log
        // ══════════════════════════════════════════════════════
        System.out.println("\n╔══════════════════════════════════════════════════════╗");
        System.out.println("║               FILE EXPORT SUMMARY                   ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");

        try {
            // Fetch incidents from DB (or build a local fallback list)
            List<AtcDAO.AtcIncident> incidents = buildLocalIncidentList(
                    records, worstPerFlight, flightIdMap);

            String auditFile   = writer.exportIncidentAuditLog(incidents);
            String vectorFile  = writer.exportEmergencyVectorOrders(results);
            writer.writeParallelCalculationLog(results);

            System.out.println("  ✔ Incident audit log   → " + auditFile);
            System.out.println("  ✔ Emergency vector CSV → " + vectorFile);
            System.out.println("  ✔ Parallel calc log    → output/parallel_vector_calc_*.log");

        } catch (Exception e) {
            System.err.println("[MainApp] Export error: " + e.getMessage());
        }

        // ══════════════════════════════════════════════════════
        // STEP 7 — TCAS Alert summary
        // ══════════════════════════════════════════════════════
        long tcasCount = results.stream().filter(r -> r.tcasAlert).count();
        if (tcasCount > 0) {
            System.out.println("\n╔══════════════════════════════════════════════════════╗");
            System.out.printf( "║   ⚠  TCAS COLLISION ALERTS DETECTED: %-3d             ║%n", tcasCount);
            System.out.println("╚══════════════════════════════════════════════════════╝");
            results.stream()
                   .filter(r -> r.tcasAlert)
                   .forEach(r -> System.out.printf("  *** ALERT: %s ↔ %s | H=%.3fNM V=%dft | %s%n",
                           r.callSignA, r.callSignB,
                           r.horizontalDistNm, r.verticalDistFt, r.clearanceA));
        } else {
            System.out.println("\n  ✅ No TCAS Collision Alerts — Airspace nominal.");
        }

        // Close DB
        DBConnection.closeConnection();
        System.out.println("\n[MainApp] ATC Safety Engine shutdown complete.\n");
    }

    // ══════════════════════════════════════════════════════════
    // Helper methods
    // ══════════════════════════════════════════════════════════

    /**
     * Validates a flight call sign against the regex [A-Z]{3}\d{1,4}.
     * Uses String processing per spec.
     */
    private static boolean validateCallSign(String cs) {
        if (cs == null || cs.isBlank()) return false;
        boolean valid = CALL_SIGN_PATTERN.matcher(cs.trim().toUpperCase()).matches();
        if (!valid) {
            System.err.printf("[MainApp] Call sign validation FAILED: '%s'%n", cs);
        } else {
            System.out.printf("[MainApp] Call sign validated: '%s' | Squawk: %s%n",
                    cs, IncidentWriter.extractSquawk(cs));
        }
        return valid;
    }

    /**
     * Computes altitude drift sub-score (0-100).
     * Score = 100 if altitude is within 5% of ceiling; decreases as drift grows.
     */
    private static double computeAltDriftScore(IncidentWriter.RadarRecord rec) {
        if (rec.altitudeCeiling == 0) return 50.0;
        double ratio = (double) rec.realTimeAltFt / rec.altitudeCeiling;
        // Ideal ratio = 0.95–1.0 → score 100; below 0.90 or above 1.0 → penalty
        double drift = Math.abs(ratio - 0.975);
        double score = 100.0 - (drift * 400.0);
        return Math.max(0.0, Math.min(100.0, score));
    }

    /**
     * Computes the third metric sub-score (speed compliance / flight-plan /
     * signal quality) using descent score from radar feed as proxy.
     */
    private static double computeThirdMetricScore(IncidentWriter.RadarRecord rec) {
        // Descent profile score from the feed already maps to 0-100
        return Math.max(0.0, Math.min(100.0, rec.descentScore));
    }

    /**
     * Builds a local in-memory incident list (used when DB is offline).
     */
    private static List<AtcDAO.AtcIncident> buildLocalIncidentList(
            List<IncidentWriter.RadarRecord> records,
            Map<String, SeparationWorker.SeparationResult> worstPerFlight,
            Map<String, Integer> flightIdMap) {

        List<AtcDAO.AtcIncident> list = new ArrayList<>();
        int syntheticId = 1;
        for (IncidentWriter.RadarRecord rec : records) {
            SeparationWorker.SeparationResult worst = worstPerFlight.get(rec.callSign);
            if (worst == null) continue;
            AtcDAO.AtcIncident inc = new AtcDAO.AtcIncident();
            inc.incidentId             = syntheticId++;
            inc.flightId               = flightIdMap.getOrDefault(rec.callSign, -1);
            inc.sepProximityViolations = rec.sepViolations;
            inc.radioContactDelaySec   = rec.radioDelayS;
            inc.descentProfileScore    = rec.descentScore;
            inc.airspaceSafetyFactor   = worst.asfA;
            inc.overallSafetyScore     = worst.overallScoreA;
            inc.safetyBand             = worst.safetyBandA;
            inc.clearanceDecision      = worst.clearanceA;
            inc.collisionAlertFlagged  = worst.tcasAlert;
            list.add(inc);
        }
        return list;
    }

    /** Returns a colored/emoji indicator for the safety band. */
    private static String getSafetyIndicator(String band) {
        return switch (band) {
            case "OPTIMAL_CORRIDOR"      -> "🟢";
            case "STANDARD_SEPARATION"   -> "🔵";
            case "VECTORING_ADJUSTMENT"  -> "🟡";
            case "EMERGENCY_VECTOR"      -> "🔴";
            default                      -> "⚪";
        };
    }

    /** Prints the ATC system banner. */
    private static void printBanner() {
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║   GLOBAL ATC FLIGHT PATH SAFETY & COLLISION AVOIDANCE SYSTEM ║");
        System.out.println("║           Project Assignment Set 23 — Java Edition           ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println();
    }
}
