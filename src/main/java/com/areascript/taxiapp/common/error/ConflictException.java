package com.areascript.taxiapp.common.error;

public class ConflictException extends AppException {

    public ConflictException(String message) {
        super(message);
    }

    @Override
    public ErrorType type() {
        return ErrorType.CONFLICT;
    }
}
