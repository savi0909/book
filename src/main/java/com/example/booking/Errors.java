package com.example.booking;

import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import java.util.Map;

@RestControllerAdvice
public class Errors {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<?> domain(ApiException e) {return response(e.status(),e.code(),e.getMessage());}
    @ExceptionHandler({MethodArgumentNotValidException.class,ConstraintViolationException.class,
        HttpMessageNotReadableException.class,MissingRequestHeaderException.class,
        MethodArgumentTypeMismatchException.class,HandlerMethodValidationException.class})
    ResponseEntity<?> invalid(Exception e) {return response(400,"INVALID_REQUEST","Check required fields, types, bounds and headers");}
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<?> database(DataAccessException e) {
        return ResponseEntity.status(503).header("Retry-After","1").body(Map.of("code","DATABASE_UNAVAILABLE","message","Database unavailable or busy; retry writes with the same key"));
    }
    private ResponseEntity<?> response(int status,String code,String message) {return ResponseEntity.status(status).body(Map.of("code",code,"message",message));}
}
