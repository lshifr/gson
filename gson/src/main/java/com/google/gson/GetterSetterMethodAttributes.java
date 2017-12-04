package com.google.gson;

import com.google.gson.internal.$Gson$Preconditions;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Created by apple on 03.12.17.
 */
public final class GetterSetterMethodAttributes {

    private final Method method;
    private final Type fieldType;

    public GetterSetterMethodAttributes(Method method) {
        $Gson$Preconditions.checkNotNull(method);
        this.method = method;
        if (isGetter()) {
            this.fieldType = method.getGenericReturnType();
        } else if (isSetter()) {
            Type[] setterArgTypes = method.getGenericParameterTypes();
            if (setterArgTypes.length != 1) {
                throw new IllegalArgumentException("Setter should take exactly one argument. " +
                        "Received setter method  " + method.getName() +
                        "which takes " + setterArgTypes.length + " arguments"
                );
            }
            this.fieldType = setterArgTypes[0];
        } else {
            throw new IllegalArgumentException(
                    "Expected getter or setter method. Received method " + method.getName()
            );
        }
    }

    public boolean isGetter() {
        return GetterSetterReflectionHelper.isGetter(method.getName());
    }

    public boolean isSetter() {
        return GetterSetterReflectionHelper.isSetter(method.getName());
    }

    public String getVirtualFieldName() {
        String methodName = method.getName();
        if (methodName.substring(0, 2).equals("is")) {
            return methodName.substring(2, 3).toLowerCase() + methodName.substring(3);
        } else if (methodName.substring(0, 3).equals("get") || methodName.substring(0, 3).equals("set")) {
            return methodName.substring(3, 4).toLowerCase() + methodName.substring(4);
        } else {
            throw new IllegalStateException();
        }
    }

    public Class<?> getDeclaringClass() {
        return method.getDeclaringClass();
    }

    public Type getDeclaredVirtualFieldType() {
        return this.fieldType;
    }

    public static class GetterSetterReflectionHelper {
        public static String getGetterName(Field field) {
            String fieldName = field.getName();
            String getterPrefix = field.getType() == boolean.class || field.getType() == Boolean.class ? "is" : "get";
            return getterPrefix + fieldName.substring(0, 1).toUpperCase() + fieldName.substring(1);
        }

        public static String getSetterName(Field field) {
            return "set" + field.getName().substring(0, 1).toUpperCase() + field.getName().substring(1);
        }

        public static boolean isGetter(String methodName) {
            return methodName.substring(0, 2).equals("is") || methodName.substring(0, 3).equals("get");
        }

        public static boolean isGetter(Method method) {
            return isGetter(method.getName());
        }

        public static boolean isSetter(String methodName) {
            return methodName.substring(0, 3).equals("set");
        }

        public static boolean isSetter(Method method) {
            return isSetter(method.getName());
        }

        public static boolean classHasPublicField(Class<?> cls, String fieldName){
            return classHasField(cls, fieldName, true);
        }

        public static boolean classHasField(Class<?> cls, String fieldName){
            return classHasField(cls, fieldName, false);
        }

        public static boolean classHasField(Class<?> cls, String fieldName, boolean publicOnly){
            try{
                if(publicOnly){
                    cls.getField(fieldName);
                } else {
                    cls.getDeclaredField(fieldName);
                }
                return true;
            } catch (NoSuchFieldException e) {
                return false;
            } catch (SecurityException e) {
                return false;
            }
        }

        public static boolean classOrParentHasField(Class<?> cls, String fieldName){
            if(cls == null){
                return false;
            }
            if(classHasField(cls, fieldName, false)){
                return true;
            }
            return classHasField(cls.getSuperclass(), fieldName);
        }

        public static boolean fieldHasGetter(Field field) {
            String getterName = getGetterName(field);
            Class<?> cls = field.getDeclaringClass();
            try {
                cls.getMethod(getterName);
                return true;
            } catch (NoSuchMethodException e) {
                return false;
            } catch (SecurityException e) {
                return false;
            }
        }

        public static boolean fieldHasSetter(Field field) {
            String setterName = getSetterName(field);
            Class<?> cls = field.getDeclaringClass();
            try {
                cls.getMethod(setterName, Object.class); //TODO: make sure Object works here
                return true;
            } catch (NoSuchMethodException e) {
                return false;
            } catch (SecurityException e) {
                return false;
            }
        }

        public static Method[] getAllGetters(Class cls, boolean declaredOnly) {
            Set<Method> getters = new HashSet<>();
            Method[] methods = declaredOnly ? cls.getDeclaredMethods() : cls.getMethods();
            for (Method m : methods) {
                if (isGetter(m)) {
                    getters.add(m);
                }
            }
            return getters.toArray(new Method[]{});
        }

        public static Method[] getAllSetters(Class cls, boolean declaredOnly) {
            Set<Method> setters = new HashSet<>();
            Method[] methods = declaredOnly ? cls.getDeclaredMethods() : cls.getMethods();
            for (Method m : methods) {
                if (isSetter(m)) {
                    setters.add(m);
                }
            }
            return setters.toArray(new Method[]{});
        }

        /**
         * Returns an array of Method objects, keeping only the methods (getters, setters)
         * for which no actual field with the corresponding name exists
         *
         * @param methods
         * @return
         */
        public static Method[] filterVirtualFieldMethods(Method[] methods){
            List<Method> vms = new ArrayList<>();
            for (Method m: methods){
                Class<?> cls = m.getDeclaringClass();
                if(!classOrParentHasField(cls, fieldNameFromGetterOrSetter(m))){
                    vms.add(m);
                }
            }
            return vms.toArray(new Method[]{});
        }

        public static String fieldNameFromGetterOrSetter(Method getter) {
            String methodName = getter.getName();
            if (methodName.substring(0, 2).equals("is")) {
                return methodName.substring(2, 3).toLowerCase() + methodName.substring(3);
            } else if (methodName.substring(0, 3).equals("get") || methodName.substring(0, 3).equals("set")) {
                return methodName.substring(3, 4).toLowerCase() + methodName.substring(4);
            } else {
                throw new IllegalArgumentException("The passed method with the name " + methodName + "is not a getter");
            }
        }
    }
}
