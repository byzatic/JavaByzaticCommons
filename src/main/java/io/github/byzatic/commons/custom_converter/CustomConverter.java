/*
 *    Copyright 2021 Konstantin Fedorov
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package io.github.byzatic.commons.custom_converter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class CustomConverter {
    private final static Logger logger = LoggerFactory.getLogger(CustomConverter.class);

    public static <T, U> U parse(T source, Class<U> destinationClass) throws DataConvertException {
        List<Field> removableSourceFields = new ArrayList<>();
        if(source == null)
            return null;

        logger.trace("destinationClass: name= {}, enum= {}", destinationClass.getCanonicalName(), destinationClass.isEnum());
        logger.trace("source: name= {}, enum= {}", source.getClass().getCanonicalName(), source.getClass().isEnum());

        if (destinationClass.isEnum() && source.getClass().isEnum()) {
            try {
                Method method = destinationClass.getMethod("valueOf", String.class);
                method.setAccessible(true);
                return (U) method.invoke(null, ((Enum) source).name());
            } catch (InvocationTargetException | NoSuchMethodException | IllegalAccessException e) {
                throw new DataConvertException("Can't call enum's 'valueOf' method", e);
            }
        } else if (source instanceof Integer || source instanceof Double ||
                source instanceof Float || source instanceof Boolean ||
                source instanceof Long || source instanceof Character ||
                source instanceof Short || source instanceof Byte){
            return (U)source;
        }
        U destination;
        try {
            Constructor<U> constructor = destinationClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            destination = constructor.newInstance();
        } catch (NoSuchMethodException | InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new DataConvertException("Can't call constructor with 0 parameters for " + destinationClass.getCanonicalName(), e);
        }
        if (destination instanceof String) {
            return (U) source.toString();
        }

        List<Field> sourceFields = new ArrayList<>(Arrays.asList(source.getClass().getDeclaredFields()));
        List<Field> destinationFields = new ArrayList<>(Arrays.asList(destinationClass.getDeclaredFields()));
        //Проход по всем аннотированным полям источника
        for (Field sourceField : sourceFields) {
            String sourceFieldName = getReflectableAnnotationName(sourceField);
            if (sourceFieldName == null) {
                continue;
            }

            setEqualField(source, destination, destinationFields, sourceField, sourceFieldName, removableSourceFields);
        }
        //Удаление всех использованных полей в источнике
        sourceFields.removeAll(removableSourceFields);

        //Проход по всем не аннотированным полям источника
        for (Field sourceField : sourceFields) {
            String sourceFieldName = sourceField.getName();

            setEqualField(source, destination, destinationFields, sourceField, sourceFieldName, removableSourceFields);
        }

        return destination;
    }

    private static <T, U> void setEqualField(T source, U destination, List<Field> destinationFields, Field sourceField, String sourceFieldName, List<Field> removableSourceFields) {
        for (Field destinationField : destinationFields) {
            String destinationFieldName = getReflectableAnnotationName(destinationField);
            if (destinationFieldName != null && destinationFieldName.equals(sourceFieldName)) {
                setDestinationField(source, destination, destinationFields, sourceField, destinationField, removableSourceFields);
                return;
            }
        }
        for (Field destinationField : destinationFields) {
            if (destinationField.getName().equals(sourceFieldName)) {
                setDestinationField(source, destination, destinationFields, sourceField, destinationField, removableSourceFields);
                return;
            }
        }
    }

    private static <T, U> void setDestinationField(T source, U destination, List<Field> destinationFields, Field sourceField, Field destinationField, List<Field> removableSourceFields) {
        sourceField.setAccessible(true);
        destinationField.setAccessible(true);
        Object sourceFieldObject = null;
        try {
            sourceFieldObject = sourceField.get(source);
        } catch (IllegalAccessException e) {
            logger.warn("", e);
        }
        if (sourceFieldObject == null)
            return;

        if (sourceFieldObject instanceof List) {
            Class<?> destinationFieldGenericClass = null;
            try {
                destinationFieldGenericClass = Class.forName(destinationField.getGenericType()
                        .getTypeName()
                        .replace("java.util.List<", "")
                        .replace(">", ""));
            } catch (ClassNotFoundException e) {
                logger.warn("", e);
            }
            List list = new ArrayList<>();
            for (Object obj : (List<?>) sourceFieldObject) {

                try {
//                    System.out.println(obj + " " + destinationFieldGenericClass);
                    list.add(parse(obj, destinationFieldGenericClass));
                } catch (DataConvertException e) {
                    logger.warn("", e);
                }
            }
            sourceFieldObject = list;
        } else if (!destinationField.getType().equals(sourceField.getType())) {
            try {
//                System.out.println("1" + destinationField.getType() + " 2" + sourceField.getType());
                sourceFieldObject = parse(sourceFieldObject, destinationField.getType());
            } catch (DataConvertException e) {
                logger.warn("", e);
            }
        }

//        System.out.println("3" + destinationField.getType());
        try {
            destinationField.set(destination, sourceFieldObject);
        } catch (IllegalAccessException e) {
            logger.warn("", e);
        }

        destinationFields.remove(destinationField);
        removableSourceFields.add(sourceField);
    }

    private static String getReflectableAnnotationName(Field field) {
        field.setAccessible(true);
        Reflectable annotation = field.getAnnotation(Reflectable.class);
        return annotation == null ? null : annotation.name();
    }
}
