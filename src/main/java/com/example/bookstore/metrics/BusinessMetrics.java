package com.example.bookstore.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class BusinessMetrics {

    private final Counter ordersCreated;
    private final Counter ordersCompleted;
    private final Counter ordersCancelled;
    private final Counter paymentsProcessed;
    private final Counter paymentsCompleted;
    private final Counter paymentsFailed;
    private final Counter paymentsRefunded;
    private final Counter booksSold;
    private final Counter emailsSent;
    private final Counter emailsFailed;
    private final Counter cacheHits;
    private final Counter cacheMisses;
    private final Counter applicationErrors;
    private final Timer checkoutDuration;

    public BusinessMetrics(MeterRegistry registry) {
        this.ordersCreated = Counter.builder("bookstore.orders.created")
                .description("Total orders created")
                .register(registry);
        this.ordersCompleted = Counter.builder("bookstore.orders.completed")
                .description("Total orders completed (DELIVERED)")
                .register(registry);
        this.ordersCancelled = Counter.builder("bookstore.orders.cancelled")
                .description("Total orders cancelled")
                .register(registry);
        this.paymentsProcessed = Counter.builder("bookstore.payments.processed")
                .description("Total payment processing attempts")
                .register(registry);
        this.paymentsCompleted = Counter.builder("bookstore.payments.completed")
                .description("Total successful payments")
                .register(registry);
        this.paymentsFailed = Counter.builder("bookstore.payments.failed")
                .description("Total failed payments")
                .register(registry);
        this.paymentsRefunded = Counter.builder("bookstore.payments.refunded")
                .description("Total refunded payments")
                .register(registry);
        this.booksSold = Counter.builder("bookstore.books.sold")
                .description("Total books sold (units)")
                .register(registry);
        this.emailsSent = Counter.builder("bookstore.emails.sent")
                .description("Total emails sent successfully")
                .register(registry);
        this.emailsFailed = Counter.builder("bookstore.emails.failed")
                .description("Total emails that failed after retries")
                .register(registry);
        this.cacheHits = Counter.builder("bookstore.cache.hits")
                .description("Total cache hits")
                .register(registry);
        this.cacheMisses = Counter.builder("bookstore.cache.misses")
                .description("Total cache misses")
                .register(registry);
        this.applicationErrors = Counter.builder("bookstore.errors.total")
                .description("Total application errors (exceptions handled by GlobalExceptionHandler)")
                .register(registry);
        this.checkoutDuration = Timer.builder("bookstore.checkout.duration")
                .description("Checkout (order creation) duration in milliseconds")
                .register(registry);
    }

    public void recordOrderCreated() {
        ordersCreated.increment();
    }

    public void recordOrderCompleted() {
        ordersCompleted.increment();
    }

    public void recordOrderCancelled() {
        ordersCancelled.increment();
    }

    public void recordPaymentProcessed() {
        paymentsProcessed.increment();
    }

    public void recordPaymentCompleted() {
        paymentsCompleted.increment();
    }

    public void recordPaymentFailed() {
        paymentsFailed.increment();
    }

    public void recordPaymentRefunded() {
        paymentsRefunded.increment();
    }

    public void recordBooksSold(int quantity) {
        booksSold.increment(quantity);
    }

    public void recordEmailSent() {
        emailsSent.increment();
    }

    public void recordEmailFailed() {
        emailsFailed.increment();
    }

    public void recordCacheHit() {
        cacheHits.increment();
    }

    public void recordCacheMiss() {
        cacheMisses.increment();
    }

    public void recordError() {
        applicationErrors.increment();
    }

    public void recordCheckoutDuration(long durationMillis) {
        checkoutDuration.record(durationMillis, TimeUnit.MILLISECONDS);
    }
}
