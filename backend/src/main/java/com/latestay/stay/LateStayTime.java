package com.latestay.stay;

import java.time.ZoneId;

public final class LateStayTime {

    /** All local times in the API (arrive, leave, night_of) are in this zone. */
    public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    private LateStayTime() {
    }
}
