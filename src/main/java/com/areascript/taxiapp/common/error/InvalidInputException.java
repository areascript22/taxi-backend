package com.areascript.taxiapp.common.error;

public class InvalidInputException extends AppException {

    public InvalidInputException(String message) {
        super(message);
    }

    @Override
    public ErrorType type() {
        return ErrorType.INVALID_INPUT;
    }
}
