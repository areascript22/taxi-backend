package com.areascript.taxiapp.common.error;

public class ForbiddenException extends AppException {

    public ForbiddenException(String message) {
        super(message);
    }

    @Override
    public ErrorType type() {
        return ErrorType.FORBIDDEN;
    }
}
