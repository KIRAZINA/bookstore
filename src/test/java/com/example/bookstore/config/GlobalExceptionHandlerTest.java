package com.example.bookstore.config;

import com.example.bookstore.exception.InvalidPaymentStateException;
import com.example.bookstore.exception.PaymentException;
import com.example.bookstore.metrics.BusinessMetrics;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GlobalExceptionHandlerTest {

    @Mock
    private BusinessMetrics businessMetrics;

    @Mock
    private HttpServletRequest request;

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler(businessMetrics);
        when(request.getRequestURI()).thenReturn("/api/v1/test");
    }

    @Test
    @DisplayName("IllegalArgumentException returns 400")
    void illegalArgument() {
        IllegalArgumentException ex = new IllegalArgumentException("Bad arg");
        ResponseEntity<ErrorResponse> response = handler.handleIllegalArgument(ex, request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("Bad arg", response.getBody().getMessage());
        assertEquals("/api/v1/test", response.getBody().getPath());
        verify(businessMetrics).recordError();
    }

    @Test
    @DisplayName("IllegalStateException returns 400")
    void illegalState() {
        IllegalStateException ex = new IllegalStateException("Invalid state");
        ResponseEntity<ErrorResponse> response = handler.handleIllegalState(ex, request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("Invalid state", response.getBody().getMessage());
    }

    @Test
    @DisplayName("DataIntegrityViolationException returns 409 with DATA_CONFLICT code")
    void dataIntegrity() {
        ResponseEntity<ErrorResponse> response = handler.handleDataIntegrity(request);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("DATA_CONFLICT", response.getBody().getCode());
        verify(businessMetrics).recordError();
    }

    @Test
    @DisplayName("Validation error returns 400 with field errors")
    void validationError() {
        var bindingResult = new BeanPropertyBindingResult(new Object(), "obj");
        bindingResult.addError(new FieldError("obj", "fieldName", "must not be null"));
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(null, bindingResult);

        ResponseEntity<ErrorResponse> response = handler.handleValidation(ex, request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("VALIDATION_ERROR", response.getBody().getCode());
        assertNotNull(response.getBody().getFieldErrors());
        assertEquals(1, response.getBody().getFieldErrors().size());
        assertEquals("fieldName", response.getBody().getFieldErrors().get(0).getField());
    }

    @Test
    @DisplayName("OptimisticLockException returns 409 with CONCURRENT_MODIFICATION code")
    void optimisticLock() {
        ResponseEntity<ErrorResponse> response = handler.handleOptimisticLock(request);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("CONCURRENT_MODIFICATION", response.getBody().getCode());
    }

    @Test
    @DisplayName("PaymentException returns 400 with PAYMENT_ERROR code")
    void paymentException() {
        PaymentException ex = new PaymentException("Payment failed");
        ResponseEntity<ErrorResponse> response = handler.handlePaymentException(ex, request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("PAYMENT_ERROR", response.getBody().getCode());
    }

    @Test
    @DisplayName("InvalidPaymentStateException returns 400 with INVALID_PAYMENT_STATE code")
    void invalidPaymentState() {
        InvalidPaymentStateException ex = new InvalidPaymentStateException("Cannot process");
        ResponseEntity<ErrorResponse> response = handler.handleInvalidPaymentState(ex, request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("INVALID_PAYMENT_STATE", response.getBody().getCode());
    }

    @Test
    @DisplayName("AccessDeniedException returns 403")
    void accessDenied() {
        ResponseEntity<ErrorResponse> response = handler.handleAccessDenied(request);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals("ACCESS_DENIED", response.getBody().getCode());
    }

    @Test
    @DisplayName("AuthenticationException returns 401")
    void authenticationFailed() {
        ResponseEntity<ErrorResponse> response = handler.handleAuthenticationFailed(request);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals("AUTHENTICATION_FAILED", response.getBody().getCode());
    }

    @Test
    @DisplayName("Unhandled exception returns 500")
    void unhandled() {
        Exception ex = new RuntimeException("Unexpected");
        ResponseEntity<ErrorResponse> response = handler.handleUnhandled(ex, request);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("INTERNAL_ERROR", response.getBody().getCode());
        verify(businessMetrics).recordError();
    }
}
