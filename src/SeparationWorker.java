import java.util.concurrent.Callable;

/**
 * SeparationWorker.java
 * ─────────────────────────────────────────────────────────────
 * Implements {@link Callable} so it can be submitted to an
 * ExecutorService and return a structured result.
 *
 * Each worker receives two aircraft radar snapshots and
 * computes:
 *   • Horizontal distance  (Haversine, Nautical Miles)
 *   • Vertical separation  (feet)
 *   • Collision Risk Index (CRI)
 *   • Airspace Safety Factor (ASF)
 *   • Overall Safety Score  (weighted by aircraft type)
 *   • Safety Band + Clearance Decision
 *   • TCAS Collision Alert flag
 *
 * Run many workers in parallel via MainApp's thread pool.
 * ─────────────────────────────────────────────────────────────
 */
public class SeparationWorker implements Callable<SeparationWorker.SeparationResult> {

    // ── Separation thresholds (Nautical Miles) ────────────────
    public static final double TCAS_HORIZONTAL_THRESHOLD_NM = 5.0;

    // ── Minimum vertical separation by aircraft type (feet) ──
    public static final int MIN_VERT_SEP_JET   = 1000;   // CPJ
    public static final int MIN_VERT_SEP_CARGO = 1000;   // CFA
    public static final int MIN_VERT_SEP_DRONE =  500;   // UCD

    // ── Safety-score band thresholds ─────────────────────────
    public static final double OPTIMAL_MIN    = 90.0;
    public static final double STANDARD_MIN   = 75.0;
    public static final double VECTORING_MIN  = 60.0;

    // ── Weight tables ─────────────────────────────────────────
    // CPJ: SepDist 50 | AltDrift 30 | SpeedCompliance 20
    private static final double[] W_CPJ   = {0.50, 0.30, 0.20};
    // CFA: SepDist 40 | AltDrift 40 | FlightPlanAdherence 20
    private static final double[] W_CFA   = {0.40, 0.40, 0.20};
    // UCD: SepDist 60 | GeoFenceProximity 30 | SignalLinkQuality 10
    private static final double[] W_UCD   = {0.60, 0.30, 0.10};

    // ═══════════════════════════════════════════════════════════
    // Result POJO returned by call()
    // ═══════════════════════════════════════════════════════════
    public static class SeparationResult {
        public final String  callSignA;
        public final String  callSignB;
        public final double  horizontalDistNm;
        public final int     verticalDistFt;
        public final double  collisionRiskIndex;
        public final double  asfA;              // ASF for aircraft A
        public final double  overallScoreA;
        public final String  safetyBandA;
        public final String  clearanceA;
        public final boolean tcasAlert;
        public final String  threadName;

        public SeparationResult(String csA, String csB,
                                double h, int v, double cri,
                                double asf, double score,
                                String band, String clearance,
                                boolean tcas, String tName) {
            this.callSignA         = csA;
            this.callSignB         = csB;
            this.horizontalDistNm  = h;
            this.verticalDistFt    = v;
            this.collisionRiskIndex = cri;
            this.asfA              = asf;
            this.overallScoreA     = score;
            this.safetyBandA       = band;
            this.clearanceA        = clearance;
            this.tcasAlert         = tcas;
            this.threadName        = tName;
        }

        @Override
        public String toString() {
            return String.format(
                "[%s] %s↔%s | H=%.3fNM V=%dft | CRI=%.4f | ASF=%.2f%% | Score=%.1f | %s | %s%s",
                threadName, callSignA, callSignB,
                horizontalDistNm, verticalDistFt, collisionRiskIndex,
                asfA, overallScoreA, safetyBandA,
                clearanceA, tcasAlert ? " *** TCAS_COLLISION_ALERT ***" : "");
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Worker fields — set by constructor
    // ═══════════════════════════════════════════════════════════
    private final String callSignA;
    private final String aircraftTypeA;
    private final int    altFtA;
    private final double latA, lonA;
    private final double actualSepDistA;   // actual separation measured (NM)
    private final double minReqSepA;       // minimum required separation (NM)
    private final double altDriftScoreA;   // 0-100 raw sub-score
    private final double thirdMetricScoreA;// Speed/FlightPlan/Signal 0-100

    private final String callSignB;
    private final int    altFtB;
    private final double latB, lonB;

    /**
     * Constructor used by MainApp for every aircraft pair.
     *
     * @param csA               Call sign of aircraft A
     * @param typeA             Aircraft type string (CPJ / CFA / UCD)
     * @param altA              Current altitude of A (ft)
     * @param latA              Latitude of A
     * @param lonA              Longitude of A
     * @param actualSepNm       Actual separation distance measured by radar (NM)
     * @param minReqSepNm       Minimum required separation for this aircraft type (NM)
     * @param altDriftScore     Raw altitude-drift sub-score (0-100)
     * @param thirdMetricScore  Speed/FlightPlan/Signal sub-score (0-100)
     * @param csB               Call sign of aircraft B
     * @param altB              Current altitude of B (ft)
     * @param latB              Latitude of B
     * @param lonB              Longitude of B
     */
    public SeparationWorker(String csA, String typeA, int altA, double latA, double lonA,
                            double actualSepNm, double minReqSepNm,
                            double altDriftScore, double thirdMetricScore,
                            String csB, int altB, double latB, double lonB) {
        this.callSignA        = csA;
        this.aircraftTypeA    = typeA.toUpperCase().trim();
        this.altFtA           = altA;
        this.latA             = latA;
        this.lonA             = lonA;
        this.actualSepDistA   = actualSepNm;
        this.minReqSepA       = minReqSepNm;
        this.altDriftScoreA   = altDriftScore;
        this.thirdMetricScoreA = thirdMetricScore;
        this.callSignB        = csB;
        this.altFtB           = altB;
        this.latB             = latB;
        this.lonB             = lonB;
    }

    // ═══════════════════════════════════════════════════════════
    // Callable entry point
    // ═══════════════════════════════════════════════════════════
    @Override
    public SeparationResult call() {
        String tName = Thread.currentThread().getName();
        System.out.printf("[%s] Computing separation: %s ↔ %s%n",
                tName, callSignA, callSignB);

        // 1. Haversine horizontal distance
        double horizNm = haversineNm(latA, lonA, latB, lonB);

        // 2. Vertical separation
        int vertFt = Math.abs(altFtA - altFtB);

        // 3. Collision Risk Index
        //    CRI = (1/H_nm) × (1/V_ft) × 10^6
        double cri = 0.0;
        if (horizNm > 0 && vertFt > 0) {
            cri = (1.0 / horizNm) * (1.0 / vertFt) * 1_000_000.0;
        } else if (horizNm == 0 || vertFt == 0) {
            cri = Double.MAX_VALUE;  // coincident paths
        }

        // 4. Airspace Safety Factor (ASF)
        //    ASF = (ActualSep / MinRequiredSep) × 100
        double asf = (minReqSepA > 0)
                ? (actualSepDistA / minReqSepA) * 100.0
                : 0.0;
        // Cap at 100 (exceeding minimum is perfect)
        asf = Math.min(asf, 100.0);

        // 5. Weighted Overall Safety Score
        double[] weights = getWeights(aircraftTypeA);
        // sub-scores: ASF (sep distance), altDrift, thirdMetric — each 0-100
        double overallScore = (asf * weights[0])
                            + (altDriftScoreA * weights[1])
                            + (thirdMetricScoreA * weights[2]);

        // 6. Determine minimum vertical separation for aircraft A
        int minVert = getMinVerticalSep(aircraftTypeA);

        // 7. TCAS alert: horizontal < 5NM AND vertical < threshold
        boolean tcas = (horizNm < TCAS_HORIZONTAL_THRESHOLD_NM) && (vertFt < minVert);

        // 8. If TCAS alert → score forced below 60 (Emergency Vector)
        if (tcas) overallScore = Math.min(overallScore, 59.9);

        // 9. Safety Band
        String band;
        String clearance;
        if (overallScore >= OPTIMAL_MIN) {
            band      = "OPTIMAL_CORRIDOR";
            clearance = "Unrestricted Flight Path";
        } else if (overallScore >= STANDARD_MIN) {
            band      = "STANDARD_SEPARATION";
            clearance = "Normal Sector Clearance";
        } else if (overallScore >= VECTORING_MIN && !tcas) {
            band      = "VECTORING_ADJUSTMENT";
            clearance = "Altitude/Course Correction Ordered";
        } else {
            band      = "EMERGENCY_VECTOR";
            clearance = tcas
                    ? "TCAS_COLLISION_ALERT — Emergency vectoring issued"
                    : "Immediate Emergency Eviction";
        }

        // Simulate computation workload (realistic thread separation)
        simulateRadarCycle();

        return new SeparationResult(callSignA, callSignB, horizNm, vertFt,
                cri, asf, overallScore, band, clearance, tcas, tName);
    }

    // ═══════════════════════════════════════════════════════════
    // Helper methods
    // ═══════════════════════════════════════════════════════════

    /**
     * Haversine formula: returns great-circle distance in Nautical Miles.
     */
    public static double haversineNm(double lat1, double lon1,
                                     double lat2, double lon2) {
        final double R_NM = 3440.065;    // Earth radius in Nautical Miles
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                 + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                 * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R_NM * c;
    }

    /** Returns weight array [sepDist, altDrift, thirdMetric] for the aircraft type. */
    private double[] getWeights(String type) {
        return switch (type) {
            case "COMMERCIAL_PASSENGER_JET", "CPJ" -> W_CPJ;
            case "CARGO_FREIGHT_AIRCRAFT",   "CFA" -> W_CFA;
            case "UNMANNED_COMMERCIAL_DRONE", "UCD" -> W_UCD;
            default -> W_CPJ;
        };
    }

    /** Returns minimum vertical separation (ft) for the aircraft type. */
    private int getMinVerticalSep(String type) {
        return switch (type) {
            case "UNMANNED_COMMERCIAL_DRONE", "UCD" -> MIN_VERT_SEP_DRONE;
            default -> MIN_VERT_SEP_JET;
        };
    }

    /** Simulates one radar sweep cycle (50 ms) so thread scheduling is visible. */
    private void simulateRadarCycle() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
