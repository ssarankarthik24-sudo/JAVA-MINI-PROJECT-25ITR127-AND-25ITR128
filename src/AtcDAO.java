import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * AtcDAO.java
 * ─────────────────────────────────────────────────────────────
 * Data Access Object — all CRUD operations for the ATC Safety
 * Database (flights, radar_logs, separation_breaches,
 * atc_incidents).
 *
 * Uses PreparedStatements throughout to prevent SQL injection.
 * ─────────────────────────────────────────────────────────────
 */
public class AtcDAO {

    // ══════════════════════════════════════════════════════════
    // Inner model classes (plain POJOs)
    // ══════════════════════════════════════════════════════════

    /** Represents a row in the `flights` table (Phase 1). */
    public static class Flight {
        public int    flightId;
        public String callSign;
        public String aircraftType;
        public String assignedCorridor;
        public int    altitudeCeiling;
        public String status;

        @Override
        public String toString() {
            return String.format("Flight[%d | %s | %s | %s | Ceil:%dft | %s]",
                    flightId, callSign, aircraftType,
                    assignedCorridor, altitudeCeiling, status);
        }
    }

    /** Represents a row in the `radar_logs` table (Phase 2). */
    public static class RadarLog {
        public int    logId;
        public int    flightId;
        public int    realTimeAltitude;
        public double groundSpeed;
        public double heading;
        public double lat;
        public double lon;

        @Override
        public String toString() {
            return String.format(
                    "RadarLog[flt:%d | Alt:%dft | Spd:%.1fkt | Hdg:%.1f° | (%.6f,%.6f)]",
                    flightId, realTimeAltitude, groundSpeed, heading, lat, lon);
        }
    }

    /** Represents a row in the `atc_incidents` table (Phase 3 + scoring). */
    public static class AtcIncident {
        public int    incidentId;
        public int    flightId;
        public int    sepProximityViolations;
        public double radioContactDelaySec;
        public double descentProfileScore;
        public double airspaceSafetyFactor;
        public double overallSafetyScore;
        public String safetyBand;
        public String clearanceDecision;
        public boolean collisionAlertFlagged;

        @Override
        public String toString() {
            return String.format(
                    "Incident[flt:%d | ASF:%.2f | Score:%.1f | Band:%s | Alert:%b | %s]",
                    flightId, airspaceSafetyFactor, overallSafetyScore,
                    safetyBand, collisionAlertFlagged, clearanceDecision);
        }
    }

    // ══════════════════════════════════════════════════════════
    // Phase 1 — Flight Plan Activation
    // ══════════════════════════════════════════════════════════

    /**
     * Inserts a new flight plan into the database.
     *
     * @param callSign         validated call sign ([A-Z]{3}\d{1,4})
     * @param aircraftType     one of the three aircraft type constants
     * @param assignedCorridor sector/corridor name (trimmed before insert)
     * @param altitudeCeiling  assigned altitude ceiling in feet
     * @return generated flight_id, or -1 on failure
     */
    public int insertFlight(String callSign, String aircraftType,
                            String assignedCorridor, int altitudeCeiling)
            throws SQLException {

        String sql = "INSERT INTO flights (call_sign, aircraft_type, assigned_corridor, altitude_ceiling) "
                   + "VALUES (?, ?, ?, ?)";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

            ps.setString(1, callSign.trim().toUpperCase());
            ps.setString(2, aircraftType.trim());
            ps.setString(3, assignedCorridor.trim());    // .trim() per spec
            ps.setInt(4, altitudeCeiling);
            ps.executeUpdate();

            ResultSet rs = ps.getGeneratedKeys();
            if (rs.next()) {
                int id = rs.getInt(1);
                System.out.printf("[AtcDAO] Flight inserted: %s → flight_id=%d%n", callSign, id);
                return id;
            }
        }
        return -1;
    }

    /**
     * Retrieves a {@link Flight} by its call sign.
     *
     * @param callSign flight call sign
     * @return Flight object, or null if not found
     */
    public Flight getFlightByCallSign(String callSign) throws SQLException {
        String sql = "SELECT * FROM flights WHERE call_sign = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, callSign.trim().toUpperCase());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return mapFlight(rs);
            }
        }
        return null;
    }

    /** Returns all active flights in the database. */
    public List<Flight> getAllActiveFlights() throws SQLException {
        List<Flight> list = new ArrayList<>();
        String sql = "SELECT * FROM flights WHERE status = 'ACTIVE'";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) list.add(mapFlight(rs));
        }
        return list;
    }

    // ══════════════════════════════════════════════════════════
    // Phase 2 — Radar Telemetry Ingestion
    // ══════════════════════════════════════════════════════════

    /**
     * Inserts a radar telemetry snapshot for a given flight.
     *
     * @param flightId         FK referencing flights.flight_id
     * @param altitudeFt       real-time altitude in feet
     * @param groundSpeedKt    ground speed in knots
     * @param headingDeg       heading in degrees (0–360)
     * @param lat              radar coordinate latitude
     * @param lon              radar coordinate longitude
     * @return generated log_id, or -1 on failure
     */
    public int insertRadarLog(int flightId, int altitudeFt, double groundSpeedKt,
                              double headingDeg, double lat, double lon)
            throws SQLException {

        String sql = "INSERT INTO radar_logs (flight_id, real_time_altitude, ground_speed, heading, "
                   + "radar_coord_lat, radar_coord_lon) VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

            ps.setInt(1, flightId);
            ps.setInt(2, altitudeFt);
            ps.setDouble(3, groundSpeedKt);
            ps.setDouble(4, headingDeg);
            ps.setDouble(5, lat);
            ps.setDouble(6, lon);
            ps.executeUpdate();

            ResultSet rs = ps.getGeneratedKeys();
            if (rs.next()) return rs.getInt(1);
        }
        return -1;
    }

    /** Returns the latest radar log for a given flight. */
    public RadarLog getLatestRadarLog(int flightId) throws SQLException {
        String sql = "SELECT * FROM radar_logs WHERE flight_id = ? "
                   + "ORDER BY recorded_at DESC LIMIT 1";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setInt(1, flightId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return mapRadarLog(rs);
        }
        return null;
    }

    /** Returns all radar logs for a given flight (time-ordered). */
    public List<RadarLog> getRadarLogsByFlight(int flightId) throws SQLException {
        List<RadarLog> list = new ArrayList<>();
        String sql = "SELECT * FROM radar_logs WHERE flight_id = ? ORDER BY recorded_at";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setInt(1, flightId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(mapRadarLog(rs));
        }
        return list;
    }

    // ══════════════════════════════════════════════════════════
    // Phase 3 — Separation Breach & Incident Recording
    // ══════════════════════════════════════════════════════════

    /**
     * Records a separation breach between two aircraft.
     *
     * @param flightIdA          first flight
     * @param flightIdB          second flight
     * @param horizontalDistNm   horizontal distance in Nautical Miles
     * @param verticalDistFt     vertical separation in feet
     * @param breachType         HORIZONTAL_VIOLATION | VERTICAL_VIOLATION | TCAS_COLLISION_ALERT
     * @param collisionRiskIndex computed CRI value
     */
    public void insertSeparationBreach(int flightIdA, int flightIdB,
                                       double horizontalDistNm, int verticalDistFt,
                                       String breachType, double collisionRiskIndex)
            throws SQLException {

        String sql = "INSERT INTO separation_breaches "
                   + "(flight_id_a, flight_id_b, horizontal_dist_nm, vertical_dist_ft, "
                   + " breach_type, collision_risk_index) VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setInt(1, flightIdA);
            ps.setInt(2, flightIdB);
            ps.setDouble(3, horizontalDistNm);
            ps.setInt(4, verticalDistFt);
            ps.setString(5, breachType.trim());
            ps.setDouble(6, collisionRiskIndex);
            ps.executeUpdate();
            System.out.printf("[AtcDAO] Breach recorded: %s between flt%d↔flt%d (H=%.2fNM, V=%dft)%n",
                    breachType, flightIdA, flightIdB, horizontalDistNm, verticalDistFt);
        }
    }

    /**
     * Inserts or updates the ATC incident audit record for a flight.
     *
     * @param incident fully-populated {@link AtcIncident}
     */
    public void upsertAtcIncident(AtcIncident incident) throws SQLException {
        String sql = "INSERT INTO atc_incidents "
                   + "(flight_id, sep_proximity_violations, radio_contact_delay_sec, "
                   + " descent_profile_score, airspace_safety_factor, overall_safety_score, "
                   + " safety_band, clearance_decision, collision_alert_flagged) "
                   + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) "
                   + "ON DUPLICATE KEY UPDATE "
                   + "sep_proximity_violations=VALUES(sep_proximity_violations), "
                   + "radio_contact_delay_sec=VALUES(radio_contact_delay_sec), "
                   + "descent_profile_score=VALUES(descent_profile_score), "
                   + "airspace_safety_factor=VALUES(airspace_safety_factor), "
                   + "overall_safety_score=VALUES(overall_safety_score), "
                   + "safety_band=VALUES(safety_band), "
                   + "clearance_decision=VALUES(clearance_decision), "
                   + "collision_alert_flagged=VALUES(collision_alert_flagged)";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setInt(1, incident.flightId);
            ps.setInt(2, incident.sepProximityViolations);
            ps.setDouble(3, incident.radioContactDelaySec);
            ps.setDouble(4, incident.descentProfileScore);
            ps.setDouble(5, incident.airspaceSafetyFactor);
            ps.setDouble(6, incident.overallSafetyScore);
            ps.setString(7, incident.safetyBand);
            ps.setString(8, incident.clearanceDecision);
            ps.setBoolean(9, incident.collisionAlertFlagged);
            ps.executeUpdate();
            System.out.printf("[AtcDAO] Incident upserted: flt%d → %s%n",
                    incident.flightId, incident.safetyBand);
        }
    }

    /** Updates flight status (ACTIVE / CLEARED / EMERGENCY / EVICTED). */
    public void updateFlightStatus(int flightId, String newStatus) throws SQLException {
        String sql = "UPDATE flights SET status = ? WHERE flight_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, newStatus.trim());
            ps.setInt(2, flightId);
            ps.executeUpdate();
        }
    }

    /** Returns all recorded incidents (for export / reporting). */
    public List<AtcIncident> getAllIncidents() throws SQLException {
        List<AtcIncident> list = new ArrayList<>();
        String sql = "SELECT * FROM atc_incidents ORDER BY overall_safety_score ASC";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) list.add(mapIncident(rs));
        }
        return list;
    }

    // ══════════════════════════════════════════════════════════
    // Private mapping helpers
    // ══════════════════════════════════════════════════════════

    private Flight mapFlight(ResultSet rs) throws SQLException {
        Flight f = new Flight();
        f.flightId        = rs.getInt("flight_id");
        f.callSign        = rs.getString("call_sign");
        f.aircraftType    = rs.getString("aircraft_type");
        f.assignedCorridor = rs.getString("assigned_corridor");
        f.altitudeCeiling = rs.getInt("altitude_ceiling");
        f.status          = rs.getString("status");
        return f;
    }

    private RadarLog mapRadarLog(ResultSet rs) throws SQLException {
        RadarLog r = new RadarLog();
        r.logId            = rs.getInt("log_id");
        r.flightId         = rs.getInt("flight_id");
        r.realTimeAltitude = rs.getInt("real_time_altitude");
        r.groundSpeed      = rs.getDouble("ground_speed");
        r.heading          = rs.getDouble("heading");
        r.lat              = rs.getDouble("radar_coord_lat");
        r.lon              = rs.getDouble("radar_coord_lon");
        return r;
    }

    private AtcIncident mapIncident(ResultSet rs) throws SQLException {
        AtcIncident i = new AtcIncident();
        i.incidentId             = rs.getInt("incident_id");
        i.flightId               = rs.getInt("flight_id");
        i.sepProximityViolations = rs.getInt("sep_proximity_violations");
        i.radioContactDelaySec   = rs.getDouble("radio_contact_delay_sec");
        i.descentProfileScore    = rs.getDouble("descent_profile_score");
        i.airspaceSafetyFactor   = rs.getDouble("airspace_safety_factor");
        i.overallSafetyScore     = rs.getDouble("overall_safety_score");
        i.safetyBand             = rs.getString("safety_band");
        i.clearanceDecision      = rs.getString("clearance_decision");
        i.collisionAlertFlagged  = rs.getBoolean("collision_alert_flagged");
        return i;
    }
}
