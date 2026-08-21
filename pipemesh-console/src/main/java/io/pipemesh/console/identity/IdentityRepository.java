package io.pipemesh.console.identity;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Data access for accounts. Queries only — every rule lives in the service. */
@Repository
public class IdentityRepository {

    private static final RowMapper<ConsoleUser> USER = (ResultSet rows, int index) -> new ConsoleUser(
            rows.getString("id"),
            rows.getString("organization_id"),
            rows.getString("email"),
            rows.getString("password_hash"),
            instantOf(rows.getTimestamp("verified_at")));

    private static final RowMapper<Organization> ORGANIZATION =
            (ResultSet rows, int index) -> new Organization(
                    rows.getString("id"),
                    rows.getString("name"),
                    rows.getString("plan_id"),
                    instantOf(rows.getTimestamp("created_at")));

    private final JdbcTemplate jdbc;

    public IdentityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertOrganization(Organization organization) {
        jdbc.update("INSERT INTO console_organization (id, name, plan_id) VALUES (?, ?, ?)",
                organization.id(), organization.name(), organization.planId());
    }

    public void insertUser(ConsoleUser user) {
        jdbc.update("""
                INSERT INTO console_user (id, organization_id, email, password_hash)
                VALUES (?, ?, ?, ?)
                """, user.id(), user.organizationId(), user.email(), user.passwordHash());
    }

    public Optional<ConsoleUser> findUserByEmail(String email) {
        return single(jdbc.query("SELECT * FROM console_user WHERE email = ?", USER, email));
    }

    public Optional<ConsoleUser> findUser(String id) {
        return single(jdbc.query("SELECT * FROM console_user WHERE id = ?", USER, id));
    }

    public Optional<Organization> findOrganization(String id) {
        return single(jdbc.query("SELECT * FROM console_organization WHERE id = ?", ORGANIZATION, id));
    }

    public void markVerified(String userId, Instant at) {
        jdbc.update("UPDATE console_user SET verified_at = ? WHERE id = ?",
                Timestamp.from(at), userId);
    }

    public void insertVerification(String tokenHash, String userId, Instant expiresAt) {
        jdbc.update("""
                INSERT INTO console_verification (token_hash, user_id, expires_at)
                VALUES (?, ?, ?)
                """, tokenHash, userId, Timestamp.from(expiresAt));
    }

    /**
     * Claims a verification link, once.
     *
     * <p>The check and the write are one statement, so two clicks arriving
     * together cannot both find it unused. Doing this as read-then-write would
     * leave exactly that gap.
     *
     * @return the user the link belonged to, or empty when it was used, expired
     *         or never existed
     */
    public Optional<String> claimVerification(String tokenHash, Instant now) {
        return single(jdbc.query("""
                UPDATE console_verification
                   SET used_at = ?
                 WHERE token_hash = ? AND used_at IS NULL AND expires_at > ?
                RETURNING user_id
                """, (ResultSet rows, int index) -> rows.getString("user_id"),
                Timestamp.from(now), tokenHash, Timestamp.from(now)));
    }

    public void insertSession(String tokenHash, String userId, Instant expiresAt) {
        jdbc.update("""
                INSERT INTO console_session (token_hash, user_id, expires_at)
                VALUES (?, ?, ?)
                """, tokenHash, userId, Timestamp.from(expiresAt));
    }

    public Optional<String> findSessionUser(String tokenHash, Instant now) {
        return single(jdbc.query("""
                SELECT user_id FROM console_session
                 WHERE token_hash = ? AND expires_at > ?
                """, (ResultSet rows, int index) -> rows.getString("user_id"),
                tokenHash, Timestamp.from(now)));
    }

    public void deleteSession(String tokenHash) {
        jdbc.update("DELETE FROM console_session WHERE token_hash = ?", tokenHash);
    }

    private static <T> Optional<T> single(List<T> rows) {
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private static Instant instantOf(Timestamp timestamp) throws SQLException {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
