package com.e2eq.framework.util;

import io.quarkus.logging.Log;
import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Utility class for consistent exception logging across the framework.
 * Replaces direct printStackTrace() calls with proper logging.
 */
public class ExceptionLoggingUtils {
    
    /**
     * Log exception with full stack trace at ERROR level
     * 
     * @param exception the exception to log
     * @param message the message format string
     * @param args optional arguments for message formatting
     */
    public static void logError(Throwable exception, String message, Object... args) {
        if (exception == null) {
            if (args.length > 0) {
                Log.errorf(message, args);
            } else {
                Log.error(message);
            }
            return;
        }
        
        String stackTrace = getStackTrace(exception);
        if (args.length > 0) {
            String formattedMessage = String.format(message.replace("%", "%%"), args);
            Log.errorf("%s: %s%n%s", formattedMessage, 
                exception.getMessage() != null ? exception.getMessage() : exception.getClass().getName(), 
                stackTrace);
        } else {
            Log.errorf("%s: %s%n%s", message, 
                exception.getMessage() != null ? exception.getMessage() : exception.getClass().getName(), 
                stackTrace);
        }
    }
    
    /**
     * Log exception with full stack trace at WARN level
     * 
     * @param exception the exception to log
     * @param message the message format string
     * @param args optional arguments for message formatting
     */
    public static void logWarn(Throwable exception, String message, Object... args) {
        if (exception == null) {
            if (args.length > 0) {
                Log.warnf(message, args);
            } else {
                Log.warn(message);
            }
            return;
        }
        
        String stackTrace = getStackTrace(exception);
        if (args.length > 0) {
            String formattedMessage = String.format(message.replace("%", "%%"), args);
            Log.warnf("%s: %s%n%s", formattedMessage, 
                exception.getMessage() != null ? exception.getMessage() : exception.getClass().getName(), 
                stackTrace);
        } else {
            Log.warnf("%s: %s%n%s", message, 
                exception.getMessage() != null ? exception.getMessage() : exception.getClass().getName(), 
                stackTrace);
        }
    }
    
    /**
     * Log exception with full stack trace at DEBUG level
     * 
     * @param exception the exception to log
     * @param message the message format string
     * @param args optional arguments for message formatting
     */
    public static void logDebug(Throwable exception, String message, Object... args) {
        if (!Log.isDebugEnabled()) {
            return;
        }
        
        if (exception == null) {
            if (args.length > 0) {
                Log.debugf(message, args);
            } else {
                Log.debug(message);
            }
            return;
        }
        
        String stackTrace = getStackTrace(exception);
        if (args.length > 0) {
            String formattedMessage = String.format(message.replace("%", "%%"), args);
            Log.debugf("%s: %s%n%s", formattedMessage, 
                exception.getMessage() != null ? exception.getMessage() : exception.getClass().getName(), 
                stackTrace);
        } else {
            Log.debugf("%s: %s%n%s", message, 
                exception.getMessage() != null ? exception.getMessage() : exception.getClass().getName(), 
                stackTrace);
        }
    }
    
    /**
     * Get stack trace as string
     * 
     * @param exception the exception
     * @return stack trace as string
     */
    public static String getStackTrace(Throwable exception) {
        if (exception == null) {
            return "";
        }
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        exception.printStackTrace(pw);
        return sw.toString();
    }
    
    /**
     * Log an ignored exception at DEBUG level with context information
     * 
     * @param exception the exception that was ignored
     * @param context the context where the exception was ignored (e.g., method name, operation)
     */
    public static void logIgnoredException(Throwable exception, String context) {
        if (Log.isDebugEnabled() && exception != null) {
            Log.debugf(exception, "Exception ignored in %s: %s", context, 
                exception.getMessage() != null ? exception.getMessage() : exception.getClass().getSimpleName());
        }
    }
}






