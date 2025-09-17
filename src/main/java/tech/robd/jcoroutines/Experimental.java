package tech.robd.jcoroutines;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.SOURCE)
@Documented
public @interface Experimental {
    String value() default "This API is experimental and may change";
}
