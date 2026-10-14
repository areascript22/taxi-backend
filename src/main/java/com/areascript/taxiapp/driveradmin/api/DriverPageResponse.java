package com.areascript.taxiapp.driveradmin.api;

import com.areascript.taxiapp.driveradmin.domain.DriverPage;

import java.util.List;

// JSON de una página de conductores (DriverPageModel.fromJson en driver_app).
public record DriverPageResponse(
        List<DriverResponse> drivers,
        String nextCursor,
        boolean hasMore
) {
    static DriverPageResponse from(DriverPage page) {
        return new DriverPageResponse(
                page.drivers().stream().map(DriverResponse::from).toList(),
                page.nextCursor(),
                page.hasMore()
        );
    }
}
