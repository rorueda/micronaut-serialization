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
package io.micronaut.serde.serializers;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

import io.micronaut.context.annotation.Factory;
import io.micronaut.core.convert.exceptions.ConversionErrorException;
import io.micronaut.core.type.Argument;
import io.micronaut.core.util.ArrayUtils;
import io.micronaut.core.util.CollectionUtils;
import io.micronaut.serde.Encoder;
import io.micronaut.serde.Serializer;
import io.micronaut.serde.exceptions.SerdeException;
import jakarta.inject.Singleton;

/**
 * Factory class for core serializers.
 */
@Factory
public class CoreSerializers {

    /**
     * A serializer for all instances of {@link java.lang.CharSequence}.
     *
     * @return A char sequence serializer
     */
    @Singleton
    protected Serializer<CharSequence> charSequenceSerializer() {
        return new Serializer<CharSequence>() {
            @Override
            public void serialize(Encoder encoder,
                                  EncoderContext context,
                                  CharSequence value,
                                  Argument<? extends CharSequence> type) throws IOException {
                encoder.encodeString(value.toString());
            }

            @Override
            public boolean isEmpty(CharSequence value) {
                return value == null || value.length() == 0;
            }
        };
    }

    /**
     * A serializer for all instances of {@link java.lang.Character}.
     *
     * @return A Character serializer
     */
    @Singleton
    protected Serializer<Character> charSerializer() {
        return (encoder, context, value, type) -> encoder.encodeChar(value);
    }

    /**
     * A serializer for all instances of {@link java.lang.Boolean}.
     *
     * @return A boolean serializer
     */
    @Singleton
    protected Serializer<Boolean> booleanSerializer() {
        return (encoder, context, value, type) -> encoder.encodeBoolean(value);
    }

    /**
     * A serializer for all instances of {@link java.lang.Iterable}.
     *
     * @param <T> The element type
     * @return An iterable serializer
     */
    @Singleton
    protected <T> Serializer<Iterable<T>> iterableSerializer() {
        return new Serializer<Iterable<T>>() {
            @Override
            public void serialize(Encoder encoder,
                                  EncoderContext context,
                                  Iterable<T> value,
                                  Argument<? extends Iterable<T>> type) throws IOException {
                final Encoder childEncoder = encoder.encodeArray();
                final Argument[] generics = type.getTypeParameters();
                if (ArrayUtils.isEmpty(generics)) {
                    throw new SerdeException("Serializing raw iterables is not supported for value: " + value);
                }
                final Argument<T> generic = (Argument<T>) generics[0];
                final Serializer<T> componentSerializer = (Serializer<T>) context.findSerializer(generic);
                for (T t : value) {
                    if (t == null) {
                        encoder.encodeNull();
                        continue;
                    }
                    componentSerializer.serialize(
                            childEncoder,
                            context,
                            t,
                            generic
                    );
                }
                childEncoder.finishStructure();
            }

            @Override
            public boolean isEmpty(Iterable<T> value) {
                if (value == null) {
                    return true;
                }
                if (value instanceof Collection) {
                    return ((Collection<T>) value).isEmpty();
                } else {
                    return !value.iterator().hasNext();
                }
            }
        };
    }

    /**
     * A serializer for all instances of {@link java.util.Optional}.
     *
     * @param <T> The optional type
     * @return An Optional serializer
     */
    @Singleton
    protected <T> Serializer<Optional<T>> optionalSerializer() {
        return new OptionalSerializer<>();
    }

    /**
     * A serializer for maps.
     *
     * @param <K>  The key type
     * @param <V>  The value type
     * @return A bit decimal serializer
     */
    @Singleton
    protected <K, V> Serializer<Map<K, V>> mapSerializer() {
        return new Serializer<Map<K, V>>() {
            @Override
            public void serialize(Encoder encoder,
                                  EncoderContext context,
                                  Map<K, V> value,
                                  Argument<? extends Map<K, V>> type) throws IOException {
                final Encoder childEncoder = encoder.encodeObject();
                final Argument[] generics = type.getTypeParameters();
                if (ArrayUtils.isEmpty(generics) || generics.length != 2) {
                    throw new SerdeException("Serializing raw maps is not supported for value: " + value);
                }
                final Argument<K> keyGeneric  = (Argument<K>) generics[0];
                final Argument<V> valueGeneric = (Argument<V>) generics[1];
                final Serializer<V> valSerializer = (Serializer<V>) context.findSerializer(valueGeneric);
                for (K k : value.keySet()) {
                    // relies on the key type implementing toString() correctly
                    // perhaps we should supply conversion service
                    if (CharSequence.class.isAssignableFrom(keyGeneric.getType())) {
                        childEncoder.encodeKey(k.toString());
                    } else {
                        try {
                            final String result = context.getConversionService().convertRequired(
                                    k,
                                    Argument.STRING
                            );
                            childEncoder.encodeKey(result != null ? result : k.toString());
                        } catch (ConversionErrorException e) {
                            throw new SerdeException("Error converting Map key [" + k + "] to String: " + e.getMessage(), e);
                        }
                    }
                    final V v = value.get(k);
                    if (v == null) {
                        childEncoder.encodeNull();
                    } else {
                        valSerializer.serialize(
                                encoder,
                                context,
                                v,
                                valueGeneric
                        );
                    }
                }
                childEncoder.finishStructure();
            }

            @Override
            public boolean isEmpty(Map<K, V> value) {
                return CollectionUtils.isEmpty(value);
            }
        };
    }

}
