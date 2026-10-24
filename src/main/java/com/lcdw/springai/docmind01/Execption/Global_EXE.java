package com.lcdw.springai.docmind01.Execption;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class Global_EXE {

    @ExceptionHandler(ResourceNotFound_EXE.class)
    public ResponseEntity<API_Respone> Global_EXE(ResourceNotFound_EXE   e) {
        return ResponseEntity.status(404).body(new API_Respone(false
                , e.getMessage()
                , 404, java.time.LocalDateTime.now()));
    }

    @ExceptionHandler(Document_EXE.class)
    public ResponseEntity<API_Respone> Global_EXE(Document_EXE   e) {
        return ResponseEntity.status(500).body(new API_Respone(false
                , e.getMessage()
                , 500, java.time.LocalDateTime.now()));
    }
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<?> Global_EXE(MaxUploadSizeExceededException   e) {
        return ResponseEntity.status(413).body(new API_Respone(false
                , "File size exceeds the maximum allowed size of 25MB"
                , 413, java.time.LocalDateTime.now()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<API_Respone> Global_EXE(MethodArgumentNotValidException e) {
        return ResponseEntity.status(400).body(
                new API_Respone(
                        false,
                        e.getBindingResult().getFieldError().getDefaultMessage(),
                        400,
                        java.time.LocalDateTime.now()
                )
        );
    }
     @ExceptionHandler(IllegalAccessException.class)
    public ResponseEntity<API_Respone> Global_EXE(IllegalAccessException e){
         return  ResponseEntity.status(400).body(new API_Respone((false)
                 , e.getMessage()+ "validation failed"
                 , 400, java.time.LocalDateTime.now()));
     }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> Global_EXE(Exception e) {

        Map<String, Object> map = new HashMap<>();

        map.put("success", false);
        map.put("message", e.getMessage());
        map.put("status", 500);
        map.put("timestamp", java.time.LocalDateTime.now());

        return ResponseEntity.status(500).body(map);
    }


}
