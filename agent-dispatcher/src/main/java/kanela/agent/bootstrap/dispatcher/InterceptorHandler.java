package kanela.agent.bootstrap.dispatcher;

import java.lang.reflect.Method;

public interface InterceptorHandler {

    void handleEntry(Method method, Object[] params, Object span);

    boolean handleExit(Method method, Object result, Object span);

}
