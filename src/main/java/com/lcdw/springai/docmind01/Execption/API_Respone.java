package com.lcdw.springai.docmind01.Execption;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class API_Respone    {

     private Boolean success;
     private String message;
     private  Integer status;
     private LocalDateTime timestamp;

}
