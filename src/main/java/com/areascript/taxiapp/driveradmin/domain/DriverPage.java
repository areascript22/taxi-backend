package com.areascript.taxiapp.driveradmin.domain;

import java.util.List;

// Una página de conductores. nextCursor es el último id de la página cuando
// hasMore es true, y null en caso contrario.
public record DriverPage(List<Driver> drivers, String nextCursor, boolean hasMore) {
}
