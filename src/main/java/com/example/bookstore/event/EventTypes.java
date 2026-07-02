package com.example.bookstore.event;

public final class EventTypes {

    private EventTypes() {}

    public static final String AGGREGATE_ORDER = "ORDER";
    public static final String AGGREGATE_PAYMENT = "PAYMENT";
    public static final String AGGREGATE_INVENTORY = "INVENTORY";
    public static final String AGGREGATE_NOTIFICATION = "NOTIFICATION";

    public static final String ORDER_CREATED = "ORDER_CREATED";
    public static final String ORDER_STATUS_CHANGED = "ORDER_STATUS_CHANGED";
    public static final String ORDER_CANCELLED = "ORDER_CANCELLED";

    public static final String PAYMENT_CREATED = "PAYMENT_CREATED";
    public static final String PAYMENT_COMPLETED = "PAYMENT_COMPLETED";
    public static final String PAYMENT_FAILED = "PAYMENT_FAILED";
    public static final String PAYMENT_REFUNDED = "PAYMENT_REFUNDED";

    public static final String INVENTORY_RESERVED = "INVENTORY_RESERVED";
    public static final String INVENTORY_RELEASED = "INVENTORY_RELEASED";
    public static final String INVENTORY_FINALIZED = "INVENTORY_FINALIZED";
    public static final String INVENTORY_ADJUSTED = "INVENTORY_ADJUSTED";

    public static final String NOTIFICATION_REQUESTED = "NOTIFICATION_REQUESTED";
    public static final String NOTIFICATION_SENT = "NOTIFICATION_SENT";
    public static final String NOTIFICATION_FAILED = "NOTIFICATION_FAILED";
}
