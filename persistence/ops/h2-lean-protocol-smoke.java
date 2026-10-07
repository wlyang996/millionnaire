// JDK21 source launcher; invoked by h2-lean-smoke.sh. No Store implementation is tested here.
// Fresh memory H2 only. Tests JDBC lock timeout/rollback and the 5.7-compatible SQL shape.
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import org.h2.tools.RunScript;

class H2LeanProtocolSmoke {
    static int cases;
    static void check(String name, boolean ok) {
        cases++;
        if (!ok) throw new AssertionError(name);
        System.out.println("protocol ok: " + name);
    }
    static long scalar(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            if (!r.next()) throw new AssertionError("missing scalar");
            return r.getLong(1);
        }
    }
    static void exec(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) { s.execute(sql); }
    }
    static void record(Connection c, long id, long user) throws SQLException {
        exec(c, "INSERT INTO game_record (record_id,room_id,game_no,outcome,end_reason,end_mode,time_limit_min,"
            + "board_id,initial_cash,player_count,started_at,ended_at,engine_version,config_hash,draft_sha256,created_at) "
            + "VALUES (" + id + ",1," + id + ",'FINISHED','ALL_AWAY','BANKRUPTCY',NULL,'classic',1,1,0,100,'v','h',"
            + "X'0000000000000000000000000000000000000000000000000000000000000000',100)");
        exec(c, "INSERT INTO game_record_player VALUES (" + id + ",0," + user + ",100,1,1,1,'ALIVE',1)");
    }
    static int trimPage(Connection c, long user) throws SQLException {
        c.setAutoCommit(false);
        try {
            try (PreparedStatement p = c.prepareStatement("SELECT status FROM app_user WHERE user_id=? FOR UPDATE")) {
                p.setLong(1, user);
                try (ResultSet r = p.executeQuery()) {
                    if (!r.next() || !"ACTIVE".equals(r.getString(1))) throw new AssertionError("not active");
                }
            }
            long ended, id;
            try (PreparedStatement p = c.prepareStatement("SELECT ended_at,record_id FROM game_record_player "
                    + "WHERE user_id=? AND user_live=1 ORDER BY ended_at DESC,record_id DESC LIMIT 19,1")) {
                p.setLong(1, user);
                try (ResultSet r = p.executeQuery()) {
                    if (!r.next()) { c.commit(); return 0; }
                    ended = r.getLong(1); id = r.getLong(2);
                }
            }
            int count;
            try (PreparedStatement p = c.prepareStatement("DELETE FROM game_record_player WHERE user_id=? AND user_live=1 "
                    + "AND (ended_at < ? OR (ended_at = ? AND record_id < ?)) LIMIT 1000")) {
                p.setLong(1, user); p.setLong(2, ended); p.setLong(3, ended); p.setLong(4, id);
                count = p.executeUpdate();
            }
            c.commit(); return count;
        } catch (Throwable e) { c.rollback(); throw e; }
        finally { c.setAutoCommit(true); }
    }
    public static void main(String[] args) throws Exception {
        String url = "jdbc:h2:mem:protocol;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE";
        try (Connection a = DriverManager.getConnection(url); Connection b = DriverManager.getConnection(url)) {
            try (FileReader reader = new FileReader(args[0], StandardCharsets.UTF_8)) { RunScript.execute(a, reader); }
            for (long id = 1; id <= 3; id++)
                exec(a, "INSERT INTO app_user VALUES (" + id + ",'x',0,'ACTIVE',0,0,0,NULL,1)");
            exec(a, "INSERT INTO room (room_id,room_code,held_code,created_by,create_request_id,status,created_at,updated_at) "
                + "VALUES (1,1,1,1,X'00000000000000000000000000000001','OPEN',0,0)");
            a.setAutoCommit(false);
            exec(a, "SELECT room_id FROM room WHERE room_id=1 FOR UPDATE");
            exec(b, "SET LOCK_TIMEOUT 150");
            b.setAutoCommit(false);
            record(b, 9000, 2); // Earlier successful statements must be rolled back after timeout.
            boolean timedOut = false;
            long start = System.nanoTime();
            try { exec(b, "UPDATE room SET updated_at=1 WHERE room_id=1"); }
            catch (SQLException e) { timedOut = e.getErrorCode() == 50200; }
            finally { b.rollback(); a.rollback(); a.setAutoCommit(true); b.setAutoCommit(true); }
            check("I3 lock timeout has expected code and bounded H2 wait", timedOut && System.nanoTime()-start < 5_000_000_000L);
            check("I3 timeout rollback removes header and detail", scalar(a,"SELECT COUNT(*) FROM game_record WHERE record_id=9000")==0
                && scalar(a,"SELECT COUNT(*) FROM game_record_player WHERE record_id=9000")==0);
            a.setAutoCommit(false);
            for (int i=1; i<=1105; i++) record(a,10000+i,1);
            for (int i=1; i<=21; i++) { record(a,12000+i,2); record(a,13000+i,3); }
            a.commit(); a.setAutoCommit(true);
            int first = trimPage(a,1), second = trimPage(a,1);
            check("trim SQL deletes bounded 1000 then 85", first==1000 && second==85);
            check("trim ties retain newest 20 record IDs", scalar(a,"SELECT COUNT(*) FROM game_record_player WHERE user_id=1")==20
                && scalar(a,"SELECT MIN(record_id) FROM game_record_player WHERE user_id=1")==11086);
            String sweep = "SELECT user_id FROM game_record_player WHERE user_live=1 AND user_id > %d "
                + "GROUP BY user_id HAVING COUNT(*) > 20 ORDER BY user_id LIMIT 1";
            long cursor = scalar(a,sweep.formatted(0));
            check("sweep cursor continues past first page", cursor==2 && scalar(a,sweep.formatted(cursor))==3);
        }
        System.out.println("protocol_cases=" + cases + " failures=0");
    }
}
