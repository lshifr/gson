package com.google.gson;

import com.google.gson.internal.$Gson$Preconditions;

import java.lang.reflect.Method;
import java.lang.reflect.Type;

/**
 * Created by apple on 03.12.17.
 */
public final class GetterSetterMethodAttributes {

    private final Method method;
    private final Type fieldType;

    public GetterSetterMethodAttributes(Method method){
        $Gson$Preconditions.checkNotNull(method);
        this.method = method;
        if(isGetter()){
            this.fieldType = method.getGenericReturnType();
        } else if (isSetter()){
            Type [] setterArgTypes =  method.getGenericParameterTypes();
            if(setterArgTypes.length != 1){
                throw new IllegalArgumentException("Setter should take exactly one argument. " +
                        "Received setter method  " + method.getName() +
                        "which takes " + setterArgTypes.length + " arguments"
                );
            }
            this.fieldType = setterArgTypes[0];
        } else {
            throw new IllegalArgumentException(
                    "Expected getter or setter method. Received method "+ method.getName()
            );
        }
    }

    public boolean isGetter(){
        String methodName = method.getName();
        return methodName.substring(0, 2).equals("is") || methodName.substring(0, 3).equals("get");
    }

    public boolean isSetter(){
        String methodName = method.getName();
        return methodName.substring(0, 3).equals("set");
    }

    public String getVirtualFieldName(){
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

    public Type getDeclaredVirtualFieldType(){
        return this.fieldType;
    }
}
