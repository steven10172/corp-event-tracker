package com.steven10172.corptracker;

import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public class BossTrackerItem {
    private final int id;
    private final String name;
    private final int quantity;
    private final int gePrice;
    private final int haPrice;

    long getTotalGePrice() {
        return (long) gePrice * quantity;
    }

    long getTotalHaPrice() {
        return (long) haPrice * quantity;
    }

    static BossTrackerItem generateFakeItem() {
        return new BossTrackerItem(0, "N/A", 0, 0, 0);
    }
}
