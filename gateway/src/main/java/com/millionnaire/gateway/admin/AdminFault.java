package com.millionnaire.gateway.admin;
public class AdminFault extends RuntimeException {
    public final int status; public final String code; public final Long retryAt;
    public AdminFault(int status,String code,String message){this(status,code,message,null);}
    public AdminFault(int status,String code,String message,Long retryAt){super(message);this.status=status;this.code=code;this.retryAt=retryAt;}
}
