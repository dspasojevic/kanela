package kanela.agent;

import io.jaegertracing.Configuration;
import io.opentracing.Tracer;
import io.opentracing.util.GlobalTracer;
import io.undertow.Undertow;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.RoutingHandler;
import io.undertow.util.Headers;
import io.vavr.control.Option;
import kanela.agent.api.instrumentation.listener.DebugInstrumentationListener;
import kanela.agent.api.instrumentation.listener.dumper.ClassDumperListener;
import kanela.agent.bootstrap.dispatcher.Dispatcher;
import kanela.agent.bootstrap.dispatcher.InterceptorHandler;
import kanela.agent.instrumentations.InterceptorHandlerA;
import kanela.agent.instrumentations.InterceptingHandlerAdvice;
import kanela.agent.util.log.Logger;
import lombok.Data;
import lombok.ToString;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.AsmVisitorWrapper;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import java.lang.instrument.Instrumentation;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

import static java.text.MessageFormat.format;
import static net.bytebuddy.matcher.ElementMatchers.nameMatches;

public class RealTimeServer {

    private Map<String, Handler> handlers = new ConcurrentHashMap<>();

    private Map<String, ResettableClassFileTransformer> transformers = new ConcurrentHashMap<>();

    private Instrumentation instrumentation;

    public static void listen(Instrumentation instrumentation) {
        new RealTimeServer(instrumentation);
    }

    public RealTimeServer(Instrumentation instrumentation) {

        this.instrumentation = instrumentation;

        Logger.info(() -> "Starting server");

        try {
            Tracer tracer = Configuration.fromEnv().getTracer();
            GlobalTracer.register(tracer);
            Logger.info(() -> "Registered tracer [" + GlobalTracer.get().getClass().getSimpleName() + "].");
        }
        catch (Exception e) {
            System.err.println("Failed to start the agent " + e);
            e.printStackTrace(System.err);
        }

        final HttpHandler routes = new RoutingHandler()
                .post("/handlers", this::createHandler)
                .get("/handlers", this::listHandlers)
                .put("/handlers/{handlerId}", this::updateHandler)
                .get("/handlers/{handlerId}", this::getHandler)
                .delete("/handlers/{handlerId}", this::deleteHandler);

        Undertow server = Undertow.builder()
                .addHttpListener(18080, "0.0.0.0")
                .setHandler(routes)
                .build();

        server.start();
        Logger.info(() -> "Started server");
    }

    private void script(HttpServerExchange exchange, String id, BiConsumer<HttpServerExchange, Handler> f) {
        exchange.getRequestReceiver().receiveFullBytes((exchange1, message) -> {
            ScriptEngineManager engineManager =
                    new ScriptEngineManager();
            ScriptEngine engine =
                    engineManager.getEngineByName("nashorn");
            try {
                Logger.info(() -> format("Parsing [{0}] into handler.", new String(message)));
                engine.eval(new String(message));
                Invocable invocable = (Invocable) engine;

                final InterceptorHandler interceptorHandler = invocable.getInterface(InterceptorHandler.class);
                if (interceptorHandler == null) {
                    Logger.error(() -> format("Supplied script [{0}] does not contain a handle(start, end, method, result, span) function.", new String(message)));
                    exchange1.setStatusCode(400);
                } else {
                    Dispatcher.VALUES.put(id, interceptorHandler);

                    Handler handler = new Handler(id, new String(message), interceptorHandler);
                    handlers.put(id, handler);

                    f.accept(exchange1, handler);
                }
            } catch (ScriptException e) {
                Logger.error(() -> format("Failed to parse the provided handler", e));
                exchange1.setStatusCode(400);
                exchange1.getResponseSender().send("No.");
            }
        });
    }

    private void listHandlers(HttpServerExchange exchange) {
        exchange.setStatusCode(200);
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain");
        String str = handlers.values().stream().map(Handler::toString).reduce("\n", (h1, h2) -> h1 + "\n" + h2);
        exchange.getResponseSender().send(str);
    }

    private void createHandler(HttpServerExchange exchange) {
        Map<String, Deque<String>> queryParameters = exchange.getQueryParameters();

        Option<String> clazz = Option.of(queryParameters.get("class")).flatMap(d -> Option.of(d.pollFirst()));
        Option<String> method = Option.of(queryParameters.get("method")).flatMap(d -> Option.of(d.pollFirst()));

        Logger.info(() -> format("Instrumented [{0}] / [{1}].", clazz, method));

        String id = UUID.randomUUID().toString();

        script(exchange, id, (e, handler) -> {
            AsmVisitorWrapper.ForDeclaredMethods advice =
                    Advice.withCustomMapping()
                            .bind(InterceptorHandlerA.class, id)
                            .to(InterceptingHandlerAdvice.class)
                            .on(nameMatches(method.getOrElse(".*")));

            ResettableClassFileTransformer transformer = new AgentBuilder.Default()
                    .with(new ClassDumperListener())
                    .with(DebugInstrumentationListener.instance())
                    .disableClassFormatChanges()
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .type(nameMatches(clazz.getOrElse(".*")))
                    .transform((builder, type, loader, a) -> builder
                            .visit(advice))
                    .installOn(instrumentation);
            transformers.put(id, transformer);

            e.setStatusCode(201);
            e.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain");
            e.getResponseSender().send(handler.toString());
        });
    }

    private void getHandler(HttpServerExchange exchange) {
        Option<String> maybeId = Option.of(exchange.getQueryParameters().get("handlerId")).flatMap(d -> Option.of(d.pollFirst()));
        maybeId.forEach(id -> {
            Option<Handler> maybeHandler = Option.of(handlers.get(id));
            maybeHandler.forEach(handler -> {
                exchange.setStatusCode(200);
                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain");
                exchange.getResponseSender().send(handler.toString());
            });

            if (maybeHandler.isEmpty()) {
                exchange.setStatusCode(404);
            }
        });

        if (maybeId.isEmpty()) {
            exchange.setStatusCode(400);
        }
    }

    private void updateHandler(HttpServerExchange exchange) {
        Option<String> maybeId = Option.of(exchange.getQueryParameters().get("handlerId")).flatMap(d -> Option.of(d.pollFirst()));
        maybeId.forEach(id -> {
            Option<Handler> maybeHandler = Option.of(handlers.get(id));
            maybeHandler.forEach(handler -> {
                script(exchange, id, (e, h) -> {
                    e.setStatusCode(200);
                    exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain");
                    exchange.getResponseSender().send(handler.toString());
                });
            });

            if (maybeHandler.isEmpty()) {
                exchange.setStatusCode(404);
            }
        });

        if (maybeId.isEmpty()) {
            exchange.setStatusCode(400);
        }
    }

    private void deleteHandler(HttpServerExchange exchange) {
        Option<String> maybeId = Option.of(exchange.getQueryParameters().get("handlerId")).flatMap(d -> Option.of(d.pollFirst()));
        maybeId.forEach(id -> {
            Option<ResettableClassFileTransformer> maybeTransformer = Option.of(transformers.get(id));
            maybeTransformer.forEach(transformer -> {
                transformer.reset(instrumentation, AgentBuilder.RedefinitionStrategy.RETRANSFORMATION);
            });

            handlers.remove(id);
            Dispatcher.VALUES.remove(id);

            if (maybeTransformer.isEmpty()) {
                exchange.setStatusCode(404);
            }
        });

        if (maybeId.isEmpty()) {
            exchange.setStatusCode(400);
        }
    }

    @Data
    @ToString
    private static final class Handler {
        private final String id;
        private final String source;
        private final InterceptorHandler interceptorHandler;

    }
}
