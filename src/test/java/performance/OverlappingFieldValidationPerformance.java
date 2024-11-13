package performance;

import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.i18n.I18n;
import graphql.language.Document;
import graphql.parser.Parser;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.SchemaGenerator;
import graphql.validation.LanguageTraversal;
import graphql.validation.RulesVisitor;
import graphql.validation.ValidationContext;
import graphql.validation.ValidationError;
import graphql.validation.ValidationErrorCollector;
import graphql.validation.rules.OverlappingFieldsCanBeMerged;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static graphql.Assert.assertTrue;

@State(Scope.Benchmark)
@Warmup(iterations = 2, time = 5)
@Measurement(iterations = 3)
@Fork(3)
public class OverlappingFieldValidationPerformance {

    @State(Scope.Benchmark)
    public static class MyState {

        GraphQLSchema schema;
        Document document;

        @Setup
        public void setup() {
            try {
                String schemaString = PerformanceTestingUtils.loadResource("large-schema-4.graphqls");
                String query = PerformanceTestingUtils.loadResource("large-schema-4-query.graphql");
                schema = SchemaGenerator.createdMockedSchema(schemaString);
                document = Parser.parse(query);

                // make sure this is a valid query overall
                GraphQL graphQL = GraphQL.newGraphQL(schema).build();
                ExecutionResult executionResult = graphQL.execute(query);
                assertTrue(executionResult.getErrors().size() == 0);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    public void overlappingFieldValidationAbgTime(MyState myState, Blackhole blackhole) {
        blackhole.consume(validateQuery(myState.schema, myState.document));
    }

    @Benchmark
    @OutputTimeUnit(TimeUnit.SECONDS)
    public void overlappingFieldValidationThroughput(MyState myState, Blackhole blackhole) {
        blackhole.consume(validateQuery(myState.schema, myState.document));
    }

    private List<ValidationError> validateQuery(GraphQLSchema schema, Document document) {
        ValidationErrorCollector errorCollector = new ValidationErrorCollector();
        I18n i18n = I18n.i18n(I18n.BundleType.Validation, Locale.ENGLISH);
        ValidationContext validationContext = new ValidationContext(schema, document, i18n);
        OverlappingFieldsCanBeMerged overlappingFieldsCanBeMerged = new OverlappingFieldsCanBeMerged(validationContext, errorCollector);
        LanguageTraversal languageTraversal = new LanguageTraversal();
        languageTraversal.traverse(document, new RulesVisitor(validationContext, Collections.singletonList(overlappingFieldsCanBeMerged)));
        return errorCollector.getErrors();
    }
}
