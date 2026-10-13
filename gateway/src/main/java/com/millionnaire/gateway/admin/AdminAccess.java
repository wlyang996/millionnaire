package com.millionnaire.gateway.admin;
import java.lang.annotation.*;
/** An undeclared management handler is denied. */
@Target(ElementType.METHOD) @Retention(RetentionPolicy.RUNTIME)
public @interface AdminAccess {
    String value() default "self";
    boolean passwordChangeAllowed() default false;
}
