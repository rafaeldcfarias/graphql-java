package graphql.execution;

import com.google.common.collect.ImmutableList;
import graphql.ExecutionResult;
import graphql.ExecutionResultImpl;
import graphql.GraphQLError;
import graphql.Internal;
import graphql.PublicSpi;
import graphql.SerializationError;
import graphql.TrivialDataFetcher;
import graphql.TypeMismatchError;
import graphql.UnresolvedTypeError;
import graphql.collect.ImmutableKit;
import graphql.execution.directives.QueryDirectives;
import graphql.execution.directives.QueryDirectivesImpl;
import graphql.execution.instrumentation.Instrumentation;
import graphql.execution.instrumentation.InstrumentationContext;
import graphql.execution.instrumentation.parameters.InstrumentationFieldCompleteParameters;
import graphql.execution.instrumentation.parameters.InstrumentationFieldFetchParameters;
import graphql.execution.instrumentation.parameters.InstrumentationFieldParameters;
import graphql.extensions.ExtensionsBuilder;
import graphql.introspection.Introspection;
import graphql.language.Argument;
import graphql.language.Field;
import graphql.normalized.ExecutableNormalizedField;
import graphql.normalized.ExecutableNormalizedOperation;
import graphql.schema.CoercingSerializeException;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingFieldSelectionSet;
import graphql.schema.DataFetchingFieldSelectionSetImpl;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLCodeRegistry;
import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLOutputType;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;
import graphql.schema.LightDataFetcher;
import graphql.util.FpKit;
import graphql.util.LogKit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import java.util.function.Supplier;

import static graphql.execution.Async.exceptionallyCompletedFuture;
import static graphql.execution.ExecutionStepInfo.newExecutionStepInfo;
import static graphql.execution.FieldCollectorParameters.newParameters;
import static graphql.execution.FieldValueInfo.CompleteValueType.ENUM;
import static graphql.execution.FieldValueInfo.CompleteValueType.LIST;
import static graphql.execution.FieldValueInfo.CompleteValueType.NULL;
import static graphql.execution.FieldValueInfo.CompleteValueType.OBJECT;
import static graphql.execution.FieldValueInfo.CompleteValueType.SCALAR;
import static graphql.execution.instrumentation.SimpleInstrumentationContext.nonNullCtx;
import static graphql.schema.DataFetchingEnvironmentImpl.newDataFetchingEnvironment;
import static graphql.schema.GraphQLTypeUtil.isEnum;
import static graphql.schema.GraphQLTypeUtil.isList;
import static graphql.schema.GraphQLTypeUtil.isScalar;
import static java.util.concurrent.CompletableFuture.completedFuture;

/**
 * An execution strategy is give a list of fields from the graphql query to execute and find values for using a recursive strategy.
 * <pre>
 *     query {
 *          friends {
 *              id
 *              name
 *              friends {
 *                  id
 *                  name
 *              }
 *          }
 *          enemies {
 *              id
 *              name
 *              allies {
 *                  id
 *                  name
 *              }
 *          }
 *     }
 *
 * </pre>
 * <p>
 * Given the graphql query above, an execution strategy will be called for the top level fields 'friends' and 'enemies' and it will be asked to find an object
 * to describe them.  Because they are both complex object types, it needs to descend down that query and start fetching and completing
 * fields such as 'id','name' and other complex fields such as 'friends' and 'allies', by recursively calling to itself to execute these lower
 * field layers
 * <p>
 * The execution of a field has two phases, first a raw object must be fetched for a field via a {@link DataFetcher} which
 * is defined on the {@link GraphQLFieldDefinition}.  This object must then be 'completed' into a suitable value, either as a scalar/enum type via
 * coercion or if it's a complex object type by recursively calling the execution strategy for the lower level fields.
 * <p>
 * The first phase (data fetching) is handled by the method {@link #fetchField(ExecutionContext, ExecutionStrategyParameters)}
 * <p>
 * The second phase (value completion) is handled by the methods {@link #completeField(ExecutionContext, ExecutionStrategyParameters, FetchedValue)}
 * and the other "completeXXX" methods.
 * <p>
 * The order of fields fetching and completion is up to the execution strategy. As the graphql specification
 * <a href="https://spec.graphql.org/October2021/#sec-Normal-and-Serial-Execution">https://spec.graphql.org/October2021/#sec-Normal-and-Serial-Execution</a> says:
 * <blockquote>
 * Normally the executor can execute the entries in a grouped field set in whatever order it chooses (often in parallel). Because
 * the resolution of fields other than top-level mutation fields must always be side effect-free and idempotent, the
 * execution order must not affect the result, and hence the server has the freedom to execute the
 * field entries in whatever order it deems optimal.
 * </blockquote>
 * <p>
 * So in the case above you could execute the fields depth first ('friends' and its sub fields then do 'enemies' and its sub fields or it
 * could do breadth first ('fiends' and 'enemies' data fetch first and then all the sub fields) or in parallel via asynchronous
 * facilities like {@link CompletableFuture}s.
 * <p>
 * {@link #execute(ExecutionContext, ExecutionStrategyParameters)} is the entry point of the execution strategy.
 */
@PublicSpi
@SuppressWarnings("FutureReturnValueIgnored")
public abstract class ExecutionStrategy {

    private static final Logger log = LoggerFactory.getLogger(ExecutionStrategy.class);
    private static final Logger logNotSafe = LogKit.getNotPrivacySafeLogger(ExecutionStrategy.class);

    protected final FieldCollector fieldCollector = new FieldCollector();
    protected final ExecutionStepInfoFactory executionStepInfoFactory = new ExecutionStepInfoFactory();
    private final ResolveType resolvedType = new ResolveType();

    protected final DataFetcherExceptionHandler dataFetcherExceptionHandler;

    /**
     * The default execution strategy constructor uses the {@link SimpleDataFetcherExceptionHandler}
     * for data fetching errors.
     */
    protected ExecutionStrategy() {
        dataFetcherExceptionHandler = new SimpleDataFetcherExceptionHandler();
    }

    /**
     * The consumers of the execution strategy can pass in a {@link DataFetcherExceptionHandler} to better
     * decide what do when a data fetching error happens
     *
     * @param dataFetcherExceptionHandler the callback invoked if an exception happens during data fetching
     */
    protected ExecutionStrategy(DataFetcherExceptionHandler dataFetcherExceptionHandler) {
        this.dataFetcherExceptionHandler = dataFetcherExceptionHandler;
    }

    /**
     * This is the entry point to an execution strategy.  It will be passed the fields to execute and get values for.
     *
     * @param executionContext contains the top level execution parameters
     * @param parameters       contains the parameters holding the fields to be executed and source object
     *
     * @return a promise to an {@link ExecutionResult}
     *
     * @throws NonNullableFieldWasNullException in the future if a non null field resolves to a null value
     */
    public abstract CompletableFuture<ExecutionResult> execute(ExecutionContext executionContext, ExecutionStrategyParameters parameters) throws NonNullableFieldWasNullException;

    /**
     * This is the re-entry point to an execution strategy.  If the engine needs to call bck to the execution strategy (say to complete an object)
     * then it will call this method.
     * <p>
     * This method can return `CompletableFuture<ExecutionResult>` or a `ExecutionResult` depending on
     * whether the fields values are asynchronous or not
     *
     * @param executionContext contains the top level execution parameters
     * @param parameters       contains the parameters holding the fields to be executed and source object
     *
     * @return a promise to an {@link ExecutionResult} or just a {@link ExecutionResult}
     *
     * @throws NonNullableFieldWasNullException in the future if a non-null field resolves to a null value
     */
    public /* CompletableFuture<ExecutionResult> | ExecutionResult */ Object executePolymorphic(ExecutionContext executionContext, ExecutionStrategyParameters parameters) throws NonNullableFieldWasNullException {
        return execute(executionContext, parameters);
    }

    /**
     * Called to fetch a value for a field and resolve it further in terms of the graphql query.  This will call
     * #fetchField followed by #completeField and the completed {@link ExecutionResult} is returned.
     * <p>
     * An execution strategy can iterate the fields to be executed and call this method for each one
     * <p>
     * Graphql fragments mean that for any give logical field can have one or more {@link Field} values associated with it
     * in the query, hence the fieldList.  However the first entry is representative of the field for most purposes.
     *
     * @param executionContext contains the top level execution parameters
     * @param parameters       contains the parameters holding the fields to be executed and source object
     *
     * @return a promise to an {@link ExecutionResult}
     *
     * @throws NonNullableFieldWasNullException in the future if a non null field resolves to a null value
     */
    protected CompletableFuture<ExecutionResult> resolveField(ExecutionContext executionContext, ExecutionStrategyParameters parameters) {
        CompletableFuture<FieldValueInfo> cf = Async.asCompletableFuture(resolveFieldWithInfo(executionContext, parameters));
        return cf.thenCompose(FieldValueInfo::getFieldValue);
    }

    /**
     * Called to fetch a value for a field and its extra runtime info and resolve it further in terms of the graphql query.  This will call
     * #fetchField followed by #completeField and the completed {@link graphql.execution.FieldValueInfo} is returned.
     * <p>
     * An execution strategy can iterate the fields to be executed and call this method for each one
     * <p>
     * Graphql fragments mean that for any give logical field can have one or more {@link Field} values associated with it
     * in the query, hence the fieldList.  However the first entry is representative of the field for most purposes.
     *
     * @param executionContext contains the top level execution parameters
     * @param parameters       contains the parameters holding the fields to be executed and source object
     *
     * @return a promise to a {@link FieldValueInfo}
     *
     * @throws NonNullableFieldWasNullException in the {@link FieldValueInfo#getFieldValue()} future if a non null field resolves to a null value
     */
    protected /* CompletableFuture<FieldValueInfo> | FieldValueInfo */ Object resolveFieldWithInfo(ExecutionContext executionContext, ExecutionStrategyParameters parameters) {
        GraphQLFieldDefinition fieldDef = getFieldDef(executionContext, parameters, parameters.getField().getSingleField());
        Supplier<ExecutionStepInfo> executionStepInfo = FpKit.intraThreadMemoize(() -> createExecutionStepInfo(executionContext, parameters, fieldDef, null));

        Instrumentation instrumentation = executionContext.getInstrumentation();
        InstrumentationContext<ExecutionResult> fieldCtx = nonNullCtx(instrumentation.beginField(
                new InstrumentationFieldParameters(executionContext, executionStepInfo), executionContext.getInstrumentationState()
        ));

        Object fetchedField = fetchField(executionContext, parameters);
        if (fetchedField instanceof CompletableFuture) {
            @SuppressWarnings("unchecked")
            CompletableFuture<FetchedValue> fetchFieldFuture = (CompletableFuture<FetchedValue>) fetchedField;
            CompletableFuture<FieldValueInfo> result = fetchFieldFuture.thenApply((fetchedValue) ->
                    completeField(executionContext, parameters, fetchedValue));

            CompletableFuture<ExecutionResult> executionResultFuture = result.thenCompose(FieldValueInfo::getFieldValue);
            fieldCtx.onDispatched(executionResultFuture);
            executionResultFuture.whenComplete(fieldCtx::onCompleted);
            return result;
        } else {
            FetchedValue fetchedValue = (FetchedValue) fetchedField;
            FieldValueInfo fieldValueInfo = completeField(executionContext, parameters, fetchedValue);
            //
            // TODO - fix this up with other onDispatched calls
            CompletableFuture<ExecutionResult> executionResultFuture = fieldValueInfo.getFieldValue();
            fieldCtx.onDispatched(executionResultFuture);

            executionResultFuture.whenComplete(fieldCtx::onCompleted);
            return fieldValueInfo;
        }
    }

    /**
     * Called to fetch a value for a field from the {@link DataFetcher} associated with the field
     * {@link GraphQLFieldDefinition}.
     * <p>
     * Graphql fragments mean that for any give logical field can have one or more {@link Field} values associated with it
     * in the query, hence the fieldList.  However the first entry is representative of the field for most purposes.
     *
     * @param executionContext contains the top level execution parameters
     * @param parameters       contains the parameters holding the fields to be executed and source object
     *
     * @return a promise to a fetched object
     *
     * @throws NonNullableFieldWasNullException in the future if a non null field resolves to a null value
     */
    protected /* CompletableFuture<FetchedValue> | FetchedValue */ Object fetchField(ExecutionContext executionContext, ExecutionStrategyParameters parameters) {
        MergedField field = parameters.getField();
        GraphQLObjectType parentType = (GraphQLObjectType) parameters.getExecutionStepInfo().getUnwrappedNonNullType();
        GraphQLFieldDefinition fieldDef = getFieldDef(executionContext.getGraphQLSchema(), parentType, field.getSingleField());
        GraphQLCodeRegistry codeRegistry = executionContext.getGraphQLSchema().getCodeRegistry();

        // if the DF (like PropertyDataFetcher) does not use the arguments or execution step info then dont build any

        Supplier<DataFetchingEnvironment> dataFetchingEnvironment = FpKit.intraThreadMemoize(() -> {

            Supplier<ExecutionStepInfo> executionStepInfo = FpKit.intraThreadMemoize(
                    () -> createExecutionStepInfo(executionContext, parameters, fieldDef, parentType));

            Supplier<Map<String, Object>> argumentValues = () -> executionStepInfo.get().getArguments();

            Supplier<ExecutableNormalizedField> normalizedFieldSupplier = getNormalizedField(executionContext, parameters, executionStepInfo);

            // DataFetchingFieldSelectionSet and QueryDirectives is a supplier of sorts - eg a lazy pattern
            DataFetchingFieldSelectionSet fieldCollector = DataFetchingFieldSelectionSetImpl.newCollector(executionContext.getGraphQLSchema(), fieldDef.getType(), normalizedFieldSupplier);
            QueryDirectives queryDirectives = new QueryDirectivesImpl(field,
                    executionContext.getGraphQLSchema(),
                    executionContext.getCoercedVariables().toMap(),
                    executionContext.getGraphQLContext(),
                    executionContext.getLocale());


            return newDataFetchingEnvironment(executionContext)
                    .source(parameters.getSource())
                    .localContext(parameters.getLocalContext())
                    .arguments(argumentValues)
                    .fieldDefinition(fieldDef)
                    .mergedField(parameters.getField())
                    .fieldType(fieldDef.getType())
                    .executionStepInfo(executionStepInfo)
                    .parentType(parentType)
                    .selectionSet(fieldCollector)
                    .queryDirectives(queryDirectives)
                    .build();
        });
        DataFetcher<?> dataFetcher = codeRegistry.getDataFetcher(parentType, fieldDef);

        Instrumentation instrumentation = executionContext.getInstrumentation();

        InstrumentationFieldFetchParameters instrumentationFieldFetchParams = new InstrumentationFieldFetchParameters(executionContext, dataFetchingEnvironment, parameters, dataFetcher instanceof TrivialDataFetcher);
        InstrumentationContext<Object> fetchCtx = nonNullCtx(instrumentation.beginFieldFetch(instrumentationFieldFetchParams,
                executionContext.getInstrumentationState())
        );

        dataFetcher = instrumentation.instrumentDataFetcher(dataFetcher, instrumentationFieldFetchParams, executionContext.getInstrumentationState());
        Object fetchedValue = invokeDataFetcher(executionContext, parameters, fieldDef, dataFetchingEnvironment, dataFetcher);
        //
        // this is problematic as we have to make a CF just to call onDispatched - the viral nature of CFs
        // BUT the data loader code for example wants this as a way to know a field is dispatched
        // even thought it never cares about the value at all
        fetchCtx.onDispatched(Async.asCompletableFuture(fetchedValue));

        if (fetchedValue instanceof CompletableFuture) {
            @SuppressWarnings("unchecked")
            CompletableFuture<Object> fetchedCF = (CompletableFuture<Object>) fetchedValue;
            return fetchedCF
                    .handle((result, exception) -> {
                        fetchCtx.onCompleted(result, exception);
                        if (exception != null) {
                            return handleFetchingException(dataFetchingEnvironment.get(), exception);
                        } else {
                            // we can simply return the fetched value CF and avoid a allocation
                            return fetchedCF;
                        }
                    })
                    .thenCompose(Function.identity())
                    .thenApply(result -> unboxPossibleDataFetcherResult(executionContext, parameters, result));
        } else {
            fetchCtx.onCompleted(fetchedValue, null);
            return unboxPossibleDataFetcherResult(executionContext, parameters, fetchedValue);
        }
    }

    private /* CompletableFuture<Object> | Object */ Object invokeDataFetcher(ExecutionContext executionContext, ExecutionStrategyParameters parameters, GraphQLFieldDefinition fieldDef, Supplier<DataFetchingEnvironment> dataFetchingEnvironment, DataFetcher<?> dataFetcher) {
        Object fetchedValue;
        try {
            Object fetchedValueRaw;
            if (dataFetcher instanceof LightDataFetcher) {
                fetchedValueRaw = ((LightDataFetcher<?>) dataFetcher).get(fieldDef, parameters.getSource(), dataFetchingEnvironment);
            } else {
                fetchedValueRaw = dataFetcher.get(dataFetchingEnvironment.get());
            }
            fetchedValue = fetchedValueRaw;
        } catch (Exception e) {
            if (logNotSafe.isDebugEnabled()) {
                logNotSafe.debug(String.format("'%s', field '%s' fetch threw exception", executionContext.getExecutionId(), parameters.getPath()), e);
            }
            fetchedValue = Async.exceptionallyCompletedFuture(e);
        }
        return fetchedValue;
    }

    protected Supplier<ExecutableNormalizedField> getNormalizedField(ExecutionContext executionContext, ExecutionStrategyParameters parameters, Supplier<ExecutionStepInfo> executionStepInfo) {
        Supplier<ExecutableNormalizedOperation> normalizedQuery = executionContext.getNormalizedQueryTree();
        return () -> normalizedQuery.get().getNormalizedField(parameters.getField(), executionStepInfo.get().getObjectType(), executionStepInfo.get().getPath());
    }

    protected FetchedValue unboxPossibleDataFetcherResult(ExecutionContext executionContext,
                                                          ExecutionStrategyParameters parameters,
                                                          Object result) {

        if (result instanceof DataFetcherResult) {
            DataFetcherResult<?> dataFetcherResult = (DataFetcherResult<?>) result;
            executionContext.addErrors(dataFetcherResult.getErrors());
            addExtensionsIfPresent(executionContext, dataFetcherResult);

            Object localContext = dataFetcherResult.getLocalContext();
            if (localContext == null) {
                // if the field returns nothing then they get the context of their parent field
                localContext = parameters.getLocalContext();
            }
            return FetchedValue.newFetchedValue()
                    .fetchedValue(executionContext.getValueUnboxer().unbox(dataFetcherResult.getData()))
                    .rawFetchedValue(dataFetcherResult.getData())
                    .errors(dataFetcherResult.getErrors())
                    .localContext(localContext)
                    .build();
        } else {
            return FetchedValue.newFetchedValue()
                    .fetchedValue(executionContext.getValueUnboxer().unbox(result))
                    .rawFetchedValue(result)
                    .localContext(parameters.getLocalContext())
                    .build();
        }
    }

    private void addExtensionsIfPresent(ExecutionContext executionContext, DataFetcherResult<?> dataFetcherResult) {
        Map<Object, Object> extensions = dataFetcherResult.getExtensions();
        if (extensions != null) {
            ExtensionsBuilder extensionsBuilder = executionContext.getGraphQLContext().get(ExtensionsBuilder.class);
            if (extensionsBuilder != null) {
                extensionsBuilder.addValues(extensions);
            }
        }
    }

    protected <T> CompletableFuture<T> handleFetchingException(
            DataFetchingEnvironment environment,
            Throwable e) {
        DataFetcherExceptionHandlerParameters handlerParameters = DataFetcherExceptionHandlerParameters.newExceptionParameters()
                .dataFetchingEnvironment(environment)
                .exception(e)
                .build();

        try {
            return asyncHandleException(dataFetcherExceptionHandler, handlerParameters);
        } catch (Exception handlerException) {
            handlerParameters = DataFetcherExceptionHandlerParameters.newExceptionParameters()
                    .dataFetchingEnvironment(environment)
                    .exception(handlerException)
                    .build();
            return asyncHandleException(new SimpleDataFetcherExceptionHandler(), handlerParameters);
        }
    }

    private <T> CompletableFuture<T> asyncHandleException(DataFetcherExceptionHandler handler, DataFetcherExceptionHandlerParameters handlerParameters) {
        //noinspection unchecked
        return handler.handleException(handlerParameters).thenApply(
                handlerResult -> (T) DataFetcherResult.<FetchedValue>newResult().errors(handlerResult.getErrors()).build()
        );
    }

    /**
     * Called to complete a field based on the type of the field.
     * <p>
     * If the field is a scalar type, then it will be coerced  and returned.  However if the field type is an complex object type, then
     * the execution strategy will be called recursively again to execute the fields of that type before returning.
     * <p>
     * Graphql fragments mean that for any give logical field can have one or more {@link Field} values associated with it
     * in the query, hence the fieldList.  However the first entry is representative of the field for most purposes.
     *
     * @param executionContext contains the top level execution parameters
     * @param parameters       contains the parameters holding the fields to be executed and source object
     * @param fetchedValue     the fetched raw value
     *
     * @return a {@link FieldValueInfo}
     *
     * @throws NonNullableFieldWasNullException in the {@link FieldValueInfo#getFieldValue()} future if a non null field resolves to a null value
     */
    protected FieldValueInfo completeField(ExecutionContext executionContext, ExecutionStrategyParameters parameters, FetchedValue fetchedValue) {
        Field field = parameters.getField().getSingleField();
        GraphQLObjectType parentType = (GraphQLObjectType) parameters.getExecutionStepInfo().getUnwrappedNonNullType();
        GraphQLFieldDefinition fieldDef = getFieldDef(executionContext.getGraphQLSchema(), parentType, field);
        ExecutionStepInfo executionStepInfo = createExecutionStepInfo(executionContext, parameters, fieldDef, parentType);

        Instrumentation instrumentation = executionContext.getInstrumentation();
        InstrumentationFieldCompleteParameters instrumentationParams = new InstrumentationFieldCompleteParameters(executionContext, parameters, () -> executionStepInfo, fetchedValue);
        InstrumentationContext<ExecutionResult> ctxCompleteField = nonNullCtx(instrumentation.beginFieldComplete(
                instrumentationParams, executionContext.getInstrumentationState()
        ));

        NonNullableFieldValidator nonNullableFieldValidator = new NonNullableFieldValidator(executionContext, executionStepInfo);

        ExecutionStrategyParameters newParameters = parameters.transform(builder ->
                builder.executionStepInfo(executionStepInfo)
                        .source(fetchedValue.getFetchedValue())
                        .localContext(fetchedValue.getLocalContext())
                        .nonNullFieldValidator(nonNullableFieldValidator)
        );

        if (log.isDebugEnabled()) {
            log.debug("'{}' completing field '{}'...", executionContext.getExecutionId(), executionStepInfo.getPath());
        }

        FieldValueInfo fieldValueInfo = completeValue(executionContext, newParameters);

        // TODO - fix this up with other onDispatched calls
        CompletableFuture<ExecutionResult> executionResultFuture = fieldValueInfo.getFieldValue();
        ctxCompleteField.onDispatched(executionResultFuture);

        executionResultFuture.whenComplete(ctxCompleteField::onCompleted);

        return fieldValueInfo;
    }


    /**
     * Called to complete a value for a field based on the type of the field.
     * <p>
     * If the field is a scalar type, then it will be coerced  and returned.  However if the field type is an complex object type, then
     * the execution strategy will be called recursively again to execute the fields of that type before returning.
     * <p>
     * Graphql fragments mean that for any give logical field can have one or more {@link Field} values associated with it
     * in the query, hence the fieldList.  However the first entry is representative of the field for most purposes.
     *
     * @param executionContext contains the top level execution parameters
     * @param parameters       contains the parameters holding the fields to be executed and source object
     *
     * @return a {@link FieldValueInfo}
     *
     * @throws NonNullableFieldWasNullException if a non null field resolves to a null value
     */
    protected FieldValueInfo completeValue(ExecutionContext executionContext, ExecutionStrategyParameters parameters) throws NonNullableFieldWasNullException {
        ExecutionStepInfo executionStepInfo = parameters.getExecutionStepInfo();
        Object result = executionContext.getValueUnboxer().unbox(parameters.getSource());
        GraphQLType fieldType = executionStepInfo.getUnwrappedNonNullType();
        Object fieldValue;

        if (result == null) {
            return getFieldValueInfoForNull(executionContext, parameters);
        } else if (isList(fieldType)) {
            return completeValueForList(executionContext, parameters, result);
        } else if (isScalar(fieldType)) {
            fieldValue = completeValueForScalar(executionContext, parameters, (GraphQLScalarType) fieldType, result);
            return FieldValueInfo.newFieldValueInfo(SCALAR).fieldValue(fieldValue).build();
        } else if (isEnum(fieldType)) {
            fieldValue = completeValueForEnum(executionContext, parameters, (GraphQLEnumType) fieldType, result);
            return FieldValueInfo.newFieldValueInfo(ENUM).fieldValue(fieldValue).build();
        }

        // when we are here, we have a complex type: Interface, Union or Object
        // and we must go deeper
        //
        GraphQLObjectType resolvedObjectType;
        try {
            resolvedObjectType = resolveType(executionContext, parameters, fieldType);
            fieldValue = completeValueForObject(executionContext, parameters, resolvedObjectType, result);
        } catch (UnresolvedTypeException ex) {
            // consider the result to be null and add the error on the context
            handleUnresolvedTypeProblem(executionContext, parameters, ex);
            // complete field as null, validating it is nullable
            return getFieldValueInfoForNull(executionContext, parameters);
        }
        return FieldValueInfo.newFieldValueInfo(OBJECT).fieldValue(fieldValue).build();
    }

    private void handleUnresolvedTypeProblem(ExecutionContext context, ExecutionStrategyParameters parameters, UnresolvedTypeException e) {
        UnresolvedTypeError error = new UnresolvedTypeError(parameters.getPath(), parameters.getExecutionStepInfo(), e);
        logNotSafe.warn(error.getMessage(), e);
        context.addError(error);

    }

    /**
     * Called to complete a null value.
     *
     * @param executionContext contains the top level execution parameters
     * @param parameters       contains the parameters holding the fields to be executed and source object
     *
     * @return a {@link FieldValueInfo}
     *
     * @throws NonNullableFieldWasNullException if a non null field resolves to a null value
     */
    private FieldValueInfo getFieldValueInfoForNull(ExecutionContext executionContext, ExecutionStrategyParameters parameters) {
        CompletableFuture<ExecutionResult> fieldValue = completeValueForNull(executionContext, parameters);
        return FieldValueInfo.newFieldValueInfo(NULL).fieldValue(fieldValue).build();
    }

    protected CompletableFuture<ExecutionResult> completeValueForNull(ExecutionContext executionContext, ExecutionStrategyParameters parameters) {
        return Async.tryCatch(() -> {
            Object nullValue = parameters.getNonNullFieldValidator().validate(parameters.getPath(), null);
            return completedFuture(new ExecutionResultImpl(nullValue, executionContext.getErrors()));
        });
    }

    /**
     * Called to complete a list of value for a field based on a list type.  This iterates the values and calls
     * {@link #completeValue(ExecutionContext, ExecutionStrategyParameters)} for each value.
     *
     * @param executionContext contains the top level execution parameters
     * @param parameters       contains the parameters holding the fields to be executed and source object
     * @param result           the result to complete, raw result
     *
     * @return a {@link FieldValueInfo}
     */
    protected FieldValueInfo completeValueForList(ExecutionContext executionContext, ExecutionStrategyParameters parameters, Object result) {
        Iterable<Object> resultIterable = toIterable(executionContext, parameters, result);
        try {
            resultIterable = parameters.getNonNullFieldValidator().validate(parameters.getPath(), resultIterable);
        } catch (NonNullableFieldWasNullException e) {
            return FieldValueInfo.newFieldValueInfo(LIST).fieldValue(exceptionallyCompletedFuture(e)).build();
        }
        if (resultIterable == null) {
            return FieldValueInfo.newFieldValueInfo(LIST).fieldValue(new ExecutionResultImpl(null, executionContext.getErrors())).build();
        }
        return completeValueForList(executionContext, parameters, resultIterable);
    }

    /**
     * Called to complete a list of value for a field based on a list type.  This iterates the values and calls
     * {@link #completeValue(ExecutionContext, ExecutionStrategyParameters)} for each value.
     *
     * @param executionContext contains the top level execution parameters
     * @param parameters       contains the parameters holding the fields to be executed and source object
     * @param iterableValues   the values to complete, can't be null
     *
     * @return a {@link FieldValueInfo}
     */
    protected FieldValueInfo completeValueForList(ExecutionContext executionContext, ExecutionStrategyParameters parameters, Iterable<Object> iterableValues) {

        OptionalInt size = FpKit.toSize(iterableValues);
        ExecutionStepInfo executionStepInfo = parameters.getExecutionStepInfo();

        InstrumentationFieldCompleteParameters instrumentationParams = new InstrumentationFieldCompleteParameters(executionContext, parameters, () -> executionStepInfo, iterableValues);
        Instrumentation instrumentation = executionContext.getInstrumentation();

        InstrumentationContext<ExecutionResult> completeListCtx = nonNullCtx(instrumentation.beginFieldListComplete(
                instrumentationParams, executionContext.getInstrumentationState()
        ));

        List<FieldValueInfo> fieldValueInfos = new ArrayList<>(size.orElse(1));
        int index = 0;
        for (Object item : iterableValues) {
            ResultPath indexedPath = parameters.getPath().segment(index);

            ExecutionStepInfo stepInfoForListElement = executionStepInfoFactory.newExecutionStepInfoForListElement(executionStepInfo, indexedPath);

            NonNullableFieldValidator nonNullableFieldValidator = new NonNullableFieldValidator(executionContext, stepInfoForListElement);

            FetchedValue value = unboxPossibleDataFetcherResult(executionContext, parameters, item);

            ExecutionStrategyParameters newParameters = parameters.transform(builder ->
                    builder.executionStepInfo(stepInfoForListElement)
                            .nonNullFieldValidator(nonNullableFieldValidator)
                            .localContext(value.getLocalContext())
                            .path(indexedPath)
                            .source(value.getFetchedValue())
            );
            fieldValueInfos.add(completeValue(executionContext, newParameters));
            index++;
        }

        Async.CombinedBuilder<ExecutionResult> futures = Async.ofExpectedSize(fieldValueInfos.size());
        for (FieldValueInfo fieldValueInfo : fieldValueInfos) {
            if (fieldValueInfo.isFutureValue()) {
                futures.add(fieldValueInfo.getFieldValue());
            } else {
                futures.addObject(fieldValueInfo.getFieldValueMaterialised());
            }
        }
        Object fieldValueResult;
        Object listOfResults = futures.awaitPolymorphic();
        if (listOfResults instanceof CompletableFuture) {
            @SuppressWarnings("unchecked")
            CompletableFuture<List<ExecutionResult>> resultsFuture = (CompletableFuture<List<ExecutionResult>>) listOfResults;

            CompletableFuture<ExecutionResult> overallResult = new CompletableFuture<>();
            completeListCtx.onDispatched(overallResult);
            resultsFuture.whenComplete((results, exception) -> {
                if (exception != null) {
                    ExecutionResult executionResult = handleNonNullException(executionContext, overallResult, exception);
                    completeListCtx.onCompleted(executionResult, exception);
                    return;
                }
                List<Object> completedResults = new ArrayList<>(results.size());
                for (ExecutionResult completedValue : results) {
                    completedResults.add(completedValue.getData());
                }
                ExecutionResultImpl executionResult = new ExecutionResultImpl(completedResults, executionContext.getErrors());
                overallResult.complete(executionResult);
            });
            overallResult.whenComplete(completeListCtx::onCompleted);
            fieldValueResult = overallResult;
        } else {
            CompletableFuture<ExecutionResult> overallResult = new CompletableFuture<>();
            // TODO - fix this since we dont want a CF here
            completeListCtx.onDispatched(overallResult);

            @SuppressWarnings("unchecked")
            List<ExecutionResult> results = (List<ExecutionResult>) listOfResults;
            List<Object> completedResults = new ArrayList<>(results.size());
            for (ExecutionResult completedValue : results) {
                completedResults.add(completedValue.getData());
            }
            ExecutionResultImpl executionResult = new ExecutionResultImpl(completedResults, executionContext.getErrors());

            overallResult.complete(executionResult); // TODO - this goes if we fix InstrumentationContext
            completeListCtx.onCompleted(executionResult, null);
            fieldValueResult = executionResult;
        }

        return FieldValueInfo.newFieldValueInfo(LIST)
                .fieldValue(fieldValueResult)
                .fieldValueInfos(fieldValueInfos)
                .build();
    }

    /**
     * Called to turn an object into a scalar value according to the {@link GraphQLScalarType} by asking that scalar type to coerce the object
     * into a valid value
     *
     * @param executionContext contains the top level execution parameters
     * @param parameters       contains the parameters holding the fields to be executed and source object
     * @param scalarType       the type of the scalar
     * @param result           the result to be coerced
     *
     * @return a promise to an {@link ExecutionResult}
     */
    protected /* CompletableFuture<ExecutionResult> | ExecutionResult */ Object completeValueForScalar(ExecutionContext executionContext, ExecutionStrategyParameters parameters, GraphQLScalarType scalarType, Object result) {
        Object serialized;
        try {
            serialized = scalarType.getCoercing().serialize(result, executionContext.getGraphQLContext(), executionContext.getLocale());
        } catch (CoercingSerializeException e) {
            serialized = handleCoercionProblem(executionContext, parameters, e);
        }

        try {
            serialized = parameters.getNonNullFieldValidator().validate(parameters.getPath(), serialized);
        } catch (NonNullableFieldWasNullException e) {
            return exceptionallyCompletedFuture(e);
        }
        return new ExecutionResultImpl(serialized, executionContext.getErrors());
    }

    /**
     * Called to turn an object into a enum value according to the {@link GraphQLEnumType} by asking that enum type to coerce the object into a valid value
     *
     * @param executionContext contains the top level execution parameters
     * @param parameters       contains the parameters holding the fields to be executed and source object
     * @param enumType         the type of the enum
     * @param result           the result to be coerced
     *
     * @return a promise to an {@link ExecutionResult}
     */
    protected /* CompletableFuture<ExecutionResult> | ExecutionResult */ Object completeValueForEnum(ExecutionContext executionContext, ExecutionStrategyParameters parameters, GraphQLEnumType enumType, Object result) {
        Object serialized;
        try {
            serialized = enumType.serialize(result, executionContext.getGraphQLContext(), executionContext.getLocale());
        } catch (CoercingSerializeException e) {
            serialized = handleCoercionProblem(executionContext, parameters, e);
        }
        try {
            serialized = parameters.getNonNullFieldValidator().validate(parameters.getPath(), serialized);
        } catch (NonNullableFieldWasNullException e) {
            return exceptionallyCompletedFuture(e);
        }
        return new ExecutionResultImpl(serialized, executionContext.getErrors());
    }

    /**
     * Called to turn a java object value into an graphql object value
     *
     * @param executionContext   contains the top level execution parameters
     * @param parameters         contains the parameters holding the fields to be executed and source object
     * @param resolvedObjectType the resolved object type
     * @param result             the result to be coerced
     *
     * @return a promise to an {@link ExecutionResult}
     */
    protected /* CompletableFuture<ExecutionResult> | ExecutionResult */ Object completeValueForObject(ExecutionContext executionContext, ExecutionStrategyParameters parameters, GraphQLObjectType resolvedObjectType, Object result) {
        ExecutionStepInfo executionStepInfo = parameters.getExecutionStepInfo();

        FieldCollectorParameters collectorParameters = newParameters()
                .schema(executionContext.getGraphQLSchema())
                .objectType(resolvedObjectType)
                .fragments(executionContext.getFragmentsByName())
                .variables(executionContext.getCoercedVariables().toMap())
                .build();

        MergedSelectionSet subFields = fieldCollector.collectFields(collectorParameters, parameters.getField());

        ExecutionStepInfo newExecutionStepInfo = executionStepInfo.changeTypeWithPreservedNonNull(resolvedObjectType);
        NonNullableFieldValidator nonNullableFieldValidator = new NonNullableFieldValidator(executionContext, newExecutionStepInfo);

        ExecutionStrategyParameters newParameters = parameters.transform(builder ->
                builder.executionStepInfo(newExecutionStepInfo)
                        .fields(subFields)
                        .nonNullFieldValidator(nonNullableFieldValidator)
                        .source(result)
        );

        // Calling this from the executionContext to ensure we shift back from mutation strategy to the query strategy.
        return executionContext.getQueryStrategy().executePolymorphic(executionContext, newParameters);
    }

    @SuppressWarnings("SameReturnValue")
    private Object handleCoercionProblem(ExecutionContext context, ExecutionStrategyParameters parameters, CoercingSerializeException e) {
        SerializationError error = new SerializationError(parameters.getPath(), e);
        logNotSafe.warn(error.getMessage(), e);
        context.addError(error);


        return null;
    }

    protected GraphQLObjectType resolveType(ExecutionContext executionContext, ExecutionStrategyParameters parameters, GraphQLType fieldType) {
        // we can avoid a method call and type resolver environment allocation if we know it's an object type
        if (fieldType instanceof GraphQLObjectType) {
            return (GraphQLObjectType) fieldType;
        }
        return resolvedType.resolveType(executionContext, parameters.getField(), parameters.getSource(), parameters.getExecutionStepInfo(), fieldType, parameters.getLocalContext());
    }


    protected Iterable<Object> toIterable(ExecutionContext context, ExecutionStrategyParameters parameters, Object result) {
        if (FpKit.isIterable(result)) {
            return FpKit.toIterable(result);
        }

        handleTypeMismatchProblem(context, parameters, result);
        return null;
    }


    private void handleTypeMismatchProblem(ExecutionContext context, ExecutionStrategyParameters parameters, Object result) {
        TypeMismatchError error = new TypeMismatchError(parameters.getPath(), parameters.getExecutionStepInfo().getUnwrappedNonNullType());
        logNotSafe.warn("{} got {}", error.getMessage(), result.getClass());
        context.addError(error);
    }


    /**
     * Called to discover the field definition give the current parameters and the AST {@link Field}
     *
     * @param executionContext contains the top level execution parameters
     * @param parameters       contains the parameters holding the fields to be executed and source object
     * @param field            the field to find the definition of
     *
     * @return a {@link GraphQLFieldDefinition}
     */
    protected GraphQLFieldDefinition getFieldDef(ExecutionContext executionContext, ExecutionStrategyParameters parameters, Field field) {
        GraphQLObjectType parentType = (GraphQLObjectType) parameters.getExecutionStepInfo().getUnwrappedNonNullType();
        return getFieldDef(executionContext.getGraphQLSchema(), parentType, field);
    }

    /**
     * Called to discover the field definition give the current parameters and the AST {@link Field}
     *
     * @param schema     the schema in play
     * @param parentType the parent type of the field
     * @param field      the field to find the definition of
     *
     * @return a {@link GraphQLFieldDefinition}
     */
    protected GraphQLFieldDefinition getFieldDef(GraphQLSchema schema, GraphQLObjectType parentType, Field field) {
        return Introspection.getFieldDef(schema, parentType, field.getName());
    }

    /**
     * See (<a href="https://spec.graphql.org/October2021/#sec-Errors-and-Non-Nullability">...</a>),
     * <p>
     * If a non nullable child field type actually resolves to a null value and the parent type is nullable
     * then the parent must in fact become null
     * so we use exceptions to indicate this special case.  However if the parent is in fact a non nullable type
     * itself then we need to bubble that upwards again until we get to the root in which case the result
     * is meant to be null.
     *
     * @param e this indicates that a null value was returned for a non null field, which needs to cause the parent field
     *          to become null OR continue on as an exception
     *
     * @throws NonNullableFieldWasNullException if a non null field resolves to a null value
     */
    protected void assertNonNullFieldPrecondition(NonNullableFieldWasNullException e) throws NonNullableFieldWasNullException {
        ExecutionStepInfo executionStepInfo = e.getExecutionStepInfo();
        if (executionStepInfo.hasParent() && executionStepInfo.getParent().isNonNullType()) {
            throw new NonNullableFieldWasNullException(e);
        }
    }

    protected void assertNonNullFieldPrecondition(NonNullableFieldWasNullException e, CompletableFuture<?> completableFuture) throws NonNullableFieldWasNullException {
        ExecutionStepInfo executionStepInfo = e.getExecutionStepInfo();
        if (executionStepInfo.hasParent() && executionStepInfo.getParent().isNonNullType()) {
            completableFuture.completeExceptionally(new NonNullableFieldWasNullException(e));
        }
    }

    protected ExecutionResult handleNonNullException(ExecutionContext executionContext, CompletableFuture<ExecutionResult> result, Throwable e) {
        ExecutionResult executionResult = null;
        List<GraphQLError> errors = ImmutableList.copyOf(executionContext.getErrors());
        Throwable underlyingException = e;
        if (e instanceof CompletionException) {
            underlyingException = e.getCause();
        }
        if (underlyingException instanceof NonNullableFieldWasNullException) {
            assertNonNullFieldPrecondition((NonNullableFieldWasNullException) underlyingException, result);
            if (!result.isDone()) {
                executionResult = new ExecutionResultImpl(null, errors);
                result.complete(executionResult);
            }
        } else if (underlyingException instanceof AbortExecutionException) {
            AbortExecutionException abortException = (AbortExecutionException) underlyingException;
            executionResult = abortException.toExecutionResult();
            result.complete(executionResult);
        } else {
            result.completeExceptionally(e);
        }
        return executionResult;
    }


    /**
     * Builds the type info hierarchy for the current field
     *
     * @param executionContext the execution context  in play
     * @param parameters       contains the parameters holding the fields to be executed and source object
     * @param fieldDefinition  the field definition to build type info for
     * @param fieldContainer   the field container
     *
     * @return a new type info
     */
    protected ExecutionStepInfo createExecutionStepInfo(ExecutionContext executionContext,
                                                        ExecutionStrategyParameters parameters,
                                                        GraphQLFieldDefinition fieldDefinition,
                                                        GraphQLObjectType fieldContainer) {
        MergedField field = parameters.getField();
        ExecutionStepInfo parentStepInfo = parameters.getExecutionStepInfo();
        GraphQLOutputType fieldType = fieldDefinition.getType();
        List<GraphQLArgument> fieldArgDefs = fieldDefinition.getArguments();
        Supplier<Map<String, Object>> argumentValues = ImmutableKit::emptyMap;
        //
        // no need to create args at all if there are none on the field def
        //
        if (!fieldArgDefs.isEmpty()) {
            List<Argument> fieldArgs = field.getArguments();
            GraphQLCodeRegistry codeRegistry = executionContext.getGraphQLSchema().getCodeRegistry();
            Supplier<Map<String, Object>> argValuesSupplier = () -> ValuesResolver.getArgumentValues(codeRegistry,
                    fieldArgDefs,
                    fieldArgs,
                    executionContext.getCoercedVariables(),
                    executionContext.getGraphQLContext(),
                    executionContext.getLocale());
            argumentValues = FpKit.intraThreadMemoize(argValuesSupplier);
        }


        return newExecutionStepInfo()
                .type(fieldType)
                .fieldDefinition(fieldDefinition)
                .fieldContainer(fieldContainer)
                .field(field)
                .path(parameters.getPath())
                .parentInfo(parentStepInfo)
                .arguments(argumentValues)
                .build();
    }


    @Internal
    public static String mkNameForPath(Field currentField) {
        return mkNameForPath(Collections.singletonList(currentField));
    }

    @Internal
    public static String mkNameForPath(MergedField mergedField) {
        return mkNameForPath(mergedField.getFields());
    }


    @Internal
    public static String mkNameForPath(List<Field> currentField) {
        Field field = currentField.get(0);
        return field.getResultKey();
    }
}
