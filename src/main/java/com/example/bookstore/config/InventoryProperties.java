package com.example.bookstore.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.inventory")
public class InventoryProperties {

    private Thresholds thresholds = new Thresholds();

    public Thresholds getThresholds() { return thresholds; }
    public void setThresholds(Thresholds thresholds) { this.thresholds = thresholds; }

    public static class Thresholds {
        private int low = 10;
        private int critical = 3;

        public int getLow() { return low; }
        public void setLow(int low) { this.low = low; }
        public int getCritical() { return critical; }
        public void setCritical(int critical) { this.critical = critical; }
    }
}
