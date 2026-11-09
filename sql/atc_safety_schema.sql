-- ============================================================
-- Project Assignment Set 23
-- Global Air Traffic Control (ATC) Flight Path Safety
-- & Collision Avoidance System
-- Database Schema: atc_safety_schema.sql
-- ============================================================

CREATE DATABASE IF NOT EXISTS atc_safety_db;
USE atc_safety_db;

-- ============================================================
-- TABLE 1: flights
-- Phase 1 — Flight Plan Activation
-- ============================================================
CREATE TABLE IF NOT EXISTS flights (
    flight_id          INT AUTO_INCREMENT PRIMARY KEY,
    call_sign          VARCHAR(10)     NOT NULL UNIQUE,          -- e.g. AAL1234
    aircraft_type      ENUM(
                           'COMMERCIAL_PASSENGER_JET',
                           'CARGO_FREIGHT_AIRCRAFT',
                           'UNMANNED_COMMERCIAL_DRONE'
                       )               NOT NULL,
    assigned_corridor  VARCHAR(50)     NOT NULL,
    altitude_ceiling   INT             NOT NULL,                 -- in feet
    activation_time    TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
    status             ENUM('ACTIVE','CLEARED','EMERGENCY','EVICTED')
                                        DEFAULT 'ACTIVE',
    CONSTRAINT chk_callsign CHECK (call_sign REGEXP '^[A-Z]{3}[0-9]{1,4}$')
);

-- ============================================================
-- TABLE 2: radar_logs
-- Phase 2 — Radar Telemetry Ingestion
-- ============================================================
CREATE TABLE IF NOT EXISTS radar_logs (
    log_id             INT AUTO_INCREMENT PRIMARY KEY,
    flight_id          INT             NOT NULL,
    real_time_altitude INT             NOT NULL,                 -- ft
    ground_speed       DECIMAL(7,2)    NOT NULL,                 -- knots
    heading            DECIMAL(5,2)    NOT NULL,                 -- degrees 0-360
    radar_coord_lat    DECIMAL(10,6)   NOT NULL,
    radar_coord_lon    DECIMAL(10,6)   NOT NULL,
    recorded_at        TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_radar_flight FOREIGN KEY (flight_id)
        REFERENCES flights(flight_id) ON DELETE CASCADE
);

-- ============================================================
-- TABLE 3: separation_breaches
-- Phase 3 — Sector Exit Audit (violation events)
-- ============================================================
CREATE TABLE IF NOT EXISTS separation_breaches (
    breach_id              INT AUTO_INCREMENT PRIMARY KEY,
    flight_id_a            INT             NOT NULL,
    flight_id_b            INT             NOT NULL,
    horizontal_dist_nm     DECIMAL(8,4)    NOT NULL,            -- Nautical Miles
    vertical_dist_ft       INT             NOT NULL,            -- ft
    breach_type            ENUM(
                               'HORIZONTAL_VIOLATION',
                               'VERTICAL_VIOLATION',
                               'TCAS_COLLISION_ALERT'
                           )               NOT NULL,
    collision_risk_index   DECIMAL(15,6)   DEFAULT 0,
    detected_at            TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_breach_a FOREIGN KEY (flight_id_a)
        REFERENCES flights(flight_id),
    CONSTRAINT fk_breach_b FOREIGN KEY (flight_id_b)
        REFERENCES flights(flight_id)
);

-- ============================================================
-- TABLE 4: atc_incidents
-- Combined audit + grading per flight
-- ============================================================
CREATE TABLE IF NOT EXISTS atc_incidents (
    incident_id               INT AUTO_INCREMENT PRIMARY KEY,
    flight_id                 INT             NOT NULL UNIQUE,

    -- Phase 3 Sector-Exit Audit Fields
    sep_proximity_violations  INT             DEFAULT 0,
    radio_contact_delay_sec   DECIMAL(6,2)    DEFAULT 0,
    descent_profile_score     DECIMAL(5,2)    DEFAULT 0,         -- 0-100

    -- Computed Safety Metrics
    airspace_safety_factor    DECIMAL(8,4)    DEFAULT 0,         -- ASF %
    overall_safety_score      DECIMAL(5,2)    DEFAULT 0,         -- 0-100
    safety_band               ENUM(
                                  'OPTIMAL_CORRIDOR',
                                  'STANDARD_SEPARATION',
                                  'VECTORING_ADJUSTMENT',
                                  'EMERGENCY_VECTOR'
                              )               DEFAULT 'STANDARD_SEPARATION',
    clearance_decision        VARCHAR(100),
    collision_alert_flagged   BOOLEAN         DEFAULT FALSE,
    audit_time                TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_incident_flight FOREIGN KEY (flight_id)
        REFERENCES flights(flight_id) ON DELETE CASCADE
);

-- ============================================================
-- Indexes for performance
-- ============================================================
CREATE INDEX idx_radar_flight   ON radar_logs(flight_id);
CREATE INDEX idx_breach_a       ON separation_breaches(flight_id_a);
CREATE INDEX idx_breach_b       ON separation_breaches(flight_id_b);
CREATE INDEX idx_incident_band  ON atc_incidents(safety_band);

-- ============================================================
-- Sample seed data for testing
-- ============================================================
INSERT INTO flights (call_sign, aircraft_type, assigned_corridor, altitude_ceiling)
VALUES
    ('AAL101',  'COMMERCIAL_PASSENGER_JET',    'CORRIDOR_ALPHA',   35000),
    ('FDX202',  'CARGO_FREIGHT_AIRCRAFT',      'CORRIDOR_BRAVO',   28000),
    ('DRN303',  'UNMANNED_COMMERCIAL_DRONE',   'CORRIDOR_DELTA',    1200),
    ('UAL404',  'COMMERCIAL_PASSENGER_JET',    'CORRIDOR_ALPHA',   37000),
    ('UPS505',  'CARGO_FREIGHT_AIRCRAFT',      'CORRIDOR_CHARLIE', 30000);

INSERT INTO radar_logs (flight_id, real_time_altitude, ground_speed, heading, radar_coord_lat, radar_coord_lon)
VALUES
    (1, 34800, 480.5, 270.0, 40.712800,  -74.006000),
    (2, 27500, 420.0, 180.0, 40.730000,  -74.020000),
    (3,  1100,  60.0,  90.0, 40.715000,  -74.008000),
    (4, 36500, 510.0, 275.0, 40.714000,  -74.005500),
    (5, 29800, 390.0, 185.0, 40.728000,  -74.018000);
