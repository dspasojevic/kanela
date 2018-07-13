# Customisable Kanela Agent

This experimental fork of Kanela adds support for dynamically adding, modifiying and removing tracing points that generate OpenTracing spans.

## Using

Given a simple piece of Java code:
```java
package kanela_test;

import java.util.UUID;

class Hello {

    public Hello() throws InterruptedException {
        while (keepGoing()) {
            doIt();
        }
    }

    public Result doIt() throws InterruptedException {
        System.out.println("Some stuff");
        Thread.sleep(5000);
        String result = "";
        for (int i = 0; i < 10; i++) {
            result = innerDo(i + 1);
        }

        return new Result(result);
    }

    public String innerDo(int length) {
        return UUID.randomUUID().toString().substring(0, length);
    }

    public boolean keepGoing() {
        return true;
    }

    public static final class Result {
        private final String value;

        public Result(String value) {
            this.value = value;
        }

        public String getValue() {
            return this.value;
        }
    }
}
```

And some JavaScript like:
```javascript
function handleEntry(method, params, span) {

}

function handleExit(method, result, span) { 
	Packages.kanela.agent.bootstrap.log.LoggerHandler.info("[" + method + "] returned [" + result + "] and span was [" + span + "].");

	span.setTag("thing", result.getValue());

	Packages.kanela.agent.bootstrap.log.LoggerHandler.info("value [" + result.getValue() + "].");

	return true;
}
```

We can add a customised span:
```
http localhost:18080/handlers class==kanela_test.Hello method==doIt  < ~/dev/scratch/handler.js
```

The span will be customised with the `value` from the `Result` instace returned by `Hello.doIt`.

Similarly, this handler:
```javascript
function handleEntry(method, params, span) {
	span.setTag("length", params[0]);
}

function handleExit(method, result, span) { 
	span.setTag("inner_thing", result);
	return true;
}
```

can be added to the inner method:
```
http localhost:18080/handlers class==kanela_test.Hello method==innerDo  < ~/dev/scratch/handler_inner.js
```

POSTs to the handlers end point returns an `id` that can be used to update:
```
http PUT localhost:18080/handlers/98d5b61f-b77d-4c86-a5e6-75be28779ccb  < ~/dev/scratch/handler_inner_quiet.js
```
and DELETE the handler:
```
http DELETE localhost:18080/handlers/2274402e-a479-47a8-a1c5-58d8101462ba
```

## Building

To build the experimental agent:
```
./gradlew publishToMavenLocal assemble
```

Then to attach:
```
java -DJAEGER_AGENT_HOST=localhost -DJAEGER_AGENT_PORT=6831 -DJAEGER_SERVICE_NAME=testService -javaagent:/Users/dan/dev/github/kanela/agent/build/libs/kanela-agent-0.0.300.jar -Xbootclasspath/a:/tmp/jaeger-thrift-0.30.1.jar -cp /Users/kanela_test/build/classes/java/main kanela_test.Runner
```
## About Kanela

**Kanela** is a Java Agent written in Java 8+ and powered by [ByteBuddy] with some additionally [ASM] features to provide a simple way to instrument applications running on the JVM and allow introduce Kamon features such as context propagation and metrics.

- Head to their new documentation :sparkles: [Microsite](http://kamon-io.github.io/kanela/) :sparkles:
[ByteBuddy]:http://bytebuddy.net/#/
[ASM]:http://asm.ow2.org/
