package kanela.agent.instrumentations;

import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.Tracer;
import io.opentracing.util.GlobalTracer;
import kanela.agent.bootstrap.dispatcher.Dispatcher;
import kanela.agent.bootstrap.dispatcher.InterceptorHandler;
import kanela.agent.bootstrap.log.LoggerHandler;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

import java.lang.reflect.Method;

public class InterceptingHandlerAdvice {

    @Advice.OnMethodEnter
    static Scope enter(@InterceptorHandlerA String handlerName, @Advice.Origin Method method, @Advice.AllArguments(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object[] args) throws Exception {

        final Tracer tracer = GlobalTracer.get();

        String methodName = method.getName();
        String methodDeclarer = method.getDeclaringClass().getSimpleName();

        final Tracer.SpanBuilder spanBuilder = tracer.buildSpan(methodDeclarer + "." + methodName);
        final Scope scope = spanBuilder.startActive(false);

        Span span = scope.span();

        if (handlerName == null) {
            LoggerHandler.error("Handler for [" + method + "] is null :(", null);
        }
        else {
            InterceptorHandler handler = (InterceptorHandler) Dispatcher.VALUES.get(handlerName);
            if (handler == null) {
                LoggerHandler.info("handler for [" + method + "] is null :(");
            } else {
                LoggerHandler.info("handler for [" + method + "] is set :)");
                handler.handleEntry(method, args, span);
            }
        }

        return scope;
    }

    @Advice.OnMethodExit
    static void exit(@InterceptorHandlerA String handlerName, @Advice.Origin Method method, @Advice.Enter Scope scope, @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object returnValue) throws Exception {
        Span span = scope.span();
        scope.close();
        if (handlerName == null) {
            LoggerHandler.error("Handler for [" + method + "] is null :(", null);
            span.finish();
        }
        else {
            InterceptorHandler handler = (InterceptorHandler) Dispatcher.VALUES.get(handlerName);
            if (handler == null) {
                LoggerHandler.info("handler for [" + method + "] is null :(");
                span.finish();
            } else {
                LoggerHandler.info("handler for [" + method + "] is set :)");
                boolean closeSpan = handler.handleExit(method, returnValue, span);
                if (closeSpan) {
                    span.finish();
                }
            }
        }
    }
}