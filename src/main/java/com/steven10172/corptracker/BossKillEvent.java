package com.steven10172.corptracker;

import lombok.Data;
import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RequiredArgsConstructor
@Data
public class BossKillEvent {
    private final UUID uuid;
    private final int bossId;
    private final BossTrackerItem item;
    private final String killOwner;
    private final List<String> participants;
    private final Instant time;
    private boolean inProgress = false;

    public boolean search(String search) {
        if (search == null) {
            return true;
        }
        if (item.getName().toLowerCase().contains(search)) {
            return true;
        }

        for (String participant : participants) {
            if (participant.toLowerCase().contains(search)) {
                return true;
            }
        }

        return false;
    }
}
