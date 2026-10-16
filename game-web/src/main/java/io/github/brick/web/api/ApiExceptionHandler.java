package io.github.brick.web.api;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
@RestControllerAdvice
public class ApiExceptionHandler {
    private final ApiErrorWriter errors;
    public ApiExceptionHandler(ApiErrorWriter errors){this.errors=errors;}
    @ExceptionHandler(Exception.class)
    public void handle(Exception exception,HttpServletResponse response)throws IOException{errors.failure(response,exception);}
}
