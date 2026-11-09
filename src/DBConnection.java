import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * DBConnection.java
 * ─────────────────────────────────────────────────────────────
 * Singleton JDBC connection manager for the ATC Safety DB.
 *
 * Student 2 hosts MySQL on OCI VM 2.
 * Update DB_HOST to that VM's public IP; ensure port 3306 is
 * open in the OCI Ingress Security List and OS firewall.
 * ─────────────────────────────────────────────────────────────
 */
public class DBConnection {

    // ── OCI VM 2 — update these before running ───────────────
    private static final String DB_HOST     = "localhost";   // e.g. "140.238.X.X"
    private static final int    DB_PORT     = 3306;
    private static final String DB_NAME     = "atc_safety_db";
    private static final String DB_USER     = "atc_user";
    private static final String DB_PASSWORD = "ATC@Secure123";
    // ─────────────────────────────────────────────────────────

    private static final String JDBC_URL =
            "jdbc:mysql://" + DB_HOST + ":" + DB_PORT + "/" + DB_NAME
            + "?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true";

    private static Connection instance = null;

    /** Private constructor — singleton pattern. */
    private DBConnection() {}

    /**
     * Returns (or creates) the shared JDBC Connection.
     *
     * @return active {@link Connection}
     * @throws SQLException if the connection cannot be established
     */
    public static synchronized Connection getConnection() throws SQLException {
        if (instance == null || instance.isClosed()) {
            try {
                Class.forName("com.mysql.cj.jdbc.Driver");
                instance = DriverManager.getConnection(JDBC_URL, DB_USER, DB_PASSWORD);
                System.out.println("[DBConnection] Connected to ATC Safety DB on "
                        + DB_HOST + ":" + DB_PORT);
            } catch (ClassNotFoundException e) {
                throw new SQLException("[DBConnection] MySQL JDBC Driver not found. "
                        + "Add mysql-connector-j to your classpath.", e);
            }
        }
        return instance;
    }

    /** Gracefully closes the shared connection. */
    public static synchronized void closeConnection() {
        if (instance != null) {
            try {
                if (!instance.isClosed()) {
                    instance.close();
                    System.out.println("[DBConnection] Connection closed.");
                }
            } catch (SQLException e) {
                System.err.println("[DBConnection] Error closing connection: " + e.getMessage());
            } finally {
                instance = null;
            }
        }
    }

    /**
     * Quick connectivity test.  Run with: javac DBConnection.java && java DBConnection
     */
    public static void main(String[] args) {
        try {
            Connection conn = DBConnection.getConnection();
            System.out.println("[DBConnection] Test SUCCESS — auto-commit: "
                    + conn.getAutoCommit());
        } catch (SQLException e) {
            System.err.println("[DBConnection] Test FAILED: " + e.getMessage());
        } finally {
            closeConnection();
        }
    }
}
