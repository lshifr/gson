/*
 * Copyright (C) 2011 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.gson.internal.bind;

import com.google.gson.*;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;
import com.google.gson.internal.*;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.*;

/**
 * Type adapter that reflects over the fields and methods of a class.
 */
public final class ReflectiveTypeAdapterFactory implements TypeAdapterFactory {
    private final ConstructorConstructor constructorConstructor;
    private final FieldNamingStrategy fieldNamingPolicy;
    private final Excluder excluder;
    private final JsonAdapterAnnotationTypeAdapterFactory jsonAdapterFactory;

    public ReflectiveTypeAdapterFactory(ConstructorConstructor constructorConstructor,
                                        FieldNamingStrategy fieldNamingPolicy, Excluder excluder,
                                        JsonAdapterAnnotationTypeAdapterFactory jsonAdapterFactory) {
        this.constructorConstructor = constructorConstructor;
        this.fieldNamingPolicy = fieldNamingPolicy;
        this.excluder = excluder;
        this.jsonAdapterFactory = jsonAdapterFactory;
    }

    public boolean excludeField(Field f, boolean serialize) {
        return excludeField(f, serialize, excluder);
    }

    static boolean excludeField(Field f, boolean serialize, Excluder excluder) {
        return !excluder.excludeClass(f.getType(), serialize) && !excluder.excludeField(f, serialize);
    }

    boolean includeGetterOrSetter(Method m, boolean serialize) {
        return includeGetterOrSetter(m, serialize, excluder);
    }

    static boolean includeGetterOrSetter(Method m, boolean serialize, Excluder excluder) {
        return !excluder.excludeGetterOrSetter(m, serialize);
    }


    /**
     * first element holds the default name
     */
    private List<String> getFieldNames(Field f) {
        SerializedName annotation = f.getAnnotation(SerializedName.class);
        if (annotation == null) {
            String name = fieldNamingPolicy.translateName(f);
            return Collections.singletonList(name);
        }

        String serializedName = annotation.value();
        String[] alternates = annotation.alternate();
        if (alternates.length == 0) {
            return Collections.singletonList(serializedName);
        }

        List<String> fieldNames = new ArrayList<String>(alternates.length + 1);
        fieldNames.add(serializedName);
        for (String alternate : alternates) {
            fieldNames.add(alternate);
        }
        return fieldNames;
    }

    @Override
    public <T> TypeAdapter<T> create(Gson gson, final TypeToken<T> type) {
        Class<? super T> raw = type.getRawType();

        if (!Object.class.isAssignableFrom(raw)) {
            return null; // it's a primitive!
        }

        ObjectConstructor<T> constructor = constructorConstructor.get(type);
        return new Adapter<T>(constructor, getBoundFields(gson, type, raw));
    }


    private Map<String, BoundField> getBoundFields(Gson context, TypeToken<?> type, Class<?> raw) {
        Map<String, BoundField> result = new LinkedHashMap<String, BoundField>();
        if (raw.isInterface()) {
            return result;
        }

        Type declaredType = type.getType();
        while (raw != Object.class) {
            Field[] fields = raw.getDeclaredFields();
            for (Field field : fields) {
                boolean serialize = excludeField(field, true);
                boolean deserialize = excludeField(field, false);
                if (!serialize && !deserialize) {
                    continue;
                }
                field.setAccessible(true);
                Type fieldType = $Gson$Types.resolve(type.getType(), raw, field.getGenericType());
                List<String> fieldNames = getFieldNames(field);
                BoundField previous = null;
                for (int i = 0, size = fieldNames.size(); i < size; ++i) {
                    String name = fieldNames.get(i);
                    if (i != 0) serialize = false; // only serialize the default name
                    GetterSetterSerializedField wrapped = new GetterSetterSerializedField(
                            name, fieldType, context, field
                    );
                    BoundField boundField = wrapped.createBoundField(serialize, deserialize);
                    BoundField replaced = result.put(name, boundField);
                    if (previous == null) previous = replaced;
                }
                if (previous != null) {
                    throw new IllegalArgumentException(declaredType
                            + " declares multiple JSON fields named " + previous.name);
                }
                // Serialization of "virtual" fields, defined by getters only
                // TODO: support deserialization
                if (context.useGetterSetter()) {
                    Method[] getters = GetterSetterMethodAttributes.GetterSetterReflectionHelper.filterVirtualFieldMethods(
                            GetterSetterMethodAttributes.GetterSetterReflectionHelper.getAllGetters(raw, true)
                    );
                    for (Method getter : getters) {
                        serialize = includeGetterOrSetter(getter, true);
                        deserialize = includeGetterOrSetter(getter, false);
                        if (!serialize && !deserialize) {
                            continue;
                        }
                        GetterSetterSerializedVirtualField wrapped = new GetterSetterSerializedVirtualField(
                            getter, context
                        );
                        result.put(wrapped.name, wrapped.createBoundField(serialize, deserialize));
                    }
                }
            }
            type = TypeToken.get($Gson$Types.resolve(type.getType(), raw, raw.getGenericSuperclass()));
            raw = type.getRawType();
        }
        return result;
    }


    abstract class GeneralizedField {
        final String name;
        final Type fieldType;
        final TypeToken<?> typeToken;
        final Gson context;

        GeneralizedField(String name, Type fieldType, Gson context) {
            this.name = name;
            this.fieldType = fieldType;
            this.typeToken = TypeToken.get(fieldType);
            this.context = context;
        }

        abstract TypeAdapter<?> getTypeAdapter();

        abstract Object getValue(Object instance) throws IllegalAccessException;

        abstract void setValue(Object instance, Object value) throws IllegalAccessException;

        abstract boolean isRuntimeWrapped();

        ReflectiveTypeAdapterFactory.BoundField createBoundField(boolean serialize, boolean deserialize) {
            return new ReflectiveTypeAdapterFactory.BoundField(name, serialize, deserialize) {

                @Override
                boolean writeField(Object value) throws IOException, IllegalAccessException {
                    if (!serialized) return false;
                    Object fieldValue = getValue(value);
                    return fieldValue != value; // avoid recursion for example for Throwable.cause
                }

                @SuppressWarnings({"unchecked", "rawtypes"}) // the type adapter and field type always agree
                @Override
                void write(JsonWriter writer, Object instance) throws IOException, IllegalAccessException {
                    TypeAdapter t = isRuntimeWrapped() ? getTypeAdapter()
                            : new TypeAdapterRuntimeTypeWrapper(context, getTypeAdapter(), typeToken.getType());
                    t.write(writer, getValue(instance));
                }

                @Override
                void read(JsonReader reader, Object instance) throws IOException, IllegalAccessException {
                    Object fieldValue = getTypeAdapter().read(reader);
                    final boolean isPrimitive = Primitives.isPrimitive(typeToken.getRawType());
                    if (fieldValue != null || !isPrimitive) {
                        setValue(instance, fieldValue);
                    }
                }
            };

        }
    }

    class StandardField extends GeneralizedField {

        private final TypeAdapter<?> typeAdapter;
        private final boolean jsonAdapterPresent;
        protected final Field field;

        StandardField(String name, Type fieldType, Gson context, Field field) {
            super(name, fieldType, context);
            JsonAdapter annotation = field.getAnnotation(JsonAdapter.class);
            TypeAdapter<?> mapped = null;
            if (annotation != null) {
                mapped = jsonAdapterFactory.getTypeAdapter(
                        constructorConstructor, context, this.typeToken, annotation);
            }
            jsonAdapterPresent = mapped != null;
            if (mapped == null) mapped = context.getAdapter(this.typeToken);
            typeAdapter = mapped;
            this.field = field;
        }


        @Override
        TypeAdapter<?> getTypeAdapter() {
            return this.typeAdapter;
        }

        @Override
        Object getValue(Object instance) throws IllegalAccessException {
            return field.get(instance);
        }

        @Override
        void setValue(Object instance, Object value) throws IllegalAccessException {
            field.set(instance, value);
        }

        @Override
        boolean isRuntimeWrapped() {
            return jsonAdapterPresent;
        }
    }

    class GetterSetterSerializedField extends StandardField {

        private final boolean useGetterSetter;

        GetterSetterSerializedField(String name, Type fieldType, Gson context, Field field) {
            super(name, fieldType, context, field);
            this.useGetterSetter = context.useGetterSetter();
        }

        @Override
        Object getValue(Object instance) throws IllegalAccessException {
            if (!useGetterSetter || !GetterSetterMethodAttributes.GetterSetterReflectionHelper.fieldHasGetter(field)) {
                return super.getValue(instance);
            } else {
                String getterName = GetterSetterMethodAttributes.GetterSetterReflectionHelper.getGetterName(field);
                try {
                    Method method = instance.getClass().getMethod(getterName);
                    return method.invoke(instance);
                } catch (NoSuchMethodException | InvocationTargetException ignored) {
                    // TODO: revisit this
                    return super.getValue(instance);
                }
            }
        }

        @Override
        void setValue(Object instance, Object value) throws IllegalAccessException {
            if (!useGetterSetter) {
                super.setValue(instance, value);
            } else {
                String setterName = GetterSetterMethodAttributes.GetterSetterReflectionHelper.getSetterName(field);
                try {
                    Method method = instance.getClass().getMethod(setterName, field.getType());
                    method.invoke(instance, value);
                } catch (NoSuchMethodException | InvocationTargetException e) {
                    // TODO: revisit this
                    super.setValue(instance, value);
                }
            }
        }
    }

    class GetterSetterSerializedVirtualField extends GeneralizedField {

        private Method method;

        GetterSetterSerializedVirtualField(Method method, Gson context) {
            this(GetterSetterMethodAttributes.GetterSetterReflectionHelper.fieldNameFromGetterOrSetter(method),
                    new GetterSetterMethodAttributes(method).getDeclaredVirtualFieldType(), context);
            $Gson$Preconditions.checkArgument( // Only support getters for now
                    GetterSetterMethodAttributes.GetterSetterReflectionHelper.isGetter(method)
            );
            this.method = method;
        }

        GetterSetterSerializedVirtualField(String name, Type fieldType, Gson context) {
            super(name, fieldType, context);
        }

        @Override
        TypeAdapter<?> getTypeAdapter() {
            return context.getAdapter(this.typeToken);
        }

        @Override
        Object getValue(Object instance) throws IllegalAccessException {
            try {
                return method.invoke(instance);
            } catch (InvocationTargetException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        void setValue(Object instance, Object value) throws IllegalAccessException {
            throw new UnsupportedOperationException("Setting values for virtual fields not yet supported");
        }

        @Override
        boolean isRuntimeWrapped() {
            return false; // TODO: Revisit this
        }
    }


    static abstract class BoundField {
        final String name;
        final boolean serialized;
        final boolean deserialized;

        protected BoundField(String name, boolean serialized, boolean deserialized) {
            this.name = name;
            this.serialized = serialized;
            this.deserialized = deserialized;
        }

        abstract boolean writeField(Object value) throws IOException, IllegalAccessException;

        abstract void write(JsonWriter writer, Object value) throws IOException, IllegalAccessException;

        abstract void read(JsonReader reader, Object value) throws IOException, IllegalAccessException;
    }

    public static final class Adapter<T> extends TypeAdapter<T> {
        private final ObjectConstructor<T> constructor;
        private final Map<String, BoundField> boundFields;

        Adapter(ObjectConstructor<T> constructor, Map<String, BoundField> boundFields) {
            this.constructor = constructor;
            this.boundFields = boundFields;
        }

        @Override
        public T read(JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) {
                in.nextNull();
                return null;
            }

            T instance = constructor.construct();

            try {
                in.beginObject();
                while (in.hasNext()) {
                    String name = in.nextName();
                    BoundField field = boundFields.get(name);
                    if (field == null || !field.deserialized) {
                        in.skipValue();
                    } else {
                        field.read(in, instance);
                    }
                }
            } catch (IllegalStateException e) {
                throw new JsonSyntaxException(e);
            } catch (IllegalAccessException e) {
                throw new AssertionError(e);
            }
            in.endObject();
            return instance;
        }

        @Override
        public void write(JsonWriter out, T value) throws IOException {
            if (value == null) {
                out.nullValue();
                return;
            }

            out.beginObject();
            try {
                for (BoundField boundField : boundFields.values()) {
                    if (boundField.writeField(value)) {
                        out.name(boundField.name);
                        boundField.write(out, value);
                    }
                }
            } catch (IllegalAccessException e) {
                throw new AssertionError(e);
            }
            out.endObject();
        }
    }
}
