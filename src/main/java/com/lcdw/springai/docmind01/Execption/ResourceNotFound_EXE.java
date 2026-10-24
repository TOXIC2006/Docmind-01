package com.lcdw.springai.docmind01.Execption;

public class ResourceNotFound_EXE extends RuntimeException {
    public ResourceNotFound_EXE(String message) {
        super(message+" Resource Not Found");
    }

    public ResourceNotFound_EXE(String message, Throwable cause) {
        super(message, cause);
    }
}
