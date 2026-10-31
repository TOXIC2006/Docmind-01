package com.lcdw.springai.docmind01.Execption;

public class Auth_EXE extends RuntimeException {

    public Auth_EXE(String message) {
        super(message);
    }

    public Auth_EXE(String message, Throwable cause) {
        super(message, cause);
    }
}
