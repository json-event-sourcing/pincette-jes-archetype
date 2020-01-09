#set( $symbol_pound = '#' )
#set( $symbol_dollar = '$' )
#set( $symbol_escape = '\' )
package ${package};

import static com.mongodb.client.model.Aggregates.match;
import static com.mongodb.client.model.Aggregates.project;
import static com.mongodb.client.model.Filters.ne;
import static com.mongodb.client.model.Projections.include;
import static com.mongodb.reactivestreams.client.MongoClients.create;
import static java.lang.System.exit;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.logging.Level.parse;
import static java.util.logging.Logger.getLogger;
import static javax.json.Json.createObjectBuilder;
import static javax.json.Json.createValue;
import static ${groupId}.jes.elastic.APM.monitor;
import static ${groupId}.jes.elastic.Logging.log;
import static ${groupId}.jes.util.Configuration.loadDefault;
import static ${groupId}.jes.util.Event.changed;
import static ${groupId}.jes.util.JsonFields.COMMAND;
import static ${groupId}.jes.util.JsonFields.ID;
import static ${groupId}.jes.util.Mongo.addNotDeleted;
import static ${groupId}.jes.util.Mongo.aggregationPublisher;
import static ${groupId}.jes.util.Streams.start;
import static ${groupId}.util.Collections.list;
import static ${groupId}.util.Util.tryToDoWithRethrow;
import static ${groupId}.util.Util.tryToGetSilent;

import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoDatabase;
import com.typesafe.config.Config;
import java.util.concurrent.CompletionStage;
import java.util.function.IntUnaryOperator;
import java.util.logging.Level;
import javax.json.JsonObject;
import ${groupId}.jes.Aggregate;
import ${groupId}.jes.Reactor;
import ${groupId}.jes.util.Fanout;
import ${groupId}.jes.util.Streams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.Topology;
import org.reactivestreams.Publisher;

/**
 * A demo application for pincette-jes.
 *
 * @author Werner Donn${symbol_escape}u00e9
 */
public class Application {
  private static final String AGGREGATE_TYPE = "counter";
  private static final String APP = "plusminus";
  private static final String AUDIT = "audit";
  private static final String AUTH = "authorizationHeader";
  private static final String DEV = "dev";
  private static final String ELASTIC_APM = "elastic.apm";
  private static final String ELASTIC_LOG = "elastic.log";
  private static final String ENVIRONMENT = "environment";
  private static final String FANOUT = "fanout";
  private static final String INFO = "INFO";
  private static final String KAFKA = "kafka";
  private static final String LOG_LEVEL = "logLevel";
  private static final String MINUS = "minus";
  private static final String MONGODB_DATABASE = "mongodb.database";
  private static final String MONGODB_URI = "mongodb.uri";
  private static final String PLUS = "plus";
  private static final String REALM_ID = "realmId";
  private static final String REALM_KEY = "realmKey";
  private static final String URI = "uri";
  private static final String VALUE = "value";
  private static final String VERSION = "1.0";

  static StreamsBuilder createApp(
      final StreamsBuilder builder, final Config config, final MongoClient mongoClient) {
    final String environment = getEnvironment(config);
    final Level logLevel = getLogLevel(config);
    final Aggregate aggregate =
        new Aggregate()
            .withApp(APP)
            .withType(AGGREGATE_TYPE)
            .withEnvironment(environment)
            .withBuilder(builder)
            .withMongoDatabase(mongoClient.getDatabase(config.getString(MONGODB_DATABASE)))
            .withBreakingTheGlass()
            .withMonitoring(true)
            .withAudit(AUDIT + "-" + DEV)
            .withReducer(PLUS, (command, currentState) -> reduce(currentState, v -> v + 1))
            .withReducer(MINUS, (command, currentState) -> reduce(currentState, v -> v - 1));

    tryToGetSilent(() -> config.getConfig(FANOUT))
        .ifPresent(
            fanout ->
                Fanout.connect(
                    aggregate.replies(), fanout.getString(REALM_ID), fanout.getString(REALM_KEY)));

    tryToGetSilent(() -> config.getConfig(ELASTIC_APM))
        .ifPresent(apm -> monitor(aggregate, config.getString(URI), config.getString(AUTH)));

    tryToGetSilent(() -> config.getConfig(ELASTIC_LOG))
        .ifPresent(
            log -> {
              log(aggregate, logLevel, VERSION, log.getString(URI), log.getString(AUTH));

              log(
                  getLogger(APP),
                  logLevel,
                  VERSION,
                  environment,
                  log.getString(URI),
                  log.getString(AUTH));
            });

    return aggregate.build();
  }

  private static CompletionStage<JsonObject> createCommand(final JsonObject event) {
    return completedFuture(createObjectBuilder().add(COMMAND, "plus").build());
  }

  @SuppressWarnings("squid:UnusedPrivateMethod") // Reactor example.
  private static StreamsBuilder createReactor(
      final StreamsBuilder builder, final Config config, final MongoClient mongoClient) {
    final String environment = getEnvironment(config);

    return new Reactor()
        .withBuilder(builder)
        .withEnvironment(environment)
        .withSourceType("plusminus-counter")
        .withDestinationType("plusminus-counter")
        .withDestinations(
            event ->
                getOthers(
                    event.getString(ID),
                    mongoClient.getDatabase(config.getString(MONGODB_DATABASE)),
                    environment))
        .withEventToCommand(Application::createCommand)
        .withFilter(event -> changed(event, "/value", createValue(9), createValue(10)))
        .build();
  }

  static String getEnvironment(final Config config) {
    return tryToGetSilent(() -> config.getString(ENVIRONMENT)).orElse(DEV);
  }

  private static Level getLogLevel(final Config config) {
    return parse(tryToGetSilent(() -> config.getString(LOG_LEVEL)).orElse(INFO));
  }

  static MongoClient getMongoClient(final Config config) {
    return create(config.getString(MONGODB_URI));
  }

  private static Publisher<JsonObject> getOthers(
      final String id, final MongoDatabase database, final String environment) {
    return aggregationPublisher(
        database.getCollection("plusminus-counter-" + environment),
        list(match(addNotDeleted(ne(ID, id))), project(include(ID))));
  }

  private static CompletionStage<JsonObject> reduce(
      final JsonObject currentState, final IntUnaryOperator op) {
    return completedFuture(
        createObjectBuilder(currentState)
            .add(VALUE, op.applyAsInt(currentState.getInt(VALUE, 0)))
            .build());
  }

  public static void main(final String[] args) {
    final Config config = loadDefault();

    tryToDoWithRethrow(
        () -> getMongoClient(config),
        client -> {
          final Topology topology = createApp(new StreamsBuilder(), config, client).build();

          getLogger(APP).log(Level.INFO, "Topology:${symbol_escape}n${symbol_escape}n {0}", topology.describe());

          if (!start(topology, Streams.fromConfig(config, KAFKA))) {
            exit(1);
          }
        });
  }
}