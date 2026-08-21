package com.localdeals.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Result {
    private Boolean success;
    private String code;
    private String errorMsg;
    private Object data;
    private Long total;

    public static Result ok(){
        return new Result(true, null, null, null, null);
    }
    public static Result ok(Object data){
        return new Result(true, null, null, data, null);
    }
    public static Result ok(List<?> data, Long total){
        return new Result(true, null, null, data, total);
    }
    public static Result fail(String errorMsg){
        return new Result(false, null, errorMsg, null, null);
    }
    public static Result fail(String code, String errorMsg){
        return new Result(false, code, errorMsg, null, null);
    }
}
