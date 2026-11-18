package com.example.bookstore.concurrency;

import com.example.bookstore.base.AbstractIntegrationTest;
import com.example.bookstore.config.SecurityUtils;
import com.example.bookstore.model.AppUser;
import com.example.bookstore.model.Book;
import com.example.bookstore.model.Order;
import com.example.bookstore.model.UserRole;
import com.example.bookstore.repository.BookRepository;
import com.example.bookstore.service.InventoryAdminService;
import com.example.bookstore.repository.InventoryRepository;
import com.example.bookstore.repository.OrderRepository;
import com.example.bookstore.repository.PaymentRepository;
import com.example.bookstore.repository.UserRepository;
import com.example.bookstore.service.CartService;
import com.example.bookstore.service.OrderService;
import com.example.bookstore.service.PaymentService;
import com.example.bookstore.service.payment.DummyPaymentProvider;
import com.example.bookstore.service.payment.PaymentProviderResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

// Purpose: characterize isSynchronizationActive behavior at the provider
// boundary. Diagnostic only, not a correctness gate.
@Import(SynchronizationDiagnosticTest.DiagnosticProviderConfig.class)
class SynchronizationDiagnosticTest extends AbstractIntegrationTest {

    static final List<String> PROVIDER_OBSERVATIONS =
            Collections.synchronizedList(new ArrayList<>());

    @TestConfiguration
    static class DiagnosticProviderConfig {
        @Bean
        @Primary
        DummyPaymentProvider diagnosticPaymentProvider() {
            return new DummyPaymentProvider() {
                @Override
                public PaymentProviderResponse processPayment(com.example.bookstore.model.Payment payment) {
                    PROVIDER_OBSERVATIONS.add(snapshot("provider"));
                    return PaymentProviderResponse.success("diag-" + System.nanoTime(),
                            com.example.bookstore.model.PaymentStatus.COMPLETED);
                }
            };
        }
    }

    @Autowired private PaymentService paymentService;
    @Autowired private CartService cartService;
    @Autowired private OrderService orderService;
    @Autowired private UserRepository userRepository;
    @Autowired private BookRepository bookRepository;
    @Autowired private InventoryAdminService inventoryAdminService;
    @Autowired private InventoryRepository inventoryRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    @MockitoBean private SecurityUtils securityUtils;

    private AppUser user;
    private Book book;

    @BeforeEach
    void setUp() {
        PROVIDER_OBSERVATIONS.clear();
        paymentRepository.deleteAll();
        orderRepository.deleteAll();
        inventoryRepository.deleteAll();
        bookRepository.deleteAll();
        userRepository.deleteAll();

        user = new AppUser();
        user.setUsername("diag-" + UUID.randomUUID());
        user.setPassword("password");
        user.setEmail("diag@example.com");
        user.setRole(UserRole.ROLE_USER);
        user = userRepository.save(user);

        book = new Book();
        book.setTitle("Diagnostic Book");
        book.setAuthor("Diagnostic Author");
        book.setPrice(new BigDecimal("10.00"));
        book.setCategory("Diagnostic");
        book.setStock(100);
        book = bookRepository.save(book);
        inventoryAdminService.initializeInventory(book.getId(), 100);
    }

    private static String snapshot(String point) {
        boolean active = TransactionSynchronizationManager.isActualTransactionActive();
        boolean sync = TransactionSynchronizationManager.isSynchronizationActive();
        int count = sync ? TransactionSynchronizationManager.getSynchronizations().size() : -1;
        return point + ": isActualTransactionActive=" + active
                + ", isSynchronizationActive=" + sync
                + ", synchronizationsCount=" + count;
    }

    private Order newCommittedOrder() {
        when(securityUtils.getCurrentUser()).thenReturn(user);
        cartService.addToCart(book.getId(), 2);
        return orderService.createOrder();
    }

    private TransactionTemplate propagation(int behavior) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(behavior);
        return template;
    }

    @Test
    void providerBoundary_insideCallerTransaction_observesSynchronizationFlag() {
        Order order = newCommittedOrder();
        List<String> snapshots = new ArrayList<>();
        snapshots.add(snapshot("before"));
        propagation(TransactionDefinition.PROPAGATION_REQUIRED).executeWithoutResult(status ->
                paymentService.processPayment(order.getId(), "card"));
        PROVIDER_OBSERVATIONS.add(snapshot("after"));
        snapshots.addAll(PROVIDER_OBSERVATIONS);
        snapshots.forEach(System.out::println);
        assertEquals(3, snapshots.size());
    }

    @Test
    void providerBoundary_outsideCallerTransaction_observesSynchronizationFlag() {
        Order order = newCommittedOrder();
        List<String> snapshots = new ArrayList<>();
        snapshots.add(snapshot("before"));
        propagation(TransactionDefinition.PROPAGATION_REQUIRES_NEW).executeWithoutResult(status ->
                paymentService.processPayment(order.getId(), "card"));
        PROVIDER_OBSERVATIONS.add(snapshot("after"));
        snapshots.addAll(PROVIDER_OBSERVATIONS);
        snapshots.forEach(System.out::println);
        assertEquals(3, snapshots.size());
    }

    @Test
    void synchronizationsRegisteredBeforeProvider_doNotFireDuringProvider() {
        Order order = newCommittedOrder();
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        propagation(TransactionDefinition.PROPAGATION_REQUIRED).executeWithoutResult(status -> {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void suspend() { events.add("suspend"); }
                @Override public void resume() { events.add("resume"); }
                @Override public void beforeCommit(boolean readOnly) { events.add("beforeCommit"); }
                @Override public void beforeCompletion() { events.add("beforeCompletion"); }
                @Override public void afterCommit() { events.add("afterCommit"); }
                @Override public void afterCompletion(int result) { events.add("afterCompletion:" + result); }
            });
            events.add("before-provider:" + snapshot("inside-caller"));
            paymentService.processPayment(order.getId(), "card");
            events.add("after-provider:" + snapshot("inside-caller"));
        });
        events.add("final:" + snapshot("final"));
        events.forEach(System.out::println);

        int before = -1;
        int after = -1;
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).startsWith("before-provider:")) {
                before = i;
            } else if (events.get(i).startsWith("after-provider:")) {
                after = i;
            }
        }
        assertEquals(true, before >= 0 && after > before, "provider window must be recorded");
        List<String> duringProvider = events.subList(before + 1, after);
        assertEquals(false, duringProvider.stream().anyMatch(e ->
                        e.equals("beforeCommit") || e.equals("beforeCompletion")
                                || e.equals("afterCommit") || e.startsWith("afterCompletion")),
                "sentinel must not complete during the provider call: " + duringProvider);
        assertEquals(true, events.subList(after + 1, events.size()).stream()
                        .anyMatch("afterCommit"::equals),
                "sentinel must fire after the caller transaction completes");
    }
}
