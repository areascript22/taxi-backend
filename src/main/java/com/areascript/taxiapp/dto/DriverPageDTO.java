package com.areascript.taxiapp.dto;

import java.util.List;

public record DriverPageDTO(
        List<DriverDTO> drivers,
        String nextCursor,
        boolean hasMore
) {
}
