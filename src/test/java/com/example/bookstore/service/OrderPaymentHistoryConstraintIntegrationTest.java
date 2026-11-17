package com.example.bookstore.service;

import com.example.bookstore.base.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Evidence for 4C structural hardening: once-per-lifetime order/payment history rows
 * are enforced by a partial UNIQUE index (V11), not only by {@code @Version} optimistic
 * locking and {@code findByIdForUpdate}. Direct inserts are used deliberately so the
 * test exercises the database constraint rather than the service-layer guards.
 */
@TestPropertySource(properties = {
        "app.outbox.poll-interval=3600000",
        "app.outbox.reap-interval-ms=3600000"
})
class OrderPaymentHistoryConstraintIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        // Seed users are 'admin'/'testuser'; cascades remove orders, payments and history.
        jdbcTemplate.update("DELETE FROM app_user WHERE username LIKE 'hist-test-%'");
    }

    private long insertUser() {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO app_user (username, password, email, role) VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, "hist-test-" + UUID.randomUUID());
            ps.setString(2, "unused");
            ps.setString(3, "history@example.com");
            ps.setString(4, "ROLE_USER");
            return ps;
        }, keyHolder);
        return ((Number) keyHolder.getKeyList().get(0).get("id")).longValue();
    }

    private long insertOrder() {
        long userId = insertUser();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO orders (user_id, created_at, total_price, status, version) VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, userId);
            ps.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now()));
            ps.setBigDecimal(3, new BigDecimal("12345.6789"));
            ps.setString(4, "PENDING");
            ps.setLong(5, 0L);
            return ps;
        }, keyHolder);
        return ((Number) keyHolder.getKeyList().get(0).get("id")).longValue();
    }

    private long insertPayment(long orderId, String status) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO payment (order_id, payment_provider, payment_status, amount, currency, created_at, version) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, orderId);
            ps.setString(2, "DUMMY");
            ps.setString(3, status);
            ps.setBigDecimal(4, new BigDecimal("10.0000"));
            ps.setString(5, "USD");
            ps.setTimestamp(6, Timestamp.valueOf(LocalDateTime.now()));
            ps.setLong(7, 0L);
            return ps;
        }, keyHolder);
        return ((Number) keyHolder.getKeyList().get(0).get("id")).longValue();
    }

    private void insertOrderHistory(long orderId, String previous, String next) {
        jdbcTemplate.update(
                "INSERT INTO order_status_history (order_id, previous_status, new_status, changed_by, comment, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                orderId, previous, next, "test", "constraint test", Timestamp.valueOf(LocalDateTime.now()));
    }

    private void insertPaymentHistory(long paymentId, String previous, String next) {
        jdbcTemplate.update(
                "INSERT INTO payment_history (payment_id, previous_status, new_status, changed_by, reason, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                paymentId, previous, next, "test", "constraint test", Timestamp.valueOf(LocalDateTime.now()));
    }

    private int countOrderHistory(long orderId, String status) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_status_history WHERE order_id = ? AND new_status = ?",
                Integer.class, orderId, status);
    }

    private int countPaymentHistory(long paymentId, String status) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_history WHERE payment_id = ? AND new_status = ?",
                Integer.class, paymentId, status);
    }

    /** B1 probe: run before V11 exists; both queries must return zero rows for the migration to apply. */
    @Test
    @DisplayName("B1: no pre-existing duplicate once-per-lifetime history rows")
    void preExistingViolationsAreZero() {
        List<Map<String, Object>> orderViolations = jdbcTemplate.queryForList("""
                SELECT order_id, new_status, COUNT(*) AS occurrences
                  FROM order_status_history
                 WHERE new_status IN ('PAID', 'SHIPPED', 'DELIVERED', 'CANCELLED')
                 GROUP BY order_id, new_status
                HAVING COUNT(*) > 1
                """);
        List<Map<String, Object>> paymentViolations = jdbcTemplate.queryForList("""
                SELECT payment_id, new_status, COUNT(*) AS occurrences
                  FROM payment_history
                 WHERE new_status IN ('CANCELLED', 'REFUNDED')
                 GROUP BY payment_id, new_status
                HAVING COUNT(*) > 1
                """);
        System.out.println("B1_ORDER_VIOLATIONS=" + orderViolations.size());
        System.out.println("B1_PAYMENT_VIOLATIONS=" + paymentViolations.size());
        assertEquals(0, orderViolations.size(), "order history has pre-existing duplicates");
        assertEquals(0, paymentViolations.size(), "payment history has pre-existing duplicates");
    }

    @Test
    @DisplayName("R-HISTORY-1a: a duplicate Order once-per-lifetime row is rejected regardless of previous_status")
    void directInsertOfDuplicateOrderOncePerLifetimeThrowsAfterV11() {
        long orderId = insertOrder();
        insertOrderHistory(orderId, "PENDING", "PAID");

        assertThrows(DataIntegrityViolationException.class,
                () -> insertOrderHistory(orderId, "PROCESSING", "PAID"),
                "the (order_id, new_status) key must reject a second PAID row from any source state");
        assertEquals(1, countOrderHistory(orderId, "PAID"));
    }

    @Test
    @DisplayName("R-HISTORY-1b: a duplicate Payment REFUNDED row is rejected")
    void directInsertOfDuplicatePaymentOncePerLifetimeThrowsAfterV11() {
        long paymentId = insertPayment(insertOrder(), "REFUNDED");
        insertPaymentHistory(paymentId, "REFUND_PROCESSING", "REFUNDED");

        assertThrows(DataIntegrityViolationException.class,
                () -> insertPaymentHistory(paymentId, "REFUND_PROCESSING", "REFUNDED"),
                "a second REFUNDED row for the same payment must be rejected");
        assertEquals(1, countPaymentHistory(paymentId, "REFUNDED"));
    }

    @Test
    @DisplayName("R-HISTORY-3: a refund-failure restore writes a second COMPLETED row (critical gate)")
    void refundFailureRestoreStillInserts() {
        long paymentId = insertPayment(insertOrder(), "COMPLETED");
        insertPaymentHistory(paymentId, "PROCESSING", "COMPLETED");
        insertPaymentHistory(paymentId, "REFUND_PROCESSING", "COMPLETED");

        assertEquals(2, countPaymentHistory(paymentId, "COMPLETED"),
                "COMPLETED is legitimately re-enterable via refund-failure restore and must not be constrained");
    }

    @Test
    @DisplayName("R-HISTORY-4: a retry-then-refailure writes two FAILED rows (critical gate)")
    void paymentRetryAndRefailureStillInserts() {
        long paymentId = insertPayment(insertOrder(), "FAILED");
        insertPaymentHistory(paymentId, "PROCESSING", "FAILED");
        insertPaymentHistory(paymentId, "PROCESSING", "FAILED");

        assertEquals(2, countPaymentHistory(paymentId, "FAILED"),
                "FAILED is legitimately re-enterable through FAILED -> PENDING -> PROCESSING -> FAILED");
    }

    @Test
    @DisplayName("R-HISTORY-5: a legitimate PENDING -> PAID -> SHIPPED -> DELIVERED progression inserts all rows")
    void legitimateOrderProgressionStillInserts() {
        long orderId = insertOrder();
        insertOrderHistory(orderId, "PENDING", "PAID");
        insertOrderHistory(orderId, "PAID", "SHIPPED");
        insertOrderHistory(orderId, "SHIPPED", "DELIVERED");

        assertEquals(1, countOrderHistory(orderId, "PAID"));
        assertEquals(1, countOrderHistory(orderId, "SHIPPED"));
        assertEquals(1, countOrderHistory(orderId, "DELIVERED"));
    }

    @Test
    @DisplayName("R-HISTORY-6: V11 is applied and pre-existing history rows survive migration")
    void seededRowsSurvive() {
        Integer applied = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '11' AND success = true",
                Integer.class);
        assertEquals(1, applied, "V11 must be applied by Flyway in the test build");

        // The Testcontainers database is shared across test classes, so seed users may have
        // been removed by another class. Capture whatever history rows exist right now and
        // assert every one of them survives the migration and our own inserts.
        List<Long> orderRowsBefore = jdbcTemplate.queryForList("SELECT id FROM order_status_history", Long.class);
        List<Long> paymentRowsBefore = jdbcTemplate.queryForList("SELECT id FROM payment_history", Long.class);

        long orderId = insertOrder();
        insertOrderHistory(orderId, "PENDING", "PAID");
        assertEquals(1, countOrderHistory(orderId, "PAID"),
                "legitimate once-per-lifetime rows remain insertable after V11");

        List<Long> orderRowsAfter = jdbcTemplate.queryForList("SELECT id FROM order_status_history", Long.class);
        List<Long> paymentRowsAfter = jdbcTemplate.queryForList("SELECT id FROM payment_history", Long.class);
        assertTrue(orderRowsAfter.containsAll(orderRowsBefore),
                "pre-existing order history rows must survive V11");
        assertTrue(paymentRowsAfter.containsAll(paymentRowsBefore),
                "pre-existing payment history rows must survive V11");
    }

    @Test
    @DisplayName("R-HISTORY-7: two PENDING -> PAID rows for one order — the concrete duplicate race")
    void orderPaidDuplicateRejected() {
        long orderId = insertOrder();
        insertOrderHistory(orderId, "PENDING", "PAID");

        assertThrows(DataIntegrityViolationException.class,
                () -> insertOrderHistory(orderId, "PENDING", "PAID"),
                "the duplicate PENDING -> PAID row is the race the constraint exists to catch");
        assertEquals(1, countOrderHistory(orderId, "PAID"));
    }
}
