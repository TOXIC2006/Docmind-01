package com.lcdw.springai.docmind01.Execption;

public class Document_EXE  extends  RuntimeException{
    public Document_EXE(String message) {
        super(message);
    }
    public Document_EXE(String message, Throwable cause) {
        super(message, cause);
    }
}
