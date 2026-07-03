package com.example.bookstore.chaos;

import com.example.bookstore.base.AbstractIntegrationTest;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Tag;

import java.time.Duration;

@Tag("chaos")
public abstract class AbstractChaosTest extends AbstractIntegrationTest {

    protected static final Duration DEFAULT_WAIT = Duration.ofSeconds(60);
    protected static final Duration POLL_INTERVAL = Duration.ofMillis(100);

    protected void waitForCondition(Runnable condition) {
        Awaitility.await()
                .atMost(DEFAULT_WAIT)
                .pollInterval(POLL_INTERVAL)
                .untilAsserted(condition::run);
    }
}
