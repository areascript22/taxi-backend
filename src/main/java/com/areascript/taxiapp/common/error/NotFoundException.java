package com.areascript.taxiapp.common.error;

public class NotFoundException extends AppException {

    public NotFoundException(String message) {
        super(message);
    }

    @Override
    public ErrorType type() {
        return ErrorType.NOT_FOUND;
    }
}
