package io.pipemesh.console.apikey;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Data access for keys. */
@Repository
public class ApiKeyRepository {

    private static final RowMapper<ApiKey> KEY = (ResultSet rows, int index) -> new ApiKey(
            rows.getString("id"),
            rows.getString("organization_id"),
            rows.getString("name"),
            rows.getString("prefix"),
            instantOf(rows.getTimestamp("created_at")),
            instantOf(rows.getTimestamp("last_used_at")),
            instantOf(rows.getTimestamp("revoked_at")));

    private final JdbcTemplate jdbc;

    public ApiKeyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(ApiKey key, String keyHash) {
        jdbc.update("""
                INSERT INTO console_api_key (id, organization_id, name, key_hash, prefix)
                VALUES (?, ?, ?, ?, ?)
                """, key.id(), key.organizationId(), key.name(), keyHash, key.prefix());
    }

    public List<ApiKey> forOrganization(String organizationId) {
        return jdbc.query("""
                SELECT * FROM console_api_key
                 WHERE organization_id = ? AND revoked_at IS NULL
                 ORDER BY created_at
                """, KEY, organizationId);
    }

    /**
     * Revokes a key, scoped to the organization that owns it.
     *
     * <p>The organization is in the WHERE clause rather than checked beforehand:
     * a check and a write are two moments, and only one of them is the one that
     * matters.
     *
     * @return whether anything was revoked
     */
    public boolean revoke(String id, String organizationId, Instant at) {
        return jdbc.update("""
                UPDATE console_api_key SET revoked_at = ?
                 WHERE id = ? AND organization_id = ? AND revoked_at IS NULL
                """, Timestamp.from(at), id, organizationId) == 1;
    }

    /**
     * The lookup behind every call the runtime receives.
     *
     * <p>Answers with the organization and its plan's permissions in one query.
     * Splitting it would mean two round trips on the hot path of every single
     * execution start.
     */
    public Optional<KeyHolder> holderOf(String keyHash) {
        List<KeyHolder> holders = jdbc.query("""
                SELECT k.id, k.organization_id, p.permissions
                  FROM console_api_key k
                  JOIN console_organization o ON o.id = k.organization_id
                  JOIN console_plan p ON p.id = o.plan_id
                 WHERE k.key_hash = ? AND k.revoked_at IS NULL
                """, (ResultSet rows, int index) -> new KeyHolder(
                        rows.getString("id"),
                        rows.getString("organization_id"),
                        List.of((String[]) rows.getArray("permissions").getArray())),
                keyHash);

        return holders.isEmpty() ? Optional.empty() : Optional.of(holders.get(0));
    }

    public void markUsed(String id, Instant at) {
        jdbc.update("UPDATE console_api_key SET last_used_at = ? WHERE id = ?",
                Timestamp.from(at), id);
    }

    private static Instant instantOf(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
