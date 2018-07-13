/*
 * =========================================================================================
 * Copyright © 2013-2018 the kamon project <http://kamon.io/>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 * =========================================================================================
 */

package kanela.agent;

import io.vavr.control.Option;
import kanela.agent.api.instrumentation.KanelaInstrumentation;
import kanela.agent.util.BootstrapInjector;
import kanela.agent.util.ExtensionLoader;
import kanela.agent.util.banner.KanelaBanner;
import kanela.agent.util.classloader.KanelaClassLoader;
import kanela.agent.util.conf.KanelaConfiguration;
import kanela.agent.util.log.Logger;
import lombok.Value;

import java.lang.instrument.Instrumentation;

import static java.text.MessageFormat.format;
import static kanela.agent.util.Execution.runWithTimeSpent;

@Value
public class KanelaEntryPoint {
    /**
     * Kanela entry point.
     *
     * @param arguments       Agent argument list
     * @param instrumentation {@link Instrumentation}
     */
    private static void start(final String arguments, final Instrumentation instrumentation) {

        runWithTimeSpent(() -> {
            KanelaClassLoader.from(instrumentation).use(kanelaClassLoader -> {
                BootstrapInjector.injectJar(instrumentation, "bootstrap");
                KanelaConfiguration configuration = KanelaConfiguration.instance();
                KanelaBanner.show(configuration);


                ExtensionLoader.attach(arguments, instrumentation);

                RealTimeServer.listen(instrumentation);
            });

        });
    }

    public static void premain(final String arguments, final Instrumentation instrumentation) {
        start(arguments, instrumentation);
    }

    public static void agentmain(final String arguments, final Instrumentation instrumentation) {
        KanelaConfiguration.instance().runtimeAttach();
        premain(arguments, instrumentation);
    }



    private static Option<KanelaInstrumentation> loadInstrumentation(String instrumentationClassName, ClassLoader classLoader) {
        Logger.info(() -> format(" ==> Loading {0} ", instrumentationClassName));
        try {
            return Option.some((KanelaInstrumentation) Class.forName(instrumentationClassName, true, classLoader).newInstance());
        } catch (Throwable cause) {
            Logger.warn(() -> format("Error trying to load Instrumentation: {0} with error: {1}", instrumentationClassName, cause));
            return Option.none();
        }
    }
}
