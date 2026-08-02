package cn.zcj.aether.trigger.http.handler;

import cn.zcj.aether.api.response.Response;
import cn.zcj.aether.types.enums.ResponseCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * P1: 参数校验异常处理器。
 */
@Slf4j
@RestControllerAdvice
public class ValidationExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Response<Map<String, String>> handleValidation(
            MethodArgumentNotValidException ex) {
        Map<String, String> errors = ex.getBindingResult()
                .getFieldErrors().stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        e -> e.getDefaultMessage() != null
                                ? e.getDefaultMessage() : "无效输入",
                        (a, b) -> a));

        log.debug("参数校验失败: {}", errors);
        return Response.<Map<String, String>>builder()
                .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                .info("参数校验失败")
                .data(errors)
                .build();
    }
}
