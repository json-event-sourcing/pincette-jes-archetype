#set( $symbol_pound = '#' )
#set( $symbol_dollar = '$' )
#set( $symbol_escape = '\' )
package ${package};

import static ${groupId}.jes.test.Test.run;
import static ${groupId}.jes.util.Configuration.loadDefault;
import static ${groupId}.jes.util.Streams.fromConfig;
import static ${package}.Application.createApp;
import static ${package}.Application.getMongoClient;
import static ${groupId}.util.Util.tryToDoWithRethrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.typesafe.config.Config;
import java.io.File;
import org.junit.jupiter.api.Test;

class ReducerTest {
  private static final String ENVIRONMENT = "environment";
  private static final String KAFKA = "kafka";

  @Test
  void test() {
    final Config config = loadDefault();

    tryToDoWithRethrow(
        () -> getMongoClient(config),
        client ->
            assertTrue(
                run(
                    new File("src/test/resources").toPath(),
                    fromConfig(config, KAFKA),
                    config.getString(ENVIRONMENT),
                    ${groupId}.jes.test.Application::report,
                    builder -> createApp(builder, config, client))));
  }
}
