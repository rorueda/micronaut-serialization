/*
 * Copyright 2017-2021 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.serde.support.deserializers;

import io.micronaut.core.annotation.AnnotatedElement;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Creator;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.NextMajorVersion;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.beans.BeanIntrospection;
import io.micronaut.core.beans.BeanMethod;
import io.micronaut.core.beans.BeanProperty;
import io.micronaut.core.beans.BeanWriteProperty;
import io.micronaut.core.beans.UnsafeBeanWriteProperty;
import io.micronaut.core.bind.annotation.Bindable;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.convert.exceptions.ConversionErrorException;
import io.micronaut.core.naming.NameUtils;
import io.micronaut.core.type.Argument;
import io.micronaut.core.type.GenericPlaceholder;
import io.micronaut.core.util.ArrayUtils;
import io.micronaut.inject.annotation.AnnotationMetadataHierarchy;
import io.micronaut.serde.Decoder;
import io.micronaut.serde.Deserializer;
import io.micronaut.serde.config.DeserializationConfiguration;
import io.micronaut.serde.config.SerdeConfiguration;
import io.micronaut.serde.config.annotation.SerdeConfig;
import io.micronaut.serde.config.naming.PropertyNamingStrategy;
import io.micronaut.serde.exceptions.InvalidFormatException;
import io.micronaut.serde.exceptions.InvalidPropertyFormatException;
import io.micronaut.serde.exceptions.SerdeException;
import io.micronaut.serde.support.util.SerdeAnnotationUtil;
import io.micronaut.serde.support.util.SerdeArgumentConf;
import io.micronaut.serde.support.util.SubtypeInfo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * Holder for data about a deserializable bean.
 *
 * @param <T> The generic type
 */
@Internal
final class DeserBean<T> {
    private static final String JK_PROP = "com.fasterxml.jackson.annotation.JsonProperty";

    // CHECKSTYLE:OFF
    @NonNull
    public final BeanIntrospection<T> introspection;
    @Nullable
    public final PropertiesBag<T> creatorParams;
    @Nullable
    public final DerProperty<T, Object>[] creatorUnwrapped;
    @Nullable
    public final PropertiesBag<T> injectProperties;
    @Nullable
    public final DerProperty<T, Object>[] unwrappedProperties;
    @Nullable
    public final AnySetter anySetter;
    @Nullable
    public final String wrapperProperty;
    @Nullable
    public final DeserializeSubtypeInfo<T> subtypeInfo;
    @Nullable
    public final Set<String> ignoredProperties;
    @Nullable
    public final Set<String> externalProperties;
    @Nullable
    public final boolean isJsonValueProperty;

    public final int creatorSize;
    public final int injectPropertiesSize;

    public final boolean ignoreUnknown;
    public final boolean delegating;
    public final boolean simpleBean;
    public final boolean recordLikeBean;

    public final boolean hasBuilder;
    public final ConversionService conversionService;

    private final Map<String, Argument<?>> bounds;

    private volatile boolean initialized;
    private volatile boolean initializing;

    // CHECKSTYLE:ON

    public DeserBean(DeserializationConfiguration defaultDeserializationConfiguration,
                     Argument<T> type,
                     BeanIntrospection<T> introspection,
                     Deserializer.DecoderContext decoderContext,
                     DeserBeanRegistry deserBeanRegistry,
                     @Nullable SerdeArgumentConf serdeArgumentConf)
        throws SerdeException {
        // !!! Avoid accessing annotations from the argument, the annotations are not included in the cache key

        if (type.hasTypeVariables()) {
            bounds = type.getTypeVariables();
        } else {
            bounds = Collections.emptyMap();
        }

        PropertyNamingStrategy defaultPropertyNamingStrategy = decoderContext.getSerdeConfiguration().map(SerdeConfiguration::getPropertyNamingStrategy).orElse(null);
        this.conversionService = decoderContext.getConversionService();
        this.introspection = introspection;
        final SerdeConfig.SerCreatorMode creatorMode = introspection
            .getConstructor().getAnnotationMetadata()
            .enumValue(Creator.class, "mode", SerdeConfig.SerCreatorMode.class)
            .orElse(null);
        delegating = creatorMode == SerdeConfig.SerCreatorMode.DELEGATING;
        hasBuilder = introspection.hasBuilder();
        final Argument<?>[] constructorArguments = hasBuilder ? introspection.builder().getBuildMethodArguments() : introspection.getConstructorArguments();
        creatorSize = constructorArguments.length;
        PropertyNamingStrategy entityPropertyNamingStrategy = getPropertyNamingStrategy(introspection, decoderContext, defaultPropertyNamingStrategy);

        Set<String> ignoredProperties = new HashSet<>();
        Set<String> externalProperties = new HashSet<>();

        @Nullable
        Predicate<String> allowPropertyPredicate = serdeArgumentConf == null ? null : serdeArgumentConf.resolveAllowPropertyPredicate(false);

        // Replicating Jackson behaviour: @JsonIncludeProperties will ignore any not-included properties
        boolean hasIncludedProperties = serdeArgumentConf != null && serdeArgumentConf.getIncluded() != null
            || introspection.isAnnotationPresent(SerdeConfig.SerIncluded.class);
        this.ignoreUnknown = hasIncludedProperties || introspection.booleanValue(SerdeConfig.SerIgnored.class, SerdeConfig.SerIgnored.IGNORE_UNKNOWN)
            .orElse(decoderContext.getDeserializationConfiguration().orElse(defaultDeserializationConfiguration).isIgnoreUnknown());
        final PropertiesBag.Builder<T> creatorPropertiesBuilder = new PropertiesBag.Builder<>(introspection, constructorArguments.length);

        BeanMethod<T, Object> jsonValueMethod = null;
        BeanProperty<T, Object> jsonValueProperty = introspection.getBeanProperties()
            .stream()
            .filter(m -> m.isAnnotationPresent(SerdeConfig.SerValue.class))
            .findFirst()
            .orElse(null);

        if (jsonValueProperty != null) {
            if (constructorArguments.length != 1) {
                throw new SerdeException("Cannot have multiple parameters for a json value constructor!");
            }
        }

        List<DerProperty<T, ?>> creatorUnwrapped = null;
        AnySetter anySetterValue = null;
        List<DerProperty<T, ?>> unwrappedProperties = null;
        for (int i = 0; i < constructorArguments.length; i++) {
            Argument<Object> constructorArgument = resolveArgument((Argument<Object>) constructorArguments[i]);
            final AnnotationMetadata annotationMetadata = resolveArgumentMetadata(introspection, constructorArgument, constructorArgument.getAnnotationMetadata());
            if (annotationMetadata.isAnnotationPresent(SerdeConfig.SerAnySetter.class)) {
                anySetterValue = new AnySetter(constructorArgument, i);
                continue;
            }

            SubtypeInfo propertySubtypeInfo = SubtypeInfo.createForProperty(annotationMetadata);
            if (propertySubtypeInfo != null && propertySubtypeInfo.discriminatorType() == SerdeConfig.SerSubtyped.DiscriminatorType.EXTERNAL_PROPERTY) {
                externalProperties.add(propertySubtypeInfo.discriminatorName());
            }

            PropertyNamingStrategy propertyNamingStrategy = getPropertyNamingStrategy(annotationMetadata, decoderContext, entityPropertyNamingStrategy);
            final String propertyName = resolveName(serdeArgumentConf, constructorArgument, annotationMetadata, propertyNamingStrategy);

            boolean isIgnored = isIgnored(annotationMetadata) || (allowPropertyPredicate != null && !allowPropertyPredicate.test(propertyName));
            if (isIgnored) {
                ignoredProperties.add(propertyName);
            }

            Argument<Object> constructorWithPropertyArgument = Argument.of(
                constructorArgument.getType(),
                constructorArgument.getName(),
                annotationMetadata,
                constructorArgument.getTypeParameters()
            );
            final boolean isUnwrapped = annotationMetadata.hasAnnotation(SerdeConfig.SerUnwrapped.class);
            DeserBean<Object> unwrapped = null;
            if (isUnwrapped) {
                unwrapped = deserBeanRegistry.getDeserializableBean(
                    serdeArgumentConf == null ? constructorArgument : serdeArgumentConf.extendArgumentWithPrefixSuffix(constructorArgument),
                    decoderContext
                );
            }
            DerProperty<T, Object> derProperty = new DerProperty<>(
                conversionService,
                introspection,
                i,
                propertyName,
                constructorWithPropertyArgument,
                isIgnored ? null : introspection.getProperty(propertyName)
                    .or(() -> introspection.getProperty(constructorArgument.getName()))
                    .orElse(null),
                null,
                unwrapped,
                null,
                isIgnored
            );
            if (isUnwrapped) {
                if (creatorUnwrapped == null) {
                    creatorUnwrapped = new ArrayList<>();
                }
                creatorUnwrapped.add(derProperty);
            }
            creatorPropertiesBuilder.register(propertyName, derProperty, true);
        }

        this.creatorParams = creatorPropertiesBuilder.build();

        if (hasBuilder) {
            PropertiesBag.Builder<T> readPropertiesBuilder = new PropertiesBag.Builder<>(introspection);
            BeanIntrospection.Builder<T> builder = introspection.builder();
            @NonNull Argument<?>[] builderArguments = builder.getBuilderArguments();

            for (int i = 0; i < builderArguments.length; i++) {
                Argument<Object> builderArgument = (Argument<Object>) builderArguments[i];
                AnnotationMetadata annotationMetadata = builderArgument.getAnnotationMetadata();
                Optional<BeanProperty<T, Object>> matchingOuterProperty = introspection.getProperty(builderArgument.getName());
                PropertyNamingStrategy propertyNamingStrategy = getPropertyNamingStrategy(annotationMetadata, decoderContext, entityPropertyNamingStrategy);
                final String jsonProperty = resolveName(
                    builderArgument,
                    matchingOuterProperty
                        .map(outer -> List.of(annotationMetadata, outer.getAnnotationMetadata()))
                        .orElse(List.of(annotationMetadata)),
                    propertyNamingStrategy
                );
                final DerProperty<T, Object> derProperty = new DerProperty<>(
                    conversionService,
                    introspection,
                    i,
                    jsonProperty,
                    builderArgument,
                    null,
                    null,
                    null,
                    null,
                    false
                );
                readPropertiesBuilder.register(jsonProperty, derProperty, true);
            }
            injectProperties = readPropertiesBuilder.build();
        } else {
            final Collection<BeanMethod<T, Object>> beanMethods = introspection.getBeanMethods();
            final List<BeanMethod<T, Object>> jsonSetters = new ArrayList<>(beanMethods.size());
            BeanMethod<T, Object> anySetter = null;
            for (BeanMethod<T, Object> method : beanMethods) {
                if (method.isAnnotationPresent(SerdeConfig.SerSetter.class)) {
                    jsonSetters.add(method);
                } else if (method.isAnnotationPresent(SerdeConfig.SerAnySetter.class) && ArrayUtils.isNotEmpty(method.getArguments())) {
                    anySetter = method;
                } else if (method.isAnnotationPresent(SerdeConfig.SerValue.class) && ArrayUtils.isEmpty(method.getArguments())) {
                    jsonValueMethod = method;
                }
            }

            if (anySetterValue == null) {
                anySetterValue = (anySetter != null ? new AnySetter((BeanMethod<Object, ?>) anySetter) : null);
            }

            Collection<BeanWriteProperty<T, Object>> beanProperties = introspection.getBeanWriteProperties();
            if (!beanProperties.isEmpty() || !jsonSetters.isEmpty()) {
                PropertiesBag.Builder<T> readPropertiesBuilder = new PropertiesBag.Builder<>(introspection);
                int i = -1;
                for (BeanWriteProperty<T, Object> beanProperty : beanProperties) {
                    final AnnotationMetadata annotationMetadata = beanProperty.getAnnotationMetadata();
                    final String propertyName = resolveName(
                        serdeArgumentConf,
                        beanProperty,
                        annotationMetadata,
                        getPropertyNamingStrategy(annotationMetadata, decoderContext, entityPropertyNamingStrategy)
                    );
                    SubtypeInfo propertySubtypeInfo = SubtypeInfo.createForProperty(annotationMetadata);
                    if (propertySubtypeInfo != null && propertySubtypeInfo.discriminatorType() == SerdeConfig.SerSubtyped.DiscriminatorType.EXTERNAL_PROPERTY) {
                        externalProperties.add(propertySubtypeInfo.discriminatorName());
                    }
                    if (creatorParams != null && creatorParams.propertyIndexOf(propertyName) != -1) {
                        continue;
                    }
                    if (isIgnored(beanProperty) || allowPropertyPredicate != null && !allowPropertyPredicate.test(propertyName)) {
                        ignoredProperties.add(propertyName);
                        continue;
                    }
                    i++;

                    // Remove any ignored conflicting properties
                    ignoredProperties.remove(propertyName);

                    if (annotationMetadata.isAnnotationPresent(SerdeConfig.SerAnySetter.class)) {
                        anySetterValue = new AnySetter((BeanWriteProperty<Object, Object>) beanProperty);
                    } else {
                        final boolean isUnwrapped = annotationMetadata.hasAnnotation(SerdeConfig.SerUnwrapped.class);
                        final Argument<Object> propertyArgument = resolveArgument(beanProperty.asArgument());

                        DeserBean<Object> unwrapped = null;
                        if (isUnwrapped) {
                            unwrapped = deserBeanRegistry.getDeserializableBean(
                                serdeArgumentConf == null ? propertyArgument : serdeArgumentConf.extendArgumentWithPrefixSuffix(propertyArgument),
                                decoderContext
                            );
                        }

                        final DerProperty<T, Object> derProperty = new DerProperty<>(
                            conversionService,
                            introspection,
                            i,
                            propertyName,
                            propertyArgument,
                            beanProperty,
                            null,
                            unwrapped,
                            null,
                            false
                        );
                        if (isUnwrapped) {
                            if (unwrappedProperties == null) {
                                unwrappedProperties = new ArrayList<>();
                            }
                            unwrappedProperties.add(derProperty);
                        }
                        readPropertiesBuilder.register(propertyName, derProperty, true);
                    }
                }

                for (BeanMethod<T, Object> jsonSetter : jsonSetters) {
                    i++;
                    PropertyNamingStrategy propertyNamingStrategy = getPropertyNamingStrategy(jsonSetter.getAnnotationMetadata(), decoderContext, entityPropertyNamingStrategy);
                    final String property = resolveName(serdeArgumentConf,
                        new AnnotatedElement() {
                            @Override
                            public String getName() {
                                return NameUtils.getPropertyNameForSetter(jsonSetter.getName());
                            }

                            @Override
                            public AnnotationMetadata getAnnotationMetadata() {
                                return jsonSetter.getAnnotationMetadata();
                            }
                        },
                        jsonSetter.getAnnotationMetadata(),
                        propertyNamingStrategy
                    );
                    final Argument<Object> argument = resolveArgument((Argument<Object>) jsonSetter.getArguments()[0]);
                    final DerProperty<T, Object> derProperty = new DerProperty<>(
                        conversionService,
                        introspection,
                        i,
                        property,
                        argument,
                        null,
                        jsonSetter,
                        null,
                        null,
                        false
                    );
                    readPropertiesBuilder.register(property, derProperty, true);
                }
                injectProperties = readPropertiesBuilder.build();
            } else {
                injectProperties = null;
            }
        }
        this.injectPropertiesSize = injectProperties == null ? 0 : injectProperties.getDerProperties().size();
        this.wrapperProperty = introspection.stringValue(SerdeConfig.class, SerdeConfig.WRAPPER_PROPERTY).orElse(null);

        this.anySetter = anySetterValue;

        //noinspection unchecked
        this.creatorUnwrapped = creatorUnwrapped != null ? creatorUnwrapped.toArray(new DerProperty[0]) : null;
        //noinspection unchecked
        this.unwrappedProperties = unwrappedProperties != null ? unwrappedProperties.toArray(new DerProperty[0]) : null;

        SubtypeInfo subtypeInfoBase = serdeArgumentConf == null ? SubtypeInfo.createForType(introspection) : serdeArgumentConf.getSubtypeInfo();
        subtypeInfo = subtypeInfoBase == null ? DeserializeSubtypeInfo.create(SubtypeInfo.createForType(introspection), introspection, decoderContext, deserBeanRegistry) : DeserializeSubtypeInfo.create(subtypeInfoBase, introspection, decoderContext, deserBeanRegistry);

        String discriminatorProperty = introspection.stringValue(SerdeConfig.class, SerdeConfig.TYPE_PROPERTY).orElse(null);
        if (discriminatorProperty != null && !introspection.booleanValue(SerdeConfig.class, SerdeConfig.TYPE_PROPERTY_VISIBLE).orElse(false)) {
            ignoredProperties.add(discriminatorProperty);
        }
        boolean allowIgnoredProperties = introspection.booleanValue(SerdeConfig.SerIgnored.class, SerdeConfig.SerIgnored.ALLOW_DESERIALIZE).orElse(false);
        if (!allowIgnoredProperties && serdeArgumentConf != null && serdeArgumentConf.getIgnored() != null) {
            ignoredProperties.addAll(
                Arrays.asList(
                    serdeArgumentConf.getIgnored()
                )
            );
        }
        if (ignoredProperties.isEmpty()) {
            this.ignoredProperties = null;
        } else {
            this.ignoredProperties = ignoredProperties;
        }
        if (externalProperties.isEmpty()) {
            this.externalProperties = null;
        } else {
            this.externalProperties = externalProperties;
        }

        isJsonValueProperty = jsonValueMethod != null || jsonValueProperty != null;

        simpleBean = isSimpleBean();
        recordLikeBean = isRecordLikeBean();
    }

    void initialize(ReentrantLock lock, Deserializer.DecoderContext decoderContext) throws SerdeException {
        // Double check locking
        if (!initialized) {
            lock.lock();
            try {
                if (!initialized && !initializing) {
                    initializing = true;
                    initializeInternal(decoderContext);
                    initialized = true;
                    initializing = false;
                }
            } finally {
                lock.unlock();
            }
        }
    }

    private void initializeInternal(Deserializer.DecoderContext decoderContext) throws SerdeException {
        if (injectProperties != null) {
            for (DerProperty<T, Object> property : injectProperties.getProperties()) {
                initProperty(property, decoderContext);
            }
        }
        if (creatorParams != null) {
            for (DerProperty<T, Object> property : creatorParams.getProperties()) {
                initProperty(property, decoderContext);
            }
        }
        if (anySetter != null) {
            anySetter.deserializer = anySetter.valueType.equalsType(Argument.OBJECT_ARGUMENT) ? null : findDeserializer(decoderContext, anySetter.valueType);
        }
        if (unwrappedProperties != null) {
            for (DerProperty<T, Object> unwrappedProperty : unwrappedProperties) {
                initProperty(unwrappedProperty, decoderContext);
            }
        }
    }

    private boolean isSimpleBean() {
        if (isJsonValueProperty || ignoredProperties != null || externalProperties != null || delegating || subtypeInfo != null || creatorParams != null || creatorUnwrapped != null || unwrappedProperties != null || anySetter != null) {
            return false;
        }
        if (injectProperties != null) {
            for (DerProperty<T, Object> property : injectProperties.getProperties()) {
                if (property.isAnySetter || property.views != null || property.managedRef != null || introspection != property.introspection || property.backRef != null || property.beanProperty == null) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean isRecordLikeBean() {
        if (isJsonValueProperty || ignoredProperties != null || externalProperties != null || delegating || subtypeInfo != null || injectProperties != null || creatorUnwrapped != null || unwrappedProperties != null || anySetter != null) {
            return false;
        }
        if (creatorParams != null) {
            for (DerProperty<T, Object> property : creatorParams.getProperties()) {
                if (property.beanProperty != null || property.isAnySetter || property.views != null || property.managedRef != null || introspection != property.introspection || property.backRef != null) {
                    return false;
                }
            }
        }
        return true;
    }

    private void initProperty(DerProperty<T, Object> property, Deserializer.DecoderContext decoderContext) throws SerdeException {
        if (!property.ignored) {
            property.deserializer = findDeserializer(decoderContext, property.argument);
        }
    }

    private PropertyNamingStrategy getPropertyNamingStrategy(AnnotationMetadata annotationMetadata,
                                                             Deserializer.DecoderContext decoderContext,
                                                             PropertyNamingStrategy defaultNamingStrategy) throws SerdeException {
        Class<? extends PropertyNamingStrategy> namingStrategyClass = annotationMetadata.classValue(SerdeConfig.class, SerdeConfig.RUNTIME_NAMING)
            .orElse(null);
        return namingStrategyClass == null ? defaultNamingStrategy : decoderContext.findNamingStrategy(namingStrategyClass);
    }

    private <A> Argument<A> resolveArgument(Argument<A> argument) {
        if (argument instanceof GenericPlaceholder || argument.hasTypeVariables()) {
            Map<String, Argument<?>> bounds = this.bounds;
            if (!bounds.isEmpty()) {
                return resolveArgument(argument, bounds);
            }
        }
        return argument;
    }

    @SuppressWarnings("unchecked")
    private <A> Argument<A> resolveArgument(Argument<A> argument, Map<String, Argument<?>> bounds) {
        Argument<?>[] declaredParameters = argument.getTypeParameters();
        if (argument instanceof GenericPlaceholder<A> gp) {
            Argument<?> resolved = bounds.get(gp.getVariableName());
            if (resolved != null) {
                return (Argument<A>) Argument.of(
                    resolved.getType(),
                    argument.getName(),
                    argument.getAnnotationMetadata(),
                    resolveParameters(bounds, resolved.getTypeParameters())
                );
            }
            Argument<?>[] typeParameters = resolveParameters(bounds, declaredParameters);
            if (typeParameters != declaredParameters) {
                return Argument.ofTypeVariable(
                    argument.getType(),
                    argument.getName(),
                    gp.getVariableName(),
                    gp.getAnnotationMetadata(),
                    typeParameters
                );
            }
        } else {
            Argument<?>[] typeParameters = resolveParameters(bounds, declaredParameters);
            if (typeParameters != declaredParameters) {
                return Argument.of(
                    argument.getType(),
                    argument.getName(),
                    argument.getAnnotationMetadata(),
                    typeParameters
                );
            }
        }
        return argument;
    }

    private Argument<?>[] resolveParameters(Map<String, Argument<?>> bounds, Argument<?>[] typeParameters) {
        if (ArrayUtils.isEmpty(typeParameters)) {
            return typeParameters;
        }
        Argument<?>[] resolvedParameters = new Argument[typeParameters.length];
        boolean differ = false;
        for (int i = 0; i < typeParameters.length; i++) {
            Argument<?> typeParameter = typeParameters[i];
            Argument<?> resolved = resolveArgument(typeParameter, bounds);
            if (resolved != typeParameter) {
                resolvedParameters[i] = resolved;
                differ = true;
            } else {
                resolvedParameters[i] = typeParameter;
            }
        }
        return differ ? resolvedParameters : typeParameters;
    }

    private String resolveName(@Nullable SerdeArgumentConf serdeArgumentConf,
                               AnnotatedElement annotatedElement,
                               AnnotationMetadata annotationMetadata,
                               PropertyNamingStrategy namingStrategy) {
        String name = resolveName(annotatedElement, List.of(annotationMetadata), namingStrategy);
        if (serdeArgumentConf != null) {
            return serdeArgumentConf.applyPrefixSuffix(name);
        }
        return name;
    }

    private String resolveName(AnnotatedElement annotatedElement, List<AnnotationMetadata> annotationMetadata, PropertyNamingStrategy namingStrategy) {
        for (AnnotationMetadata metadataElement : annotationMetadata) {
            Optional<String> serde = metadataElement.stringValue(SerdeConfig.class, SerdeConfig.PROPERTY);
            if (serde.isPresent()) {
                return serde.get();
            }
            Optional<String> jackson = metadataElement.stringValue(JK_PROP);
            if (jackson.isPresent()) {
                return jackson.get();
            }
        }
        if (namingStrategy != null) {
            return namingStrategy.translate(annotatedElement);
        }
        return annotatedElement.getName();
    }

    private static <T> Deserializer<T> findDeserializer(Deserializer.DecoderContext decoderContext, Argument<T> argument) throws SerdeException {
        Class customDeser = argument.getAnnotationMetadata().classValue(SerdeConfig.class, SerdeConfig.DESERIALIZER_CLASS).orElse(null);
        if (customDeser != null) {
            return decoderContext.findCustomDeserializer(customDeser).createSpecific(decoderContext, argument);
        }
        Class<T> deserializeAs = argument.getAnnotationMetadata().classValue(SerdeConfig.class, SerdeConfig.DESERIALIZE_AS).orElse(null);
        if (deserializeAs != null) {
            argument = Argument.of(
                deserializeAs,
                argument.getName(),
                argument.getAnnotationMetadata(),
                argument.getTypeParameters()
            );
        }
        return (Deserializer<T>) decoderContext.findDeserializer(argument).createSpecific(decoderContext, argument);
    }

    private boolean isIgnored(AnnotationMetadata annotationMetadata) {
        return annotationMetadata.booleanValue(SerdeConfig.class, SerdeConfig.READ_ONLY).orElse(false)
            || annotationMetadata.booleanValue(SerdeConfig.class, SerdeConfig.IGNORED).orElse(false)
            || annotationMetadata.booleanValue(SerdeConfig.class, SerdeConfig.IGNORED_DESERIALIZATION).orElse(false);
    }

    static final class AnySetter {
        // CHECKSTYLE:OFF
        final Argument<Object> valueType;
        private final BiConsumer<Object, Map<String, ?>> mapSetter;
        private final TriConsumer<Object, Object> valueSetter;

        // Null when DeserBean not initialized
        public Deserializer<?> deserializer;

        public final boolean constructorArgument;
        // CHECKSTYLE:ON

        private AnySetter(BeanMethod<Object, ?> anySetter) {
            final Argument<?>[] arguments = anySetter.getArguments();
            // if the argument length is 1 we are dealing with a map parameter
            // otherwise we are dealing with 2 parameter variant
            final boolean singleArg = arguments.length == 1;
            this.valueType = (Argument<Object>) (singleArg ? arguments[0].getTypeVariable("V").orElse(Argument.OBJECT_ARGUMENT) : arguments[1]);
            if (singleArg) {
                this.valueSetter = null;
                this.mapSetter = anySetter::invoke;
            } else {
                this.valueSetter = anySetter::invoke;
                this.mapSetter = null;
            }
            constructorArgument = false;
        }

        private AnySetter(BeanWriteProperty<Object, Object> anySetter) {
            // if the argument length is 1 we are dealing with a map parameter
            // otherwise we are dealing with 2 parameter variant
            this.valueType = (Argument<Object>) anySetter.asArgument().getTypeVariable("V").orElse(Argument.OBJECT_ARGUMENT);
            this.mapSetter = anySetter::set;
            this.valueSetter = null;
            this.constructorArgument = false;
        }

        private AnySetter(Argument<Object> anySetter, int index) {
            // if the argument length is 1 we are dealing with a map parameter
            // otherwise we are dealing with 2 parameter variant
            this.valueType = (Argument<Object>) anySetter.getTypeVariable("V").orElse(Argument.OBJECT_ARGUMENT);
            this.mapSetter = (o, map) -> ((Object[]) o)[index] = map;
            this.valueSetter = null;
            this.constructorArgument = true;
        }

        void bind(Map<String, Object> values, Object object) {
            if (values != null) {
                if (mapSetter != null) {
                    mapSetter.accept(object, values);
                } else if (valueSetter != null) {
                    for (String s : values.keySet()) {
                        valueSetter.accept(object, s, values.get(s));
                    }
                }
            }
        }
    }

    private interface TriConsumer<T, V> {
        void accept(T t, String k, V v);
    }

    /**
     * Models a deserialization property.
     *
     * @param <B> The bean type
     * @param <P> The property type
     */
    @Internal
    // CHECKSTYLE:OFF
    public static final class DerProperty<B, P> {
        public final BeanIntrospection<B> introspection;
        public final int index;
        public final Argument<P> argument;
        @Nullable
        public final P defaultValue;
        public final boolean mustSetField;
        public final boolean explicitlyRequired;
        public final boolean nonNull;
        public final boolean nullable;
        public final boolean isAnySetter;
        @Nullable
        public final Class<?>[] views;
        @Nullable
        public final String[] aliases;

        @Nullable
        public final UnsafeBeanWriteProperty<B, P> beanProperty;

        public final DeserBean<P> unwrapped;
        public final DerProperty<?, ?> unwrappedProperty;
        public final String managedRef;
        public final String backRef;
        public final boolean ignored;

        // Null when DeserBean not initialized
        public Deserializer<P> deserializer;

        DerProperty(ConversionService conversionService,
                    BeanIntrospection<B> introspection,
                    int index,
                    String property,
                    Argument<P> argument,
                    @Nullable BeanWriteProperty<B, P> beanProperty,
                    @Nullable BeanMethod<B, P> beanMethod,
                    @Nullable DeserBean<P> unwrapped,
                    @Nullable DerProperty<?, ?> unwrappedProperty,
                    boolean ignored) throws SerdeException {
            this(conversionService,
                introspection,
                index,
                property,
                argument,
                argument.getAnnotationMetadata(),
                beanProperty,
                beanMethod,
                unwrapped,
                unwrappedProperty,
                ignored
            );
        }

        DerProperty(ConversionService conversionService,
                    BeanIntrospection<B> introspection,
                    int index,
                    String property,
                    Argument<P> argument,
                    AnnotationMetadata argumentMetadata,
                    @Nullable BeanWriteProperty<B, P> beanProperty,
                    @Nullable BeanMethod<B, P> beanMethod,
                    @Nullable DeserBean<P> unwrapped,
                    @Nullable DerProperty<?, ?> unwrappedProperty,
                    boolean ignored) throws SerdeException {
            this.introspection = introspection;
            this.index = index;
            this.argument = argument;
            this.ignored = ignored;
            Class<?> type = argument.getType();
            this.mustSetField = argument.isNonNull() || type.equals(Optional.class)
                || type.equals(OptionalLong.class)
                || type.equals(OptionalDouble.class)
                || type.equals(OptionalInt.class);
            this.nonNull = argument.isNonNull();
            this.nullable = argument.isNullable();
            if (beanProperty != null) {
                this.beanProperty = (UnsafeBeanWriteProperty<B, P>) beanProperty;
            } else if (beanMethod != null) {
                this.beanProperty = new BeanMethodAsBeanProperty<>(property, beanMethod);
            } else {
                this.beanProperty = null;
            }
            // compute default
            AnnotationMetadata annotationMetadata = resolveArgumentMetadata(introspection, argument, argumentMetadata);
            this.views = SerdeAnnotationUtil.resolveViews(introspection, annotationMetadata);

            try {
                this.defaultValue = annotationMetadata
                    .stringValue(Bindable.class, "defaultValue")
                    .map(s -> conversionService.convertRequired(s, argument))
                    .orElse(null);
            } catch (ConversionErrorException e) {
                throw new SerdeException((index > -1 ? "Constructor Argument" : "Property") + " [" + argument + "] of type [" + introspection.getBeanType().getName() + "] defines an invalid default value", e);
            }
            this.unwrapped = unwrapped;
            this.unwrappedProperty = unwrappedProperty;
            this.isAnySetter = annotationMetadata.isAnnotationPresent(SerdeConfig.SerAnySetter.class);
            final String[] aliases = annotationMetadata.stringValues(SerdeConfig.class, SerdeConfig.ALIASES);
            if (ArrayUtils.isNotEmpty(aliases)) {
                this.aliases = ArrayUtils.concat(aliases, property);
            } else {
                this.aliases = null;
            }
            this.managedRef = annotationMetadata.stringValue(SerdeConfig.SerManagedRef.class)
                .orElse(null);
            this.backRef = annotationMetadata.stringValue(SerdeConfig.SerBackRef.class)
                .orElse(null);
            this.explicitlyRequired = annotationMetadata.booleanValue(SerdeConfig.class, SerdeConfig.REQUIRED)
                .orElse(false);
        }

        public void setDefaultPropertyValue(Deserializer.DecoderContext decoderContext, @NonNull B bean) throws SerdeException {
            if (explicitlyRequired) {
                throw new SerdeException("Unable to deserialize type [" + introspection.getBeanType().getName() + "]. Required property [" + argument +
                    "] is not present in supplied data");
            }
            P value = provideDefaultValue(decoderContext);
            if (value != null) {
                beanProperty.setUnsafe(bean, value);
            }
        }

        public void setDefaultConstructorValue(Deserializer.DecoderContext decoderContext, @NonNull Object[] params) throws SerdeException {
            if (explicitlyRequired) {
                throw new SerdeException("Unable to deserialize type [" + introspection.getBeanType().getName() + "]. Required constructor parameter [" + argument + "] at index [" + index + "] is not present or is null in the supplied data");
            }
            params[index] = provideDefaultValue(decoderContext, mustSetField || argument.isPrimitive());
        }

        public void set(@NonNull Deserializer.DecoderContext decoderContext, @NonNull B obj, @Nullable P value) throws SerdeException {
            if (value == null) {
                setDefaultPropertyValue(decoderContext, obj);
            } else {
                beanProperty.setUnsafe(obj, value);
            }
        }

        public void deserializeAndSetConstructorValue(Decoder objectDecoder, Deserializer.DecoderContext decoderContext, Object[] values) throws IOException {
            try {
                values[index] = deserializeValue(objectDecoder, decoderContext);
            } catch (InvalidFormatException e) {
                throw new InvalidPropertyFormatException(e, argument);
            } catch (Exception e) {
                throw new SerdeException("Error decoding property [" + argument + "] of type [" + introspection.getBeanType() + "]: " + e.getMessage(), e);
            }
        }

        @NextMajorVersion("Receiving a null value for a primitive or a non-null should produce an expection")
        public void deserializeAndSetPropertyValue(Decoder objectDecoder, Deserializer.DecoderContext decoderContext, B beanInstance) throws IOException {
            try {
                P value = deserializeValue(objectDecoder, decoderContext);
                if (value != null || nullable) {
                    beanProperty.setUnsafe(beanInstance, value);
                }
            } catch (InvalidFormatException e) {
                throw new InvalidPropertyFormatException(e, argument);
            } catch (Exception e) {
                throw new SerdeException("Error decoding property [" + argument + "] of type [" + introspection.getBeanType() + "]: " + e.getMessage(), e);
            }
        }

        public void deserializeAndCallBuilder(Decoder objectDecoder, Deserializer.DecoderContext decoderContext, BeanIntrospection.Builder<B> builder) throws IOException {
            try {
                P value = deserializeValue(objectDecoder, decoderContext);
                if (value != null || nullable) {
                    builder.with(index, argument, value);
                }
            } catch (InvalidFormatException e) {
                throw new InvalidPropertyFormatException(e, argument);
            } catch (Exception e) {
                throw new SerdeException("Error decoding property [" + argument + "] of type [" + introspection.getBeanType() + "]: " + e.getMessage(), e);
            }
        }

        private P deserializeValue(Decoder objectDecoder, Deserializer.DecoderContext decoderContext) throws IOException {
            P value = deserializer.deserializeNullable(objectDecoder, decoderContext, argument);
            if (value != null || nullable) {
                return value;
            }
            if (explicitlyRequired) {
                throw new SerdeException("Unable to deserialize type [" + introspection.getBeanType().getName() + "]. Required property [" + argument +
                        "] is not present in supplied data");
            }
            return provideDefaultValue(decoderContext);
        }

        private P provideDefaultValue(Deserializer.DecoderContext decoderContext) {
            return provideDefaultValue(decoderContext, mustSetField);
        }

        private P provideDefaultValue(Deserializer.DecoderContext decoderContext, boolean mustSetField) {
            P value = defaultValue;
            if (value == null && mustSetField) {
                value = deserializer.getDefaultValue(decoderContext, argument);
            }
            return value;
        }

    }

    private static <B, P> AnnotationMetadata resolveArgumentMetadata(BeanIntrospection<B> introspection, Argument<P> argument, AnnotationMetadata annotationMetadata) {
        // records store metadata in the bean property
        final AnnotationMetadata propertyMetadata = introspection.getProperty(argument.getName(), argument.getType())
            .map(BeanProperty::getAnnotationMetadata)
            .orElse(AnnotationMetadata.EMPTY_METADATA);
        return new AnnotationMetadataHierarchy(propertyMetadata, annotationMetadata);
    }

    private static final class BeanMethodAsBeanProperty<B, P> implements UnsafeBeanWriteProperty<B, P> {

        private final String name;
        private final BeanMethod<B, P> beanMethod;
        private final Argument<P> argument;
        private final Class<P> type;

        public BeanMethodAsBeanProperty(String name, BeanMethod<B, P> beanMethod) {
            this.name = name;
            this.beanMethod = beanMethod;
            this.argument = (Argument<P>) beanMethod.getArguments()[0];
            this.type = argument.getType();
        }

        @Override
        public B withValueUnsafe(B bean, P value) {
            throw new IllegalStateException("Not supported");
        }

        @Override
        public void setUnsafe(B bean, P value) {
            beanMethod.invoke(bean, value);
        }

        @Override
        public BeanIntrospection<B> getDeclaringBean() {
            return null;
        }

        @Override
        public B withValue(@NonNull B bean, @Nullable P value) {
            setUnsafe(bean, value);
            return bean;
        }

        @Override
        public void set(@NonNull B bean, @Nullable P value) {
            setUnsafe(bean, value);
        }

        @Override
        public Class<P> getType() {
            return type;
        }

        @Override
        public Argument<P> asArgument() {
            return argument;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public AnnotationMetadata getAnnotationMetadata() {
            return beanMethod.getAnnotationMetadata();
        }
    }

}
